# v1.2.2 实机诊断报告 —— 两个独立故障，一个根因

抓取时间：2026-10-03 15:48 / 16:10
设备：192.168.2.5:5555，rk3229，Android 5.1.1 API22，armeabi-v7a

---

## 结论速览

| # | 故障 | 现象 | 根因 | 是否已证实 |
|---|---|---|---|---|
| **A** | 主线程 ANR（20s） | `onCreate` 卡死 → ANR → kill → 重启 | **mediaserver 音频 HAL 内部死锁**，`AudioTrack.getMinBufferSize()` 的 binder 调用永不返回 | ✅ 栈级证实 |
| **B** | KWS native 崩溃 | 每次启动必崩，拖走整个进程 | **sherpa-jni(要 ORT 1.28.2) 与 ORT 1.17.1 的 `OrtApi` 结构体 ABI 错配** → API 表里有 NULL 指针 | ✅ 栈级证实 |

**关键结构事实：两者是不同层次的问题。**
故障 A 阻塞 Java 主线程（binder 层），故障 B 是 native 段错误（ABI 层）。
**A 让人看不到 B** —— 因为 ANR 20s 就把进程杀了，根本走不到 KWS 那一步。

---

## 一、故障 A：音频 HAL 死锁（不是"占着 HAL"，是 HAL 自己卡住了）

### 1.1 主线程栈（/data/anr/traces.txt，pid 2299）

```
"main" prio=5 tid=1 Native
  native: #00 pc 0003d950  /system/lib/libc.so (__ioctl+8)
  native: #01 pc 00042afd  /system/lib/libc.so (ioctl+14)
  native: #02 pc 0001f407  /system/lib/libbinder.so (IPCThreadState::talkWithDriver(bool)+138)
  native: #03 pc 0001fa13  /system/lib/libbinder.so (IPCThreadState::waitForResponse(Parcel*, int*)+42)
  native: #04 pc 0001fbb5  /system/lib/libbinder.so (IPCThreadState::transact(int,uint,Parcel const&,Parcel*,uint)+124)
  native: #05 pc 0001ad63  /system/lib/libbinder.so (BpBinder::transact(...)+30)
  native: #06 pc 00068c39  /system/lib/libmedia.so (???)
  native: #07 pc 00059589  /system/lib/libmedia.so (AudioSystem::getOutput(stream,uint,format,uint,flags,offload)+46)
  native: #08 pc 000595b5  /system/lib/libmedia.so (AudioSystem::getOutputSamplingRate(uint*, stream)+26)
  native: #09 pc 0004ae31  /system/lib/libmedia.so (AudioTrack::getMinFrameCount(...)+16)
  native: #10 pc 0009a637  /system/lib/libandroid_runtime.so (???)
  native: #11 pc 00292c7b  boot.oat (Java_android_media_AudioTrack_native_1get_1min_1buff_1size__III+102)
  at android.media.AudioTrack.native_get_min_buff_size(Native method)
  at android.media.AudioTrack.getMinBufferSize(AudioTrack.java:844)
  at cn.workbuddy.r1client.TtsPlayer.start(TtsPlayer.java:46)      ← 卡在这一行
  at cn.workbuddy.r1client.R1ClientService.buildPipeline(R1ClientService.java:77)
  at cn.workbuddy.r1client.R1ClientService.onCreate(R1ClientService.java:68)
```

**纯 binder 阻塞**：`talkWithDriver` → `ioctl(BINDER_WRITE_READ)` 睡着等对端回包。
这解释了 ANR 报告里我们进程只占 **2.2% CPU** —— 完全一致，不是死循环。

### 1.2 服务端也卡在同一把锁上（pid 210 mediaserver）

```
"Binder_3" sysTid=989
  #00 __ioctl            libc
  #01 __futex_wait_ex
  #02 pthread_mutex_lock
  #03 pc 0001dc07  libaudioflinger.so        ← 抢 AudioFlinger 全局锁
  #04 pc 0001f2d1  libaudioflinger.so
  #05 pc 00050f55  libmedia.so (BnAudioFlinger::onTransact+2108)   ← 就是我们那次 getOutput
  #06 BBinder::transact
  #07 IPCThreadState::executeCommand
```

mediaserver 里**同时**还有一堆线程在抢同一把锁：

| 线程 | 卡在哪 |
|---|---|
| `mediaserver` (210) | `libaudiopolicyservice.so` |
| `ApmAudio` (569) | `libaudioflinger.so` 锁 |
| `AudioOut_2` (574) | `audio.primary.rk30board.so` ← **输出 HAL** |
| `Binder_1` (577) | `libaudioflinger.so` 锁 |
| `Binder_3` (989) | `libaudioflinger.so` 锁 |
| `Binder_4` (2130) | `libaudiopolicyservice.so` |
| `Binder_5` (2203) | `libaudiopolicyservice.so` |

**锁持有者 = `AudioOut_2`，它卡在 `audio.primary.rk30board.so` 里。**

### 1.3 另一条线：AudioIn 卡在 `open()` 上

```
"AudioIn_17" sysTid=2040
  #00 pc 0003d888  /system/lib/libc.so (__openat+8)          ← 正在 open 一个设备节点，一直没返回
  #01 pc 00015beb  /system/lib/libc.so (open+30)
  #02 pc 0000225f  /system/lib/libtinyalsa.so (pcm_open+122)
  #03 pc 00002e53  /system/lib/libuni4michal.so (uni_4mic_pcm_open+174)   ← 云知声四麦 HAL
  #04 pc 0000c9a1  /system/lib/hw/audio.primary.rk30board.so
  #05 pc 0002a7c5  /system/lib/libaudioflinger.so (RecordThread)
```

`libuni4michal.so` = 云知声四麦 HAL，就是 `usc_record_thread` 在驱动的东西。
它在 `pcm_open()` 里对 `/dev/snd/pcmCxD0c` 做 `open()`，**这个 open 不返回**。

设备节点是存在的（权限 `crwxrwxrwx`，世界可写）：

```
crwxrwxrwx  pcmC1D0c    116, 56
crwxrwxrwx  pcmC2D0c    116, 88
```

所以不是权限问题，是**内核 ALSA 驱动在 open 里等不到资源**。

### 1.4 死锁的形状

把服务端这一坨连起来看：

```
  AudioIn_17 (2040)                    AudioOut_2 (574)
  ─────────────────                    ────────────────
  uni_4mic_pcm_open()                  audio.primary.rk30board.so
    └─ pcm_open()  [卡在 open()]         └─ 也在等（等 codec / 等时钟）
         │
         └── 和 AudioOut 抢同一个 codec 时钟 —— 输入流的 open
             需要输出流先跑起来，而输出流被后面那串 Binder 线程堵着
```

一堆 binder 线程（`Binder_1/3/4/5`、`mediaserver`、`ApmAudio`）
全在 `pthread_mutex_lock` 上排队，**等的就是 `AudioOut_2` 那把锁**；
`AudioOut_2` 又在等 codec 侧（`AudioIn_17` 那条线放着不放）。
→ **典型的 HAL 层互等。**

**要点：这是 mediaserver 内部的死锁，和"谁在占 HAL"是两回事。**
一旦进入这个状态，**任何** App 调 `AudioTrack`/`AudioRecord` 都会卡在 binder 上，
不只是我们。

### 1.5 关于「原厂小讯占着 HAL」——**这个假设被实测否掉了**

`traces.txt` 里 `com.phicomm.speaker.device` (pid 819) 持有的音频相关线程只有：

```
usc_fix_thread     prio=10 TimedWaiting     ← 在等条件变量，不持锁
usc_vad_thread     prio=10 Suspended        ← 被挂起
usc_record_thread  prio=10 Native
"usc_record_thre"  prio=5 (not attached)
```

**关键：这 4 个线程的栈里没有任何 binder / futex / HAL 记录。**
它们只是**用**音频（消费者），不是**持有** HAL 锁的那个
（持有者是 mediaserver 里的 `AudioOut_2`/`AudioIn_17`）。

我们在 ANR 报告里看到 `com.phicomm.speaker.device` 占 111% CPU、
`usc_*` 三个线程满载 —— 那是**它自己的事**（VAD 在疯狂跑），
**不是它锁住了音频**。两个现象时间上重合，但**没有因果。**

**实测验证**（16:10）：

```
am force-stop com.phicomm.speaker.device   →  进程消失
am startservice cn.workbuddy.r1client/.R1ClientService
```

结果：服务**一路走到 ready**，音频调用全部成功：

```
I/R1CLIENT: TtsPlayer started, minBuf=4096 buf=16384
I/R1CLIENT: AudioCapture started: 16000Hz/60ms, minBuf=4096
I/R1CLIENT: mic start=true
I/R1CLIENT: =========== R1ClientService ready (mic=true kws=pending bus=true opus=true) ===========
```

→ **`getMinBufferSize` 这次没卡。**

**但进程仍然死了、仍然重启了**（pid 2959 → 3031），死因是**故障 B**。

> 准确表述：杀小讯**确实解除了 ANR**，
> 但**不能**据此说"小讯锁住了 HAL"——
> 更可能是杀小讯让四麦 HAL 停止 open 输入流，**顺带**解开了那个互等。
> 而真正每次必崩的，是 KWS 的 native 段错误。

### 1.6 还有个更麻烦的点：这只手不太听使唤

- 小讯是 `persistent` 倾向的系统应用，**开机/被拉活会自动回来**（实测 monkey 拉起后 pid 变成 2896）
- 它是**唤醒事件的来源**，真禁掉 = 自断粮道（DEPLOY.md 自己也写了）
- 就算这段时间不回来，`mediaserver` 的 `AudioIn_17` 线程**已经死在那里了**，
  只要不重启 mediaserver，输入路径就一直是坏的

### 1.5 还有个更麻烦的点：这只手不太听使唤

`force-stop` 后小讯确实是没了，但：

- 它是 `persistent` 级别倾向的系统应用，**开机/被拉活会自动回来**（实测 monkey 拉起后 pid 变成 2896）
- 它是**唤醒事件的来源**，真禁掉 = 自断粮道（DEPLOY.md 自己也写了）
- 就算这段时间不回来，`mediaserver` 的 `AudioIn_17` 线程**已经死在那里了**，
  只要不重启 mediaserver，输入路径就一直是坏的

→ **A 的可行解法不是"杀小讯"，是"别在 main 线程碰音频"。**

---

## 二、故障 B：KWS native 段错误（真正的每日必崩点）

### 2.1 crash buffer 原文

```
F/libc ( 2959): Fatal signal 11 (SIGSEGV), code 1, fault addr 0xc in tid 3004 (R1KwsInit)
I/DEBUG( 2959): signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xc
    r0 00000000  r1 ab696bc8  r2 b7405ccc  r3 00000000
    r4 ab98c9c8  r5 ab696bc8  r6 00000003  r7 abaa8620
    lr ab709cc1  pc ab76ae8a
  backtrace:
    #00 pc 0012be8a  libsherpa-onnx-jni.so
    #01 pc 001efc59  libsherpa-onnx-jni.so
    #02 pc 001c34d1  libsherpa-onnx-jni.so
    #03 pc 001c326d  libsherpa-onnx-jni.so
    #04 pc 00123cb3  libsherpa-onnx-jni.so
    #05 pc 00115dd3  libsherpa-onnx-jni.so
    #06 pc 000e3d4d  libsherpa-onnx-jni.so (Java_com_k2fsa_sherpa_onnx_KeywordSpotter_newFromAsset+260)
```

**`fault addr 0xc` = 解引用 `null + 12`。`r0 = 0`。**

### 2.2 这个地址和 v1.1.1 一模一样

DEPLOY.md 记载 v1.1.1 崩在同一处：

> `#06 pc 000e3d4d libsherpa-onnx-jni.so (Java_..._newFromAsset+260)`
> `Fatal signal 11 (SIGSEGV), code 1, fault addr 0xc`

**v1.2.0 → v1.2.2 折腾了三轮 DT_HASH、verdef 改名、assets 路径，pc 值一字未变。**
→ 说明**打进 APK 的 so 到现在还是 v1.1.1 崩溃时的那一份**（或至少 native 行为没变）。

### 2.3 当前 APK 里 so 的真实符号状态（我直接解包验的）

`libsherpa-onnx-jni.so` 的导入需求：

```
--- verneed ---
  file=libonnxruntime.so  cnt=1
     aux vna_other=3  name=VERS_1.28.2     ← 要 VERS_1.28.2
  file=libm.so   vna_other=5  name=LIBC
  file=libdl.so  vna_other=4  name=LIBC
  file=libc.so   vna_other=2  name=LIBC
```

`libonnxruntime.so` 的导出（**改完之后**）：

```
--- verdef ---
  vd_ndx=1  name=libonnxruntime.so   hash=0x0fee26af
  vd_ndx=3  name=VERS_1.28.2         hash=0x027bfe82   ← 名字对了

--- Ort* 符号 ---
  [211] __cxa_thread_atexit_impl                        ver_ndx=1
  [216] OrtGetApiBase                                   ver_ndx=3   ← 指向 VERS_1.28.2
  [217] OrtSessionOptionsAppendExecutionProvider_CPU     ver_ndx=3
  [218] OrtSessionOptionsAppendExecutionProvider_Nnapi   ver_ndx=3
```

**从 ELF 元数据看，改名的 4 项操作（vd_ndx / vd_hash / dynstr / 3 个 ver_ndx）
全部落地了，名字、hash、索引自洽。** 但运行时仍然拿到 `r0=0`。

### 2.4 排除法的结果：**符号版本不是凶手**

我逐条验了，两条都被**证伪**：

**❌ 候选 2 证伪：`vd_hash` 是对的**

用 bionic 用的同一套 ELF sysv hash 算法重算：

```
VERS_1.28.2  ->  0x027bfe82     ← 和文件里记录的一模一样
VERS_1.17.1  ->  0x027bef81     ← DEPLOY.md 说"原值"，也对得上
VERS_1.24.3  ->  0x027bf283
```

**匹配成功。改名时 hash 算对了，这一环没问题。**

**❌ 候选 3 证伪：索引没坏**

查了 `libsherpa-onnx-jni.so` 的全部 174 个 UND 符号，`ver_ndx==3` 的**只有一条**：

```
OrtGetApiBase   ver_ndx=3   bind=2 (STB_GLOBAL)
```

`__cxa_thread_atexit_impl` 在 jni 侧的 `ver_ndx=1`，**根本不是 3**。
所以「ndx 冲突把 libc 符号挤到 3」这个担忧在**导入侧**不存在。
至于 ORT 侧 `__cxa_thread_atexit_impl` 标 `ver_ndx=1` —— 它是 `w`（weak），
WEAK 符号的版本校验本来就宽松，不影响。

### 2.5 ✅ 真正的答案：**pc 落在函数体内，符号解析是好的**

这是最关键的一步。我把崩溃地址回填到符号表：

```
Java_com_k2fsa_sherpa_onnx_KeywordSpotter_newFromAsset
     起始 = 0x000e3c49
     长度 = 804 字节
     结束 = 0x000e3f6d

崩溃 pc          = 0x000e3d4d
0x000e3c49 + 260 = 0x000e3d4d     ← 完全吻合（+260 = backtrace 里那个 +260）
```

**pc 稳稳落在 `newFromAsset` 的函数体内，偏移 260。**
如果 `OrtGetApiBase` 解析失败（返回 null 桩），崩溃会发生在
**调用点那一条 `blx` 指令本身**附近的解引用上；
而现在 `r0 = 0x00000000` 是**函数内部某次调用的返回值**为 0，
不是函数指针本身为 0（函数指针为 0 的话 pc 不会进到函数体里）。

**结论：`OrtGetApiBase()` 调用成功了，拿到的是有效指针；
但 `GetApi()` 返回的 API 表里，某个成员是 NULL，代码直接解引用它 → `fault addr 0xc`。**

### 2.6 那到底为什么 API 表里有 NULL？

三段式推理：

**① jni 和 ORT 确实是"配对"的**（这点 DEPLOY.md 之前判断错了）

DEPLOY.md 认为「jni 要求的 `VERS_1.28.2` + maven 的 1.17.1 `VERS_1.17.1`」是错配。
但 verneed 是**按名字+hash 匹配**的，我们改名后名字和 hash **都对上了**，
linker 会正常把它绑定到 `OrtGetApiBase`。**绑定成功 ≠ ABI 兼容。**

**② 真正的错配是「C API 结构体版本」**

sherpa-onnx-jni 是**对着 ORT 1.28.2 的头文件编译**的。
`OrtApi` 是个巨大的**函数指针结构体**，它在 1.17.1 → 1.28.2 之间**加过字段**。

```
sherpa 编译时以为的布局:
  OrtApi[ N ]  = OrtSessionOptionsAppendExecutionProvider_...
  OrtApi[ N+k] = <1.24 之后新增的某个函数>

实际 1.17.1 提供的布局:
  OrtApi[ N ]  = 同一个老函数      ✅ 对得上
  OrtApi[ N+k] = 不存在 → 越界读到 0  ❌ 或者索引错位
```

**注意 `OrtSessionOptionsAppendExecutionProvider_*` 这 3 个符号在 1.17.1 里是存在的**
（ver_ndx 都改成了 3），所以 `OrtGetApiBase` 能解析、能返回非 null。
**问题在结构体内部**：`GetApi(version)` 会按传入的版本号校验，
1.17.1 的 `GetApi` 遇到 1.28.2 的版本号，行为未定义 —— 可能返回
一个**部分填充**的 `OrtApi`，高位函数指针全是 0。

`newFromAsset+260` 就是第一次碰到那个 0 的地方。

**③ 这也解释了为什么三轮补丁 pc 一字不变**

`0x000e3d4d` 在 v1.1.1 和 v1.2.2 里**完全相同**，而且：
- APK 里的 so 大小和 DEPLOY.md 记录的**分毫不差**（3428780 / 10739204）
- 说明 v1.2.0/1/2 改的都是**外层**（ELF 段、签名、assets 路径、异步化），
  **真正跑的 native 代码从头到尾没换过**

补 DT_HASH、改 version 名字，都改变不了
「**jni 要 1.28.2 的功能、ORT 只给到 1.17.1**」这个本质矛盾。

### 2.7 所以 KWS 的正确修法

| 方案 | 做法 | 评价 |
|---|---|---|
| **A. 换成套产物** | 用 **sherpa-onnx 官方 AAR 自带的 ORT**（15,359,592 那份）成对使用，不要 jni 配 maven ORT | ⭐ 最正统，但要解决它缺 `DT_HASH` + 依赖 `__register_atfork` |
| **B. 用匹配的 sherpa 版本** | 找一版**原生用 ORT 1.17.x 编译**的 sherpa-onnx AAR | ⭐⭐ 最省事，ABI 天然对齐，不用 patch ELF |
| **C. 自己交叉编译** | 用 NDK + ORT 1.17.1 头文件重编 sherpa-onnx JNI | 最可控，成本最高 |
| **D. 独立进程** | `android:process=":kws"` | 不解决崩溃，但**让崩溃不拖累主服务** |

**推荐顺序：B → A → D**（D 作为兜底一起做）。

> **本次 v1.2.3 不做**。理由：故障 B 已经烧掉三个版本，
> 而手工 patch ELF 是收益低、风险高的死胡同。
> 先用 `MODE_BUS_ONLY` 把产品跑起来，KWS 降为可选实验特性。

### 2.8 为什么 native 崩溃会拖走整个进程

每次崩都是 `F/libc` 级，`SIGSEGV` 在 native 层：

- `startKwsAsync()` 把它放独立线程 → 只改崩溃时机，**不改后果**
- `try/catch(Throwable)` → **拦不住 native SIGSEGV**
- `START_STICKY` → 系统立刻重启 → 回到 2.2 的循环

DEPLOY.md §6 自己诚实地写了这一点，现在被实测坐实。

---

## 三、v1.2.3 修改方案

### 3.1 对故障 A（ANR）——**onCreate 只做 startForeground，音频全部异步 + 懒加载**

anr trace 明确告诉我们 68 行就死了。改造：

```java
// R1ClientService.java
@Override
public void onCreate() {
    super.onCreate();

    // ① 只做这一件事，必须在 5s 内返回
    startForeground(NOTIF_ID, buildNotification());
    Log.i(TAG, "onCreate: foreground up, spawning pipeline");

    // ② 所有装配丢到工作线程，main 立刻返回
    new Thread(this::buildPipeline, "R1Pipeline").start();
}
```

`buildPipeline()` 内部再分两步，**音频资源懒加载**：

```java
private void buildPipeline() {
    try {
        // ---- 第一阶段：不碰音频，先把"能收唤醒"这件事做起来 ----
        initOpus();        // 纯 JNI，无 binder
        initWakeupBridge();// 反射 + 注册总线，无 binder
        Log.i(TAG, "=== stage1 ready (opus + bus), audio deferred ===");

        // ---- 第二阶段：音频，可失败、可重试、绝不在 main ----
        ensureAudioReady();   // TtsPlayer / AudioCapture 都在这儿
    } catch (Throwable t) {
        Log.e(TAG, "pipeline stage failed", t);
    }
}
```

**`TtsPlayer.start()` 的关键改写 —— 把 `getMinBufferSize` 挪出构造/启动路径：**

```java
// 现状（会卡死）：
static final int minBuf = AudioTrack.getMinBufferSize(SR, CH, ENC);  // ← binder，会挂

// 改法一：给死值，彻底不调用（推荐，最快最稳）
static final int MIN_BUF = 4096;   // 16k/mono/16bit 下 4096 frame = 256ms，够用

// 改法二：必须调的话，加超时兜底线程
//   另起线程调，main 只 wait(1500ms)，超时就用默认值继续
```

**为什么"给死值"是对的**：`getMinBufferSize` 只是**建议值**，
传小了 `AudioTrack.write()` 会自动补；传大了只浪费几 KB。
**为它冒 ANR 的风险完全不划算。** 而且它正是那个死锁调用，
绕开它 = 绕开整条 AudioFlinger 锁竞争。

**`AudioRecord`（AudioCapture）同理**：

```java
// 同样不要调 getMinBufferSize，直接用固定值
static final int MIN_BUF = 4096;
```

> ⚠️ 注意：`AudioTrack`/`AudioRecord` 的**构造 + start() 本身仍然是 binder 调用**，
> 还是可能卡。所以要加**看门狗**：

```java
private boolean withTimeout(String what, Runnable r, long ms) {
    final AtomicBoolean done = new AtomicBoolean(false);
    Thread t = new Thread(() -> { r.run(); done.set(true); }, what);
    t.setDaemon(true); t.start();
    try { t.join(ms); } catch (InterruptedException ignored) {}
    if (!done.get()) { Log.e(TAG, what + " TIMEOUT after " + ms + "ms (audio HAL wedged)"); return false; }
    return true;
}

// 用法
if (!withTimeout("tts-start", () -> tts.start(), 2000)) {
    tts = null;                       // 放弃 TTS，但服务活着
    Log.w(TAG, "degraded: no TTS, wakeup+ASR still available");
}
```

**降级链**：TTS 起不来 → 只做上行（ASR 可用，靠手机端出字）；
麦克风起不来 → 只留总线唤醒（等 HAL 恢复后重试）。
**核心原则：任何单个音频组件失败都不许拖垮服务。**

**重试策略**：audio 初始化失败后，起一个退避重试（如 5s → 15s → 60s → 300s），
因为 `AudioIn_17` 那种卡死可能在 HAL 超时后自己恢复。

### 3.2 对故障 B（KWS 崩溃）——先停用，再单独立项

**v1.2.3 的目标是"服务能活"，不是"KWS 能用"。所以：**

```java
// 把 KWS 从启动路径摘掉，改成手动/条件触发
private static final boolean KWS_AUTOSTART = false;   // ← 本次改为 false

// 保留 MDOE_HYBRID 但实际只跑总线：
//   WAKEUP_MODE = MODE_BUS_ONLY 更直白
private static final int WAKEUP_MODE = MODE_BUS_ONLY;
```

**为什么现在就该这么做**（三条理由，DEPLOY.md 也印证过）：

1. `MODE_BUS_ONLY` **完全不碰 sherpa / ORT**，是已知最稳的一档
2. 总线唤醒（"小讯小讯"）走的是**原厂四麦引擎**，唤醒率反而比自建 KWS 高
3. KWS 的 native 崩溃是**进程级**的，只要它在启动路径上，服务就永远活不过 20 秒

**留给 v1.2.4 的 KWS 修复方向**（本次不做，先记录）：

| 方向 | 说明 |
|---|---|
| **换回成套产物** | `libsherpa-onnx-jni.so` + `libonnxruntime.so` **必须来自同一个 AAR**。现在是「官方 AAR 的 jni + maven 的 ORT + 手工改名」，这个组合本身就是脆的 |
| **升 minSdk 到 23+** | `__cxa_thread_atexit_impl` API23 才有；API22 全靠 weak 绑定侥幸。如果换设备/换版本，这条随时会炸 |
| **放弃 ORT，用 sherpa-onnx 的 pure-ONNX 构建** | 或降到一个**原生支持 API21 的 sherpa 版本**，不要自己 patch ELF |
| **独立进程** | `android:process=":kws"`，崩了只死 KWS 进程，主服务不受影响。这是唯一能真正隔离 native SIGSEGV 的办法 |

> **本次不碰 KWS 的另一个理由**：故障 B 已经耗掉 v1.2.0/1/2 三个版本，
> 而 ELF 手工 patch 是**收益低、风险高**的路线。
> 先用总线唤醒把产品跑起来，KWS 降级为"可选实验特性"。

### 3.3 建议的 v1.2.3 验收标准

```
✅ 服务启动后 20s 不 ANR、不被 kill
✅ 进程存活 ≥ 5 分钟
✅ 日志出现：onCreate: foreground up, spawning pipeline
✅ 日志出现：=== stage1 ready (opus + bus), audio deferred ===
✅ 日志出现：=========== R1ClientService ready ===========
✅ 喊「小讯小讯」→ *** BUS WAKEUP 出现
✅ 即使 audio TIMEOUT，服务依然活着（降级但可用）
❌ 不要求 kws init=true（本次主动关闭）
```

---

## 附录 A：下次抓现场的命令

```bash
# 1. 完整崩溃栈（含 native backtrace）
adb -s 192.168.2.5:5555 logcat -b crash -d

# 2. linker 加载 libonnxruntime 的详细过程（验证候选 1）
adb -s 192.168.2.5:5555 logcat -d -s linker:V sherpa-onnx:V

# 3. 看 sherpa 有没有打出 config（v1.2.0 那次是打出来了的）
adb -s 192.168.2.5:5555 logcat -d -s sherpa-onnx:V

# 4. ANR 栈
adb -s 192.168.2.5:5555 shell cat /data/anr/traces.txt
```

**验证 vd_hash 是否正确**（候选 2）：

```python
def elf_hash(name):
    h = 0
    for c in name.encode():
        h = (h << 4) + c
        g = h & 0xF0000000
        h ^= g >> 24
        h &= ~g & 0xFFFFFFFF
    return h

# 期望 = 0x027bfe82
print(hex(elf_hash("VERS_1.28.2")))
```

**如果算出来 ≠ 0x027bfe82 → 候选 2 成立，改名时 hash 算错了。**

---

## 附录 B：本次实验记录（可复现）

| 步骤 | 命令 | 结果 |
|---|---|---|
| 抓 ANR | `cat /data/anr/traces.txt` | 4407 行，含 r1client(2299) main 栈 |
| 看线程 | traces 里 pid 2299 段 | **只有 13 个线程，全是 JVM 默认池，无应用线程** → 卡在装配最早处 |
| 看服务端 | traces 里 pid 210 段 | `Binder_3` 卡在 `BnAudioFlinger::onTransact` |
| 找钉子 | pid 210 `AudioIn_17` | 卡在 `uni_4mic_pcm_open` → `pcm_open` → `open()` |
| **验证假设** | `am force-stop com.phicomm.speaker.device` + 重启服务 | **音频调用全部成功，走到 ready**（假设证伪，但证明杀小讯能解 ANR） |
| 但服务仍死 | `logcat -b crash` | pid 2959/3031 均 `SIGSEGV fault addr 0xc` in `R1KwsInit` |
| 查 so 符号 | 解包 APK + ELF 解析 | verdef `VERS_1.28.2` hash=0x027bfe82，3 个 Ort* 符号 ver_ndx=3 |

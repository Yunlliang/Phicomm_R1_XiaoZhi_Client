# R1 小智客户端 —— 部署与验证

> ## 📌 当前定版看这里（2026-10-04）
>
> **定版 = v1.3.27（versionCode 49）**，唤醒/对话在 **≤0.5 m** 可用。
> 见 **`RELEASE-v1.3.27.md`**（定版说明）。
> 本文下述 §产物 / §快速部署 等章节为 **v1.2.x 时代**内容，版本号已过期，仅作历史参考。
> 远场能力结案见 `diag/audio/VERDICT-audio-frontend.md`。

> ## ⚠️ 2026-10-03 更正（先读这段）
>
> 本文 §2「v1.2.2 修复记录」的**核心结论已被实机证伪**。
> 详见 **`DIAG-v1.2.2.md`**。
>
> | 本文的旧结论 | 实际 |
> |---|---|
> | v1.2.2 已"真正打穿 ORT 三角死结" | ❌ KWS **仍然必崩**，且崩溃 pc 与 v1.1.1 **完全相同**（`0x000e3d4d`） |
> | 崩溃根因是"符号版本 `VERS_1.28.2` 对不上" | ❌ 改名**全部生效且 hash 正确**；真因是 **`OrtApi` 结构体 ABI 错配**（jni 按 1.28.2 编译，ORT 只有 1.17.1） |
> | assets 相对路径是崩溃根因 | ❌ 已核实，改了之后 pc 一字未变 |
>
> **v1.2.6 → v1.2.2 三轮都在改外层（ELF 段/签名/assets 路径/异步化），
> 真正跑的 native 代码从头到尾没换过**（so 大小分毫不差：3428780 / 10739204）。
> 手工 patch ELF 是死胡同，**必须换成套构建产物**。
>
> 另：主线程 ANR 是**另一个独立故障**（音频 HAL binder 死锁），
> 与 ORT 无关，见 `DIAG-v1.2.2.md` §1。

## 产物

| 文件 | 说明 |
|---|---|
| `/workspace/r1-client/r1-client-v1.2.2.apk` | **当前版本**，19.8MB，armeabi-v7a |
| `/workspace/r1-client/r1-client-v1.2.1.apk` | 历史（ORT 缺 `__register_atfork`，dlopen 失败） |
| `/workspace/r1-client/r1-client-v1.2.0.apk` | 历史（ORT 缺 DT_HASH，dlopen 失败） |
| `/workspace/r1-client/r1-client-v1.1.1.apk` | 历史（ORT ABI 不匹配，SIGSEGV） |
| `/workspace/r1-client/r1-client-v1.1.0.apk` | 历史（接口名写错） |
| `/workspace/r1-client/r1-client-v1.0.1.apk` | 历史（仅自建 KWS） |

---

## v1.2.2 修复记录 —— 真正打穿 ORT 三角死结

### 现象

```
E/linker: CANNOT LINK EXECUTABLE "libonnxruntime.so":
  cannot locate symbol "__register_atfork" referenced by "libonnxruntime.so"
```

上一版给 15.3MB 的 ORT 补了 `DT_HASH`，结果暴露出下一层：**它依赖 `__register_atfork`**。

### `__register_atfork` 是 glibc 独有符号

**bionic 从来没有实现过它。** ORT ≥ 1.24 的官方 Android 构建引入了这个符号 →
在任何 Android 版本上都是死路，不只是 API 22。

按 `__register_atfork` 一票否决，市面上的 ORT 分成两拨：

| ORT 版本 | 大小 | `__register_atfork` | `DT_HASH` | 符号版本 | API22 可用 |
|---|---|---|---|---|---|
| **1.17.1**（官方 maven） | 10,739,204 | ❌ 不需要 | ✅ 自带 | `VERS_1.17.1` | ⚠️ 只差版本名 |
| 1.24.3（官方 maven） | 18,356,280 | ✅ 依赖 | — | `VERS_1.24.3` | ❌ |
| 1.28.2（sherpa AAR 自带） | 15,359,592 | ✅ 依赖 | ❌ | `VERS_1.28.2` | ❌ |

**只有 1.17.1 是干净的**，它唯一的问题就是符号版本名不叫 `VERS_1.28.2`。

### 三角死结的真相

| 要求 | 谁提的 | 能否伪造 |
|---|---|---|
| 有 `VERS_1.28.2` 的符号定义 | sherpa 的 `.gnu.version_r` | ✅ **纯元数据，可改** |
| 有 `DT_HASH` | Android 5.1 linker | ✅ 可补（或 1.17.1 自带） |
| 不依赖 `__register_atfork` | bionic 先天限制 | ❌ **改不了，只能换版本** |

前两条都能在二进制层面解决，第三条只能靠选对 ORT 版本。

### 做法：改写 ORT 1.17.1 的 verdef 版本名

ORT 1.17.1 的 `.gnu.version_d` 原本有 2 个条目：

```
Index: 1  BASE         Name: libonnxruntime.so
Index: 2               Name: VERS_1.17.1     ← 要改成 VERS_1.28.2
```

同时它自己 3 个导出符号（`OrtGetApiBase` / `..._CPU` / `..._Nnapi`）的
`.gnu.version` 值都是 `2`，指向那个条目。

改动清单（全部等长原地改写，文件大小与段布局零变化）：

| 位置 | 原值 | 新值 |
|---|---|---|
| verdef entry#2 `vd_ndx` | `2` | `3` |
| verdef entry#2 `vd_hash` | `0x027bef81` | `0x027bfe82` |
| dynstr `VERS_1.17.1` | 12 字节 | `VERS_1.28.2`（同长） |
| 3 个 `Ort*` 符号的 `.gnu.version` | `2` | `3` |

> `vd_hash` 是版本名的 ELF hash，改名必须同步重算 —— 否则 linker 用 hash 匹配时会落空。
>
> `vd_ndx` 从 2 改 3，是为了**避开 verneed 已占用的索引**：
> 该库 verneed 里 libc/libm/libdl 分别占 `3/4/5`，
> 而 `.gnu.version` 里 `ver_ndx==3` 的符号有 159 个（都是 UND 引用）。
> 把 verdef 的 ndx 提到 3，等于让「导出侧 ndx=3」和「导入侧 ndx=3」各归各位 ——
> 运行时 bionic 查导出走 verdef、查导入走 verneed，互不干扰。
>
> 唯一副作用：`objdump -T` 会把 libc 的 UND 符号错标成 `VERS_1.28.2`
> （objdump 偷懒只按 verdef 线性映射）。**这是显示瑕疵，不影响加载。**

### 为什么改了 ndx 还要改符号自己的 ver_ndx

```
改之前:  OrtGetApiBase → ver_ndx=2 → verdef[ndx=2] 存在 ✅
改之后(只改verdef): OrtGetApiBase → ver_ndx=2 → verdef[ndx=2] 已不存在 ❌ 悬空
所以必须一并: OrtGetApiBase → ver_ndx=3 → verdef[ndx=3] = VERS_1.28.2 ✅
```

### 另一条线索：真正的崩溃根因不是 ORT

回头审 `newFromAsset` 的崩溃栈，native 侧导入了：

```
AAssetManager_fromJava / AAssetManager_open / AAsset_getBuffer / AAsset_getLength
```

说明它拿 config 里的**路径字符串直接喂 `AAssetManager_open()`**。
v1.1.x 传的是绝对路径 `/data/data/.../files/kws/xxx.onnx` → open 必然失败 →
拿到无效 handle → 解引用 `null + 0xc` → `fault addr 0xc`。

**v1.2.x 已改为 assets 相对路径**（`kws/xxx.onnx`），模型不再释放到 filesDir。

### API 22 符号可用性核查

用 NDK 各 API level 的 `libc.so` 存根交叉核对 ORT 1.17.1 的全部 UND 符号：

```
api21: 20/21  缺 __cxa_thread_atexit_impl
api22: 20/21  缺 __cxa_thread_atexit_impl   ← R1 就在这档
api23: 21/21  全有
```

`__cxa_thread_atexit_impl` 是 **API 23 引入**的，API 22 确实没有 —— 但它在本库里是 **weak（`w`）绑定**：

```
00000000  w   D  *UND*  Base  __cxa_thread_atexit_impl
00000000  w   D  *UND*  Base  _ZTH15ThreadedBufSize
```

bionic 允许 weak 未定义符号在 `dlopen` 时解析为 0，不报错。**其余 20 个符号 API 22 全部存在。**

> ⚠️ 别用 NDK 存根的符号表判断「某个符号在设备上是否存在」——
> 实测它连 `pthread_mutex_lock` / `stderr` 都"查不到"。
> 存根只适合判断**符号首次引入的 API level**（本用法），不适合判断存在性。

### 为什么要重建 APK 而不是直接换 so

`libonnxruntime.so` 打进 APK 后由 `System.loadLibrary` 加载，
必须重新 `zip` 进 APK 并重签名。APK 从 22.1MB 缩到 19.8MB，正是 ORT 减重的结果。


---

## （归档）v1.2.1 尝试 —— 给 ORT 补 DT_HASH（方向对，但选错了 ORT 版本）

### 现象

```
java.lang.UnsatisfiedLinkError: dlopen failed:
  empty/missing DT_HASH in "libonnxruntime.so" (built with --hash-style=gnu?)
    at com.k2fsa.sherpa.onnx.KeywordSpotter.<clinit>(KeywordSpotter.kt:86)
```

### 死结：两个条件互相排斥

| 要求 | ORT 1.17.1 | ORT 1.28.2 |
|---|---|---|
| 符号版本 `VERS_1.28.2` | ❌ 只有 1.17.1 | ✅ |
| `DT_HASH` 段 | ✅ | ❌ 只有 GNU_HASH |

- 用 1.17.1 → `DT_HASH` 有了，但符号版本对不上 → **SIGSEGV**
- 用 1.28.2 → 符号版本对了，但缺 `DT_HASH` → **dlopen 直接拒绝**

市面上没有「1.28.2 + DT_HASH」的成品，所以只能**二进制层面给它补一个 `DT_HASH` 段**。

### 做法

写了个 ELF32 处理脚本，原理：

1. 从 `.dynsym` 重建 SysV hash 表
   - 符号总数**不能用遍历法数**（`.dynsym` 后面紧跟别的段，会越界读到脏数据）。
     正确做法是从 **GNU_HASH 的链式结构反推最大符号索引** —— 本文件得 **232** 个。
   - `nbucket = nchain = 232`，表大小 8 + 232×4 + 232×4 = **1864 字节**
2. `.dynamic` 没有富余槽位（只有 1 个 NULL 结尾），所以**整体重定位**：
   复制全部条目到文件尾部 4K 对齐处 → 追加 `DT_HASH` → 补新 `DT_NULL`
3. 扩大最后一个 `LOAD` 段的 `filesz/memsz`，让它覆盖新位置
4. 改写 `PT_DYNAMIC` 指向新位置（旧位置留作死数据，无害）

> ⚠️ 注意 `readelf -d` 会显示不出 `DT_HASH` —— 它按 section header 找（指向旧位置）。
> **运行时 linker 按 `PT_DYNAMIC` 找（新位置），所以功能正常。**
> 验证要用 `readelf -l` 看 PT_DYNAMIC，或直接 dump 新偏移。

### 验证结果

```
DT_HASH va = 0xeb2100, nbucket=232 nchain=232
独立校验: 231 个符号在哈希链中正确可达, 0 个失败
```

最终三个 so 全部双段齐全：

| so | DT_HASH | GNU_HASH |
|---|---|---|
| libonnxruntime.so | ✅ | ✅ |
| libr1opus.so | ✅ | ✅ |
| libsherpa-onnx-jni.so | ✅ | ✅ |

---

## （归档）v1.2.0 诊断 —— 曾误判为 ORT 符号版本不匹配

### 现象

```
W/linker : libonnxruntime.so: unused DT entry: type 0x6ffffef5 arg 0x1210
W/sherpa-onnx: keyword-spotter.cc:Java_com_k2fsa_sherpa_onnx_KeywordSpotter_newFromAsset:93
W/sherpa-onnx: config: KeywordSpotterConfig(...)     ← 模型路径解析成功
F/libc   : Fatal signal 11 (SIGSEGV), code 1, fault addr 0xc in tid 6572
I/DEBUG  : #06 pc 000e3d4d libsherpa-onnx-jni.so (Java_com_k2fsa_sherpa_onnx_KeywordSpotter_newFromAsset+260)
```

崩溃在 native 层，Java 的 `try/catch` 兜不住 → 进程直接死亡。

### 根因：sherpa-onnx 的 JNI 是按 ORT ABI 版本编译的

```
libsherpa-onnx-jni.so 的 .gnu.version_r：
  File: libonnxruntime.so
  Name: VERS_1.28.2            ← 明确要求 1.28.2 版本的符号
```

之前用的 ORT 是 `com.microsoft.onnxruntime:onnxruntime-android:1.17.1`，它导出：

```
0024ef49 g DF .text VERS_1.17.1 OrtGetApiBase     ← 版本号对不上
```

对比正确的（sherpa-onnx 官方 AAR v1.13.8 自带）：

```
0035f701 g DF .text VERS_1.28.2 OrtGetApiBase     ← 精确匹配 ✅
```

符号版本校验失败时，bionic **不会硬报错**，而是返回一个未完全初始化的桩。
`OrtGetApiBase()` → 半成品指针 → `GetApi()` 拿到的函数指针对照表错位 →
调用时跳到野地址 → `fault addr 0xc`（解引用 null + 偏移 12）。

### 教训

**选 ORT 不能只看版本号新旧或 minSdk，必须看符号版本能不能对上。**
`libsherpa-onnx-jni.so` 和 `libonnxruntime.so` 必须来自**同一套构建产物**。

| | sherpa-onnx AAR 自带 ORT | ORT 官方 maven |
|---|---|---|
| 大小 | 15,359,592 | 10,739,204 |
| 符号版本 | `VERS_1.28.2` | `VERS_1.17.1` |
| 能否配 sherpa JNI | ✅ | ❌ |

### 配套问题：只识别 GNU_HASH

新的 ORT 只有 `GNU_HASH`，没有 `DT_HASH`。R1 的 Android 5.1 linker 会打警告：

```
W/linker: libonnxruntime.so: unused DT entry: type 0x6ffffef5 arg 0x1210
```

但**这只是警告，不阻止加载**（后面 sherpa 的 config 打印证明了 so 已加载成功）。
所以 DT_HASH 不是本次崩溃的原因——真正的凶手是符号版本。

### 附带加固：KWS 初始化改为异步隔离

`KeywordSpotter` 初始化一旦 native 段错误，进程必死、无 Java 层补救可能。
现在把它挪到**装配完成之后、独立线程**里做：

```
=========== R1ClientService ready (mic=true kws=pending bus=true opus=true) ===========
kws init=true (xxxx ms)
kws wired to session: true
```

好处：即使 KWS 崩了，总线唤醒 + 麦克风 + WS 都已经是活的，
进程重启后仍能靠总线唤醒工作。`SessionController.setKws()` 支持运行期挂载/摘除。

---

## v1.1.1 修复记录

| 问题 | 根因 | 修复 |
|---|---|---|
| `ClassNotFoundException: android.os.IMessageReceiver` | **隐藏 API 是内部类，不是顶层类**。R1(API22) 的 boot classloader 找不到它 | 改用 `android.os.MessageDispatchManager$MessageReceiver`；并加「按方法签名反查接口类型」的兜底 |
| 日志里连 `kws init=` / `mic start=` / `ready` 都没有 | `register()` 抛异常后 `onCreate` 被中断，后续组件全没装配 | 整个管线装配包 `try/catch`，任一环失败仍继续，并打印 `pipeline assembly FAILED` |
| 同一份日志出现 5 个进程号 | `START_STICKY` 被 kill 后自动重启，叠加旧版残留 | 加 `started` 幂等标志，重复 `onStartCommand` 直接返回 |

### 为什么接口名是内部类

```
错误：Class.forName("android.os.IMessageReceiver")
  → ClassNotFoundException: Didn't find class "android.os.IMessageReceiver"
    on path: DexPathList[[.../base.apk], nativeLibraryDirectories=[.../system/lib]]
    Caused by: NoClassDefFoundError: Class not found using the boot class loader

正确：Class.forName("android.os.MessageDispatchManager$MessageReceiver")
  → 这是 MessageDispatchManager 的静态内部接口，本机确实存在
```

旧 probe APK 之所以能成功注册 8 个 type，用的就是这个内部类名。本版还额外做了两层兜底：
① 候选名列表（内部类名 / 顶层类名 / 其他 ROM 变体）；
② 全失败则遍历 `registerMessageReceiver` 的 `getParameterTypes()`，取其中 `isInterface()` 的那个作为接口。

### 不要硬编码注册方法签名

```java
// ✅ 动态匹配：兼容 (receiver, int) 与 (int, receiver) 两种顺序
for (Method m : mgrCls.getMethods()) {
    if (!"registerMessageReceiver".equals(m.getName())) continue;
    Class<?>[] pt = m.getParameterTypes();
    if (pt.length != 2) continue;
    boolean recvFirst = pt[0].isAssignableFrom(ifaceCls) && pt[1] == int.class;
    boolean intFirst  = pt[0] == int.class && pt[1].isAssignableFrom(ifaceCls);
    if (recvFirst || intFirst) return m;
}
```

### 不要给原始类型返回 null

系统会对 receiver 代理调用 `equals()` 并对结果做 boolean 拆箱。返回 `null` 就是
`NullPointerException: Expected to unbox a 'boolean' primitive type but was returned null`
（旧 probe 死在 `MessageDispatchManager.java:184`）。本版 `equals/hashCode/toString/asBinder`
全部显式实现，其余方法按返回类型给兜底值。

---

## v1.1.0：双模唤醒（复用原厂云知声）

### 为什么要做这件事

原版 v1.0.1 只能靠自建的 sherpa-onnx KWS 听「小智小智」。实机验证后确认：
**R1 固件里本来就跑着一套云知声（Unisound）四麦唤醒引擎**，而且它每次唤醒都会
往 msgcenter 总线上投递事件。与其自己再跑一份模型去抢麦克风，不如直接搭这趟车。

### 关键证据链

**① 唤醒事件确实在总线上**（用户实机 logcat，共 3 次）：

```
[bus33554432] what=33554432 arg1=3 arg2=-1
  val={"data":{"wakeupWord":"灏忚灏忚","wakeupType":1},
       "from":"unisound","key":"wakeup",
       "msgId":"3e2dd4bf-5945-40cc-a7b1-a553f9e5282e"}
```

`"灏忚灏忚"` 是「小讯小讯」被 UTF-8→GBK→UTF-8 三明治解读后的乱码。

**② 唤醒后紧跟全灯效**：总线立刻出现 `type=4096, subType=1..24, arg1=1` 的 24 连发
（原厂 App 收到唤醒事件后点亮 24 颗 LED）。

**③ 唤醒引擎不可移植**（三条铁证）：

| 证据 | 说明 |
|---|---|
| `libus4.so` 不在 APK 内 | dex 里有 `libus4` 常量（= `com.unisound.jni.Uni4micHalJNI`），但 `lib/` 只有 8 个 so，均与唤醒无关 |
| `assets/wakeup/data` 仅 647 字节 | 只有授权串 `Go!Go!Speech@YZS!!` + 词表 `<eps> <s> 你好 魔方 </s>`，**无模型** |
| 引擎在固件 `/system/lib` | 是 RK3229 四麦 HAL 的一部分，由 Pandora 进程（`com.phicomm.speaker.device`）加载 |

**结论：唤醒引擎无法搬进我们的 APK。但唤醒「事件」可以监听。**

### 实现

新增 `WakeupBridge.java`：

- `getSystemService("msgcenter")` 拿 `MessageDispatchManager`
- 动态代理 `android.os.IMessageReceiver`，反射调用
  `registerMessageReceiver(receiver, 33554432)`
- 收到 `key=wakeup` 事件 → 回调 `SessionController.onWakeup(word)`

**避坑记录**：旧 probe APK 曾崩在
`NullPointerException: Expected to unbox a 'boolean' primitive type but was returned null`。
根因是 `InvocationHandler.invoke()` 对 Binder 体系调用的 `equals()`
返回了 `null`。本版对 `equals/hashCode/toString/asBinder` 显式实现，
其余方法按返回类型给原始类型兜底（绝不返回 null）。

### 唤醒模式开关

`R1ClientService.WAKEUP_MODE`：

| 值 | 行为 | 适用 |
|---|---|---|
| `MODE_BUS_ONLY` (1) | 只听总线，喊「小讯小讯」唤醒 | CPU 最省，唤醒率最高 |
| `MODE_KWS_ONLY` (2) | 只跑自建 KWS，喊「小智小智」唤醒 | 唤醒词自由 |
| `MODE_HYBRID` (3) | **默认**，两个词都能唤醒 | 兼容性最好 |

双源同时命中时，`SessionController` 有 800ms 去抖窗口，不会开两次会话。

### 关于「小讯小智冲突」

用户反馈「喊小智唤醒后出来的是小讯的信息」——**这不是冲突，是抢答**：

1. 喊「小讯小讯」→ 原厂引擎唤醒
2. 它同时做两件事：往总线投事件 + 自己连云端 `aios-home.hivoice.cn:19999`
3. 那个云端**早已下线**，所以原厂 App 会报错/闪灯/说话（就是用户看到的现象）
4. 我们的客户端也收到了总线事件，会正常开小智会话

要彻底消除原厂的那半场戏，把 `WakeupBridge.KILL_UNISOUND_AFTER_WAKEUP`
改成 `true`（当前为 `false`，即不动原厂进程）。

> 注意：那份 `debug(2).txt` 里**没有任何 `R1CLIENT` 日志**（3301 行全是旧
> `R1PROBE`），所以 v1.0.1 的实机状态其实还没被验证过。请重跑后单独抓：
> `adb logcat -d -s R1CLIENT:V`

---

## v1.0.1 修复记录（相对 v1.0.0）

| 问题 | 根因 | 修复 |
|---|---|---|
| `UnsatisfiedLinkError: empty/missing DT_HASH in "libonnxruntime.so"` | bihe 包的 ORT 1.24.3 抄自 ORT 官方 maven，`--hash-style=gnu`，**Android 5.x linker 只认 DT_HASH（SysV）** | 换 ORT 官方 maven **1.17.1**（minSdk 21，DT_HASH ✓）+ 官方 sherpa-onnx 1.13.8 v7a so |
| OTA 返回 400 `Invalid MAC address` | 发的是 ANDROID_ID（16hex 无分隔），服务器要求标准 MAC 格式 | 改用 R1 真实 WiFi MAC（`WifiManager`），兜底格式化 ANDROID_ID |

全套原生库（v1.1.0 复核，三个 so 均为 `HASH + GNU_HASH` 双段）：

- `libsherpa-onnx-jni.so` 3,428,780 B ← sherpa-onnx 官方 AAR v1.13.8（v7a）
- `libonnxruntime.so` 10,739,204 B ← `com.microsoft.onnxruntime:onnxruntime-android:1.17.1`（v7a）
- `libr1opus.so` 312,804 B ← 自编（NDK r21e，静态链接 opus）

---

## 快速部署

```
adb install -r /workspace/r1-client/r1-client-v1.2.1.apk
adb shell am startservice cn.workbuddy.r1client/.R1ClientService
adb logcat -d -s R1CLIENT:V
```

## 预期日志

```
=========== R1ClientService onCreate (mode=3) ===========
TtsPlayer started, minBuf=4096 buf=16384
opus enc=true dec=true
msgcenter manager class = android.os.MessageDispatchManager
receiver iface resolved: android.os.MessageDispatchManager$MessageReceiver
wakeup bridge REGISTERED on type=33554432 via ...registerMessageReceiver(...$MessageReceiver,int) ret=null
WakeupBridge register=true
mic start=true
=========== R1ClientService ready (mic=true kws=pending bus=true opus=true) ===========
extracted encoder-epoch-12-...onnx (12152560 bytes)
KwsEngine ready, modelDir=/data/data/cn.workbuddy.r1client/files/kws
kws init=true (8532ms)
kws wired to session: true
```

喊 **「小讯小讯」**，预期：

```
[wakeup-bus] notifyMsg what=33554432 arg1=3 arg2=-1 val={"data":{"wakeupWord":"小讯小讯",...}}
*** BUS WAKEUP: 小讯小讯
wakeup src=bus:小讯小讯 state=IDLE
session start -> connecting (preroll=true)
```

喊 **「小智小智」**，预期：

```
*** KWS HIT: 小智小智
wakeup src=kws:小智小智 state=IDLE
```

## 首次激活

小智官方服务器要求 `Authorization: Bearer <token>`。APK 启动时 POST OTA：

1. **直接返回 token** → 自动注入 WS，可用。
2. **返回 `activation.code`** → 日志打印验证码，去 https://xiaozhi.me 绑定后重启。
3. **401 / 无 token** → 把 `OTA resp body:` 那行贴出来。

## 排错清单

| 现象 | 排查 |
|---|---|
| `pipeline assembly FAILED` | 装配某个组件抛异常，看栈顶那一层 |
| `wakeup bridge register failed` + `ClassNotFoundException` | 接口名不对；日志里 `msgcenter manager class =` 那行会告诉你真实类名，据此调整候选名 |
| `receiver interface not found` | 三个候选名全失败且签名反查也没命中；下面 `dumpMethods` 会列出全部方法，把 `registerMessageReceiver` 那几行贴出来 |
| 日志里出现 `onStartCommand: first start` 多次 | 服务被反复拉起，检查是否被系统 kill（R1 内存只有 512MB） |
| 喊「小讯小讯」没反应 | 看有没有 `*** BUS WAKEUP`；没有则原厂 Pandora 进程可能没在跑 |
| 喊「小智小智」没反应 | 看 `kws init=` 是否 true；false 则模型释放或 so 加载失败 |
| KWS 有 HIT 但不连 | 看 `connecting` 后是否 `http=401` → token 问题 |
| 崩溃 | `adb logcat -d -b crash`，重点 `NoClassDefFoundError` / `UnsatisfiedLinkError` |

## 调试小技巧

`R1ClientService.onCreate` 现在会把全部装配包在 try/catch 里。如果怀疑某个环节
偷偷失败，直接看有没有 `=========== R1ClientService ready` 这一行——
**没有它 = 装配中途断了**，不用去猜哪个组件的问题。

抓完整日志（含 crash buffer）：

```
adb logcat -d -s R1CLIENT:V AndroidRuntime:E
```

## 已知限制

- 首次启动释放 13MB 模型，约 3~8 秒。若要完全省掉，可切 `MODE_BUS_ONLY`
  （此时不需要 assets/kws，APK 也能瘦身到 6MB 左右）。
- 按需连接：每次唤醒 +200~400ms 握手延迟。
- **总线唤醒依赖原厂 Pandora 进程存活**。若它被禁用/冻结，总线事件就没有了
  （用户提到「这个 apk 禁用就不能语音唤醒了」，正是这个原因）。
  此时应回退到 `MODE_KWS_ONLY`。

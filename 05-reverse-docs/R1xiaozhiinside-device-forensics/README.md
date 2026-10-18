# R1xiaozhiinside — 用小智替换小讯（复用原厂四麦 + AEC）

> 目标：保留原厂 `mediaserver` 侧的四麦波束成形与 AEC，把上层的"小讯"换成我们的小智客户端。  
> 本文所有结论均来自本机对设备与系统库的实测取证，附证据位置。

---

## 0. 一句话结论

**原厂四麦算法并不在"小讯"这个 app 里，而是编进了音频 HAL（`audio.primary.rk30board.so` 静态链接 `libuni4michal.so`）。**  
所以任何 `AudioRecord` 客户端——包括我们现在的 r1client——**都已经在吃四麦增强**。真正没搞清楚的只有一件事：**AEC 有没有开**（`aec_on`），这是"播歌时唤醒难"的唯一剩余变量，也是本项目要解决的核心。

---

## 1. 原厂音频栈拓扑（实测）

```
                    ┌──────────────────────────────────────────────┐
                    │  mediaserver (pid 209, uid system)           │
   App 进程          │  ┌────────────────────────────────────────┐  │
 ┌──────────────┐   │  │ AudioFlinger (libaudioflinger.so)      │  │
 │ AudioRecord  │◄──┼──│  Input thread: 16000Hz / 2ch / pcm16   │  │
 │ (A 通路)      │   │  │  Output thread: 48000Hz / 2ch         │  │
 └──────────────┘   │  └───────────────┬────────────────────────┘  │
                    │                  │ HAL 接口 (audio_hw_device) │
 ┌──────────────┐   │  ┌───────────────▼────────────────────────┐  │
 │ Uni4micHalJNI│───┼─►│ audio.primary.rk30board.so   (120KB)   │  │
 │ (B 通路)      │   │  │  ├─ libuni4michal.so        (25KB)     │  │
 │ uid 10008    │   │  │  ├─ libUniMicArray.so        (535KB) 波束│ │
 │ platform_app │   │  │  ├─ libUniLinearMicArray.so  (711KB)    │  │
 └──────────────┘   │  │  └─ libtinyalsa.so → ALSA card1/dev0    │  │
                    │  └────────────────────────────────────────┘  │
                    └──────────────────────────────────────────────┘
                                       │
                          /system/usr/uni_4mic_config/
                          MicArrayConfig.ini / wopt.bin / pcm_hw_config.txt
```

### 关键证据

| 结论                 | 证据                                                                                                            |
| ------------------ | ------------------------------------------------------------------------------------------------------------- |
| 四麦在 HAL 内，不在 app 内 | `audio.primary.rk30board.so` 的 `DT_NEEDED` 含 `libuni4michal.so`、`libUniMicArray.so`、`libUniLinearMicArray.so` |
| 运行时确实在跑            | `logcat` 里 `D/UNI_4MIC(209)` —— 标签自带 **pid 209 = mediaserver**，而我们此刻只有 r1client(A 通路)在开麦                      |
| 输入格式               | AudioFlinger Input thread = `16000Hz / Channel Mask 0xc (left,right) / pcm16`                                 |
| AEC 参考是**硬件回采**    | `libuni4michal.so` 内三个独立 PCM 句柄：`(NULL == aec_pcm)`、`(NULL == mic_pcm)`、`(NULL == out_pcm)`                   |
| AEC 有独立开关          | 字符串 `<INFO> aec_on=%d`、`<INFO> aec_enlarge_4mic=%d`                                                           |
| dump 管线            | `/sdcard/unidata/` 三个 wav（见 §2）                                                                               |

---

## 2. HAL 内部管线（由 dump 文件的 WAV 头反推）

`/sdcard/unidata/` 下每组三个文件，实测 WAV 头：

| 文件                    | 通道    | 采样率/位深        | 阶段                            |
| --------------------- | ----- | ------------- | ----------------------------- |
| `waked_file_4mic.wav` | **4** | 16000 / 16bit | 原始四麦（MicNum=4）                |
| `waked_file_2aec.wav` | **2** | 16000 / 16bit | 波束成形后，送进 AEC 的 2 路            |
| `waked_file_out.wav`  | **2** | 16000 / 16bit | AEC 之后输出，即交给 AudioFlinger 的数据 |

命名前缀含义：`waking_*` = 唤醒/录音进行中，`waked_*` = 已唤醒。  
**每次开麦会被重置为 0 字节**，所以取证必须在开麦后立刻抓。

这与 `MicArrayConfig.ini` 完全对得上：

```ini
[Engine]
ArrayType = circle      ; 环形阵列
MicNum = 4
MicDistance = 3.5       ; 半径 3.5cm
HasCentre = 0
SpeakerNum = 2          ; ← 2 个喇叭（AEC 参考对应 2 路）
Amplify = 1

[AEC]
AecFrameShift = 256     ; 帧移 256 样本 @16k = 16ms
AecFilterNum = 12       ; 12 个滤波器 → 尾长 12×256 = 3072 样本 ≈ 192ms
```

```ini
# pcm_hw_config.txt
pcm_card 1 / pcm_device 0 / i2s_bits 32
linear_4mic 0 / no_vad 0 / high_priority 1
aec_enlarge_4mic 2          # ← 主配置；chance 版是 1
debug_file_path /sdcard/unidata/
```

**推论**：`SpeakerNum=2` + `aec_enlarge_4mic=2` 同时出现，说明 AEC 参考就是硬件回采的喇叭信号。  
由于回采发生在 ALSA 硬件层，**无论音频是谁播的**（原厂 player / 我们的 TTS），参考都成立——这与过去"B 通路没接线所以回环"的旧结论不同，值得重新验证。

---

## 3. 两个 app 通路对比

|                  | **A 通路**（现在 v1.3.55 在用）            | **B 通路**（原厂小讯在用）                           |
| ---------------- | ---------------------------------- | ------------------------------------------ |
| API              | `AudioRecord(VOICE_COMMUNICATION)` | `Uni4micHalJNI.openAudioIn(2)`             |
| 承载进程             | app 自己，经 AudioFlinger → HAL        | app 自己，直开 ALSA（经 libuni4michal）            |
| 所需权限             | 无（普通 untrusted_app 即可）             | 需 **platform_app**（uid 10008）开 `/dev/snd/` |
| 吃到四麦波束           | ✅ 是（HAL 里做的）                       | ✅ 是                                        |
| 拿到的数据            | HAL `out` 阶段（AEC 之后）               | 同样是 `out` 阶段                               |
| 额外能力             | 无                                  | DOA 测向、one-shot、唤醒状态同步                     |
| 与 mediaserver 关系 | 共用同一个 HAL 实例                       | **另开一份 HAL 实例**（可能与 A 通路互斥）                |

**重要**：B 通路并不比 A 通路"多一层算法"——两者最终都是 `waked_file_out.wav` 那个阶段的数据。  
B 通路真正多出来的只有 DOA/one-shot 这类**元信息**，以及绕开 AudioFlinger 的低延迟。  
所以"必须借壳才能拿到好音质"这个直觉是错的，**关键变量只有 `aec_on`**。

---

## 4. 候选路线

| 路线                    | 做法                                                                                       | 代价                                                       | 适用条件                          |
| --------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------- | ----------------------------- |
| **A. 维持 AudioRecord** | 继续用现在 v1.3.55 的 A 通路                                                                     | 零                                                        | **若 `aec_on=1` 且有效，这是最优解**    |
| **B. Janus 借壳（jp2）**  | 顶替 `com.phicomm.speaker.device` 拿 platform_app，用 JNI 开四麦                                 | 高：Janus 重建、依赖 r1client 存活借 so                            | 只有需要 DOA / 证明 A 通路 AEC 无效时才值得 |
| **C. 迷你借壳**           | 借壳壳只做一件事：开四麦，通过 socket 把 PCM 送给 r1client                                                 | 中                                                        | B 的简化版，解耦代码                   |
| **D. 系统级 WebRTC APM** | 启用 `/system/lib/soundfx/libaudiopreprocessing.so`（依赖 `libwebrtc_audio_preprocessing.so`） | 需改 `/system/etc/audio_effects.conf`（**要 root，当前无 root**） | 仅当 HAL AEC 判死后的备选             |

### 关于路线 D 的现状

`/system/etc/audio_effects.conf` 里 `pre_processing` / `aec` / `ns` / `agc` **全部是注释状态**，  
库却都在 `/system/lib/soundfx/` 下。这解释了 `dumpsys` 里 `0 Effect Chains`。  
无 root 改不了 conf，故 D 暂不可行——但留作 HAL AEC 判死后的备手。

---

## 5. 待验证清单（按优先级）

1. **`aec_on` 的实际值** —— 决定路线 A 是否成立。  
   日志串 `<INFO> aec_on=%d` 只在 HAL 初始化时打印一次，被 `<DEBUG>` 刷屏冲掉。  
   取法：重启 mediaserver 后立刻抓，或在抓之前先关掉 DEBUG 打印。
2. **AEC 削减量实测** —— 播歌同时开麦，对比 `2aec` 与 `out` 的能量差（dB）。  
   这是判定"AEC 到底有没有在削回声"的唯一硬指标。
3. **A 通路与 B 通路是否互斥** —— 两个 HAL 实例能否同时开 card1/device0。  
   若互斥，则小智与小讯不能共存，替换必须是"顶替"而非"共存"。
4. **`aec_enlarge_4mic` 2 vs 1 的差异** —— `pcm_hw_config.txt`(2) 与 `pcm_hw_config_chance.txt`(1)。  
   推测为 AEC 强度档位，可尝试改写验证（需 root 改 /system，或找到 runtime 开关）。

---

## 6. 目录结构

```
R1xiaozhiinside/
├─ README.md            ← 本文
├─ tools/elfinfo.py     ← 自写 ELF 解析器（本机无 readelf）
├─ lib/                 ← 从设备拉回的原厂库（静态分析用）
│   ├─ audio.primary.rk30board.so
│   ├─ libuni4michal.so        libuni4michalchance.so
│   ├─ libUniMicArray.so       libUni4micHalJNI.so
│   ├─ librk_audio.so          libaec.so
│   ├─ libaudiopreprocessing.so
│   └─ libwebrtc_audio_preprocessing.so
├─ config/              ← audio_policy.conf 等
├─ dump/                ← /sdcard/unidata/ 抓回的 wav
├─ audio_flinger.txt    ← dumpsys media.audio_flinger
├─ logcat*.txt          ← 各轮日志
└─ ms_maps.txt          ← （pid 209 maps 取不到：Permission denied）
```

---

## 6.5 共存性实测：小智 + 小讯能否同时跑（2026-10-08 17:0x）

**结论：塞不下。** 瓶颈不是"抢麦克风"，而是内存与 CPU。

### 内存（小讯已启动、小智已停）

```
MemTotal:   501444 kB
MemFree:      5532 kB      ← 只剩 5.5 MB
Cached:     174584 kB
```

| 进程                                    | RSS                |
| ------------------------------------- | ------------------ |
| `com.phicomm.speaker.device`          | 132,752 kB         |
| `com.phicomm.speaker.device:remote`   | 49,584 kB          |
| 小讯小计                                  | **≈ 181 MB**       |
| `cn.workbuddy.r1client` + `:r1client` | 29,452 + 19,972 kB |
| mediaserver                           | 6,168 kB           |

### CPU（6 秒采样，HZ=100）

| 进程                           | CPU         | 线程数 |
| ---------------------------- | ----------- | --- |
| `com.phicomm.speaker.device` | **108.5 %** | 52  |
| mediaserver                  | 4.3 %       | 11  |
| `cn.workbuddy.r1client`      | 0.0 %（已停）   | 15  |

小讯常驻 KWS 就吃掉 **1.08 个核**（rk3229 是 4 核）。再加一份常驻 KWS 必然爆。

### 时间线

```
16:56:20  r1client onCreate → AudioCapture started → mic ACQUIRED   ✅ 成功
16:58:21  ws closed: service-destroy                                 ← 之后被停
17:03:40  再次 startservice
17:03:48  AudioCapture started / mic ACQUIRED                        ✅ 成功
17:03:~   用户在 UI 拨总开关 → enabled=false → stopService
```

**关键观察**：小讯**待命（未唤醒）**&#x65F6;，r1client 的 `AudioRecord` 是可以正常 `ACQUIRED` 的，  
AudioFlinger Input thread 也能正常建立（16000Hz / 2ch / 1 active track）。  
⇒ **小讯待命阶段两者不冲突**；真正没验证的是**小讯唤醒后**（它会真正打开 Uni4micHalJNI / ALSA）会不会互斥。

### 小讯的完整启动序列（关键证据）

```
usc_create return 0
wakeup compile net file:/data/data/com.phicomm.speaker.device/files/wakeup/data
wakeup compile net file:success
setWakeupWord: 小讯小讯
usc_set_option(option:WAKEUP_OPT_...)
usc_start_wakeup success
HalAudioSource: startAudio... threadPriority=-1
HalAudioSource: AudioSource:Uni4micHalSource          ← 音频源 = 四麦 HAL
HalAudioSource: openAudioIn: 2                        ← 2 通道
HalAudioSource: openAudioIn: 2 ; ret=0, 0:success
HalAudioSource: startRecorder: 0
```

要点：

1. 音频源是 **`Uni4micHalSource`**（B 通路），不是 `AudioRecord`。
2. `openAudioIn(2)` —— 2 通道，与 `waked_file_2aec/out.wav` 的 2ch 吻合。
3. **一启动就 `openAudioIn` + `startRecorder`**，不等唤醒词 ⇒ 小讯全程占着四麦 HAL。
4. 唤醒网编译产物落在 `/data/data/com.phicomm.speaker.device/files/wakeup/data`，  
   唤醒词是「小讯小讯」，通过 `setWakeupWord` 运行时下发。

⇒ 既然小讯启动时就独占 `Uni4micHalSource`，"小智与小讯共存"只能是  
**小智走 A 通路（AudioRecord）、小讯走 B 通路（JNI）** 这种形态，  
且必须实测两者同时开时 A 通路拿到的数据是否仍然有效。


### SP 总开关

```xml
<boolean name="enabled" value="false" />   
```

`MainActivity:204` 读取它，`isChecked ? startService : stopService`；  
`R1ClientService:123` 与 `BootReceiver:26` 也会检查，false 则不起服务。

---

## 6.8 A 通路 AEC 实测：没在削回声（2026-10-08 17:20）

停掉小讯、小智独占后跑一轮完整对话，TTS 播报窗口与 `micTelem` 精确对齐：

```
17:20:27.872  TTS start
17:20:31.872  micTelem rms[1938, 740, 365, ...]        ← 播报中：1938
17:20:33.007  TTS stop
17:20:37.630  micTelem rms[203, 202, 204, 209, ...]    ← 播报一停立刻回底噪
```

- 底噪稳定 **~200**，播报时 **1938** ⇒ 高出约 **19.7 dB**。
- 播报结束**无拖尾**，采集链路本身是干净的。
- ⚠ `micTelem` 的 rms 数组**左边是最新帧**，读时间线别读反。
- 参照：同轮用户说话 `maxRms=1833`，与播报时的 1938 **同量级**  
  ⇒ 回声和人声一样响，KWS/ASR 被淹没，这就是「播歌时喊不醒」的直接原因。

**结论：HAL 的 AEC 在 A 通路上没有有效削减喇叭回声。**（`aec_on` 实际值仍待查，见 §5。）

---

## 6.9 自研 AEC 的可行性：别从零写，系统里有 WebRTC

`libwebrtc_audio_preprocessing.so`（390KB）导出 **1437 个符号**，C API 完整可用：

| 模块             | 数量 | 说明                                                                                                        |
| -------------- | -- | --------------------------------------------------------------------------------------------------------- |
| `WebRtcAec_*`  | 29 | `Create` / `Init` / `BufferFarend` / `Process` / `Free` / `set_config` / `GetMetrics` / `GetDelayMetrics` |
| `WebRtcAecm_*` | 32 | 移动端版，`int16` 直入（`Create`/`Init`/`BufferFarend`/`Process`/`Free`/`set_config`）                             |
| `WebRtcAgc_*`  | 22 | 自动增益                                                                                                      |
| `WebRtcNsx_*`  | 26 | 降噪                                                                                                        |
| `WebRtcSpl_*`  | 94 | FFT / 向量运算底子                                                                                              |

`BufferFarend` = 喂参考信号（我们的 TTS PCM），`Process` = 麦克风输入 —— 接口与需求完全对上。

### 四条实现路径对比

| 路径                                 | 前提                | 评价                                                                                                                      |
| ---------------------------------- | ----------------- | ----------------------------------------------------------------------------------------------------------------------- |
| **① dlopen 系统 WebRTC AEC**         | 需要 NDK 编一个薄 JNI 壳 | ★ 最优选：工业级算法 + NEON，已有延迟估计/NLP/舒适噪声                                                                                      |
| **② 纯 Java 频域分块自适应(FDAF)**         | 无 NDK 也能做         | 可行。块 256 / FFT 512，尾长 4~~8 块(64~~128ms)，约 62 块/秒 × ~10⁴ 次运算 ⇒ 很轻                                                        |
| ③ 纯 Java 时域 NLMS                   | 无                 | 尾长 512 taps 才 32ms，短；拉到 4096 则 ~8M MAC/s，CPU 偏高                                                                         |
| ④ 系统 `libaec.so`(152KB)            | —                 | 导出接口与 APK 版**完全相同**(`Java_com_unisound_jni_AEC_*`)，大概率仍是固定衰减器；但字符串 `nmic_buf/necho_buf/nout_buf` 表明它结构上确是双输入真 AEC，可低成本试 |
| ~~Android `AcousticEchoCanceler`~~ | —                 | 已试（v1.3.56/57）：session 绑定后仍无效，且开启时"底噪没了、人声也没了"                                                                          |

### 参考信号从哪来

我们自己播的 TTS / 音乐，**PCM 在写进 AudioTrack 之前就能截一份** —— 这是自研 AEC 成立的前提，  
也是过去 HAL AEC 失败时最缺的东西。

---

## 6.7 进程归属实测：四麦不在 mediaserver，在小讯自己进程（假设已证伪）

曾假设「mediaserver 处理完再交给小讯」。**实测证伪**，方法：清 logcat 缓冲后看  
`UNI_4MIC` 标签带的 pid。

| 场景                   | `UNI_4MIC` 日志                     | AudioFlinger Input thread |
| -------------------- | --------------------------------- | ------------------------- |
| r1client(A 通路)开麦、小讯停 | **`D/UNI_4MIC(209)` 刷屏**          | 有（16k/2ch/1 active track） |
| **小讯运行、小智停**         | **0 条**                           | **无**                     |
| 两者同开                 | 有（209）但 r1client 读不到 → stalled 自杀 | 有但无数据                     |

结论：

1. **小讯的四麦处理跑在它自己的进程里**（加载 `libUni4micHalJNI.so` → `libuni4michal.so`  
   直开 ALSA），**不经过 mediaserver**。
2. mediaserver 的 HAL 是**另一条独立通路**，专门服务 `AudioRecord` 客户端。
3. 两者**不共享状态且互斥**：小讯占住 ALSA 后，mediaserver 的 `in_read` 拿不到数据  
   （r1client 表现为 `capture thread stalled 22s` → 自杀）。
4. 因此「替换」只有两条路：**顶替**（借壳拿到 platform_app 直开 HAL），  
   或**停掉小讯**让 mediaserver 通路独占（现在就是这样，能正常对话）。

### 原厂 SDK 的 AEC 配置（小讯启动日志实证）

```
D/ANTPrivatePreference( <pid> ): [aec_debug=true]
D/ANTPrivatePreference( <pid> ): [aec_enable=true]
D/ANTPrivatePreference( <pid> ): [wakeup_aec_test=false]
```

`aec_enable=true` 与逆向报告一致：开启后 TTS 期唤醒引擎**常开**（barge-in），  
并用 `effectWakeupBenchmark` 自适应抬阈值（-9.1 平时 / -11.3、-12.3、-13.3 音乐三档 /  
-11.3 连续对话 5s 窗口）。⇒ **原厂也不是纯靠 AEC 硬消回声，而是「AEC + 抬阈值」组合**。

---

## 7. 已知环境约束

- 设备 `192.168.2.5:5555`，Android 5.1.1 / API22 / rk3229 / 无 root（adb uid 2000）
- `/proc/<system-pid>/maps` **读不了**（Permission denied），只能靠静态分析 + logcat
- 设备 shell 无 `head/tail/awk/sed`；`logcat -d` 全量会挂，须 `-t N` 后落盘再 pull
- 当前 `com.phicomm.speaker.device` 装的是**第三方魔改版**（30.4MB，uid 10008 platform_app，点歌等云端功能正常）
- 现役 r1client：versionCode 76，A 通路

---

## 6.10 ★★★ 决定性证据：HAL 的 AEC 是真开的，参考是**硬件回采**（2026-10-08 17:4x）

### 用户实测（驱动本节的全部取证）

> 「小讯放歌时远处轻轻喊也是能识别到的」

即：**原厂链路在强回声（播歌）场景下仍能唤醒**。这与小智 A 通路「播报时 19.7dB 回声、  
完全喊不醒」（§6.8）形成直接对照 ⇒ 原厂手里一定有我们没拿到的东西。

### 证据 1：小讯 HAL 初始化日志（`hal_init.txt`，pid 7964 = com.phicomm.speaker.device）

```
D/UNI_4MIC( 7964): <INFO> UNI_4MIC_VERSION: UNI_4MIC_HAL_ANDROID_V1.1
D/UNI_4MIC( 7964): <INFO> use_4mic=1.
D/UNI_4MIC( 7964): <INFO> pcm_card=1      pcm_device=0     i2s_bits=32
D/UNI_4MIC( 7964): <INFO> linear_4mic=0   no_vad=0         high_priority=1
D/UNI_4MIC( 7964): <INFO> mic_array_rw_path=/system/usr/uni_4mic_config/
D/UNI_4MIC( 7964): <INFO> aec_enlarge_4mic=2
D/UNI_4MIC( 7964): <INFO> uni_hal_4mic_array_version=v2.3.0
D/UNI_4MIC( 7964): <INFO> mic_num=4
D/UNI_4MIC( 7964): <INFO> echo_num=2        ← 两路回声参考
D/UNI_4MIC( 7964): <INFO> aec_on=1          ← ★ AEC 真的开着
D/UNI_4MIC( 7964): <INFO> mclp_on=-1        agc_on=-1
D/USC     ( 7964): AudioSourceImplopenIn uni4micHalJNI status = 0
```

`echo_num=2` ⇔ `MicArrayConfig.ini` 的 `SpeakerNum = 2` / `MicArray_config.txt` 的 `speaker_num 2`。

### 证据 2：HAL 里存在独立的 `aec_pcm`（回采）句柄 —— `libuni4michal.so` 字符串

```
<ERR> %s: (NULL == aec_pcm)     ← ★ 第三个 PCM 句柄
<ERR> %s: (NULL == mic_pcm)
<ERR> %s: (NULL == out_pcm)
<INFO> aec_on=%d   <INFO> echo_num=%d   <INFO> aec_enlarge_4mic=%d
pcm_card / pcm_device / pcm_open / pcm_read / pcm_close
```

### ~~证据 3~~ ❌ 已作废（2026-10-08 17:4x 用户质疑后实证撤除）

原以为 `mic4_uni_test_config.txt` 的 `pcm_card_play 2 / pcm_device_play 0` 是回采通道。  
**对全部 9 个 so 做字节级检索：`mic4_uni_test`、`pcm_card_play`、`pcm_device_play`、  
`uni_test` 均为零引用**；`pcm_hw_config` 只被 `libuni4michal.so` 引用、`MicArrayConfig`  
只被 `libUniMicArray.so` 引用。⇒ **该文件是出厂/产线测试残留，HAL 运行时根本不读。**  
（文件名里那个 `test` 就是答案，之前不该拿它当证据。）

| 关键字                               | 引用它的库                    | 判定         |
| --------------------------------- | ------------------------ | ---------- |
| `pcm_hw_config`                   | `libuni4michal.so` ×1    | ✅ 运行时真读    |
| `MicArrayConfig`                  | `libUniMicArray.so` ×1   | ✅ 运行时真读    |
| `AecInitModel`                    | `libUniMicArray.so` ×2   | ✅ AEC 库要加载 |
| `mic4_uni_test` / `pcm_card_play` | **零引用**                  | ❌ 产线测试残留   |
| `wopt` / `wopt.bin`               | **零引用**（可能由 sprintf 拼路径） | ❓ 待定       |

⇒ **回采 PCM 的 card/device 目前无实证**，只剩代码里 `aec_pcm` 句柄这一条间接证据。

### 证据 4：JNI 层**没有任何喂参考的入口**（19 个导出方法全列）

`libUni4micHalJNI.so` 导出只有：`initHal / releaseHal / openAudioIn / closeAudioIn /
startRecorder / stopRecorder / readData / set4MicDebugMode / close4MicAlgorithm /
set4MicWakeUpStatus / get4MicDoaResult / get4MicBoardVersion / set4MicDelayTime /
set4MicUtteranceTimeLen / set4MicOneShotStartLen / set4MicWakeupStartLen /
set4MicOneShotReady / get4MicOneShotReady`（+ `-edata/__bss_start/_end`）。  
**零 ref / echo / write / play 相关符号，字符串表同样全空。**

⇒ **参考信号 100% 来自硬件回采（aec_pcm / card2 回采），不需要 app 喂一帧数据。**  
这推翻了之前「B 通路无 AEC 参考入口 ⇒ 必然自环」的推论——**不是没有参考，是参考在板子上。**

### 证据 5：AEC 算法实体在 `libUniMicArray.so`（不是 libaec.so）

```
aec_init / aec_process / aec_reset / aec_release / aec_setOptionInt / aec_getOptionInt
aec_filterbank_init / compute_bank / compute_bank_average / compute_psd / release
AEC_FilterBank / 14AEC_FilterBank / AECPARM / echo_get_residual / _neon_ee_loop
AecFilterNum  AecFrameShift  aec_filter_num  aec_nshift  aec_out_len  echo1 echo2
/work/local/majl/source_code/C_code/**BargeIn**/jni/aec_api.cpp   ← 原厂 barge-in 实现
```

滤波器尾 = `AecFilterNum(12) × AecFrameShift(256)` = **3072 样本 ≈ 192ms**（16k），  
比我们自研评估的 600 样本 / 37.5ms 长 5 倍 —— 这才是能覆盖音箱声学回声尾的量级。  
（注：`AecInitModel.bin` 在 `/system/usr/uni_4mic_config/` **不存在**，走的是内置默认模型。）

### 配置清单（`/system/usr/uni_4mic_config/`，2018-04-28）

| 文件                         | 大小     | 要点                                                                                                                                             |
| -------------------------- | ------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `MicArrayConfig.ini`       | 343    | `ArrayType=circle MicNum=4 MicDistance=3.5 HasCentre=0 SpeakerNum=2 Amplify=1` + `[AEC] AecFrameShift=256 AecFilterNum=12`                     |
| `MicArray_config.txt`      | 159    | `mic_num 4 speaker_num 2 aec_filter_num 12 aec_nshift 256 amplify 1` + 4 麦坐标（r=3.5cm）                                                          |
| `pcm_hw_config.txt`        | 173    | `pcm_card 1 / pcm_device 0 / i2s_bits 32 / linear_4mic 0 / no_vad 0 / high_priority 1 / aec_enlarge_4mic 2 / debug_file_path /sdcard/unidata/` |
| `pcm_hw_config_chance.txt` | 112    | 同上但 `aec_enlarge_4mic 1`，**无** `mic_array_rw_path`/`no_vad`/`high_priority`                                                                    |
| `mic4_uni_test_config.txt` | 198    | `pcm_card_play 2 / pcm_device_play 0 / thr1-4 / doa 145`                                                                                       |
| `wopt.bin`                 | 1.18MB | 波束成形权重                                                                                                                                         |

### 结论与路线决策

|         | 小讯（原厂）                                                  | 小智（现役 A 通路）                                     |
| ------- | ------------------------------------------------------- | ----------------------------------------------- |
| 进程/域    | `com.phicomm.speaker.device` uid 10008 **platform_app** | `cn.workbuddy.r1client` uid 10012 untrusted_app |
| 采集      | 自身进程 `libUni4micHalJNI` 直开 ALSA（B 通路）                   | `AudioRecord` → AudioFlinger → HAL（A 通路）        |
| 四麦波束    | ✅ use_4mic=1                                            | ✅（AudioFlinger 侧也在跑 UNI_4MIC）                   |
| **AEC** | ✅ **aec_on=1，echo_num=2，192ms 尾**                       | ❌ 实测 19.7dB 回声，零衰减                              |
| 播歌唤醒    | ✅ **远处轻喊可唤醒（用户实测）**                                     | ❌ 喊不醒                                           |


**⇒ 差距不在算法，在「能不能把 HAL 的 AEC 段跑起来」。**  
`libUni4micHalJNI` 的 `openAudioIn()` 一旦成功，板载回采参考 + 192ms AEC 就**白拿**，  
app 侧一行代码都不用写。而 `openAudioIn()` 直开 ALSA 需要 platform_app 域 ——  
**这唯一指向 Janus 顶替（把小智塞进 `com.phicomm.speaker.device`）**。

---

## 6.11 ⚠️ 关键未决：AEC「配置说开了」≠「真在削回声」（2026-10-08 17:4x）

上面 §6.10 的证据 1/2/4/5 全是**静态 + 配置项**证据，只证明「AEC 被配置启用、算法库存在、  
代码里预留了回采句柄」，**没有任何一条直接证明音频真的被削掉了**。

### 为什么拿不到直接证据：HAL dump 默认只写 WAV 头

```
44  2026-10-08 17:33  waked_file_2aec.wav    ← 正是用户播歌唤醒那次
44  2026-10-08 17:33  waked_file_4mic.wav
44  2026-10-08 17:33  waked_file_out.wav
 0  2026-10-08 17:33  waking_file_*.wav
```

`libuni4michal.so` 有 `file_2aec_data_size` / `file_out_data_size` / `header_2aec` /  
`header_out` ⇒ **dump 的数据量是配置控制的，出厂值为 0**，只 `open` + 写 44B 头。  
要真 dump 必须改 `/system/usr/uni_4mic_config/`（只读挂载，无 root）⇒ **此路不通**。

### 另一个替代假设（同样能解释「播歌能唤醒」）

原厂 `aec_enable=true` 时做的是：**TTS/播放期唤醒引擎常开（barge-in）+ `effectWakeupBenchmark`  
动态抬阈值**（平时 -9.1 / 音乐三档 -11.3、-12.3、-13.3 / 连续对话 5s 窗口 -11.3），  
**而不是半双工 doStopWakeup**。也就是说：

> **「播歌能唤醒」可能根本不是 AEC 的功劳，而是「波束成形（空间滤波）+ KWS 阈值自适应」的功劳。**

这两者对**路线决策的影响不同**：

| 假设            | 若成立，jp2 拿到的是什么                         |
| ------------- | -------------------------------------- |
| A. AEC 真在削    | 上行语音本身就干净，KWS 和 **ASR 双双受益**           |
| B. 只有波束 + 抬阈值 | 上行仍混着音乐，**只有 KWS 能扛**（靠阈值），ASR 在播歌时会变差 |

### 区分实验（无需 root，5 分钟）

**播歌时唤醒小讯后，再说一句命令**（如「下一首」/「暂停」/「音量调小」），看能否正确执行：

- **能执行** ⇒ 假设 A（AEC 真在削，ASR 阶段音频是干净的）→ jp2 顶替价值极高
- **唤醒了但命令听错/听不清** ⇒ 假设 B（只有 KWS 靠阈值扛着）→ jp2 顶替后仍需自己解决  
  ASR 期回声，但**唤醒问题**照原厂「抬阈值」抄即可解决

（顺带也能确认：用户说的「远处轻轻喊也是能识别到的」里的「识别」是唤醒还是唤醒+执行。）

### 对当前路线的影响

**不变**：无论 A 还是 B，这套能力都只在 B 通路（小讯自身进程 `libUni4micHalJNI` 直开 ALSA）  
里存在，A 通路（`AudioRecord`）实测拿不到 ⇒ **Janus 顶替仍是唯一正路**。  
**变的**：jp2 内的防回环策略应按「TTS 期 KWS 常开 + 抬阈值」为主来设计，  
不要指望「AEC 一开就万事大吉」。

### 下一步（按性价比）

1. **先做 §6.11 的区分实验**（播歌唤醒后下命令），确定是 A 还是 B。
2. **判据先行**：装 jp2 后抓 `logcat | grep UNI_4MIC`，确认出现  
   `aec_on=1` + `echo_num=2` + `use_4mic=1`。出现 ⇒ AEC 白拿到手；不出现 ⇒ 查  
   `openAudioIn` 入参与 `initHal` 配置是否对齐小讯（`aec_enlarge_4mic=2`、`no_vad=0`、  
   `high_priority=1`、`mic_array_rw_path=/system/usr/uni_4mic_config/`）。
3. 重跑 §6.8 的同款对照（播报期测 rms），预期应从 1938 掉到接近底噪量级。
4. 若 AEC 生效，之前的 `echoGate` / `holdSilence` / `halfDuplex` 三件治标件可全部拆除，  
   改回原厂策略：**TTS 期 KWS 常开 + 抬阈值（barge-in）**。

---

## 6.13 ★★★ mediaserver 那份 HAL **根本没接四麦算法**（2026-10-08 18:2x，静态实证）

用户提示「实际有用的版本可能在 mediaserver 里」→ 顺着查，**结论是反直觉的：库在 mediaserver  
里，但算法没被接上。**

### 证据：`audio.primary.rk30board.so` 的符号调用面

NEEDED：`libuni4michal.so`（**标准版，非 chance**）+ `libUniMicArray.so` + `libUniLinearMicArray.so`

| 符号                                        | 小讯 JNI（B 通路） | mediaserver HAL（A 通路） |
| ----------------------------------------- | ------------ | --------------------- |
| `uni_4mic_hal_init`                       | ✅            | ❌ **未导入**             |
| `uni_4mic_pcm_start` / `pcm_stop`         | ✅            | ❌ **未导入**             |
| `uni_hal_4mic_array_init/set/get/process` | ✅            | ❌ **未导入**             |
| `uni_4mic_board_init`                     | ✅            | ❌ 未导入                 |
| `uni_4mic_pcm_open` / `read` / `close`    | ✅            | ✅ **只有这 3 个**         |
| `dlsym` / `dlopen`（动态兜底）                  | —            | ❌ **无** ⇒ 不可能动态加载     |

（该 HAL 只 import 了 tinyalsa 的 `pcm_start/pcm_stop`，**不是** uni 的。）

而 `libuni4michal.so` 导出 67 个符号，含 `uni_4mic_hal_init`、`uni_hal_4mic_array_init/set/get/
process`、`uni_4mic_board_init`、`uni_4mic_get_hw_param`、`top_aec_enlarge` —— **mediaserver 一个都没接**。

### 正确的调用序列（小讯的做法）

```
uni_4mic_hal_init()  →  uni_4mic_pcm_open()  →  pcm_start  →  read  →  stop  →  close
                     ↑ mediaserver 从这一步就跳过了
```

### 结论

**A 通路（`AudioRecord`）拿到的是 card1 的裸 PCM，波束成形和 AEC 算法链根本没有初始化。**

一次解释掉全部历史现象：

- 播报期上行 rms 1938、19.7 dB 回声**零衰减** ⇒ 没有 AEC
- 录音内 SNR 仅 **+3.1 dB**（vs B 通路 +18.4 dB）⇒ 没有波束
- `D/UNI_4MIC(209)` 那些 PCM dump 只是 `uni_4mic_debug_print` 在 read 路径打的原始帧，  
  **不代表算法在跑**（之前把它当成"mediaserver 在算四麦"的证据是误读）

**⇒ 链接了库 ≠ 调用了算法。** 之前 §6.10 说的"任何 AudioRecord 都在吃 4mic 增强"是错的。

### 对路线的影响

- 要吃到四麦（波束 + AEC），**必须走 B 通路的完整 `uni_4mic_hal_init` 链路** ⇒ **仍需 Janus 顶替**。
- 自研 WebRTC AEC **补不了**波束带来的语音拾取增益（15 dB 量级），只能解决喇叭回声一项。
- ⚠️ 顶替前需先把整套 `Uni4micHalJNI` 调用代码加回源码（现役 v76 dex 里 `Uni4micHalJNI`  
  字符串计数为 **0**，已被删净）。

---

## 6.14 v1.3.67：把四麦 HAL 链路加回主线（2026-10-08 18:3x，已编译验证）

§6.13 结论「顶替前需先把整套 `Uni4micHalJNI` 调用代码加回源码」——本节完成。

### 交付物

| 文件                                 | 说明                                             |
| ---------------------------------- | ---------------------------------------------- |
| `R1/apply_v1.3.67_uni4mic.py`      | 补丁脚本（幂等，逐条 `present` 判据，失败会报锚点出现次数）            |
| `R1/patches/v1.3.67_uni4mic.patch` | 同样改动的 unified diff（4085 行，含新增文件），可 `patch -p1` |

构建树 = **`/workspace/r1-client`**（`build.sh` 的 `PROJ` 默认值），versionCode 76 / 1.3.55，  
与设备实测一致。**Windows 侧 `R1/src/full/r1-client` 是旧副本（59KB AudioCapture，含当年代码），  
不是构建树** —— 别按它判断现状。

### 加了什么

1. **新增 `src/com/unisound/jni/Uni4micHalJNI.java`**  
   签名逐条照抄原厂 smali（`janus/smali_orig/.../Uni4micHalJNI.smali`），关键两点：
   - native 名是 **`private native initHal(int)` / `releaseHal()`**，  
     `init(int)` / `release()` 是 **Java 包装**（内含 `b` 标志位去重）——  
     当年 stub 把它们写成普通方法只是编译期桩，照抄会绑不上 JNI。
   - `static { System.loadLibrary("Uni4micHalJNI"); }` 予以 try/catch，  
     并暴露 `isLibLoaded()` / `getLoadError()` 供诊断（**不改变 native 绑定面**）。
2. **AudioCapture**：`useUni4mic` / `uniTakeCh0` 开关；`startUni4mic()` / `stopUni4mic()` /  
   `uniRead()`（带 v1.3.37 配速 64000B/s + 短读 4ms 退避，防 `R1MicReader` 98% 的老 bug）/  
   `uniDownmix()`（2ch→mono，可只取 ch0）/ `deliver()`（mono 帧后处理，两条读源共用）。  
   HAL 出 2ch ⇒ 累积满 `UNI_FRAME_BYTES=3840` 才产出 1 帧 mono（960 样点）。
3. **R1ClientService**：`files/src_uni4mic`（开四麦）、`files/uni_ch0`（只取 ch0）
   - 装配日志 `audio cfg: src=UNI4MIC-HAL ... (flags: ... src_uni4mic=... uni_ch0=...)`。

默认**全关**，任一环节失败自动回落 AudioRecord —— 与现役 v1.3.55 行为逐字节一致。

### 编译验证（Windows 侧，JDK17）

```
javac -source 8 -target 8 -encoding UTF-8 \
  -classpath android.jar:kotlin-stdlib.jar:okhttp.jar:okio.jar:sherpa-onnx-classes.jar \
  -sourcepath <patched>/src  AudioCapture.java R1ClientService.java Uni4micHalJNI.java
→ 0 error（仅 TtsPlayer 的 deprecation 警告）
```

### ⚠️ 权限前提（决定它现在能不能跑通）

直开 ALSA 需要 **platform_app** 域；本包是 untrusted_app（uid 10012）。  
预期在 `openAudioIn` 就失败并回落 —— **但日志会写明失败点**，这正是下一步要的取证：

| 日志                            | 含义                             |
| ----------------------------- | ------------------------------ |
| `uni4mic so NOT loaded (...)` | 系统 so 加载失败                     |
| `uni4mic init(1) -> r`（r≠0）   | HAL 初始化被拒                      |
| `openAudioIn(2) -> handle=0`  | **/dev/snd 权限或 card1 被占**（最可能） |
| `startRecorder -> s`（s≠0）     | 设备流启动失败                        |

真正跑通要靠 Janus 顶替（`com.phicomm.speaker.device`）；本补丁是它的前置。

---

## 6.15 ★★★ 四麦路径失败点实测定案：**SELinux 域卡死**（2026-10-08 18:5x）

v1.3.67 装到设备、`touch files/src_uni4mic` 后的真实结果（`logcat -s R1CLIENT`）：

```
I/R1CLIENT: audio cfg: src=UNI4MIC-HAL ... src_uni4mic=true uni_ch0=false
I/R1CLIENT: uni4mic init(1) -> 0                          ← ✅ HAL 初始化成功
I/R1CLIENT: uni4mic board=UNI_4MIC_HAL_ANDROID_V1.1 dbg=0 ← ✅ 版本号读到
I/R1CLIENT: uni4mic openAudioIn(2) -> handle=0 (0x0)      ← ❌ 卡在这
W/R1CLIENT: uni4mic openAudioIn returned NULL -> fallback AudioRecord
```

### 根因：不是权限位，不是设备占用，是 SELinux

```
ls -laZ /dev/snd/
  crwxrwxrwx system audio  u:object_r:audio_device:s0 pcmC1D0c   ← 0777，POSIX 完全放开
  crwxrwxrwx system audio  u:object_r:audio_device:s0 pcmC2D0c
```

```
dmesg:
  avc: denied { search } for pid=15730 comm="mic-acquire" name="snd" dev="tmpfs"
       scontext=u:r:untrusted_app:s0 tcontext=u:object_r:audio_device:s0 tclass=dir permissive=0
```

**拒的是 `/dev/snd` 目录的 `search`** —— 哪怕节点本身 0777 也根本摸不到路径。  
（tinyalsa 固定拼 `/dev/snd/pcmC%dD%u%c`，绕不开这个目录。）

同时 `for p in /proc/*; do ls -la /proc/$p/fd | grep pcmC1D0c; done` **零命中**  
⇒ **没有任何进程占用 card1**，排除 EBUSY。**纯粹是域的问题。**

### 旁证：代码序列与原厂逐字一致

`janus/smali_orig/.../player/util/Cap4mic.smali` 的 `run4mic()`：

```
const-string v2, "openAudioIn(2)="
const/4 v3, 0x2
invoke-virtual {v7, v3}, Lcom/unisound/jni/Uni4micHalJNI;->openAudioIn(I)J   ← 参数就是 2
```

顺序 `init → set4MicDebugMode → close4MicAlgorithm → set4MicWakeUpStatus → openAudioIn(2)`，  
我们传的也是 2 ⇒ **参数没写错**。

### 域地图（设备实测 `ps -Z`）

| 进程                                                 | uid              | SELinux 域            | 能否开 ALSA                                                  |
| -------------------------------------------------- | ---------------- | -------------------- | --------------------------------------------------------- |
| `/system/bin/mediaserver`                          | system           | `u:r:mediaserver:s0` | ✅（但它只用了 pcm_open/read/close，见 §6.13）                      |
| `com.phicomm.speaker.systemtool / netctl / player` | **system(1000)** | `u:r:system_app:s0`  | 未知（无实测）                                                   |
| `com.phicomm.speaker.device`（小讯）                   | 10008            | `platform_app`       | ✅ **实测**：`AudioSourceImplopenIn uni4micHalJNI status = 0` |
| `cn.workbuddy.r1client`（小智）                        | u0_a9            | **`untrusted_app`**  | ❌ **avc denied**                                          |

### 决定性旁证：jp2 上 B 通路历史上跑通过

早期 jp2（Janus 顶替 `com.phicomm.speaker.device`，platform_app 域）实测  
`src=uni4mic-hal` **成功读到音频**，并复现了 HAL 无参考导致的 TTS 自环复读  
⇒ 证明 platform_app 下 `openAudioIn` 成功、`readData` 有数据。**路径是通的，只差把顶替版重建出来。**

### 下一步：重建 jp2（Janus 顶替）

1. **底座要选当前装的那个 APK**，否则 `pm install -r` 会因签名不符失败。  
   先取：`pm dump com.phicomm.speaker.device | grep -i codePath`，再 `adb pull` 出来当 Janus 底包。  
   （`/system/app/Unisound/Unisound.apk` 24936387 字节，与本地 `diag/pandora/Unisound.apk` 同大小，  
   可作为备选底包。）
2. `janus_pandora2/build_all.sh` 的 `CLIENT` 目前指向 **Windows 旧副本**  
   `R1/src/full/r1-client`（v1.3.4x 时代、且已被主线分叉），必须改成**当前源码**  
   （WSL `/workspace/r1-client`，已含 v1.3.67 四麦代码）—— 需先复制到 Windows 侧可写路径。
3. 装 jp2 后 `touch files/src_uni4mic`，预期日志从 `openAudioIn(2) -> handle=0`  
   变成 `handle=0x…` 且不再出现 fallback。

---

## 6.16 ★★★ jp2（platform_app）实测：**四麦 B 通路成功跑通**（2026-10-08 19:3x）

### 构建与安装
- 构建链：`janus_pandora2/build_v22.sh`（jp2v2，uni_smali **5306 类回编** + v2src 寄生层）
- 底座：`diag/pandora/Unisound.apk`（**其 CERT.RSA 与现装魔改版完全一致**：
  sha256 前 16 位均为 `695b2d6c5502b905`，1549B）⇒ `pm install -r` 不会因签名失败。
- 产物：`jp2v22.apk` 31,421,447 B，Janus 验签 PASS，method_ids 42401/65536。
- **装后进程域**：`u:r:platform_app:s0  u0_a8  com.phicomm.speaker.device` ✅

### ★ 结果：openAudioIn 成功（对比 §6.15 在 untrusted_app 下的 0x0）

```
uni4mic init(1) -> 0
uni4mic board=UNI_4MIC_HAL_ANDROID_V1.1 dbg=0 closeAlgo=0 wakeUp=0
uni4mic openAudioIn(2) -> handle=-1178661136 (0xffffffffb9bf0ef0)   ← 非 0！
uni4mic startRecorder -> 0
AudioCapture started: 16000Hz/out-mono rawCh=2 take=avg **SRC=UNI4MIC-HAL**
mic ACQUIRED (attempt=1) -> unisound KWS paused
...
uni4mic stopRecorder -> 0, closeAudioIn -> 0, release -> 0
```

### 数据真的在流，且底噪显著降低

```
uniIO(5s): reads=134 idle=0 bytes=321600 pace=64000B/s  readMs=1125/5027 (r22%)  down=126 tel=92
micTelem t=6s  rms[70,72,73,71,74,71,83]              peak=384
micTelem t=12s rms[68,68,70,71,105,72,70,72,73,71,74] peak=1760
```

| | A 通路（AudioRecord，untrusted_app） | **B 通路（四麦 HAL，platform_app）** |
|---|---|---|
| 底噪 rms | ~200 | **~70（≈ −9 dB）** |
| 数据率 | — | 321600 B/5s = 64.3 KB/s ✅ 配速命中 |
| CPU | — | read 22% / work 4% |

（`maxRms=0 voicedFrames=0` 是那次**唤醒后没人说话**导致的 25s 无 TTS 超时，非故障。）

### 途中修掉的两个 bug

1. **`files/src_uni4mic` 旗标在 v2src 里只有注释、没有读取点**
   ⇒ 在 `XzBootstrap.java` 音频旗标段补 `AudioCapture.useUni4mic = new File(getFilesDir(),"src_uni4mic").exists()`
   并把 `src_uni4mic=` 加进 `xz audio cfg` 日志。
2. **opus 加载：Java 类初始化顺序陷阱（隐蔽）**
   `XzBootstrap` 里 `OpusCodec.overrideLibPath = …` **赋值静态字段会先触发 OpusCodec 类初始化**，
   静态块跑完时 `overrideLibPath` 仍是 null ⇒ 只剩写死的 `-3`（该目录不存在）⇒ 落到 `files/` ⇒
   SELinux `avc: denied { execute } … platform_app → app_data_file` 拒绝。
   修法：候选列表改成**直接枚举** `/data/app/cn.workbuddy.r1client-{1..4}/lib/{arm,armeabi-v7a}/libr1opus.so`。
   修复后：`loaded r1opus from /data/app/cn.workbuddy.r1client-1/lib/arm/libr1opus.so` → `opus enc=true dec=true`。

### 安装踩坑（重要，下次照做）
- `adb shell "pm …"` → `error: closed`；`adb shell sh -c 'pm …'` → **Git Bash 吃掉单引号**，
  设备只收到 `sh -c pm`，pm 打印 usage。
  **正解：把命令写成脚本 push 到设备再 `adb shell sh /data/local/tmp/xxx.sh`**（`do_install.sh`）。
- 两个不同版本的 adb.exe 抢 5037 端口会导致 daemon 反复重启、TCP 连接掉。
- `pm enable com.phicomm.speaker.device` 会被拒（uid 2000 无权改组件状态），
  但 `pm unhide` 可用；装完 `-r` 本身就会置可用状态。
- 装前必须 **`am force-stop cn.workbuddy.r1client`**：它用 AudioRecord 占着 card1，
  与直开 ALSA 互斥。

### ⚠️ 已知共存问题
原厂 小讯 自己的对话引擎也会响应唤醒并尝试录音 ⇒ 报「启动录音失败」（我们已经占住 HAL）。
下一步需要抑制原厂 dialog（或让它退居纯 KWS 提供者）。

### 下一步测试（无需重编译）
`run-as com.phicomm.speaker.device cat files/r1client.log` 可持久取证（不依赖 logcat 缓冲）：
1. 唤醒后**正常说话** → 看 ASR 转写质量（对照 A 通路的「几乎全错」）
2. **播报/播歌时喊唤醒** → 验证 §6.12 的「喇叭声被 AEC 消掉」是否在 B 通路成立
3. 远场轻喊 → 验证波束带来的拾取增益（对照 SNR +3.1 dB → +18.4 dB）

## 6.17 「叫不应」根因 + jp2v23（v1.3.68）：唤醒自持（2026-10-08 傍晚）

### 现象
装 jp2v22 后用户报「叫不应」。`files/r1client.log` 取证：
19:28/19:32 bus 唤醒成功，19:36 起 `mic acquire attempt 1..12 failed (unisound ASR holding)`，
**19:38 之后零唤醒事件**。

### 根因（两条叠加）
1. jp2v22 **无自建 KWS**（`xz pipeline ready (bus-wakeup mode, no kws)`），唤醒只有原厂
   USC 总线事件，只认「**小讯小讯**」→ 喊「小智小智」必然不应。
2. `files/src_uni4mic` 让会话期直开四麦 ALSA（**独占 HAL**）→ 原厂 KWS 的麦被抢坏后
   回不来 → 连 bus 一起死。主线源码 2026-10-03 注释只说了"独占/放手 1s 复活"，
   实测补充：**B 通路开过一次后原厂就再也回不来**。

### 修复
- 即时：`run-as … rm files/src_uni4mic`（麦归还原厂）。
- 根本：XzBootstrap 加 `startKwsAsync()`，自建 sherpa KWS 常驻：
  - `injectNativeLibDir()`：反射插 `BaseDexClassLoader.pathList.nativeLibraryDirectories`
    （**API22 = `File[]`**，API23+ = `List`），让 sherpa 类的
    `System.loadLibrary("sherpa-onnx-jni")` 命中 r1client 的 lib 目录；
  - `injectAssets()`：反射 `AssetManager.addAssetPath(r1client sourceDir)` → cookie=3，
    `assets/kws` 5 文件齐全；
  - init 成功后 `setKws()` + `startAlwaysOnMic()`（麦常驻，IDLE 也喂 KWS）。

### 构建
`build_v23.sh`：**D8 → R8**（sherpa 是 Kotlin 写的，kotlin-stdlib 必须一起进 dex，
全量会爆 64K；靠 `keep_v23.pro` 裁剪，okhttp/okio/orgjson 走 `--lib`，原厂 dex 已有）。
产物合并 dex 方法数 **44342 / 65536**，v1 验签 PASS。

### 实测
```
inject nativeLibraryDir=/data/app/cn.workbuddy.r1client-1/lib/arm
addAssetPath cookie=3 kws=[decoder… encoder… joiner… keywords.txt tokens.txt]
KwsEngine ready (assets mode) thr=0.05 score=6.0 paths=8 threads=1
kws init=true / AudioCapture started: src=VOICE_COMMUNICATION / always-on mic ACQUIRED
CPU：init 峰值 147%（模型加载）→ 稳态 9.2%
```
⚠️ 唤醒词实际只有「小智小智」（`assets/kws/keywords.txt` 单行）。

## 6.18 原厂唤醒词改成「小智小智」（v1.3.71 / jp2v26，2026-10-08 夜）

### 约束：不能改 resources.arsc
Janus 顶替之所以能覆盖 system app，靠的是 **APK v1(JAR) 签名仍然有效**（前插 dex 落在
签名盲区，见 janus_poc.verify_v1 的「签名盲区 offset 0..6704632」）。
`resources.arsc` 是签名条目，改一个字节 MANIFEST.MF 摘要即失配，且无原厂私钥可重签。
⇒ 唤醒词只能在**自己回编的 dex（uni_smali）** 里改。

（arsc 取证：`小讯小讯` 在 offset 8142/8776，UTF-8 字符串池；`交接手续` 在 8791 与之相邻，
即 `R.array.default_wakeup_words` 的两项。等长替换字节数相同，但仍不可改。）

### 改法
`janus_pandora2/patch_wakeword.py`（幂等）短路 `UserPerferenceUtil` 的两个方法：
| 方法 | 原行为 | 现行为 |
|---|---|---|
| `getDefaultWakeupWord(Context)` | 读 `R.array.default_wakeup_words` | 返回 `["小智小智"]` |
| `getMainWakeupWord(Context)` | SP `main_wakeup_word` + 上面 | 返回 `["小智小智"]` |

覆盖了全部调用者：NativeANTEngine:904/1406/1412/1439、NativeANTEngine$1:68、
DefaultANTEMainVocabProvider:72、NLUDispatcher:582/736（ASR 文本里剥唤醒词）。
smali 中中文写作 `\u5c0f\u667a\u5c0f\u667a`。

### 实证
```
D/NativeANTEngine(2857): setWakeupWord0,words=["小智小智"]playBeep:false
```
（改前：`["小讯小讯","交接手续"]`）
寄生层：`wakeup src=bus:小智小智` 与 `wakeup src=kws:小智小智` 并存，
20:46:22 唤醒 → 20:46:33 TTS「现在是晚上8点46分。」完整对话。

### 抓 logcat 的坑
全量 `logcat -d -v brief > file` 会把 adb 前台拖到 120s 超时（SIGTERM 打断）。
必须 `-t N -s TAG:V`（关键 TAG=`NativeANTEngine`）后写设备文件再 pull。

## 6.19 顶部「休眠标志」= 原厂 doStopWakeup() 被 AEC 开关触发（v1.3.74 / jp2v29）

### 现象
唤醒一次后，再喊「小智小智」无效；音箱顶部亮起休眠标志（该灯亮 = 原厂 KWS 停用）。
但与小智的对话本身仍然正常（连续对话走服务端 VAD，不需要二次唤醒）。

### 根因（smali 实证）
```
NativeANTEngine.doPlayTTS(String)   # 方法体起于 2146
  2182  invoke-virtual {p0}, NativeANTEngine->getAecEnable()Z
        if-nez v0, :cond_4a
  2188  invoke-virtual {p0}, NativeANTEngine->doStopWakeup()V

NativeANTEngine.doStopWakeup()      # 2313
        if isWakeup():  j.b();  setEngineState(0)      ← 就是「休眠」态
```
本机 `sp_aec_enable` 默认 false ⇒ 每次会话（doPlayTTS）后 USC 都把唤醒引擎置 0。

AEC 开关链路：
```
UserPerferenceUtil.getAecEnable(ctx)  → SP unicar_user_settings / sp_aec_enable (default false)
   ↑ ANTConfigPreference.aecEnable（ANTServiceBinder$NativeANTEFactory:96 启动时写入）
AbstractANTEngine.initAecEnableState() (被 NativeANTEngine:576 调) → 字段 aecEnable
```

### 修复
只短路 `NativeANTEngine.doStopWakeup()` 为 `return-void`（**不翻 getAecEnable()**）。
理由：`getAecEnable()` 还有第二个使用点 `NativeANTEngine$b:405`（疑似音频源选择），
翻 true 可能让 USC 换采集通路，风险大；而 doStopWakeup 只有两个调用者
（`AbstractUnsafe.stopWakeup():578`、`doPlayTTS():2188`）。
no-op 后 USC 永久处于 wakeup 态 = 原厂 `aec_enable=true` 时的 **barge-in** 行为
（即逆向报告 §「防回环真策略」里说的正解：KWS 永不失聪，靠自适应阈值而非停唤醒）。
安全性：寄生层只在 `state==IDLE` 才响应 bus 唤醒（日志 `wakeup src=bus:… state=IDLE`），
USC 常开不会在会话中重复抢话。

### 实证
- 安装后 `logcat -s NativeANTEngine`：**doStopWakeup 出现 0 次**
- 21:09:44 `wakeup src=bus:小智小智` → 会话正常（uplink #250、micTelem 连续）
- 归档 `diag/jp2v29-v1.3.74.apk`（44342/65536，v1 验签 PASS）

## 6.20 二次唤醒失效真凶：原厂半双工（AEC 关 → doEnterWakeup(false)）（v1.3.75 / jp2v30）

### v1.3.74 不够
`doStopWakeup()` no-op 只堵住 `doPlayTTS()` 那条支路。真正的开关在 native 回调
`NativeANTEngine$b`：
```
:cond_13e
  v0 = getAecEnable()
  if-nez v0 -> :cond_14e                 # AEC 关 → 进动作块
  v0 = isTTSPlaying()
  if-nez v0 -> :cond_15c                 # AEC 开且未播 TTS → 跳过（保持唤醒）
:cond_14e
  v0 = isTTSPlaying(); if-eqz -> :cond_187
  doEnterWakeup(false)                   # ← 关唤醒
:cond_187
  doEnterWakeup(isPlayBeep())            # 播 TTS 中 → 带 beep 重入
```
原厂半双工：AEC 关 ⇒ 每次交互结束关唤醒，靠后续事件 `doEnterWakeup(true)` 重启。
寄生层劫持后 USC 的 ASR 永不完成、TTS 又被短路（v1.3.70），**重启事件永远不来**
⇒ 唤醒一睡不醒（顶部休眠灯）。

### 修复
`UserPerferenceUtil.getAecEnable(Context)` → 硬编码 `const/4 v0, 0x1; return v0`。
选中原厂 barge-in 分支：KWS 常驻；并解锁 `ANTConfigPreference.effectWakeupBenchmark`
（自适应唤醒阈值，`PhicommMusicHandler:388` / `DeviceCenterHandler:1213` 按音乐场景分档）。

安全性：`getAecEnable()` 全 dex 仅两个消费者（NativeANTEngine:2182、NativeANTEngine$b:405），
**与音频通路选择无关**（此前担心的 $b:405 就是上面这个唤醒保持块）。

### 业务层其他 stopWakeup 调用者（均被引擎层 no-op 兜住）
`SpeechManager.stopWakeup` ← PhicommInitializeHandler:760（isFristBoot）、
MatchProcessor:462、DoubleClickProcessor×N、LongClickProcessor:69；
`ANTEngine.stopWakeup` ← CustomApiManager:2272、**PhicommBindStatusHandler:89**
（`clearWakeupWord()`，设备未绑定时触发，可疑度最高，但唤醒词读取方法已被短路，清不掉）。

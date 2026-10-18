# 结案 (b)：顶替后「音频怎么接出来」—— 源码级 + DEX 级 + 设备级三重取证

> **结论先行（一句话）**：MOD **根本没有「接音频」这个动作** —— 它把原厂整套音频栈（云知声 SDK + 四麦 HAL）**原样沿用**，一行音频代码都没改；
> 因此**也没有任何一个 Binder 服务名被劫持**。恩山帖里"Binder 服务劫持 + AudioFlinger 代理注入"的说法，在 DEX 里**不成立**。
> 附带挖到一个**可能比顶替更重要的事实**：四麦波束成形**同时被编进了标准 Audio HAL**（跑在 mediaserver 里）→ 普通 `AudioRecord` 可能**本来就在吃四麦增强音频**（§四/§六，待一次对照实验拍板）。

取证日期：2026-10-04　设备：R1 @ 192.168.2.5:5555（Android 5.1.1 / API22 / 未 root）

---

## 一、`jojo729/R1_DEVICE`（GPL-2.0，2023）—— 源码级回答

仓库结构：`app/`（主工程）、`NewPackage/`（打包用）、`KMusic/`、`getUserInfo/`、`gradlew`、`install.bat`。
README 亲自点名了"核心代码"，只有三处：

| 文件 | 作用（README 原文） | 实际读下来是什么 |
|---|---|---|
| `app/…/utils/PhicommUtils.java` | 「hook 原始语音命令，采用新的 unisound api」 | **hook 的是 ASR/NLU 结果的 JSON 字符串**（`hookRawAsr(String rawAsr)`），按 `service` 字段分流、改写 `data.result`、把音乐源换成酷我/QQ |
| `app/…/utils/ServiceManagerWraper.java` | 「解决签名验证问题」 | **进程内反射**：把 `ActivityThread.sPackageManager` 和 `PackageManager.mPM` 换成动态代理 |
| `app/…/utils/PmsHookBinderInvocationHandler.java` | 同上 | 代理体里 `info.packageName = ExampleApp.PROCESS_NAME`；**注释掉的那段**才是签名伪造：`info.signatures[0] = new Signature(SIGN)`，SIGN 是硬编码的 **QQ 证书**（`30820253308201bc…`）—— 用于骗过**运行时自校验**，不是安装校验 |

安装方式（README）：「安装 NewPackage-release.apk，**禁用系统 app `com.phicomm.speaker.device`**」。

### 这份源码给出的最重要事实
`PhicommUtils.java` 里**没有任何** `AudioRecord` / `AudioTrack` / PCM / 播放 / 录音 / VAD 代码。
它消费的输入只有 `text`（识别文字）、`service`、`audioUrl`（注释明说是"用户语音跟踪信息，用于判断是否为最终结果"）。

⇒ **音频采集完全在云知声 SDK（`com.unisound.vui`）里，MOD 只动"识别出来之后的文字"。**
⇒ 这也解释了为什么不需要 AudioFlinger 劫持 —— 麦克风从来不由 MOD 打开。

> ⚠️ 注意：这份 GPL 仓库是**早期实验版**（用的是 PMS 反射 hook + 禁用原包），**不是**设备上现役的 1.8.x。现役版的机制见 §二。

---

## 二、现役 1.8.x（闭源付费版）真身 —— DEX 级回答

把设备上真正在跑的 `com.phicomm.speaker.device`（= `pandora.apk`，30,361,519 B，**Janus 文件**）的**前置 DEX** 剥出来（5,425,132 B），与原厂 `Unisound.apk` 内的 `classes.dex`（6,067,068 B）做**类级 diff**：

| | 前置 DEX（MOD 执行体） | 原厂 classes.dex（签名体） |
|---|---|---|
| 类数 | **5,536** | 5,306 |
| 相对原厂 **新增** | **+237** | — |

**新增的 237 个类里，音频相关的数量 = 0。** 构成如下：

| 类别 | 代表 | 数量 |
|---|---|---|
| DNS 库（供 `nslookup` 类小程序） | `org/xbill/DNS/*`、`Ljnamed;`、`Llookup;`、`Ldig;`、`Lupdate;` | ~150 |
| 内嵌 HTTP 服务器 | `fi/iki/elonen/NanoHTTPD*`、`Lcom/xiaofei/server;`+`$HttpdServer` | ~30 |
| 音乐/网络库 | `cn/kuwo/base/utils/*`、`retrofit2/*`、`okhttp3/package_info` | ~15 |
| 自有改动 | `com/phicomm/speaker/device/custom/setting/PhicommSettingHandler`、`com/unisound/vui/handler/session/music/MusicApiHandler`、`nluparser/scheme/CarSettingExtIntent` | **3~4** |

### 「Binder 服务劫持 / AudioFlinger 代理注入」的核查结果
对**两个** MOD 的 DEX 做字符串全扫（device 包 + player 包）：

| 关键词 | device 包 | player 包 |
|---|---|---|
| `AudioFlinger` / `IAudioFlinger` | **0** | **0** |
| `IServiceManager` / `ServiceManagerWraper` | **0** | 0（命中的 `ServiceManager*` 全是 `android.app.ServiceManager` 服务生命周期类） |
| `PmsHook` / `newProxyInstance` | **0** | 0 |
| `MessageDispatchManager`（msgcenter 总线） | ✅ 有 | ✅ 有 |

⇒ **两个 MOD 都没有劫持 Binder 里的音频服务。** 恩山帖那句话要么是指 player 包的播放（DLNA/AirPlay）侧，要么就是论坛口耳相传的失真描述。
⇒ 现役版真正的做法是：**他们拿到了原厂源码，全量重编译，直接在源码里改掉 NLU 结果处理**，再用 **Janus** 让重编译后的包顶着原厂签名装上去。**不需要 hook，因为代码是自己的。**

---

## 三、音频到底怎么"来"的 —— 完整链路（Java → JNI → tinyalsa → card1）

### 3.1 Java 层：`FourMicAudioManagerInterface`

`Lcom/unisound/sdk/x;`（obfuscated；日志 tag 自称 `FourMicAudioManagerInterface`），是个 `Thread`。
字节码还原（`is4micMode` 开关是**核心分支**）：

```java
run():
  c()  -> Uni4micHalJNI.getInstance().init(x)        // initHal()
       -> set4MicDebugMode() / close4MicAlgorithm() / set4MicWakeUpStatus()

  a()  -> if (this.j /*is4micMode*/) {               // ← if-eqz 分支
             this.i = Uni4micHalJNI.openAudioIn(this.l)   // ★ 4麦路径
             Uni4micHalJNI.startRecorder(this.i)
             if (status != 0) return -1
         }
         this.p = new AudioRecord(source, rate, chCfg, fmt, bufSize)
         if (this.p.getState() != STATE_INITIALIZED) return -1
         this.p.startRecording()

  loop -> a([B,I):
             if (is4micMode) n = readData(handle, [B, len)   // 4麦 native 输出
             else            n = this.p.read([B, len)        // 普通 AudioRecord
             listener.onRecordingData([B, n)

  b()  -> stopRecorder(handle) / closeAudioIn(handle) / AudioRecord.stop() / release()
```

可调选项（`setOption` 接受的 key，实测字符串）：
`asr_sampling_rate`、**`is4micMode`**、`ASR_FOURMIC_ISDEBUG`、`ASR_FOURMIC_CLOSE_4MICALGORITHM`、
`wakeupStatus`、`4mic_ch_num`、`use4mic`、`recordingPacSize`。

引擎侧事件常量（`FOURMIC_*`）：
`FOURMIC_RECORDING_BEGIN/END/ERROR`、`FOURMIC_DOA_RESULT`、`FOURMIC_BOARD_VERSION`、`FOURMIC_CHANGE_CHANNEL`、
`FOURMIC_IS_RK_PLATFORM`、`FOURMIC_IS_RK_SINGALCHANEL`、`FOURMIC_USE_ASR_AS_VAD`、`FOURMIC_PCM_TEST`、`FOURMIC_MODEL`、
`FOURMIC_OUT_SET_4MICWAKEUPSTATUS/DELAYTIME/UTTERANCETIME`。

### 3.2 JNI 层：`Uni4micHalJNI` → 直开 ALSA

`Lcom/unisound/jni/Uni4micHalJNI;`：`loadLibrary("Uni4micHalJNI")`，API 全集

```
initHal(int)  releaseHal()
openAudioIn(int) -> long        startRecorder(long)      readData(long,[B,int)
closeAudioIn(long)  stopRecorder(long)
get4MicDoaResult()  get4MicBoardVersion()  get4MicOneShotReady()
set4MicWakeUpStatus  set4MicOneShotReady  set4MicOneShotStartLen
set4MicWakeupStartLen  set4MicUtteranceTimeLen  set4MicDelayTime
set4MicDebugMode  close4MicAlgorithm
```

### 3.3 Native 层（全在 `/system/lib`，**不在 APK 里**）

| so | 大小 | 角色 | 关键导入/导出 |
|---|---|---|---|
| `libUni4micHalJNI.so` | 9,312 | JNI shim | 导出 `uni_4mic_hal_init/release`；导入 `uni_4mic_pcm_open/read/start/stop/close` |
| `libuni4michal.so` | 25,820 | 主逻辑 | **NEEDED `libtinyalsa.so`**；导入 **`pcm_open` / `pcm_read` / `pcm_close` / `ioctl` / `open`** ⇒ **绕过 AudioFlinger 直开 ALSA** |
| `libUniMicArray.so` | 535,444 | 环形阵列算法 | 导出 `Unisound_MicArray_Init/Process/Compute_DOA/Set/Get/Reset/Release` |
| `libUniLinearMicArray.so` | 711,736 | 线性阵列 | — |
| `libaec.so` | 152,832 | AEC | — |
| `libuni4michalchance.so` | 25,824 | 备用变体 | — |

> APK 自带的是另一批（`libaec/libasrfix/libuscasr/libyzstts/libofflinenlujni/libledjni/libBugly/liblocSDK7b`）；
> **四麦那几个 so 一个都不在 APK 里，全从 ROM 的 `/system/lib` 加载** —— 这是"谁都能 loadLibrary，但只有特权域能开 `/dev/snd`"的关键。

### 3.4 设备实测：配置 + 声卡

```
/system/usr/uni_4mic_config/
  pcm_hw_config.txt      pcm_card 1 / pcm_device 0 / i2s_bits 32 / linear_4mic 0
                         aec_enlarge_4mic 2 / mic_array_rw_path <本目录> / debug_file_path /sdcard/unidata/
  pcm_hw_config_chance.txt  同上，aec_enlarge_4mic 1
  MicArrayConfig.ini     ArrayType=circle / MicNum=4 / MicDistance=3.5 (cm) / HasCentre=0
                         SpeakerNum=2 / Amplify=1 / AecFrameShift=256 / AecFilterNum=12
  MicArray_config.txt    mic_num 4 / speaker_num 2 / micarray_pos:
                         0.035 0.000 / 0.000 0.035 / -0.035 0.000 / 0.000 -0.035   ← 半径 3.5cm 十字
  mic4_uni_test_config.txt  pcm_card_play 2 / thr1..4 / doa 145
  wopt.bin               1,184,304 B（阵列权重表）
```

`/proc/asound/pcm`（**card1 只有 capture**）：

```
00-00: HDMI PCM                                  playback 1
01-00: ma4 codec pcm ma4-0                      capture  1     ← ★ 四麦阵列入口 (MA4)
02-00: AK7755 PCM ak7755-AIF1-0                 playback 1 : capture 1
03-00: SPDIF PCM Playback                       playback 1
```

card1 实时状态（抓取时）：
```
state: RUNNING      owner_pid: 22165
hw_params: S32_LE / channels: 2 / rate: 96000 / period 2048 / buffer 16384
```
- `owner_pid 22165` = **mediaserver（pid 210）的一个线程**（`cat /proc/22165/cmdline` → `/system/bin/mediaserver`，`ls -Zd` → `u:r:mediaserver:s0`；`ps` 不列线程所以只看到 210）
- `S32_LE` 与配置里的 `i2s_bits 32` 吻合 ⇒ **4 个麦以 32bit 打包在 2 通道里**（这正是 `tinycap` 看到的 `2 ch, 32 bit` 的原因，不是"只有 2 个麦"）

⇒ **"四麦" = card1 `ma4 codec`，不是 AK7755（card2）。** 之前"AK7755 不在通路上"的说法可以升级为：**AK7755 是另一张卡，我们和原厂都不走它做采集。**

---

## 四、最大的意外：四麦算法**同时**被编进了标准 Audio HAL

```
/system/lib/hw/audio.primary.rk30board.so   (120,552 B)
  NEEDED: libUniLinearMicArray.so, libUniMicArray.so, libuni4michal.so,
          libtinyalsa.so, libaudioroute.so, libaudioutils.so, ...
  导入:   uni_4mic_pcm_open / uni_4mic_pcm_read / uni_4mic_pcm_close,
          pcm_open / pcm_read / pcm_start / pcm_stop / pcm_write
  字符串: "uni_4mic_pcm_open success/failed",
          "get_next_buffer() uni_4mic_pcm_read error %d",
          "/proc/asound/card1/id", "now is 2 snd card mode", "now is 3snd card mode",
          "input_source", 路由名 voice-rec-main-mic / media-main-mic / communication-main-mic
```

这个 HAL 由 **mediaserver 加载**。设备实证：
- `logcat` 里持续刷 **`D/UNI_4MIC(210): [UNI_4MIC]:<DEBUG> …`** —— pid 210 就是 mediaserver
- `/proc/asound/card1/pcm0c/sub0/status` = `RUNNING`，owner 是 mediaserver 的线程

⇒ **四麦波束成形是在 mediaserver 的 Audio HAL 里跑的**；App 侧那套 `Uni4micHalJNI` 更像是"配置 + 第二条采集路"。

### 这意味着什么（重要）
**任何 App 走普通 `AudioRecord`，理论上拿到的都可能是四麦增强之后的音频。**
如果成立，那"必须 Janus 顶替才能吃四麦"这个前提**就不成立**了 —— 问题会从"权限"回退到"**ASR 前端/服务端**"。

这一条必须用实验拍板（§六）。**在拍板之前，不要把 D 路线当成唯一正解。**

---

## 五、"接音频"到底需不需要 platform_app 域？

| 场景 | 是否需要 | 依据 |
|---|---|---|
| 走 **AudioFlinger**（`AudioRecord`） | **不需要** | 普通 app 权限即可，mediaserver 代为开设备 |
| **绕过 AudioFlinger 直取**（`loadLibrary("uni4michal")` + `pcm_open(card1)`，或 `Uni4micHalJNI`） | **需要** | `libuni4michal.so` 直开 `/dev/snd/pcmC1D0c`；`untrusted_app` 对 `/dev/snd` 是 `denied{search}`，`platform_app` 只有 `denied{getattr}`（search 放行 → 能 open） |

⇒ Janus 顶替（拿到 `platform_app`）解锁的是**第二条**：不经系统混音、直吃 4 麦原始/增强流，还能用 `Unisound_MicArray_*` 自己做波束。
⇒ 但若第一条已够用，**顶替的高风险就不必承担**。

---

## 六、下一步（零/低风险对照实验）—— 判定"AudioRecord 是否已含四麦增强"

| # | 动作 | 风险 | 判读 |
|---|---|---|---|
| 1 | 记录基线：`cat /proc/asound/card1/pcm0c/sub0/hw_params`；`logcat` 里 UNI_4MIC 刷屏频率 | 只读 | 基线 |
| 2 | `am stopservice cn.workbuddy.r1client/.R1ClientService`，看 UNI_4MIC 刷屏是否停 | 可逆 | 若停 ⇒ 刷屏由**我们的采集**触发 ⇒ 我们**已在走 HAL** |
| 3 | 固定距离（1m / 2m）播同一段人声：分别用 ① r1client 上行 wav ② `run-as com.phicomm.speaker.device` 下抓一次 | 低 | 若两者 SNR 相近 ⇒ 音频不是瓶颈 |
| 4 | 把 ①的 wav 丢给 NAS 上的 ASR，看 1m/2m 识别率 | 低 | 与"听懂距离"直接挂钩 |
| 5 | 若 ③ 显示 AudioRecord 也吃四麦、但 ASR 仍差 ⇒ **瓶颈在 ASR/服务端**，走"换 ASR/调 VAD/降噪前端"，**不动设备** | — | 可能省掉整条 D 路线 |

---

## 七、四条路线的重新评估

| 路 | 内容 | 新状态 |
|---|---|---|
| **A** | 接受 0.5m（v1.3.27 已定版） | 已定，保底 |
| **B** | USB 麦 | 待验 OTG 供电 |
| **C** | 手机 / ESP32 当前端 | 待议 |
| **D** | Janus 顶替 → `platform_app` → 直吃四麦 | **技术链路已 100% 打通**：Janus PoC ✅（`VERDICT-janus-poc.md`）+ 音频 API 已摸清（本文）+ 回退路径明确（重装 `Unisound.apk`） |
| **D′（新）** | **不顶替**，先验证「AudioRecord 已含四麦」 | **成本最低、信息量最大 → 建议先做 §六** |

---

## 八、证据文件（`diag/pandora/`）

| 文件 | 说明 |
|---|---|
| `cls_dump.py` / `callers.py` / `mod_dex_analyze.py` | DEX 类表/字符串/调用点/字节码解剖工具（`--full` 打印全指令） |
| `so_dex.py` | ELF 解剖（NEEDED + 未定义符号 + rodata 字符串），用 `ridi` venv 的 pyelftools |
| `hookscan.py` / `listlibs.py` | Binder/服务关键词全扫 / APK 内 native 库清单 |
| `real_device_prefix.dex` | 设备在跑的 device 包**前置 DEX**（5,425,132 B，5536 类） |
| `real_mod_prefix.dex` | `new_EchoService1.8.82.apk` 前置 DEX（5,706,812 B，5187 类） |
| `orig_classes.dex` | 原厂 `Unisound.apk` 的 `classes.dex`（6,067,068 B，5306 类） |
| `_cls/{dev,mod,orig}.txt`、`_cls/{dev,mod}_str.txt` | 类表 / 字符串表（diff 用） |
| `lib/*.so` | 拉回的四麦 HAL 套件 + `audio.primary.rk30board.so` + `librk_audio.so` |
| `_hal_log.txt` | `UNI_4MIC` 刷屏样本（pid 210 = mediaserver） |
| `VERDICT-janus-poc.md` / `VERDICT-pandora-disguise.md` | Janus 前置结案 / 伪装可行性结案 |

### 外部来源
- `github.com/jojo729/R1_DEVICE`（GPL-2.0，2023-05-31 最后提交）—— 早期实验版源码，含 `PhicommUtils` / `ServiceManagerWraper` / `PmsHookBinderInvocationHandler`
- 恩山 thread-8472542 —— 点明 CVE-2017-13156；但其"AudioFlinger 代理注入"说法**与 DEX 证据不符**

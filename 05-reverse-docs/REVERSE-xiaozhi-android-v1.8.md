# 逆向报告：R1 上能正常收音发声的「小智」APK

**样本**：`C:\Users\shaoh\WorkBuddy\R1\一键安装Unisound_v1.6.13\apps\app-armeabi-v7a-release.apk`
**逆向工具**：androguard 4.1.4（Python）+ 自写反汇编器（Windows 侧，WSL 被安全策略拦截）
**工作目录**：`C:\Users\shaoh\WorkBuddy\R1\reverse\unisound-v1.6.13\`

---

## 0. 一句话结论

**这不是小讯，是社区版安卓小智客户端 `com.huihongcloud.xiaozhi` v1.8**。它是一个**通用安卓 App，零 R1 特化代码**，用**标准 AudioRecord** 在 R1 上就能正常收音 —— 这直接推翻了我们此前「设备身份/HAL 归属导致我们拿到坏流」的假设方向。它能在 R1 上跑通，靠的是下面这套**与我们的实现完全不同的录音姿态**。

---

## 1. 样本身份

| 项 | 值 |
|---|---|
| 包名 | **`com.huihongcloud.xiaozhi`** |
| 版本 | versionName **1.8** / versionCode 11（文件名 `app-armeabi-v7a-release.apk` = Gradle ABI split 产物） |
| minSdk / targetSdk | 21 / 33 |
| Application | `com.huihongcloud.xiaozhi.MyApplication` |
| 主 Activity | `MainActivity`（`screenOrientation=2` 强制横屏，`launchMode=1`） |
| 服务 | `ForegroundService`（`foregroundServiceType=0x82` = MICROPHONE\|MEDIA_PLAYBACK，**exported=true**） |
| 自启 | `BootReceiver` ← `RECEIVE_BOOT_COMPLETED` |
| 服务器 | 默认 `wss://api.tenclass.net/xiaozhi/v1/` + `https://api.tenclass.net/xiaozhi/ota/`；作者私有代理 `huihongcloud.cn/api/xiaozhi/{ota,mcp_plugin}`（在 `libxiaozhi.so` 里） |

> ⚠️ 同目录的 `说明.txt`（v1.0~v1.6.13 更新日志、`iotapi.txt`、酷我/蜻蜓FM）**描述的是另一个东西**——「魔改小讯」。这个 APK 里没有任何相关代码。文件夹是复用的安装器壳。

**保护/混淆**：类名被 R8 重打包成 `LA/A;`/`LC/c;` 单字母包 + **控制流平坦化**（一个大 `run()` 内 packed-switch 状态机）+ 类合并；`MainActivity.onCreate` 里有 **签名校验**（读 `META-INF/*.RSA` → X.509 → SHA-256），彩蛋字符串 `"让我看看是谁反编译我"`。

---

## 2. 音频链路全貌（本报告的核心）

### 2.1 采集：`AudioRecord` —— **只开一次，终身不关**

反汇编证据（`LC/c;->run()`，即 ForegroundService 的初始化 Runnable；因 v0 恒为 0，走的是「零值分支」）：

```
#319 const/4  v0, 0
#321 sget-boolean v2, XiaoZhi->o          ← 设置项 voiceSource
#322 if-eqz  v2  →  #326 const/16 v16, 1  │ v16 = audioSource = 1  (MIC)
                 →  #324 const/16 v16, 7  │ v16 = audioSource = 7  (VOICE_COMMUNICATION)
#331 const/16 v18, 1                      ← channelConfig = 1 (CHANNEL_IN_DEFAULT)
#345 const/16 v2,  960
#346 const/16 v20, 960                    ← bufferSize = 960 字节
#347 new-instance v2, Landroid/media/AudioRecord;
#348 const/16 v17, 16000                  ← sampleRate = 16000
#349 const/16 v19, 2                      ← audioFormat = ENCODING_PCM_16BIT
#351 invoke-direct/range v15..v20, AudioRecord.<init>(IIIII)
#352 iput-object v2, ForegroundService->T   (字段 T 常驻持有)
#356 AudioRecord.getAudioSessionId()
#358 AcousticEchoCanceler.create(sessionId)
#362 AudioEffect.setEnabled(true)
...
#415 AudioRecord.startRecording()        ← 全 APK 仅此一处
```

```java
// 还原出的等价代码（默认配置）
AudioRecord rec = new AudioRecord(
        AudioSource.VOICE_COMMUNICATION,   // 7 —— 默认；voiceSource=false 时为 MIC(1)
        16000,
        /*channelConfig*/ 1,               // CHANNEL_IN_DEFAULT（作者笔误，见 §5.3）
        AudioFormat.ENCODING_PCM_16BIT,
        /*bufferSizeInBytes*/ 960);
AcousticEchoCanceler aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
if (aec != null) aec.setEnabled(true);
rec.startRecording();
```

**关键事实**：全 APK **没有任何 `AudioRecord.release()` 调用**；`AudioRecord.stop()` 只在 `ForegroundService.onDestroy()` 出现一次。
→ **它 open 一次、startRecording 一次、之后就永久持有这条流，直到进程死亡。**

### 2.2 播放：`AudioTrack` **与录音共用同一个 audio session**

```
#374 const/16 v18, 7680                   ← bufferSize = 7680
#386 new-instance v0, Landroid/media/AudioTrack;
#388 AudioRecord.getAudioSessionId()  →  v20
#390 const/16 v15, 16000
#391 const/16 v16, 4                       ← CHANNEL_OUT_MONO
#392 const/16 v17, 2                       ← PCM_16BIT
#393 const/16 v19, 1                       ← MODE_STREAM
#395 AudioTrack.<init>(streamType, 16000, 4, 2, 7680, 1, sessionId)
#402 AudioTrack.play()
#409 AudioTrack.setVolume(XiaoZhi.r0 / 10f)   // r0 默认 10 → 1.0
```

```java
AudioTrack trk = new AudioTrack(
        streamType,                       // streamTypeVoice ? 3 : 0
        16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        7680, AudioTrack.MODE_STREAM,
        rec.getAudioSessionId());          // ★ 复用录音 session
```

> **这是全案最可能的解题点**：播放与录音绑定同一 audio session，HAL 会把整条链路归入「通话/语音」用例，硬件 AEC 也才有回采参考。我们的实现里播放和录音是两个互不相干的 session。

### 2.3 读帧 → 编码 → 上行

`com.huihongcloud.xiaozhi.a`（采集线程 `Runnable`）：

```java
short[] buf = <累积到 960 shorts 才发一帧>;      // const/16 v2, 960；if-ge v9, v2 循环累积
AudioRecord.read(short[] data, int off, int sizeInShorts);  // 读进 short[]，非 byte[]
// → WebRTC APM（可选）→ KWS → Opus 编码
```

Opus 编码器（`J/h;->run()` + `a;->run()`）：

```java
Opus.encoderInit(SampleRate._16000, Channels.mono, Application.audio);
Opus.encoderSetBitrate(Bitrate.auto());
Opus.encode(byte[] pcm, FrameSize._960);     // 960 样本 @16k = 60 ms
```
→ 与 hello 声明字符串完全对齐：`"format":"opus", "sample_rate":16000, "channels":1, "frame_duration":60`

### 2.4 降噪 / AEC 的三层结构

| 层 | 开关（`MyPrefs`） | 默认 | 实现 |
|---|---|---|---|
| 系统 AEC | `androidSystemAec` | **开** | `AcousticEchoCanceler.create(rec.getAudioSessionId())` + `setEnabled(true)` |
| 软件 AEC | `softwareAec` | 关 | `com.huihongcloud.apm.Apm` → `libwebrtc_apm.so`（WebRTC APM：`ProcessStream` 近端 / `ProcessReverseStream` 远端回采 / `setDelay` 自适应 / `author("zhonghuihong")` 授权） |
| KWS 增益 | `kwsGain` (int) | 0 | 唤醒前增益 |

`Apm.create(0, 3, 0, 24, 0.8f, 1, 1)`（`z.b()` 里的实参；`AEC3_FILTER_BLOCKS`/`AEC3_INITIAL_STATE_SECONDS` 为常量字段）。

### 2.5 唤醒（KWS）

`ForegroundService` 里创建 sherpa-onnx `KeywordSpotter`（来自 **AssetManager**）：

```java
OnlineTransducerModelConfig  encoder="kws/encoder-epoch-12-avg-2-chunk-16-left-64.onnx"
                             decoder="kws/decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
                             joiner ="kws/joiner-epoch-12-avg-2-chunk-16-left-64.onnx"
OnlineModelConfig            tokens ="kws/tokens.txt", modelType="zipformer2"
KeywordSpotterConfig         keywordsFile="kws/keywords.txt"
spotter = new KeywordSpotter(getAssets(), cfg);
stream  = spotter.createStream(<keywords.txt 内容，换行替换为 "/">);
```

`assets/kws/keywords.txt` 内容（**注意：只有 2 个唤醒词，且带多音调变体**）：

```
x iǎo zh ì x iǎo zh ì @小智小智
x iǎo zh x iǎo zh     @小智小智
x iǎo z ì x iǎo z ì   @小智小智
x iǎo z x iǎo z       @小智小智
n ǐ h ǎo x iǎo zh ì   @你好，小智
...（你好，小智 的 4 个变体）
```

> 模型文件与我们 v1.3.0 试过的是**同一套**（`encoder-epoch-12-avg-2-chunk-16-left-64`，WenetSpeech/icefall zipformer2 KWS）。

### 2.6 其余组件

| 库 | 用途 |
|---|---|
| `libxiaozhi.so` (5.8KB) | JNI：`XiaoZhi.a/c/dc/h/m/mcp` + 硬编码 `huihongcloud.cn/api/xiaozhi/{ota,mcp_plugin}` |
| `libsaba.so` (1.0MB) | MMD 3D 数字人渲染器（bullet 物理 + spdlog） |
| `libLive2DCubismCoreJNI.so` | Live2D 数字人（Hiyori） |
| `libwebrtc_apm.so` | WebRTC 音频处理 |
| `libeasyopus.so` + `libopus.so` | Opus |
| Rhino JS 引擎（`org.mozilla.javascript`） | MCP 插件脚本（`assets/plugin/*.json`：app/media/coze/music） |
| `libonnxruntime.so` | ONNX Runtime |

---

## 3. 可调设置项全表（`MyPrefs`）

`MainActivity.E()` 一次性读入静态字段 `XiaoZhi.*`：

| prefs 键 | 字段 | 类型 | 出厂默认 | 含义 |
|---|---|---|---|---|
| **`voiceSource`** | `o` | bool | **true** | **AudioSource：true→VOICE_COMMUNICATION(7) / false→MIC(1)** |
| **`androidSystemAec`** | `p` | bool | **true** | 启用系统 AcousticEchoCanceler |
| `softwareAec` | `q` | bool | false | 启用 WebRTC 软件 APM |
| `streamTypeVoice` | `n` | bool | false | 音频流类型 / AudioManager MODE_IN_COMMUNICATION |
| `btSCO` | `r` | bool | false | 蓝牙 SCO |
| `kwsGain` | `s` | int | 0 | KWS 输入增益 |
| `micMute` | `L` | bool | false | 静麦（KWS 输入静音） |
| `audioFocus` | `T` | bool | false | 请求音频焦点 |
| `isAutoMode` | `e` | bool | false | 自动对话模式 |
| `realTimeMode` | `f` | bool | false | 实时模式（流式播放） |
| `isSingeLine` | `g` | bool | false | 单行字幕显示 |
| `ws` / `otaAddr` / `mac` / `token` | `h`/—/—/`c` | String | `ws://127.0.0.1:8000` / `http://127.0.0.1/ota` / — / `test-token` | 服务器配置（静态初值见 `<clinit>`） |
| `showStatus` / `reportIot` / `transparentBg` / `l2d*` / `hideBtn` … | 其余字段 | — | — | UI / 数字人 |

---

## 4. 与我们 R1 客户端的逐项对照

| 维度 | 本 APK（能正常收音） | 我们的 `cn.workbuddy.r1client` | 差异风险 |
|---|---|---|---|
| **AudioSource** | **VOICE_COMMUNICATION(7)**（默认） | v1.3.5 起 **VOICE_RECOGNITION(6)**；更早 MIC | ★★★ |
| **channelConfig** | `1`（CHANNEL_IN_DEFAULT，实则单声道） | v1.3.5 起 **STEREO + 下混**；v1.3.7 探针测过矩阵 | ★★★ |
| **bufferSize** | **960 B**（=30 ms） | minBuf×倍数（通常 ≥ 2560） | ★★ |
| **系统 AEC** | **开启**（`AcousticEchoCanceler`） | **从未启用**（靠 45s 静音罩掩盖） | ★★★ |
| **播放 AudioTrack** | **与录音共用 audio session** | 独立 session | ★★★ |
| **流生命周期** | **open 一次，终身不关**（无 release） | 按会话开/关；关闭常楔死（泄漏） | ★★★ |
| 读帧 | `read(short[]...)` 累积 960 samples | `read(byte[]...)` 60 ms | — |
| 上行编码 | Opus 16k/mono/60ms/auto bitrate | Opus 16k/…/60ms | 一致 |
| 唤醒 | sherpa-onnx KeywordSpotter（**同一套模型**） | 同（v1.3.x 尝试） | 一致 |
| 数字人 | Live2D / MMD / VRM | 无 | — |

---

## 5. 给我们的三条可执行结论

### 5.1 头号假设（按嫌疑排序）

1. **AudioSource 用 VOICE_COMMUNICATION，而不是 VOICE_RECOGNITION/MIC。**
   R1 的 AK7755 + 厂商 HAL 很可能只在「通话/语音」用例下把输入路由接到真实麦阵；其它 source 下 ADC 输入 mux 停在悬空源 → 正好是我们量到的「0x00/0xff 底噪、RMS≈500 平线、peak 恒 0x0D0D」画像。
2. **播放 AudioTrack 与录音共用 session id + 开系统 AEC。**
   这会让 HAL 把链路整体划入语音用例，并提供硬件回采参考。
3. **流只开一次、永不 release。**
   我们的「开→关→开」循环每次关闭都有概率把输入流楔死（我们已实测到 `Standby:no` 残留 + force-stop 才复活），而它从结构上完全规避了这个问题。

### 5.2 建议的 v1.3.9 最小改动（4 行级）

```java
// 1) 录音：改 source + 单声道 + 小 buffer
rec = new AudioRecord(AudioSource.VOICE_COMMUNICATION, 16000,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 960);
// 2) 系统 AEC
AcousticEchoCanceler aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
if (aec != null) aec.setEnabled(true);
// 3) 播放绑定同一 session（AudioTrack 7 参构造）
track = new AudioTrack(STREAM_MUSIC, 24000, CHANNEL_OUT_MONO, PCM_16BIT, 7680,
        AudioTrack.MODE_STREAM, rec.getAudioSessionId());
// 4) 不再关闭录音流（常驻），把"静音罩"降级为兜底
```
配套：v1.3.7 的 micprobe 矩阵**必须**重跑（上次全 TIMEOUT 是级联污染，第一个 worker 泄漏的 AudioRecord 把后面 6 个全楔死了）——而且这次要**每次探针都新建进程**或**探针之间强制 release**。

### 5.3 必须留意的两个坑

- **channelConfig=1 看着像作者笔误**：作者 `getMinBufferSize` 用的是 16（`CHANNEL_IN_MONO`），构造 `AudioRecord` 却传了 1（`CHANNEL_IN_DEFAULT`）。两者在 HAL 側都解析成单声道，所以能跑。我们实现时建议**两处统一用 `CHANNEL_IN_MONO`(16)**，别照抄 1。
- **别照抄永久持有**：它的 `exported=true` 前台服务 + BootReceiver + 永不释放，意味着**它会长期霸占麦**，在 R1 上会和原厂小讯（USC）互相饿死。我们若采用常驻方案，要保留「总线唤醒优先 / 可退让」的仲裁。

---

## 6. 建议的实机验证实验（R1，不改代码即可做）

1. **装它，跑起来，抓它的音频拓扑**：
   ```bash
   "$A" -s 192.168.2.5:5555 push .../app-armeabi-v7a-release.apk /data/local/tmp/xz.apk && \
   "$A" -s 192.168.2.5:5555 shell /system/bin/pm install -r /data/local/tmp/xz.apk && \
   "$A" -s 192.168.2.5:5555 shell am start -n com.huihongcloud.xiaozhi/.MainActivity
   ```
   然后立刻：
   ```bash
   "$A" -s 192.168.2.5:5555 shell "dumpsys media.audio_flinger | grep -A22 'Input thread'"
   "$A" -s 192.168.2.5:5555 shell "dumpsys media.audio_flinger | grep -iE 'session|Track' | head -30"
   "$A" -s 192.168.2.5:5555 shell "dumpsys audio | grep -iE 'mode|input|Recording' | head -20"
   ```
   看 `Input thread` 的 **Session**、`Mode`、以及是否只有**一条** Input thread 长期存活 —— 这直接验证 §2.1/§2.2 的结论。**在没有 USC 干扰时**它若长时间是唯一输入流，就是「开一次常驻」的实机铁证。
2. **喊「小智小智」** —— 它自带 KWS，能唤醒就说明第三条路（非厂商 App）在今天这台 R1 上也能拿到干净流。
3. **同屏对照**：先只跑它（记录 Input thread 形态），再 force-stop 它、只跑我们 app，记录形态；最后用 `/tmp/mix2.txt` 那套 tinymix 参数一起对照。

---

## 7. 复现步骤（我这边用过的命令）

```bash
# 全部在 Windows 侧（WSL 被安全策略拦截），使用 WorkBuddy 托管的 Python venv（已装 androguard 4.1.4）
PY="C:/Users/shaoh/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
cd C:/Users/shaoh/WorkBuddy/R1/reverse/unisound-v1.6.13

$PY classes_scan.py        # 类清单 / 音频相关类
$PY find_audio_calls.py    # AudioRecord/AudioTrack/loadLibrary 调用点
$PY dasm.py 'LC/c;' run '()V' 236 420   # 带精确地址的分支还原 ← 最关键
$PY fieldmap.py            # XiaoZhi 静态字段 ↔ 设置读写点
$PY search_ctx.py '<needle>' <before> <after>   # 任意调用点上下文
$PY cfg.py                 # <clinit> 默认值
```
产物：`dex_strings.json`（29433 条字符串全文）、上述 6 个脚本。

> **踩坑记录**：① androguard 4.x 指令名是 `invoke-virtual`/`invoke-static`，不是 `invoke`；② 遍历指令要用 `code.get_bc().get_instructions()`（`DalvikCode` 上没有 `get_basic_blocks`）；③ 脚本别命名 `dis.py`（阴影标准库，loguru 会炸）；④ Dalvik 分支偏移是**相对本指令地址**的 code unit 数，且 `const/16` 是 4 字节不是 3。

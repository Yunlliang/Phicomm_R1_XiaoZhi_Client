# 播音乐时唤醒率下降：AEC 方案

> 2026-10-07 · 针对 r1-client v1.3.55（普通安卓方案，`cn.workbuddy.r1client`）
> 证据来源：`pandora逆向报告/`（原厂 AEC）+ 设备实测 + v1.3.9/v1.3.11 既有实测注释

---

## 0. 结论先行

> **2026-10-07 18:00 实测更新（v1.3.56 装机后）：方案 A 判死。**

| 方案 | 改动量 | 依赖 | 实测结论 |
|---|---|---|---|
| **A. 共 session + 开系统 AEC** | ~3 处、10 行 | 0（HAL 现成） | ❌ **此机不成立**：`AcousticEchoCanceler not available on this device` |
| **A′. sess_bind 逼 HAL 进语音用例** | 0 行（flag） | 0 | ❌ 判负（§0.2/§0.3，两轮） |
| **B. 移植原厂 libaec.so** | 新增 so + 桩类 + 双路接线 | libaec.so（零依赖） | ⚠️ CPU 1.7% 可接受，但 **ref 恒 0、cut 恒 28%**（v1.3.59/60），v1.3.61 最后一搏 |
| **C. Ducking（播歌降音量）** | ~5 行 | 0 | 🟢 **v1.3.61 内置**（`files/duck_pct`），物理上唯一稳赢 |

### 0.1 方案 A 判死的实证（v1.3.56 已装、`files/aec_on` 已开）

```
10-07 18:01:31.777  AudioTrack session=0      ← TtsPlayer 建轨时 captureSession 还是 0
10-07 18:01:38.508  AudioRecord session=30
10-07 18:01:38.514  AcousticEchoCanceler not available on this device   ← 判死
10-07 18:01:38.514  AudioCapture started: ... aec=true ... sessBind=false
```

- `AcousticEchoCanceler.isAvailable()` 返回 **false**（`AudioCapture.java:402` 分支），
  与 `/system/etc/audio_effects.conf` 里 **AEC / NS / AGC 全被注释**一致。
- **共 session 补丁白做**：`ensureTts()` 在 `R1ClientService.java:179`（onCreate）就开线程建轨，
  那时 `AudioRecord` 还没起来（mic 要 ~7s 后随 KWS 就绪才开）⇒ `captureSession=0` ⇒
  AudioFlinger 实测：output track session=23/26，input session=24/27，**从来没共上**。
  - 修法（若将来要复用）：TtsPlayer 改**懒建轨**——首次真正写 PCM 时才 `new AudioTrack`，
    并在 `onTtsStart()` 检测 session 变化后重建（覆盖关麦再开麦）。

### 0.2 `files/sess_bind` 实测：**判据为负**（2026-10-07 18:12）

```
session-bind ON (session=33) keep-alive playback started     18:03:08
音乐窗口：《引力轨迹》 18:09:36.6 ──► 18:11:41.5（约 2 分钟，无人说话）
```

`micTelem` rms（每 5.75s 一行，30 个采样/行）：

| 时段 | rms | peak |
|---|---|---|
| 静默基线 18:03~18:09 | **150~190**（偶发单帧 200~440） | 700~1800 |
| **播放中** 18:09:30~18:11:36 | **250~1100**（大量 400~900） | 3800~4700 |
| 播完 18:11:42 起 | 立刻回落到 **160~180** | — |

⇒ **泄漏 +6~17 dB，判据 ❌：音乐原样进了 mic，HAL 一个 dB 都没减。**

⚠️ 但这次是**假阴性**——不能据此判 HAL 无 AEC：
v1.3.56 的 TtsPlayer 在 `buildPipeline` 第 3 步就建了 AudioTrack，那时 `captureSession` 还是 0
（mic 第 6 步才起）⇒ 音乐轨 session=0，HAL AEC 的参考只有 sess_bind 那条**静音**常驻流。
参考是静音，减掉 0，音乐当然照原样进 mic。**这恰恰验证了 §1.2 的表格第 3 行。**

## 0.0 🔴 最终结论（2026-10-07 19:55）：**全线判死，回退 v1.3.55**

> 用户实测 v1.3.61（AEC 坐标修复 + ducking 45%）**仍然唤不醒** ⇒ 决定 **v1.3.55 为最终版**。
> 机器已回退并清理干净：`pm path` = `.../r1client-1/base.apk` **12,758,405**，
> `audio cfg: src=VOICE_COMMUNICATION aec=false gain=16 sessBind=false`，
> 实验 flag（`aec_sw` / `aec_on` / `duck_pct` / `aec_delay_ms` / `sess_bind`）**已全部删除**。

| 版本 | 做了什么 | 结果 |
|---|---|---|
| v1.3.56 | 共 session + 系统 AEC | ❌ `AcousticEchoCanceler not available` |
| v1.3.57 | 懒建轨（真共 session=35） | ❌ 播放期 rms 394~970，HAL 不减 |
| v1.3.58 | 软件 AEC `process2()` | ❌ **崩溃**（零 xref 死接口，SIGSEGV） |
| v1.3.59 | 改 `process(mic,ref)` | ⚠️ 不崩，CPU **1.8%**，但 `ref=0`、`cut` 恒 30% |
| v1.3.60 | 墙钟统一坐标 | ⚠️ `ref=0`，`coord` 恒落后 `written` 778k 样本 |
| v1.3.61 | 坐标改 `written - delay` + ducking 45% | ❌ 用户实测仍唤不醒 ⇒ **收工** |

**四条定论（供以后参考，别再重踩）：**

1. **标准 `AudioRecord(VOICE_COMMUNICATION)` 通路，厂商 HAL 没有 AEC**，共 session 也无用
   （原厂的 AEC 只存在于四麦私有 HAL `libUni4micHalJNI` 里，而它没有任何参考信号入口）。
2. **`libaec.so` 是原厂从未启用的 SDK 备用件**（`IAudioSourceAEC` 全 dex 零调用者）。
   `process()` 能跑、CPU 只要 1.8%，但 **`cut` 恒定 28~30% 与 `ref` 无关** —— 在我们的
   调用序列下它退化成一个固定 -3dB 衰减器（连人声一起削，反而更糟）。**不要再投入。**
3. **软件 AEC 的硬骨头不是 CPU（1.8%，可忽略），是收敛**。滤波器尾长 600 样本 = 37.5ms，
   而音箱腔体的声学回声尾是 200~500ms，物理上覆盖不了。
4. **ducking 45% 也没救回来** ⇒ 说明瓶颈不只是"回声能量高"。更可能是
   **音乐本身的频谱把 KWS 的声学特征淹没**（唤醒词是宽带瞬态，音乐也是宽带）；
   纯能量降低不足以恢复可分离性。若将来再战，方向应是
   **原厂路线：四麦 HAL 波束成形（空间滤波）**——那才是靠"方向"而不是靠"音量"把人声从音乐里挑出来。

---

### 0.3 v1.3.57 懒建轨复测：**共 session 生效了，判据仍为负**（2026-10-07 18:28）

```
18:23:17.465 session-bind ON (session=35) keep-alive playback started
18:23:17.466 AudioCapture started: src=VOICE_COMMUNICATION aec=true sessBind=true
18:23:41.626 AudioTrack session=35 buf=16384 (shared with AudioRecord for AEC reference)  ← 共上了！
播放窗口：讲笑话 TTS 18:23:45.7 ──► 18:24:10.4（无人说话）
```

| 时段 | mic rms |
|---|---|
| 播放前 18:23:18~18:23:41 | 160~190 |
| **播放中** 18:23:47~18:24:10 | **394~970** |
| 播完 18:24:16 起 | 160~189 |

⇒ **这次是真阴性**：`AudioTrack session == 录音 session == 35`，共 session 这一环已经闭环，
HAL 依然一个 dB 没减。叠加 `AcousticEchoCanceler not available`，结论坐实：

> **标准 AudioRecord(VOICE_COMMUNICATION) 这条通路上，厂商 HAL 没有挂 AEC。**
> 播歌/播 TTS 的回声原样压在 mic 上（+8~16 dB），只能自己减 → **方案 B**。

### 0.4 v1.3.58：软件 AEC（方案 B，已就绪待构建）

补丁 `apply_v1.3.58_swaec.py`（WSL `/workspace/r1-client/`）：

- `com/unisound/jni/AEC.java` + `jniLibs/armeabi-v7a/libaec.so`（原厂 so，零依赖）
- `AecRef.java`：参考信号**按累计样本坐标寻址**的 6 秒环形缓冲（吸收 AudioTrack 500ms 播放缓冲）
- `SoftAec.java`：600 样本/块（照抄原厂 `setOptionInt(0,600)`），FIFO 切块 + 按 960 样本重组；
  时延对齐 `offset = micTotal - (audible - delay)`，`audible` 由 TtsPlayer 播放**时钟外推**
  （不调 `getPlaybackHeadPosition()`，绕开 v1.2.2 那类 binder 卡死）
- `TtsPlayer.writeOut()` 单一出口：真 PCM 与补的静音都进参考缓冲（漏了静音坐标就漂）
- AEC 插在**软件增益之前**（参考是未增益的播放 PCM，先 x16 会让 mic/ref 差 16 倍）
- 只在"正在出声"时启用，静默期直通（避免滤波器乱减背景噪声）

开关：默认关。`touch files/aec_sw` 开；`files/aec_delay_ms` 写整数（毫秒）覆盖默认 80。

**判据（一轮出结果）**：播歌时看新增的 `aecTelem` 行
`aecTelem raw=NNN out=NNN ref=NNN cut=NN% delay=80ms`
- `cut` > 50% 且 `out` 掉到底噪量级 → AEC 生效，再验唤醒率
- `cut` ≈ 0 → 时延没对齐，改 `files/aec_delay_ms` 试 40 / 120 / 160 各一轮扫一次

> ⚠️ 判版本别用 `dumpsys package`（build.sh 只改文件名、**不改 manifest**，versionName 恒 1.3.55）。
> 用 `/data/app/cn.workbuddy.r1client-1/base.apk` 的**字节数**：v1.3.56=12,758,592，v1.3.55=12,758,405。

---

## 1. 根因：不是 AEC 不能用，是参考信号没接上

### 1.1 现状拓扑（v1.3.55 默认）

```
TtsPlayer  ──▶ AudioTrack(session=X, 真实的 TTS/音乐 PCM) ──▶ HAL 输出
                                                              │
AudioCapture ◀─ AudioRecord(session=Y, VOICE_COMMUNICATION) ◀──┴─ 4mic HAL
     └─ keepAlive: AudioTrack(session=Y, 静音)  ← files/sess_bind 时才有
```

- `aecEnabled = false`（默认关，`files/aec_on` 才开）
- `sessionBind = false`（默认关，`files/sess_bind` 才开）；且 keepAlive 写的是**静音**
- TTS/音乐走 `TtsPlayer.java:88` 的 AudioTrack，**没有指定 sessionId** → 拿到新 session X

### 1.2 为什么现在播音乐难唤醒

AEC 的原理是「用播放出去的信号当参考，从 mic 里减掉它」。参考信号接谁，决定成败：

| 配置 | AEC 的参考信号 | 结果 |
|---|---|---|
| 现状（AEC 关） | — | mic = 音乐 + 人声，SNR 崩 → 难唤醒 |
| v1.3.9 实测（开 AEC，未共 session） | 空/不匹配 | **人声一起被消掉**：说话段 rms 23~41、peak 661→103 |
| keepAlive 共 session（静音流） | 静音 | 学不到音乐，白开 |
| **A 方案：音乐 track 共用 record 的 session** | **真实音乐 PCM** | 音乐被减掉，人声保留 |

AudioCapture.java 注释里已经把这层写死了：

> 参考 App 之所以没事：它的 AudioTrack 与 AudioRecord 共用同一 session，
> AEC 拿得到正确回声参考；我们只抄了 AEC、没抄共 session → AEC 把近端人声误判为回声消掉。

只是当时补的是「静音 keepAlive」（为了骗 HAL 进全双工用例拿 AGC），
**没有把真正的音乐流也拉进同一个 session**——所以音乐场景仍然没有有效参考。

---

## 2. 方案 A：共 session + 开 AEC（首选）

### 2.1 改动点

**① AudioCapture.java —— 暴露采集 sessionId**

在字段区加（紧挨 `sessionBind`）：

```java
    /**
     * v1.3.56：采集侧 audio session id（>0 表示 AudioRecord 已就绪）。
     * 供 TtsPlayer 构造 AudioTrack 时共用，使 HAL AEC 拿到真实参考信号。
     */
    public static volatile int captureSession = 0;
```

在 `record.startRecording()` 成功后赋值：

```java
        record.startRecording();
        captureSession = record.getAudioSessionId();   // ← 新增
        Log.i(TAG, "AudioRecord session=" + captureSession);
```

`stop()` / `release()` 里复位：`captureSession = 0;`

**② TtsPlayer.java —— AudioTrack 共用该 session**

原文（约 88 行）：

```java
        track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufBytes, AudioTrack.MODE_STREAM);
```

改为：

```java
        int sid = AudioCapture.captureSession;   // v1.3.56：共 session → AEC 参考信号
        track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufBytes, AudioTrack.MODE_STREAM,
                sid > 0 ? sid : 0);
```

> 7 参带 sessionId 的 `AudioTrack` 构造函数自 API 21 起为 public，本机 API22 可用。
> `sid=0` 时行为与原来一致（新建 session），保证 AudioCapture 未启动时不退化。

**③ 开 AEC 开关（设备端，无需改代码）**

```bash
adb -s 192.168.2.5:5555 shell "run-as cn.workbuddy.r1client touch files/aec_on"
adb -s 192.168.2.5:5555 shell "am force-stop cn.workbuddy.r1client"
adb -s 192.168.2.5:5555 shell "am startservice -n cn.workbuddy.r1client/.R1ClientService"
```

### 2.2 A/B 判据

| 指标 | 期望（修好） | 失败特征 |
|---|---|---|
| 播音乐时 mic rms（无人说话） | 从「音乐电平」掉到底噪级（<300） | 仍高 → AEC 没吃到参考 |
| 播音乐时喊唤醒词 | 命中，且 STT 是**人声**不是歌词 | 仍不中 / 中但文本是歌词 |
| 播音乐时说话段 rms | 明显高于 v1.3.9 的 23~41 | 若又变 23~41 → 参考信号仍错 |
| 空闲底噪 | 不变（185~237 量级） | 若掉到 13 → 又把人声当回声消了 |

### 2.3 风险与回退

- 共 session 后 HAL 可能对下行施加通话类处理（音量/窄带），TTS 音质可能变化 → 先听一耳朵。
- 回退：`rm files/aec_on`；共 session 本身可保留（无害，必要时加 flag 控制）。

---

## 3. 方案 B：移植原厂 libaec.so（备用）

### 3.1 可行性取证（已全部坐实）

| 项 | 结果 |
|---|---|
| ELF NEEDED | `liblog.so / libm.so / libstdc++.so / libc.so / libdl.so` —— **不依赖 libuscasr** |
| 导出 JNI | `Java_com_unisound_jni_AEC_{init,process,process2,getlast,reset,setOptionInt,release}` |
| 内部实现 | 静态链入 `ffts`（NEON 优化 FFT）→ 正经频域 AEC，非玩具 |
| 体积 | 50,368 B |
| 库名 | `System.loadLibrary("aec")` → 装为 `libaec.so` |
| **参考信号** | **音乐是我们自己的 AudioTrack 播的 ⇒ ref 现成**（写 track 前的 PCM） |

文件已就位：`pandora逆向报告/证据/libaec.so`

### 3.2 桩类（签名与原厂 dex 逐字对齐）

`src/com/unisound/jni/AEC.java`：

```java
package com.unisound.jni;

public class AEC {
    static {
        System.loadLibrary("aec");
    }

    private int a;

    public native int init(int sampleRate, int channel);
    public native byte[] process(byte[] mic, byte[] ref);
    public native int process2(byte[] mic, int micLen, byte[] ref, int refLen, byte[] out);
    public native byte[] getlast();
    public native void reset(float f1, float f2);
    public native int setOptionInt(int key, int value);
    public native void release();

    public AEC(int sampleRate, int channel) {
        init(sampleRate, channel);
    }
}
```

so 放 `jniLibs/armeabi-v7a/libaec.so`（主工程与 `:r1client` 进程都要能加载）。

### 3.3 初始化参数（抄原厂 IAudioSourceAEC）

```java
AEC aec = new AEC(16000, 1);        // init(16000, 1)
aec.setOptionInt(0, 600);           // 帧长/滤波相关
aec.setOptionInt(2, 1);
aec.setOptionInt(3, 1);             // setMicChannel：单声道
```

原厂管线：双线程（priority 10）+ BlockingQueue，帧 1200 B，poll 5 ms。

### 3.4 接线点

- **ref 写入**：`TtsPlayer` 每次 `track.write(pcm, ...)` 之前，把同一段 PCM 塞进 ref 环形缓冲。
- **mic 处理**：`AudioCapture` 采集循环里，读到的 mono PCM 先 `aec.process(mic, ref)` 再喂 KWS / 上行。

### 3.5 ⚠️ 唯一硬难点：时延对齐

软件 AEC 对 ref/mic 时延极敏感，而这里天然存在大延迟：

```
写 PCM → AudioTrack 缓冲（v1.2.3 起 ≥500ms）→ HAL 输出 → 扬声器 → 空气 → mic → HAL 采集 → 读到
```

总延迟可能 300~700 ms，**远超 AEC 滤波器覆盖范围**（`setOptionInt(0,600)` 量级）。
不对齐 = 减错位置 = 不仅不消回声，还会把音频搞烂。

对齐办法（必须做）：用 `AudioTrack.getPlaybackHeadPosition()` 与录音侧采样计数求差值，
据此从 ref 环形缓冲里取「当前时刻正在响的那一帧」：

```
delaySamples = trackWrittenSamples - track.getPlaybackHeadPosition()
refFrame = refRing.readAt(recordReadSamples - delaySamples)
```

并周期性用 playbackHead 校正漂移。

> 这正是方案 A 更优的原因：HAL AEC 的参考信号取自输出线程内部，**天然对齐，不用自己算**。

---

## 4. 方案 C：Ducking（兜底，立刻见效）

检测到自己在播音乐/TTS 时，把 STREAM_MUSIC 音量压到 40% 左右，唤醒后再恢复。
`SessionController.onVolume()`（1902 行）与 `setMediaMute()`（2060 行）已有成型代码可复用。

代价：听感变差。建议在 A/B 都验证不足时，作为「唤醒优先」的默认策略。

---

## 4.5 原厂 Pandora 到底怎么做的（逆向报告结论，见 `pandora逆向报告/README.md`）

| 层 | 原厂做法 | 证据 |
|---|---|---|
| 收音 | **不走 AudioRecord**，走 `Uni4micHalJNI.openAudioIn(2)` 四麦 HAL 私有通路（失败不回退） | §2.1 / `证据/dumps/dump_e.txt` |
| AEC | HAL 内部完成 4mic→AEC 流水线（`aec_enlarge_4mic`），App 只是普通客户端，**不需要喂参考** | §2.1 / `uni_4mic_config/` |
| 软件 AEC | `libaec.so` + `IAudioSourceAEC` 是 SDK 备用件，dex 内**无任何调用者**（xref 实证） | §2.2 / `xref*.txt` |
| TTS 期唤醒 | `aec_enable=true` → 唤醒**常开**（可 barge-in）；`false` → `doStopWakeup()` 退化为纯半双工 | §3.1 / `dump_engine.txt` |
| 播音乐 | `onMusicStatusChanged` → 唤醒阈值 **-9.1 → -11.3 / -12.3 / -13.3** 三档（越负越难），停播后 30 分钟恢复任务 | §4.3 / `config.mg` + `dump_music.txt` |

**两个必须记住的推论：**

1. **原厂的解药是 HAL AEC，不是软件 AEC** —— 它甚至没给 libaec.so 接线。我们现在要启用的，正是原厂**没启用**的那条备用路径，因为我们走的是标准 AudioRecord 通路，拿不到 HAL 那层。
2. **原厂播音乐时抬阈值 = 让唤醒更难**，方向与我们诉求相反。那是**防误触发**（音乐里含近似音）的代价，不是提升灵敏度的手段。原厂敢这么抬，是因为 HAL 已经把回声消掉了；我们没消之前照抄只会更难唤醒。
   ⇒ 所以我们不能靠调阈值解决"播歌喊不醒"，**必须先补 AEC**；AEC 成立后再抄阈值分档压误唤醒。

**为什么不复刻原厂的四麦 HAL 通路（B 通路）**：`Uni4micHalJNI` 只暴露 10 个 native 函数、**无任何 AEC/参考入口**（`dump_hal.txt`），
2026-10-05 回环隔离实验实测 B 通路立刻自环；且 r1client 是 untrusted_app，直开 ALSA 已被拒（v1.3.23）。⇒ 唯一可行解就是软件 AEC。

---

## 5. 建议执行顺序

1. **量化现状**：播小智音乐，抓 logcat 里 AudioCapture 的 rms（安静 vs 播歌两组），确认泄漏量级。
2. **改方案 A**（10 行），`touch files/aec_on` 重启，播音乐喊唤醒词 5~10 次，按 §2.2 判据判成败。
3. A 成功 → 收工（并可顺手删掉静音 keepAlive，音乐流本身就是最好的 keepAlive）。
4. A 失败（人声又被吃掉，或消不掉音乐）→ 上方案 B，重点攻时延对齐。
5. 都不理想 → 方案 C 兜底。

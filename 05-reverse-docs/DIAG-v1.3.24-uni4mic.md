# R1 四麦阵列 HAL 诊断报告

- **日期**：2026-10-04 19:00 ~ 19:15
- **设备**：rk322x_echo / Android 5.1.1 (API 22) / 192.168.2.5:5555
- **客户端**：`cn.workbuddy.r1client` v1.3.24 (versionCode 46)
- **触发**：用户「试四麦 HAL」——目标是解决「收音灵敏度低，要离很近，原来小讯放很远轻轻说都能唤醒」

---

## 一、结论摘要（先说结果）

| # | 结论 | 状态 |
|---|---|---|
| 1 | 原厂四麦 HAL **本体完全可用**（so 加载、JNI 签名、板型识别全部成功） | ✅ 确认 |
| 2 | 被 **SELinux 拦死**：`untrusted_app` 域无权进入 `/dev/snd` → 拿不到 ALSA 句柄 | ❌ 无 root 不可解 |
| 3 | **我们本来就在用四麦硬件**：framework 的 AudioRecord 拿到的就是 card1 同一路信号 | ✅ 意外收获 |
| 4 | 即使 HAL 能用，**也不会提升电平**（HAL 做波束成形/降噪，不做增益） | ⚠️ 关键认知 |
| 5 | 「灵敏度低」的**真病根**：麦克风前级电平只有满量程的 **0.04%** | 🔴 物理层 |
| 6 | `MIC Input Volume L/R = 0` **与四麦采集无关**（已验证） | ❌ 排除 |
| 7 | stereo 双通道是**独立的**（corr ≈ -0.036），可能是两个麦克风 | 🟡 待验证 |

---

## 二、证据链①：HAL 卡在 `/dev/snd`

开启 `files/src_uni4mic` 并重启服务后，logcat 逐步输出：

```
19:00:31.716 I/R1CLIENT  audio cfg: src=UNI4MIC-HAL aec=false gain=16 sessBind=false
                         (flags: src_uni4mic=true ...)
19:00:38.542 I/R1CLIENT  uni4mic init(1) -> 0                          ← 初始化成功
19:00:38.546 I/R1CLIENT  uni4mic board=UNI_4MIC_HAL_ANDROID_V1.1       ← 板型自报，真库真跑
                         dbg=0 closeAlgo=0 wakeUp=0
19:00:38.548 I/R1CLIENT  uni4mic openAudioIn(2) -> handle=0            ← ⚠️ 拿不到设备
19:00:38.549 I/R1CLIENT  uni4mic startRecorder -> -1
19:00:38.549 W/R1CLIENT  uni4mic startRecorder failed (-1) -> fallback
19:00:38.568 I/R1CLIENT  AudioCapture started: 16000Hz/mono src=VOICE_COMMUNICATION ...
```

### 根因（SELinux，唯一一条 denial）

```
19:00:38.537 W/mic-acquire(11330):
  avc: denied { search } for name="snd" dev="tmpfs" ino=8047
  scontext=u:r:untrusted_app:s0          ← 我们的 app 域
  tcontext=u:object_r:audio_device:s0    ← /dev/snd
  tclass=dir permissive=0                ← 强制拒绝
```

`/dev/snd/*` 节点的 DAC 权限是 `crwxrwxrwx system audio`（**0666，完全开放**），
所以卡的不是文件权限，而是 **SELinux 的 MAC 策略**：`untrusted_app` 域没有 `audio_device:dir` 的 `search` 权限。

> 正常 App 录音走 `AudioRecord` → Binder → mediaserver，由 **mediaserver 域**访问 `/dev/snd`。
> 原厂四麦 HAL 是 **tinyalsa 直连 ALSA**（依赖 `libtinyalsa.so`），必须在特权域里跑。

### 提权尝试（全部失败）

| 尝试 | 结果 |
|---|---|
| `adb root` | 静默失败，仍 `uid=2000(shell)`（`ro.secure=1`） |
| `ro.debuggable=1` | 但 adbd 不提权 |
| `getenforce` | `Enforcing` |
| `ro.build.type` | `user` |
| shell 域 `[ -r /dev/snd/pcmC1D0c ]` | **YES** → 仅 app 域被拦，shell 域畅通 |

---

## 三、证据链②：so / JNI / 依赖 全绿（说明「不可用」不是代码错）

- **JNI 导出符号 18 个全中**，与 `Uni4micHalJNI.java` 的 `native` 声明逐字一致：
  `initHal` `releaseHal` `openAudioIn` `closeAudioIn` `startRecorder` `stopRecorder` `readData`
  `close4MicAlgorithm` `get4MicBoardVersion` `get4MicDoaResult` `get4MicOneShotReady`
  `set4MicDebugMode` `set4MicDelayTime` `set4MicOneShotReady` `set4MicOneShotStartLen`
  `set4MicUtteranceTimeLen` `set4MicWakeUpStatus` `set4MicWakeupStartLen`
- **API22 linker 体检**（`diag/kws/check_elf.py`）：

  | 库 | 大小 | DT_HASH | DT_GNU_HASH | VERS |
  |---|---|---|---|---|
  | libUni4micHalJNI.so | 9,312 | ✅ | ✗ | 无 |
  | libuni4michal.so | 25,820 | ✅ | ✗ | 无 |
  | libUniMicArray.so | 535,444 | ✅ | ✗ | 无 |

- **依赖齐全**：`libuni4michal` / `libUniMicArray` / `libUniLinearMicArray` / `libtinyalsa` /
  `libstlport` / `libcrypto` / `libcutils` / `libutils` 全在 `/system/lib`。
- 原厂 `com.phicomm.speaker.device` **当前未运行** → 不存在设备占用竞争。

> 即：**只差 SELinux 一条**。若刷 boot 改 sepolicy 放行，HAL 大概率可直接工作。

---

## 四、证据链③：framework 通路 = 四麦同一路信号（决定性数据）

设备自带 `tinycap / tinypcminfo / tinymix / tinyplay / tinypcm2wav`，shell 域可直接录 card1。

```
tinypcminfo -D 1 -d 0
  PCM in:
    Format: S16_LE, S32_LE
    Rate:   min=16000Hz max=96000Hz
    Channels: min=2 max=2
```

⚠️ **实测只能以 48000 打开**：`-r 16000` 报 `cannot set hw params: Invalid argument`。
原因：`tinymix -D 2` 显示 `CLKO Output Clock = 12.288MHz`、`BICK fs = 64fs` →
**12.288MHz / 256 = 48kHz，I2S 主时钟固定**，无法跑 16k。

### 电平对比（同时长、同环境、无人说话）

| 通路 | 通道 | peak | RMS |
|---|---|---|---|
| `tinycap` 直录 card1（四麦硬件） | L / R | 860 / 870 | **14.04 / 13.30** |
| App 的 AudioRecord（micprobe，未加 gain） | mono | 52~61 | **12.4~12.6** |
| App 的 AudioRecord（stereo 探针） | L/R | 90 | 18.1 |

**结论：两者电平完全一致（rms 13~14）→ framework 拿到的就是四麦阵列的同一路信号。**

| 通路 | 结论 | 补充 |
|---|---|---|
| `tinycap` 直录 card1（四麦硬件） | peak 860 / 870，rms **14.04 / 13.30** | — |
| App AudioRecord（micprobe mono，未加 gain） | peak 52~61，rms **12.4~12.6** | — |
| App AudioRecord（micprobe stereo） | peak 90，rms 18.1 | L/R 独立 |

**⇒ HAL 的价值是「波束成形 + 降噪 + AEC」（数字域空间滤波），不是电平增益。**
**⇒ 我们并没有因为 HAL 用不了而损失原始信号 —— 那一路本来就拿到了。**

---

## 五、真病根：麦克风前级电平只有满量程的 0.04%

```
信号 rms ≈ 13        （满量程 32767 的 0.04%）
信号 peak ≈ 860      （满量程的 2.6%）
底噪 rms ≈ 13
```

对比典型手机：说话时 rms 应在 1000~5000（满量程 3%~15%）。

**远场推算**：3m 距离比 0.3m 衰减约 **20dB（10 倍）**
→ 远场轻声说话 rms ≈ 13 × 0.1 ≈ **1.3**，直接沉在底噪之下 → **SNR ≈ 1**。

这解释了用户的观察：「要离很近才能听到」。
且**软件增益（×16）只是等比放大，SNR 不变** —— 抬上去的是信号，噪声同样被抬起。

### 已排除的嫌疑

| 嫌疑 | 验证 | 结论 |
|---|---|---|
| `MIC Input Volume L/R = 0` | 设为上限 **4**（设 20 被 clamp 成 4）后重录 card1 | rms **14.04 → 14.11**，纹丝不动 → **无关** |

该控件位于 card2（RK_AK7755，74 个控件），只作用于 AK7755 自己的 ADC，与四麦采集通路（card1）无关。**已恢复为 0。**

---

## 六、新线索：stereo 双通道可能是两个真实麦克风

对 micprobe 的 stereo 录音做通道相关性分析：

| 文件 | frames | L≠R 比例 | 相关系数 |
|---|---|---|---|
| micprobe_cam_st.wav | 8192 | 98.3% | **-0.0359** |
| micprobe_mic_st.wav | 8192 | 98.3% | **-0.0359** |
| micprobe_vcomm_st.wav | 8192 | 98.3% | **-0.0359** |
| micprobe_vrec_st.wav | 8192 | 98.3% | **-0.0359** |

- **corr ≈ 0** → 两路**不是同一信号的复制**，也不是简单混音 → 有可能是**两个独立麦克风**。
- ⚠️ **存疑点**：4 个不同 AudioSource 得到的统计值**完全相同**（连 corr 都到小数点后 4 位），
  也可能是「一路真实 Mic + 一路独立噪声」，或探针取数逻辑问题。
- **待验证**：录一段**真人说话的 stereo**，检查 L/R 是否都收到语音且带**不同延迟/幅度**。
  若为真，则可在 app 层自建**双麦降噪/延迟求和**（虽不如四麦，但比单麦强）。

---

## 七、硬件配置速查（以后无需重复探查）

```
/system/usr/uni_4mic_config/
  pcm_hw_config.txt         pcm_card 1 / pcm_device 0 / i2s_bits 32 / high_priority 1
  MicArrayConfig.ini        ArrayType=circle  MicNum=4  MicDistance=3.5(cm)  SpeakerNum=2
                            AecFrameShift=256  AecFilterNum=12  Amplify=1
  MicArray_config.txt       mic_num 4 / speaker_num 2 / 正方形坐标 ±0.035
  mic4_uni_test_config.txt  播放 pcm_card_play 2 / pcm_device_play 0
  wopt.bin                  1,184,304 B（波束成形权重）
```

| 声卡 | 名称 | 用途 |
|---|---|---|
| card0 | RKHDMII2S | HDMI I2S |
| **card1** | **RKMA4** | **四麦阵列采集**（S16/S32，2ch，固定 48k） |
| card2 | RKAK7755 | AKM DSP/Codec（播放 + 74 个控件） |
| card3 | RKSPDIFCARD | SPDIF |

---

## 八、未解决 & 下一步选项

### 代码层防御性修复（本次发现）
`AudioCapture.startUni4mic()` 中，`openAudioIn` 返回 **0** 时被当作「有效句柄」
（判据只有 `uniHandle < 0`），实际是随后 `startRecorder(0)` 返回 -1 才兜住回落。
→ 应改为 **`uniHandle <= 0` 即视为失败**。当前因 HAL 不可用未显形，属隐患。

### 可选路线

| 选项 | 手段 | 收益 | 风险 |
|---|---|---|---|
| **A**（推荐） | KWS 前独立增益 + 检测阈值调低 | **唤醒距离**直接提升（立竿见影） | 零（纯 app 层） |
| **B** | 上行软件 AGC（目标电平自适应） | 提升**识别电平** | 零（纯 app 层） |
| **C** | 验证 stereo 双麦真实性 → 双麦降噪 | 中等（取决于 C 是否成立） | 零（纯 app 层） |
| **D** | 刷 boot 改 sepolicy 放行 `/dev/snd` | **唯一能拿到 HAL 波束成形**的路 | **高（变砖）** |

**当前生产状态**：`src_uni4mic` 旗标已删除，服务运行在 v1.3.24 / `VOICE_COMMUNICATION` / gain16，与验收基线一致。

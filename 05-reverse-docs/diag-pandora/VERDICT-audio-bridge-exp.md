# 结案（§六 对照实验）：AudioRecord 到底有没有吃到四麦增强？

> **结论先行**：
> ① **吃到了通路，但没吃到增益** —— 我们的录音**确实穿过 HAL 四麦链**（停我们就停），
> 但 HAL 交给 AudioFlinger 的仍是 **−68 dBFS 前级噪声底 / ~10 dB SNR** 的信号；
> ② **换 AudioSource 不是杠杆**（7 种全测，电平分毫不差）；
> ③ **瓶颈在前级 SNR**，软件无解 → **D（Janus 顶替）仍是唯一能复刻原厂的路径**。

实验日期：2026-10-04 22:38–22:52　设备：R1 @ 192.168.2.5:5555（Android 5.1.1 / API22 / 未 root）
被测版本：**v1.3.27 (versionCode 49)**，`mode=3` / `src=VOICE_COMMUNICATION` / `aec=false` / `gain=16`

---

## 一、实验 1：刷屏到底是谁造成的？（"停我即停"判据）

| 观测 | 停服务前 | 停服务后（12 s 后） |
|---|---|---|
| `card1 / pcm0c` (`ma4 codec`) | `RUNNING`，owner = mediaserver TID 22165 | **`closed`** |
| `UNI_4MIC` logcat | **~10 000 行/秒**（见 §二） | **完全空白** |

⇒ **四麦采集口 + 四麦算法的运行，100 % 由我们自己的录音触发。**
⇒ 我们没有"绕过四麦"，**我们的 AudioRecord 就是走 HAL 四麦链的**（D′ 的第一判据成立）。

---

## 二、刷屏的真实速率（顺带纠正一个直觉）

`logcat -d -t 3000 -s UNI_4MIC` 拉回本机分析：**3000 行只覆盖 0.3 秒**
（`22:41:38.450 → 22:41:38.749`），即 **≈10 000 行/秒**，且抓取瞬间时间戳几乎实时。

⇒ 该刷屏是**持续高频**的（不是偶发），`logcat` buffer 被秒级填满 —— 这也是
`logcat -d` 全量 dump 会卡死、必须用 `-t N` 限量的原因（**取证坑，记下**）。

消息构成（旧 16 MB dump 统计）：**322 924 行**中
- 234 495 行 = `<DEBUG> XX,00`（逐字节 PCM hex dump）
-  62 461 行 = `<DEBUG> XX,ff`
-  24 665 行 = `<DEBUG>` 空行
-     568 行 = `[unpack_es_v2:689]`（算法内部 unpack 打点）

---

## 三、实验 2：AudioSource 是不是杠杆？（micprobe 矩阵，7 种）

`diag/micprobe/*.wav`（v1.3.7 探针，2026-10-04 14:2x 采集）实测：

| 配置 | 时长 | rms | dBFS | peak |
|---|---|---|---|---|
| `def_mono`（DEFAULT） | **4.00 s** | 12.4 | **−68.4** | 57 |
| `mic_mono`（MIC） | **4.00 s** | 12.6 | **−68.3** | 61 |
| `vrec_mono`（VOICE_RECOGNITION） | 0.51 s | 12.6 | −68.3 | 52 |
| `mic_st` / `vrec_st` / `vcomm_st` / `cam_st` | 0.51 s | 18.1 | −65.2 | 90 |

**两个独立结论**：
1. **电平完全一致**（−65 ~ −68 dBFS，peak ≤ 90）
   ⇒ **AudioSource 对电平没有任何影响** —— 换 source 是死路（本轮彻底关掉这个变量）。
2. **只有 mono 配置能录满 4 s；所有 stereo 配置都在 ~0.51 s 停滞**（read 不再返回）
   ⇒ **R1 的 HAL 只向上层稳定供 mono** —— 这与 `AudioCapture.CHANNELS=1` 的现状一致，
   也解释了历史上"stereo 开流差异"的测量噪声。

---

## 四、实验 3：HAL 交给 AudioFlinger 的到底是什么形态？

`dumpsys media.audio_flinger`（服务运行中）：

```
Input thread 0x...:
  Sample rate: 16000
  Channel Count: 2            ← HAL 侧是 2 通道
  Channel Mask: 0x0000000c (left,right)
  Format: pcm16,  Frame size: 4
  HAL frame count: 1024 / HAL buffer size: 4096
  1 Tracks of which 1 are active
     yes  24284(我们)  1  00000010(MONO)  session 334   fCount 8192  16000
  0 Effect Chains             ← ★ 没有任何 AEC/NS/AGC/AEC effect
```

⇒ **HAL 输出 16k / 2ch / pcm16，无任何 Android effect**；
⇒ **我们请求 mono，AudioFlinger 把 2ch 下混成 mono 再给我们**。
⇒ 也就是说：**从 HAL 出来之后到我们手里，没有任何"增强"环节**（也没被削弱）。
⇒ 唯一可能的增强只发生在 **HAL 内部**（`uni_4mic_*`）。

---

## 五、实验 4：前级噪声是什么性质？（频谱定性，决定性）

对最安静的 1 s 段做 FFT（`numpy`，Hann 窗，分段平均）：

| 频段 | micprobe_def_mono（安静） | r1-uplink（会话中） |
|---|---|---|
| 0–80 Hz | 16.0 % | 15.9 % |
| 80–300 Hz | 16.3 % | 14.9 % |
| 300–1 000 Hz | 18.7 % | 20.3 % |
| 1 000–3 000 Hz | 23.4 % | 25.4 % |
| 3 000–6 000 Hz | 17.9 % | 16.5 % |
| 6 000–8 000 Hz | 7.8 % | 7.0 % |
| **50 Hz 工频** | **1.25 %** | 2.76 % |
| rms | **12.3（−68.5 dBFS）** | 205.8（−44.0 dBFS，×16 后） |

**判读**：
- **频谱平坦、无工频、无低频隆起** ⇒ **不是环境噪声**（环境噪声必有低频/工频特征）
- **−68.5 dBFS 比 16 bit 量化底（−98 dBFS）高 30 dB** ⇒ **不是量化噪声**
- ⇒ 只能是 **模拟前端固有噪声**（麦阵 + 前置放大 + ADC 的热噪声/本底）

**⇒ 这就是"R1 前级 SNR 墙"的物理量**：噪声底 **−68.5 dBFS**。

---

## 六、实验 5：会话中的真实电平与 SNR

`r1-uplink.wav`（v1.3.27 实际上行，48 s，已含 ×16）：

| 量 | ×16 后 | 折回原始 |
|---|---|---|
| 全程 rms | 628.3（−34.3 dBFS） | **39.3（−58.4 dBFS）** |
| 最静 1 s rms | 205.8（−44.0 dBFS） | **12.9（−68.0 dBFS）** |
| 全程 peak | 8 992 | 562 |

⇒ **说话 −58.4 dBFS vs 底噪 −68.5 dBFS ⇒ 前级 SNR ≈ 10 dB**
（与早前"0.5 m +10.3 dB / 1 m +4.8 dB / 2 m +2.1 dB"完全自洽）

⇒ **要 ASR 稳，需要 SNR ≳ 15 dB**；房间混响 + 距离衰减每米掉 6–20 dB
⇒ **1 m 已经是极限，2 m 必挂** —— 这不是通路问题，是**物理 SNR 问题**。

---

## 七、实验 6：四麦 HAL 直连（`src_uni4mic`）第二次复现

`touch files/src_uni4mic` → 重启服务，实时 logcat（`-s R1CLIENT`）：

```
audio cfg: src=UNI4MIC-HAL aec=false gain=16 sessBind=false (flags: src_uni4mic=true ...)
uni4mic init(1) -> 0                                   ← 初始化成功
uni4mic board=UNI_4MIC_HAL_ANDROID_V1.1 dbg=0 closeAlgo=0 wakeUp=0
uni4mic openAudioIn(2) -> handle=0                     ← ⚠️ 无效句柄
uni4mic startRecorder -> -1                            ← 失败
uni4mic startRecorder failed (-1) -> fallback
AudioCapture started: 16000Hz/mono src=VOICE_COMMUNICATION ...   ← 自动回落 ✓
```

⇒ 与 `DIAG-v1.3.24-uni4mic.md` 完全一致（第二次独立复现）：
**so 能加载、init 能成功、板型能自报，但 `openAudioIn` 拿不到设备。**
⇒ `libuni4michal.so` 走 tinyalsa 直开 `/dev/snd`，`untrusted_app` 域被 SELinux 挡住。

> 附注：`openAudioIn` 返回 **0** 而非负值，代码 `if (uniHandle < 0)` 会误判为"成功"，
> 真正拦住的是随后的 `startRecorder → -1`（**这份"成功假象"是 DIAG-v1.3.24 已记录的坑**）。

**清理完毕**：`files/src_uni4mic` 已删除，服务已重启回 `VOICE_COMMUNICATION`，card1 `RUNNING`。

---

## 八、实验 7：card1 直读（tinycap）路线 —— 证伪

| 条件 | 结果 |
|---|---|
| 服务运行时 tinycap card1 | **0 字节**（device busy） |
| 停服务后 tinycap card1 | **3 735 596 B / 5 s** ✅（`-D 1 -d 0 -c 2 -r 96000 -b 32`） |
| tinycap card2 (AK7755) | **0 字节**（打不开） |

数据解析（裸 PCM，因被 kill 无 RIFF header）：
- 每 8 B 帧 = `[有效16][噪声16][有效16][噪声16]` ⇒ **实际只有 2 路有效音频**
- 但 **ch0 的 rms 每 0.5 s 完全相同**（12401 / peak 20480）⇒ **周期性静态模式，不是真实音频**
- 电平 −8.4 dBFS / −2.9 dBFS（接近满幅）—— 典型的**未驱动 I2S / 测试模式**特征

⇒ **tinycap 绕过了 HAL 的初始化**（card1 无任何 mixer 控件，`tinymix -D 1` 全空），
拿到的不是真实声场 ⇒ **"直读 card1"这条廉价路线证伪**，不必再试。

---

## 九、实验 8：发现 HAL 的音频 dump 通道（未激活）

`/sdcard/unidata/`：

```
waking_file_4mic.wav   0 B   (22:48 / 22:49 ← 每次我们开流被创建/截断)
waking_file_2aec.wav   0 B   (22:48 / 22:49)
waking_file_out.wav    0 B   (22:48 / 22:49)
waked_file_{4mic,2aec,out}.wav   44 B (2026-10-04 14:32，只有空 header)
```

⇒ HAL 内部**存在三个观察点**：`4mic`（阵列原始）/ `2aec`（AEC 中间）/ `out`（最终输出）。
⇒ 每次我们的流被打开，这三个文件被**创建/截断**，但**始终没有数据写入**。
⇒ 考虑 `set4MicDebugMode(1)` 的返回是 `dbg=0`，推测**该 debug 通道未真正开启**（或需特定触发）。

**这条通道若能激活，就是"量化 D 路线收益"的最廉价手段**（见 §十一）。

---

## 十、判读汇总

| 假设 | 判定 | 依据 |
|---|---|---|
| D′「普通 AudioRecord 已含四麦增强，故无需顶替」 | **部分成立**（通路确实过 HAL）**但不解决问题** | §一/§四：过 HAL，但出 HAL 时仍是 −68 dBFS 噪声底 |
| 「换 AudioSource 能救」 | **证伪** | §三：7 种 source 电平分毫不差 |
| 「加软件增益能救」 | **证伪** | §六：gain16 已用，SNR 不变（放大器等比放大） |
| 「sessBind / AEC 能救」 | **证伪**（前轮已测） | 上行 rms 纹丝不动 |
| 「tinycap 直读 card1」 | **证伪** | §八：绕过 HAL 只有静态模式 |
| 「HAL 侧已做四麦波束」 | **未知**（关键未决） | §四 无 effect chain；HAL 内部是否波束，无上层观测点 |
| 「D = 顶替进 platform_app 能吃波束输出」 | **技术上唯一可行，收益未量化** | §七 直连失败 = 必须换域；§九 缺对照数据 |

**一句话**：**问题不在"走哪条路"，而在"前级 SNR 只有 10 dB"**。
所有软件通路手段（source / gain / session / 直读）都已穷尽且无一有效。

---

## 十一、下一步（按性价比排序）

| # | 动作 | 成本 | 收益 |
|---|---|---|---|
| 1 | **激活 `/sdcard/unidata/waking_file_out.wav`**（HAL 输出）与 `waking_file_4mic.wav`（阵列原始），做 **out vs 我们 AudioRecord** 的直接对比 | 低（找触发条件） | **量化 D 路线收益**——若 out 的 SNR 明显更高，D 就值得做；若相同，D 也白做 |
| 2 | 若 §1 无法激活 → 直接做 **D（Janus 顶替）**，用 `Uni4micHalJNI.readData` 录一段与 `r1-uplink.wav` 同条件的音频对比 | 高（需顶替 + 回退预案） | 拿到最终答案 |
| 3 | 接受 **A（0.5 m 定版，v1.3.27）** | 0 | 保底可用 |
| 4 | B（USB 麦）/ C（手机·ESP32 前端） | — | 绕开 R1 前级 |

**明确放弃**：换 AudioSource / 加软件增益 / sessBind / AEC / tinycap 直读 / card2 采集。

---

## 十二、证据文件（`diag/pandora/`）

| 文件 | 说明 |
|---|---|
| `_r1log.txt` / `_r1log2.txt` / `_r1log3.txt` | 三个时间点的 r1client 内部日志（含 `audio cfg` / `micTelem`） |
| `_exp1_uni.txt` + `analyze_uni.py` | UNI_4MIC 3000 行样本与时间分布分析（0.3 s/3000 行） |
| `_card1_raw.wav` / `_ch0_96k.wav` / `_ch1_96k.wav` / `analyze_card1.py` | card1 直读原始数据与解析 |
| `_af.txt` | `dumpsys media.audio_flinger` 快照（Input thread / 0 Effect Chains） |
| `_lg4.txt` | 实时抓到的四麦尝试日志（`init(1)→0` / `openAudioIn(2)→0` / `startRecorder→-1`） |
| `analyze_wavs.py` | 批量 WAV 电平分析（rms/dBFS/peak） |
| `../micprobe/*.wav` | 7 种 AudioSource 对照样本（mono 4 s / stereo 0.5 s） |
| `../micprobe2/r1-uplink.wav` | v1.3.27 实际上行 48 s（SNR 基准） |

### 关键命令备忘
```bash
# 停/起服务（可逆）
adb -s 192.168.2.5:5555 shell "am stopservice cn.workbuddy.r1client/.R1ClientService"
adb -s 192.168.2.5:5555 shell "am startservice cn.workbuddy.r1client/.R1ClientService"
# card1 直读（必须在服务停止、card1=closed 时）
adb -s 192.168.2.5:5555 shell 'tinycap /data/local/tmp/o.wav -D 1 -d 0 -c 2 -r 96000 -b 32 & TP=$!; sleep 5; kill $TP'
# 四麦尝试日志必须实时抓（buffer 被 hex dump 秒级冲掉）
adb -s 192.168.2.5:5555 logcat -v time -s R1CLIENT
```

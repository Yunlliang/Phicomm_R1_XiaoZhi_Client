# R1 四麦 HAL 内部 dump —— 激活方法结案（已实测跑通）

> 日期：2026-10-04 23:00
> 设备：192.168.2.5:5555（rk3229 / Android 5.1.1 / API22）
> 前置：`VERDICT-audio-bridge-exp.md`（§六 对照实验，结论「吃过 HAL 但没吃到增益」）
> 结论：**激活成功。拿到 4ch 四麦原始 + 2ch AEC + 2ch HAL 输出，共 1.58 MB 真实数据。**

---

## 零、App 能不能「不顶替」直接用？—— **不能（已实测钉死）**

激活方法只在 **shell 域**有效；**App（untrusted_app）同一个二进制跑出来是 0 字节**。

### 决定性对照（2026-10-04 23:06 实测，同一个 `/system/bin/uni_4mic_test`）

| 执行域 | 命令 | 结果 |
|---|---|---|
| **shell**（adb） | `uni_4mic_test -cap /data/local/tmp/cap.pcm -c 2 -t 5` | ✅ **320,000 B** 真实 PCM |
| **App**（`run-as cn.workbuddy.r1client`，`u:r:untrusted_app:s0`） | `uni_4mic_test -cap /sdcard/unidata/app.pcm -c 2 -t 2` | ❌ **0 B**（`pcm_open` 静默失败） |

> `run-as cn.workbuddy.r1client id` → `uid=10012(u0_a12) ... context=u:r:untrusted_app:s0`（这就是我们 App 的真实运行域）

### 为什么 exec 不出域

SELinux 域**由父进程决定，不随可执行文件走**。App `Runtime.exec()` 一个 `/system/bin/*` 二进制，
子进程**仍是 `untrusted_app`**（没有 `untrusted_app → shell` 的 `domain_transition` 规则），
所以照样被 `untrusted_app → /dev/snd ... denied{search}` 挡死。**换谁去点这个工具都没用。**

### 其他「不顶替」的退路也一并封死

| 思路 | 实测结果 |
|---|---|
| App 直接 `tinyalsa`/`Uni4micHalJNI.openAudioIn` 开 card1 | ❌ `openAudioIn(2)→handle=0`（两次复现） |
| App exec `/system/bin/uni_4mic_test` | ❌ 0 字节（上表） |
| 让 App 改配置，锁存 **mediaserver 的 HAL** 去 dump，App 再从 `/sdcard` 读 | ❌ ①`/data/unidata` 域 = `system_data_file`（777 是假象），App `touch` 返回 **255**；②部署的 `pcm_hw_config.txt` **没有 `debug_record_files` 键**；③debug level 是**函数调用**设定的，不是配置项 → 无法锁存 |
| 长期驻留一个 shell 域 helper 给 App 供音频 | ❌ adb shell 子进程随宿主断开而死，且无 root 不能改 init 拉起 → 不是产品方案 |

⇒ **App 要在运行时用到四麦（那 4 路更强的拾取 + 波束成形），唯一的路仍然是：跨过 SELinux 域门 —— 也就是顶替（Janus）、或 root、或 USB 麦、或把前端挪出设备。**

### 那这次激活到底还有什么用

**它是「量尺」，不是「通路」**：从 shell 域能拿到四麦真实数据后，
才第一次可以做 **A（我们 App 上行）vs B（四麦裸采集）的同条件语音对照**，
从而**判定顶替值不值得投**。所以正确顺序是：**先量（这次能做了）→ 再决定要不要顶替**。

---

## 一、一句话答案

**别自己造轮子 —— 设备上自带官方 CLI 工具 `/system/bin/uni_4mic_test`。**

```bash
# 1) 释放 card1（我们 App 占着 ma4 采集口，HAL PCM 打不开）
adb shell am stopservice cn.workbuddy.r1client/.R1ClientService

# 2) 初始化（把 debug level 顶到 5，这一步就会写出 WAV header）
adb shell uni_4mic_test -uid

# 3) 采集 5 秒：落一份原始 pcm，同时 HAL 自动 dump 三个 wav
adb shell uni_4mic_test -cap /data/local/tmp/cap.pcm -c 2 -t 5

# 4) 取回
adb pull /sdcard/unidata/waking_file_4mic.wav     # ★ 4ch 四麦原始
adb pull /sdcard/unidata/waking_file_out.wav      # 2ch HAL 输出
adb pull /sdcard/unidata/waking_file_2aec.wav     # 2ch AEC 中间量
adb pull /data/local/tmp/cap.pcm

# 5) 恢复
adb shell am startservice cn.workbuddy.r1client/.R1ClientService
adb shell rm /data/local/tmp/cap.pcm
```

实测输出（5 秒 / 16kHz）：

| 文件 | 大小 | 格式 | 说明 |
|---|---|---|---|
| `cap.pcm` | 320,000 B | 16k / 2ch / i16 | `-cap` 指定的裸 PCM |
| `waking_file_4mic.wav` | 641,068 B | **16k / 4ch / i16** | 四麦原始（4 路独立） |
| `waking_file_out.wav` | 320,556 B | 16k / 2ch / i16 | HAL 输出 |
| `waking_file_2aec.wav` | 320,556 B | 16k / 2ch / i16 | AEC 中间量 |

---

## 二、为什么之前是 0 字节（根因）

之前每次我们 App 打开录音流，`/sdcard/unidata/waking_file_*.wav` 都会被**创建但恒为 0 字节**。
现在根因明确 —— **两个条件缺一不可**：

| 条件 | 我们 App（untrusted_app） | `uni_4mic_test`（shell 域） |
|---|---|---|
| `uni_4mic_pcm_open` 打开 card1 | ❌ `openAudioIn(2)→handle=0`（SELinux 挡 `/dev/snd`） | ✅ 成功 |
| debug level | `set4MicDebugMode(1)` → **level 4** | `uni_4mic_set_debug_level(5)` → **level 5** |

- **0 字节** = 只走了 `fopen(path,"wb")` 的截断创建，没写 header。
- **44 字节** = 完整 WAV header（`RIFF....WAVEfmt ...data`）写进去了 —— 这是 `uni_4mic_test -uid` 单独就能触发的。
- **>44 字节** = PCM 真的开始落盘。

> 反汇编佐证（`libuni4michal.so`，Thumb）：
> ```
> set4MicDebugMode(int m):   level = (m == 0) ? 1 : 4      ; 我们走的是 4
> uni_4mic_set_debug_level:  level = clamp(level, 1, 5)    ; test 程序给 5
> uni_4mic_set_debug_mode:   if (m > 3) 静默丢弃
> uni_4mic_set_debug_name:   name == NULL → 返回 -1；strlen(name) > 15 → 返回 -1
> ```

---

## 三、机制全貌（so 级证据）

`libuni4michal.so` 导出符号里直接躺着 dump 的全部家当：

```
file_4mic / file_2aec / file_out                    ← 三个 FILE* 全局句柄
file_4mic_data_size / file_2aec_data_size / file_out_data_size   ← 已写字节数
header_4mic / header_2aec / header_out              ← WAV header 缓冲
top_status_f_debug                                  ← 调试总开关
uni_4mic_set_debug_mode / _level / _name            ← 三个设置函数
uni_4mic_env_cfg_init                               ← 读配置
```

路径拼装（`__sprintf_chk`）：

```
"%s/waking_%s"  ← 唤醒中
"%s/waked_%s"   ← 已唤醒
     %s = debug_file_path（来自 pcm_hw_config.txt）
     后半 = file_4mic.wav / file_2aec.wav / file_out.wav
⇒ /sdcard/unidata/ + waking_ + file_4mic.wav = /sdcard/unidata/waking_file_4mic.wav ✓ 完全对解
```

配置来源（`pcm_hw_config.txt`，实测内容）：

```
pcm_card 1                 ← card1 = ma4（四麦采集口）
pcm_device 0
i2s_bits 32                ← I2S 物理层 32bit（4 麦打包）
linear_4mic 0              ← 0 = 圆形阵列
no_vad 0
high_priority 1
mic_array_rw_path /system/usr/uni_4mic_config/
aec_enlarge_4mic 2
debug_file_path /sdcard/unidata/     ← dump 输出目录（末尾无换行）
```

> `pcm_hw_config_chance.txt` 是「chance」变体（`aec_enlarge_4mic 1`），对应 `/system/bin/uni_4mic_testchance`。
> 另有 `mic4_uni_test_config.txt`（`test_mic_file` / `test_aec_file` 指向那两个 wav，是**回放自测的输入**，不是输出）。

---

## 四、这批数据说明了什么（关键判读）

### 4.1 四麦四个通道的电平（本次是**静音**样本，只有底噪）

| 通道 | RMS | dBFS | 备注 |
|---|---|---|---|
| ch0 | 13.5 | **−67.7** | 与我们 App 的底噪（−68.5）几乎一致 |
| ch1 | 24.8 | **−62.4** | 比 App 高 6 dB |
| ch2 | 28.0 | **−61.4** | 比 App 高 7 dB |
| ch3 | 21.7 | **−63.6** | 比 App 高 5 dB |

⇒ **四麦通道的拾取灵敏度普遍比我们 App 拿到的信号高 5~7 dB**（同样的安静房间，底噪更高 = 增益链路更强）。
这是「原厂远场更强」的第一个正面证据，但**静音样本不能证明 SNR 更好** —— 底噪高也可能是前级噪声更大。

### 4.2 `out` == `4mic[ch0, ch1]`（逐字节相同）

```
waking_file_out.wav  ch0 rms=13.5  ch1 rms=24.8
waking_file_4mic.wav ch0 rms=13.5  ch1 rms=24.8   ← 完全相同
```

⇒ **`uni_4mic_test` 的裸采集模式下，HAL「输出」就是四麦的前两个通道，没有做波束成形。**
波束成形/DOA 是在 `com.phiscmm.speaker.device` 走 `FourMicAudioManager`（`uni_hal_4mic_array_process`）时才启用的 —— 这正是原厂 App 独占的能力。

### 4.3 2aec 通道电平最低（−71 / −74 dBFS）

AEC 把回声和一部分噪声减掉了，所以 RMS 更低。这是**参考量**，不是给 ASR 用的。

---

## 五、这批数据**不能**证明什么（避免误判）

- ❌ **不能**证明「顶替/直连四麦能提升 ASR 识别率」—— 静音样本没有语音 SNR 可比。
- ❌ **不能**说明我们 App 走 AudioFlinger 拿到了四麦增益 —— 我们的 uplink 原始电平（÷16 后 −58.4 dBFS）反而**比四麦 ch1/ch2 高**，但那是因为我们加了 16 倍数字增益，**数字增益不改变 SNR**。
- ⚠️ `-cap` 是**裸采集**（不做波束成形），所以 `out.wav` 并不等于「原厂助手实际送进 ASR 的那路信号」。

---

## 六、下一步（决定性实验，需要你配合说话）

**目标：量化「四麦通路 vs 我们 App」的真实语音 SNR 差**。

同距离（建议 2 m，正对音箱）朗读同一句话，两个通路各录一遍：

| 通路 | 命令 |
|---|---|
| A = 我们 App | `run-as cn.workbuddy.r1client cat files/r1client.log`（含 micTelem）＋ 现有上行 wav 机制 |
| B = 四麦裸采集 | `uni_4mic_test -cap ... -t 5`（分 4 通道看哪个 beam 最好） |
| C = 原厂算法 | 若能拿到 `FourMicAudioManager` 通路（需顶替或看 device 包日志） |

判据（**按优先级**）：

1. **语音段 SNR**（安静段 RMS vs 语音段 RMS，单位 dB）：B 是否比 A 高 ≥3 dB？
2. **把 A / B 的 wav 都丢给 NAS 的 ASR（192.168.2.189:8000）**，看识别文本谁对 —— 这是**唯一有决策力的判据**。
3. 若 B 明显更好 ⇒ **D 路线（Janus 顶替 → platform_app 域）才有价值**。
   若 B ≈ A ⇒ **不用动设备**，瓶颈在服务端 ASR 或者只能接受 0.5 m 定版（v1.3.27）。

---

## 七、附带发现（资产）

| 资产 | 位置 | 用途 |
|---|---|---|
| `uni_4mic_test` | `/system/bin/`（5372 B，shell 可执行） | 四麦裸采集 + dump 激活，**无需 root / 无需顶替** |
| `uni_4mic_testchance` | `/system/bin/`（5376 B） | chance 变体（`aec_enlarge_4mic 1`） |
| 反汇编工具 | `diag/pandora/uni_xref.py`（capstone，Thumb） | `xref <子串>` / `dis <符号>`，用于继续挖 HAL 行为 |
| 提取的库 | `diag/pandora/lib/*.so` | `libuni4michal.so` / `libUniMicArray.so` / `libaec.so` / Audio HAL |
| 本次数据 | `diag/pandora/uni4mic_cap/*` | 4ch 原始 + out + 2aec + cap.pcm |
| 分析脚本 | `diag/pandora/analyze_uni4mic_cap.py` | 格式识别 / 每通道电平 / 频谱 |

> ⚠️ 设备端 shell 无 `head/sed/sort/tail` → 一律 `exec-out` 或重定向到 `/data/local/tmp` 再本机处理。
> ⚠️ `/sdcard/unidata` 权限 `drwxrwx--- system sdcard_rw`，shell（gid sdcard_rw）可读写；HAL 自己会 `mkdir -p`。
> ⚠️ 跑 `uni_4mic_test` 前**必须停自己的服务**，否则 card1 被 mediaserver 独占（EBUSY）→ 只拿到 0 字节。

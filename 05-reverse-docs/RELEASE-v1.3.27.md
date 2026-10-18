# R1 小智客户端 —— v1.3.27 定版说明

> 定版日期：2026-10-04 21:35　|　决策：**接受 0.5 m 近场**（方案 A）
> 依据：`diag/audio/VERDICT-audio-frontend.md`（远场"听不清"结案）+ `diag/kwsdm/VERDICT-v1.3.27-distance.md`（唤醒距离结案）

---

## 一、定版信息

| 项 | 值 |
|---|---|
| 版本 | **v1.3.27**（versionCode **49**） |
| 包名 / 服务 | `cn.workbuddy.r1client` / `.R1ClientService` |
| ABI / 系统 | armeabi-v7a / Android 5.1.1（API 22）/ rk3229 |
| 源码快照 | `patchwork/ax/b/r1-client`（a=1.3.26, b=1.3.27） |
| 定版补丁 | `r1-client-v1.3.26-to-v1.3.27-kws-tune.patch` |

## 二、一句话状态

**唤醒 + 对话在 ≤0.5 m 全部可用，零漏检零虚警；0.5 m 以外受限于麦克风前端 SNR 墙，为定版接受的边界。**

## 三、已验证能力（实机实测）

| 能力 | 结果 |
|---|---|
| 唤醒词 | 「小智小智」/「小讯小讯」，`wakeup src=kws` 正常 |
| KWS 参数（固化） | `gain=1.0 / thr=0.05 / score=6.0 / paths=8 / blank=2` |
| 唤醒召回 / 虚警 | 真实喊话召回 **2→4 段**；纯底噪误报 **0**；自家 TTS 正确忽略 |
| 抗大瞬态虚警 | 全轨最响的拖凳声（raw 峰 10413 = 31.8% FS）**不误触发** ✓ |
| 对话（ASR→TTS） | ≤0.5 m 正常，无变调（服务器尊重 hello 的 16k） |
| 心跳 / 自启 | `alive Ns` 连续；BootReceiver 开机自启 ✓ |

## 四、已知边界（定版接受，非缺陷）

| 距离 | 唤醒(KWS) | 对话(ASR) | 备注 |
|---|---|---|---|
| 0.2 m | ✓ | ✓ | SNR +21.9 dB |
| **0.5 m** | **✓** | **✓** | **SNR +10.3 dB ← 定版可用边界** |
| 1 m | ✗ | ✗ | SNR +4.8 dB（ASR 需 ≥10~15 dB） |
| 2 m | ✗ | ✗ | SNR +2.1 dB（已沉进底噪） |

**根因**：R1 上行前端 SNR 墙 —— 空闲底噪 **−43.3 dBFS**（÷16 折原始 −67.6 dBFS，高出量化底 25 dB，
属模拟/声学噪声）。四条软件杠杆（Android NS/AGC 链、AK7755 模拟前级、card1 四麦控件、数字增益）
**已逐一实测封死**；`sessionBind`/AEC 首次真机补验亦无效。
**决定性证据**：参考 App `com.huihongcloud.xiaozhi` 手机 3 m 可用、装机到 R1 上 **0.2 m 就跪**
⇒ 差距全在硬件前端，framework/客户端路径**无优化空间**。
（原厂「小讯」能 4~5 m，走的是 Unisound 私有四麦 HAL —— 被 SELinux 挡在 `untrusted_app` 之外，够不着。）

## 五、未采纳的可选升级（留档，将来若需远场再走）

| 方案 | 说明 |
|---|---|
| **B. USB 麦克风/声卡** | `audio.usb.default.so` 存在 ⇒ ROM 支持 USB 音频；需先验 R1 是否支持 USB Host/OTG |
| **C. 手机/ESP32 当前端，R1 当音箱** | 手机已实测 3 m；把录音搬过去，R1 只做播放 + 唤醒 |
| D. app 内自建 NS+AGC | 低 ROI（噪声宽带无结构，预期 <3 dB），不建议 |
| E. root + 改 SELinux 走四麦 HAL | 不可行（`ro.secure=1`，无 root） |

## 六、部署（WSL 侧一条龙）

```bash
A="/mnt/c/Users/shaoh/WorkBuddy/ridibooks/【adb和fastboot工具】platform-tools 28.0.0 (June 2018)/adb.exe"
"$A" -s 192.168.2.5:5555 push <apk> /data/local/tmp/r1.apk && \
"$A" -s 192.168.2.5:5555 shell /system/bin/pm install -r /data/local/tmp/r1.apk && \
"$A" -s 192.168.2.5:5555 shell rm /data/local/tmp/r1.apk && \
"$A" -s 192.168.2.5:5555 shell am startservice -n cn.workbuddy.r1client/.R1ClientService
```

> 铁律：`adb install` 不可用 → `push` 到 `/data/local/tmp/` 后走 **`/system/bin/pm install -r`**（绝对路径）；
> push 后**务必 `ls -l` 对比大小**（WiFi 传输可能静默截断）。

## 七、日常取证

```bash
# 持久日志（免疫 USC 刷屏，落盘在内部存储）
"$A" -s 192.168.2.5:5555 shell run-as cn.workbuddy.r1client cat files/r1client.log

# 关键行
#   micTelem ...            实时上行 rms/peak（每 ~5.8s）
#   wakeup src=kws:...      唤醒
#   KwsEngine ready ...     参数生效
#   conv cfg: ...           kwsGain/kwsThr/kwsScore/kwsPaths/kwsBlank/kwsDump
```

## 八、相关文档

| 文档 | 内容 |
|---|---|
| `diag/audio/VERDICT-audio-frontend.md` | 远场"听不清"结案（SNR 墙 + 四条杠杆封死 + 通路所有权） |
| `diag/kwsdm/VERDICT-v1.3.27-distance.md` | 唤醒距离结案（9 次喊话能量指纹 + 3 次唤醒对齐） |
| `diag/uni4mic/`、`diag/micprobe*/` | 四麦 HAL 逆向与麦克风取证 |

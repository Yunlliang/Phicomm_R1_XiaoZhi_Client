# CVE-2017-13156 (Janus) 绕过 —— 本地 PoC 复现 + 设备实证

> 结论先行：**Janus 绕过在本机 PC 上完整复现成功；而且 R1 设备上此刻已经有两个真实的 Janus 系统包在跑。**
> 「伪装成 Pandora 调四麦阵列」这条路，从「原理可行」升级为「**已有现成工具链 + 本机已实证**」。

---

## 一、原理（一句话 + 一张图）

APK 的 **v1 (JAR) 签名只签名 ZIP 里「条目的名字 + 内容」，不签 ZIP 的文件结构**；而 Android 判定「这是不是一个 DEX」只看**文件第 0 字节**。

于是构造一个**同时是合法 DEX、又是合法 ZIP** 的文件：

```
[ 攻击者 DEX  ← ART 从 offset 0 读，执行它 ] [ 原厂已签名 APK（ZIP）← 校验器从文件尾读，签名通过 ]
 └──────────── 签名盲区（不属于任何 ZIP 条目）────────────┘ └──── 被签名覆盖的部分 ────┘
```

* **结果**：代码是你的，**身份（包名 / 签名 / uid / SELinux 域 / 权限）是原厂的**。
* **ZIP 里那些偏移字段没被签名**，所以把 DEX 前置后只要把 `EOCD 的中央目录偏移` 与 `每个中央目录条目的本地头偏移` 各加 `len(DEX)`，签名依然有效。

---

## 二、PoC 结果（四组对照，PC 本地，全程不碰设备）

用 `janus_poc.py` 在 PC 上**复刻 Android ≤8 的 v1 校验算法**（MANIFEST.MF 逐条目摘要 → CERT.SF → CERT.RSA PKCS#7 经 openssl 验签）：

| # | 样本 | 文件头 | 前置区 | 条目摘要 | CERT.SF | PKCS#7 | 判定 |
|---|---|---|---|---|---|---|---|
| ① | `EchoService.apk`（原厂干净） | `PK\x03\x04` | 0 B | 12/12 OK | OK | OK | **PASS**（基线） |
| ② | `new_EchoService1.8.82.apk`（民间魔改） | **`dex\n035`** | **5,706,812 B** | 12/12 OK | OK | OK | **PASS** ← 就是漏洞 |
| ③ | 篡改 1 字节（阴性对照） | `PK\x03\x04` | 0 B | **11 OK / 1 BAD** | OK | OK | **FAIL** ✔ 校验器有效 |
| ④ | `janus_EchoService.apk`（**自制**） | **`dex\n035`** | **2,751,780 B** | 12/12 OK | OK | OK | **PASS** |

**③ 是关键**：校验器并不是「什么都放过」——往里改 1 个字节立刻就 FAIL。而 ②④ 却全绿。

---

## 三、自制 PoC（④）细节

```
payload DEX : r1-client-v1.2.17.apk/classes.dex   （我们自己的代码，2,751,780 B，1431 个类）
target  APK : EchoService.apk（原厂平台签名，1,160,423 B，15 条目）
输出        : janus_EchoService.apk = [DEX 2,751,780 B] + [原厂 APK，偏移已修补] = 3,912,203 B
```

| 自检项 | 结果 |
|---|---|
| 首 8 字节 | `dex\n035\x00` ✔（ART 会当 DEX 加载） |
| ZIP 首次 `PK\x03\x04` | offset **2,751,780**（= 前置区长度） |
| `unzip -t` | **No errors detected**（rc=0） |
| DEX 头自洽 | `file_size=3,912,203`（= 全文件长）、SHA-1 OK、adler32 OK |
| 签名盲区 | offset 0 ~ 2,751,780（**2.75 MB 完全不在签名覆盖范围**） |
| **执行体 vs 签名体** | 前置 DEX `sha256=5d90e3cf…`（我们的）≠ ZIP `classes.dex` `sha256=c6f87d0f…`（原厂的）→ **两套代码** |

构造算法与 V-E-O/PoC 的 `janus.py` 完全一致（前置 DEX → 修补 EOCD 中央目录偏移 → 修补每个 CD 条目的本地头偏移 → 把 DEX 头的 `file_size` 改成全文件长并重算 SHA-1 / adler32）。

---

## 四、真实世界的两个 Janus 样本（都在你手上 / 设备里）

| 文件 | 总长 | 前置区 | 前置 DEX 类数 | ZIP 内 classes.dex 类数 | 判读 |
|---|---|---|---|---|---|
| `new_EchoService1.8.82.apk` | 6,868,330 | 5,706,812 | **5187** | 1850（= 原厂） | 民间魔改 `com.phicomm.speaker.player` |
| `pandora.apk`（= 你给的 `Pandora.com.phicomm.speaker.device.apk.zip`） | 30,361,519 | 5,425,132 | **5536** | 5306（= 原厂 Unisound） | 民间魔改 `com.phicomm.speaker.device` |
| （对照）`Unisound.apk`（设备原厂） | 24,936,387 | 0 | — | 5306 | 干净 |
| （对照）`EchoService.apk`（设备原厂） | 1,160,423 | 0 | — | 1850 | 干净 |

两处细节坐实「**执行体 ≠ 签名体**」：
1. 每个 Janus 文件的**前置 DEX 类数 ≠ ZIP 内 classes.dex 类数**（5187 vs 1850、5536 vs 5306）；
2. 前置 DEX 头的 `file_size` **被改写成整个合并文件的长度**（6,868,330 / 30,361,519）——与 V-E-O PoC 的 `out_data[32:36] = len(out_data)` 行为逐字节吻合 → **民间工具就是这套 PoC 或其直系后代**。

额外发现：`SystemTool.apk`（设备 `/system/app`，7,501 B）**ZIP 里没有 `classes.dex`，但 MANIFEST.MF 里列着它**。这是构造器的另一种省体积手法——**Android 的 JarVerifier 只遍历 ZIP 里实际存在的条目**，manifest 里多出来的条目被忽略，所以无害（我们的校验器已按此语义修正）。

---

## 五、设备侧实证（**只读**，仅 `dd` 16 字节）

```
/data/app/com.phicomm.speaker.player-2/base.apk   7,262,014 B  2025-09-08 19:56
   first16: 64 65 78 0a 30 33 35 00 ...        → "dex\n035\0"  ★ Janus

/data/app/com.phicomm.speaker.device-1/base.apk  30,361,519 B  2026-10-03 11:05
   first16: 64 65 78 0a 30 33 35 00 ...        → "dex\n035\0"  ★ Janus
```

* 后者 **30,361,519 B 与你给的 `pandora.apk` 长度完全相同** → 设备上跑的就是那个魔改 device 包。
* 也就是说：**这台 R1 早就在跑第三方 Janus 包了**（`speaker.player` 和 `speaker.device` 两个），而这正是「民间免拆复活」的实现方式。
* 顺带解释了上一轮的一个疑问：`com.phicomm.speaker.device` 为什么是 `DEBUGGABLE`、versionName 是 `V3.2.5.2-`——那是 MOD 的版本号。

---

## 六、为什么这台机器「正好能打」

| 条件 | 本机 | 说明 |
|---|---|---|
| Android 版本 | 5.1.1 (API 22) | 在漏洞区间（5.0 ~ 8.0） |
| 安全补丁 | **2016-04-01** | 远早于 2017 年修复 |
| APK 签名方案 | **v1-only**（四个原厂 APK 均无 `APK Sig Block 42`） | v2 会覆盖全文件，v1-only 才有盲区 |
| root 需求 | **不需要** | 普通 `pm install` 即可 |
| 平台签名外壳 | 手上已有 4 份（EchoService / Unisound / SystemTool / ota service） | 直接当前置 ZIP 用 |
| **实证** | 设备上已跑着 2 个 Janus 包 | 不是理论可行，是既成事实 |

修复侧（供参考）：Android 8.0 起 v1 校验会拒绝「ZIP 结构之外还有前置数据」的 APK，且 API 24+ 强制 v2 全文件签名覆盖 → 本机两者皆无。

---

## 七、对「调四麦阵列」意味着什么

民间 `new_Unisound` 顶替的正是 **`com.phicomm.speaker.device`**，而它顶替后代码跑在 **`platform_app` 域**（我们的 SELinux 取证已实测：`platform_app` 对 `/dev/snd` 是 `search` 允许、只有 `getattr` 被拒 → `tinycap` 能 open 四麦设备）。

⇒ 于是远场通路多出第 4 条：

| 方案 | 手段 | 代价 |
|---|---|---|
| **D. Janus 顶替 `com.phicomm.speaker.device`** | 代码进 platform_app 域 → `loadLibrary` 整套四麦 HAL（`libUni4micHalJNI.so` + `libUniMicArray.so` + `libaec.so`…） | 包名**锁定**，会**顶掉现有 device 包** → 连带总线唤醒源 + 客户端 `WakeupBridge` 受影响；回退 = 重装原厂 `Unisound.apk`（已有备份） |

**风险等级：最高；收益等级：最高。**（而且民间已有 1.8.x 系列在用，可回退性有先例。）

---

## 八、复现命令

```bash
PY="C:/Users/shaoh/.workbuddy/binaries/python/envs/py312/Scripts/python.exe"
cd "C:/Users/shaoh/WorkBuddy/R1/diag/pandora"

# ① 四组 v1 校验对照（基线 / 真实样本 / 阴性对照）
"$PY" janus_poc.py

# ② 自制 PoC + 真实样本前置 DEX 解剖
"$PY" janus_poc_run.py

# ③ 手工构造（可选）
"$PY" janus_poc.py --dex payload_classes.dex --apk EchoService.apk --out my_janus.apk
```

产物：`janus_EchoService.apk`（自制 PoC）、`payload_classes.dex`（我们自己的 DEX）、`real_mod_prefix.dex`（真实样本前置区）、`tamper_test.apk`（阴性对照）。

---

## 九、下一步（等你选）

| 选项 | 内容 | 是否碰设备 | 状态 |
|---|---|---|---|
| (b) | 读 `jojo729/R1_DEVICE`，搞清顶替后**怎么把音频接出来**（劫持了哪些 Binder 服务名） | 否 | ✅ **已完成** → `VERDICT-mod-audio-path.md` |
| (c) | 用 `run-as`（shell，非破坏）在 platform_app 域**复现一次四麦录音**，确认 HAL 真能出数据 | 只读 | 待做 |
| (d) | 真顶替 `com.phicomm.speaker.device` | **是（高危，须先确认回退）** | 待做（建议先做 (b) 里 §六 的对照实验） |

> **(b) 的一句话结论**：MOD **没有"接音频"这个动作** —— 原厂云知声 SDK + 四麦 HAL 原样沿用，**没有任何 Binder 服务被劫持**。
> 且发现四麦波束成形**同时被编进标准 Audio HAL**（跑在 mediaserver 里）→ 普通 `AudioRecord` 可能已吃到四麦增强音频，
> **顶替也许并非必需**。详见 `VERDICT-mod-audio-path.md` §四 / §六。

---

## 附：本目录证据文件

| 文件 | 说明 |
|---|---|
| `janus_poc.py` | v1 校验复刻器 + Janus 构造器 + 阴性对照 |
| `janus_poc_run.py` | 自制 PoC 全流程 + 真实样本前置 DEX 解剖 |
| `janus_EchoService.apk` | **自制 Janus PoC 产物** |
| `payload_classes.dex` | PoC 用的前置 DEX（来自我们自己的 r1-client-v1.2.17） |
| `real_mod_prefix.dex` | `new_EchoService1.8.82.apk` 的前置 DEX（5,706,812 B） |
| `tamper_test.apk` | 阴性对照样本 |
| `pandora.apk` / `Unisound.apk` / `EchoService.apk` / `SystemTool.apk` | 四个待检 / 对照 APK |
| `mac_permissions.xml` / `mp2.der` / `*.pem` | 平台证书与 seinfo 映射（签名基准） |

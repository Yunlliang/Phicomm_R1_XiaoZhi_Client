# 结案：伪装 Pandora 调四麦阵列 —— ❌"伪造签名"死路，✅ 但 **Janus(CVE-2017-13156) 有旁路**（2026-10-04 22:10 修订）

> ### 🔥 2026-10-04 22:50 更新：**Janus 已本地 PoC 复现成功，且设备上已有 2 个真实 Janus 包在跑**
> 详见 → **`VERDICT-janus-poc.md`**（本文档的"下一步 (a)"已完成）
> 要点：四组对照（基线 PASS / 真实魔改样本 PASS / 篡改 1 字节 FAIL / **自制 PoC PASS**）；
> `/data/app/com.phicomm.speaker.player-2/base.apk` 与 `/data/app/com.phicomm.speaker.device-1/base.apk`
> 文件头均为 `dex\n035\0` ⇒ **本机已在跑第三方 Janus 系统包，非理论可行**。

> 原问题：「能不能逆向 Pandora 的 apk，把我们的 apk 伪装成 Pandora，调用四麦阵列？」
> **修订触发**：用户指出 —— **`EchoService` 不是原厂包，是第三方作者魔改的，而且还在持续更新**（恩山 `new_EchoService`，已迭代到 1.8.46）。
> 这条纠正把我上一轮「不可能」的结论**推翻了一半**：不是"伪造签名"，而是"**顶替身份**"。

---

## 一句话结论

1. **"改包名/伪造平台签名" —— 依旧不可行**：SELinux 域由「签名(seinfo) + uid/sharedUserId」决定，
   **与包名无关**（§一）；Phicomm 平台私钥我们只有公钥（§二）；设备无 root（§三）。
2. **✅ 但民间根本不需要私钥** —— 走 **CVE-2017-13156（Janus）**（§四）：
   把自定义 **DEX 前置**到一个**平台已签名的 APK** 里再 `pm install`。
   v1(JAR) 签名只覆盖 ZIP **条目内容**、**不覆盖 ZIP 结构** ⇒ **签名校验照过**；
   而 ART 从 offset 0 读到的是**前置的 DEX** ⇒ **我们的代码跑进了那个平台签名包的身份里**。
   ⚠️ 代价：**包名锁定为被顶替的原厂包**（manifest 改了会让 v1 摘要失配），所以是"**顶替**"不是"新建"。
3. 本机 **安全补丁 = `2016-04-01`**（Android 5.1.1 / LMY49F / 3448）⇒ 落在 Janus 影响区间（5.0–8.0 且未打 2017 补丁）⇒ **可利用**。
4. **本机实证**：`/data/app/com.phicomm.speaker.player-2`（`UPDATED_SYSTEM_APP`，**lastUpdateTime = 2025-09-08**）
   是一个 **2025 年的第三方更新包，却占着 `android.uid.system`（uid 1000）** ——
   在无 root、无平台私钥的前提下，这**只能是签名校验被绕过**（= Janus 路线）。

---

## 一、域是怎么分的：**不看包名**（`seapp_contexts` 实证）

设备 `/seapp_contexts`（Enforcing）：

```
user=system                 domain=system_app      type=system_app_data_file
user=_app seinfo=platform   domain=platform_app    type=app_data_file
user=_app                   domain=untrusted_app   type=app_data_file
```

- **没有任何 `name=` 规则** ⇒ 改包名对域**零影响**，永远落 `untrusted_app`。
- 想进 `system_app` ⇒ 必须 `sharedUserId=android.uid.system`；想进 `platform_app` ⇒ 必须 `seinfo=platform`。
- **这两条规则决定了"目标域"，但不决定"能不能把包装进去"** —— 后者才是 Janus 的战场（§四）。

**佐证**：`com.phicomm.speaker.player` 装在 **/data/app** 却是 `system_app`（uid 1000，属 `android.uid.system`）；
`com.phicomm.speaker.launcher` 在 **/system/app** 却是 `untrusted_app`。
⇒ **安装位置不影响分域，"系统应用"≠特权域。**

## 二、平台证书 = Phicomm 私有密钥（**我们拿不到私钥**）

`/system/etc/security/mac_permissions.xml` 里只有**一条**签名映射：

```
signer 34:A2:D2:FE:89:96:56:AF:5D:F3:FB:F6:8E:80:54:77:B3:DF:37:08:CA:65:C8:42:BA:EC:2D:E8:E5:78:BC:33
  → seinfo=platform
default → seinfo=default
```

- 该证书 **== 签名 Unisound.apk / pandora.apk / SystemTool.apk / EchoService.apk 的那把**
  （四份 APK 逐一提取 CERT.RSA，指纹**全部一致**）。
- subject：`C=CN, ST=GuangDong, O=Company, OU=ShenZhen RD Center, CN=R1, phicomm@phicomm.com`，**自签 2017-11-29**。
- **不是 AOSP 公共测试键**（AOSP testkey 的 subject 是 android@android.com）。
⇒ **无法伪造签名** —— 但注意：Janus **绕过的不是"签名算法"，而是"签名校验的时机"**，所以这一条不构成障碍。

## 三、无 root（`adb root` 实测无效）

```
ro.debuggable=1   ro.secure=1   ro.build.type=user   ro.build.tags=release-keys   ro.build.version.security_patch=2016-04-01
```

- `adb root` → 断连重连后仍是 `uid=2000(shell) context=u:r:shell:s0`。
- `ro.debuggable=1` 是摆设：`user` 版 adbd 未编 `ALLOW_ADBD_ROOT`。
⇒ 改不了 `/system`、`setenforce 0` 也不可能。**（但这不影响 Janus —— 它走的是普通 `pm install`，不需要 root。）**

---

## 四、✅ 关键补漏：**CVE-2017-13156（Janus）—— 不需要私钥的安装绕过**

### 4.1 民间已经把原理写明了（恩山 thread-8472542）

> 楼主问：「论坛的大佬是怎么做到自己定制 APK 覆盖官方的软件的？在没有源码和签名的情况下。」
> 回帖 #7：「**利用漏洞修改的，你查一下 CVE-2017-13156 就知道了**」
> 回帖 #3：「公共场所，请勿讨论漏洞：**janus**！」
> 回帖 #5（楼主）：「核心代码：`PmsHookBinderInvocationHandler` / `ServiceManagerWraper`（**解决签名验证问题**）；
> hook 原始语音命令采用新的 unisound api；源码 `github.com/jojo729/R1_DEVICE`」
> 回帖 #4：「`new_EchoService` 就是这么做到的把 —— **Binder 服务劫持** + **AudioFlinger 代理注入**」

### 4.2 原理（为什么不需要私钥）

- APK 的 **v1(JAR) 签名** = 对 ZIP **每个条目的内容**做摘要（写进 `MANIFEST.MF`），再对 `MANIFEST.MF` 签名（`CERT.SF`/`CERT.RSA`）。
  **它不覆盖 ZIP 的中央目录、也不覆盖文件头偏移**。
- ⇒ 只要**不动条目内容**，就可以**重排/前置**字节、**修好 ZIP 偏移**，v1 签名**依然有效**。
- ⇒ 构造 `[自定义 DEX][原平台签名 APK]`（把 ZIP 里所有偏移 += DEX 长度）：
  - **PackageManager** 从文件尾的 EOCD 读 ZIP → 条目内容没变 → **签名 = 原平台签名** → **安装通过**，且
    **共享 uid / seinfo 全部按原包算**（这就是能占进 `android.uid.system` 的原因）；
  - **ART** 从 **offset 0** 读 → 看到 `dex\n035` → **执行我们前置的 DEX**。
- 结论：**代码 = 我们的，身份 = 原厂的。** 这是 5.0–8.0 未打补丁设备的经典 Janus。

### 4.3 本机可利用性

| 条件 | 本机值 | 判定 |
|---|---|---|
| Android 版本 | 5.1.1 (API 22) | ✅ 在 Janus 影响区间 |
| 安全补丁 | **2016-04-01** | ✅ **远早于 2017 修复** |
| root | 无 | ✅ **Janus 不需要 root**（普通 pm install） |
| 平台签名 APK | 手上已有 4 份（Unisound/Pandora、EchoService/player、SystemTool、OtaService） | ✅ 作"外壳" |
| 现成先例 | `/data/app/…player-2` 第三方更新包**已在 uid 1000** | ✅ 旁证 |

---

## 五、民间实况：他们做的**正是你想做的事**

| 项目 | 顶替的包 | 作用 |
|---|---|---|
| **`new_EchoService`**（→1.8.46） | `com.phicomm.speaker.player`（EchoService，uid 1000） | 播放器 + 网页控制（:8080）+ 氛围灯 |
| **`new_Unisound`**（→1.8.46） | `com.phicomm.speaker.device`（Pandora） | **劫持语义 → 自建服务器二次解析**（= 你现在的架构） |

- 安装方式：**免拆、免 root**（`pm install`）。恢复官方 = `pm uninstall com.phicomm.speaker.device` + `…player`。
- `new_Unisound` 的功能列表里已经出现：「**支持调节唤醒灵敏度**」「劫持语义本地/云端测试」「去除绑定判断」。
- 作者（jojo729）在论坛里明说目标是「**复刻小智**，搭配电视盒子做简单智能体」——**和你的 R1 小智客户端是同一件事**。
- 项目 `github.com/jojo729/R1_DEVICE`：公开、**GPL-2.0**、Java、32 stars、default_branch=`main`（2023-05-31 最后提交）。
- 商业侧：`new_Unisound`/`new_EchoService` 是**闭源 + 付费授权**（3 元/月）。

## 六、意外收获与其它被否的路

### 6.1 Pandora = `com.phicomm.speaker.device`，且 **`debuggable=true`**

- `/system/app/Unisound/Unisound.apk` **就是** `com.phicomm.speaker.device`（同一包名、同一套 lib）。
- **manifest `android:debuggable="true"`**：`run-as` **成功** → `context=u:r:platform_app:s0`（借到特权域）。
- 但 **`run-as` 只允许 shell/root**（二进制内字符串：
  `"only 'shell' or 'root' users can run this program"`）⇒ 只能作调试技巧，**变不成 app 的运行时能力**。

### 6.2 platform_app 域对 `/dev/snd` 的权限：**部分允许**

`/dev/snd` 目录 **777**、`pcmC1D0c`（四麦采集节点）**`crwxrwxrwx`** ⇒ **DAC 全开，唯一门槛是 SELinux**。

```
avc: denied { search }  scontext=u:r:untrusted_app:s0  tcontext=u:object_r:audio_device:s0 tclass=dir   ← 我们
avc: denied { getattr } scontext=u:r:platform_app:s0   tcontext=u:object_r:audio_device:s0 tclass=dir   ← Pandora 域
```

即：`untrusted_app` 连 `search` 都被拒；`platform_app` 只被拒 `getattr`，**`search` 允许** →
`tinycap` 在 platform_app 域下**成功 open 设备**（打印 `Capturing sample: 2 ch, 48000 hz, 32 bit`）。
> ⚠️ 本次直录**未产出数据（读取阻塞）**；r1client 会自启重占 card1，此点存疑 —— 但"**能 open**"已证明域权限够一半。

### 6.3 四麦 HAL 在哪（`/system/lib`，随包加载）

```
libUni4micHalJNI.so      9,312 B     ← JNI 入口
libUniMicArray.so      535,444 B     ← 阵列算法
libUniLinearMicArray.so 711,736 B    ← 线性阵列
libuni4michal.so        25,820 B
libuni4michalchance.so  25,824 B
libaec.so              152,832 B     ← AEC
libofflinenlujni.so    394,624 B     ← 离线 NLU
```
⇒ 这些由 `com.phicomm.speaker.device` 进程 `System.loadLibrary` 加载。**谁顶替了那个包，谁就能加载它们。**

### 6.4 其它被否路径（依旧否）

| 路径 | 结果 |
|---|---|
| `wrap.<pkg>` 属性 | ✗ `property_contexts` 无 wrap 条目，setprop 静默失败 |
| `run-as com.phicomm.speaker.player` | ✗ `Package '…' is not an application`（共享 uid 包 run-as 拒绝） |
| 直接写 `/system` | ✗ 无 root |

---

## 七、修订后的结论与影响

1. **"把我们的 app 伪装成 Pandora" 字面做法仍不可行**（包名无关、签名伪造不了）。
2. **但 ✅ 有一条真实可行的旁路**：**用 Janus 顶替 `com.phicomm.speaker.device` 的身份** ——
   我们的代码将以 **`platform_app` 域**运行，且**能加载 `/system/lib/libUni4micHalJNI.so` 一整套四麦 HAL**。
   ⇒ **这就是"调用四麦阵列"的正解**，也正是民间 `new_Unisound` 在做的事。
3. **代价与风险（必须先想清楚）**：
   - **会顶掉现有 `com.phicomm.speaker.device`**（原厂/民间 mod 都会被覆盖）→ 语音助手、总线唤醒源、
     以及**你客户端的 `WakeupBridge`**（依赖它投 msgcenter 事件）**全部受影响**。
   - 包名**锁定**为 `com.phicomm.speaker.device`，不能另起炉灶。
   - 5.1 依赖 ART 从 offset 0 取 DEX 的行为；做成后**该包的自检/校验都可能失败**，需实测。
   - 民间同类 mod 是**闭源付费**的，说明这条路工程量大且作者不愿公开。
4. **远场问题因此多出第 4 条出路**（原 `diag/audio/VERDICT-audio-frontend.md` §七的 A/B/C 之外）：
   **D. Janus 顶替 Pandora，直接把我们的客户端跑进 platform_app 域，吃四麦 HAL** —— 技术可行、风险最高、收益最大。

## 八、下一步（待用户拍板，**涉及改设备，先确认**）

- **(a) 只做本地 PoC（零风险）**：在 PC 上对某份系统 APK 构造 Janus 文件，验证
  ①文件头是 `dex\n035`、②`unzip -t` 通过、③`jarsigner -verify` 仍报"jar verified" ⇒ **证明绕过成立**，不碰设备。
- **(b) 深挖民间实现**：读 `github.com/jojo729/R1_DEVICE` + `PmsHookBinderInvocationHandler`/`ServiceManagerWraper`，
  搞清他们**顶替后怎么把音频接出来**（Binder 劫持了哪些服务名）。
  → ✅ **已完成**：见 **`VERDICT-mod-audio-path.md`**。
  一句话结论：**没有"接音频"这个动作，也没有任何 Binder 服务被劫持** —— 原厂云知声 SDK + 四麦 HAL 原样沿用；
  现役 1.8.x 是"拿到源码全量重编译 + Janus 顶签名"。**且发现四麦算法同时被编进标准 Audio HAL（mediaserver 内）**，
  ⇒ 普通 `AudioRecord` 可能本来就吃四麦增强音频 ⇒ **顶替也许并非必需**（待一次对照实验拍板）。
- **(c) 只在 `run-as`（shell，非破坏）下**复现一次"platform_app 域读四麦"，确认 HAL 是否真能出数据。
- **(d) 真做**：顶替 `com.phicomm.speaker.device` —— **有丢语音助手风险，须先备份/确认可回退**
  （回退 = `pm uninstall com.phicomm.speaker.device` 恢复出厂版）。

## 九、证据文件（`diag/pandora/`）

| 文件 | 说明 |
|---|---|
| `mac_permissions.xml` + `mp.hex`/`mp.der` | 平台证书映射（seinfo=platform） |
| `es.pem` / `st.pem` | EchoService / SystemTool 签名证书（指纹与上同） |
| `Unisound.apk` / `pandora.apk` / `EchoService.apk` / `SystemTool.apk` | 拉回的系统 APK（四份均平台签名） |
| `player_dump.txt` | `dumpsys package com.phicomm.speaker.player`（含 `/data/app` 副本 + uid 1000 + 更新时间） |
| `pandora_manifest.xml` | Pandora 完整 manifest（`debuggable=true`、组件、权限） |
| `run-as` / `property_contexts.txt` | run-as 二进制字符串 / 属性策略 |
| `../audio/` | 前端 SNR 证据链（VERDICT-audio-frontend.md） |

### 外部来源
- 恩山 thread-8472542《斐讯R1的官方软件是怎么被覆盖》—— 点明 **CVE-2017-13156 (Janus)**、源码仓库、Binder/AudioFlinger 劫持
- 恩山 thread-4073677《（免拆复活小讯）基于最新版本Unisound修改》—— `new_Unisound`/`new_EchoService` 功能与更新日志
- `github.com/jojo729/R1_DEVICE`（GPL-2.0）
- 8fe.com《斐讯R1(EchoService.apk)dlna开启氛围灯方法》—— EchoService.apk 修改版 1.2MB

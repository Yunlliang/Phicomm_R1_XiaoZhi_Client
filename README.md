# Phicomm_R1_XiaoZhi_Client —— 源码、原厂包与逆向资料（release V2.0.1 快照）

这份是 **release V2.0.1** 的完整可重建快照。
装机只需要上一级的 `apps/` 里那三个 apk；本目录给的是「它是怎么来的」。

**对应关系**：device = 主包 `XiaoZhi_Unisound_V2.0.1.apk`；player = 控制台包 `XiaoZhi_EchoService_V2.0.1.apk`。

---

## 目录

| 目录 | 内容 |
|---|---|
| `01-parasite-src/device-v2src/` | 主包（device）我们写的 Java 层：会话控制、音频采集/上行、TTS 播放、音乐让路、灯控、曲库与 MCP 桥接、控制台后端 |
| `01-parasite-src/player-plsrc/` | 控制台包（player）注入层：Web 控制台 :8080、播放控制、拖动进度条、封面 / 歌词、蓝牙 / DLNA / AirPlay |
| `02-reverse-smali/device-uni_smali/` | 原厂 Pandora（`com.phicomm.speaker.device`）反编译后的 smali，**已含全部落地改动** |
| `02-reverse-smali/device-patches/` | 打在 smali 上的幂等补丁 `patch_*.py`，每个脚本头部写了「现象 → 根因 → 改在哪个类/方法」 |
| `02-reverse-smali/player-patches/` | player 侧 smali 补丁（三钩子 / 蓝牙 / DLNA / seek 等） |
| `03-stock-rom/` | 各版本原厂包（**未改动**），Janus 母本与对照用 |
| `04-build-scripts/device/` | 主包 release / 测试构建脚本 + `janus_poc.py` + R8 配置 + `libr1opus.so` + so 宿主构建脚本 + `ui/` + `javacp/` |
| `04-build-scripts/player/` | 控制台包 release / 测试构建脚本 + `ui/` + 资源生成脚本 |
| `05-reverse-docs/` | 逆向报告、AEC 方案、诊断记录、真机取证、溯源取证等 |

---

## 补丁脚本一览（device，打在 smali 上）

| 脚本 | 干了什么 |
|---|---|
| `patch_wakeword.py` | 原厂默认唤醒词「小讯小讯」→「小智小智」 |
| `patch_enter_asr.py` | 唤醒后直接 `enterASR`，跳过原厂提示音 |
| `patch_light.py` | 短路原厂呼吸灯（loading light），治「底部一直闪」 |
| `patch_boot_sound.py` | 关掉原厂启动音乐 / 网络状态提示音 |
| `patch_bindgate.py` | 拆掉原厂「未绑定 / 无网络 / 待升级」三道唤醒闸门（云已死，闸门永真不了） |
| `patch_light_hold.py` | 掐掉 `onRecognizeStart()` 的灭灯，让唤醒拾音灯能定格在收音方向 |

player 侧：

| 脚本 | 干了什么 |
|---|---|
| `patch_smali.py` | 三钩子：`Common.onCreate`→`Boot.attach`；`EasyPlayer.play`→`onPlay`；`playIndex(IZ)`→`onIndex`（幂等） |
| `patch_bt_scan.py` | 拔掉原厂两处把 scan mode 强设 NONE 的 kick |
| `patch_bt_hold.py` | 修「连上马上断」：`bootInit()` A2DP kick ＋ `BluetoothPlayer$2.run()` 的主凶分支 |
| `patch_dlna_sync.py` | DLNA 推流同步「正在播放」 |
| `patch_seek.py` | AIDL 新增 seek 事务（拖动进度条） |

寄生层自己的改动（`01-parasite-src/`）按版本在文件头注释里标了 `v1.3.xxx` / `pl-1.6.x`。

---

## 两条版本序列

- **release `V2.0.x`**：验收通过才出，只进发布目录，产物 `XiaoZhi_*_V2.0.x.apk`。
- **测试版 `v1.3.x` / `pl-1.6.x`**：日常迭代，只在开发目录流转，不进发布目录。

release 脚本在编译前把版本号临时改成 `2.0.1` / `pl-2.0.1`、产物落盘后立刻还原，
所以源码树里躺着的永远是「下一个测试版」，两条序列不会串。

---

## 怎么重建

工具链不随包附带（体积大），脚本里写的是开发机绝对路径，换机器改脚本头部这几个变量：

```
JAVA_HOME  → 便携 JDK 17
R8 / D8    → r8.jar / d8 相关 jar
AJAR       → android.jar (API 22)
SMALI_CP   → smali / baksmali jar
```

```bash
# 主包 device：release / 测试
bash 04-build-scripts/device/build_rel_V201.sh
bash 04-build-scripts/device/build_v144.sh

# 控制台包 player：release / 测试
bash 04-build-scripts/player/build_rel_V201.sh
bash 04-build-scripts/player/build_pv22.sh

# so 宿主（需要 jarsigner）
cd 04-build-scripts/device
java -cp "<smali jar 路径>/*" org.jf.smali.Main a --api 22 sohost_stub -o sohost_stub.dex
python build_sohost.py --keep-dex
python build_pure_sohost.py
jarsigner -keystore r1-pure.jks -storepass android -keypass android ../../../r1-pure.apk androiddebugkey
```

主包链路：`javac` → `R8`（min-api 22）→ `baksmali` → 与原厂 `uni_smali` 合并 →
**钩子自检** → `janus_poc.py`（DEX + so 前置到原厂 APK 的 ZIP 目录之前，v1 签名验签 PASS）。

控制台包链路：`javac` → `d8` → `baksmali` → 与原厂 player dex 合并 + 打钩子 →
`smali` → `janus_poc.py` 拼接到 `EchoService_system.apk`。

> ★ 从 `build_*.sh` 派生新脚本时，全局替换产物名会**连底包路径一起继承**。
> 正常底包是 `diag/pandora/Unisound.apk`（134 条目）；一旦看到 **135 条目**，
> 说明误用了装不上那个注入版 `Unisound_lib.apk`。

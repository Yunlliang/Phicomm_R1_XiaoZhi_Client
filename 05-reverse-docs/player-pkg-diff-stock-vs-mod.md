# player 包对比：设备原厂 vs 魔改（含重建方案）

> 2026-10-14　｜　素材全部**从设备上实拉**
> `diag/pl_factory.apk` = `/system/app/EchoService/EchoService.apk`（原厂，1,160,423 B）
> `diag/pl_mod.apk` = `/data/app/com.phicomm.speaker.player-1/base.apk`（魔改，6,868,330 B）
> 脚本：`diag/_player_cmp.py`、`_player_cmp2.py`、`_player_cls.py`、`_injected.py`

---

## 0. 三句话结论

1. **魔改包的外壳 = 100% 原厂**（`classes.dex` 字节级全等，签名完整），作者没碰外壳一个字节。
2. **我们的音乐依赖的不是"小飞的功能"，而是他用新版 ExoPlayer 重写的播放层**
   （`new_NetPlayer` / `DlnaPlayer2` / `MultiExoPlayer`，注入在原厂包名下）。
3. **可以重建**，有三条路线；推荐 **路线 C**（播放搬进我们自己的包，彻底扔掉 player 依赖）。

---

## 1. 三方文件对照

| | 原厂固件 F | 魔改外壳 | 魔改载荷 |
|---|---|---|---|
| 大小 | 1,160,423 B | 1,161,518 B | 5,706,812 B |
| 文件头 | `PK` | `PK` | `dex\n035` |
| md5 | `fc6cd0ff…` | `aa000afe…` | `7c3b1734…` |
| ZIP 条目 | 15 | 22 | — |
| `classes.dex` | **2,496,580 B `79a64fba…`** | **2,496,580 B `79a64fba…`** | — |

### 1.1 外壳 vs 原厂：差异只有 7 个空目录条目

| 对比 | 结果 |
|---|---|
| 仅魔改外壳有 | 7（全是**空目录项**：`META-INF/`、`res/`、`res/mipmap-*-v4/`） |
| 仅原厂有 | 0 |
| **内容不同** | **0** |
| 完全相同 | 15（原厂的全部条目） |

⇒ 作者用的底包是 **EchoService v1.8.82 原厂包**（22 条目，带 `ic_launcher_round`），
设备 `/system/app` 里那份是 2018-07-07 的旧版（15 条目）。**两者 Java 代码完全一致**，
差别仅是图标资源。这也印证了：Janus 包的外壳必然是原厂，签名才能过。

---

## 2. 魔改到底加了什么（载荷 3,764 个独有类）

| 包 | 数量 | 是什么 |
|---|---|---|
| `com/google/common` | 1,803 | Guava |
| `com/google/android` | 1,285 | **ExoPlayer2（新版）** |
| `com/alibaba/fastjson` | 260 | fastjson |
| **`com/phicomm/speaker`** | **88** | ★ **注入原厂包名下的类（魔改核心）** |
| `fi/iki/elonen` | 38 | NanoHTTPD |
| `com/xiaofei` | 34 | 作者自己的类 |
| `javax/ws/rs`、`org/springframework/*`、`org/joda/time` | ~55 | 服务端库片段 |

作者**删掉**的：`com/tencent/bugly` 120 类（崩溃上报）、`com/easydlna/dlna` 34 类
（DMC 控制点，**DMR 渲染器保留**）、旧版 ExoPlayer 238 类。

---

## 3. ★★ 注入原厂包名下的 11 个顶层类（重建要复刻的就是这些）

```
com.phicomm.speaker.player/
├── MsgHandler                        接管原厂消息路由
├── PlayerBinderWrapper               Binder 包装
├── model/
│   ├── new_NetPlayer                 ★ 网络播放器（新版 ExoPlayer）
│   ├── NetPlayer4                    ★ 网络播放器 v4
│   ├── DlnaPlayer2                   ★ DLNA 播放器（我们用它播）
│   ├── MultiExoPlayer                ★ 多实例管理
│   └── EasyPlayer                    简易播放器
├── exomedia/…（64 个类）              devbrackets ExoMedia 播放框架
└── util/ChineseToEnglish              中文转英文（搜歌用）
```

**注意包名**：`exomedia` 被放在 `com.phicomm.speaker.player` 下，
连 `ExoMediaDrm` 都伪装成 `com.google.android.exoplayer2.drm.*` —— 刻意混淆归属。

---

## 4. ★★★ 依赖点定位：ExoPlayer 2.9 → 2.13+

| 标志 | 原厂 dex | 魔改载荷 |
|---|---|---|
| `ExtractorMediaSource`（老 API，2.9-） | **3** | **0** |
| `ProgressiveMediaSource`（2.10+） | **0** | **6** |
| `MediaSourceFactory`（2.12+） | 0 | 23 |
| `ExoPlayer.Builder`（2.13+） | 0 | 24 |
| `MediaItem`（2.12+） | 0 | 24 |
| `DefaultHttpDataSourceFactory` | 2 | 0 |

⇒ **原厂是 ExoPlayer ~2.9，魔改升级到 ~2.13+，并用 ExoMedia 重写了播放层。**

**这就是"音乐播放依赖魔改包"的根因**：我们经 DLNA `SetAVTransportURI` 推过去的 URI，
最终由 `DlnaPlayer2`（新版 ExoPlayer）解码播放。原厂 2.9 的老链路
（`ExtractorMediaSource`）对某些流（尤其 AAC 直播 / 需要 Range 的）支持更差。

---

## 5. ⚠️ 第二次勘误：`10.30.4.12` 也是原厂自带的

| 特征 | 原厂 dex | 魔改载荷 |
|---|---|---|
| `10.30.4.12` | **1** | 1 |
| `get_song_by_id` | **1** | 1 |
| `data-cloud-service` | **1** | 1 |
| `211.102.192.39:19999` | **1** | 1 |
| `aios-home.hivoice.cn` | **1** | 1 |

**它们全部在原厂 dex 里就有**，作者只是继承。
（跟 Unisound 那 4 个 10.x 是同一类情况 —— 开发期遗留的内网服。）

⇒ 修正两处旧备忘：
- ~~"魔改固件把音源换成作者私服"~~ → 音源地址是**原厂的**，作者没换。
- ~~"`10.30.4.12` 是作者私服"~~ → 原厂遗留。

**作者真正独有的只有 `com/xiaofei`（34 类）和注入的播放层。**

---

## 6. 8080 页面：模板在 dex 里，域名静态不可见

| 字符串 | 载荷 dex |
|---|---|
| `meta charset` | 1 |
| `    <script>` / `</script>` | 有（4 空格缩进 = Java 拼接的 HTML） |
| `createElement` | 9 |
| **`viewport-fit` / `format-detection` / `script.src` / `wxfsq`** | **全部 0** |

⇒ 我们 `curl :8080` 拿到的页面里那些 meta 标签和 `r1.wxfsq.com` 域名，
**在 dex 里搜不到** —— 作者做了混淆/加密存储，运行时才拼出来。

**这比硬编码更值得警惕**：静态审计看不见，运行时才暴露。
（已实测：访问 8080 会拉 `http://r1.wxfsq.com/new_r1_control.js`，解析到 112.13.210.66。）

---

## 7. 重建方案：三条路线

### 路线 A：换回原厂 player（零开发，风险：播放回退）

```bash
# 用魔改包的外壳 = EchoService v1.8.82 原厂（比 /system/app 那份新）
adb install -r pl_mod_shell.apk
adb reboot
```

- ✅ 零开发；外壳 dex 与固件原厂**字节级全等**，可审计；签名完整
- ⚠️ 播放后端退回 **ExoPlayer 2.9**，我们的 NAS 流可能播不了/播不好
- ✅ 回滚：`adb install -r diag/pl_mod.apk`（已备份）

### 路线 B：Janus 挂我们自己的载荷（保留原厂 DLNA 架构）

底包 = `pl_mod_shell.apk`（原厂 v1.8.82），载荷 = 我们自己写的 dex：

- 打包 **ExoPlayer 官方 2.13+**（不是作者那份）
- 复刻 `MsgHandler` / `new_NetPlayer` / `DlnaPlayer2` 的消息契约
- 本地控制页改成**内联 HTML**（不拉远程 JS）
- 去掉 `com.xiaofei.*`、`Screencap`、`Auto_restart_device`、远程域名

- ✅ `PlayerBridge.java` 不用改（DLNA 接口不变）
- ⚠️ 工作量最大：要逆向原厂 `MsgHandler` 的接口契约（约 11 个顶层类 + 消息码）
- ⚠️ 仍依赖原厂闭源 player 作宿主

### 路线 C：★ 播放搬进我们自己的 device 包（推荐）

**不依赖 player 包、不走 DLNA、不看任何第三方脸色。**

- 在 `janus_pandora2/v2src/` 里加一个 `LocalPlayer.java`：
  `MediaPlayer` 直接播 NAS 的 http 流（我们已有 `TtsPlayer` 的 AudioTrack 经验）
- `PlayerBridge.java` 改成：优先本地播，DLNA 作为可选回退
- 只保留我们需要的能力：播 http mp3/aac 直播流 + 音量 + 暂停/续播
- 放弃：DLNA 投屏、AirPlay、原厂消息总线（我们本来就不用）

- ✅ **完全掌控、可审计、无第三方代码**
- ✅ 少一次跨进程 + 一层 DLNA，链路更短更好排障
- ✅ 已有 R8 打包工具链，直接进现有 build 流程
- ⚠️ 要自己处理音频焦点 / 流缓冲 / 断流重连
- ⚠️ 失去 DLNA 投屏与 AirPlay（当前没在用）

### 三步验证（路线 C 开工前必做）

```bash
# 1) MediaPlayer 能不能播我们的 NAS 流？—— 写个 20 行 demo apk 先试
#    mp3 曲库流 + AAC 电台直播流 各测一遍
# 2) 音量/焦点：STREAM_MUSIC + AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
# 3) 断流重连：setOnErrorListener + 重连退避
```

---

## 8. 建议

**先花半天跑路线 C 的可行性验证（MediaPlayer 播 NAS 流）**。
能播 ⇒ 路线 C 直接开工，player 包爱装啥装啥，彻底解耦。
不能播（AAC 直播流 MediaPlayer 顶不住）⇒ 退回路线 B，打包 ExoPlayer 官方版自己挂。

**当前缓解措施**（不管走哪条）：
- 别访问 8080
- 路由器屏蔽 `r1.wxfsq.com` + `112.13.210.66`
- `diag/pl_mod.apk` 已备份，随时可回滚

---

## 9. 目录 / 脚本

| 文件 | 说明 |
|---|---|
| `diag/pl_factory.apk` | 设备 `/system/app` 原厂 EchoService（1,160,423 B） |
| `diag/pl_mod.apk` | 设备已装的魔改 player（6,868,330 B）**回滚用** |
| `diag/pl_mod_payload.dex` | 魔改载荷（作者代码 5,706,812 B） |
| `diag/pl_mod_shell.apk` | 魔改外壳 = 原厂 v1.8.82（1,161,518 B）**路线 A/B 底包** |
| `diag/_player_cmp.py` | 文件级 + 逐条目对比 |
| `diag/_player_cmp2.py` | 解压后同类特征扫描（DLNA / AirPlay / 外联） |
| `diag/_player_cls.py` | 类名分组、独有类统计 |
| `diag/_injected.py` | 注入原厂包名的类 + ExoPlayer 版本判定 |

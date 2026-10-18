# Unisound_v1.6.14.apk 逆向报告（含与 Pandora 旧版对比）

> 分析日期：2026-10-14　｜　目标：`一键安装Unisound_v1.6.14/apps/Unisound_v1.6.14.apk`
> 对照基线：`diag/pandora/Unisound.apk`（设备上拉回的原厂 `com.phicomm.speaker.device`）
> 参照：旧报告 `diag/pandora/VERDICT-pandora-disguise.md`（2026-10-04）

---

## 1. 一句话结论

**这就是民间传说中的 `new_Unisound`，而且是 Janus（CVE-2017-13156）包的实物证据。**

它的 ZIP 部分与设备上原厂 `Unisound.apk` **134 个条目逐字节 md5 全等**（含签名 `CERT.RSA`），
只在文件头前置了一个 **5.17 MB 的魔改 DEX**。作者自报家门 **「小飞」**，与之前逆向的
`new_EchoService1.8.82.apk` 是同一人、同一套 `com.xiaofei` 框架。

---

## 2. 文件与结构

| 项 | v1.6.14 | 原厂（Pandora，对照） |
|---|---|---|
| 大小 | 30,361,671 B | 24,936,387 B |
| SHA-256（前 32） | `7e76ee8c8528c7d4088a7420205f8092` | `a0548242414666d27df9b099e85ab696` |
| **文件头** | **`dex\n035\0` ← Janus** | `PK\x03\x04`（正常 ZIP） |
| ZIP 条目数 | 134 | 134 |
| 解压后合计 | 40.4 MB | 40.4 MB |

```
[ dex\n035 …… 载荷 DEX 5,425,284 B（5.17 MB）…… ][ 原厂 APK 的 ZIP 24,936,387 B ]
   ↑ ART 从这里加载（小飞的代码）                    ↑ PackageManager 校验这里（原厂平台签名）
```

载荷 DEX SHA-256（前 32）：`c4c709c90205862dac351ef0ac4bdd43`

### ★ 2.1 关键实证：外壳一个字节都没改

逐条目 md5 对比结果：

| 对比项 | 数量 |
|---|---|
| 仅 v1.6.14 有 | **0** |
| 仅原厂有 | **0** |
| 同名但内容不同 | **0** |
| **完全相同** | **134（全部）** |

`META-INF/CERT.RSA`、`.SF`、`MANIFEST.MF` 全部一致 ⇒ **原厂平台签名被完整保留**，
这正是 Janus 的核心：v1(JAR) 签名只覆盖 ZIP 条目内容、不覆盖文件头偏移，
所以前置 DEX 后签名校验照过，安装的仍是「平台签名 + `platform_app` 域 + 系统身份」。

> 注意：外壳 ZIP 与原厂文件的**字节流**不相等（False）——因为 Janus 构造时把所有条目偏移
> 向前推了 DEX 长度。这是正确做法，不是差异。

---

## 3. 载荷：小飞的魔改代码

载荷共 **3042 个类**，相对原厂 dex 的 6220 个引用类，**独有 274 个**。

### 3.1 作者身份（硬证据）

`com.xiaofei.server` 里内嵌的默认页面 HTML 直接署名：

```html
<h1>Unisound_v1.6.14 by 小飞</h1>
<a href="https://www.right.com.cn/forum/thread-6445368-1-1.html">更新地址：…</a>
```

- 作者：**小飞**（`com.xiaofei` = xiaofei）
- 发布页：恩山无线论坛 **thread-6445368**（即旧报告里提到的「免拆复活小讯」系列）
- 与 `new_EchoService1.8.82.apk` 同源：两边都出现 `com.xiaofei.server` / `HttpdServer`

### 3.2 载荷独有类的构成

| 包 | 数量 | 是什么 |
|---|---|---|
| `org/xbill/DNS` | **175** | **dnsjava 全套**（DNS 协议库） |
| `fi/iki/elonen` | **28** | **NanoHTTPD**（嵌入式 HTTP 服务器） |
| `com/unisound/vui` | 12 | 注入原厂包名下的钩子/音乐接口 |
| `java/security/*`、`javax/net/ssl` | 17 | 证书/SSL 相关 |
| `com/xiaofei/server`、`StackTraceUtil` | 2 | 作者自己的类 |
| `cn/kuwo/base` | 2 | 酷我 |
| `defpackage.jnamed`（+3 内部类） | 4 | **dnsjava 的 DNS 服务器** |

### 3.3 本地 HTTP 服务：**端口 8086**

```java
public class server {
    HttpdServer httpd = new HttpdServer(8086);      // ← 监听 8086
    public void start() { this.httpd.start(); }

    public class HttpdServer extends NanoHTTPD {
        private MusicApiHandler MusicApi = new MusicApiHandler();

        public Response serve(IHTTPSession session) {
            String uri = session.getUri().substring(1);
            // 默认：返回署名页面（by 小飞 + 更新地址）
            if ("play".equals(uri)) {
                String id = session.getParms().get("id");
                String[] r = MusicApi.newGetPlayUrl(id, 0);   // 取播放地址
                ...
            }
        }
    }
}
```

对应载荷里的字符串 `http://127.0.0.1:8086/play`。
⇒ **局域网内可以直接 `http://<音箱IP>:8086/play?id=<歌曲ID>` 让它播指定歌。**

> ⚠️ 修正一条旧备忘：之前记的「8080 = 后门端口」里，**8086 才是 `new_Unisound` 的本地控制端口**，
> 而 8080/8089/18080 是**私服侧的端口**（见 §4）。两者别混。

### 3.4 酷我音乐接口（注入原厂包名下）

`com.unisound.vui.handler.session.music.MusicApiHandler`（注意：**伪装在原厂包名下**）：

- 字段 `kw_token`、`kw_Hm_Iuvt`（酷我的 `Hm_Iuvt` cookie）、`api`、`get_api_time`
- 方法 `newGetPlayUrl(id, source)`、`GetAuditionPlayUrl(id)`（试听）、`GetPlayUrl1(id)`、`Bang_List(...)`
- 用 OkHttp（连接 5s / 读写 30s / 不跟随重定向）
- 甚至自带 `main()`：*"请输入酷我歌曲id："* —— 作者留了 PC 端调试入口

⇒ **绕过酷我的鉴权直接取播放地址**，这是这个 mod 的核心卖点之一。

### 3.5 dnsjava / DNS 服务器（最值得警惕）

打包了完整 dnsjava（175 类），并包含 **`jnamed`（一个真正能跑的 DNS 服务器）**，
它读 zone 文件、维护 `znames` / `caches` / `TSIGs`，支持区传送（`ZoneTransferException`）。
另有 `dig`、`lookup` 等查询工具（被混淆进 `defpackage` 包）。

**用途推测**（未在代码中找到启动点，需运行时确认）：把原厂域名解析劫持到私服 IP，
使 `aios-home.hivoice.cn` 等指向作者的服务器，从而接管语音/音乐请求。

---

## 4. 硬编码地址

| 地址 | 归属 |
|---|---|
| `http://10.200.19.108:8080/app_wx_adapt_service/m/uploadAppLogFile` | **私服**：上传日志 |
| `…/uploadClientInfo/getPersonalOptions`、`setPersonalOptions` | **私服**：读写个人配置 |
| `http://10.30.2.13:8089/data-process-service/rtc` | **私服**：数据处理/rtc |
| `http://10.30.2.231:8080/app_wx_adapt_service/m/getCustomerConfig` | **私服**：下发客户配置 |
| `http://106.38.55.5:18080`、`http://106.38.55.5:19999/`、`/tr/dataProcess` | 公网服务器 |
| `http://127.0.0.1:8086/play` | 本机控制接口 |
| `aios-home.hivoice.cn:19999`、`asr-wakeup.hivoice.cn:8080/uc/m/upload` | 原厂云知声 |
| `dc.hivoice.cn/rest/v2/device/activate`、`/token/refresh` | 原厂激活/令牌 |
| `loc.map.baidu.com/*`、`itsdata.map.baidu.com` | 百度定位 |
| `antiserver.kuwo.cn/anti.s` | 酷我 |
| `android.bugly.qq.com`、`rqd.uu.qq.com` | 腾讯 Bugly |

### ★ 4.1 勘误（2026-10-14 复核）：这些不是「作者的私服」

初版判断前四条为「作者私服」，**错了**。后续溯源取证（`diag/_who_owns_ip.py`）证明：
这些地址在**原厂 `classes.dex` 里就已经存在**，且命中次数与小飞载荷**完全一致**：

| 地址 | 原厂 dex | 小飞载荷 | 判定 |
|---|---|---|---|
| `10.200.19.108` | 3 | 3 | 原厂自带，小飞纯继承 |
| `10.30.2.13` | 1 | 1 | 同上 |
| `10.30.2.231` | 1 | 1 | 同上 |
| `106.38.55.5` | 3 | 3 | 同上 |
| `211.102.192.39` | 2 | 2 | 同上 |

原厂 dex 里的上下文显示，它们是与主域名**并列的一串兜底地址**：

```
http://aios-home.hivoice.cn:19999/          ← 主域名（云知声）
http://10.200.19.108:8080/app_wx_adapt_service/m/uploadAppLogFile
http://10.200.19.108:8080/.../uploadClientInfo/getPersonalOptions
http://10.200.19.108:8080/.../uploadClientInfo/setPersonalOptions
http://10.30.2.13:8089/data-process-service/rtc
http://10.30.2.231:8080/.../getCustomerConfig
http://106.38.55.5:18080   /  :19999/tr/dataProcess
```

⇒ 这是**云知声开发期留下的内网测试服务器 + 公网兜底**，不是作者自建。
作者自己的私服是 `10.30.4.12`（只见于 `new_EchoService1.8.82`），本包里没有。

---

## 5. 与旧版 Pandora 报告的对比：新增了什么

| 维度 | 旧报告（2026-10-04，基于推测+设备取证） | 本次（2026-10-14，实物逆向） |
|---|---|---|
| 民间是否用 Janus | 由恩山帖**推测** + 设备上有 2 个 Janus 包 | ✅ **拿到实物**：v1.6.14 文件头 `dex\n035` |
| 外壳是否被动过 | 未知 | ✅ **134 条目 md5 全等**，签名完整保留 |
| 作者 | 恩山帖提到 `jojo729/R1_DEVICE` | ✅ 页面自报 **「小飞」**，`com.xiaofei` 命名空间 |
| 发布渠道 | thread-8472542 / 4073677 | ✅ 页面内嵌 **thread-6445368** |
| 本地控制端口 | 提到 EchoService 的 8080 | ✅ **`new_Unisound` 是 8086**（NanoHTTPD） |
| 音乐源 | "劫持语义 → 自建服务器二次解析" | ✅ 具体为**酷我 API 注入**原厂包名下 |
| DNS | 未提及 | ⚠️ **新增发现**：完整 dnsjava + jnamed DNS 服务器 |
| 私服地址 | 未提及 | ✅ 4 个 10.x 内网私服 + 公网 106.38.55.5 |
| 与 EchoService 关系 | 分别讨论 | ✅ **同一作者同一框架**（都含 `com.xiaofei.server`） |

---

## 6. 对本项目（jp2 / r1-music）的参考意义

1. **手法完全一致，目标不同** —— 小飞是「原厂 dex 保留 + 前置补丁拓展」，
   我们是「整体替换成小智客户端」。两者都用 Janus 顶替 `com.phicomm.speaker.device`，
   因此**互斥**：装了它就顶掉 jp2，反之亦然。
2. **端口不冲突** —— 小飞 8086、我们控制台 8088、NAS 音乐 8010，可共存（但只能二选一装包）。
3. **酷我 API 可借鉴** —— 若想让自定义曲库接在线源，`MusicApiHandler` 的
   `newGetPlayUrl` / `GetAuditionPlayUrl` 三级降级策略（正式→重试→试听→备用）是可参考的容错写法。
4. **⚠️ DNS 劫持风险** —— 如果设备装了这个包，域名解析可能被本地 DNS 服务接管。
   我们的服务都走 IP 直连（`192.168.2.189:8010`、ws 走 IP），**不受 DNS 影响**，这点没问题。
5. **"复活小讯"的代价** —— 原厂 USC/KWS 得以保留的代价，是把日志、配置、音乐请求
   全部交给作者私服。我们走本地小智服务端，反而没有这条外联链路。

---

## 7. 复现步骤

```bash
# 1) 看文件头判定 Janus
head -c 8 Unisound_v1.6.14.apk | od -c        # d e x \n ⇒ 中

# 2) 逐条目对比外壳与原厂（证明零改动）
python 04-分析脚本/_uni_diff.py

# 3) 拆包 + 载荷类统计 + 私服地址
python 04-分析脚本/_uni_split.py

# 4) jadx 深挖（推荐，比 smali 直观）
jadx MCP: load_apk(path=原始 APK)          # jadx 会自动走文件头那份 = 载荷
          → search_classes_by_keyword("xiaofei" / "elonen" / "jnamed")
          → get_class_source("com.xiaofei.server")
          → get_class_source("com.unisound.vui.handler.session.music.MusicApiHandler")
```

> jadx 加载整个 APK 时读的是**文件头的载荷**（这正是我们想要的）。
> 要看原厂代码，请改加载 `03-对照基线-原厂Unisound/Unisound.apk`。
> 脚本输出目录写死在 `R1/diag/` 下，重跑时产物落在那里。

---

## 8. 目录说明

```
Unisound_v1.6.14逆向报告/
├── 报告.md                                 本文档
├── 01-原始文件/Unisound_v1.6.14.apk         目标（30.4 MB，Janus 包）
├── 02-拆分产物/
│   ├── payload.dex                          载荷（小飞的代码，5.17 MB）
│   └── shell.apk                            外壳（= 原厂 APK，偏移已修正）
├── 03-对照基线-原厂Unisound/Unisound.apk     设备上拉回的原厂包（24.9 MB）
└── 04-分析脚本/
    ├── _uni_diff.py      逐条目 md5 对比（证明外壳零改动）
    ├── _uni_split.py     拆包 + 载荷类统计 + 私服地址提取
    ├── _uni_jadx.py      jadx 取 xiaofei / NanoHTTPD / jnamed
    └── _uni_jadx2.py     jadx 取 MusicApiHandler / jnamed 源码
```

---

## 9. 待确认

1. **`jnamed`（DNS 服务器）是否在运行时被真正启动** —— 若启动，等于本地 DNS 被接管，
   需要在设备上 `netstat` / `ps` 确认（装了这个包的前提下）。
2. **`MusicApiHandler.api` 字段的实际后端域名** —— 源码里是运行时赋值的，
   需追 `get_api_time` 那段刷新逻辑，才能确定它先问哪个地址拿 API。
3. **`106.38.55.5` 是谁的服务器** —— 公网 IP，:18080 / :19999 双端口，性质待查。
4. **注入到 `com.unisound.vui.*` 下的另外 12 个类** —— 具体劫持了哪些语义分支。
5. **与 `new_EchoService1.8.82` 的联动** —— 两个包都含 `com.xiaofei.server`，
   是否共享同一套私服账号体系（日志上报地址不同，可能分属不同服务）。

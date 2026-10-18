# 溯源取证：我们的 jp2 包会不会连小飞的服务器？

> 2026-10-14　｜　起因：逆向完 `Unisound_v1.6.14.apk` 后，怀疑我们的包是基于魔改包改的
> 方法：静态逐字节比对 + 设备运行时取证（`/proc/net/tcp` + `pm path` + 端口探测）

---

## 0. 一句话结论

**我们的包是干净的，但设备上"另一个包"确实是小飞的魔改包 —— 直觉没错，只是对象搞混了一个。**

| 包名 | 装机大小 | 真实身份 | 会连小飞吗 |
|---|---|---|---|
| `com.phicomm.speaker.device` | 31,811,995 B | **我们的 jp2-V2.0.1** | ❌ **不会**（代码 0 命中） |
| `com.phicomm.speaker.player` | **6,868,330 B** | **小飞 new_EchoService1.8.82** | ✅ **会**（远程 JS + 私服音源） |

`6,868,330 = 1,161,518（原厂 player）+ 5,706,812（小飞载荷 dex）` —— **字节数完全吻合**。

---

## 1. 为什么"感觉是基于魔改包改的"？（错觉的来源）

我们的底包是 `diag/pandora/Unisound.apk`（设备上 pull 回来的原厂包）。
把它跟小飞 v1.6.14 的外壳逐条目比对：

| 对比 | 结果 |
|---|---|
| 底包条目 / 外壳条目 | 134 / 134 |
| 仅底包有 | **0** |
| 仅外壳有 | **0** |
| 内容不同 | **0** |
| **完全相同** | **134（全部）** |

⇒ **两者都是同一个原厂文件。** 小飞的外壳 = 原厂 ZIP，我们的底包 = 原厂 ZIP。
**同源（都基于原厂），不是派生。** Janus 包的外壳必然是原厂，这正是这套手法的本质。

关键的区分点在 **DEX**：

| | 大小 | md5 |
|---|---|---|
| 底包 ZIP 内的 `classes.dex`（原厂） | 6,067,068 B | `b50a18c8…` |
| 小飞文件头载荷 `payload.dex`（魔改） | 5,425,284 B | `bb491afb…` |

**两者完全不同。** 我们从前者 baksmali 出 `uni_smali` 再合并寄生层；
小飞的代码只在后者里，**从来没进过我们的链路**。

---

## 2. 我们的产物里有没有小飞的代码？（静态扫描）

对 `jp2-V2.0.1.apk` 载荷 dex（6,875,608 B）逐项扫描，以小飞载荷作阳性对照：

| 特征 | 我方载荷 | 我方外壳 | 小飞载荷（对照） |
|---|---|---|---|
| `com/xiaofei` | **0** | **0** | 3 |
| 署名「小飞」 | **0** | **0** | 2 |
| 恩山帖 right.com.cn | **0** | **0** | 4 |
| dnsjava (`org/xbill/DNS`) | **0** | **0** | **181** |
| jnamed（DNS 服务器） | **0** | **0** | 9 |
| NanoHTTPD (`fi/iki/elonen`) | **0** | **0** | 30 |
| 酷我 `kw_token` / `antiserver.kuwo` | **0** | **0** | 6 |

**全部 0 命中。** 小飞的魔改代码一行都没进我们的包。

我方载荷里出现的地址只有三类：原厂云知声（`*.hivoice.cn`，已死）、
我们的服务（`192.168.2.189:8010` / `xiaozhi.me` / `api.tenclass.net`）、
以及下面要说的原厂兜底地址。

---

## 3. 那 4 个 10.x 地址为什么我们包里也有？（重要勘误）

第一版扫描时它们在我方载荷里命中了 3/1/1/3 次，一度以为是"继承了小飞"。
追到原厂 dex 里一看才明白 —— **它们是原厂自带的兜底域名列表**：

| 地址 | 原厂 dex | 小飞载荷 | 我方载荷 | 判定 |
|---|---|---|---|---|
| `10.200.19.108` | 3 | 3 | 3 | 原厂自带 |
| `10.30.2.13` | 1 | 1 | 1 | 原厂自带 |
| `10.30.2.231` | 1 | 1 | 1 | 原厂自带 |
| `106.38.55.5` | 3 | 3 | 3 | 原厂自带 |
| `10.30.4.12`（**小飞真私服**） | **0** | **0** | **0** | 本包无 |

原厂 dex 里的上下文（连续字符串常量）：

```
http://aios-home.hivoice.cn:19999/                                   ← 主域名
http://10.200.19.108:8080/app_wx_adapt_service/m/uploadAppLogFile
http://10.200.19.108:8080/.../uploadClientInfo/getPersonalOptions
http://10.200.19.108:8080/.../uploadClientInfo/setPersonalOptions
http://10.30.2.13:8089/data-process-service/rtc
http://10.30.2.231:8080/.../getCustomerConfig
http://106.38.55.5:18080     /    :19999/tr/dataProcess
```

⇒ **云知声开发期留下的内网测试服务器 + 公网兜底**，跟主域名并列。
作者真正的私服是 `10.30.4.12`（只在 `new_EchoService1.8.82` 里），
**Unisound_v1.6.14 和我们的包里都没有**。

---

## 4. 设备运行时取证（最硬的证据）

```bash
adb shell "cat /proc/net/tcp; cat /proc/net/tcp6" > _net_tcp.txt
```

解析结果：

```
★ 命中可疑地址（10.x / 106.38.55.5 / 211.102.192.39）：0 条
```

实际对外连接只有两条，全部是我们的：

| 本地 | 远端 | 说明 |
|---|---|---|
| `192.168.2.5:39047` | `115.11.155.139:8000` | 小智 WebSocket |
| `192.168.2.5:53179` | `94.16.60.123:80` | HTTP |

监听端口：`8088`（我们的控制台，uid 10008）、`8080`、`5012`（uid 1000 = system）。

---

## 5. ⚠️ 真正的隐患：`com.phicomm.speaker.player`

```
/data/app/com.phicomm.speaker.player-1/base.apk   6,868,330 B   2026-10-12 21:04
/data/app/com.phicomm.speaker.device-1/base.apk  31,811,995 B   2026-10-14 00:22
```

**player 就是小飞的 `new_EchoService1.8.82`**，跑在 **system uid**，4 个进程常驻：

```
system  21114  com.phicomm.speaker.player
system  21142  com.phicomm.speaker.player:NetPlayer
system  21167  com.phicomm.speaker.player:DlnaPlayer
system  21190  com.phicomm.speaker.player:AirPlayer
```

### 5.1 8080 是什么

```bash
curl http://192.168.2.5:8080/
```

```html
<script>
  var ver = 1882;                                        <!-- ← 1.8.82 -->
  script.src = 'http://r1.wxfsq.com/new_r1_control.js?t=' + new Date().getTime();
</script>
```

- 本地页面是**空壳**，真正的逻辑来自 **`r1.wxfsq.com`**（解析到 `112.13.210.66`，走 `eo.dnse4.com` CDN）
- 拉取的 JS 里还有作者 QQ 头像：`https://q1.qlogo.cn/g?b=qq&nk=203017966&s=100`
- ⇒ **作者可以随时改那个 JS，所有装了这个包的设备下次打开控制页就执行新代码**
- 这就是旧备忘里"8080 = 第三方 RCE 后门"的真相（措辞不准，但风险是真实的）

### 5.2 私服音源

`http://10.30.4.12:8080/data-cloud-service/rest/v1/music/get_song_by_id`
—— 取歌曲播放地址的接口。当前 netstat 显示 **没有**连它，
因为我们的音乐走 `PlayerBridge` 总线 / NAS `192.168.2.189:8010`，没走它的音乐 API。
但**敞口存在**：一旦某条路径触发它的取链逻辑，请求就会打到作者服务器。

### 5.3 端口对照（别再混）

| 端口 | 归属 | 说明 |
|---|---|---|
| **8080** | 小飞 player（system uid） | 远程 JS 控制台，**别用** |
| 8086 | v1.6.14 的 NanoHTTPD | 本机**没装** v1.6.14，无监听 |
| 8088 | 我们的控制台 | 局域网，无鉴权 |
| 5012 | system uid 某服务 | 原厂 |

---

## 6. 附带发现：我们的 device 是 `u0_a8`，不是 `system`

```
u0_a8     5460   com.phicomm.speaker.device          ← 我们的包
u0_a8     5556   com.phicomm.speaker.device:remote
system   21114   com.phicomm.speaker.player
```

预期是 `sharedUserId=android.uid.system` + 平台签名 ⇒ 应该显示 `system`，
实测却是普通应用 uid `u0_a8`。**功能全部正常**（四麦 HAL / AEC / KWS 都工作），
所以不影响使用，但跟"Janus 顶替拿 platform 身份"的初衷有出入，记一笔待查。

---

## 7. 建议（按优先级）

1. **不要访问 8080** —— 那是个远程代码加载的控制台，开着它等于给作者留了执行入口。
2. **如果要彻底清干净**：把 player 换回原厂包。⚠️ 但要先确认我们的音乐链路
   （`PlayerBridge` 总线 / DLNA）是否依赖魔改 player 的行为 —— 换之前先在测试机上验证。
3. **折中方案**：在路由器上屏蔽 `r1.wxfsq.com` 与 `10.30.4.12`，
   保留 player 的播放能力，掐断它的外联。
4. 我们自己的包**不需要动** —— 静态扫描 + 运行时抓包双重确认干净。

---

## 8. 复现脚本

| 脚本 | 作用 |
|---|---|
| `diag/_trace_base.py` | 底包溯源：文件 md5 / 逐条目比对 / 产物结构 / 小飞特征扫描 |
| `diag/_who_owns_ip.py` | 私服 IP 归属：原厂 vs 小飞 vs 我方 三向计数 + 上下文 |
| `diag/_net_who.py` | 解析 `/proc/net/tcp`，高亮可疑远端 |
| `diag/_my_payload.dex` / `_my_shell.apk` | 我方产物拆分件 |
| `diag/_r1_control.js` | 从 `r1.wxfsq.com` 拉回的远程控制脚本（静态样本，勿执行） |

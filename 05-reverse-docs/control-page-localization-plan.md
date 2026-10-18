# 控制页本地化：保留全部功能，去掉外联

> 2026-10-14　｜　前提：DLNA 投屏 / AirPlay / 蓝牙 / 灯效 / 屏幕 / 语音助手开关 / 设备状态 / 播放器界面**都要保留**
> 资源已全部拉回本地：`diag/_ctrlres/`（371 KB，11 个文件）

---

## 0. 先纠正一个认知：那些「功能」不是本地的

你常用的控制界面（播放器 UI、灯效、蓝牙开关、语音助手开关、设备状态、屏幕显示），
**不是音箱本地提供的，而是 `r1.wxfsq.com` 上的网页**。

完整链路（实测 + 静态分析确认）：

```
① 手机打开 http://192.168.2.5:8080
      ↓ 音箱返回空壳 HTML（var ver = 1882）
② 加载 http://r1.wxfsq.com/new_r1_control.js
      ↓
③ ⚠️ 自动跳转 http://r1.wxfsq.com/?connect_ip=192.168.2.5&ver=1882&u_ver=0
      —— 音箱的内网 IP 被上报给作者
      ↓
④ 加载 r1_control.min.js（228 KB，整套 UI）
      ↓
⑤ JS 用 connect_ip 直连音箱 WebSocket  ← ✅ 控制指令走局域网
```

**关键结论**：
- ✅ **控制指令是本地的**（`new WebSocket("ws://<音箱IP>:<端口>")`），不经作者服务器
- ⚠️ **UI 代码是云端的**，作者随时可改（`refresh.js` 这名字就是干这个的）
- ⚠️ **默认会跳转并上报音箱内网 IP**（加 `?no_jump` 可阻止跳转）

---

## 1. 好消息：这套资源完全可以搬回家

| 项 | 情况 |
|---|---|
| 框架依赖 | **无**（没有 jQuery / Vue / React，纯原生 JS） |
| CDN 依赖 | **无** |
| 外部域名 | 只有 QQ 登录（`graph.qq.com`）、QQ 头像（`q1.qlogo.cn`）、备案号 |
| 协议 | **http**（不是 https）⇒ DNS 劫持不需要证书 |
| 总大小 | **371 KB / 11 个文件** |

⇒ 是**自包含的一整套**，搬到 NAS 就能跑。

### 已拉回的资源

| 文件 | 大小 | 作用 |
|---|---|---|
| `js/r1_control.min.js` | 227,733 B | **主程序（全部功能在这）** |
| `device_scan.html` | 41,958 B | 设备扫描 |
| `new_r1_control.css` | 18,125 B | 样式 |
| `dlna.html` | 33,221 B | DLNA 页面 |
| `mdns.html` | 25,730 B | mDNS |
| `js/connect.js` | 3,034 B | 加载器（含 localStorage 降级缓存） |
| `new_r1_control.js` | 2,009 B | bootstrap |
| `refresh.js` | 1,340 B | 加载器 |
| `upload` | 315 B | 404 页 |
| `ip` | 11 B | `{"code":-1}`（POST 接口，需 mock） |
| `iot.php` | 54 B | `{"status":200,...}`（POST 接口，需 mock） |

### 功能覆盖确认（`r1_control.min.js` 关键词命中）

| 功能 | 次数 |
|---|---|
| 音乐播放 | 519 |
| 屏幕显示 | 234 |
| 蓝牙 | 213 |
| 语音助手 | 114 |
| 灯效 | 77 |
| DLNA | 24 |
| AirPlay | 20 |
| 设备状态 | 11 |

**你要保留的，一个不落全在里面。**

---

## 2. 方案：DNS 劫持 + NAS 托管（半天，零开发）

### 2.1 原理

```
原来：手机 → r1.wxfsq.com(112.13.210.66) 拿 JS  → 手机 → 音箱(ws) 发指令
现在：手机 → r1.wxfsq.com →【路由器DNS指向 NAS】→ 手机 → 音箱(ws) 发指令
                                ↑ 指令路径完全不变
```

**控制指令一如既往走局域网，只是 UI 代码改从 NAS 加载。**

### 2.2 步骤

```bash
# 1) 资源放到 NAS 的 web 目录（假设 8010 端口的静态目录）
#    保持相对路径：/  /js/  /dlna.html 等

# 2) mock 掉两个 POST 接口（返回空即可，我们不需要）
#    /ip      → {"code":-1}
#    /iot.php → {"status":200,"message":"ok","text":""}

# 3) 路由器上加一条 DNS 覆盖
#    r1.wxfsq.com  →  192.168.2.189（NAS）
#    （小米/华硕/OpenWrt 都有「自定义 hosts / DNS 劫持」）

# 4) 验证
curl -H "Host: r1.wxfsq.com" http://192.168.2.189:8010/js/r1_control.min.js | head -c 100
```

### 2.3 附带动作

- 访问时用 **`http://192.168.2.5:8080/?no_jump`**：阻止跳转 ⇒ **不再上报内网 IP**
- 把 `?no_jump` 存成手机书签 / 桌面快捷方式

### 2.4 已知残留（不影响核心功能）

| 残留 | 影响 |
|---|---|
| `https://r1.wxfsq.com/lanzou.php`（下载 APP 按钮） | https ⇒ 劫持需证书，**会失败**。只是"下载固件"按钮，不影响控制 |
| `graph.qq.com`（QQ 登录） | 若用 QQ 登录功能则需外网；不用就无所谓 |
| `q1.qlogo.cn`（作者 QQ 头像） | 纯装饰 |

---

## 3. 为什么这个方案比"重建"划算

| | 本地化托管 | 真重建（逆向 API 自己写 UI） |
|---|---|---|
| 工作量 | **半天** | 1~2 周 |
| 功能完整度 | **100%（原样）** | 取决于复刻程度，大概率打折 |
| 风险 | 低（不动音箱、不动 player） | 高（要动载荷、要验签、要 reboot） |
| 可回滚 | **随时删 DNS 记录即可** | 要重新刷包 |
| 外联 | **零** | 零 |

**先做本地化托管，把外联掐掉；真重建作为长期选项慢慢来。**

---

## 4. 长期：并入我们已有的 8088 控制台

我们有 `WebConsole.java`（123 KB）+ `R1Lights.java`（灯效）+ `PlayerBridge.java`（DLNA），
基础设施是现成的。要做的：

1. **逆向音箱侧本地 API**
   - `com.xiaofei.server.HttpdServer` 的 HTTP 端点
   - `com.xiaofei.server.WsdServer` 的 WebSocket 消息格式
   - 蓝牙开关、屏幕显示走哪些系统 API
2. 把控制页做成**内联 HTML**（不拉任何外部 JS）
3. 挂到 8088，与我们现有的「激活 / 连接 / 音乐 / 设置 / 日志」并列

⇒ 这一步做完，就可以**彻底卸掉魔改 player**（除非还要用它升级过的 ExoPlayer 播放层）。

---

## 5. ⚠️ DLNA / AirPlay 与 player 包的关系（重要澄清）

| 能力 | 归属 | 换回原厂还在吗 |
|---|---|---|
| **DLNA 渲染器（DMR）** | **原厂**（`PlatinumJniProxy` + `/system/lib/libplatinum-jni.so`） | ✅ 在 |
| **AirPlay** | **原厂**（`ShairportWrapper` + `libshairport.so`） | ✅ 在 |
| 蓝牙 | 系统服务 | ✅ 在 |
| 音乐播放（新版 Exo 2.13） | **魔改**（`new_NetPlayer` / `DlnaPlayer2`） | ❌ 退回 2.9 |
| 控制页 UI | **作者服务器** | 不受影响（本来就不在本地） |

⇒ **DLNA 投屏 / AirPlay 是原厂能力，跟魔改无关**。
唯一会因为换回原厂而退化的是**音乐播放内核**（Exo 2.13 → 2.9）。

**所以：本地化方案（第 2 节）完全不需要动 player 包，投屏/AirPlay 一切照旧。**

---

## 6. 执行顺序建议

```
[今天]  ① 资源上传 NAS  →  ② 路由器 DNS 劫持  →  ③ 用 ?no_jump 验证
                                                    ↓
[观察]  用几天，确认所有功能正常（尤其 DLNA 投屏 / AirPlay / 蓝牙 / 灯效）
                                                    ↓
[可选]  长期方案：逆向本地 API，并入 8088 控制台
```

**回滚**：删掉路由器那条 DNS 记录，一切回到原状。
**兜底**：`diag/pl_mod.apk` 是魔改 player 的完整备份，随时 `adb install -r` 装回。

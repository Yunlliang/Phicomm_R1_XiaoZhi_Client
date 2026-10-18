# 现代化控制台 UI（暗色玻璃拟态）

寄生层 8088 控制台的新页面。**移动优先**，四个底部 Tab：播放 / 音乐 / 日志 / 设置。

## 文件

| 文件 | 作用 | 要不要手改 |
|---|---|---|
| `ui.html` | **唯一真源**。HTML + CSS + JS 全在里面，零框架零依赖 | ✅ 改这个 |
| `gen_java.py` | 把 ui.html 转成可编译的 Java 常量 | ❌ |
| `_preview.html` | 强制演示模式的副本，双击就能看 UI（不用装机） | ❌ 生成物 |
| `../v2src/cn/workbuddy/r1client/UiModern.java` | 生成物，被 WebConsole 引用 | ❌ 生成物 |

## 改 UI 的流程

```bash
cd R1/janus_pandora2/ui
# 1. 改 ui.html
# 2. 重新生成（会做 JS 语法检查 + 分块自检 + 产出预览副本）
python gen_java.py
# 3. 照常跑 build（build_v1XX.sh 用 find v2src -name "*.java" 自动收集，新文件不用登记）
```

`gen_java.py` 会拦住三类错误：**JS 语法错**、**分块切坏转义序列**、**块超 60KB**。
它还会产出 `_preview.html`，双击就能看效果，不必先装机。

## 路由（WebConsole.java）

| 路径 | 内容 |
|---|---|
| `/` | 新 UI（`UiModern.html()`） |
| `/old` | 旧页面（`page()`）—— 字段原样铺开，排障时更方便 |
| `/api/*` | 不变 |

新页面设置 Tab 底部有「旧版排障页」入口。

## 数据源：双源 + 自动降级

```
① 自有   fetch /api/*        设置 / 状态 / 日志 / 控制      ← 永远可用
② 音箱   ws://<ip>:8080      播放进度 / 播放队列            ← 魔改 player 提供
```

**为什么必须两个**：播放进度和播放队列只存在于 player 进程里，device 包跨进程拿不到。
我们自有的曲库索引（1185 首）在 device 侧，但「当前播放器队列」只有 player 知道。

- ② 连不上时自动降级到 ①：歌名用 `music.curTitle`，控制仍全部可用，只是**没有进度条和队列**
- 控制**始终走 ①**（`/api/act`），不依赖 player 的 WS
- 顶栏 `WS 8080`（绿）/ `WS 离线`（灰）指示当前用哪个源

### 两个协议事实（写代码前必须知道）

1. **player 的 WS 零自发推送** —— 连上后不发请求，被动监听 40 秒收到 0 条。
   所以页面必须轮询：`get_info` 2 秒一次、`list` 10 秒一次（只在对应 Tab 可见时）。
2. **协议里没有 `seek`**（`seek` 在控制页 JS 里 0 命中）⇒ **进度条只读，不能拖**。

## 设置 Tab：音频与行为（合并后的取舍）

音频区**只保留三个真正会改变行为的开关**：上行增益 / 上行 ALC / 丢弃唤醒词音频。
原来还摆着的两个开关**已经从 UI 移除**（后端 `/api/set` 仍接受，留给 adb 手工 touch 做 A/B）：

| 移除的开关 | 为什么不摆出来 |
|---|---|
| AEC 回声消除（`aec`） | 这是 **Android 软件** `AcousticEchoCanceler`，不是原厂四麦的硬件 AEC。v1.3.9 实测：开着底噪没了、**人声也没了**（说话段 rms 仅 23~41，服务器 25s 无 TTS）——因为没有和播放流共用 audio session，AEC 把近端人声当回声消掉。默认关，打开只会变坏。 |
| 搭车原厂拾音（`uscTap`） | 默认开。关掉 ⇒ `tapEnabled=false` ⇒ 退化成 `AudioRecord` 直采（VOICE_COMMUNICATION），拿不到原厂波束+AGC+AEC 处理过的 PCM，收音明显变差。 |

两个 flag 的实际值不隐藏，改成卡片底部一行**只读回显**：
`采集链路：原厂四麦 tap（波束 + AGC + 硬件 AEC） · 软件 AEC 关`
—— 一旦有人手工 touch 了 flag 让链路偏离正常值，这一行会标黄并写出后果，不会静默。

## 点一下即播（播放 Tab，v1.3.152）

**播放队列**：点第 i 行 → 音箱 WS 发 `{"type":"play","index":i}`。

| 事项 | 说明 |
|---|---|
| 单击 vs 双击 | 小飞版是**双击**才播（单击弹「歌曲信息」窗）。我们没有那个弹窗，改成**单击即播**。 |
| 实测 | 回包 `{"msg":"ok","type":"play","code":200}`，`get_info` 的曲名真的切了、`playIndex` 也跟着变；播回原 index 正常。 |
| 依赖 | 队列只存在于 player 进程，**WS 一断就不能按索引播**（容器/进度也是同理）；此时点行会 toast 提示「未连接」，控制按钮仍可用。 |
| 重复点 | 点当前正在播的那行直接忽略（`i === PIDX`）。 |

**搜索曲库**：原来 `op=search` 返回的是一整段中文文本（「OK 找到 N 首：1.xxx、2.yyy…」），那是给 MCP / 模型读的，前端没法渲染成列表。
新增结构化接口 **`GET /api/search?k=歌名/歌手`** → `{"total":N,"list":[{"index","title","artist","url"}…]}`（上限 60 条，`index` 是 1-based，与 `play_index` 编号一致）。
点结果走 **`op=play_url`** 播那条 `url`；曲库条目没 url 时退化成按歌名播（`PlayerBridge.playUrl` 本来就有这条兜底）。

⚠ 走 `play_url` 会把 player 的队列换成「就这一首」—— 这是原厂 `playSpecifiedMusic` 的语义，不是 bug。

## 电台列表（音乐 Tab，v1.3.156）

原来是横排 `chips`，**48 个台铺开会占掉两三屏**。改成和「搜索曲库 / 播放队列」同一套 `.li` 列表，
装进带滚动条的框里：

| 项 | 做法 |
|---|---|
| 可视区 | 固定 **5 行**：`.scroll.r5{max-height:230px}` + `.li.rad{height:46px}`（5×46 正好，不露半行） |
| 行内容 | `.n` 序号 + `.a` 台名 + `.b` 「网络直播」／选中时「正在收听」 |
| 点一下 | `playRadioAt(i)` → `playRadio(name)` → `act("radio", name=…)`（**按台名播，不是按 url**） |
| 当前台 | 点了立刻标红（取流要等 1~2 秒，先给反馈）；语音换台时若 `music_info.title` 等于台名也会跟上 |
| 过滤框 | 框上面一个 input，`oninput` 边打边筛（**纯本地字符串匹配，不走网络**）+ 「清空」按钮 |

过滤细节：

- 序号**保留在完整名单里的位置**，不随过滤重排（台序固定，序号就是它的标识）
- 过滤时 hint 变「匹配 6 / 48 个台」；清空后回到「48 个台 · 点一下收听（可上下滑）」
- **`radioHint` 只能由 `renderRadios()` 写**。写在 `load()` 里会被 8 秒一次的状态刷新冲掉
  （而 `renderRadios()` 因签名没变会跳过，hint 就停在错误文案上）

### ★ 为什么名单没变就不重建 DOM

`load()` 每 8 秒跑一次，直接 `innerHTML = …` 会把用户滑到第 20 个台的滚动位置弹回顶部
（chips 时代没有这个问题，是列表化之后新引入的）。所以 `renderRadios()` 先算一份
「台名签名 + 当前台」的 `radioSig`，和上次一样就直接 return。
副作用：改 `radios.json` 后要等签名变化才会重画 —— 台名变了就会变，符合预期。

## 音量（播放 Tab，v1.3.155 修过量纲）

**player 的音量只有一个入口能改：WS `{"type":"set_vol","vol":N}`，量纲是 `0..15` 档位**（实测：发 `vol:8` → `get_info` 的 `vol` 变 8；还原成 0 也跟手）。

以前滑块走的是 `/api/set?vol=` → `PlayerBridge.dispatch("volume")` → `setSystemVolume(lv)`，那个 **level 是 0~100 百分比**（`setSystemVolume` 里 `target = lv*max/100`）。两套量纲撞在一起的后果：

| 现象 | 成因 |
|---|---|
| 拖完滑块弹回 0 | 拖到 1~3 档 ⇒ `setSystemVolume(1)` ⇒ `1*15/100 = 0` ⇒ 系统音乐流被设成 **0**；回读 `readSystemVolume()=0` ⇒ UI 又被覆盖成 0 |
| 怎么拖都不响 | player 播的不是我们改的那条流，改了跟没改一样 |

现在：

- **WS 在线** ⇒ 一律 `set_vol`（档位），发完立刻 `get_info`（跟小飞版一致）
- **WS 离线** ⇒ 退回自有链路，但先把档位换算成百分比（`pct = val*100/max`），并 toast 说明「只改了系统音乐流，player 音量没变」
- **防回弹 `applyVol()`**：`vol < 0`（读取失败）不回填；**刚拖过 2.5s 内不回填** —— 否则取整误差/异步读回会把滑块拽回去
- 滑块 `max` 用 `get_info` 的 `max_vol`；`get_info` 没给就按 15

## 重启后那段「还没就绪」的时间（v1.3.155）

整机重启后 **player（8080）比我们的 8088 起得晚**，这段时间 WS 连不上，进度和队列本来就拿不到（这是客观事实，不是 bug）。能做的是别让它看起来像「没在播」：

- 切到播放 / 音乐页时**立刻重试一次 WS** 并补 `get_info`+`list`（原来只靠 5s 轮询）
- 队列区未连时不显示「队列为空」，而是「正在等音箱播放服务（8080）… 已等 Ns」
- 「正在播放」降级用自有 `music.curTitle/playing`，并注明在等服务
- 自有链路的 `volume`（0~100 百分比）回填时会换算成档位

## 安装到桌面（PWA，v1.3.154）

让用户把控制台当 App 装到手机桌面（独立窗口、无地址栏、有自己的图标）。

**服务端给的三件套**（都在 `WebConsole.route()`）：

| 路径 | 内容 |
|---|---|
| `/manifest.webmanifest` | name/short_name/start_url/scope/`display:standalone`/theme_color + 三张图标 |
| `/sw.js` | Service Worker，带 fetch handler（带 `Service-Worker-Allowed: /`） |
| `/icon-192.png` `/icon-512.png` `/icon-maskable-512.png` | **运行时用 `Canvas` 画的**，不往 dex 里塞 PNG 常量；`maskable` 那张背景铺满、字缩到中心（自适应图标只保留中心 80%） |

### ★ 硬门槛：安全上下文

Android Chrome 给不给「安装应用」，三条必须同时成立：manifest 合格 + 注册过带 fetch handler 的 SW + **安全上下文（HTTPS 或 localhost）**。前两条我们都能给，**第三条是局域网 http 的死穴**（`http://192.168.2.5:8088` 不算）。

绕法（安卓 Chrome，一次设置长期有效）：
1. 地址栏进 `chrome://flags/#unsafely-treat-insecure-origin-as-secure`
2. 填入 `http://192.168.2.5:8088` → Enabled → Relaunch
3. 回到控制台，浏览器菜单里就有「安装应用」了

iOS Safari 不受此限：分享 → 添加到主屏幕（靠 `apple-mobile-web-app-capable` + `apple-touch-icon`，不需要 SW）。

设置 Tab 的「安装到桌面」卡片会自动判断（`window.isSecureContext`、`display-mode: standalone`、`beforeinstallprompt`）并显示对应文案，非安全上下文时给出上面的步骤和一个「复制地址」按钮。

### SW 为什么只做「网络优先」

控制台是长驻 SPA，之前已经因为「用户手上那份是旧 HTML」踩过一次（新按钮点了没反应）。所以 SW **只对 HTML 做网络优先 + 断网兜底，`/api/*` 一律放行走网络** —— 装成 App 后页面仍然每次拿最新的，那条「页面已过时」的黄条照旧管用。

### standalone 下的安全区

`body` 加了 `padding-top:env(safe-area-inset-top)`，顶栏/更新条同理，避免 iOS 刘海盖住内容（普通浏览器里 `env()` 为 0，视觉无变化）。

## 氛围灯（设置 Tab）

小飞版控制页里那套「灯效设置」，我们用自己的通道重实现了 —— 不经过 8080。

命令全部走原厂总线 `messageCenter.sendMessage(what=4, arg1, arg2)`，
与拾音灯的 `what=0x1000` 不是一条。语义是从魔改 player 的
`r1_control.min.js` 命令表里扒出来，并在本机用 `get_info` 的
`music_light_*` 字段**逐条自证过**（发完字段真的会变，也能复原）：

| 功能 | arg1 | arg2 |
|---|---|---|
| 总开关 | 64 | 1 开 / 0 关 |
| 亮度 | 65 | 1..200（默认 100） |
| 颜色渐变速度 | 66 | 0..100（默认 4） |
| 效果 | 68 | 0 官方 / 1 七彩旋转 / 2 七彩 / 3 七彩旋转1 / 4 单色 |
| 单色颜色 | 70 | 0xRRGGBB |

⚠ **状态回显是本地记录**（`files/ambient.txt`），不是真实状态：
真实值在设备控制服务进程里，device 侧读不到。所以「从没在本页设过」的项显示未知，
用小飞版改过的话这里也不会同步。

## 重启音箱（设置 Tab → 设备）

按钮会依次试五条路，第一条 `exit=0` 就收工，并把每条的 exit/out/err 用 alert 弹出来：

1. `/system/bin/adb reboot` ← 正路
2. `adb connect 127.0.0.1:5555; adb -s …:5555 reboot`
3. `adb shell reboot`
4. `/system/bin/reboot`（v1.3.109 失败过，留着看报错）
5. `setprop sys.powerctl reboot`（同上）

**为什么这次能行**：v1.3.109 只试了 4、5 两条，都栽在权限上 —— 我们的包实测跑在
**u0_a8**（不是 system uid），`android.permission.REBOOT` 没有、`/system/bin/reboot`
没有 setuid、setprop 写系统属性被拒。小飞版用的是 `adb reboot`：它压根没打算自己有权限，
而是把重启交给 **adbd** 去做。本机三条前提恰好都成立：

- `/system/bin/adb` 存在（root:shell 755，others 可执行）
- `ro.adb.secure=0` → adbd 免认证，设备上自己连自己不会被踢
- `service.adb.tcp.port=5555` → adb 客户端自动走 TCP 回环

## 取证用 shell（默认关）

`/api/act?op=shell&v=id` 会以本 app（u0_a8）的身份执行命令并回显 stdout/stderr，
用来确认「我们到底是谁、哪条命令有没有权限」。
默认关闭，要在 `files/shell_api` 建一个空文件才启用，**排障完记得删**。

## 已知限制

| 项 | 说明 |
|---|---|
| 进度条不能拖 | 协议无 seek |
| 歌词 / 封面 | 要作者服务器（`r1.wxfsq.com`），本地化后拿不到，新 UI 直接不做 |
| 队列依赖 player | 换回原厂 player 后队列会空，降级逻辑已就位 |
| `u_ver=1000` | 我们的 jp2 不是小飞的 `new_Unisound`，所以 QQ 音乐源/收藏/报时那些门控功能本来就没有 |

## 演示模式

真机上访问 `http://192.168.2.5:8088/?demo=1` 会用假数据渲染（不碰真实接口、不会改设置），
用来确认 UI 本身没问题。`_preview.html` 就是强制这个模式的本地副本。

## 踩过的坑（别再踩）

**分块切坏了转义序列。** Java class 常量池里单个字符串 UTF-8 ≤ 65535 字节，
所以大页面必须切块。但按"字符"切会把 `\"` 切成两半 —— 上一块末尾留个裸反斜杠，
javac 报「未结束的字符串文字」+「非法字符: '\'」。
`split_by_bytes()` 现在把「反斜杠 + 后一个字符」当不可分割单元处理。

另外：HTML 转义顺序不能乱 —— 先 `\`→`\\`，再 `"`→`\"`，最后换行→`\n`。
反过来会把第二步生成的反斜杠再转义一遍。

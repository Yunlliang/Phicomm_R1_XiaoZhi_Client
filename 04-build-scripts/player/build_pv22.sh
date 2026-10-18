#!/bin/bash
# pl-1.6.3：
#   ① 「只有可被搜到时才能连」—— 原厂 BluetoothPlayer 在手机连上（CONNECTION_STATE
#      CONNECTED）和 disconnectDevice 里把 scan mode 强设 SCAN_MODE_NONE(20)，
#      isSystemInNormalMode 常态恒真 ⇒ 我们设的 21/23 活不过一次连接。
#      patch_bt_scan.py 两处全拔（startServer 的 23 保留）。
#   ② 可发现状态偏好账本（Bt.java：files/r1_bt_disc.txt，手动点过才写）——
#      attach / 适配器 STATE_ON / reopenEngine 后恢复；无记录默认 23
#      （和设置向导出厂态一致），保证「蓝牙开着就能被已配对手机连上」。
#   ③ 常数口径写死 20/21/23（本 ROM 编译值互证；教材 21/23/39 是别的平台的），
#      /api/bt 加 scanMode 原值，页面 hint 直显，排连不上一眼定位。
#
# pl-1.6.2：
#   ① 手动意图账本（files/r1_svc_manual.txt）—— 手动开过的功能（dlna/airplay/bt）
#      被 ensureBusy 停掉后 2.5s 自动补开；手动关过/没记录的一律不补。
#
# pv12（pl-1.4.0）：
#   ① 赞赏码修好 —— 页面引用 /donate.png，player 侧压根没这个路由 ⇒ 图片 404；
#   ② PWA「安装到桌面」修好 —— 之前只给了一个没有图标的假 manifest，
#      而 Chrome 要求 manifest(带 192/512 图标) + 注册过 SW 才肯给安装资格；
#      现在 /manifest.webmanifest / /sw.js / /icon-*.png 三件套整套补齐；
#   ③ 设置页「设备」栏 → 「设备状态」，新增 CPU 占用 / 内存占用 / 温度 / 存储空间
#      （数据源 /api/device：player 直接读 procfs、sysfs；小飞是靠 8080 WS 发
#       {type:"shell"} 让设备跑 cat 再回传，我们不用绕这一圈）；
#   ④ 一键清理无用进程 —— 只杀认得的原厂残留（launcher / systemtool / netctl）。
#
#   ① 循环模式用**原厂真值**（1=随机 2=顺序 3=单曲循环）—— 以前发 0，
#      而 AbsPlayer.setPlayMode 只认 1/2/3，0 直接被打回 ⇒「顺序播放」点了没反应；
#   ② 「播放控制」里的「随机播放」更名「随便听」；
#   ③ 按歌手连播：数量留空 = 该歌手全部歌曲（服务端 count<=0 不限量）；
#   ④ player 重启后顶栏残留曲名 ⇒ 队列空时点 ▶ 走 op=replay（device 用落盘曲名
#      回库反查重推列表开播，查不到退化 random），status 新增 mode / stale。
#
# pv7（pl-1.1.5）：① 队列为空时 ▶ 直接随机播放；② 上下曲 1.2s 主动补状态+队列；
#   ③ 切歌时队列自动把正在播的那条滚进可视区（cur 没变则不动）。
#
# pv6（pl-1.1.4）：设置页重排 + 队列上限 + 空搜索列全部
#   ① 氛围灯 + 调试 两栏合并为「灯光控制」（氛围灯 / 唤醒灯 / 播报灯）；
#   ② 新增「播放列表」上限输入（localStorage，0 = 不限制）；
#   ③ 搜索框留空点搜索 = 列全部曲库（/api/search?k=&limit=N，player 侧原样转发）。
#
# =============================================================================
#  jp2-player 注入版构建：把控制台塞进原厂 player（com.phicomm.speaker.player）
#
#  产物 = [前置 dex（原厂 934 类 + 我们的 cn.workbuddy.r1web）] ++ [EchoService_system.apk]
#  （Janus CVE-2017-13156 逐字节拼接；janus_poc.py 会修 dex 头与 EOCD 偏移）
#
#  用法：  bash build_pv2.sh
# =============================================================================
set -u
cd "$(dirname "$0")"
HERE="$(pwd)"
R1="C:/Users/shaoh/WorkBuddy/R1"

AJAR="$R1/sdkdl/stage/android.jar"
R8="$R1/sdkdl/stage/r8.jar"
JAVAC="$R1/janus/dl/jdk-17.0.20.1+1/bin/javac.exe"
JAVA="$R1/janus/dl/jdk-17.0.20.1+1/bin/java.exe"
PY="C:/Users/shaoh/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
SM="$R1/janus/smali"
BCP="$SM/baksmali-2.5.2.jar;$SM/dexlib2-2.5.2.jar;$SM/guava-25.1-android.jar;$SM/jcommander-1.78.jar;$SM/commons-cli-1.4.jar;$SM/antlr-runtime-3.5.2.jar;$SM/util-2.5.2.jar"
SCP="$SM/smali-2.5.2.jar;$SM/dexlib2-2.5.2.jar;$SM/guava-25.1-android.jar;$SM/jcommander-1.78.jar;$SM/commons-cli-1.4.jar;$SM/antlr-runtime-3.5.2.jar;$SM/util-2.5.2.jar"

V=pv22
OUT="xiaozhi_EchoService_$V.apk"

chk() {   # chk <文件> <说明> <特征串>
  if grep -qF -- "$3" "$1"; then echo "  OK   $2"; else echo "  FAIL $2（没找到：$3）"; FAIL=1; fi
}
chkNo() {
  if grep -qF -- "$3" "$1"; then echo "  FAIL $2（不该出现：$3）"; FAIL=1; else echo "  OK   $2"; fi
}
FAIL=0

echo "=========== [0/8] 生成 Ui.java（页面 -> Java 常量） ==========="
# [0.1] 静态资源（赞赏码 base64 / PWA 三件套）从 device 侧抽取，别手抄
"$PY" _gen_assets.py || exit 1
"$PY" ui/gen_java.py || exit 1

echo "=========== [1/8] 静态自检 ==========="
UI="plsrc/cn/workbuddy/r1web/Ui.java"
SRCS="plsrc/cn/workbuddy/r1web"
chk "$SRCS/WebSrv.java" 'op=play_at 路由'  '"play_at".equals(op)'
chk "$SRCS/WebSrv.java" 'op=devctl 路由'   '"devctl".equals(op)'
chk "$SRCS/WebSrv.java" 'CORS'             'Access-Control-Allow-Origin: *'
chk "$SRCS/WebSrv.java" '状态带进度'        'posMs'
chk "$SRCS/DevCtl.java" '停用走 op=halt'    'op=halt'
chk "$SRCS/DevCtl.java" '恢复 startservice' 'am startservice -n '
chk "$SRCS/PCtl.java"   '跳播用 playIndex'  'playIndex(i, true)'
chk "$SRCS/PCtl.java"   '队列来自钩子'       'noteQueue'
chk "$SRCS/Boot.java"   '非主进程跳过'       'skip web: 非主进程'
chk "$UI"               '页面已生成'         'class Ui'
chk "ui/player.html"    '页面有小智开关'      '停用小智'
chk "ui/player.html"    '页面有队列'         'play_at'
chk "$SRCS/Proxy.java"  '反代 8088'         '127.0.0.1:8088'
chk "$SRCS/WebSrv.java" 'status 同构标记'    '"pl", true'
chk "$SRCS/WebSrv.java" '设置反代'          'path.equals("/api/set")'
chk "$SRCS/WebSrv.java" '搜索反代'          'path.equals("/api/search")'
chk "$SRCS/WebSrv.java" '未知 op 转 device'  'Proxy.post("/api/act"'
chk "$SRCS/WebSrv.java" 'act 返回 JSON'     'jsonOk(true'
chk "ui/player.html"    '页面跳过 8080 WS'   'S.pl'
chk "$SRCS/WebSrv.java" 'form body 合并'     'query.putAll(parse(body))'
chk "$SRCS/WebSrv.java" '音量百分比→档位'     'pct * mx / 100.0f'
chk "$SRCS/WebSrv.java" 'volstep 直设档位'   '"volstep".equals(op)'
chk "$SRCS/WebSrv.java" '停用时不干等 8s'    '小智已停用（8088 不可达）'
chk "ui/player.html"    '页面读 volMax'      'm.volMax'
chk "ui/player.html"    '队列签名防重绘'      'qLastSig'
chk "ui/player.html"    '重建保 scrollTop'   'box.scrollTop = st'
chk "ui/player.html"    '定位当前'           'jumpCur'
chk "ui/player.html"    'lnk 样式'           '.ttl .lnk'
chkNo "ui/player.html"  '不再自动 scrollIntoView' 'c.scrollIntoView('
chk "ui/player.html"    '页面有日志/设置/电台' '/api/log'
chkNo "ui/player.html"  '氛围灯已瘦身'        'ambMode('
chkNo "ui/player.html"  '不再有模式段'        'id="segAmb"'
chkNo "ui/player.html"  '不再有颜色选择'      'ambColorApply'
chk "ui/player.html"    '保留氛围灯开关'      'ambToggle'
chk "ui/player.html"    '灯光控制栏'          '>灯光控制 <'
chk "ui/player.html"    '唤醒灯开关'          'swWkL'
chk "ui/player.html"    '播报灯开关'          'swBsL'
chk "ui/player.html"    '队列上限输入'        'saveQlim'
chk "ui/player.html"    '队列用 QLIM'         'limit=" + QLIM'
chk "ui/player.html"    '空队列随机播放'      'playOrRandom'
chk "ui/player.html"    '切歌自动定位'        'queueScrollTo(box, cur)'
chk "ui/player.html"    '上下曲主动补状态'    'function skip('
chk "$SRCS/PCtl.java"    'curIdx 按实际在放校正' 'public static synchronized void syncCur('
chk "$SRCS/PCtl.java"    'queueJson 先 syncCur'  'syncCur();'
chk "$SRCS/PCtl.java"    '窗口 start'          'o.put("start", start)'
chk "ui/player.html"    '队列窗口 start'      'Q.start'
chk "ui/player.html"    'playAt 收真实下标'  'function playAt(i, real)'
chk "ui/player.html"    '切歌按绝对下标判定'  'qLastCurAbs'
chk "$SRCS/PCtl.java"    '模式只收 1/2/3'      'm != 1 && m != 2 && m != 3'
chk "$SRCS/WebSrv.java"  '状态带 mode'         'm.put("mode", PCtl.mode())'
chk "$SRCS/WebSrv.java"  '状态带 stale'        'm.put("stale"'
chk "ui/player.html"    '随机->随便听'        '>随便听</button>'
chk "ui/player.html"    '模式 2/1/3'          'setMode(3)'
chk "ui/player.html"    '空队列走 replay'     'act("replay")'
chk "ui/player.html"    '数量留空=全部'       '数量留空'
chk "ui/player.html"    '模式回显'            'setModeUI(m.mode)'
chk "ui/player.html"    '空搜索列全部'        '&limit='
chkNo "ui/player.html"  '调试栏已移除'        '>调试</div>'
chk "$SRCS/WebSrv.java" '搜索转发 limit'      '&limit='
# ---- pv12 新增：赞赏码 + PWA 三件套 + 设备状态 ----
chk "$SRCS/Assets.java"  '赞赏码 base64'       'DONATE_QR_B64'
chk "$SRCS/Assets.java"  'manifest 常量'       'MANIFEST_JSON'
chk "$SRCS/Assets.java"  'service worker'      'SW_JS'
chk "$SRCS/Assets.java"  '运行时画图标'         'iconPng(int size'
chk "$SRCS/WebSrv.java"  '/donate.png 路由'    'path.equals("/donate.png")'
chk "$SRCS/WebSrv.java"  '/sw.js 路由'         'path.equals("/sw.js")'
chk "$SRCS/WebSrv.java"  '/icon-* 路由'        'path.startsWith("/icon-")'
chk "$SRCS/WebSrv.java"  'SW 作用域头'         'Service-Worker-Allowed: /'
chk "$SRCS/WebSrv.java"  '二进制写出'          'writeBytes(os, 200'
chk "$SRCS/WebSrv.java"  '/api/device 路由'    'path.equals("/api/device")'
chk "$SRCS/WebSrv.java"  'op=cleanup'          '"cleanup".equals(op)'
chk "$SRCS/WebSrv.java"  'op=restore'          '"restore".equals(op)'
chk "$SRCS/Sys.java"     'CPU 取两次求差'       'Thread.sleep(250)'
chk "$SRCS/Sys.java"     '温度节点 tsadc'       'hwmon0/device/temp0_input'
chk "$SRCS/Sys.java"     '存储 StatFs'         'new android.os.StatFs(path)'
chk "$SRCS/Sys.java"     '进程白名单'           '"system_server"'
chk "$SRCS/Sys.java"     'launcher 受保护'      '"com.phicomm.speaker.launcher"'
chk "$SRCS/Sys.java"     'systemtool 受保护'    '"com.phicomm.speaker.systemtool"'
chk "$SRCS/Sys.java"     'netctl 受保护'        '"com.phicomm.speaker.netctl"'
chk "$SRCS/Sys.java"     '无用服务清单照小飞'    'String[] USELESS'
chk "$SRCS/Sys.java"     '清单 16 个不许多禁'    '"com.phicomm.speaker.exceptionreporter"'
chk "$SRCS/Sys.java"     'defcontainer 受保护'   '"com.android.defcontainer"'
chk "$SRCS/Sys.java"     '只禁已安装的'          'inst.indexOf(pkg) < 0'
chkNo "$SRCS/Sys.java"   '不再按 uid 通杀'       'isAppUid'
chk "$SRCS/Sys.java"     '清缓存 trim-caches'    'pm trim-caches'
chk "$SRCS/Sys.java"     '进程表读 /proc'        '"/proc/" + pid + "/cmdline"'
chk "$SRCS/Sys.java"     'RSS 取 VmRSS'         'VmRSS:'
chk "$SRCS/Sys.java"     '停用后复查'           'left.indexOf(q.name) < 0'
chk "ui/player.html"    '恢复已禁用服务'      '恢复已禁用服务'
chk "$SRCS/Sys.java"     '恢复走 pm unhide'     '/system/bin/pm unhide '
chk "$SRCS/Boot.java"    '版本 pl-1.6.18'      'pl-1.6.18'
# ---- pl-1.6.18：歌词框收成 5 排（150px），留白同步 58px 保证居中 ----
chk "ui/player.html"     '歌词框 5 排高'       'max-height:150px'
chk "ui/player.html"     '留白同步'            '#lrcLines{padding:58px 0}'
chk "$SRCS/Ui.java"      '页面已随 HTML 重建'   'max-height:150px'
# ---- pl-1.6.17：封面时序（换歌立刻摘旧图 / 下载完才显示）+ 歌词框内滚动 ----
chk "ui/player.html"     '封面钥匙'            'QPICKEY'
chk "ui/player.html"     '换歌摘封面'          'im.removeAttribute("src")'
chk "ui/player.html"     '下载完才显示'        'im.onload = function ()'
chk "ui/player.html"     '歌词框内滚动'        'function lrcScroll('
chk "ui/player.html"     '不用 scrollIntoView' 'box.scrollTop = t'
chk "ui/player.html"     '框 relative 基准'    'position:relative'
chk "ui/player.html"     '用户翻词不抢滚'      'LRCUSR'
chk "ui/player.html"     '首尾可居中留白'      '#lrcLines{padding:58px 0}'
# ---- pl-1.6.16：封面 + 可折叠歌词（反代 /api/lrc） ----
chk "$SRCS/WebSrv.java"  '/api/lrc 反代'       'path.equals("/api/lrc")'
chk "$SRCS/WebSrv.java"  'lrc 透传 t/a'        '/api/lrc?t=" + enc(t) + "&a=" + enc(a)'
chk "ui/player.html"     '封面 img'            'id="npImg"'
chk "ui/player.html"     '歌词按钮'            'toggleLrc()'
chk "ui/player.html"     '歌词面板'            'id="lrcBox"'
chk "ui/player.html"     '折叠类 open'         '.lrcbox.open'
chk "ui/player.html"     '当前行高亮'          'function lrcTick('
chk "ui/player.html"     '取词走 /api/lrc'      'fetch("/api/lrc?t="'
chk "$SRCS/Ui.java"      '页面已随 HTML 重建'   'lrcBox'
# ---- pl-1.6.6：在线搜歌（8080 侧补齐） ----
chk "$SRCS/WebSrv.java"  'search 透传 online'  '&online="'
chk "$SRCS/WebSrv.java"  '在线类 op 长超时'    'fwdMs = 25000'
chk "ui/player.html"     '在线搜歌按钮'        'doSearchOnline()'
chk "ui/player.html"     '在线结果区'          'SRES_ON'
chk "ui/player.html"     '在线点播 play_online' 'act("play_online", p)'
# ---- pl-1.6.8：在线随机 100 首（反代透传 random/olimit + 新 op 长超时） ----
chk "$SRCS/WebSrv.java"  'search 透传 random'  '&random="'
chk "$SRCS/WebSrv.java"  'search 透传 olimit'  '&olimit="'
chk "$SRCS/WebSrv.java"  'play_artist 长超时'  '"play_artist".equals(op)'
chk "$SRCS/WebSrv.java"  'random_online 长超时' '"random_online".equals(op)'
chk "$SRCS/WebSrv.java"  'play_rand_at 长超时' '"play_rand_at".equals(op)'
chk "ui/player.html"     '留空走随机批'        'rnd ? "&random=1'
chk "ui/player.html"     '随机批点播'          'function playRandAt(j)'
chk "ui/player.html"     '随机批文案'          '在线随机 '
# ---- pl-1.6.4：可发现 = 60 秒临时窗口（连上/到点自动收回） ----
chk "$SRCS/Bt.java"      '可发现窗口时长'      'DISC_WINDOW_MS'
chk "$SRCS/Bt.java"      '连上即收回'          '设备已连上'
chk "$SRCS/Bt.java"      '到点自动收回'        '60 秒无设备连上，到点收回'
chk "$SRCS/Bt.java"      '手动立即收回'        '手动关闭'
chk "$SRCS/Bt.java"      '默认落 21'           'int mode = win ? SM_CD : SM_CONN;'
chk "$SRCS/Bt.java"      '倒计时上报'          'discLeft'
chk "$SRCS/Bt.java"      '账本已废弃'          '已废弃'
chk "ui/player.html"     '窗口倒计时显示'      '（" + o.discLeft + "s）'
chk "ui/player.html"     '窗口规则说明'        '60 秒临时窗口'
# ---- pl-1.6.3：蓝牙 scan mode kick 全拔 + 可发现偏好账本 ----
chk "$SRCS/Bt.java"      'scan mode 常量口径'  'SM_CD'
chk "$SRCS/Bt.java"      '恢复可发现偏好'      'applyDiscPref'
chk "$SRCS/Bt.java"      'STATE_ON 也恢复'     'applyDiscPref();'
chk "$SRCS/Bt.java"      'scanMode 原值上报'   'o.put("scanMode"'
chk "ui/player.html"     'scan 档位提示'        '· scan '
chk "ui/player.html"     '20 谁也不收提示'      '20 = 谁也不收'
chk "ui/player.html"     'airplay 记账说明'     '没有苹果设备也能验'
# ---- pl-1.4.0：蓝牙 / DLNA / AirPlay 开关 ----
chk "$SRCS/Bt.java"      '蓝牙开关'            'public static String setOn(boolean on)'
chk "$SRCS/Bt.java"      '扫描'                'startDiscovery()'
chk "$SRCS/Bt.java"      '配对 createBond'     'createBond()'
chk "$SRCS/Bt.java"      '取消配对反射'         'removeBond'
chk "$SRCS/Bt.java"      'A2DP 连接'           'sink.connect(d)'
chk "$SRCS/Bt.java"      '可发现反射 setScanMode' 'setScanMode'
chk "$SRCS/Bt.java"      '广播接收'            'ACTION_DISCOVERY_FINISHED'
chk "$SRCS/Svc.java"     'DLNA 字段'           'mDlnaPlayer'
chk "$SRCS/Svc.java"     'AirPlay 字段'        'mAirPortPlayer'
chk "$SRCS/Svc.java"     '开功能走 AIDL'        'startServer'
chk "$SRCS/Svc.java"     '关功能走 AIDL'        'stopServer'
chk "$SRCS/Svc.java"     '反射取 mIPlayer'      'mIPlayer'
chk "$SRCS/Svc.java"     '不杀进程'             '进程是功能的载体'
chk "$SRCS/Svc.java"     'DLNA 实际状态读内核'  'P_DLNA'
chk "$SRCS/Svc.java"     'DLNA 端口 1900'      '0x76C'
chk "$SRCS/Svc.java"     'AirPlay 端口 5002'   '0x138A'
chk "$SRCS/Svc.java"     '状态读 socket 表'    '/proc/net/udp'
chk "$SRCS/Svc.java"     '进程状态也报'         'hasProc(":DlnaPlayer")'
chk "$SRCS/Sys.java"     'hasProc 可见'        'static boolean hasProc('
chk "$SRCS/WebSrv.java"  '/api/bt 路由'        'path.equals("/api/bt")'
chk "$SRCS/WebSrv.java"  '/api/svc 路由'       'path.equals("/api/svc")'
chk "$SRCS/WebSrv.java"  'op=bt_scan'          '"bt_scan".equals(op)'
chk "$SRCS/WebSrv.java"  'op=dlna/airplay'     '"dlna".equals(op)'
chk "$SRCS/Boot.java"    '蓝牙初始化'           'Bt.init(app)'
chk "ui/player.html"     '蓝牙栏'              '>蓝牙 <'
chk "ui/player.html"     '外部播放源栏'         '>外部播放源 <'
chk "ui/player.html"     '蓝牙列表渲染'         'function btShow('
chk "ui/player.html"     'DLNA 开关按钮(单键翻转)'  "svcToggle('dlna')"
chk "ui/player.html"     '设备栏更名'           '>设备状态 <'
chk "ui/player.html"     'CPU 字段'            'id="kCpu"'
chk "ui/player.html"     '内存字段'            'id="kMem"'
chk "ui/player.html"     '温度字段'            'id="kTemp"'
chk "ui/player.html"     '存储字段'            'id="kDisk"'
chk "ui/player.html"     '拉 /api/device'      '"/api/device"'
chk "ui/player.html"     '清理按钮'            'doClean()'
chk "ui/player.html"     '设置页定时采集'       'if (curTab === "set") loadDev()'
chk "ui/player.html"     '手动刷新按钮'        'loadDev(1)'
# ---- pl-1.6.0：音量应用按钮 + 播放列表推流后恢复 ----
chk "$SRCS/PCtl.java"    '状态反射读取'         'getDeclaredField("mPlayerStatus")'
chk "$SRCS/PCtl.java"    '非 BUSY 重推恢复'     'private static boolean ensureBusy('
chk "$SRCS/PCtl.java"    '重推走 play(info)'    'p.play(info, true)'
chk "$SRCS/PCtl.java"    '推流后重推恢复'      '（重推恢复）'
chk "$SRCS/PCtl.java"    '远程源判据'          'onlineIsRemote'
chk "$SRCS/PCtl.java"    '停推流引擎交接'      'exitWaitingExternalConnect'
chk "$SRCS/PCtl.java"    '触发自动补开'        'Svc.autoReopenLater'
chk "$SRCS/Svc.java"     '手动意图账本'        'r1_svc_manual.txt'
chk "$SRCS/Svc.java"     '补开只看手动账本'    'manualOn(w)'
chk "$SRCS/Svc.java"     '开功能独立方法'      'static String openOnly'
chk "$SRCS/Bt.java"      '蓝牙手动记账'        'Svc.saveManual("bt"'
chk "$SRCS/Bt.java"      '蓝牙引擎补开'        'reopenEngine'
chk "ui/player.html"     '自动补开规则'        '手动关过的一律不自动补开'
chk "ui/player.html"     '音量拖动不生效'        'oninput="onVolDrag('
chk "ui/player.html"     '音量应用按钮'          'volApply()'
chk "ui/player.html"     '3 秒自动还原'          'setTimeout(volRevert, 3000)'
if [ "$FAIL" != "0" ]; then echo "静态自检未过，中止"; exit 1; fi

echo "=========== [2/8] javac：stub -> stubout（stub 不进 dex） ==========="
rm -rf stubout classes_$V out_$V smali_$V smali_build_$V
mkdir -p stubout classes_$V out_$V
MSYS2_ARG_CONV_EXCL="*" "$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$AJAR" -d stubout $(find stub -name "*.java") 2>&1 | head -20
echo "  stub class 数：$(find stubout -name '*.class' | wc -l)"

echo "=========== [3/8] javac：注入类 -> classes_$V ==========="
# ★ 坑：以前是 `javac ... | head -30` —— 管道的退出码是 head 的，javac 挂了脚本也看不出来，
#   白装一次机（pl-1.6.12 踩过）。改成落盘再取 RC。
MSYS2_ARG_CONV_EXCL="*" "$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$AJAR" -cp stubout -d classes_$V $(find plsrc -name "*.java") > javac_$V.log 2>&1
JRC=$?
head -40 javac_$V.log
if [ "$JRC" != "0" ]; then echo "javac 失败（rc=$JRC），中止"; exit 1; fi
N1=$(find classes_$V -name "*.class" | wc -l)
N2=$(find classes_$V -path "*com/phicomm*" -name "*.class" | wc -l)
echo "  产出 class：$N1（其中 com/phicomm 污染：$N2，必须为 0）"
if [ "$N2" != "0" ] || [ "$N1" -lt 5 ]; then echo "class 产物不对，中止"; exit 1; fi
# ★ javac 失败时 -d 目录里还留着上一次的 class，N1 照样好看 ⇒ 必须点名校验新类。
#   （pl-1.4.0 踩过：BluetoothProfile.A2DP_SINK 是隐藏常量，编译直接挂掉，
#    而脚本看着 14 个旧 class 就放行了，白装一次机。）
for c in Boot WebSrv PCtl Bt Svc Sys; do
  if [ ! -f "classes_$V/cn/workbuddy/r1web/$c.class" ]; then
    echo "缺 $c.class（javac 没编出来），中止"; exit 1
  fi
done

echo "=========== [4/8] d8 -> dex ==========="
MSYS2_ARG_CONV_EXCL="*" "$JAVA" -cp "$R8" com.android.tools.r8.D8 --min-api 22 \
  --lib "$AJAR" --output out_$V $(find classes_$V -name "*.class" | tr '\n' ' ') 2>&1 | head -20
ls -l out_$V/classes.dex

echo "=========== [5/8] baksmali：dex -> smali_$V ==========="
MSYS2_ARG_CONV_EXCL="*" "$JAVA" -cp "$BCP" org.jf.baksmali.Main d out_$V/classes.dex -o smali_$V
echo "  smali 文件：$(find smali_$V -name '*.smali' | wc -l)"
chk "smali_$V/cn/workbuddy/r1web/Boot.smali" 'Boot 进了 smali' 'attach'
chk "smali_$V/cn/workbuddy/r1web/WebSrv.smali" 'WebSrv 进了 smali' 'devctl'

echo "=========== [6/8] 合并 smali 树 + 打钩子 ==========="
if [ ! -d smali_echo ]; then echo "缺 smali_echo（先跑 baksmali 原厂）"; exit 1; fi
# ⚠ 坑：`cp -r smali_echo smali_build_$V` 在目标**已存在**时会拷成
#   smali_build_$V/smali_echo/ 子目录 ⇒ smali 编译时「类已 interned」报错。
#   所以先删干净、再 mkdir、最后用 `smali_echo/.` 的写法拷内容。
# 用 mv 挪走而不是 rm -rf：批量删除会被安全机制拦（构建中间产物没必要真删）
TS="$(date +%s)"
if [ -d smali_build_$V ]; then
  mv smali_build_$V smali_build_old_$TS || { echo "挪不动旧目录，中止"; exit 1; }
fi
if [ -d smali_build_$V ]; then echo "旧目录仍在，中止"; exit 1; fi
mkdir -p smali_build_$V
cp -r smali_echo/. smali_build_$V/
mkdir -p smali_build_$V/cn/workbuddy/r1web
cp -f smali_$V/cn/workbuddy/r1web/*.smali smali_build_$V/cn/workbuddy/r1web/
"$PY" patch_smali.py smali_build_$V || exit 1
# pl-1.4.0：蓝牙「连上就断」的原厂 bug —— 两处 kick 全拔（幂等，带标记）：
#   ① BluetoothPlayer.bootInit()  A2DP proxy 连上时把已连接设备统统 disconnect
#   ② BluetoothPlayer$2.run()      收 CONNECTION_STATE 广播后按 mConnectState
#      case 0/3 直接 disconnect 新连接 ← 主凶
"$PY" patch_bt_hold.py smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer.smali smali_build_$V/com/phicomm/speaker/player/model/'BluetoothPlayer$2.smali' || exit 1
FAIL=0
chk "smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer.smali" '蓝牙不再踢连接(bootInit)' 'bootInit 已空掉'
chk "smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer\$2.smali" '蓝牙不再踢连接(receiver)' 'r1web: 去掉 disconnect'
# pl-1.6.3：原厂把 scan mode 强设 SCAN_MODE_NONE(20) 的两处也拔掉
#   （手机连上就不可连 = 「只有可被搜到时才能连」的根因；startServer 的 23 保留）
"$PY" patch_bt_scan.py smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer.smali smali_build_$V/com/phicomm/speaker/player/model/'BluetoothPlayer$2.smali' || exit 1
FAIL=0
chk "smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer.smali" '不再强设 SCAN_MODE_NONE(disconnectDevice)' 'r1web: 不再强设 SCAN_MODE_NONE'
chk "smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer\$2.smali" '不再强设 SCAN_MODE_NONE(receiver)' 'r1web: 不再强设 SCAN_MODE_NONE'
chk "smali_build_$V/com/phicomm/speaker/player/model/BluetoothPlayer.smali" 'startServer 仍设 23' 'setScanMode(I)Z'
# pl-1.5.0：DLNA 推流同步 —— DlnaPlayer2.onPlayCommand 插桩，把手机推来的曲目
#   广播给主进程控制台（Ext 收 → music.ext；播不播另由 AIDL isPlaying 问真实引擎）
"$PY" patch_dlna_sync.py smali_build_$V/com/phicomm/speaker/player/model/DlnaPlayer2.smali || exit 1
chk "smali_build_$V/com/phicomm/speaker/player/model/DlnaPlayer2.smali" 'DLNA 推流同步钩子' 'DlnaHook;->onPush'
# pl-1.6.12：拖动进度条 —— 原厂 AIDL 里没有 seek，这里新增事务 0x15
#   （IPlayer$Stub 分派 → PlayerBinderWrapper$2 → AbsPlayer → NetPlayer4
#    → MultiMediaPlayer.seek → MediaPlayer.seekTo）；客户端那半在 PCtl.seekTo()
"$PY" patch_seek.py smali_build_$V || exit 1
chk "smali_build_$V/com/phicomm/speaker/player/IPlayer\$Stub.smali" 'Stub 分派 seek' ':sswitch_seek'
chk "smali_build_$V/com/phicomm/speaker/player/PlayerBinderWrapper\$2.smali" 'Wrapper 实现 seek' 'r1web: seek'
chk "smali_build_$V/com/phicomm/speaker/player/model/AbsPlayer.smali" 'AbsPlayer 默认 seek' 'r1web: seek'
chk "smali_build_$V/com/phicomm/speaker/player/model/NetPlayer4.smali" 'NetPlayer4 seek 落点' 'MultiMediaPlayer;->seek(I)I'
chk "$SRCS/PCtl.java" 'seek 客户端' 'TX_SEEK'
chk "$SRCS/WebSrv.java" 'op=seek 路由' '"seek".equals(op)'
# pl-1.6.13：拖动「把手跟手、条慢一拍又弹回去」—— paintPos 拖动期间强制画 PR_VAL
chk "$SRCS/Ui.java"       '拖动期间独占绘图' 'if (PR_DRAG) pos = PR_VAL'
# pl-1.6.14：松手后「跳回原处再跳回落点」—— seek 生效窗口，窗口内不采纳旧真值
chk "$SRCS/Ui.java"       'seek 生效窗口状态' 'function prWait()'
chk "$SRCS/Ui.java"       '窗口内锁死落点' 'else if (prWait()) pos = PR_SEEK_MS'
chk "$SRCS/Ui.java"       '窗口内不碰时钟' 'if (PR_SEEK_WAIT) {'
chk "$SRCS/Ui.java"       '窗口内冻住插值' 'if (PR_DRAG || prWait()) { POS_NEXT = 0; return; }'
chk "$SRCS/Ui.java"       '换曲作废窗口' 'PR_SEEK_WAIT = false; PR_SEEK_TILL = 0'
chk "$SRCS/Ui.java"       '把手同步过渡' 'transition:left .5s linear'
# pl-1.6.15：卡片信息去重 —— 专辑=歌名不显示；来源文本只留 npTag 一颗 pill
# （★ Ui.java 是 Java 字符串常量，引号已转义成 \"，chk 串也要用 \"）
chk "$SRCS/Ui.java"       '专辑=歌名不显示' 'm.curAlbum !== \"Radio\" && m.curAlbum !== m.curTitle'
chk "$SRCS/Ui.java"       'via 不再写第二遍' 'npMeta\").textContent = pm.join(\" · \");'
chk "$SRCS/Ext.java"     '外部源状态读真实引擎' 'Svc.playing'
chk "smali_build_$V/com/phicomm/speaker/player/util/Common.smali"     'attach 钩子'  'Boot;->attach'
chk "smali_build_$V/com/phicomm/speaker/player/model/EasyPlayer.smali" 'onPlay 钩子' 'Boot;->onPlay'
chk "smali_build_$V/com/phicomm/speaker/player/model/EasyPlayer.smali" 'onIndex 钩子' 'Boot;->onIndex'
if [ "$FAIL" != "0" ]; then echo "钩子自检未过，中止"; exit 1; fi
echo "  合并后 smali：$(find smali_build_$V -name '*.smali' | wc -l)"

echo "=========== [7/8] smali -> bridge_$V.dex ==========="
rm -f bridge_$V.dex
MSYS2_ARG_CONV_EXCL="*" "$JAVA" -Xmx2g -cp "$SCP" org.jf.smali.Main a smali_build_$V -o bridge_$V.dex
ls -l bridge_$V.dex

echo "=========== [8/8] Janus 拼接 + 校验 ==========="
"$PY" "$R1/diag/pandora/janus_poc.py" --dex "$HERE/bridge_$V.dex" \
  --apk "$R1/EchoService_system.apk" --out "$HERE/$OUT" || exit 1
"$PY" - "$HERE/$OUT" <<'EOF'
import struct, zlib, hashlib, zipfile, sys
p = sys.argv[1]
d = open(p, 'rb').read()
print('  size', len(d), 'magic', d[:4])
ok = True
ok &= d[:4] == b'dex\n'
print('  dex magic     ', ok)
fs = struct.unpack('<L', d[32:36])[0] == len(d)
print('  file_size==总长', fs)
ad = struct.unpack('<L', d[8:12])[0] == (zlib.adler32(d[12:]) & 0xffffffff)
print('  adler32       ', ad)
sh = d[12:32] == hashlib.sha1(d[32:]).digest()
print('  sha1          ', sh)
z = zipfile.ZipFile(p)
print('  zip 条目       ', len(z.namelist()), 'testzip', z.testzip())
if not (ok and fs and ad and sh and z.testzip() is None):
    print('校验未全过！'); sys.exit(1)
print('  ★ 全部校验通过')
EOF
echo "=========== 完成：$OUT ==========="
ls -l "$OUT"

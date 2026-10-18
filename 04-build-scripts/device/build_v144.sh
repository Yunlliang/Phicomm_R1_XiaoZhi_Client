#!/bin/bash
# jp2-v1.3.175：在线搜歌全套（HTTP 入口 + 在线搜索接口 + 实际歌曲信息）
#   ① 用户抱怨串：「搜到的还是只有池子里的歌」「语音点歌也只播池子里的」。
#      根因两层：web /api/search 只搜内存 tracks（100 首池）；语音链上 MCP 模型
#      习惯先调 search 试探，池内空就直接回复「没找到」，根本走不到 v1.3.174 的
#      playOnlineSong 兜底。
#   ② 落点：
#      - PlayerBridge：nasBase()/onlineSearchJson()/playOnlineItem(六参)；
#        tracks 扩 String[7]（[4]album [5]duration [6]pic）；
#        播放元数据经 notePlayingFull 落盘（curAlbum/curDuration/curVia，
#        K_ALBUM/K_DURATION/K_VIA），status/queueJson 全带出；
#        池内歌也上报（index.json 条目本就带 album）。
#      - WebConsole：op=play_song（HTTP 触发完整语音链）/ op=play_online /
#        op=search_online；/api/search?online=1 池内 0 中时附带在线结果。
#      - XiaoZhiWs：MCP 描述纠偏 —— play_song「点名直接调，别先 search 试探」、
#        search「只覆盖本地曲库池，搜不到≠没有」。
#      - ui/ui.html：「在线搜歌」按钮 + 在线结果区（点 ▶ 即播）+ npMeta 展示
#        专辑·时长·来源（翻唱/DJ 版显示真实信息）。
#   ③ 配套 player 侧 pl-1.6.5：/api/status 已整表透传 music 段，player.html
#      加 npMeta 同行展示。
#   根因：music index.json 是启动时拉的快照 —— 池子外的歌（新歌/没导入的），
#         play_song 走到 matchRadio 之后只剩一个 ERR。
#   改动：miss 链末尾插 playOnlineSong(q)：从 indexUrl 推导 NAS mini-lx base，
#         GET /api/search?q=&source=all 全源搜 → 取第一条 →
#         GET /api/url?rid=&source=&q=128k 拿直链 → append 进 tracks →
#         走与库内歌完全相同的 pushSongs/kickPlay（队列可见、能上下曲）。
#         全程 try/catch，任何一步失败返回 null ⇒ 维持原 ERR，零副作用。
#   依赖：files/music_index 指向的 mini-lx（NAS 8100）提供 /api/search 与 /api/url。
#
# jp2-v1.3.173：device 的 :8088 收口 ——
#   ① **只监听 127.0.0.1**：整页已搬到 player 的 :8080，8088 现在只剩一个用途
#      （给同机的 player 控制台做反向代理，Proxy.BASE 本来就是 127.0.0.1:8088）。
#      以前绑 0.0.0.0 ⇒ 局域网里谁都能开一个「第二控制台」（能改设置、看对话日志）。
#   ② **主端口不再供页面**：直接访问 8088 只回一句指引 + 3s 跳转到 :8080；
#      /api/* 全部照旧。镜像端口（files/mirror_port，默认关）仍绑 0.0.0.0 且给完整页面。
#   ③ 配套：NAS 侧 控制页本地化-NAS部署/www/js/xz_panel.js 的 iframe 已改指 8080。
#
# jp2-v1.3.173：
#   ① 循环模式真值修正 —— 原厂 AbsPlayer.setPlayMode **只认 1/2/3**
#      （1=列表随机 2=列表循环/顺序 3=单曲循环），以前页面发 0 会被打回 ⇒「顺序播放」无效；
#   ② 新增 op=replay：player 进程重启后列表（内存态）空了、但 curTitle 是落盘的，
#      点 ▶ 用落盘曲名回库反查并重推列表开播（查不到退化 random）；
#   ③ 「播放控制」的「随机播放」更名「随便听」；按歌手连播数量留空 = 该歌手全部；
#   ④ status 新增 mode / stale（player 侧）供页面回显与提示。
#
# jp2-v1.3.169：① 播放列表为空时点 ▶ 直接随机播放（player 只有「列表+index」模型，
#      空列表发 play 没有可播对象）；② 上一首/下一首发完 1.2s 主动补状态+队列；
#      ③ 切歌时队列自动把正在播的那条滚进可视区（cur 没变时一动不动）。
#
# jp2-v1.3.168：设置页重排 —— 氛围灯/调试两栏合并成「灯光控制」+ 新增「播放列表」上限
#   ① 灯光控制：氛围灯（player 侧 op=amb）+ 唤醒灯 / 播报灯（device 侧 files flag，
#      files/wake_light_off / files/busy_light_off，只挡「开」不挡「灭」）；
#   ② 播放列表上限：纯前端（localStorage r1_qlim），0 = 不限制，影响 /api/queue?limit=
#      与空搜索的 limit；
#   ③ /api/search?k= 留空 = 列全部曲库（PlayerBridge.searchJson 加 limit 参数）。
#   页头/调试卡片（灯位、角度、模拟唤醒、重连 WS）已按用户要求删除。
#
# jp2-v1.3.173：play_at 改走 subType=15（原厂 case 1 会 setIndex(0) 写死清零，15 不清零）
#    ⇒ 跳到队列第 N 首时**列表顺序完全不变**（复刻小飞版行为），失败才退回 rotate
#   起因：把 player 刷回原厂（/system/app/EchoService/EchoService.apk）后，8080 上
#         再也没有服务 ⇒ 页面一直「正在等音箱播放服务（8080）… 已等 234s」，队列空白。
#
#   实测（2026-10-16，原厂 player）：
#     · 我们的 type=4 总线指令照单全收 —— push `100_local_MUSIC_4` 后
#       EchoService 的 MultiMediaPlayer 真的 buffered → STATE_PLAYING；
#     · player 会把「当前曲目」回推到 what=33554432（USER_DATA）总线 arg1=1：
#       [{"singer":"Echosmith","songName":"Cool Kids","music_id":"100_local_MUSIC_4",…}]
#       ⇒ 曲名 / 歌手 / 库内下标全都有，而且切歌即推，比 8080 的 get_info 轮询还实时；
#       （以前 WakeupBridge 只认 key=wakeup，这条信息白白丢掉）
#     · itemId 是我们 push 时自己拼的（100_local_MUSIC_<库内下标>）⇒ 队列能自己重建。
#
#   改动：
#     ① PlayerBridge 记下最后一次 push 的列表（lastIdx）⇒ /api/queue 出队列；
#     ② WakeupBridge 把 arg1=1 的曲目事件转给 PlayerBridge.onBusSong() ⇒ 曲名/歌手/高亮；
#     ③ op=play_at&index=N（1-based）：★原厂 player 不认 JSON 的 index（实测连推三次
#        都播 itemList[0]）⇒ 改成把目标顶到列表第一再整表重推（与 play_index 同路子） ⇒
#        队列内跳转；player 不认 index 时 1.6s 后自动把目标顶到第一重推一次（两种都收敛）；
#     ④ UI：连续 3 次连不上 8080 ⇒ wsAbsent，全面改用自有链路，不再显示「正在等」；
#     ⑤ 音量：8080 缺席时改系统 STREAM_MUSIC（原厂 player 播的就是这条流，实测有效）。
#   ★ 唯一拿不到的还是总时长/进度（那个只上报原厂云 MQTT）⇒ 显示本地已播计时。
#
# jp2-v1.3.155：音量链路量纲错配修复 + 重启后「还没就绪」的显示
#   ★ 音量：player 的音量只有 WS {"type":"set_vol","vol":N} 能改（0..15 档位）。
#     以前走 /api/set vol= —— 那是系统 STREAM_MUSIC 的 0~100 百分比：
#     拖到 1~3 档 ⇒ setSystemVolume 换算成 index 0 ⇒ 静音 + 回读 0 ⇒ 滑块弹回 0，
#     而且 player 播的不是那条流 ⇒ 怎么拖都不响。现在 WS 在线一律走 set_vol，
#     离线才退回系统流（并把档位换算成百分比）。
#   ★ 重启后：player（8080）比我们起得晚，那段时间的队列/进度本来就拿不到。
#     现在切到播放/音乐页立刻重试 WS，未连时队列显示「正在等…已等 Ns」而不是「空」。
#
# jp2-v1.3.154：控制台支持「安装到桌面」（PWA）
#   · 新增 GET /manifest.webmanifest、/sw.js、/icon-<n>.png（图标运行时用 Canvas 画，
#     不往 dex 里塞 PNG 常量）
#   · 页面 head 引 manifest + iOS apple-* meta，设置 Tab 加「安装到桌面」卡片
#   ★ 硬门槛：Android Chrome 要求安全上下文，局域网 http 不算 ⇒ 用户需在
#     chrome://flags/#unsafely-treat-insecure-origin-as-secure 登记本地址；
#     UI 上会检测 window.isSecureContext 并把做法写清楚（iOS Safari 不受此限）。
#
# jp2-v1.3.153：控制台页面「过时防呆」—— 装机后用户手上那份 HTML 仍是旧的
#               （最典型症状：新按钮点了没反应）。加 no-cache meta + status ver
#               与 localStorage 比对，变了就顶部弹条提示刷新。
#
# jp2-v1.3.150：氛围灯设置（原厂总线 what=4，命令表从小飞版 r1_control.min.js 扒出并自证）
#               + 重启音箱（借道 adbd：adb reboot，我们是 u0_a8 没有 REBOOT 权限）
# jp2-v1.3.152：播放队列 / 搜索结果「点一下即播」
#               · 队列：点第 i 行 → 音箱 WS {"type":"play","index":i}
#                 （小飞版是双击，我们单击；实测回包 {"code":200,"type":"play"} 并真的切歌）
#               · 搜索：新增结构化接口 GET /api/search?k=… → {total,list:[{index,title,artist,url}]}
#                 （原 op=search 返回一整段中文文本，是给 MCP/模型看的，前端没法渲染）
#                 点结果 → op=play_url 播那条 url（没有 url 时退化为按歌名播）
# jp2-v1.3.151：设置 Tab「音频」与「行为」合并为一张卡 —— 移除「AEC 回声消除」
#               （= Android 软件 AEC，v1.3.9 实测连人声一起消掉）与「搭车原厂拾音」
#               （关掉就退化 AudioRecord 直采）两个有害开关，改只读回显「采集链路」。
#               后端 /api/set 仍接受 aec / uscTap，留给手工 A/B。
#
# ── v1.3.134 装机实测暴露的 bug（2026-10-13 20:58 r1client.log）───────────
#     20:58:40.237 music deferPause (唤醒让路挂起：音乐继续播，小智开口即暂停；trusted=true)
#     20:58:40.238 music markPaused(wakeup) → pause + 待恢复        ← 只隔 1ms！
#   根因：两个 handler 的 pause 分支里，v1.3.130 遗留的 markPaused() 钩子紧跟在
#   deferPause() 之后（DefaultMusicHandler:5619 / PhicommExternalMusicHandler:1533），
#   v1.3.134 只替换了原厂 pause，没摘掉这个旧钩子 ⇒
#     · 账记上了，但音乐**根本没被暂停**（原厂 pause 已被 deferPause 顶掉）
#       ⇒ 小智说完话的「恢复」是凭空的，会把上一曲拽回来；
#     · deferPending / pending 两套语义打架，首帧开播时反而走成 tts 分支。
#
# ── v1.3.135 改动 ──────────────────────────────────────────────────────────
#   ① 两个 handler 的挂起分支：**deferPause 之后直接 return**（goto :goto_36 /
#      :goto_49），彻底不再调 markPaused()。记账统一由
#      MusicResume.onTtsAudioStart() 在第一帧音频开播那一刻完成。
#   ② PhicommExternalMusicHandler 去掉 isSoftPlaying 门禁：external 模式原厂
#      本来就是**无条件** pause（说明原厂认为有东西在播），而软状态进程重启即丢，
#      拿它当门禁只会漏掉让路；trusted=false ⇒ 开播时 DLNA 查真状态，阴性撤账。
#
# ── 判读（files/r1client.log，tag 前缀 "music "）──────────────────────────
#   放歌中喊小智（正确顺序，两条之间应隔好几秒 = 唤醒到开口的时间）：
#     music deferPause (唤醒让路挂起：音乐继续播，小智开口即暂停；trusted=…)
#     …（音乐继续播）…
#     music markPaused(wakeup) → pause + 待恢复      ← 紧挨 tts-first-frame->play-start
#     music onTurnEnded(tts-stop) -> resume in 2500ms
#     music auto-resume … : sent
#   ★ 若 deferPause 与 markPaused 相隔 <100ms ⇒ 挂起没生效，别急着判恢复链路。
#   唤醒后啥也没说：music onTurnEnded(session-end) skip: 唤醒后音乐从未暂停…
#   软状态丢了：music yield-probe(…) -> DLNA 说没在播/在播 …
# jp2-v1.3.190：歌词多候选 rid（撞上 Live/伴奏版没词时继续试下一个）
#   真机翻车（2026-10-18 14:01~14:05）：语音「我要听 Taylor Swift 的歌」、web 按歌手
#   连播「周杰伦」全部 ERR「本地和在线都没找到」。device 日志真凶：
#     online append skipped: 已 append 401 条（上限 400），等下次 reloadIndex 归零
#   即在线 append 额度耗尽后 appendOnlineTracks 一律返回空 —— 搜索明明成功
#   （NAS /api/search 周杰伦 247 条 / Taylor Swift 263 条都秒回），只是装不进库。
#   而「等下次 reloadIndex」在正常使用里永远不会来 ⇒ 额度一满，在线点歌全哑。
#   修复：额度满时自动回收最早收录的一半在线 append（FIFO，只删 via=online/rand
#   的尾部条目；本地段/pool 段下标不动 ⇒ 调用方手里的本地命中下标不受影响；
#   player 队列持条目值拷贝 ⇒ 不影响正在播）。改动仅 PlayerBridge.appendOnlineTracks。
#
set -e
cd /c/Users/shaoh/WorkBuddy/R1/janus_pandora2

JAVA_HOME=/c/Users/shaoh/WorkBuddy/R1/janus/dl/jdk-17.0.20.1+1
JAVAC="$JAVA_HOME/bin/javac.exe"
JAR="$JAVA_HOME/bin/jar.exe"
JAVA="$JAVA_HOME/bin/java.exe"
AJAR="C:/Users/shaoh/WorkBuddy/R1/sdkdl/stage/android.jar"
R8="C:/Users/shaoh/WorkBuddy/R1/sdkdl/stage/r8.jar"
PY="C:/Users/shaoh/.workbuddy/binaries/python/versions/3.13.12/python.exe"
SMALI_CP="C:/Users/shaoh/WorkBuddy/R1/janus/smali/*"
ACKP="C:/Users/shaoh/WorkBuddy/R1/janus_pandora2/javacp"
SO="C:/Users/shaoh/WorkBuddy/R1/janus_pandora2/libr1opus.so"

# safe-delete 阈值：旧产物先 mv 走
if [ -d v22classes ]; then mv v22classes _old_v24_$(date +%s) 2>/dev/null || true; fi
if [ -d v22out ]; then mv v22out _old_v24_out_$(date +%s) 2>/dev/null || true; fi
mkdir -p v22classes v22out
find v2src -name "*.java" | sed 's|^|C:/Users/shaoh/WorkBuddy/R1/janus_pandora2/|' > v22sources.txt
echo "sources: $(wc -l < v22sources.txt)"

# v1.3.173：页面常量 UiModern.java 是 ui/ui.html 生成的 —— 以前要手动跑
# janus_pandora2/ui/gen_java.py，忘了跑就会出现「源码改了、8088 页面还是旧的」。
# 这里在 javac 之前强制重生成一次（幂等，几十毫秒）。
echo "== [0.5/6] ui.html -> UiModern.java =="
"C:/Users/shaoh/.workbuddy/binaries/python/envs/default/Scripts/python.exe" ui/gen_java.py || exit 1

echo "== [1/6] javac =="
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options \
    -bootclasspath "$AJAR" -cp "$ACKP/*" -d v22classes @v22sources.txt
echo "   classes: $(find v22classes -name '*.class' | wc -l)"

echo "== [2/6] R8（shrink sherpa + kotlin 运行时）=="
"$JAR" cf v22client.jar -C v22classes .
"$JAVA" -cp "$R8" com.android.tools.r8.R8 --release --min-api 22 \
    --lib "$AJAR" --lib "$ACKP/okhttp.jar" --lib "$ACKP/okio.jar" --lib "$ACKP/orgjson.jar" \
    --pg-conf keep_v23.pro --output v22out \
    v22client.jar "$ACKP/sherpa-onnx-classes.jar" "$ACKP/kotlin-stdlib.jar" 2>&1 | tail -8
ls -la v22out/classes.dex

echo "== [3/6] 方法数预算 =="
"$PY" - <<'PYEOF'
import struct
d = open('v22out/classes.dex','rb').read()
print(f'  stand-alone dex method_ids = {struct.unpack("<L", d[88:92])[0]}')
PYEOF

echo "== [4/6] baksmali =="
if [ -d smali_xz27 ]; then mv smali_xz27 _old_v24_smali_$(date +%s) 2>/dev/null || true; fi
"$JAVA" -cp "$SMALI_CP" org.jf.baksmali.Main d v22out/classes.dex -o smali_xz27
echo "   smali files: $(find smali_xz27 -name '*.smali' | wc -l)"
rm -rf smali_xz27/com/unisound/jni

echo "== [5/6] smali 合并装配 =="
rm -f v22_merged.dex
"$JAVA" -cp "$SMALI_CP" org.jf.smali.Main a uni_smali smali_xz27 -o v22_merged.dex
ls -la v22_merged.dex
"$PY" - <<'PYEOF'
import struct
d = open('v22_merged.dex','rb').read()
m = struct.unpack('<L', d[88:92])[0]
print(f'  merged method_ids = {m} / 65536')
assert m < 65536, '64K OVERFLOW'
for s in [b'XzBootstrap', b'KwsEngine', b'KeywordSpotter', b'sherpa-onnx-jni', b'MusicResume']:
    assert s in d, s
print('  PARASITE + SHERPA + MusicResume PRESENT: OK')
PYEOF

echo "== [5b/6] 钩子落点自检（两个 music handler） =="
"$PY" - <<'PYEOF'
import re
# --- DefaultMusicHandler：唤醒分支 isPlaying() 真 ⇒ deferPause(true)，然后**直接返回** ---
p = 'uni_smali/com/unisound/vui/handler/session/music/DefaultMusicHandler.smali'
s = open(p, encoding='utf-8', errors='replace').read()
# ★ v1.3.135：markPaused 遗留钩子必须为 0（挂起后 1ms 记账 = 无中生有的恢复账）
for name, want in [('markPaused', 0), ('markStopped', 1), ('markResumed', 1)]:
    n = len(re.findall(r'MusicResume;->' + name + r'\(\)V', s))
    print(f'  DefaultMusicHandler.{name} hooks = {n}')
    assert n == want, f'{name} hook count {n} != {want} (v1.3.135 要求 {want})'
n = len(re.findall(r'MusicResume;->deferPause\(Z\)V', s))
print(f'  DefaultMusicHandler.deferPause(Z) hooks = {n}')
assert n == 1, 'deferPause(Z) hook count != 1'
# 挂起后必须立刻返回：deferPause 到 .end method 之间只能有 goto，不能有别的 MusicResume 调用
k = s.find('MusicResume;->deferPause(Z)V')
end = s.find('.end method', k)
seg = s[k:end]
assert 'goto ' in seg, 'deferPause 之后没有直接返回（缺 goto）'
n = len(re.findall(r'MusicResume;->(?!deferPause)', seg))
print(f'  deferPause 之后的 MusicResume 调用残留: {n}')
assert n == 0, 'deferPause 之后仍残留其它 MusicResume 钩子（会污染挂起语义）'
print('  OK: 挂起后直接返回，无记账残留')
# 唤醒分支内不能有原厂 ANTPlayController;->pause
seg2 = s[max(0, k-800):k]
n = seg2.count('ANTPlayController;->pause()V')
print(f'  唤醒分支内原厂 pause 残留: {n}')
assert n == 0, '原厂 pause 仍在唤醒分支里'
print('  OK: 唤醒 pause 已替换为挂起版')
PYEOF
"$PY" - <<'PYEOF'
import re
# --- PhicommExternalMusicHandler：external 模式（用户的音乐走这条）---
p = 'uni_smali/com/phicomm/speaker/device/custom/handler/PhicommExternalMusicHandler.smali'
s = open(p, encoding='utf-8', errors='replace').read()
for name, want in [('markPaused', 0), ('markStopped', 1), ('markResumed', 1)]:
    n = len(re.findall(r'MusicResume;->' + name + r'\(\)V', s))
    print(f'  PhicommExternalMusicHandler.{name} hooks = {n}')
    assert n == want, f'{name} hook count {n} != {want} (v1.3.135 要求 {want})'
n = len(re.findall(r'MusicResume;->deferPause\(\)V', s))
print(f'  PhicommExternalMusicHandler.deferPause() hooks = {n}')
assert n == 1, 'deferPause() hook count != 1'
# v1.3.135：isSoftPlaying 门禁已去掉（软状态重启即丢，会漏让路）
n = len(re.findall(r'PlayerBridge;->isSoftPlaying\(\)Z', s))
print(f'  PhicommExternalMusicHandler.isSoftPlaying 门禁 = {n} (v1.3.135 应为 0)')
assert n == 0, 'isSoftPlaying 门禁仍在（会漏掉让路）'
k = s.find('MusicResume;->deferPause()V')
end = s.find('.end method', k)
seg = s[k:end]
assert 'goto ' in seg, 'deferPause 之后没有直接返回（缺 goto）'
n = len(re.findall(r'MusicResume;->(?!deferPause)', seg))
print(f'  deferPause 之后的 MusicResume 调用残留: {n}')
assert n == 0, 'deferPause 之后仍残留其它 MusicResume 钩子'
n = s.count('mCommonPlayer;->pause()V')
print(f'  PhicommExternalMusicHandler 原厂 pause 残留: {n}')
assert n == 0, '原厂 mCommonPlayer.pause() 仍在（应已替换）'
print('  OK: external 模式唤醒 pause 已替换为无条件挂起 + 直接返回')
PYEOF

echo "== [5c/6] v1.3.135 首帧开播让路自检 =="
"$PY" - <<'PYEOF'
import re
# TtsPlayer 播放循环起点应调用 MusicResume.onTtsAudioStart()
p = 'smali_xz27/cn/workbuddy/r1client/TtsPlayer.smali'
s = open(p, encoding='utf-8', errors='replace').read()
n = len(re.findall(r'MusicResume;->onTtsAudioStart\(\)V', s))
print(f'  TtsPlayer.onTtsAudioStart calls = {n}')
assert n == 1, 'TtsPlayer 首帧开播钩子缺失'
i = s.find('MusicResume;->onTtsAudioStart()V')
j = s.find('tts-first-frame->play-start')
assert 0 < i and 0 < j and abs(i - j) < 2000, 'onTtsAudioStart 未紧挨首帧打点'
print('  onTtsAudioStart 紧挨 tts-first-frame->play-start 打点: OK')
PYEOF
"$PY" - <<'PYEOF'
d = open('v22_merged.dex','rb').read()
for s in [b'onTtsAudioStart', b'queryPlayingStateAsync', b'StateCallback',
          b'deferPause', b'cancelDefer', b'music_pause_tts',
          # v1.3.136：跨重启播放状态
          b'restorePlayState', b'didlTitle', b'r1player', b'cur_title',
          # v1.3.137：真状态判据
          b'isMusicActive']:
    n = d.count(s)
    print(f'  {s.decode():22s} in dex: {n}')
    assert n >= 1, s
print('  first-frame yield symbols PRESENT: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.138：开关语义统一自检 —— ttsPause 必须是「让路的唯一且最先的门禁」
p = 'v2src/cn/workbuddy/r1client/MusicResume.java'
s = open(p, encoding='utf-8', errors='replace').read()
i1 = s.find('public static void deferPause(boolean trusted)')
i2 = s.find('public static void onTtsAudioStart()')
assert 0 < i1 < i2, '方法定位失败'
segd = s[i1:i2]
assert 'if (!ttsPause)' in segd, 'deferPause 缺少 ttsPause 门禁（开关关着时唤醒仍会让路）'
print('  deferPause ttsPause 门禁: OK')
seg = s[i2:i2 + 4000]
k1 = seg.find('if (!ttsPause)')
k2 = seg.find('if (wakeupYield)')
assert 0 < k1 < k2, 'onTtsAudioStart 里 ttsPause 门禁必须排在 wakeupYield 分支之前'
print(f'  onTtsAudioStart 门禁顺序 OK (ttsPause@{k1} < wakeupYield@{k2})')
# 文案同步
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
assert '音乐一路播到底' in w, 'Web 文案未同步 v1.3.138 语义'
print('  Web 开关文案已同步: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.139：自定义音乐库开关 + 日志「仅显示对话」
d = open('v22_merged.dex','rb').read()
for s in [b'my_music_off', '自定义音乐库'.encode(), '仅显示对话'.encode(),
          '我：'.encode(), '小智：'.encode(), b'sentence_start']:
    n = d.count(s)
    print(f'  {s.decode("utf-8","replace"):14s} in dex: {n}')
    assert n >= 1, s
print('  v1.3.139 symbols PRESENT: OK')
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
assert 'saveMyMusic' in w and 'tgChat' in w and "q.get(\"chat\")" in w
print('  Web: myMusic 开关 + chat 视图 wiring OK')
m = open('v2src/cn/workbuddy/r1client/XiaoZhiWs.java', encoding='utf-8', errors='replace').read()
i = m.find('private void sendMcpToolsResult(int id)')
assert 'isMyMusic()' in m[i:i+900], 'MCP 工具表缺少自定义音乐库门禁'
print('  MCP tools/list 门禁 OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.141：切音乐库开关 ⇒ 断当前连接（不必重启进程）
b = open('v2src/cn/workbuddy/r1client/XzBootstrap.java', encoding='utf-8', errors='replace').read()
assert 'public static void dropWs(' in b, 'XzBootstrap.dropWs 缺失'
assert 'probe()' not in b[b.find('public static void dropWs('):b.find('public static void dropWs(')+900], \
    'dropWs 里不能 probe（那是 restartWs 的活儿）'
print('  XzBootstrap.dropWs(只断不连): OK')
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
i = w.find('String mm = q.get(\"myMusic\")')
assert 'dropWs' in w[i:i+700], 'myMusic 切换未调用 dropWs'
print('  myMusic 切换 -> dropWs wiring OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.142：日志视图选择持久化到 localStorage（刷新页面不丢）
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
i = w.find('var chatOnly=false;')
assert i > 0 and 'localStorage.getItem' in w[i:i+220], 'chatOnly 未从 localStorage 读回'
j = w.find('function tgChat()')
assert j > 0 and 'localStorage.setItem' in w[j:j+260], 'tgChat 未写回 localStorage'
print('  chatOnly localStorage 持久化: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.143：唤醒即挂起让路 + 软状态新鲜度 + 页脚版本号动态化
c = open('v2src/cn/workbuddy/r1client/SessionController.java', encoding='utf-8', errors='replace').read()
i = c.find('private void handleWakeup(String src, boolean usePreroll)')
assert i > 0, 'handleWakeup 未找到'
seg = c[i:i+3000]
k1 = seg.find('MusicResume.onWakeup()')
k2 = seg.find('playWakeAck();', k1)   # 从调用点之后找，避开注释里的同名文字
assert k1 > 0, 'handleWakeup 未调用 MusicResume.onWakeup()'
assert k2 > 0 and k1 < k2, 'onWakeup() 必须在 playWakeAck() 之前（采样点要干净）'
print(f'  handleWakeup: onWakeup@{k1} < playWakeAck@{k2}: OK')

m = open('v2src/cn/workbuddy/r1client/MusicResume.java', encoding='utf-8', errors='replace').read()
assert 'public static void onWakeup()' in m, 'MusicResume.onWakeup 缺失'
assert 'isSoftPlayingFresh' in m, '让路判据未改用 fresh'
print('  MusicResume.onWakeup + fresh 判据: OK')

p = open('v2src/cn/workbuddy/r1client/PlayerBridge.java', encoding='utf-8', errors='replace').read()
assert 'public static boolean isSoftPlayingFresh()' in p, 'isSoftPlayingFresh 缺失'
assert 'softFresh = false;' in p[p.find('private static void restorePlayState()'):p.find('private static void restorePlayState()')+2200], \
    'restorePlayState 恢复值必须标为不新鲜'
print('  PlayerBridge.softFresh: OK')

w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
i = w.find("class='foot'")
j = w.find("</div>", i)    # 截到页脚 div 结束，别把后面的注释也算进来
line = w[i:j]
assert i > 0 and 'XzBootstrap.VERSION' in line, '页脚版本号未动态化'
assert 'v1.3.' not in line, '页脚仍写死版本号: ' + line
print('  页脚版本号动态化: OK')

d = open('v22_merged.dex','rb').read()
for s in [b'onWakeup', b'isSoftPlayingFresh', b'wakeup-yield armed', b'markPaused']:
    assert d.count(s) >= 1, s
print('  dex symbols PRESENT: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.149：指示灯联动（原厂 PhicommLightController）
r = open('v2src/cn/workbuddy/r1client/R1Lights.java', encoding='utf-8', errors='replace').read()
for s in ['public static void onWakeup(Object ctx)', 'public static void busyOn()',
          'public static void busyOff()', 'public static void allOff()',
          'ASR_FOURMIC_DOA_RESULT', 'messageCenter', 'LIGHT_LOADING = 0xcb']:
    assert s in r, 'R1Lights 缺 ' + s
print('  R1Lights 灯控方法齐备: OK')
c = open('v2src/cn/workbuddy/r1client/SessionController.java', encoding='utf-8', errors='replace').read()
i = c.find('public void onStt(String text)')
assert 'R1Lights.busyOn()' in c[i:i+900], 'onStt 未开播报灯'
j = c.find('public void onTtsStop()')
assert 'R1Lights.busyOff()' in c[j:j+400], 'onTtsStop 未关播报灯'
k = c.find('private void enterIdle()')
assert 'R1Lights.allOff()' in c[k:k+4000], 'enterIdle 未兜底关灯'
print('  SessionController: onStt/busyOn + onTtsStop/busyOff + enterIdle/allOff wiring OK')
u = open('v2src/cn/workbuddy/r1client/UscTap.java', encoding='utf-8', errors='replace').read()
i = u.find('public static void onWakeup(final Object ctx)')
assert 'R1Lights.onWakeup(ctx)' in u[i:i+900], 'UscTap.onWakeup 未点拾音灯'
print('  UscTap.onWakeup -> R1Lights.onWakeup wiring OK')
b = open('v2src/cn/workbuddy/r1client/XzBootstrap.java', encoding='utf-8', errors='replace').read()
assert 'R1Lights.init(app)' in b, 'XzBootstrap 未 init 灯控'
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
i = w.find('"light".equals(op)')
assert i > 0 and 'R1Lights.debug' in w[i:i+300], 'Web ?op=light 未接线'
print('  R1Lights.init + ?op=light wiring OK')
d = open('v22_merged.dex','rb').read()
for s in [b'R1Lights', b'busyOn', b'allOff', b'ASR_FOURMIC_DOA_RESULT']:
    assert d.count(s) >= 1, s
print('  dex symbols PRESENT: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.143b：调试唤醒入口 ?op=wakeup
b = open('v2src/cn/workbuddy/r1client/XzBootstrap.java', encoding='utf-8', errors='replace').read()
assert 'public static boolean debugWakeup()' in b, 'XzBootstrap.debugWakeup 缺失'
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
i = w.find('"wakeup".equals(op)')
assert i > 0 and 'XzBootstrap.debugWakeup' in w[i:i+300], 'op=wakeup 未接到 debugWakeup'
print('  ?op=wakeup 调试唤醒 wiring: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.144：应答音（叮）不得触发让路
mm = open('v2src/cn/workbuddy/r1client/MusicResume.java', encoding='utf-8', errors='replace').read()
i = mm.find('public static void onTtsAudioStart()')
seg = mm[i:i+2500]
assert 'inWakeCue()' in seg, 'onTtsAudioStart 未排除应答音'
k1 = seg.find('inWakeCue()'); k2 = seg.find('cancelDefer()', k1)
assert k1 < k2, '应答音判断必须排在 cancelDefer() 之前（不能把挂起撤掉）'
print('  onTtsAudioStart 应答音豁免 (inWakeCue@%d < cancelDefer@%d): OK' % (k1, k2))
j = mm.find('public static void onTurnEnded(String why)')
segt = mm[j:j+1600]
assert 'deferPending && !serverTtsSeen' in segt, 'onTurnEnded 未保留应答音收尾时的挂起'
print('  onTurnEnded 应答音收尾保留挂起: OK')

c = open('v2src/cn/workbuddy/r1client/SessionController.java', encoding='utf-8', errors='replace').read()
k = c.find('public void onTtsStart()')
assert 'MusicResume.noteServerTts()' in c[k:k+900], 'onTtsStart 未标记服务器 TTS'
k2 = c.find('private void playWakeAck()')
assert 'MusicResume.noteWakeCue()' in c[k2:k2+2600], 'playWakeAck 未标记应答音'
print('  SessionController: noteWakeCue + noteServerTts wiring OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.145：唤醒一律重采 + session-end 撤账
mm = open('v2src/cn/workbuddy/r1client/MusicResume.java', encoding='utf-8', errors='replace').read()
i = mm.find('public static void onWakeup()')
seg = mm[i:i+2200]
assert 'if (deferPending) {' not in seg, 'onWakeup 不能因为已挂起就跳过采样'
assert 'PlayerBridge.isMusicActive()' in seg, 'onWakeup 必须采样'
print('  onWakeup 一律重采: OK')
j = mm.find('public static void onTurnEnded(String why)')
segt = mm[j:j+2000]
assert 'session-end".equals(why)' in segt, 'session-end 未撤账'
print('  onTurnEnded session-end 撤账: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.146：会话结束后（断连提示音）不得再让路
mm = open('v2src/cn/workbuddy/r1client/MusicResume.java', encoding='utf-8', errors='replace').read()
assert 'public static void setSessionActive(boolean a)' in mm, 'MusicResume.setSessionActive 缺失'
i = mm.find('public static void onTtsAudioStart()')
seg = mm[i:i+1800]
k0 = seg.find('if (!sessionActive)')
kw = seg.find('inWakeCue()')
k1 = seg.find('cancelDefer()', max(k0, kw))
assert k0 > 0, 'onTtsAudioStart 缺少 sessionActive 门禁'
assert kw > 0 and kw < k0, 'inWakeCue 必须排在 sessionActive 门禁之前（否则「叮」被误记为断连 cue）'
assert k1 > 0 and k0 < k1, 'sessionActive 门禁必须排在 cancelDefer() 之前'
print('  onTtsAudioStart 判据顺序 (inWakeCue@%d < sessionActive@%d < cancelDefer@%d): OK' % (kw, k0, k1))
c = open('v2src/cn/workbuddy/r1client/SessionController.java', encoding='utf-8', errors='replace').read()
k = c.find('private void setState(State s)')
seg2 = c[k:k+700]
assert 'MusicResume.setSessionActive(active)' in seg2, 'setState 未同步 sessionActive'
print('  setState -> MusicResume.setSessionActive wiring OK')
d = open('v22_merged.dex','rb').read()
assert d.count(b'setSessionActive') >= 1, 'dex 缺 setSessionActive'
assert '会话已结束'.encode() in d or b'sessionActive' in d
print('  dex symbols PRESENT: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.148：拾音灯定格 —— onRecognizeStart 不得再灭拾音灯
p = 'uni_smali/com/phicomm/speaker/device/custom/lights/PhicommLightListener.smali'
s = open(p, encoding='utf-8', errors='replace').read()
i = s.find('.method public onRecognizeStart()V')
assert i > 0, '找不到 onRecognizeStart'
seg = s[i:s.find('.end method', i)]
assert 'turnOffALLWakeupLight' not in seg,     'onRecognizeStart 仍在灭拾音灯（唤醒后 ~440ms 会把定格亮点砍掉）'
assert 'turnOnLoadingLight' in seg, 'onRecognizeStart 结构变了'
print('  PhicommLightListener.onRecognizeStart: 灭灯已摘除，仅剩被短路的 loading  OK')
# 全灭路径必须还在：onInterrupt 保留（异常/打断时仍能收尾）
j = s.find('.method public onInterrupt()V')
assert 'turnOffALLWakeupLight' in s[j:s.find('.end method', j)],     'onInterrupt 的灭灯被误删了'
print('  onInterrupt 灭灯保留: OK')
# 我们自己的收尾：busyOn 灭拾音灯 + enterIdle 全灭
r = open('v2src/cn/workbuddy/r1client/R1Lights.java', encoding='utf-8', errors='replace').read()
k = r.find('public static void busyOn()')
assert 'wakeupAllOff()' in r[k:k+1200], 'busyOn 未先灭拾音灯'
print('  R1Lights.busyOn -> wakeupAllOff + blue: OK')
d = open('v22_merged.dex','rb').read()
for t in [b'R1Lights', b'busyOn', b'allOff']:
    assert d.count(t) >= 1, t
print('  dex symbols PRESENT: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.149：唤醒触发的「会话硬重启」不得播断连提示音
p = 'v2src/cn/workbuddy/r1client/SessionController.java'
s = open(p, encoding='utf-8', errors='replace').read()
i = s.find('if (cueOnDisconnect && wasSession)')
assert i > 0, '找不到 cueOnDisconnect 判定'
seg = s[i:i + 1100]
assert 'restartPending' in seg, \
    '断连提示音未加 restartPending 门禁（唤醒硬重启会误播）'
assert 'disconnect cue skipped' in seg, '缺 skip 日志（取证用）'
print('  enterIdle 断连提示音: restartPending 门禁已加  OK')
j = s.find('ws.close("no-tts-timeout")')
assert j > 0, 'no-tts-timeout 真掉线路径不见了'
print('  no-tts-timeout 真掉线路径保留（照旧出声）: OK')
d = open('v22_merged.dex', 'rb').read()
for t in [b'disconnect cue skipped', b'disconnect cue played']:
    assert d.count(t) >= 1, t
print('  dex symbols PRESENT: OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.151：设置页合并 —— 两个有害开关必须真的从 UI 里消失
d = open('v22_merged.dex', 'rb').read()
for t in [b'chainNote', b'fillChain', b'\xe9\x87\x87\xe9\x9b\x86\xe9\x93\xbe\xe8\xb7\xaf']:
    assert d.count(t) >= 1, t            # 「采集链路」
print('  UI 采集链路只读回显: PRESENT  OK')
for t in [b'swAec', b'swTap', b'AEC \xe5\x9b\x9e\xe5\xa3\xb0\xe6\xb6\x88\xe9\x99\xa4',
          b'\xe6\x90\xad\xe8\xbd\xa6\xe5\x8e\x9f\xe5\x8e\x82\xe6\x8b\xbe\xe9\x9f\xb3']:
    assert d.count(t) == 0, 'UI 里还有被移除的开关: ' + str(t)
print('  swAec / swTap 两个有害开关已从 UI 移除  OK')
assert d.count(b'\xe9\x9f\xb3\xe9\xa2\x91\xe4\xb8\x8e\xe8\xa1\x8c\xe4\xb8\xba') >= 1, '音频与行为'
print('  「音频与行为」合并卡片: PRESENT  OK')
PYEOF

"$PY" - <<'PYEOF'
# v1.3.152：点一下即播（队列 index + 搜索结果 url）
p = 'v2src/cn/workbuddy/r1client/WebConsole.java'
s = open(p, encoding='utf-8', errors='replace').read()
assert '/api/search' in s, '缺 /api/search 路由'
assert 'PlayerBridge.searchJson' in s, '/api/search 没接到 PlayerBridge.searchJson'
print('  /api/search 路由 -> PlayerBridge.searchJson: OK')

p = 'v2src/cn/workbuddy/r1client/PlayerBridge.java'
s = open(p, encoding='utf-8', errors='replace').read()
i = s.find('public static JSONObject searchJson')
assert i > 0, 'PlayerBridge.searchJson 不见了'
seg = s[i:i + 3200]   # v1.3.181 加了在线过滤，方法变长 ⇒ 窗口从 1600 扩到 3200
assert 'matchTracks' in seg and 'play_index' not in seg, 'searchJson 实现被改坏'
assert 'it.put("url"' in seg, 'searchJson 没带 url（前端要靠它点播）'
print('  searchJson 返回 index/title/artist/url: OK')

d = open('v22_merged.dex', 'rb').read()
for t in [b'playAt(', b'playRes(', b'renderSearch', b'\xe7\x82\xb9\xe4\xb8\x80\xe4\xb8\x8b\xe5\x8d\xb3\xe6\x92\xad']:
    assert d.count(t) >= 1, t            # 「点一下即播」
print('  UI 点播入口 playAt / playRes: PRESENT  OK')
for t in [b'/api/search', b'searchJson']:
    assert d.count(t) >= 1, t
print('  /api/search + searchJson 已进 dex: OK')
PYEOF

echo "== [5.5/6] UI 自检（v1.3.153 页面过时防呆）=="
H="v2src/cn/workbuddy/r1client/UiModern.java"
for kw in 'checkVer' 'updBar' 'Cache-Control'; do
  if grep -q "$kw" "$H"; then echo "  PRESENT  $kw"; else echo "  ✗ MISSING $kw"; exit 1; fi
done
if grep -q 'u2192' "$H"; then echo "  ✗ 残留 \\u2192（Java 词法层会吃掉，引号会被破坏）"; exit 1; else echo "  CLEAN    no \\u escape"; fi

echo "== [5.6/6] 自检：play_url 必须带走真实歌名（v1.3.153 修复）=="
python - <<'PCHECK'
p = 'v2src/cn/workbuddy/r1client/PlayerBridge.java'
s = open(p, encoding='utf-8', errors='replace').read()
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
ok = True
# 后端：不能再死写「网络音频」当唯一标题
if 'oneJson("网络音频", url)' in s:
    print('  ✗ playUrl 仍在死写「网络音频」'); ok = False
else:
    print('  OK  playUrl 不再死写标题')
# 后端：要读 args 里的 title
if 'optString("title"' not in s or 'optString("artist"' not in s:
    print('  ✗ playUrl 没读 args.title / args.artist'); ok = False
else:
    print('  OK  playUrl 读 args.title / artist')
# 前端：playRes 要带 title/artist
u = open('ui/ui.html', encoding='utf-8', errors='replace').read()
if 'title=" + encodeURIComponent(it.title)' not in u:
    print('  ✗ UI playRes 没带 title'); ok = False
else:
    print('  OK  UI playRes 带 title/artist')
if 'play_index", "index=" + it.index' not in u:
    print('  ✗ UI playRes 没优先走 play_index（曲库序号）'); ok = False
else:
    print('  OK  UI playRes 优先走 play_index')
# WebConsole 要转发 play_index（dispatch 支持但路由没转 = unknown op）
if '"play_index".equals(op)' not in w:
    print('  ✗ WebConsole 没转发 op=play_index'); ok = False
else:
    print('  OK  WebConsole 转发 op=play_index')
# WebConsole 要转发 play_index（dispatch 支持但路由没转 = unknown op）
if '"play_index".equals(op)' not in w:
    print('  ✗ WebConsole 没转发 op=play_index'); ok = False
else:
    print('  OK  WebConsole 转发 op=play_index')
# WebConsole 要把 title/artist 透传
if '"title", t' not in w or '"artist", ar' not in w:
    print('  ✗ WebConsole 没透传 title/artist'); ok = False
else:
    print('  OK  WebConsole op=play_url 透传 title/artist')
raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [5.7/6] 自检：PWA（v1.3.154 安装到桌面）=="
python - <<'PCHECK'
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8', errors='replace').read()
u = open('ui/ui.html', encoding='utf-8', errors='replace').read()
ok = True
pairs = [
    ('manifest 路由',      '/manifest.webmanifest', w),
    ('sw.js 路由',         '"/sw.js"',              w),
    ('icon 路由',          'path.startsWith("/icon-")', w),
    ('Service-Worker-Allowed', 'Service-Worker-Allowed: /', w),
    ('manifest 含 display',    '\\"display\\"',         w),
    ('manifest 含 standalone', '\\"standalone\\"',      w),
    ('UI 引 manifest',     'rel="manifest"',        u),
    ('UI 安装逻辑',        'instInit()',            u),
    ('UI 给出 flag 指引',  'chrome://flags',        u),
]
for name, kw, src in pairs:
    if kw in src:
        print('  OK       ' + name)
    else:
        print('  MISSING  ' + name + '  (' + kw + ')'); ok = False
raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [5.8/6] 自检：音量走 WS set_vol（v1.3.155 量纲修复）=="
python - <<'PCHECK'
u = open('ui/ui.html', encoding='utf-8', errors='replace').read()
ok = True
must = [
    ('音量走 WS set_vol',   'type: "set_vol"'),
    ('防回弹 applyVol',     'function applyVol'),
    ('wsSend 可发对象',     'function wsSend('),
    ('等待文案 wsWaitTxt',  'function wsWaitTxt'),
]
for name, kw in must:
    if kw in u: print('  OK       ' + name)
    else: print('  MISSING  ' + name + '  (' + kw + ')'); ok = False
# 绝对不能再回退到「拖滑块直接写自有 0~100」
if 'setKV("vol"' in u:
    print('  ✗ 音量又退回 /api/set vol=（0~100 百分比，量纲错配）'); ok = False
else:
    print('  OK       滑块不再直接写 /api/set vol=')
raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [5.9/6] 自检：灯光控制 + 夜灯 + 灯控 MCP（v1.3.173：自有队列 / 总线曲名 / play_at）=="
python - <<'PCHECK'
import io
def rd(p):
    return io.open(p, encoding='utf-8', errors='replace').read()
u      = rd('ui/ui.html')
lights = rd('v2src/cn/workbuddy/r1client/R1Lights.java')
wc     = rd('v2src/cn/workbuddy/r1client/WebConsole.java')
xz     = rd('v2src/cn/workbuddy/r1client/XiaoZhiWs.java')
pb2    = rd('v2src/cn/workbuddy/r1client/PlayerBridge.java')
sl     = rd('v2src/cn/workbuddy/r1client/SessionLog.java')
ok = True
def chk(where, src, name, kw):
    global ok
    if kw in src:
        print('  OK       ' + name)
    else:
        print('  MISSING  ' + name + '  (' + kw + ' @' + where + ')')
        ok = False
def chkNo(where, src, name, kw):
    """负面断言：这段关键字必须已经不存在（功能已移除）。"""
    global ok
    if kw in src:
        print('  STILL    ' + name + '  (' + kw + ' @' + where + ')')
        ok = False
    else:
        print('  OK(去掉) ' + name)
chk('ui',        u,      '栏目名灯光控制',   '>灯光控制 <')
chkNo('ui',      u,      '夜灯开关已移除',   'id="swNight"')
chkNo('ui',      u,      '夜灯函数已移除',   'function nightToggle')
chk('ui',        u,      '氛围灯状态回填',   'ambFill(S.ambient)')
chkNo('R1Lights',  lights, '夜灯已移除',     'public static String night(')
chkNo('R1Lights',  lights, '夜灯状态已移除', 'nightState')
chk('WebConsole', wc,    'CORS 放开',        'Access-Control-Allow-Origin')
# v1.3.181：device 页面已从 `/` 下线（只回指引页，API 全保留）——
# 设备上跑的 v134 dex 里同样没有 `<body class=` ⇒ 原「embed 嵌入模式」断言已过时，
# 换成验证指引页本身（internalNotice）还在。
chk('WebConsole', wc,    '指引页 internalNotice', 'private static String internalNotice()')
chk('WebConsole', wc,    '页面只回指引',      '8088 已改为内部接口')
chk('ui',        u,      'embed 隐藏页头',   'body.embed .top{display:none}')
chk('R1Lights',  lights, '强制清灯 wipe',      'public static String wipe(')
chk('R1Lights',  lights, 'wipe 路由',          '"wipe".equals(v)')
chkNo('ui',      u,      '调试卡片已移除',     '>调试</div>')
chk('R1Lights',  lights, '灯控 MCP 入口',    'public static String mcp(')
chk('XiaoZhiWs', xz,     '灯控前缀',         'LIGHT_PREFIX = "self.r1light."')
chk('XiaoZhiWs', xz,     '灯工具表',         'addLightTools')
chk('XiaoZhiWs', xz,     'v1.3.182 list 描述强调本地', '才是本地曲库的数量')
chkNo('XiaoZhiWs', xz,   '夜灯工具已移除',   '"night"')
chk('XiaoZhiWs', xz,     'tools/call 分流',  'name.startsWith(LIGHT_PREFIX)')
chk('SessionLog', sl,    '对话日志带日期',   '保留日期')
chk('ui',        u,      '唤醒灯开关',       "swWkL", )
chk('ui',        u,      '播报灯开关',       "swBsL", )
chk('ui',        u,      '氛围灯开关',       'ambToggle')
chk('ui',        u,      '队列上限输入',     'saveQlim')
chk('ui',        u,      '空队列随机播放',   'playOrRandom')
chk('ui',        u,      '切歌自动定位',     'queueScrollTo(box, cur)')
chk('ui',        u,      '队列窗口 start',   'Q.start')
chk('ui',        u,      'playAt 收真实下标', 'function playAt(i, real)')
chk('ui',        u,      '切歌按绝对下标判定', 'qLastCurAbs')
chk('ui',        u,      '随机->随便听',     '>随便听</button>')
chk('ui',        u,      '模式 2/1/3',       'setMode(3)')
chk('ui',        u,      '空队列走 replay',  'act("replay")')
chk('ui',        u,      '数量留空=全部',    '数量留空')
chk('ui',        u,      '模式回显',         'setModeUI(m.mode)')
chk('PlayerBridge', pb2, 'replay op',        '"replay".equals(op)')
chk('PlayerBridge', pb2, 'lastMode 字段',    'private static volatile int lastMode')
chk('PlayerBridge', pb2, 'status 带 mode',   'o.put("mode", lastMode)')
chk('PlayerBridge', pb2, '模式真值注释',     'PLAY_MODE_LIST_CYCLE   = 2')
chk('WebConsole', wc,    'replay 路由',      'dispatch("replay"')
chk('WebConsole', wc,    '主端口只监听回环',  'new ServerSocket(p, 50, lo)')
chk('WebConsole', wc,    '主端口不供页面',    'internalNotice()')
chk('WebConsole', wc,    '镜像仍供页面',      'acceptLoop(ss, true)')
chk('ui',        u,      '上下曲主动补状态', 'function skip(')
chk('ui',        u,      '空搜索列全部',     '&limit=')
chk('R1Lights',  lights, '唤醒灯 flag',      'wake_light_off')
chk('R1Lights',  lights, '播报灯 flag',      'busy_light_off')
chk('WebConsole', wc,    'set 收 wakeLight', '"wakeLight"')
chk('WebConsole', wc,    '状态带 wakeLight', 'a.put("wakeLight"')
chk('PlayerBridge', pb2, '空搜索列全部',     'key.length() == 0')
chk('PlayerBridge', pb2, '搜索带 limit',     'public static JSONObject searchJson(String q, int lim)')

raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [5.10/6] 自检：web 脱离 8080（v1.3.173 自有队列 + 总线曲名 + play_at）=="
python - <<'PCHECK'
import io
def rd(p):
    return io.open(p, encoding='utf-8', errors='replace').read()
u    = rd('ui/ui.html')
pb   = rd('v2src/cn/workbuddy/r1client/PlayerBridge.java')
wb   = rd('v2src/cn/workbuddy/r1client/WakeupBridge.java')
wc   = rd('v2src/cn/workbuddy/r1client/WebConsole.java')
ok = True
def chk(where, src, name, kw):
    global ok
    if kw in src:
        print('  OK       ' + name)
    else:
        print('  MISSING  ' + name + '  (' + kw + ' @' + where + ')')
        ok = False
def chkNo(where, src, name, kw):
    """负面断言：这段必须已经不存在。"""
    global ok
    if kw in src:
        print('  STILL    ' + name + '  (' + kw + ' @' + where + ')')
        ok = False
    else:
        print('  OK(去掉) ' + name)
# 后端：自有队列
chk('PlayerBridge', pb, '队列缓存字段',   'lastIdx')
chk('PlayerBridge', pb, '总线曲目入口',   'public static void onBusSong(')
chk('PlayerBridge', pb, 'music_id 反查',  'private static int indexOfMusicId(')
chk('PlayerBridge', pb, '队列 JSON',      'public static JSONObject queueJson(')
chk('PlayerBridge', pb, '队列内跳转',     'public static String playAt(int i)')
chk('PlayerBridge', pb, 'subType=15 跳播', 'snap, radio, i, 15')
chk('PlayerBridge', pb, '兜底 rotate',     'play_at_rotate')
chk('PlayerBridge', pb, 'subType 可选',    'int startIndex, int subType')
chk('PlayerBridge', pb, 'dispatch play_at', '"play_at".equals(op)')
chk('PlayerBridge', pb, 'status 带队列',  'o.put("qTotal"')
chk('PlayerBridge', pb, 'status 带歌手',  'o.put("curArtist"')
# 后端：总线接线 + 路由
chk('WakeupBridge', wb, '曲目事件转发',   'PlayerBridge.onBusSong(')
chk('WakeupBridge', wb, 'JSON 取字段',    'private static String jsonField(')
chk('WebConsole', wc,   '/api/queue 路由', '"/api/queue"')
chk('WebConsole', wc,   'op=play_at 路由', '"play_at".equals(op)')
# v1.3.173：镜像端口（8080 也能开我们的控制台）
chk('WebConsole', wc,   '镜像端口字段',    'mirrorPort')
chk('WebConsole', wc,   '镜像 accept 循环', 'private static void serveMirror(')
chk('WebConsole', wc,   '公共 accept 循环', 'private static void acceptLoop(')
chk('WebConsole', wc,   '镜像开关文件',    '"mirror_port"')
# v1.3.173：配合 player 侧控制台 —— device 自停接口 + 8080 默认让给 player
chk('WebConsole', wc,   'op=halt 路由',    '"halt".equals(op)')
chk('WebConsole', wc,   '自杀让位 player',  'killProcess(android.os.Process.myPid())')
chk('WebConsole', wc,   '8080 默认关',      'int mirrorPort = 0')
# 前端：不再死等 8080
chk('ui', u, '8080 缺席标记',   'wsAbsent')
chk('ui', u, '自有队列拉取',    'function loadQueue(')
chk('ui', u, '点播走 play_at',  'act("play_at"')
chk('ui', u, '队列双数据源',    'wsOK ? PLIST : (Q.list')
chkNo('ui', u, '不再死等 8080', '正在等音箱播放服务')
raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

python - <<'PCHECK'
d = open('v22_merged.dex', 'rb').read()
for t in [b'onBusSong', b'queueJson', b'play_at', b'/api/queue', b'songName', b'music_id']:
    n = d.count(t)
    print('  %-12s in dex: %d' % (t.decode(), n))
    assert n >= 1, t
print('  dex symbols PRESENT: OK')
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [5.11/6] 自检：在线搜歌 + 实际歌曲信息（v1.3.175）=="
python - <<'PCHECK'
p = 'v2src/cn/workbuddy/r1client/PlayerBridge.java'
s = open(p, encoding='utf-8').read()
w = open('v2src/cn/workbuddy/r1client/WebConsole.java', encoding='utf-8').read()
x = open('v2src/cn/workbuddy/r1client/XiaoZhiWs.java', encoding='utf-8').read()
b = open('v2src/cn/workbuddy/r1client/XzBootstrap.java', encoding='utf-8').read()
h = open('ui/ui.html', encoding='utf-8').read()
ok = True
def chk(name, kw, src=None):
    global ok
    src = s if src is None else src
    if kw in src:
        print('  OK       ' + name)
    else:
        print('  MISSING  ' + name + '  (' + kw + ')')
        ok = False
# ---- 版本 ----
chk('VERSION 1.3.190', 'VERSION = "1.3.190"', b)
# ---- v1.3.190：歌词多候选 —— 一首歌多个 rid，逐个试到有词的那个 ----
chk('候选列表函数', 'private static List<String[]> ridCandidates(String title, String artist)', s)
chk('候选缓存', 'ridListCache', s)
chk('去重助手', 'private static boolean hasRid(List<String[]> l, String[] rr)', s)
chk('逐个试到有词', 'for (int ci = 0; ci < cands.size(); ci++)', s)
chk('命中日志', 'lyric 命中 ', s)
chk('无词报尝试过', '这首歌没有歌词（试过 ', s)
chk('搜索取前 5 条', '&n=5&source=all', s)
chk('标题歌手对得上才收', 'boolean okT = kt.equals(songKey(s.optString("title", "")))', s)
# ---- v1.3.189：总线回推也要换封面（以前只改曲名 ⇒ 切歌后 curPic 还是上一首的） ----
chk('总线回推带封面', 'v1.3.189：封面也必须跟着这一首走', s)
chk('无封面时清空', 'curPic = "";', s)
chk('异步补封面', 'if (curPic.length() == 0 && curTitle.length() > 0)', s)
# ---- v1.3.188：封面 + 歌词（落雪经 NAS mini-lx 中转；/api/lrc 给页面用） ----
chk('歌词接口', 'public static JSONObject lyricJson(String title, String artist)', s)
chk('rid 解析 rel', 'private static String[] parseRel(String rel)', s)
chk('曲目→source/rid', 'private static String[] ridOf(String title, String artist)', s)
chk('封面取 NAS /api/pic', '"/api/pic?rid="', s)
chk('歌词取 NAS /api/lyric', '"/api/lyric?rid="', s)
chk('搜索结果带封面', 'jstr(s, "img")', s)
chk('起播补封面', 'fetchPicAsync(e[0], e[1])', s)
chk('status 带 curPic', 'o.put("curPic", curPic)', s)
chk('/api/lrc 路由', 'path.equals("/api/lrc")', w)
chk('JSON null 归一', 'private static String jstr(JSONObject o, String k)', s)
# ---- v1.3.187：入库去重（同名同歌手复用旧下标，跨源同曲合并；额度不再被重复搜索吃光） ----
chk('入库前查重', 'int ex = findSameOnline(it);', s)
chk('查重函数', 'private static int findSameOnline(String[] it)', s)
chk('曲名归一函数', 'private static String songKey(String s)', s)
chk('复用不占额度', 'reused++;', s)
chk('去重日志', 'online append 去重：复用库里已有', s)
# ---- v1.3.186：额度回收补完（判据=本次装不下；回收量覆盖缺口；计数按真实条目重数） ----
chk('判据=本次装不下', 'int need = onlineAppends + items.size() - ONLINE_APPEND_CAP;', s)
chk('回收量覆盖缺口', 'evictOldestOnline(Math.max(ONLINE_APPEND_CAP / 2, need))', s)
chk('只回收真 append（online/rand）', '"online".equals(e[7]) || "rand".equals(e[7])', s)
chk('计数按真实条目重数', 'onlineAppends = countOnlineAppends();', s)
chk('回收函数存在', 'private static int evictOldestOnline(int target)', s)
chk('重数函数存在', 'private static int countOnlineAppends()', s)
chk('javadoc 同步', '回收最早收录', s)
# ---- v1.3.184：play_artist 命中过滤在线条目（文案「本地 X 首」必须名副其实） ----
chk('play_artist 命中过滤在线', 'if (ix < 0 || ix >= tracks.size() || isOnlineTrack(tracks.get(ix))) {\n                        hits.remove(i);')
chk('play_artist ERR 报本地数', 'int locN2 = 0;')
# ---- v1.3.181：搜索曲库只出本地（在线 append 的条目过滤掉） ----
chk('searchJson 只搜本地(空词)', 'if (e.length > 7 && e[7] != null && e[7].length() > 0) {\n                        continue;')
chk('searchJson 只搜本地(有关键词)', 'local.add(Integer.valueOf(ix));')
# ---- v1.3.182：小智的 list/search 也只认本地（dispatch 是与 searchJson 独立的路径） ----
chk('isOnlineTrack 助手', 'private static boolean isOnlineTrack(String[] e)')
chk('artistSummary 本地开关', 'artistSummary(int maxArtists, boolean localOnly)')
chk('list 本地在线分报', 'OK 本地共 ')
chk('list 在线收录说明', '首在线曲库的歌（不属于本地')
chk('voice search 过滤在线', 'if (ix >= 0 && ix < tracks.size() && !isOnlineTrack(tracks.get(ix)))')
chk('voice search 没找到报本地数', '相关的本地歌，本地曲库共 ')
# ---- v1.3.183：启动加载的在线池段也标 via="pool"（真凶：100 首池歌 via="" 冒充本地） ----
chk('loadIndex 池段打标', 'String via = (k >= cut) ? "pool" : "";')
chk('list 文案改在线曲库', '首在线曲库的歌（不属于本地')
# ---- PlayerBridge：在线搜歌三件套 ----
chk('nasBase 基址推导', 'private static String nasBase()')
chk('onlineSearchJson 在线搜', 'public static JSONObject onlineSearchJson(String q, int lim)')
chk('playOnlineItem 六参版', 'playOnlineItem(String title, String artist, String source, String rid,')
chk('album/duration 透传', 'it.put("album", s.optString("album", ""))')
chk('tracks String[8] 带 via', 'album == null ? "" : album, duration == null ? "" : duration, "", "online"')
chk('notePlayingFull 上报', 'private static void notePlayingFull(String title, String album, String duration, String via)')
chk('curAlbum 字段', 'private static volatile String curAlbum')
chk('curVia online 角标', 'private static volatile String curVia')
chk('池内命中按曲目上报', 'notePlayingTrack(hit)')
chk('loadIndex 读 album', 't.optString("album", "")')
chk('status 输出 curAlbum', 'o.put("curAlbum", curAlbum)')
chk('queueJson 带 album', 'it.put("album", e.length > 4 ? e[4] : "")')
chk('语音兜底 playOnlineSong', 'public static String playOnlineSong(String q)')
# ---- WebConsole：三个新 op + online=1 ----
chk('op=play_song', '"play_song".equals(op)', w)
chk('op=play_online', '"play_online".equals(op)', w)
chk('op=search_online', '"search_online".equals(op)', w)
chk('/api/search online=1', '"1".equals(q.get("online"))', w)
# ---- v1.3.176：online=1 无条件在线搜（不再要求池内 0 命中） ----
if 'sj.optInt("total", 0) == 0' in w:
    print('  MISSING  不再要求池内 0 命中（还在判 total==0）')
    ok = False
else:
    print('  OK       不再要求池内 0 命中')
chk('在线条数可配', 'q.get("olimit")', w)
chk('搜不到也给空壳', 'on.put("total", 0)', w)
chk('在线超时 15s', '&source=all", 15000)', s)
chk('在线列表自己截断', 'out.length() >= lim', s)
# ---- v1.3.177：语音点歌「在线优先于最接近的」 ----
chk('本地命中置信判定', 'private static boolean localHitSolid(String k, int idx)', s)
chk('不准就改用在线', 'playOnlineSong(q, 4000, 8000)', s)
chk('语音短预算版', 'public static String playOnlineSong(String q, int searchMs, int urlMs)', s)
chk('取链超时可传', 'String album, String duration, int urlMs)', s)
# ---- XiaoZhiWs：MCP 描述纠偏 ----
chk('play_song 描述：点名直接调', '用户**点名要听某首歌**时，直接调这个', x)
chk('search 描述：只覆盖池子', '它**只覆盖本地曲库池**', x)
# ---- ui.html：在线搜歌 UI ----
chk('在线搜歌按钮', 'doSearchOnline()', h)
chk('fillNpMeta 实际信息', 'function fillNpMeta(m)', h)
chk('npMeta 元素', 'id="npMeta"', h)
chk('SRES_ON 在线结果', 'var SRES_ON', h)
chk('playOnlineRes 播在线', 'function playOnlineRes(j)', h)
# ---- v1.3.178：在线随机池 / 歌手连播含在线 / 播放信息不再残留 ----
chk('在线随机池拉取', 'private static List<String[]> fetchOnlinePool(int n, boolean fresh)', s)
chk('fresh=1 换一批', '/index.json?n=" + ask + (fresh ? "&fresh=1" : "")', s)
chk('randPool 缓存', 'private static final List<String[]> randPool', s)
chk('randPoolJson 给 web', 'public static JSONObject randPoolJson(int n)', s)
chk('op=random_online', '"random_online".equals(op)', s)
chk('op=play_rand_at', '"play_rand_at".equals(op)', s)
chk('点一首连整批进队列', 'pushSongs("play_rand_at", hitFirstIdx(hit, keep.size()))', s)
chk('在线并行取链', 'private static List<String[]> resolveOnlineUrls(', s)
chk('取链并发 4（12 会被限流）', 'URL_CONC = 4', s)
chk('取链错峰启动', 'URL_STAGGER = 250L', s)
chk('取链整体预算', 'long left = budgetMs - (System.currentTimeMillis() - t0)', s)
chk('歌手在线预算 4s+5s', 'onlineTracksFor(q, onlineMax(), 4000, 5000)', s)
chk('歌手在线补充', 'private static List<String[]> onlineTracksFor(String q, int max, int searchMs, long budgetMs)', s)
chk('play_artist 混在线', 'pick.addAll(oi)', s)
chk('play_artist 在线兜底', 'play_artist->online', s)
chk('在线 append 上限', 'ONLINE_APPEND_CAP', s)
chk('via 随曲目走', 'private static void notePlayingTrack(int idx)', s)
chk('推播按曲目上报', 'notePlayingTrack(idxs.get(at).intValue())', s)
chk('总线补实际信息', 'private static void notePlayingBus(', s)
chk('总线反查库内下标', 'ti = lastIdx.get(k).intValue()', s)
chk('总线不再抹掉信息', 'notePlayingBus(title, singer, ti)', s)
chk('曲名一换就清残留', 'curAlbum = "";\n            curDuration = "";\n            curVia = "";', s)
chk('随机批 via=rand', 'jstr(t, "pic"), "rand"', s)
chk('ui 区分在线随机/搜到', 'm.curVia === "rand"', h)
chk('/api/search random=1', '"1".equals(q.get("random"))', w)
chk('op=random_online 路由', '"random_online".equals(op)', w)
chk('op=play_rand_at 路由', '"play_rand_at".equals(op)', w)
chk('MCP 新增 random_online', 'tool("random_online"', x)
chk('play_artist 描述含在线', '本地曲库 + 在线曲库一起放', x)
chk('random 描述指向 random_online', '请用 random_online', x)
chk('ui 留空走随机', 'rnd ? "&random=1', h)
chk('ui playRandAt', 'function playRandAt(j)', h)
# v1.3.179：在线曲目改走惰性地址（推列表不再逐条取链）
chk('歌手在线条数走上限', 'onlineTracksFor(q, onlineMax(), 4000, 5000)', s)
chk('惰性探针', 'private static boolean lazyOk(String b)', s)
chk('惰性构造曲目', 'private static List<String[]> lazyTracks(', s)
chk('优先惰性', 'if (lazyOk(b)) {\n                return lazyTracks(b, cand);', s)
chk('老 NAS 回落取链', '// 老 mini-lx（没有 /r/ 路由）照原路逐条取链，功能不倒退。', s)
chk('单曲取链超时后改推惰性', 'url.length() == 0 && lazyOk(b)', s)
chk('单曲惰性话术', '（在线，起播时缓冲一下）', s)
chk('单曲预算 8s', 'return playOnlineSong(q, 8000, 8000)', s)
chk('不支持只信 10 分钟', 'now - lazyCheckedAt < 600000', s)
# v1.3.179：mini-lx 与 r1-music 合并成一个容器（R1XiaoZhi_MediaServer）
chk('默认曲库指向 8100', 'http://192.168.2.189:8100/index.json', s)
chk('在线池走 /online.json', 'b + "/online.json?n=" + ask', s)
chk('老 NAS 回落 index.json', 'b + "/index.json?n=" + ask + (fresh ? "&fresh=1" : "")', s)
chk('404 不当有效响应', 'if (t0.has("tracks")) {', s)
chk('★探针自己吞 404', '            try {\n                String s = httpGet(b + "/online.json?n=" + ask + (fresh ? "&fresh=1" : ""),', s)
chk('在线库关闭视为拉不到', 'o.optBoolean("off", false)', s)
chk('本地段单独留档', 'private static final List<String[]> localTracks', s)
chk('loadIndex 记本地段', 'nl.add(nt.get(nt.size() - 1))', s)
chk('onlineFrom 分界', 'o.has("onlineFrom") ? o.optInt("onlineFrom", 0) : -1', s)
chk('老源按 source 猜', '"mini-lx".equals(o.optString("source", "")) ? 0 : ta.length()', s)
chk('随机在线留本地段', 'tracks.addAll(keep);\n                        tracks.addAll(pool);', s)
chk('随机在线只推在线段', 'shuffledIdx(keep.size())', s)
chk('点一首只推在线段', 'hitFirstIdx(hit, keep.size())', s)
# v1.3.179（防封禁）：只有「旧 NAS 没有惰性地址」时才真的逐条取链，那时必须收着
chk('旧路径最多取 8 条链', 'int cap = Math.min(cand.size(), LEGACY_URL_MAX);', s)
chk('旧路径并发 2', 'cand.subList(0, cap), LEGACY_URL_CONC, 9000, budgetMs', s)
# v1.3.181：曲目数量上限统一（设置页两栏：本地 / 在线）
#   在线那一栏管住**所有**从 NAS 拉在线歌的途径：按歌手连播补的在线曲目（原 20）、
#   「随机播放在线音乐」（原 100）、页面「在线搜歌」（原 20）—— 以前三个写死的数。
#   在线只是拉列表（起播才取链），多拿不花时间 ⇒ 默认给到 NAS 上限 300。
chk('onlineMax 读取', 'public static int onlineMax()', s)
chk('onlineMax 默认 300', 'DEFAULT_ONLINE_MAX = 300', s)
chk('在线上限写盘', 'public static String setOnlineMax(int v)', s)
chk('本地上限写盘', 'public static String setPlaylistMax(int v)', s)
chk('playlistMax 可读', 'public static int playlistMax()', s)
chk('写文件工具 writeLineFile', 'public static boolean writeLineFile(String name, String val)', b)
chk('随机在线默认走上限', 'cnt = onlineMax();', s)
chk('随机批默认走上限', 'int rn = PlayerBridge.onlineMax();', w)
chk('在线搜歌默认走上限', 'int n = PlayerBridge.onlineMax();', w)
chk('set 支持 onlineMax', 'q.get("onlineMax")', w)
chk('set 支持 playlistMax', 'q.get("playlistMax")', w)
chk('status 报 limits', 'lm.put("onlineMax", PlayerBridge.onlineMax())', w)
raise SystemExit(0 if ok else 1)
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

python - <<'PCHECK'
d = open('v22_merged.dex', 'rb').read()
for t in [b'playOnlineSong', b'playOnlineItem', b'onlineSearchJson', b'nasBase',
          b'\xe5\x9c\xa8\xe7\xba\xbf\xe6\x90\x9c\xe5\x88\xb0\xe7\x9a\x84']:
    n = d.count(t)
    print('  %-24s in dex: %d' % (t.decode('utf-8','replace'), n))
    assert n >= 1, t
print('  dex symbols PRESENT: OK')
PCHECK
if [ $? -ne 0 ]; then echo "自检未通过，终止"; exit 1; fi

echo "== [6/6] janus 构建与验签 =="
"$PY" - <<'PYEOF'
import sys, os, re
sys.path.insert(0, 'C:/Users/shaoh/WorkBuddy/R1/diag/pandora')
import janus_poc
dex = open('v22_merged.dex', 'rb').read()
so = open('libr1opus.so', 'rb').read()
print(f'  dex={len(dex)}B so={len(so)}B prefix={len(dex)+len(so)}B')
info = janus_poc.build_janus(dex + so, 'C:/Users/shaoh/WorkBuddy/R1/diag/pandora/Unisound.apk', 'xiaozhi_Unisound_v144.apk')
print('janus:', info, 'size', os.path.getsize('xiaozhi_Unisound_v144.apk'))
r = janus_poc.verify_v1('xiaozhi_Unisound_v144.apk', 'xiaozhi_Unisound_v144')
janus_poc.show(r)
assert r['verdict'] == 'PASS', 'v1 verify FAIL'
d = open('xiaozhi_Unisound_v144.apk','rb').read()
zi = d.find(b'\x50\x4b\x03\x04')
elfs = [m.start() for m in re.finditer(b'\x7fELF', d[:zi])]
print(f'  prefix={zi}B, ELF in prefix: {len(elfs)} @{elfs[:3]}')
assert len(elfs) >= 1, 'NO SO IN PREFIX'
print('DONE: xiaozhi_Unisound_v144.apk')
PYEOF

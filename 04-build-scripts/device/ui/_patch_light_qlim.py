# -*- coding: utf-8 -*-
"""
pl-1.1.4 / v1.3.168 页面改动（同时打到 player.html 与 device 的 ui.html）：

  1) 设置页：删掉「氛围灯」+「调试」两张卡，换成
       · 「播放列表」—— 队列上限输入（0 = 不限制），存 localStorage
       · 「灯光控制」—— 氛围灯 / 唤醒灯 / 播报灯 三个独立开关
  2) 音乐页：搜索框留空点「搜索」= 列全部曲库（?k=&limit=N）
  3) loadQueue() 用 QLIM 而不是写死的 300

每个锚点必须在每个文件里**恰好命中 1 次**，否则不写盘。
"""
import io
import os
import sys

FILES = [
    r"C:\Users\shaoh\WorkBuddy\R1\janus_pandora2\ui\ui.html",
    r"C:\Users\shaoh\WorkBuddy\R1\janus_player2\ui\player.html",
]

# ---------------- 1) 两张卡 -> 播放列表 + 灯光控制 ----------------
OLD1 = u'''    <div class="card">
      <div class="ttl">氛围灯 <span class="hint">彩色灯效 · 与唤醒/播报灯不是一套</span></div>
      <div class="row">
        <div><div class="lb">氛围灯</div><div class="ds">彩色灯效；关掉后只剩唤醒、播报灯会亮</div></div>
        <div class="sw" id="swAmb" onclick="ambToggle()"></div>
      </div>
      <div class="note" style="margin-top:8px">★ <b>效果模式</b>与<b>自定义颜色</b>已移除：
        总线 what=4 的 <b>arg1=68</b>（官方/七彩/旋转/单色）与 <b>70</b>（颜色）在
        <b>原厂 player 里没有接收者</b> —— <code>PlayerVisualizerHelper</code> 的 switch
        只有 64/65/66/67 四个 case，实测发下去 logcat 一条都没有（亮度/渐变有日志）。
        那两个是小飞魔改版私有的扩展 ⇒ 换回原厂后自然就没了。<br>
        开着的形态是：15 颗灯同色，颜色随音乐频谱缓慢推进（渐变速度决定快慢）。</div>
    </div>

    <div class="card">
      <div class="ttl">调试</div>
      <div class="ttl" style="margin-top:2px">拾音灯 / 播报灯</div>
      <div class="btns">
        <button class="btn" onclick="act('light','v=wake')">唤醒灯</button>
        <button class="btn" onclick="act('light','v=busy')">播报灯</button>
        <button class="btn" onclick="act('light','v=off')">全灭</button>
        <button class="btn" onclick="act('light','v=wipe')">强制清灯</button>
      </div>
      <div class="note" style="margin-top:6px">「强制清灯」= 连发 24 条 close 耗空排队；灯仍常亮说明是
        旧进程残留的效果（关不掉），只能重启音箱。</div>
      <div class="inline" style="margin-top:8px">
        <input type="number" id="lIdx" placeholder="灯位 1-24" min="1" max="24">
        <button class="btn sm" onclick="act('light','v=idx:'+v('lIdx'))">点亮</button>
        <input type="number" id="lAng" placeholder="角度 0-359">
        <button class="btn sm" onclick="act('light','v=angle:'+v('lAng'))">按角度</button>
      </div>
      <div class="btns" style="margin-top:10px">
        <button class="btn" onclick="act('wakeup')">模拟一次唤醒</button>
        <button class="btn" onclick="act('ws_restart')">重连 WS</button>
      </div>
    </div>
'''

NEW1 = u'''    <div class="card">
      <div class="ttl">播放列表 <span class="hint">列表最多显示多少首</span></div>
      <div class="inline">
        <input type="number" id="inQlim" min="0" max="5000" placeholder="300">
        <button class="btn sm" onclick="saveQlim()">应用</button>
      </div>
      <div class="note" style="margin-top:6px">影响「播放」页的队列，以及「音乐」页留空搜索时的列表。
        <b>0 = 不限制</b>（列全部）。曲库 1188 首时全列会比较卡，建议 300 左右。</div>
    </div>

    <div class="card">
      <div class="ttl">灯光控制 <span class="hint">三项各自独立</span></div>
      <div class="row">
        <div><div class="lb">氛围灯</div><div class="ds">彩色灯效（随音乐频谱变色），跑在 player 里</div></div>
        <div class="sw" id="swAmb" onclick="ambToggle()"></div>
      </div>
      <div class="row">
        <div><div class="lb">唤醒灯</div><div class="ds">唤醒时亮起，定格在收音方向（24 颗拾音灯）</div></div>
        <div class="sw" id="swWkL" onclick="tg('swWkL','wakeLight')"></div>
      </div>
      <div class="row">
        <div><div class="lb">播报灯</div><div class="ds">识别中与小智说话期间的蓝色氛围环</div></div>
        <div class="sw" id="swBsL" onclick="tg('swBsL','busyLight')"></div>
      </div>
      <div class="note" style="margin-top:8px">★ 氛围灯的<b>效果模式</b>与<b>自定义颜色</b>已移除：
        总线 what=4 的 <b>arg1=68</b>（官方/七彩/旋转/单色）与 <b>70</b>（颜色）在
        <b>原厂 player 里没有接收者</b> —— <code>PlayerVisualizerHelper</code> 的 switch
        只有 64/65/66/67 四个 case，实测发下去 logcat 一条都没有（亮度/渐变有日志）。
        那两个是小飞魔改版私有的扩展 ⇒ 换回原厂后自然就没了。<br>
        开着的形态是：15 颗灯同色，颜色随音乐频谱缓慢推进。</div>
    </div>
'''

# ---------------- 2) 队列上限：QLIM + saveQlim + loadQueue 用它 ----------------
OLD2 = u'''/* v1.3.163：自有队列 —— 8080 缺席时它就是播放页的列表数据源。
   cur 由 player 回推的「当前曲目」算出来（总线事件），切歌会自动跟着变。 */
function loadQueue() {
  if (DEMO) { renderQueue(); return; }
  return fetch("/api/queue?limit=300").then(function (r) { return r.json(); })'''

NEW2 = u'''/* 队列上限（存在浏览器 localStorage 里，0 = 不限制）。
   改完立刻重拉一次列表 —— 不用等下一轮轮询。 */
var QLIM = 300;
try {
  var _q = localStorage.getItem("r1_qlim");
  if (_q !== null && _q !== "") QLIM = Math.max(0, parseInt(_q, 10) || 0);
} catch (e) {}
function saveQlim() {
  var n = parseInt(v("inQlim"), 10);
  if (isNaN(n) || n < 0) n = 0;
  QLIM = n;
  try { localStorage.setItem("r1_qlim", String(n)); } catch (e) {}
  toast(n === 0 ? "队列上限：不限制（列全部）" : ("队列上限：" + n + " 首"));
  loadQueue();
}

/* v1.3.163：自有队列 —— 8080 缺席时它就是播放页的列表数据源。
   cur 由 player 回推的「当前曲目」算出来（总线事件），切歌会自动跟着变。 */
function loadQueue() {
  if (DEMO) { renderQueue(); return; }
  return fetch("/api/queue?limit=" + QLIM).then(function (r) { return r.json(); })'''

# ---------------- 3) 空搜索 = 列全部 ----------------
OLD3 = u'''function doSearch() {
  var k = v("qKey").trim();
  if (!k) return;
  var box = $("qRes");'''
NEW3 = u'''function doSearch() {
  var k = v("qKey").trim();
  // ★ 关键词留空 = 列全部曲库（服务端 matchTracks 遇到空串返回全部）
  var box = $("qRes");'''

OLD4 = u'''  fetch("/api/search?k=" + encodeURIComponent(k))'''
NEW4 = u'''  fetch("/api/search?k=" + encodeURIComponent(k)
    + (k ? "" : "&limit=" + (QLIM || 0)))'''

OLD5 = u'''      <div class="ttl">搜索曲库</div>'''
NEW5 = u'''      <div class="ttl">搜索曲库 <span class="hint">留空点搜索 = 列全部</span></div>'''

# ---------------- 4) 状态回填：唤醒灯 / 播报灯 ----------------
OLD6 = u'''    setSw("swMm", !!a.myMusic);
    ambFill(S.ambient);'''
NEW6 = u'''    setSw("swMm", !!a.myMusic);
    ambFill(S.ambient);
    // 灯光控制（v1.3.168）：唤醒灯 / 播报灯是 device 侧的 files flag，默认都开
    setSw("swWkL", a.wakeLight !== false);
    setSw("swBsL", a.busyLight !== false);'''

OLD7 = u'''             log: true, sound: true, autoDisconnect: false, musicPause: false, myMusic: true },'''
NEW7 = u'''             log: true, sound: true, autoDisconnect: false, musicPause: false, myMusic: true,
             wakeLight: true, busyLight: true },'''

# ---------------- 5) 启动时把上限填进输入框 ----------------
OLD8 = u'''  tab(curTab);
  load();'''
NEW8 = u'''  $("inQlim").value = QLIM;
  tab(curTab);
  load();'''

PAIRS = [
    ("卡片：氛围灯+调试 -> 播放列表+灯光控制", OLD1, NEW1),
    ("JS：QLIM + saveQlim + loadQueue", OLD2, NEW2),
    ("JS：doSearch 允许空关键词", OLD3, NEW3),
    ("JS：空搜索带 limit", OLD4, NEW4),
    ("HTML：搜索曲库 hint", OLD5, NEW5),
    ("JS：状态回填唤醒/播报灯", OLD6, NEW6),
    ("JS：MOCK 补 wakeLight/busyLight", OLD7, NEW7),
    ("JS：启动时填 inQlim", OLD8, NEW8),
]


def main():
    ok = True
    for path in FILES:
        if not os.path.exists(path):
            print("MISSING", path)
            ok = False
            continue
        s = io.open(path, "r", encoding="utf-8", newline="").read()
        print("=== %s  (%d chars)" % (os.path.basename(path), len(s)))
        for name, old, new in PAIRS:
            c = s.count(old)
            if c != 1:
                print("  [FAIL] %s -> hit %d" % (name, c))
                ok = False
                continue
            s = s.replace(old, new)
            print("  [ok] %s" % name)
        if ok:
            io.open(path, "w", encoding="utf-8", newline="").write(s)
            print("  written:", len(s), "chars")
    print("ALL OK" if ok else "ABORTED")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

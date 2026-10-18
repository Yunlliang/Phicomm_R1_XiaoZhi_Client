# -*- coding: utf-8 -*-
"""
pl-1.1.5 / v1.3.169 页面改动（同时打到 player.html 与 device 的 ui.html）：

  ① 播放列表为空时，点主播放按钮（▶）直接随机来一批，而不是发一个没有对象的 play；
     「继续播放」按钮同理（走 playOrRandom()）。
  ② 上一首/下一首改成 skip(±1)：发完指令 1.2s 主动补一次状态+队列（原来要等 10s 轮询）。
  ③ 队列自动定位：只是当前曲变了（切歌/下一首）⇒ 把正在播的那条滚进可视区；
     cur 没变（轮询刷新）⇒ 一动不动（pl-1.1.2 修的「翻两屏被弹回顶部」不能回退）。

每个锚点必须在每个文件里**恰好命中 1 次**，否则不写盘。
"""
import io
import os
import sys

FILES = [
    r"C:\Users\shaoh\WorkBuddy\R1\janus_pandora2\ui\ui.html",
    r"C:\Users\shaoh\WorkBuddy\R1\janus_player2\ui\player.html",
]

# ---------------- 1) HTML：上一首/下一首/继续播放 ----------------
OLD1 = u'''<div class="cb" id="bPrev" onclick="act('prev')">'''
NEW1 = u'''<div class="cb" id="bPrev" onclick="skip(-1)">'''

OLD2 = u'''<div class="cb" id="bNext" onclick="act('next')">'''
NEW2 = u'''<div class="cb" id="bNext" onclick="skip(1)">'''

OLD3 = u'''<button class="btn" onclick="act('play')">继续播放</button>'''
NEW3 = u'''<button class="btn" onclick="playOrRandom()">继续播放</button>'''

# ---------------- 2) JS：togglePlay -> playOrRandom + skip ----------------
OLD4 = u'''function togglePlay() { act(PSTATE ? "pause" : "play"); }'''
NEW4 = u'''/* v1.3.169：player 只有「列表 + index」一种模型 —— 列表是空的时候发 play
   没有任何可播的对象（点了没声音）。所以空队列时 ▶ 直接随机来一批。 */
function playOrRandom() {
  var n = wsOK ? (PLIST || []).length : ((Q.list || []).length);
  if (!n) {
    toast("队列是空的 —— 随机来一批");
    act("random");
    setTimeout(function () { load(); loadQueue(); }, 1800);
    setTimeout(function () { load(); loadQueue(); }, 4500);
    return;
  }
  act("play");
}
function togglePlay() {
  if (PSTATE) { act("pause"); return; }
  playOrRandom();
}
/* 上一首/下一首：发完指令主动补一次（play_at/next 都要等 player 回推总线才知道
   切到哪一首了，原来要等 10s 轮询才更新高亮）。 */
function skip(dir) {
  act(dir > 0 ? "next" : "prev");
  setTimeout(function () { load(); loadQueue(); }, 1200);
}'''

# ---------------- 3) 队列：切歌自动定位 ----------------
OLD5 = u'''  if (!force && lsig === qLastListSig && box.children.length === use.length) {  // ②
    var old = box.querySelector(".li.cur");
    if (old) { old.className = "li"; }
    if (cur >= 0 && cur < box.children.length) { box.children[cur].className = "li cur"; }
    qLastSig = sig; qLastCur = cur;
    return;
  }'''
NEW5 = u'''  if (!force && lsig === qLastListSig && box.children.length === use.length) {  // ②
    var old = box.querySelector(".li.cur");
    if (old) { old.className = "li"; }
    if (cur >= 0 && cur < box.children.length) { box.children[cur].className = "li cur"; }
    // ★ pl-1.1.5：只是当前曲变了（切歌 / 下一首）⇒ 把正在播的那条滚进可视区。
    //   cur 没变（8s 轮询刷新）⇒ 一动不动 —— 不能把 pl-1.1.2 修好的「翻两屏被弹回顶部」改回去。
    if (cur >= 0 && cur !== qLastCur) { queueScrollTo(box, cur); }
    qLastSig = sig; qLastCur = cur;
    return;
  }'''

OLD6 = u'''  box.scrollTop = st;
  qLastSig = sig; qLastListSig = lsig; qLastCur = cur;'''
NEW6 = u'''  // 列表整张换了：当前曲也变了 ⇒ 定位到当前曲；否则还原原来的滚动位置
  if (cur >= 0 && cur !== qLastCur) { queueScrollTo(box, cur); } else { box.scrollTop = st; }
  qLastSig = sig; qLastListSig = lsig; qLastCur = cur;'''

OLD7 = u'''/* 「定位当前」：只有用户主动点才滚（容器能滚就滚容器，否则滚页面） */
function jumpCur() {
  var box = $("queue");
  var cur = wsOK ? PIDX : (Q.cur == null ? -1 : Q.cur);
  if (cur < 0 || !box.children[cur]) { toast("还没在播，没法定位"); return; }
  var c = box.children[cur];
  if (box.scrollHeight > box.clientHeight + 4) {
    var top = c.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop;
    box.scrollTop = Math.max(0, top - box.clientHeight / 2 + c.offsetHeight / 2);
  } else {
    window.scrollTo(0, c.getBoundingClientRect().top + (window.pageYOffset || 0) - 120);
  }
}'''
NEW7 = u'''/* 把第 i 条滚进可视区（居中）。只动容器滚动条，不动整页 —— 切歌自动定位用这个。 */
function queueScrollTo(box, i) {
  var c = box && box.children[i];
  if (!c) { return; }
  if (box.scrollHeight > box.clientHeight + 4) {
    var top = c.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop;
    box.scrollTop = Math.max(0, top - box.clientHeight / 2 + c.offsetHeight / 2);
  }
}
/* 「定位当前」：用户主动点 —— 容器本身滚不动（列表短）时才滚整页 */
function jumpCur() {
  var box = $("queue");
  var cur = wsOK ? PIDX : (Q.cur == null ? -1 : Q.cur);
  if (cur < 0 || !box.children[cur]) { toast("还没在播，没法定位"); return; }
  if (box.scrollHeight <= box.clientHeight + 4) {
    window.scrollTo(0, box.children[cur].getBoundingClientRect().top
      + (window.pageYOffset || 0) - 120);
    return;
  }
  queueScrollTo(box, cur);
}'''

# ---------------- 4) 空队列提示文案 ----------------
OLD8 = u'''      : '还没推过列表 —— 去音乐页搜一首点它，或点上面的「随机播放」') + '</div>';'''
NEW8 = u'''      : '还没推过列表 —— 点上面的 ▶ 就会随机来一批，或去音乐页选一首') + '</div>';'''

PAIRS = [
    ("HTML：上一首 -> skip(-1)", OLD1, NEW1),
    ("HTML：下一首 -> skip(1)", OLD2, NEW2),
    ("HTML：继续播放 -> playOrRandom", OLD3, NEW3),
    ("JS：playOrRandom + togglePlay + skip", OLD4, NEW4),
    ("JS：切歌时队列自动定位", OLD5, NEW5),
    ("JS：换列表时定位当前曲", OLD6, NEW6),
    ("JS：queueScrollTo + jumpCur 重构", OLD7, NEW7),
    ("文案：空队列提示", OLD8, NEW8),
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

# -*- coding: utf-8 -*-
"""
pl-1.1.6 / v1.3.169 配套页面改动（同时打到 player.html 与 device 的 ui.html）：

  队列窗口化 —— 列表比 limit 长、且当前曲落在窗口外时，服务端把窗口挪到当前曲附近
  （/api/queue 多返回一个 `start` 偏移）。页面据此：
    · 显示的是「第 start+1 – start+N 首」而不是「前 N 首」；
    · 序号显示真实曲库序号（it.n）；
    · 点播用真实下标（playAt(i, real)），高亮/定位用 Q.cur - Q.start。
  没有这层，随机模式下当前曲经常落在前 300 之外 ⇒ 「定位当前」永远找不到它。

每个锚点必须在每个文件里**恰好命中 1 次**，否则不写盘。
"""
import io
import os
import sys

FILES = [
    r"C:\Users\shaoh\WorkBuddy\R1\janus_pandora2\ui\ui.html",
    r"C:\Users\shaoh\WorkBuddy\R1\janus_player2\ui\player.html",
]

OLD1 = u'''  var use = wsOK ? PLIST : (Q.list || []);
  var cur = wsOK ? PIDX : (Q.cur == null ? -1 : Q.cur);'''
NEW1 = u'''  var use = wsOK ? PLIST : (Q.list || []);
  // ★ 窗口偏移：列表比 limit 长时，服务端会把窗口挪到当前曲附近（Q.start）
  var qs = wsOK ? 0 : (Q.start || 0);
  var cur = wsOK ? PIDX : (Q.cur == null ? -1 : (Q.cur - qs));'''

OLD2 = u'''    return '<div class="li' + (i === cur ? " cur" : "") + '" onclick="playAt(' + i + ')">'
      + '<div class="n">' + (i + 1) + "</div>"'''
NEW2 = u'''    var real = it.n ? (it.n - 1) : i;      // 真实曲库下标（0-based）
    return '<div class="li' + (i === cur ? " cur" : "") + '" onclick="playAt(' + i + ',' + real + ')">'
      + '<div class="n">' + (it.n || (i + 1)) + "</div>"'''

OLD3 = u'''  var tot = (Q.total || use.length);
  $("qHint").textContent = "点一下即播 · "
    + (tot > use.length ? ("显示前 " + use.length + " / 共 " + tot + " 首") : ("共 " + tot + " 首"));'''
NEW3 = u'''  var tot = (Q.total || use.length);
  var qs0 = wsOK ? 0 : (Q.start || 0);
  $("qHint").textContent = "点一下即播 · "
    + (tot > use.length
        ? ("第 " + (qs0 + 1) + "–" + (qs0 + use.length) + " 首 / 共 " + tot + " 首")
        : ("共 " + tot + " 首"));'''

OLD4 = u'''function jumpCur() {
  var box = $("queue");
  var cur = wsOK ? PIDX : (Q.cur == null ? -1 : Q.cur);'''
NEW4 = u'''function jumpCur() {
  var box = $("queue");
  var cur = wsOK ? PIDX : (Q.cur == null ? -1 : (Q.cur - (Q.start || 0)));'''

OLD5 = u'''function playAt(i) {
  if (DEMO) { toast("演示模式：不真的播放"); return; }
  if (wsOK && WS && WS.readyState === 1) {
    if (i === PIDX) { toast("已经在播这首了"); return; }'''
NEW5 = u'''/* i = 列表里的位置，real = 真实曲库下标（窗口化后两者不再相等） */
function playAt(i, real) {
  var abs = (typeof real === "number") ? real : i;
  if (DEMO) { toast("演示模式：不真的播放"); return; }
  if (wsOK && WS && WS.readyState === 1) {
    if (abs === PIDX) { toast("已经在播这首了"); return; }'''

OLD6 = u'''  if (i === (Q.cur == null ? -1 : Q.cur)) { toast("已经在播这首了"); return; }
  act("play_at", "index=" + (i + 1)).then(function () {'''
NEW6 = u'''  if (abs === (Q.cur == null ? -1 : Q.cur)) { toast("已经在播这首了"); return; }
  act("play_at", "index=" + (abs + 1)).then(function () {'''

PAIRS = [
    ("renderQueue：窗口偏移 qs", OLD1, NEW1),
    ("renderQueue：真实序号 + playAt(i,real)", OLD2, NEW2),
    ("qHint：显示窗口区间", OLD3, NEW3),
    ("jumpCur：cur 减 start", OLD4, NEW4),
    ("playAt：收 real 参数", OLD5, NEW5),
    ("playAt：用真实下标 play_at", OLD6, NEW6),
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

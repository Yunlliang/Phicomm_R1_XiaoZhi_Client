# -*- coding: utf-8 -*-
"""
v1.3.172 / pl-1.2.0 页面补丁（ui.html + player.html 同源同改）

① 循环模式：原厂 AbsPlayer.setPlayMode **只接受 1/2/3**（1=列表随机 2=列表循环/顺序
   3=单曲循环，见 Constant.PLAY_MODE_*），传 0 会被打回 "setPlayMode error, mode = 0"
   并 return false ⇒ 页面上「顺序播放」（data-v=0）点了永远没反应。
   改成 2=顺序播放 / 1=随机 / 3=单曲循环，并把服务端回的 mode 回填到 seg。
② 「播放控制」里的「随机播放」更名为「随便听」；各处提示文案同步。
③ 按歌手连播：数量留空 ⇒ 不传 count（服务端 0 = 该歌手全部歌曲），不再默认 5。
④ player 进程重启后顶栏残留上次曲名（curTitle 是 device 落盘的，队列是内存态已空）
   ⇒ 队列为空时点 ▶ 走新的 op=replay（device 用落盘曲名回库反查并重新推列表开播），
   没记录/找不到就退化为 random；同时给个 stale 提示。

每个锚点在两个文件里都必须各命中 1 次，否则整体中止。
"""
import io
import sys

FILES = [
    r"C:\Users\shaoh\WorkBuddy\R1\janus_pandora2\ui\ui.html",
    r"C:\Users\shaoh\WorkBuddy\R1\janus_player2\ui\player.html",
]

P = []

# ---- ① 循环模式：三档，值改成 2/1/3 -------------------------------
P.append((
    u'''      <div class="seg" id="segMode">
        <div data-v="0" onclick="setMode(0)">顺序播放</div>
        <div data-v="1" onclick="setMode(1)">随机播放</div>
      </div>''',
    u'''      <div class="seg" id="segMode">
        <div data-v="2" onclick="setMode(2)">顺序播放</div>
        <div data-v="1" onclick="setMode(1)">随机</div>
        <div data-v="3" onclick="setMode(3)">单曲循环</div>
      </div>''',
))

# ---- ② 「随机播放」按钮 → 「随便听」 -------------------------------
P.append((
    u'''<button class="btn pri" onclick="act('random')">随机播放</button>''',
    u'''<button class="btn pri" onclick="act('random')">随便听</button>''',
))
# 注：那句「还没播过 …「随机播放」」的改名在下面 ⑥ 的 npNote 锚点里一并做，
#     这里不要再单独替换（否则 ⑥ 的锚点会先被改掉而失配）。
P.append((
    u"""      ? '队列为空（点「随机播放」或去音乐页选台）'""",
    u"""      ? '队列为空（点「随便听」或去音乐页选台）'""",
))

# ---- ③ 按歌手连播：数量留空 = 全部 --------------------------------
P.append((
    u'''        <input type="number" id="qCnt" value="5" min="1" max="50" style="max-width:64px">''',
    u'''        <input type="number" id="qCnt" value="" min="1" max="50" placeholder="全部" style="max-width:64px">''',
))
P.append((
    u'''      <div class="ttl">按歌手连播</div>''',
    u'''      <div class="ttl">按歌手连播 <span class="hint">数量留空 = 该歌手全部</span></div>''',
))
P.append((
    u'''  act("play_artist", "artist=" + encodeURIComponent(a) + "&count=" + (v("qCnt") || 5));''',
    u'''  // ★ 数量留空 ⇒ 传 0 = 该歌手的全部歌曲都排进播放列表（服务端 count<=0 = 不限量）。
  //   以前空着会补 5，用户想要「全部」只能手动填一个很大的数。
  var c = v("qCnt").trim();
  act("play_artist", "artist=" + encodeURIComponent(a) + "&count=" + (c ? c : 0));''',
))

# ---- ④ setMode 拆出 setModeUI（供 status 回填） -------------------
P.append((
    u'''function setMode(m) {
  var d = $("segMode").children;
  for (var i = 0; i < d.length; i++) d[i].className = (+d[i].getAttribute("data-v") === m) ? "on" : "";
  act("mode", "v=" + m);
}''',
    u'''/* 循环模式：pl-1.2.0 起用**原厂真值** —— AbsPlayer.setPlayMode 只认
   1/2/3（1=列表随机 2=列表循环/顺序 3=单曲循环，见 Constant.PLAY_MODE_*），
   传 0 会被打回并 return false，这就是以前「顺序播放」点了没反应的原因。 */
function setModeUI(m) {
  var d = $("segMode").children;
  for (var i = 0; i < d.length; i++) d[i].className = (+d[i].getAttribute("data-v") === m) ? "on" : "";
}
function setMode(m) { setModeUI(m); act("mode", "v=" + m); }''',
))

# ---- ⑤ status 回填 mode -------------------------------------------
P.append((
    u'''    RADIOS = m.radioList || [];''',
    u'''    // 循环模式回显（服务端 status 现在带 mode：1=随机 2=顺序 3=单曲循环）
    if (m.mode != null && m.mode > 0) setModeUI(m.mode);

    RADIOS = m.radioList || [];''',
))

# ---- ⑥ 顶栏 stale 提示 --------------------------------------------
P.append((
    u'''      $("npNote").textContent = m.curTitle ? wsWaitTxt()
        : "还没播过 —— 去音乐页搜一首点它，或点上面的「随机播放」";''',
    u'''      $("npNote").textContent = m.stale
        ? "上次播过的曲名（player 重启后列表已空）—— 点 ▶ 会重新播放它"
        : (m.curTitle ? wsWaitTxt()
           : "还没播过 —— 去音乐页搜一首点它，或点上面的「随便听」");''',
))

# ---- ⑦ playOrRandom：队列空 + 有上次曲名 ⇒ replay -----------------
P.append((
    u'''function playOrRandom() {
  var n = wsOK ? (PLIST || []).length : ((Q.list || []).length);
  if (!n) {
    toast("队列是空的 —— 随机来一批");
    act("random");
    setTimeout(function () { load(); loadQueue(); }, 1800);
    setTimeout(function () { load(); loadQueue(); }, 4500);
    return;
  }
  act("play");
}''',
    u'''function playOrRandom() {
  var n = wsOK ? (PLIST || []).length : ((Q.list || []).length);
  if (!n) {
    // ★ player 进程重启后：队列（内存态）空了、但 curTitle 是落盘的还在顶栏显示。
    //   这时点 ▶ 以前只会 random（顶栏那首点不动）。现在先试 replay ——
    //   device 用落盘曲名回库反查，找到就整段推回 player 并开播；找不到才退化 random。
    var last = ((S.music || {}).curTitle || "").trim();
    if (last) {
      toast("队列已空 —— 重新播放「" + last + "」");
      act("replay");
    } else {
      toast("队列是空的 —— 随机来一批");
      act("random");
    }
    setTimeout(function () { load(); loadQueue(); }, 1800);
    setTimeout(function () { load(); loadQueue(); }, 4500);
    return;
  }
  act("play");
}''',
))


def main():
    ok = True
    for path in FILES:
        s = io.open(path, "r", encoding="utf-8", newline="").read()
        print("===", path.split("\\")[-1], len(s))
        for old, new in P:
            c = s.count(old)
            if c != 1:
                print("   FAIL(%d) %s" % (c, old[:50].replace("\n", "\\n")))
                ok = False
                continue
            s = s.replace(old, new)
            print("   ok  %s" % old[:46].replace("\n", "\\n"))
        if ok:
            io.open(path, "w", encoding="utf-8", newline="").write(s)
            print("   written", len(s))
    print("ALL OK" if ok else "ABORT")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

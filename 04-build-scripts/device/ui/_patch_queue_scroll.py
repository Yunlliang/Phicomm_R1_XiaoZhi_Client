# -*- coding: utf-8 -*-
"""把 player.html 那套「队列滚动位置保护」同步回 device 侧真源 ui.html。

三处同改：① renderQueue 重写（签名跳过 / 只换高亮 / 保 scrollTop / 不自动滚动）
          ② 标题加「定位当前」  ③ .ttl .lnk 样式
写回必须用 io.open(newline='')，否则 CRLF ⇒ md5 DIFF。
"""
import io
import os

P = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ui.html")
s = io.open(P, "r", encoding="utf-8", newline="").read()
orig = s

OLD_FN = u'''function renderQueue() {
  var box = $("queue");
  var use = wsOK ? PLIST : (Q.list || []);
  var cur = wsOK ? PIDX : (Q.cur == null ? -1 : Q.cur);
  if (!use.length) {
    box.innerHTML = '<div class="note" style="margin:0">' + (wsOK
      ? '队列为空（点「随机播放」或去音乐页选台）'
      : '还没推过列表 —— 去音乐页搜一首点它，或点上面的「随机播放」') + '</div>';
    return;
  }
  box.innerHTML = use.map(function (it, i) {
    var nm = it.title || it.name || ("曲目 " + (i + 1));
    var ar = it.artist || it.singer || "";
    return '<div class="li' + (i === cur ? " cur" : "") + '" onclick="playAt(' + i + ')">'
      + '<div class="n">' + (i + 1) + "</div>"
      + '<div class="tx"><div class="a">' + esc(nm) + "</div>"
      + (ar ? '<div class="b">' + esc(ar) + "</div>" : "") + "</div></div>";
  }).join("");
  var c = box.querySelector(".li.cur");
  if (c && c.scrollIntoView) c.scrollIntoView({ block: "nearest" });
}'''

NEW_FN = u'''/* 渲染队列（v1.3.163：8080 在就用它的 list，不在就用自有 /api/queue）
   ★★ 滚动位置必须保住。以前每 8s 轮询都整块 `innerHTML =` 重建 300 条
      （#queue 是 .scroll 容器，scrollTop 归零）+ 末尾 scrollIntoView 把当前曲
      拽回可视区 ⇒ 用户往下翻两屏就被弹回顶部。现在四条：
       ① 列表与当前曲都没变 ⇒ 直接 return（连 DOM 都不碰）；
       ② 只是当前曲变了 ⇒ 只换高亮 class，不重建（切歌也不打断浏览）；
       ③ 真要重建 ⇒ 先记 scrollTop 再原样还原；
       ④ 不再自动滚到当前曲 —— 想跳就点标题右边的「定位当前」。 */
var qLastSig = "", qLastListSig = "", qLastCur = -2;
function qName(it, i) { return it.title || it.name || ("曲目 " + (i + 1)); }
function qListSig(use) {
  return (wsOK ? "W" : "Q") + use.length + "|" + (Q.total || 0) + "|"
    + (use.length ? qName(use[0], 0) : "") + "|"
    + (use.length ? qName(use[use.length - 1], use.length - 1) : "");
}
function renderQueue(force) {
  var box = $("queue");
  if (!box) { return; }
  var use = wsOK ? PLIST : (Q.list || []);
  var cur = wsOK ? PIDX : (Q.cur == null ? -1 : Q.cur);
  var lsig = qListSig(use);
  var sig = lsig + "|" + cur;
  if (!force && sig === qLastSig) { return; }                 // ①
  if (!use.length) {
    box.innerHTML = '<div class="note" style="margin:0">' + (wsOK
      ? '队列为空（点「随机播放」或去音乐页选台）'
      : '还没推过列表 —— 去音乐页搜一首点它，或点上面的「随机播放」') + '</div>';
    qLastSig = sig; qLastListSig = lsig; qLastCur = cur;
    return;
  }
  if (!force && lsig === qLastListSig && box.children.length === use.length) {  // ②
    var old = box.querySelector(".li.cur");
    if (old) { old.className = "li"; }
    if (cur >= 0 && cur < box.children.length) { box.children[cur].className = "li cur"; }
    qLastSig = sig; qLastCur = cur;
    return;
  }
  var st = box.scrollTop;                                     // ③
  box.innerHTML = use.map(function (it, i) {
    var nm = qName(it, i);
    var ar = it.artist || it.singer || "";
    return '<div class="li' + (i === cur ? " cur" : "") + '" onclick="playAt(' + i + ')">'
      + '<div class="n">' + (i + 1) + "</div>"
      + '<div class="tx"><div class="a">' + esc(nm) + "</div>"
      + (ar ? '<div class="b">' + esc(ar) + "</div>" : "") + "</div></div>";
  }).join("");
  box.scrollTop = st;
  qLastSig = sig; qLastListSig = lsig; qLastCur = cur;
  var tot = (Q.total || use.length);
  $("qHint").textContent = "点一下即播 · "
    + (tot > use.length ? ("显示前 " + use.length + " / 共 " + tot + " 首") : ("共 " + tot + " 首"));
}
/* 「定位当前」：只有用户主动点才滚（容器能滚就滚容器，否则滚页面） */
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

OLD_TTL = u'<div class="ttl">播放队列 <span class="hint" id="qHint">点一下即播</span></div>'
NEW_TTL = u'<div class="ttl">播放队列 <span class="hint" id="qHint">点一下即播</span><span class="lnk" onclick="jumpCur()">定位当前</span></div>'

OLD_CSS = u'.ttl .hint{font-weight:400;color:var(--tx3);font-size:11px;letter-spacing:0}'
NEW_CSS = u'''.ttl .hint{font-weight:400;color:var(--tx3);font-size:11px;letter-spacing:0}
.ttl .lnk{float:right;font-weight:400;color:var(--ac);font-size:11px;letter-spacing:0;cursor:pointer;padding:0 2px}'''

for old, new, tag in ((OLD_FN, NEW_FN, "renderQueue"),
                      (OLD_TTL, NEW_TTL, "标题"),
                      (OLD_CSS, NEW_CSS, "CSS")):
    n = s.count(old)
    if n != 1:
        raise SystemExit("锚点 %s 命中 %d 次（应为 1），中止" % (tag, n))
    s = s.replace(old, new)

io.open(P, "w", encoding="utf-8", newline="").write(s)
print("ok, %d -> %d bytes" % (len(orig), len(s)))

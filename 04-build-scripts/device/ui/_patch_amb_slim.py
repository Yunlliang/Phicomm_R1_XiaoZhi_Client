# -*- coding: utf-8 -*-
"""氛围灯卡片瘦身：页面只留「开关」。

★ 依据（2026-10-16 实测 + 反编译）：原厂 player 的
  com.phicomm.speaker.player.helpers.PlayerVisualizerHelper.notifyMsg()
  注册了 what=4，但 switch(arg1) 只有 case 64/65/66/67（开关/亮度/渐变/缩放），
  **68（效果模式）和 70（自定义颜色）没有分支** —— 实测发下去 logcat 一条都没有
  （luma/chroma 有 `PlayerVisualizer: set luma gain:50`）。
  那两个是小飞魔改 player 的私有扩展（小飞命令表标 min_ver:1848）。
  原厂唯一形态 = PlayerVisualizer：15 颗灯同色、色相随频谱推进。

两边同改：janus_player2/ui/player.html + janus_pandora2/ui/ui.html
写回一律 io.open(newline='')，否则 CRLF ⇒ md5 DIFF。
"""
import io
import os

R1 = r"C:\Users\shaoh\WorkBuddy\R1"
TARGETS = [
    os.path.join(R1, "janus_player2", "ui", "player.html"),
    os.path.join(R1, "janus_pandora2", "ui", "ui.html"),
]

OLD_HTML = u'''      <div class="ttl" style="margin-top:8px">效果</div>
      <div class="seg" id="segAmb">
        <div data-v="0" onclick="ambMode(0)">官方</div>
        <div data-v="2" onclick="ambMode(2)">七彩</div>
        <div data-v="1" onclick="ambMode(1)">旋转</div>
        <div data-v="3" onclick="ambMode(3)">旋转1</div>
        <div data-v="4" onclick="ambMode(4)">单色</div>
      </div>
      <div class="ttl" style="margin-top:10px">亮度 <b class="hint" id="ambLumaV">100</b></div>
      <input type="range" id="ambLuma" min="1" max="200" value="100"
             oninput="ambShow('luma',this.value)" onchange="ambSet('luma',this.value)">
      <div class="ttl">渐变速度 <b class="hint" id="ambChrV">4</b></div>
      <input type="range" id="ambChr" min="0" max="100" value="4"
             oninput="ambShow('chroma',this.value)" onchange="ambSet('chroma',this.value)">
      <div class="inline" style="margin-top:8px">
        <input type="color" id="ambColor" value="#66CCFF"
               style="flex:none;width:54px;height:34px;padding:0;border:1px solid var(--line);border-radius:10px">
        <button class="btn sm" onclick="ambColorApply()">应用单色</button>
      </div>
      <div class="kv" style="margin-top:8px"><span>本页设过的值</span><b id="ambState">—</b></div>
      <div class="note" style="margin-top:6px">真实状态在设备控制服务里，这边读不到，
        所以显示的是「上次在本页设过的值」；用小飞版改过的话这里不会同步。</div>'''

NEW_HTML = u'''      <div class="note" style="margin-top:8px">★ <b>效果模式</b>与<b>自定义颜色</b>已移除：
        总线 what=4 的 <b>arg1=68</b>（官方/七彩/旋转/单色）与 <b>70</b>（颜色）在
        <b>原厂 player 里没有接收者</b> —— <code>PlayerVisualizerHelper</code> 的 switch
        只有 64/65/66/67 四个 case，实测发下去 logcat 一条都没有（亮度/渐变有日志）。
        那两个是小飞魔改版私有的扩展 ⇒ 换回原厂后自然就没了。<br>
        开着的形态是：15 颗灯同色，颜色随音乐频谱缓慢推进（渐变速度决定快慢）。</div>'''

OLD_JS = u'''/* ---------- 氛围灯 ----------
   命令（what=4）语义是从魔改 player 的控制页 JS 里扒出来、并在本机自证过的：
     64 开关 / 65 亮度 / 66 渐变速度 / 68 效果 / 70 单色颜色
   效果值：0 官方 · 1 七彩旋转 · 2 七彩 · 3 七彩旋转1 · 4 单色 */
var AMB = { on: -1, mode: -1, luma: -1, chroma: -1, color: -1 };
function ambShow(which, val) {
  $(which === "luma" ? "ambLumaV" : "ambChrV").textContent = val;
}
function ambSet(which, val) {
  ambShow(which, val);
  AMB[which] = +val;
  return act("amb", "v=" + which + ":" + encodeURIComponent(val));
}
function ambToggle() {
  var on = AMB.on === 1 ? 0 : 1;
  AMB.on = on;
  setSw("swAmb", on === 1);
  return act("amb", "v=" + (on === 1 ? "on" : "off"));
}
function ambMode(m) {
  AMB.mode = m;
  var gd = $("segAmb").children;
  for (var i = 0; i < gd.length; i++) {
    gd[i].className = (+gd[i].getAttribute("data-v") === m) ? "on" : "";
  }
  return act("amb", "v=mode:" + m);
}
function ambColorApply() {
  return act("amb", "v=color:" + encodeURIComponent((v("ambColor") || "").replace("#", "")));
}
function ambFill(a) {
  a = a || {};
  AMB.on = a.on != null ? a.on : -1;
  AMB.mode = a.mode != null ? a.mode : -1;
  AMB.luma = a.luma != null ? a.luma : -1;
  AMB.chroma = a.chroma != null ? a.chroma : -1;
  AMB.color = a.color != null ? a.color : -1;
  setSw("swAmb", AMB.on === 1);
  var gd = $("segAmb").children;
  for (var i = 0; i < gd.length; i++) {
    gd[i].className = (+gd[i].getAttribute("data-v") === AMB.mode) ? "on" : "";
  }
  if (AMB.luma >= 0) { $("ambLuma").value = AMB.luma; $("ambLumaV").textContent = AMB.luma; }
  if (AMB.chroma >= 0) { $("ambChr").value = AMB.chroma; $("ambChrV").textContent = AMB.chroma; }
  if (AMB.color >= 0) {
    $("ambColor").value = "#" + ("000000" + AMB.color.toString(16)).slice(-6);
  }
  var bits = [];
  if (AMB.mode >= 0) bits.push(a.modeName || AMB.mode);
  if (AMB.luma >= 0) bits.push("亮度" + AMB.luma);
  if (AMB.chroma >= 0) bits.push("渐变" + AMB.chroma);
  if (AMB.color >= 0) bits.push(a.colorHex || "#" + ("000000" + AMB.color.toString(16)).slice(-6));
  $("ambState").textContent = bits.length ? bits.join(" · ") : "还没在本页设过";
}'''

NEW_JS = u'''/* ---------- 氛围灯 ----------
   ★ 2026-10-16 实测（原厂 player）：总线 what=4 只有 arg1=64/65/66/67 有人收
     —— PlayerVisualizerHelper.notifyMsg() 的 switch 就这四个 case：
       64 开关 / 65 亮度(0..100) / 66 渐变速度(1..100) / 67 缩放模式(0|1)
     arg1=68（效果模式：官方·七彩·旋转·单色）与 70（自定义颜色）**没有任何接收者**：
     实测发 mode/color 后 logcat 一条日志都没有（luma/chroma 有
     `PlayerVisualizer: set luma gain:50`）。那两个是小飞魔改版私有的扩展
     （小飞命令表标 min_ver:1848）⇒ 换回原厂就没了，本页已移除这两项。
     原厂形态 = PlayerVisualizer：15 颗灯同色，色相随音乐频谱缓慢推进。 */
var AMB = { on: -1 };
function ambToggle() {
  var on = AMB.on === 1 ? 0 : 1;
  AMB.on = on;
  setSw("swAmb", on === 1);
  return act("amb", "v=" + (on === 1 ? "on" : "off"));
}
function ambFill(a) {
  a = a || {};
  AMB.on = a.on != null ? a.on : -1;
  setSw("swAmb", AMB.on === 1);
}'''

for p in TARGETS:
    s = io.open(p, "r", encoding="utf-8", newline="").read()
    n0 = len(s)
    for old, new, tag in ((OLD_HTML, NEW_HTML, "HTML 卡片"),
                          (OLD_JS, NEW_JS, "JS 逻辑")):
        n = s.count(old)
        if n != 1:
            raise SystemExit("%s 的锚点「%s」命中 %d 次（应为 1），中止" % (p, tag, n))
        s = s.replace(old, new)
    io.open(p, "w", encoding="utf-8", newline="").write(s)
    crlf = s.count("\r\n")
    print("%s: %d -> %d bytes, CRLF=%d, ambMode 残留=%s"
          % (os.path.basename(p), n0, len(s), crlf, "ambMode(" in s))

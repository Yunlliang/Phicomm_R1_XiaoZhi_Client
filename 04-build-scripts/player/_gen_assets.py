# -*- coding: utf-8 -*-
"""从 device 侧 WebConsole.java 抽出 PWA 三件套常量，生成 player 侧的 Assets.java。

为什么要抽而不是手抄：DONATE_QR_B64 有 6.5 万个字符，手工搬必错。
"""
import io, os

SRC = r"C:\Users\shaoh\WorkBuddy\R1\janus_pandora2\v2src\cn\workbuddy\r1client\WebConsole.java"
DST = r"C:\Users\shaoh\WorkBuddy\R1\janus_player2\plsrc\cn\workbuddy\r1web\Assets.java"

lines = io.open(SRC, encoding="utf-8").read().split("\n")


def block(start_kw):
    """抽出 start_kw 所在行 .. 独立的分号行（整行只有 ';'）。

    ⚠ 不能用「含分号的行」判定结束：SW_JS 的第一行就是
    `"var CACHE='r1-shell-v1';\\n"` —— 分号在字符串里，会提前收尾。
    """
    s = None
    for i, l in enumerate(lines):
        if start_kw in l and "=" in l:
            s = i
            break
    if s is None:
        raise SystemExit("not found: " + start_kw)
    for j in range(s + 1, len(lines)):
        t = lines[j].strip()
        # 两种收尾写法：独立的 ";"（DONATE），或同行 `...";`（MANIFEST / SW_JS）
        if t == ";" or t.endswith('";'):
            return lines[s:j + 1]
    raise SystemExit("end not found: " + start_kw)


donate = block("DONATE_QR_B64")
manif = block("MANIFEST_JSON")
swjs = block("SW_JS")


def reindent(b, pad):
    """把块首行的缩进换成 pad，其余行保持原样（Java 拼接串，缩进无所谓）。
    首行顺带改成 public —— 这几个常量要给 WebSrv 用。"""
    out = []
    for i, l in enumerate(b):
        if i == 0:
            out.append(pad + l.strip().replace("private static final", "public static final"))
        else:
            out.append(l)
    return out


ICON = '''
    /**
     * 运行时画 PWA 图标 —— 不往 dex 里塞 PNG（192/512 两张得好几 KB）。
     * maskable=true ⇒ 底铺满整张画布、字缩到中心（Android 自适应图标按遮罩裁剪，
     * 安全区只有中心 80%，画满会被切掉角）。
     */
    public static byte[] iconPng(int size, boolean maskable) {
        try {
            android.graphics.Bitmap bmp =
                    android.graphics.Bitmap.createBitmap(size, size,
                            android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            android.graphics.Paint p =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setShader(new android.graphics.LinearGradient(0, 0, size, size,
                    0xFF22D3EE, 0xFF818CF8, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, size, size, p);
            p.reset();
            p.setAntiAlias(true);
            p.setColor(0xFFFFFFFF);
            p.setTypeface(android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD));
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setTextSize(maskable ? size * 0.30f : size * 0.42f);
            android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
            cv.drawText("R1", size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos);
            bmp.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
'''

HEAD = '''package cn.workbuddy.r1web;

/**
 * 页面的静态资源（赞赏码 / PWA 三件套）—— 从 device 侧 8088 那一套原样搬过来。
 *
 * <p>为什么必须有：8080 的页面是从 8088 那套复制来的，里面引用了
 * {@code /donate.png}、{@code /manifest.webmanifest}、{@code /sw.js}、
 * {@code /icon-*.png}，而 player 侧 pv1..pv11 只给了一个没有图标的假 manifest
 * ⇒ 赞赏码图片 404、Chrome 拿不到「安装应用」的资格。</p>
 *
 * <p>本文件由 <code>_gen_assets.py</code> 从
 * <code>janus_pandora2/v2src/.../WebConsole.java</code> 抽取生成，
 * 别手改（赞赏码 base64 有 6.5 万字符）。</p>
 */
public final class Assets {

'''

body = []
body += reindent(donate, "    ")
body.append("")
body += reindent(manif, "    ")
body.append("")
body += reindent(swjs, "    ")
body.append(ICON)
body.append("    private Assets() {\n    }\n}\n")

io.open(DST, "w", encoding="utf-8", newline="").write(HEAD + "\n".join(body))
print("wrote", DST, os.path.getsize(DST), "bytes")

# -*- coding: utf-8 -*-
r"""
把 ui.html 转成可直接编译进寄生层的 UiModern.java。

为什么要转：寄生层是塞进原厂 APK 前置盲区的 dex，页面只能以 Java 常量的形式带进去，
不能放 assets / 不能读文件。

两个坑，生成器已经处理：
  1. Java class 常量池里单个字符串 UTF-8 不能超过 65535 字节 ⇒ 必须切块。
     这里按 22000 字节一块（中文多的页面留足余量）。
  2. HTML 里的 \ 和 " 必须转义，换行必须写成 \n（Java 字符串不能跨物理行）。

用法：  python gen_java.py
产物：  ../v2src/cn/workbuddy/r1client/UiModern.java
"""
import io
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "ui.html")
OUT = os.path.join(HERE, "..", "v2src", "cn", "workbuddy", "r1client", "UiModern.java")
CHUNK_BYTES = 22000          # 每块上限（字节，UTF-8）
NODE = r"C:\Users\shaoh\.workbuddy\binaries\node\versions\22.22.2-6\node.exe"


def esc_java(s: str) -> str:
    """Java 字符串字面量转义。"""
    s = s.replace("\\", "\\\\")
    s = s.replace('"', '\\"')
    s = s.replace("\r\n", "\n").replace("\r", "\n")
    s = s.replace("\n", "\\n")
    return s


def split_by_bytes(s: str, limit: int):
    """
    按 UTF-8 字节数切块。

    两个必须遵守的边界：
      · 不能切断多字节字符（否则写出半个汉字）
      · ★ 不能切断转义序列（\\" 或 \\n 是两个字符，切在中间会把反斜杠留在上一块
        末尾 ⇒ 上一块变成「未结束的字符串文字」，下一块开头多一个裸引号。
        实测踩过：块1 末尾停在 class=\\ 处，javac 报
        「未结束的字符串文字」+「非法字符: '\'」。

    所以这里把「反斜杠 + 后一个字符」当作不可分割的最小单元来累加。
    """
    parts, buf, n, i = [], [], 0, 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s):
            unit = s[i:i + 2]          # 转义序列，整体
        else:
            unit = s[i]
        b = len(unit.encode("utf-8"))
        if n + b > limit:
            parts.append("".join(buf))
            buf, n = [], 0
        buf.append(unit)
        n += b
        i += len(unit)
    if buf:
        parts.append("".join(buf))
    return parts


def check_js(html: str) -> bool:
    """把 <script> 内容抽出来给 node --check 验语法（抓手滑的语法错）。"""
    m = re.search(r"<script>(.*?)</script>", html, re.S)
    if not m:
        print("  [warn] 没找到 <script> 段")
        return True
    tmp = os.path.join(HERE, "_js_check.tmp.js")
    io.open(tmp, "w", encoding="utf-8").write(m.group(1))
    try:
        r = subprocess.run([NODE, "--check", tmp], capture_output=True, text=True)
        ok = (r.returncode == 0)
        if ok:
            print("   OK  JS 语法通过（%d 字符）" % len(m.group(1)))
        else:
            print("  [FAIL] JS 语法错误：")
            print((r.stderr or "").strip()[:1500])
        return ok
    except FileNotFoundError:
        print("  [warn] 找不到 node，跳过 JS 语法检查")
        return True
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass


def main():
    html = io.open(SRC, encoding="utf-8").read()
    print("源 ui.html：%d 字符 / %d 字节(UTF-8)" % (len(html), len(html.encode("utf-8"))))

    print("== [1/3] JS 语法检查 ==")
    if not check_js(html):
        print("中止：先修好 JS 语法")
        return 1

    print("== [2/3] 转义 + 分块 ==")
    esc = esc_java(html)
    parts = split_by_bytes(esc, CHUNK_BYTES)
    bad = 0
    for i, p in enumerate(parts):
        nb = len(p.encode("utf-8"))
        # 自检：块末尾不能停在裸反斜杠上（否则转义序列被切开了）
        tail = len(p) - len(p.rstrip("\\"))
        ok = (nb < 60000) and (tail == 0)
        if not ok:
            bad += 1
        print("   块 %d：%d 字符 / %d 字节  尾随反斜杠=%d  %s"
              % (i + 1, len(p), nb, tail, "OK" if ok else "!! 有问题"))
    if bad:
        print("中止：分块自检未通过")
        return 1

    print("== [3/3] 生成 Java ==")
    buf = []
    buf.append("package cn.workbuddy.r1client;")
    buf.append("")
    buf.append("/**")
    buf.append(" * 现代化控制台页面（暗色玻璃拟态，移动优先）。")
    buf.append(" *")
    buf.append(" * 【本文件由 ui/gen_java.py 自动生成，不要手改】")
    buf.append(" *   改页面请改 ui/ui.html，然后 python ui/gen_java.py 重新生成。")
    buf.append(" *")
    buf.append(" * 为什么要切成 String[] 再拼：Java class 常量池里单个字符串的")
    buf.append(" * UTF-8 字节数上限是 65535，中文页面很容易超 ⇒ 按块存，运行时拼。")
    buf.append(" *")
    buf.append(" * 数据源：")
    buf.append(" *   ① 自有  /api/*（状态、设置、日志、控制）—— 永远可用")
    buf.append(" *   ② 音箱  ws://<ip>:8080（播放进度、播放队列）—— 魔改 player 提供，")
    buf.append(" *      连不上时自动降级到 ①。player 的 WS 零自发推送 ⇒ 页面必须轮询。")
    buf.append(" *      协议里没有 seek ⇒ 进度条只读。")
    buf.append(" */")
    buf.append("public final class UiModern {")
    buf.append("")
    buf.append("    private static final String[] P = {")
    for p in parts:
        buf.append('        "' + p + '",')
    buf.append("    };")
    buf.append("")
    buf.append("    /** 完整页面 HTML。 */")
    buf.append("    public static String html() {")
    buf.append("        StringBuilder sb = new StringBuilder(%d);" % (len(html) + 64))
    buf.append("        for (int i = 0; i < P.length; i++) {")
    buf.append("            sb.append(P[i]);")
    buf.append("        }")
    buf.append("        return sb.toString();")
    buf.append("    }")
    buf.append("")
    buf.append("    private UiModern() {")
    buf.append("    }")
    buf.append("}")

    out = os.path.abspath(OUT)
    io.open(out, "w", encoding="utf-8", newline="\n").write("\n".join(buf) + "\n")
    print("   写出：%s" % out)
    print("   %d 字符 / %d 字节" % (sum(len(x) for x in buf), os.path.getsize(out)))

    # 顺便产出一份预览副本：强制 DEMO=true，双击就能看 UI（不用装机、不用接音箱）
    pv = os.path.join(HERE, "_preview.html")
    io.open(pv, "w", encoding="utf-8", newline="\n").write(
        html.replace('var DEMO = location.search.indexOf("demo=1") >= 0;',
                     'var DEMO = true;   /* 预览副本：强制演示模式 */'))
    print("   预览：%s（?demo=1 的等价物，直接双击打开）" % pv)
    return 0


if __name__ == "__main__":
    sys.exit(main())

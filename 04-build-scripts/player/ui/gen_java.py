# -*- coding: utf-8 -*-
r"""
把 player.html 转成可编译进注入层的 Ui.java。

为什么要转：注入层是塞进原厂 APK 前置盲区的 dex，页面只能以 Java 常量的形式带进去，
不能放 assets / 不能读文件。

两个坑：
  1. Java class 常量池里单个字符串 UTF-8 不能超过 65535 字节 ⇒ 必须切块（22000 一块）。
  2. HTML 里的 \ 和 " 要转义，换行要写成 \n；切块时不能切断转义序列（反斜杠+后一字符
     当整体处理）。

用法：  python gen_java.py
产物：  ../plsrc/cn/workbuddy/r1web/Ui.java
"""
import io
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "player.html")
OUT = os.path.join(HERE, "..", "plsrc", "cn", "workbuddy", "r1web", "Ui.java")
CHUNK_BYTES = 22000
NODE = r"C:\Users\shaoh\.workbuddy\binaries\node\versions\22.22.2-6\node.exe"


def esc_java(s):
    s = s.replace("\\", "\\\\")
    s = s.replace('"', '\\"')
    s = s.replace("\r\n", "\n").replace("\r", "\n")
    s = s.replace("\n", "\\n")
    return s


def split_by_bytes(s, limit):
    parts, buf, n, i = [], [], 0, 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s):
            unit = s[i:i + 2]
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


def check_js(html):
    m = re.search(r"<script>(.*?)</script>", html, re.S)
    if not m:
        print("  [warn] 没找到 <script> 段")
        return True
    tmp = os.path.join(HERE, "_js_check.tmp.js")
    io.open(tmp, "w", encoding="utf-8").write(m.group(1))
    try:
        r = subprocess.run([NODE, "--check", tmp], capture_output=True, text=True)
        ok = (r.returncode == 0)
        print("   OK  JS 语法通过（%d 字符）" % len(m.group(1)) if ok
              else "  [FAIL] JS 语法错误：\n" + (r.stderr or "").strip()[:1500])
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
    print("源 player.html：%d 字符 / %d 字节(UTF-8)" % (len(html), len(html.encode("utf-8"))))

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
    buf.append("package cn.workbuddy.r1web;")
    buf.append("")
    buf.append("/**")
    buf.append(" * player 侧控制台页面（暗色玻璃拟态，移动优先）。")
    buf.append(" *")
    buf.append(" * 【本文件由 ui/gen_java.py 自动生成，不要手改】")
    buf.append(" *   改页面请改 ui/player.html，然后 python ui/gen_java.py 重新生成。")
    buf.append(" *")
    buf.append(" * 切成 String[] 再拼的原因：Java class 常量池里单个字符串的 UTF-8")
    buf.append(" * 上限是 65535 字节，中文页面很容易超 ⇒ 按块存，运行时拼。")
    buf.append(" *")
    buf.append(" * ★ 本页只依赖 player 自己的 /api/*，小智进程被停用也照样能开。")
    buf.append(" */")
    buf.append("public final class Ui {")
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
    buf.append("    private Ui() {")
    buf.append("    }")
    buf.append("}")

    out = os.path.abspath(OUT)
    io.open(out, "w", encoding="utf-8", newline="\n").write("\n".join(buf) + "\n")
    print("   写出：%s" % out)
    print("   %d 字节" % os.path.getsize(out))

    pv = os.path.join(HERE, "_preview.html")
    io.open(pv, "w", encoding="utf-8", newline="\n").write(
        html.replace('var DEMO = location.search.indexOf("demo=1") >= 0;',
                     'var DEMO = true;   /* 预览副本：强制演示模式 */'))
    print("   预览：%s" % pv)
    return 0


if __name__ == "__main__":
    sys.exit(main())

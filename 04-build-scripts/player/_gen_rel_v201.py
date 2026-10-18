#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""由 build_pv22.sh 生成 player release 构建脚本 build_rel_V201.sh。

三件事：
  1. 中间产物编号 V: pv22 -> pv201（与测试线隔离，互不覆盖）；
  2. 产物名 xiaozhi_EchoService_pv22.apk -> XiaoZhi_EchoService_V2.0.1.apk；
  3. javac 之前把 Boot.VER 注入成 pl-2.0.1，构建完立刻还原源码树。
"""
import io

s = io.open('build_pv22.sh', encoding='utf-8').read()

HEAD = """#!/bin/bash
# =============================================================================
#  jp2-player RELEASE V2.0.1 —— 与测试版(pl-1.6.x)分开的第二条版本序列。
#
#  基线 = 测试版 pl-1.6.18（pv22），含：
#    · 歌词框收成 5 排 + 居中留白（pl-1.6.18）
#    · 封面时序 + 歌词框内滚动（pl-1.6.17）/ 封面 + 可折叠歌词（pl-1.6.16）
#    · 音量档位 0..15 / 拖动进度条 / 卡片信息去重（pl-1.6.12~1.6.15）
#    · 蓝牙可发现 60 秒临时窗口（pl-1.6.4）+ scan mode kick 全拔（pl-1.6.3）
#  仅在用户明确要求出 release 时才执行本脚本。
#
#  ★ 版本号策略（2026-10-18 明确）：
#      release 序列 pl-2.0.x   <- 只有本脚本产出，产物名 XiaoZhi_EchoService_V2.0.1.apk
#      测试序列   pl-1.6.x     <- 日常迭代（build_pv22.sh 等），只在开发目录流转
#    本脚本在 javac 之前把 Boot.VER 临时改成 pl-2.0.1，
#    d8 构建完立刻还原 —— 源码树永远回到测试序列，两条线不会串。
# =============================================================================
"""
if s.startswith('#!'):
    s = HEAD + s.split('\n', 1)[1]
else:
    s = HEAD + s

# 1) 中间产物编号隔离
assert 'V=pv22\n' in s, 'V anchor'
s = s.replace('V=pv22\n', 'V=pv201\n', 1)

# 2) 产物名
old_out = 'OUT="xiaozhi_EchoService_$V.apk"'
new_out = 'OUT="XiaoZhi_EchoService_V2.0.1.apk"'
assert old_out in s, 'OUT anchor'
s = s.replace(old_out, new_out, 1)

# 3) 版本断言
old_chk = """chk "$SRCS/Boot.java"    '版本 pl-1.6.18'      'pl-1.6.18'"""
new_chk = """chk "$SRCS/Boot.java"    '版本 pl-2.0.1 (release)'      'pl-2.0.1'"""
assert old_chk in s, 'chk anchor'
s = s.replace(old_chk, new_chk, 1)

# 4) javac 之前注入 + 之后还原
MARK = 'echo "=========== [1/8] 静态自检 ==========="'
assert MARK in s, 'javac anchor'
BS = chr(92) + 'n'
INJ = (
    'echo "=========== [0.5/8] RELEASE 版本号注入：VER -> pl-2.0.1 ==========="\n'
    '"$PY" - <<\'INJEOF\'\n'
    'import io, re\n'
    'p = \'plsrc/cn/workbuddy/r1web/Boot.java\'\n'
    'src = io.open(p, encoding=\'utf-8\').read()\n'
    "io.open('_VER_dev.bak', 'w', encoding='utf-8', newline='" + BS + "').write(src)\n"
    's = re.sub(r\'public static final String VER = "[^"]*"\',\n'
    '           \'public static final String VER = "pl-2.0.1"\', src)\n'
    'assert \'VER = "pl-2.0.1"\' in s, \'release 版本注入失败\'\n'
    "io.open(p, 'w', encoding='utf-8', newline='" + BS + "').write(s)\n"
    'print("  VER -> pl-2.0.1  (release 序列；测试序列 pl-1.6.x 保留在源码树)")\n'
    'INJEOF\n\n'
)
s = s.replace(MARK, INJ + MARK, 1)

s = s.rstrip('\n') + (
    '\n\necho "=========== [9/8] 源码树版本号还原（回到测试序列 pl-1.6.x） ==========="\n'
    '"$PY" - <<\'RSTEOF\'\n'
    'import io, os\n'
    'p = \'plsrc/cn/workbuddy/r1web/Boot.java\'\n'
    "io.open(p, 'w', encoding='utf-8', newline='" + BS + "').write(\n"
    "    io.open('_VER_dev.bak', encoding='utf-8').read())\n"
    'os.remove(\'_VER_dev.bak\')\n'
    'print("  源码树 VER 已还原为测试序列")\n'
    'RSTEOF\n\n'
    'echo "RELEASE DONE: XiaoZhi_EchoService_V2.0.1.apk"\n'
)

io.open('build_rel_V201.sh', 'w', encoding='utf-8', newline='\n').write(s)
print('written build_rel_V201.sh')

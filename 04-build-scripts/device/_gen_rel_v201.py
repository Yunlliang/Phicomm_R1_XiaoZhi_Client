#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""由 build_v144.sh 生成 release 构建脚本 build_rel_V201.sh。

只做四件事：
  1. 头部加 release 说明；
  2. 版本号断言 1.3.190 -> 2.0.1；
  3. 产物名 xiaozhi_Unisound_v144.apk -> XiaoZhi_Unisound_V2.0.1.apk；
  4. javac 之前注入 VERSION=2.0.1，janus 构建完之后还原源码树。
"""
import io

s = io.open('build_v144.sh', encoding='utf-8').read()

HEAD = """#!/bin/bash
# =============================================================================
#  jp2 RELEASE V2.0.1 —— 与测试版(1.3.x)分开的第二条版本序列。
#
#  基线 = 测试版 v1.3.190（jp2 v144），含：
#    · 歌词多候选 rid（v1.3.190，撞上 Live/伴奏版没词时继续试下一个）
#    · 总线回推同步换封面（v1.3.189）
#    · 180 首上限两栏（v1.3.180）/ 惰性地址 /r/（v1.3.179）/ 在线随机池（v1.3.178）
#    · 语音点歌在线优先（v1.3.177）/ online=1 无条件在线搜（v1.3.176）
#  仅在用户明确要求出 release 时才执行本脚本。
#
#  ★ 版本号策略（2026-10-18 明确）：
#      release 序列 2.0.x   <- 只有本脚本产出，产物名 XiaoZhi_Unisound_V2.0.1.apk
#      测试序列   1.3.x     <- 日常迭代（build_v144.sh 等），只在开发目录流转
#    本脚本在 javac 之前把 XzBootstrap.VERSION 临时改成 2.0.1，
#    janus 构建完立刻还原 —— 源码树永远回到测试序列，两条线不会串。
# =============================================================================
"""
if s.startswith('#!'):
    s = HEAD + s.split('\n', 1)[1]
else:
    s = HEAD + s

old_chk = 'chk(\'VERSION 1.3.190\', \'VERSION = "1.3.190"\', b)'
new_chk = 'chk(\'VERSION 2.0.1 (release)\', \'VERSION = "2.0.1"\', b)'
assert old_chk in s, 'chk anchor'
s = s.replace(old_chk, new_chk, 1)

n = s.count('xiaozhi_Unisound_v144')
assert n >= 4, 'out name count %d' % n
s = s.replace('xiaozhi_Unisound_v144', 'XiaoZhi_Unisound_V2.0.1')
print('产物改名处数:', n)

MARK = 'echo "== [1/6] javac =="'
assert MARK in s, 'javac anchor'
BS = chr(92) + 'n'          # 让内层 python 源码里是 \\n
INJ = (
    'echo "== [0.9/6] RELEASE 版本号注入：VERSION -> 2.0.1 =="\n'
    '"$PY" - <<\'INJEOF\'\n'
    'import io, re\n'
    'p = \'v2src/cn/workbuddy/r1client/XzBootstrap.java\'\n'
    'src = io.open(p, encoding=\'utf-8\').read()\n'
    'io.open(\'_VERSION_dev.bak\', \'w\', encoding=\'utf-8\', newline=' + "'" + BS + "'" + ').write(src)\n'
    's = re.sub(r\'public static final String VERSION = "[^"]*"\',\n'
    '           \'public static final String VERSION = "2.0.1"\', src)\n'
    'assert \'VERSION = "2.0.1"\' in s, \'release 版本注入失败\'\n'
    'io.open(p, \'w\', encoding=\'utf-8\', newline=' + "'" + BS + "'" + ').write(s)\n'
    'print(\'  VERSION -> 2.0.1  (release 序列；测试序列 1.3.x 保留在源码树)\')\n'
    'INJEOF\n\n'
)
s = s.replace(MARK, INJ + MARK, 1)

s = s.rstrip('\n') + (
    '\n\necho "== [7/6] 源码树版本号还原（回到测试序列 1.3.x） =="\n'
    '"$PY" - <<\'RSTEOF\'\n'
    'import io, os\n'
    'p = \'v2src/cn/workbuddy/r1client/XzBootstrap.java\'\n'
    "io.open(p, 'w', encoding='utf-8', newline='" + BS + "').write(\n"
    "    io.open('_VERSION_dev.bak', encoding='utf-8').read())\n"
    'os.remove(\'_VERSION_dev.bak\')\n'
    'print(\'  源码树 VERSION 已还原为测试序列\')\n'
    'RSTEOF\n\n'
    'echo "RELEASE DONE: XiaoZhi_Unisound_V2.0.1.apk"\n'
)

io.open('build_rel_V201.sh', 'w', encoding='utf-8', newline='\n').write(s)
print('written build_rel_V201.sh')

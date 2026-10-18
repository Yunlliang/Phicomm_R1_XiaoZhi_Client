#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
jp2 v1.3.79 —— 唤醒后直接 enterASR（跳过原厂提示音）。

根因：v1.3.70 (v25) 把 com/unisound/client/SpeechSynthesizer 的
playBuffer / playSynWav / playText / synthesizeText 全部短路，原厂 TTS 出口被断。
但 DefaultANTLaunchHandler 的状态机是：

    wakeup  ->  b() [playWakeupTips]  ->  TTS 播完
            ->  onTTSEventPlayingEnd() ->  ctx.enterASR()
            ->  doEnterASR() -> 开四麦录音 -> com/unisound/common/e.readData()

提示音永远不播 => onTTSEventPlayingEnd 永不到 => USC 永远不进 ASR => 不开麦
=> 寄生层的 UscTap 拿不到一帧 (fedBytes=0, maxRms=0)，唤醒后 CPU 只有 KWS 的个位数。

修法：b() 不再播提示音，直接 ctx.enterASR()。
doEnterASR() 内部第一件事就是 doCancelEngine()，与原流程等价且更快
（省掉等提示音播完的那 1 秒）。

幂等：已 patch 过则跳过。
"""
import io
import os
import sys

P = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                 'uni_smali', 'com', 'unisound', 'vui', 'handler', 'launch',
                 'DefaultANTLaunchHandler.smali')

MARK = 'XZ-PATCH v1.3.79'

NEW = u'''.method private b()V
    .registers 4

    # %s: 原厂 TTS 出口已被 v1.3.70 短路，唤醒提示音永远不会播完，
    # DefaultANTLaunchHandler 就永远等不到 onTTSEventPlayingEnd -> enterASR，
    # USC 于是永不进 ASR、永不开麦，寄生层 UscTap 拿不到一帧 PCM。
    # 这里不播提示音（寄生层自己有 ack cue），直接进 ASR。
    const-string v0, "DefaultANTLaunchHandler"

    const-string v1, "playWakeupTips -> XZ: tip muted, enterASR now"

    invoke-static {v0, v1}, Lcom/unisound/vui/util/LogMgr;->d(Ljava/lang/String;Ljava/lang/String;)V

    iget-object v0, p0, Lcom/unisound/vui/handler/launch/DefaultANTLaunchHandler;->h:Lcom/unisound/vui/engine/ANTHandlerContext;

    invoke-interface {v0}, Lcom/unisound/vui/engine/ANTHandlerContext;->enterASR()V

    return-void
.end method
''' % MARK


def main():
    if not os.path.exists(P):
        print('MISSING: ' + P)
        return 2
    src = io.open(P, encoding='utf-8').read()
    if MARK in src:
        print('already patched: ' + P)
        return 0
    lines = src.split('\n')
    try:
        i = lines.index('.method private b()V')
    except ValueError:
        print('ERROR: .method private b()V not found')
        return 3
    j = i
    while j < len(lines) and lines[j].strip() != '.end method':
        j += 1
    if j >= len(lines):
        print('ERROR: unterminated b()')
        return 4
    old = '\n'.join(lines[i:j + 1])
    if 'playWakeupTips' not in old:
        print('ERROR: b() does not look like playWakeupTips, abort')
        return 5
    out = '\n'.join(lines[:i]) + '\n' + NEW + '\n'.join(lines[j + 1:])
    io.open(P, 'w', encoding='utf-8', newline='\n').write(out)
    print('patched: %s (replaced %d lines)' % (P, j - i + 1))
    return 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
v1.3.97：关掉原厂 device 的启动音乐 / 网络状态提示音。

位置：com/phicomm/speaker/device/custom/handler/PhicommInitializeHandler.smali
原始逻辑（还原成 Java 大概是这样）：

    if (!NetUtil.isNetworkConnected(context)) {
        UniMediaPlayer.getInstance().playBeepSound("", 0x7f040001, listener, false);
        return;                       // 无网络提示音
    }
    UniMediaPlayer.getInstance().playBeepSound(0x7f040001);   // ★ 联网时的启动音乐
    initPhicommBusiness();

0x7f040001 = 原厂 raw 里的音频资源。arsc 不可改（签名条目），所以只能短路调用点。

做法：把两处 invoke-virtual 注释成空操作，保留寄存器，不动 UniMediaPlayer 本身
      （playBeepSound 其他场景仍可用）。
幂等：用标记 #PATCH_BOOTSOUND 判断，重复运行不会叠加。
"""

import io
import os
import sys

TARGET = ('C:/Users/shaoh/WorkBuddy/R1/janus_pandora2/uni_smali/com/phicomm/speaker/device/'
          'custom/handler/PhicommInitializeHandler.smali')

CALLS = [
    '    invoke-virtual {v2, v3, v6, v4, v5}, Lcom/unisound/vui/common/media/UniMediaPlayer;->playBeepSound(Ljava/lang/String;ILcom/unisound/vui/common/media/IMediaPlayerStateListener;Z)V',
    '    invoke-virtual {v2, v6}, Lcom/unisound/vui/common/media/UniMediaPlayer;->playBeepSound(I)V',
]


def main():
    if not os.path.exists(TARGET):
        print('!! target missing:', TARGET)
        return 1
    src = io.open(TARGET, 'r', encoding='utf-8').read()

    if '#PATCH_BOOTSOUND' in src:
        print('already patched, nothing to do')
        return 0

    lines = src.split('\n')
    hit = [0] * len(CALLS)
    out = []
    # 额外保险：只在这些行之后（no-op 化）也允许跨~15 行内的 invoke-virtual 变体
    for ln in lines:
        for i, c in enumerate(CALLS):
            if ln.rstrip('\r') == c:
                out.append('    # PATCH_BOOTSOUND ' + ln.strip())
                hit[i] += 1
                break
        else:
            out.append(ln)

    for i, c in enumerate(CALLS):
        print(f'call[{i}] replaced x{hit[i]}')
    miss = [i for i, h in enumerate(hit) if h != 1]
    if miss:
        print('!! anchor miss ->', miss, '(内容可能变了，请核对)')
        return 2

    dst = '\n'.join(out)
    bak = TARGET + '.orig_boot'
    if not os.path.exists(bak):
        io.open(bak, 'w', encoding='utf-8', newline='').write(src)
    io.open(TARGET, 'w', encoding='utf-8', newline='').write(dst)
    print('patched ->', TARGET)
    print('backup  ->', bak)
    return 0


if __name__ == '__main__':
    sys.exit(main())

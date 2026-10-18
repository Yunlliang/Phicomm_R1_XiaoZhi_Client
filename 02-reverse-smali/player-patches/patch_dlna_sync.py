#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
【原厂 smali 补丁】DLNA 推流同步：DlnaPlayer2.onPlayCommand 插桩。

现象：手机 DLNA 推流到音箱，web 控制台「正在播放」不同步（显示未播放/本地队列）。

根因（扒代码实锤）：手机 AVTransport Play → :DlnaPlayer 进程的
DMRCenter.onRenderPlay → DlnaPlayer2.onPlayCommand(model) →
mPlayerEngineImpl.playMedia(model) —— 音频走 :DlnaPlayer **自己的播放引擎**
（MusicPlayEngineImpl / ExoPlayer），主进程控制台的 EasyPlayer 队列
跟这事毫无关系，所以永远同步不了。

改法：在 onPlayCommand 开头插一行（只用 p0/p1 参数寄存器，不占本地寄存器）：
    invoke-static {p0, p1},
        Lcn/workbuddy/r1web/DlnaHook;->onPush(Object;Object)V
DlnaHook 把 model 里的 title/artist/url 转成全局广播 r1web.dlna.push 发给
主进程（Ext 接收 → /api/status 的 music.ext；播放与否另由 AIDL isPlaying
问真实引擎）。幂等，带标记注释。
"""
import io
import os
import sys

MARK = "# r1web: onPlayCommand 插桩 —— DLNA 推流曲目广播给主进程控制台"
CALL = ("invoke-static {p0, p1}, "
        "Lcn/workbuddy/r1web/DlnaHook;->onPush"
        "(Ljava/lang/Object;Ljava/lang/Object;)V")
SIG = (".method public onPlayCommand"
       "(Lcom/phicomm/speaker/player/dlna/center/DlnaMediaModel;)V")


def patch(path):
    if not os.path.isfile(path):
        print("skip: 没有 %s" % path)
        return 0
    src = io.open(path, encoding="utf-8", newline="").read()
    if MARK in src:
        print("already patched: %s" % os.path.basename(path))
        return 0
    i = src.find(SIG)
    if i < 0:
        print("!! 没找到 onPlayCommand，原厂 smali 结构变了？")
        return 1
    # 插在 .prologue 之后（prologue 可有可无，没有就插 .registers 那行之后）
    nl = "\r\n" if "\r\n" in src else "\n"
    p = src.find(".prologue", i)
    if p >= 0 and p < src.find(".end method", i):
        p += len(".prologue")
        while p < len(src) and src[p] in "\r\n":
            p += 1
    else:
        p = src.find(nl, i) + len(nl)      # .method 行尾
    ins = nl.join(["    " + MARK, "    " + CALL, ""])
    out = src[:p] + ins + src[p:]
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("patched(onPlayCommand): %s" % os.path.basename(path))
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: patch_dlna_sync.py <DlnaPlayer2.smali>")
        sys.exit(2)
    sys.exit(patch(sys.argv[1:][0]))

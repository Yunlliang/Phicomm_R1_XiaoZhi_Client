# -*- coding: utf-8 -*-
r"""
在原厂 smali 树上打两个钩子（幂等：已经打过就跳过）。

① Common.onCreate()          → Boot.attach(Context)
   Application 入口，比 EchoService.onCreate 早；attach 内部只登记，3.5s 后才起 web。

② EasyPlayer.play(PlayRequestInfo,Z) → Boot.onPlay(PlayRequestInfo)
   小智推来的播放列表从这里进 player，抓住它 = 拿到「队列真相」，顺序与 player 一致。

③ EasyPlayer.playIndex(I,Z)  → Boot.onIndex(int)
   跳播 / 自动切歌只换位置，不动列表 ⇒ 只记下标。

用法：  python patch_smali.py <smali树根目录>
"""
import io
import os
import sys

HOOK_ATTACH = "    invoke-static {p0}, Lcn/workbuddy/r1web/Boot;->attach(Landroid/content/Context;)V"
HOOK_PLAY = ("    invoke-static {p1}, "
             "Lcn/workbuddy/r1web/Boot;->onPlay(Lcom/phicomm/speaker/player/model/PlayRequestInfo;)V")
HOOK_IDX = "    invoke-static {p1}, Lcn/workbuddy/r1web/Boot;->onIndex(I)V"

COMMON = "com/phicomm/speaker/player/util/Common.smali"
EASY = "com/phicomm/speaker/player/model/EasyPlayer.smali"


def read(p):
    return io.open(p, encoding="utf-8").read()


def write(p, s):
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)


def insert_after_prologue(txt, method_sig, hook, tag):
    """在指定方法的 .prologue 之后插入一行 invoke-static。"""
    key = ".method " + method_sig
    i = txt.find(key)
    if i < 0:
        print("  [FAIL] 找不到方法 %s" % method_sig)
        return None
    if txt.find(hook.strip(), i, i + 4000) >= 0:
        print("  [skip] %s 钩子已存在" % tag)
        return txt
    # 方法头之后第一个 .prologue
    j = txt.find(".prologue", i)
    if j < 0:
        print("  [FAIL] %s 里没有 .prologue" % method_sig)
        return None
    eol = txt.find("\n", j)
    if eol < 0:
        return None
    return txt[:eol + 1] + hook + "\n" + txt[eol + 1:]


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "smali_build"
    print("== 打钩子：%s ==" % root)

    # ① Common.onCreate：插在 mContext 赋值之后
    p = os.path.join(root, COMMON)
    if not os.path.exists(p):
        print("  [FAIL] 没有 %s" % COMMON)
        return 1
    s = read(p)
    if HOOK_ATTACH.strip() in s:
        print("  [skip] attach 钩子已存在")
    else:
        anchor = ("    iput-object v3, p0, "
                  "Lcom/phicomm/speaker/player/util/Common;->mContext:Landroid/content/Context;")
        k = s.find(anchor)
        if k < 0:
            print("  [FAIL] Common.onCreate 里找不到 mContext 赋值那行")
            return 1
        eol = s.find("\n", k)
        s = s[:eol + 1] + HOOK_ATTACH + "\n" + s[eol + 1:]
        write(p, s)
        print("  [OK] Common.onCreate -> Boot.attach")

    # ② EasyPlayer.play
    p = os.path.join(root, EASY)
    if not os.path.exists(p):
        print("  [FAIL] 没有 %s" % EASY)
        return 1
    s = read(p)
    r = insert_after_prologue(s, "public play(Lcom/phicomm/speaker/player/model/PlayRequestInfo;Z)Z",
                              HOOK_PLAY, "onPlay")
    if r is None:
        return 1
    s = r
    # ③ EasyPlayer.playIndex
    r = insert_after_prologue(s, "public playIndex(IZ)Z", HOOK_IDX, "onIndex")
    if r is None:
        return 1
    s = r
    write(p, s)
    print("  [OK] EasyPlayer.play -> Boot.onPlay / playIndex -> Boot.onIndex")

    # 复核
    s = read(p)
    ok_play = HOOK_PLAY.strip() in s
    ok_idx = HOOK_IDX.strip() in s
    print("  复核：onPlay=%s onIndex=%s" % (ok_play, ok_idx))
    if not (ok_play and ok_idx):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

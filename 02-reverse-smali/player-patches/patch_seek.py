# -*- coding: utf-8 -*-
r"""
打「拖动进度条」需要的 seek 通路（幂等：打过就跳过）。

<h3>为什么要改 smali</h3>
原厂 player 的对外控制面是 AIDL 接口 {@code com.phicomm.speaker.player.IPlayer}，
里面**没有 seek**：Player 接口只有 start / pause / play / playIndex / playNext /
playPre / stop / startFadeIn / stopFadeOut / startServer / stopServer …
而真正在放歌的是 {@code MultiMediaPlayer}，它**有** {@code public declared-synchronized
seek(I)I}（内部就是 MediaPlayer.seekTo）—— 但它在 <b>:NetPlayer 进程</b>里，
主进程（8080 跑的地方）反射够不着。

<h3>怎么接（全部是**新增**，旧方法一行不动）</h3>
<pre>
  IPlayer$Stub.onTransact   新增事务码 0x15 → invoke-virtual p0.seek(I)I
  IPlayer$Stub              新增 abstract seek(I)I（让上面那句能解析到）
  PlayerBinderWrapper$2     实现 seek(I)I → 委托 this$0.mPlayer（AbsPlayer）
  AbsPlayer                 新增 seek(I)I，默认返回 -1（蓝牙/推流这类不支持拖）
  NetPlayer4                覆写 seek(I)I → mPlayer.seek(whereto)
</pre>
客户端那一半在 {@code PCtl.seekTo()}：反射取出 RemotePlayer.mIPlayer 的
{@code mRemote}(IBinder) 自己 transact，这样就不必再动 IPlayer 接口和
IPlayer$Stub$Proxy（少改两个文件 = 少两个雷）。

用法：  python patch_seek.py <smali树根目录>
"""
import io
import os
import sys

STUB = "com/phicomm/speaker/player/IPlayer$Stub.smali"
WRAP = "com/phicomm/speaker/player/PlayerBinderWrapper$2.smali"
ABS = "com/phicomm/speaker/player/model/AbsPlayer.smali"
NET = "com/phicomm/speaker/player/model/NetPlayer4.smali"

MARK = "r1web: seek"

# ① Stub 上的事务码常量（紧跟 unregisterCallback=0x14 之后）
F_SEEK = ".field static final TRANSACTION_seek:I = 0x15\n"
A_UNREG = ".field static final TRANSACTION_unregisterCallback:I = 0x14\n"

# ② sparse-switch 分派表新增一项
A_SW_14 = "        0x14 -> :sswitch_1da\n"
SW_SEEK = "        0x15 -> :sswitch_seek\n"

# ③ 分支体（插在分派表之前）
BRANCH = (
    "    :sswitch_seek\n"
    "    const-string v6, \"com.phicomm.speaker.player.IPlayer\"\n"
    "\n"
    "    invoke-virtual {p2, v6}, Landroid/os/Parcel;->enforceInterface(Ljava/lang/String;)V\n"
    "\n"
    "    invoke-virtual {p2}, Landroid/os/Parcel;->readInt()I\n"
    "\n"
    "    move-result v0\n"
    "\n"
    "    .local v0, \"_arg0\":I\n"
    "    invoke-virtual {p0, v0}, Lcom/phicomm/speaker/player/IPlayer$Stub;->seek(I)I\n"
    "\n"
    "    move-result v3\n"
    "\n"
    "    invoke-virtual {p3}, Landroid/os/Parcel;->writeNoException()V\n"
    "\n"
    "    invoke-virtual {p3, v3}, Landroid/os/Parcel;->writeInt(I)V\n"
    "\n"
    "    goto/16 :goto_9\n"
    "\n"
)
A_SWDATA = "    .line 40\n    nop\n\n    :sswitch_data_1f0"

# ④ IPlayer$Stub 的抽象声明（新增在文件末尾）
M_ABSTRACT = (
    "\n"
    ".method public abstract seek(I)I\n"
    "    .annotation system Ldalvik/annotation/Throws;\n"
    "        value = {\n"
    "            Landroid/os/RemoteException;\n"
    "        }\n"
    "    .end annotation\n"
    ".end method\n"
)

# ⑤ PlayerBinderWrapper$2 的实现：委托给 AbsPlayer
M_WRAP = (
    "\n"
    "# " + MARK + "\n"
    ".method public declared-synchronized seek(I)I\n"
    "    .registers 5\n"
    "    .param p1, \"whereto\"    # I\n"
    "    .annotation system Ldalvik/annotation/Throws;\n"
    "        value = {\n"
    "            Landroid/os/RemoteException;\n"
    "        }\n"
    "    .end annotation\n"
    "\n"
    "    .prologue\n"
    "    .line 300\n"
    "    monitor-enter p0\n"
    "\n"
    "    .line 301\n"
    "    :try_start_1\n"
    "    iget-object v1, p0, Lcom/phicomm/speaker/player/PlayerBinderWrapper$2;->this$0:Lcom/phicomm/speaker/player/PlayerBinderWrapper;\n"
    "\n"
    "    invoke-static {v1}, Lcom/phicomm/speaker/player/PlayerBinderWrapper;->access$000(Lcom/phicomm/speaker/player/PlayerBinderWrapper;)Lcom/phicomm/speaker/player/model/AbsPlayer;\n"
    "\n"
    "    move-result-object v0\n"
    "\n"
    "    .line 302\n"
    "    .local v0, \"pl\":Lcom/phicomm/speaker/player/model/AbsPlayer;\n"
    "    if-eqz v0, :cond_15\n"
    "\n"
    "    .line 303\n"
    "    invoke-virtual {v0, p1}, Lcom/phicomm/speaker/player/model/AbsPlayer;->seek(I)I\n"
    "\n"
    "    move-result v1\n"
    "    :try_end_11\n"
    "    .catchall {:try_start_1 .. :try_end_11} :catchall_17\n"
    "\n"
    "    .line 307\n"
    "    :goto_12\n"
    "    monitor-exit p0\n"
    "\n"
    "    return v1\n"
    "\n"
    "    .line 305\n"
    "    :cond_15\n"
    "    const/4 v1, -0x1\n"
    "\n"
    "    goto :goto_12\n"
    "\n"
    "    .line 301\n"
    "    .end local v0    # \"pl\":Lcom/phicomm/speaker/player/model/AbsPlayer;\n"
    "    :catchall_17\n"
    "    move-exception v2\n"
    "\n"
    "    monitor-exit p0\n"
    "\n"
    "    throw v2\n"
    ".end method\n"
)

# ⑥ AbsPlayer 的默认实现（不支持拖的源走这里）
M_ABS = (
    "\n"
    "# " + MARK + "\n"
    ".method public declared-synchronized seek(I)I\n"
    "    .registers 3\n"
    "    .param p1, \"whereto\"    # I\n"
    "\n"
    "    .prologue\n"
    "    .line 300\n"
    "    monitor-enter p0\n"
    "\n"
    "    const/4 v0, -0x1\n"
    "\n"
    "    monitor-exit p0\n"
    "\n"
    "    return v0\n"
    ".end method\n"
)

# ⑦ NetPlayer4 的覆写：真正落到 MultiMediaPlayer -> MediaPlayer.seekTo
M_NET = (
    "\n"
    "# " + MARK + "\n"
    ".method public declared-synchronized seek(I)I\n"
    "    .registers 5\n"
    "    .param p1, \"whereto\"    # I\n"
    "\n"
    "    .prologue\n"
    "    .line 1000\n"
    "    monitor-enter p0\n"
    "\n"
    "    .line 1001\n"
    "    :try_start_1\n"
    "    iget-object v1, p0, Lcom/phicomm/speaker/player/model/NetPlayer4;->mPlayer:Lcom/phicomm/speaker/player/model/MultiMediaPlayer;\n"
    "\n"
    "    .line 1002\n"
    "    .local v1, \"mp\":Lcom/phicomm/speaker/player/model/MultiMediaPlayer;\n"
    "    if-eqz v1, :cond_13\n"
    "\n"
    "    .line 1003\n"
    "    invoke-virtual {v1, p1}, Lcom/phicomm/speaker/player/model/MultiMediaPlayer;->seek(I)I\n"
    "\n"
    "    move-result v0\n"
    "    :try_end_d\n"
    "    .catchall {:try_start_1 .. :try_end_d} :catchall_15\n"
    "\n"
    "    .line 1007\n"
    "    :goto_e\n"
    "    monitor-exit p0\n"
    "\n"
    "    return v0\n"
    "\n"
    "    .line 1005\n"
    "    :cond_13\n"
    "    const/4 v0, -0x1\n"
    "\n"
    "    goto :goto_e\n"
    "\n"
    "    .line 1001\n"
    "    .end local v1    # \"mp\":Lcom/phicomm/speaker/player/model/MultiMediaPlayer;\n"
    "    :catchall_15\n"
    "    move-exception v2\n"
    "\n"
    "    monitor-exit p0\n"
    "\n"
    "    throw v2\n"
    ".end method\n"
)


def read(p):
    return io.open(p, encoding="utf-8").read()


def write(p, s):
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)


def ins_after(s, anchor, add, tag):
    """在 anchor 之后插入 add；找不到返回 None。"""
    if add.strip() in s:
        print("  [skip] %s 已存在" % tag)
        return s
    k = s.find(anchor)
    if k < 0:
        print("  [FAIL] 找不到锚点：%s" % tag)
        return None
    j = k + len(anchor)
    return s[:j] + add + s[j:]


def ins_before(s, anchor, add, tag):
    if add.strip() in s:
        print("  [skip] %s 已存在" % tag)
        return s
    k = s.find(anchor)
    if k < 0:
        print("  [FAIL] 找不到锚点：%s" % tag)
        return None
    return s[:k] + add + s[k:]


def append_method(path, body, tag):
    if not os.path.exists(path):
        print("  [FAIL] 没有 %s" % path)
        return 1
    s = read(path)
    if MARK in s:
        print("  [skip] %s 已存在" % tag)
        return 0
    if not s.endswith("\n"):
        s += "\n"
    write(path, s + body)
    print("  [OK] %s" % tag)
    return 0


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "smali_build"
    print("== 打 seek 通路：%s ==" % root)
    rc = 0

    # ---- IPlayer$Stub：常量 + 分派表 + 分支体 + 抽象声明 ----
    p = os.path.join(root, STUB)
    if not os.path.exists(p):
        print("  [FAIL] 没有 %s" % STUB)
        return 1
    s = read(p)
    if MARK in s or ":sswitch_seek" in s:
        print("  [skip] IPlayer$Stub 已打过 seek")
    else:
        r = ins_after(s, A_UNREG, F_SEEK, "TRANSACTION_seek 常量")
        if r is None:
            return 1
        s = r
        r = ins_after(s, A_SW_14, SW_SEEK, "分派表 0x15")
        if r is None:
            return 1
        s = r
        r = ins_before(s, A_SWDATA, BRANCH, "seek 分支体")
        if r is None:
            return 1
        s = r
        s = s.rstrip("\n") + "\n" + M_ABSTRACT
        write(p, s)
        print("  [OK] IPlayer$Stub 事务 0x15 -> seek")

    # ---- PlayerBinderWrapper$2：实现 ----
    rc |= append_method(os.path.join(root, WRAP), M_WRAP, "PlayerBinderWrapper$2.seek")
    # ---- AbsPlayer：默认 -1 ----
    rc |= append_method(os.path.join(root, ABS), M_ABS, "AbsPlayer.seek 默认实现")
    # ---- NetPlayer4：真正 seek ----
    rc |= append_method(os.path.join(root, NET), M_NET, "NetPlayer4.seek -> MultiMediaPlayer")

    # 复核
    for f, needle, tag in (
        (STUB, ":sswitch_seek", "Stub 分派"),
        (WRAP, ".method public declared-synchronized seek(I)I", "Wrapper 实现"),
        (ABS, ".method public declared-synchronized seek(I)I", "AbsPlayer 默认"),
        (NET, "MultiMediaPlayer;->seek(I)I", "NetPlayer4 落点"),
    ):
        fp = os.path.join(root, f)
        ok = os.path.exists(fp) and needle in read(fp)
        print("  复核：%-16s %s" % (tag, "OK" if ok else "FAIL"))
        if not ok:
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
【原厂 smali 补丁】蓝牙「只有可被搜到时才能连」= 原厂把 scan mode 强设 SCAN_MODE_NONE(20)。

现象：手机一连上音箱后，就算「可被搜到」关着、蓝牙开着，
已经配对的手机也再也连不上 —— 必须把「可被搜到」重新打开（setScanMode(23)）才连得上。

根因（扒 stock BluetoothPlayer 实锤，两处强设 SCAN_MODE_NONE = 0x14 = 20）：

  ① BluetoothPlayer$2.run() —— 收 a2dp.sink.profile CONNECTION_STATE_CHANGED 后
     post 出来，pswitch_9d（state 2 = CONNECTED）分支里：
         if (Utils.isSystemInNormalMode(ctx)) { ...; setScanMode(0x14); }
     isSystemInNormalMode = SysPrivateManager.getSysMode()==0，音箱常态恒真
     ⇒ **手机每连上一次，scan mode 立刻被打成「不可发现也不可连」**。
     之后我们无论怎么设 21/23，一次连接事件就被打回 20，已配对手机直接失联。

  ② BluetoothPlayer.disconnectDevice() —— 同样是 normal mode 下 setScanMode(0x14)。
     实测全 smali 树无人调用（dead code），一并拔掉防患。

本 ROM 的 scan mode 常量口径（与原厂编译进去的字面量互证，20/21/23）：
  20 = SCAN_MODE_NONE                  不可发现、也不可连
  21 = SCAN_MODE_CONNECTABLE           可连、不可发现（已配对手机连这个档）
  23 = SCAN_MODE_CONNECTABLE_DISCOVERABLE  可连 + 可被搜到
原厂 startServer() 设 23（起来等服务），连上后立刻打回 20 —— 设计上只允许
「刚开服务那一下」可连。我们靠 BluetoothAdapter.setScanMode 手动管理可发现性，
这个 kick 不拔掉，用户的选择永远活不过一次连接。

改法：两处 setScanMode(0x14) 调用整行换标记注释（其余状态流转一律不动）。
幂等，带标记。用法：
  patch_bt_scan.py <BluetoothPlayer.smali> [<BluetoothPlayer$2.smali> ...]
"""
import io
import os
import sys

MARK = "# r1web: 不再强设 SCAN_MODE_NONE（原厂在这里把 scan mode 打回 20，连已配对手机都拒）"
# 两处 kick 前面的日志串，用来锚定（两个文件里同一句）
ANCHOR = "BluetoothPlayerInNormalMode set SCAN_MODE_NONE"
# 被替换的调用行特征（两个文件里各自的寄存器不同，只匹配方法签名部分）
CALL = "Landroid/bluetooth/BluetoothAdapter;->setScanMode(I)Z"


def patch_one(path):
    if not os.path.isfile(path):
        print("skip: 没有 %s" % path)
        return 0
    src = io.open(path, encoding="utf-8", newline="").read()
    base = os.path.basename(path)
    if MARK in src:
        print("already patched(scan kick): %s" % base)
        return 0
    # 从日志锚点往后找第一处 setScanMode 调用，整行替换成注释
    i = src.find(ANCHOR)
    if i < 0:
        print("!! %s 没找到锚点日志串，原厂结构变了？" % base)
        return 1
    j = src.find(CALL, i)
    if j < 0:
        print("!! %s 锚点后没有 setScanMode 调用，原厂结构变了？" % base)
        return 1
    ls = src.rfind("\n", 0, j) + 1          # 调用行行首
    le = src.find("\n", j)                  # 调用行行尾（不含换行符）
    if le < 0:
        le = len(src)
    out = src[:ls] + "    " + MARK + src[le:]
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("patched(scan kick): %s" % base)
    return 0


def main(paths):
    rc = 0
    for p in paths:
        rc |= patch_one(p)
    return rc


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: patch_bt_scan.py <BluetoothPlayer.smali> "
              "[<BluetoothPlayer$2.smali> ...]")
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))

#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
【原厂 smali 补丁】蓝牙「连上马上断开」= 两处主动 kick，全拔掉。

现象：手机连上音箱（A2DP sink），几秒后自己断开。

根因（扒 BluetoothPlayer 全家实锤，两个补丁点）：

① BluetoothPlayer.bootInit() —— A2DP proxy 拿到手那一刻（onServiceConnected
   回调）把 getConnectedDevices() 全部 disconnect。bootInit 只在那一处被调。

② BluetoothPlayer$2.run() —— 这才是**主凶**。它由 mBlueToothReceiver 收到
   a2dp.sink.profile CONNECTION_STATE_CHANGED 后 post 出来（外层方法是
   processBluetoothA2dpConnectChanged），里面按 mConnectState 分流：
       case 0/3: if (isSystemInNormalMode) sink.disconnect(设备)   ← 新连接直接踢
       case 2  : if (!设备.equals(mConnectedDevice)) sink.disconnect(设备)
   音箱常态 mConnectState==0，所以手机一连上就命中 case 0 的 kick。

改法：
  · BluetoothPlayer.smali     —— bootInit() 方法体换 return-void
  · BluetoothPlayer$2.smali   —— 两处 sink.disconnect(...) 调用换成注释（保留
                                  case 1 的 mConnectedDevice/SCAN_MODE/media
                                  状态流转，只去掉踢人）
均幂等，带标记注释。用法：patch_bt_hold.py <BluetoothPlayer.smali> [<BluetoothPlayer$2.smali> ...]
"""
import io
import os
import re
import sys

MARK_BOOT = "# r1web: bootInit 已空掉（原厂会在这里把所有已连接 A2DP 断开）"
MARK_KICK = "# r1web: 去掉 disconnect（原厂在这里把连上的手机踢掉）"
# BluetoothPlayer$2.run() 里两处踢人调用，smali 里格式固定
KICK = ("invoke-virtual {v1, v0}, "
        "Landroid/bluetooth/BluetoothA2dpSink;->disconnect"
        "(Landroid/bluetooth/BluetoothDevice;)Z")


def patch_boot(path):
    """bootInit() 整方法体换成 return-void。"""
    if not os.path.isfile(path):
        print("skip: 没有 %s" % path)
        return 0
    src = io.open(path, encoding="utf-8", newline="").read()
    if MARK_BOOT in src:
        print("already patched(bootInit): %s" % os.path.basename(path))
        return 0
    # ★ smali 是 CRLF，别用带 \n 的正则去匹配行尾（第一次就是这么漏掉的）
    i = src.find(".method private bootInit()V")
    if i < 0:
        print("!! 没找到 bootInit，原厂 smali 结构变了？")
        return 1
    j = src.find(".end method", i)
    if j < 0:
        print("!! bootInit 没有 .end method")
        return 1
    j += len(".end method")
    nl = "\r\n" if "\r\n" in src else "\n"
    regs = re.search(r"\.registers (\d+)", src[i:j])
    reg = "    .registers " + (regs.group(1) if regs else "1")
    new = nl.join([
        ".method private bootInit()V",
        reg,
        "",
        "    .prologue",
        "    " + MARK_BOOT,
        "    return-void",
        ".end method",
    ])
    out = src[:i] + new + src[j:]
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("patched(bootInit): %s" % os.path.basename(path))
    return 0


def patch_kick(path):
    """BluetoothPlayer$2.run() 里两处 sink.disconnect 调用换成注释。"""
    if not os.path.isfile(path):
        print("skip: 没有 %s" % path)
        return 0
    src = io.open(path, encoding="utf-8", newline="").read()
    if MARK_KICK not in src and KICK not in src:
        print("!! %s 里既没有标记也没有 disconnect 调用，结构变了？"
              % os.path.basename(path))
        return 0
    if MARK_KICK in src:
        print("already patched(kick): %s" % os.path.basename(path))
        return 0
    nl = "\r\n" if "\r\n" in src else "\n"
    n = src.count(KICK)
    if n == 0:
        print("!! %s 没找到 disconnect 调用" % os.path.basename(path))
        return 1
    out = src.replace(KICK, "    " + MARK_KICK)
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    print("patched(kick): %s 干掉 %d 处" % (os.path.basename(path), n))
    return 0


def main(paths):
    rc = 0
    for p in paths:
        base = os.path.basename(p)
        if base.startswith("BluetoothPlayer$"):
            rc |= patch_kick(p)
        else:
            rc |= patch_boot(p)
    return rc


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: patch_bt_hold.py <BluetoothPlayer.smali> "
              "[<BluetoothPlayer$2.smali> ...]")
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))

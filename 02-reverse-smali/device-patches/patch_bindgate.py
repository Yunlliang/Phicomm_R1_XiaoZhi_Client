# -*- coding: utf-8 -*-
"""
jp2 v1.3.125：拆掉原厂「未绑定 / 无网络 / 待升级」三道唤醒闸门。

现象（2026-10-12 晚，用户反馈）
    「不是上行轻的问题，连拾音灯都没亮，正常小讯的 KWS 拾音灯是会亮的」
    「没开实时打断都不出声」

    日志二分（关键证据）
        正常期 diag/lc2.txt（17:39–20:12）：
            PhicommDataStatisticHandler: sendMainWakeupStatisticToPhicomm
            PhicommLightListener : [----------onWakeupSuccess---------- 处理前角度 = 160]
            DeviceCenterHandler  : [ lineNumber =304 ] Continues onWakeupEventRecognitionSuccess
            PhicommLightListener : [----------onWakeupSuccess---------- 处理后角度 ... light index : 8]
            SimpleUserEvent      : mark true,hanler:DefaultANTLaunchHandler
            DefaultANTLaunchHandler: playWakeupTips -> XZ: tip muted, enterASR deferred   ← 我方入口
        故障期 diag/live.txt（21:10–22:00）：
            同一条唤醒 -> sendMainWakeupStatisticToPhicomm 之后直接
            DefaultANTLaunchHandler: userEventTriggered---doFinishAllInterrupt
            PhicommLightListener : [----------onRecognizeStart----------]      （22 次 = 唤醒次数）
            NativeANTEngine      : doPlayTTS:音箱未绑定，请根据APP提示添加音箱    （22 次）
            NativeANTEngine      : doEnterWakeup isIdle:false
            → tip muted 0 次 / onWakeupSuccess 0 次 / Continues 0 次

根因（三段链，全部实证）
    ① 绑定状态读不出来
        UserPerferenceUtil.getDeviceBindState() 读 SP "unicar_user_settings" 的 "isBinded"，
        默认 false。设备上该文件**不存在**，而 defaultSp.xml 里**没有 isBinded 键**
        （原厂「一键绑定音箱」工具正是往 defaultSp.xml 插 isBinded=true —— 见
          一键绑定音箱、清除闹铃、修改发音人/运行这个.bat）。
        ⇒ getDeviceBindState() == false。
        注：SharedPreferencesHelper.getInstance(ctx,name) 是**残缺单例**
            （instance==null && !"defaultSp".equals(name) 才 new），
            name 只在第一次生效，之后所有 helper 访问共用同一个文件 —— 所以别猜文件名，
            两个都写才稳（这也是设备侧已用 bindfix 做过的事）。

    ② PhicommInterceptHandler.onWakeupResult() 把它当闸门，直接掐断 handler 链
            if (!NetUtil.isNetworkConnected(ctx))          → doFinishAllInterrupt + playTTS + enterWakeup(false) + return true
            if (!UserPerferenceUtil.getDeviceBindState(ctx)) → doFinishAllInterrupt + onRecognizeStart() + playTTS + enterWakeup(false) + return true
        `onRecognizeStart()` 只会**关灯**（turnOffALLWakeupLight + turnOnLoadingLight 已被
        patch_light.py 改成关灯）⇒ 拾音灯全灭。
        `return true` ⇒ 后面所有 handler 被跳过 ⇒
            DeviceCenterHandler.onWakeupResult / PhicommLightListener.onWakeupSuccess 不执行（灯永不亮）
            DefaultANTLaunchHandler.b() 不执行 ⇒ 没有 tip muted ⇒ 没有 UscTap.onWakeup
            ⇒ 没有 enterASR ⇒ 四麦 HAL 不开 ⇒ 上行零帧 ⇒ 没声音。

    ③ isBinded 为什么丢
        20:12–21:11 之间 dt 清过一次（files/ 全空、gain32/alc_on 于 21:51 重建、
        21:10:40 整机重启）⇒ defaultSp.xml 被重建，丢了 isBinded 键。
        而 setDeviceBindState 的两个写入方都依赖原厂云：
            DeviceStateMgr.reQueryDeviceBoundStatus()  → DeviceInfoUtils.isDeviceBounded(udid)（home.phicomm.com，已死）
            PhicommBindStatusHandler.queryDeviceBoundStatus() 同上
        云死了 ⇒ 永远写不回 true ⇒ 自愈无望。

    ④ 顺带发现同一函数家族还有第三道闸门（尚未咬人，但迟早会）
        DeviceCenterHandler.onWakeupResult() 开头：
            if (UserPerferenceUtil.getNeedInstallUpdate(ctx)) { startInstallAPK(); return false; }
        SP 键 sp_need_install_update（默认 false，当前侥幸没被写脏）。

修法（三处，全部在 smali，幂等）
    A. UserPerferenceUtil.getDeviceBindState()      → 恒 true
       （只有 2 个调用点，两处都受益：PhicommInterceptHandler、DeviceCenterHandler.checkNetState）
    B. UserPerferenceUtil.getNeedInstallUpdate()    → 恒 false
       （拆掉 DeviceCenterHandler 的待升级闸门）
    C. PhicommInterceptHandler.onWakeupResult()
       两个 if-nez 换成无条件 goto/32，让「无网络」「未绑定」两个分支永不进入；
       保留 :cond_60（MediaPlayerUtils.stop()）—— 唤醒时停掉原厂 player 是我们要的行为。

    为什么不改 SP：SP 会被重装/清数据/云查询再弄脏，改 smali 才是根治。
    为什么不用原厂工具：运行这个.bat 走 /sdcard 中转 + busybox，R1 上 /sdcard 可用但
    它同样只在「这次」有效，下次清数据又没了。

回滚：本脚本只做「方法体整体替换」与「一条指令换一条指令」，把下面 ORIG 段还原即可。
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
UP = os.path.join(HERE, 'uni_smali', 'com', 'unisound', 'vui', 'util',
                  'UserPerferenceUtil.smali')
IH = os.path.join(HERE, 'uni_smali', 'com', 'phicomm', 'speaker', 'device',
                  'custom', 'handler', 'PhicommInterceptHandler.smali')

NEW_TRUE = """    .prologue
    # jp2 v1.3.125: 原厂绑定闸门整体拆除 —— 恒「已绑定」。
    # 原实现读 SP unicar_user_settings/defaultSp 的 isBinded（默认 false），
    # 而原厂云已死 ⇒ 该值永远回不到 true ⇒ 每次唤醒都被
    # PhicommInterceptHandler 拦成 doFinishAllInterrupt（灯不亮 + 不进 ASR）。
    const/4 v0, 0x1
    return v0
"""

NEW_FALSE = """    .prologue
    # jp2 v1.3.125: 拆掉「待安装升级包」闸门 —— 恒 false。
    # 原实现读 sp_need_install_update；一旦为 true，
    # DeviceCenterHandler.onWakeupResult() 会 startInstallAPK() 并 return false，
    # 同样把整条唤醒链掐断。
    const/4 v0, 0x0
    return v0
"""


def replace_body(text, method_sig, new_body, tag):
    start = text.find(method_sig)
    if start < 0:
        sys.exit('[patch_bindgate] 找不到 %s' % method_sig)
    end = text.find('.end method', start)
    if end < 0:
        sys.exit('[patch_bindgate] %s 没有 .end method' % method_sig)
    body = text[start:end]
    if 'jp2 v1.3.125' in body:
        print('  [%s] 已打过补丁，跳过' % tag)
        return text, False
    new = method_sig + '\n    .registers 4\n\n' + new_body + '\n.end method\n'
    return text[:start] + new + text[end + len('.end method\n'):], True


def main():
    changed = []

    # ---- A / B：两个布尔开关整体短路 ----
    with io.open(UP, 'r', encoding='utf-8') as f:
        t = f.read()

    t, c = replace_body(
        t,
        '.method public static getDeviceBindState(Landroid/content/Context;)Z',
        NEW_TRUE, 'A getDeviceBindState->true')
    if c:
        changed.append('A')
    t, c = replace_body(
        t,
        '.method public static getNeedInstallUpdate(Landroid/content/Context;)Z',
        NEW_FALSE, 'B getNeedInstallUpdate->false')
    if c:
        changed.append('B')

    with io.open(UP, 'w', encoding='utf-8') as f:
        f.write(t)

    # ---- C：PhicommInterceptHandler 两道闸门改成无条件放行 ----
    with io.open(IH, 'r', encoding='utf-8') as f:
        h = f.read()

    if 'jp2 v1.3.125' in h:
        print('  [C InterceptHandler] 已打过补丁，跳过')
    else:
        a = '    if-nez v2, :cond_37'
        b = '    if-nez v2, :cond_60'
        if a not in h or b not in h:
            sys.exit('[patch_bindgate] InterceptHandler 里找不到两个 if-nez，smali 已变样')
        h = h.replace(a,
                      '    # jp2 v1.3.125: 拆掉「无网络」闸门（断网时原厂会 doFinishAllInterrupt + 不进 ASR）\n'
                      '    goto/32 :cond_37', 1)
        h = h.replace(b,
                      '    # jp2 v1.3.125: 拆掉「未绑定」闸门（原厂云已死 ⇒ isBinded 永远 false）\n'
                      '    goto/32 :cond_60', 1)
        with io.open(IH, 'w', encoding='utf-8') as f:
            f.write(h)
        changed.append('C')
        print('  [C InterceptHandler] 两个 if-nez -> goto/32  OK')

    if not changed:
        print('[patch_bindgate] 全部已打过，无改动')
    else:
        print('[patch_bindgate] 完成，改动: %s' % ','.join(changed))


if __name__ == '__main__':
    main()

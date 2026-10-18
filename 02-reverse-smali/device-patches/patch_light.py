# -*- coding: utf-8 -*-
"""
v1.3.84：短路原厂「呼吸灯」(loading light)。

现象（2026-10-08 23:0x 用户反馈）
    音箱底部呼吸灯一直在闪。以前纯小讯（原厂未改）/ 纯小智（r1client 普通模式，
    根本不跑 Pandora）都不闪。

根因
    com/phicomm/speaker/device/custom/lights/PhicommLightListener
    继承 unisound 的 SimpleLightListener，是 USC 会话状态 -> 灯效的桥：

        onWakeupSuccess(angle) -> turnOnWakeupIndexLight()   顶部拾音灯（朝向）
        onRecognizeStart()     -> turnOnLoadingLight()       **底部呼吸灯 203**
        onTTSEnd()             -> turnOffLoadingLight()      唯一正常熄灭路径
        onInterrupt()          -> turnOffLoadingLight()

    v1.3.70 为了「停用原厂语音」把 SpeechSynthesizer 的 playBuffer/playText 全
    短路了 —— 原厂 TTS 因此**永不结束**，onTTSEnd() 永远不会来，呼吸灯点亮后
    再没人关。（和 v1.3.79 enterASR 那个 bug 同一个根因家族。）

    灯号实证（logcat pid 11645 LightsEffectService）：
        show light 203 / no active light, show light 203
    203 = 0xcb = turnOnLoadingLight / turnOffLoadingLight 用的 index。

修法
    PhicommLightController.turnOnLoadingLight() 里把 sendMessage 的第 3 个参数
    由 0(开) 改成 1(关)。效果：
      * 任何想点亮呼吸灯的调用点（onRecognizeStart 等）实际都变成「关灯」；
      * 已经在亮着的残留灯也会被顺手关掉 —— LightsEffectService 是独立系统
        服务，app 重启后状态可能残留，这样改能自愈。
    不改任何调用点，改动面最小。

幂等：已改过则跳过。
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(
    HERE, 'uni_smali', 'com', 'phicomm', 'speaker', 'device',
    'custom', 'ipc', 'PhicommLightController.smali')

METHOD = '.method public turnOnLoadingLight()V'


def main():
    with io.open(TARGET, 'r', encoding='utf-8') as f:
        text = f.read()

    start = text.find(METHOD)
    if start < 0:
        sys.exit('[patch_light] 找不到 turnOnLoadingLight')
    end = text.find('.end method', start)
    if end < 0:
        sys.exit('[patch_light] 方法体没有结束标记')
    body = text[start:end]

    if 'const/4 v3, 0x1' in body:
        print('[patch_light] 已打过补丁，跳过')
        return

    old = 'const/4 v3, 0x0'
    if old not in body:
        sys.exit('[patch_light] 方法体里没有 %s，smali 可能已变样' % old)

    new_body = body.replace(old, 'const/4 v3, 0x1')
    text = text[:start] + new_body + text[end:]

    with io.open(TARGET, 'w', encoding='utf-8') as f:
        f.write(text)
    print('[patch_light] turnOnLoadingLight: 开(0) -> 关(1)  OK')


if __name__ == '__main__':
    main()

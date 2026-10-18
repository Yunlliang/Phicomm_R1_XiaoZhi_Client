# -*- coding: utf-8 -*-
"""
v1.3.148：让唤醒拾音灯「定格」—— 掐掉 onRecognizeStart() 的灭灯。

现象（v1.3.147 装机后 2026-10-13 23:4x 用户反馈）
    「现在喊小智方向指示灯还是不亮的」—— 但其实灯**亮过**：
    r1client.log 里有 `light 拾音灯 on: index=24`，logcat 里连原厂那条也在。

★ 铁证（adb logcat，LightsEffectService pid 498）
    23:46:39.811 PhicommLightListener: onWakeupSuccess 角度=120 -> light index : 11
    23:46:39.816 LightsEffectService : show light 11              ← 亮了，流动特效开始
    23:46:39.841 LightsEffectService : light 11 same with current running one, do nothing!
    23:46:40.255 PhicommLightListener: onRecognizeStart()         ← 唤醒后仅 444ms
    23:46:40.275 LightsEffectService : close light 11             ← ★ 被灭掉
    23:46:40.276 LightsEffectService : light 11 was stoped and deleted from active list!

根因
    PhicommLightListener.onRecognizeStart() 干两件事：
        turnOffALLWakeupLight()   ← 灭掉 1..24 拾音灯（凶手）
        turnOnLoadingLight()      ← v1.3.84 已把 arg2 0->1 短路成「关」，等于空操作
    它由 DefaultLightsHandler.onASREventRecordingStop() 触发。
    小讯时代「录音停止」要等用户把话说完（好几秒）⇒ 流动动画跑得完、能定格；
    小智模式下 enterASR 是我们自己发的，录音停止事件 ~440ms 就来了 ⇒
    **灯刚起步就被砍** —— 这正是「只有流动特效、没有后面亮点」的成因。
    v1.3.147 在 UscTap 里补发 index-on 也逃不掉：同样被这 444ms 的灭灯指令抹掉。

修法
    把 onRecognizeStart() 里的 turnOffALLWakeupLight() 整段删掉。
    改完这个方法实际等于空操作（只剩被短路过的 turnOnLoadingLight）；
    拾音灯改为由我们自己的 R1Lights 收尾：
        busyOn()  (onStt)  -> wakeupAllOff() + 蓝色播报灯
        allOff()  (enterIdle) -> 兜底全灭
    其它灭灯路径（onInterrupt / 按键处理器 / MatchProcessor）保持不变。

★ 附：LightsEffectService 是**按 pid 记灯的所有权**的
    `can not close other process's lights, sender pid:29249, light owner pid:17927`
    —— 换进程后旧灯位会被死 pid 占着关不掉，所以「灭灯」不是总能成功；
      越晚介入越容易踩到这个坑，也就更要避免多余的灭灯动作。

幂等：已删过则跳过。
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(
    HERE, 'uni_smali', 'com', 'phicomm', 'speaker', 'device',
    'custom', 'lights', 'PhicommLightListener.smali')

METHOD = '.method public onRecognizeStart()V'

CTRL = (r'iget-object v0, p0, '
        r'Lcom/phicomm/speaker/device/custom/lights/PhicommLightListener;'
        r'->phicommLightController:'
        r'Lcom/phicomm/speaker/device/custom/ipc/PhicommLightController;'
        r'\s*\n\s*invoke-virtual \{v0\}, '
        r'Lcom/phicomm/speaker/device/custom/ipc/PhicommLightController;'
        r'->turnOffALLWakeupLight\(\)V')


def main():
    with io.open(TARGET, 'r', encoding='utf-8') as f:
        text = f.read()

    start = text.find(METHOD)
    if start < 0:
        sys.exit('[patch_light_hold] 找不到 onRecognizeStart')
    end = text.find('.end method', start)
    if end < 0:
        sys.exit('[patch_light_hold] 方法体没有结束标记')

    if 'turnOffALLWakeupLight' not in text[start:end]:
        print('[patch_light_hold] 已打过补丁，跳过')
        return

    seg, n = re.subn(CTRL, '', text[start:end])
    if n != 1:
        sys.exit('[patch_light_hold] 期望删掉 1 处，实际 %d 处，smali 可能已变样' % n)
    if 'turnOffALLWakeupLight' in seg:
        sys.exit('[patch_light_hold] 删除后仍残留 turnOffALLWakeupLight')

    # 顺手清掉孤立出来的 .line 36（纯调试信息，留着无害但看着乱）
    seg = seg.replace('    .line 36\n', '')

    text = text[:start] + seg + text[end:]
    with io.open(TARGET, 'w', encoding='utf-8') as f:
        f.write(text)
    print('[patch_light_hold] onRecognizeStart: 去掉 turnOffALLWakeupLight()  OK')


if __name__ == '__main__':
    main()

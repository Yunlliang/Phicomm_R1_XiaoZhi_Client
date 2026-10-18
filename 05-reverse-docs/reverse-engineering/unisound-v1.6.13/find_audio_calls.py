import re
from loguru import logger
logger.remove()
from androguard.misc import AnalyzeAPK

APK = r'C:/Users/shaoh/WorkBuddy/R1/一键安装Unisound_v1.6.13/apps/app-armeabi-v7a-release.apk'
a, d, dx = AnalyzeAPK(APK)

TARGETS = [
    'Landroid/media/AudioRecord;->',
    'Landroid/media/AudioTrack;->',
    'Landroid/media/MediaRecorder;->',
    'Landroid/media/AudioFormat;->',
    'Landroid/media/AudioManager;->',
    'Ljava/lang/System;->loadLibrary',
    'Ljava/lang/System;->load(',
    'Lcom/k2fsa/sherpa/onnx/KeywordSpotter;->',
    'Lcom/theeasiestway/opus/',
]

found = {}
for ma in dx.get_methods():
    if ma.is_external():
        continue
    code = ma.get_method().get_code()
    if code is None:
        continue
    try:
        insns = ma.get_method().get_code().get_bc().get_instructions()
    except Exception:
        continue
    for ins in insns:
        nm = ins.get_name()
        if not nm.startswith('invoke'):
            continue
        out = ins.get_output()
        for t in TARGETS:
            if t in out:
                found.setdefault(t, set()).add((ma.class_name, ma.name, ma.descriptor, out))

for t in TARGETS:
    lst = found.get(t)
    if not lst:
        print('(no site) ', t)
        continue
    print('=' * 110)
    print('CALL:', t, ' sites=', len(lst))
    for cname, mn, md, out in sorted(lst):
        print(f'   {cname}->{mn}{md}')
        print(f'      {out}')

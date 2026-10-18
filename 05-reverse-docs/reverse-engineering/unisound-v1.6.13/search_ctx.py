import sys
from loguru import logger
logger.remove()
from androguard.misc import AnalyzeAPK

APK = r'C:/Users/shaoh/WorkBuddy/R1/一键安装Unisound_v1.6.13/apps/app-armeabi-v7a-release.apk'
NEEDLES = sys.argv[1].split(',')
BEFORE = int(sys.argv[2]) if len(sys.argv) > 2 else 14
AFTER = int(sys.argv[3]) if len(sys.argv) > 3 else 4

a, d, dx = AnalyzeAPK(APK)
hits = 0
for m in dx.get_methods():
    if m.is_external():
        continue
    code = m.get_method().get_code()
    if code is None:
        continue
    ins = list(code.get_bc().get_instructions())
    for k, i in enumerate(ins):
        out = i.get_output()
        if any(n in out for n in NEEDLES) and i.get_name().startswith('invoke'):
            hits += 1
            print('=' * 105)
            print(f'{m.class_name}->{m.name}{m.descriptor}   [idx {k}]')
            lo = max(0, k - BEFORE)
            hi = min(len(ins), k + AFTER)
            for j in range(lo, hi):
                mark = ' <<<' if j == k else ''
                print(f'   {j:5d} {ins[j].get_name():<20} {ins[j].get_output()}{mark}')
            if hits > 12:
                sys.exit(0)

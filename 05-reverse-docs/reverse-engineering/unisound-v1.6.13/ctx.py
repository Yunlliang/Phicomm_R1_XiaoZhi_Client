import sys
from loguru import logger
logger.remove()
from androguard.misc import AnalyzeAPK

APK = r'C:/Users/shaoh/WorkBuddy/R1/一键安装Unisound_v1.6.13/apps/app-armeabi-v7a-release.apk'
a, d, dx = AnalyzeAPK(APK)

WANT_CLASS = sys.argv[1] if len(sys.argv) > 1 else 'LC/c;'
WANT_METH = sys.argv[2] if len(sys.argv) > 2 else 'run'
WANT_DESC = sys.argv[3] if len(sys.argv) > 3 else '()V'
NEEDLES = sys.argv[4].split(',') if len(sys.argv) > 4 else ['AudioRecord;-><init>',
                                                           'AudioRecord;->getMinBufferSize',
                                                           'AudioTrack;-><init>',
                                                           'AudioTrack;->getMinBufferSize',
                                                           'startRecording']
WINDOW = int(sys.argv[5]) if len(sys.argv) > 5 else 40

for ma in dx.get_methods():
    if ma.class_name == WANT_CLASS and ma.name == WANT_METH and ma.descriptor == WANT_DESC:
        insns = list(ma.get_method().get_code().get_bc().get_instructions())
        print(f'method {WANT_CLASS}->{WANT_METH}{WANT_DESC}  instructions={len(insns)}')
        for idx, ins in enumerate(insns):
            out = ins.get_output()
            if any(nd in out for nd in NEEDLES):
                lo = max(0, idx - WINDOW)
                hi = min(len(insns), idx + 6)
                print('-' * 100)
                print(f'>>> HIT at {idx}: {ins.get_name()} {out}')
                for j in range(lo, hi):
                    mark = '  <<<' if j == idx else ''
                    print(f'   {j:5d} {insns[j].get_name():<20} {insns[j].get_output()}{mark}')
        break

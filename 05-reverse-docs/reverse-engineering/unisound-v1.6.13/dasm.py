import sys
from loguru import logger
logger.remove()
from androguard.misc import AnalyzeAPK

APK = r'C:/Users/shaoh/WorkBuddy/R1/一键安装Unisound_v1.6.13/apps/app-armeabi-v7a-release.apk'
CLS, METH, DESC = sys.argv[1], sys.argv[2], sys.argv[3]
LO, HI = (int(sys.argv[4]), int(sys.argv[5])) if len(sys.argv) > 5 else (None, None)

a, d, dx = AnalyzeAPK(APK)
for m in dx.get_methods():
    if m.class_name == CLS and m.name == METH and m.descriptor == DESC:
        insns = list(m.get_method().get_code().get_bc().get_instructions())
        addrs, off = [], 0
        for i in insns:
            addrs.append(off)
            off += i.get_length()
        by_addr = {ad: k for k, ad in enumerate(addrs)}

        def target(i, k):
            nm = i.get_name()
            try:
                if nm in ('goto', 'goto/16', 'goto/32'):
                    o = i.get_operands(0) if hasattr(i, 'get_operands') else None
                # use raw attributes
                for attr in ('C', 'BBBB', 'B'):
                    if hasattr(i, attr):
                        v = getattr(i, attr)
                        if isinstance(v, int):
                            return addrs[k] + i.get_length() + v * 2
            except Exception:
                pass
            return None

        print(f'{CLS}->{METH}{DESC}  n={len(insns)} bytes={off}')
        for k, i in enumerate(insns):
            if LO is not None and not (LO <= k <= HI):
                continue
            t = target(i, k)
            ts = ''
            if t is not None and t in by_addr:
                ts = f'  --> #{by_addr[t]}'
            elif t is not None:
                ts = f'  --> off {t}'
            print(f'{k:5d} @{addrs[k]:5d}  {i.get_name():<20} {i.get_output()}{ts}')
        break

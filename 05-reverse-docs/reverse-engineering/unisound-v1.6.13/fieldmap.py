import re, collections
from loguru import logger
logger.remove()
from androguard.misc import AnalyzeAPK

APK = r'C:/Users/shaoh/WorkBuddy/R1/一键安装Unisound_v1.6.13/apps/app-armeabi-v7a-release.apk'
a, d, dx = AnalyzeAPK(APK)

WRITE = collections.defaultdict(list)
READ = collections.defaultdict(list)
for m in dx.get_methods():
    if m.is_external() or m.class_name == 'Lcom/huihongcloud/xiaozhi/XiaoZhi;':
        continue
    code = m.get_method().get_code()
    if code is None:
        continue
    ins = list(code.get_bc().get_instructions())
    for k, i in enumerate(ins):
        out = i.get_output()
        mm = re.search(r'Lcom/huihongcloud/xiaozhi/XiaoZhi;->(\w+) (\w+)', out)
        if not mm:
            continue
        fld = mm.group(1)
        nm = i.get_name()
        ctx = ' | '.join(x.get_output().split(',', 1)[-1].strip()
                         for x in ins[max(0, k - 6):k + 3]
                         if x.get_name().startswith('const-string'))
        if nm.startswith('sput'):
            WRITE[fld].append((m.class_name, m.name, m.descriptor, ctx))
        elif nm.startswith('sget'):
            READ[fld].append((m.class_name, m.name, m.descriptor, ctx))

ALL = set(WRITE) | set(READ)
for fld in sorted(ALL):
    print('=' * 100)
    print(f'FIELD XiaoZhi->{fld}   writes={len(WRITE[fld])} reads={len(READ[fld])}')
    for c, n, ds, ctx in WRITE[fld][:8]:
        print(f'   W  {c}->{n}{ds}')
        if ctx:
            print(f'      strs: {ctx[:300]}')

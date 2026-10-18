import re
from loguru import logger
logger.remove()
from androguard.misc import AnalyzeAPK

APK = r'C:/Users/shaoh/WorkBuddy/R1/一键安装Unisound_v1.6.13/apps/app-armeabi-v7a-release.apk'
a, d, dx = AnalyzeAPK(APK)

FIELD_PAT = re.compile(r'Lcom/huihongcloud/xiaozhi/XiaoZhi;->(o|p|q|n|k|r|e|f|g|d|t|z|E|K|L|M|N|O|X|Y|Z|a0|C) \w')

print('#' * 30, 'XiaoZhi.<clinit> defaults')
for m in dx.get_methods():
    if m.class_name == 'Lcom/huihongcloud/xiaozhi/XiaoZhi;' and m.name == '<clinit>':
        ins = list(m.get_method().get_code().get_bc().get_instructions())
        print('  instructions:', len(ins))
        for k, i in enumerate(ins):
            print(f'   {k:4d} {i.get_name():<22} {i.get_output()}')
        break

print()
print('#' * 30, 'methods that read/write XiaoZhi config fields (non-clinit)')
seen = set()
for m in dx.get_methods():
    if m.is_external() or m.class_name == 'Lcom/huihongcloud/xiaozhi/XiaoZhi;':
        continue
    code = m.get_method().get_code()
    if code is None:
        continue
    ins = list(code.get_bc().get_instructions())
    for k, i in enumerate(ins):
        out = i.get_output()
        if FIELD_PAT.search(out) and i.get_name().startswith(('sget', 'sput')):
            key = (m.class_name, m.name)
            if key in seen:
                break
            seen.add(key)
            strs = [x.get_output().split(',', 1)[-1].strip() for x in ins
                    if x.get_name().startswith('const-string')]
            print(f'  {m.class_name}->{m.name}{m.descriptor}')
            print(f'      hit: {i.get_name()} {out}')
            if strs:
                print(f'      strings({len(strs)}): {strs[:14]}')
            break

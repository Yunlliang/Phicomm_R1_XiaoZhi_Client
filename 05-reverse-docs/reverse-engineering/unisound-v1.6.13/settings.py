import re, json
from loguru import logger
logger.remove()
from androguard.misc import AnalyzeAPK

APK = r'C:/Users/shaoh/WorkBuddy/R1/一键安装Unisound_v1.6.13/apps/app-armeabi-v7a-release.apk'
a, d, dx = AnalyzeAPK(APK)
dex = d[0]

# 1) System.loadLibrary string constants
print('#' * 30, 'loadLibrary call sites')
for m in dx.get_methods():
    if m.is_external():
        continue
    code = m.get_method().get_code()
    if code is None:
        continue
    ins = list(code.get_bc().get_instructions())
    for k, i in enumerate(ins):
        if i.get_name().startswith('invoke') and 'loadLibrary' in i.get_output():
            ctx = []
            for j in range(max(0, k - 3), k):
                ctx.append(ins[j].get_output())
            print(f'  {m.class_name}->{m.name}{m.descriptor}')
            for c in ctx:
                print('       ', c)

# 2) setting-like string constants
strs = dex.get_strings()
keys = sorted(set(s for s in strs if re.fullmatch(r'[a-zA-Z][a-zA-Z0-9_]{3,40}', s)
                  and re.search(r'(audio|source|voice|mic|record|aec|ns|agc|apm|kws|wake|vad|noise|echo|gain|sample|channel|opus|frame|bitrate|server|url|token|volume)', s, re.I)))
print()
print('#' * 30, 'setting-like string constants', len(keys))
for s in keys:
    print('  ', s)

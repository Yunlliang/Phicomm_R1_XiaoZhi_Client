import sys, re
from loguru import logger
logger.remove()

from androguard.misc import AnalyzeAPK

APK = r'C:/Users/shaoh/WorkBuddy/R1/一键安装Unisound_v1.6.13/apps/app-armeabi-v7a-release.apk'

a, d, dx = AnalyzeAPK(APK)

kw = re.compile(r'(audio|mic|record|opus|kws|wake|voice|sherpa|speech|asr|tts|play|opus|pcm)', re.I)
print('=== classes matching keyword (name) ===')
names = sorted(c.name for c in dx.get_classes())
hits = [n for n in names if kw.search(n)]
print('total classes:', len(names), ' hits:', len(hits))
for n in hits:
    print(' ', n)

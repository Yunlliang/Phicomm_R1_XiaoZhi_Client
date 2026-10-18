import re, sys, json
from loguru import logger
logger.remove()
from androguard.misc import AnalyzeAPK

APK = r'C:/Users/shaoh/WorkBuddy/R1/一键安装Unisound_v1.6.13/apps/app-armeabi-v7a-release.apk'
a, d, dx = AnalyzeAPK(APK)

dex = d[0]
strs = []
try:
    for s in dex.get_strings():
        try:
            strs.append(s.get())
        except Exception:
            pass
except Exception as e:
    print('get_strings err', e)

print('total strings:', len(strs))

def show(title, pat, limit=200):
    rx = re.compile(pat, re.I)
    hits = sorted(set(s for s in strs if rx.search(s)))
    print('=' * 90)
    print(f'### {title}  ({len(hits)})')
    for s in hits[:limit]:
        print('  ', s)

show('URL / ws / http', r'^(wss?|https?)://|\.(cn|com|net|org)/')
show('audio-ish literals', r'audio|sample|rate|channel|opus|bitrate|frame|pcm|mic|voip|aec|apm|ns\b|agc')
show('kws / wake words', r'小智|小讯|唤醒|keyword|kws|sherpa|wake')
show('settings keys', r'^\w+_\w+$|setting|prefs|key_|config')
show('r1 / phicomm / device', r'phicomm|rk322|unisound|^r1$|speaker\.device|msgcenter|MessageDispatch')

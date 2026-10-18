import sys
from loguru import logger
logger.remove()
from androguard.decompiler.decompiler import DecompilerDAD
from androguard.misc import AnalyzeAPK
from androguard.core.analysis.analysis import ClassAnalysis

APK = r'C:/Users/shaoh/WorkBuddy/R1/一键安装Unisound_v1.6.13/apps/app-armeabi-v7a-release.apk'
CLS = sys.argv[1]

a, d, dx = AnalyzeAPK(APK)
ca = dx.get_class_analysis(CLS)
if ca is None:
    print('no class analysis for', CLS)
    sys.exit(1)
dcd = DecompilerDAD(d, dx)
dcd.add(ca)
print(dcd.get_source())

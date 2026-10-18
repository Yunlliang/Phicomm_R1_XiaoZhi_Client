# -*- coding: utf-8 -*-
"""拆分 Unisound_v1.6.14（Janus），并对比载荷 DEX 与原厂 DEX 的类差异。"""
import hashlib
import os
import re
import zipfile

NEW = r'C:\Users\shaoh\WorkBuddy\R1\一键安装Unisound_v1.6.14\apps\Unisound_v1.6.14.apk'
OLD = r'C:\Users\shaoh\WorkBuddy\R1\diag\pandora\Unisound.apk'
OUT = r'C:\Users\shaoh\WorkBuddy\R1\diag\_unisound1614'
os.makedirs(OUT, exist_ok=True)

data = open(NEW, 'rb').read()
zip_off = data.find(b'PK\x03\x04')
print('文件总大小      :', len(data))
print('头部 dex magic  :', data[:8])
print('ZIP 起始偏移    :', zip_off, '( = 载荷 DEX 大小)')

payload = data[:zip_off]
shell = data[zip_off:]
print('载荷 DEX        : %d 字节 (%.2f MB)' % (len(payload), len(payload) / 1048576.0))
print('外壳 ZIP        : %d 字节 (%.2f MB)' % (len(shell), len(shell) / 1048576.0))
print('载荷 SHA256     :', hashlib.sha256(payload).hexdigest()[:32])

open(os.path.join(OUT, 'payload.dex'), 'wb').write(payload)
open(os.path.join(OUT, 'shell.apk'), 'wb').write(shell)

# 外壳与原厂 APK 是否逐字节相同
old_raw = open(OLD, 'rb').read()
print('\n== 外壳 vs 设备原厂 Unisound.apk ==')
print('  原厂大小 : %d' % len(old_raw))
print('  外壳大小 : %d' % len(shell))
print('  逐字节相同:', shell == old_raw)

# 原厂 dex
zo = zipfile.ZipFile(OLD)
old_dex_names = [n for n in zo.namelist() if n.endswith('.dex')]
print('\n原厂 dex 条目:', old_dex_names)

STR_RE = re.compile(rb'[\x20-\x7e]{5,}')
CLS_RE = re.compile(rb'L[\w/$]+;')

old_classes = set()
for n in old_dex_names:
    old_classes |= set(CLS_RE.findall(zo.read(n)))
new_classes = set(CLS_RE.findall(payload))

print('\n== 类描述符统计 ==')
print('  原厂 dex 引用类 : %d' % len(old_classes))
print('  载荷 dex 引用类 : %d' % len(new_classes))
only_new = new_classes - old_classes
only_old = old_classes - new_classes
print('  载荷独有        : %d' % len(only_new))
print('  原厂独有        : %d' % len(only_old))

print('\n== 载荷独有的类（前 60，按包名归组）==')
groups = {}
for c in only_new:
    parts = c.decode('utf-8', 'ignore')[1:-1].split('/')
    key = '/'.join(parts[:3]) if len(parts) >= 3 else '/'.join(parts)
    groups[key] = groups.get(key, 0) + 1
for k, v in sorted(groups.items(), key=lambda x: -x[1])[:30]:
    print('   %-52s %d' % (k, v))

print('\n== 载荷里的可疑关键字计数 ==')
for needle in [b'xiaofei', b'jojo', b'R1_DEVICE', b'github', b'Runtime', b'getRuntime',
               b'8080', b'http://', b'https://', b'Reflect', b'hook', b'Hook', b'Binder']:
    print('   %-14s %d' % (needle.decode(), payload.count(needle)))

print('\n== 载荷里的 http(s) 地址（去重，前 30）==')
urls = sorted(set(re.findall(rb'https?://[\w.:@/%-]{8,}', payload)))
for u in urls[:30]:
    print('   ', u.decode('ascii', 'ignore')[:110])

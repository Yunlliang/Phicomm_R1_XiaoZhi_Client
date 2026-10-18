# -*- coding: utf-8 -*-
"""Unisound v1.6.14 vs 设备上原厂 Unisound.apk（Pandora）逐条目对比。"""
import hashlib
import os
import zipfile

NEW = r'C:\Users\shaoh\WorkBuddy\R1\一键安装Unisound_v1.6.14\apps\Unisound_v1.6.14.apk'
OLD = r'C:\Users\shaoh\WorkBuddy\R1\diag\pandora\Unisound.apk'


def head(path):
    with open(path, 'rb') as f:
        return f.read(8)


def entries(path):
    z = zipfile.ZipFile(path)
    out = {}
    for i in z.infolist():
        if i.filename.endswith('/'):
            continue
        out[i.filename] = (i.file_size, hashlib.md5(z.read(i.filename)).hexdigest())
    return out


for tag, p in [('v1.6.14', NEW), ('Pandora(设备)', OLD)]:
    print('=' * 72)
    print('%s : %s' % (tag, p))
    print('  文件大小 : %d 字节' % os.path.getsize(p))
    print('  文件头   : %r  %s' % (head(p), '← 裸 DEX(Janus)' if head(p)[:4] == b'dex\n' else ''))
    with open(p, 'rb') as f:
        print('  SHA256   : %s' % hashlib.sha256(f.read()).hexdigest()[:32])

new_e = entries(NEW)
old_e = entries(OLD)

print('\n' + '=' * 72)
print('条目数：v1.6.14 = %d   Pandora = %d' % (len(new_e), len(old_e)))
print('v1.6.14 解压后合计 : %.1f MB' % (sum(v[0] for v in new_e.values()) / 1048576.0))
print('Pandora 解压后合计 : %.1f MB' % (sum(v[0] for v in old_e.values()) / 1048576.0))

only_new = sorted(set(new_e) - set(old_e))
only_old = sorted(set(old_e) - set(new_e))
common = sorted(set(new_e) & set(old_e))
changed = [k for k in common if new_e[k][1] != old_e[k][1]]
same = [k for k in common if new_e[k][1] == old_e[k][1]]

print('\n== 仅 v1.6.14 有 (%d) ==' % len(only_new))
for k in only_new[:40]:
    print('   + %10d  %s' % (new_e[k][0], k))

print('\n== 仅 Pandora 有 (%d) ==' % len(only_old))
for k in only_old[:40]:
    print('   - %10d  %s' % (old_e[k][0], k))

print('\n== 两边都有但内容不同 (%d) ==' % len(changed))
for k in sorted(changed, key=lambda x: -abs(new_e[x][0] - old_e[x][0]))[:40]:
    print('   ~ %10d -> %-10d  %s' % (old_e[k][0], new_e[k][0], k))

print('\n== 完全相同 (%d) ==' % len(same))
for k in same[:20]:
    print('   = %10d  %s' % (new_e[k][0], k))
if len(same) > 20:
    print('   … 共 %d 项' % len(same))

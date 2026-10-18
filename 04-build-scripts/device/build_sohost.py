#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 r1client 砍成「极简 so 宿主」——只保留 libr1opus.so。

原理（关键）：
  Android v1 签名的校验对象是 **ZIP 里存在的条目**：JarVerifier 遍历 zip 条目，
  逐个比对 MANIFEST.MF 里的摘要；条目不在 zip 里就不校验。
  ⇒ **只删除条目、不改动任何保留条目的内容，签名依然有效**，可以直接 `pm install -r`
    覆盖安装（不用卸载，也不用重签 —— 我们没有原签名私钥）。

保留：
  AndroidManifest.xml（包名 cn.workbuddy.r1client 必须保留，jp2 硬编码了这个路径）
  resources.arsc
  lib/armeabi-v7a/libr1opus.so   ← 唯一还需要的东西
  META-INF/*                     ← 签名本身
删除：
  classes.dex（可选保留，见 KEEP_DEX）
  assets/*（KWS 模型，USC_KWS_ONLY 下不用）
  lib/armeabi-v7a/libsherpa-onnx-jni.so（15.7MB，USC_KWS_ONLY 下不用）
  lib/armeabi-v7a/libaec.so（v1.3.55 定案弃用）

用法：python build_sohost.py [--keep-dex]
"""
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, 'r1-client-v1.3.64.apk')
KEEP_DEX = '--keep-dex' in sys.argv
DST = os.path.join(ROOT, 'r1-sohost-dex.apk' if KEEP_DEX else 'r1-sohost-min.apk')

KEEP = [
    'AndroidManifest.xml',
    'resources.arsc',
    'lib/armeabi-v7a/libr1opus.so',
]
if KEEP_DEX:
    KEEP.append('classes.dex')


def main():
    src = zipfile.ZipFile(SRC)
    names = src.namelist()

    # 保留 META-INF（签名本身）
    keep = [n for n in names if n.startswith('META-INF/')]
    for k in KEEP:
        if k in names:
            keep.append(k)
        else:
            print('  [warn] not present in source: %s' % k)

    # 记录被删掉的（便于核对）
    dropped = [n for n in names if n not in keep]
    drop_bytes = sum(src.getinfo(n).file_size for n in dropped)

    if os.path.exists(DST):
        os.remove(DST)
    out = zipfile.ZipFile(DST, 'w')
    for n in keep:
        item = src.getinfo(n)
        data = src.read(n)
        zi = zipfile.ZipInfo(n, date_time=item.date_time)
        zi.compress_type = item.compress_type
        zi.external_attr = item.external_attr
        zi.create_system = item.create_system
        out.writestr(zi, data)
    out.close()

    size = os.path.getsize(DST)
    print('  source : %d bytes (%s)' % (os.path.getsize(SRC), SRC))
    print('  output : %d bytes (%s)' % (size, DST))
    print('  kept   : %d entries' % len(keep))
    print('  dropped: %d entries, %.1f MB' % (len(dropped), drop_bytes / 1048576.0))
    for n in KEEP:
        if n in names:
            print('    keep %10d B  %s' % (src.getinfo(n).file_size, n))

    # ---- manifest 里声明了哪些组件（AXML 字符串池是 UTF-16）----
    m = src.read('AndroidManifest.xml')
    u = m.decode('utf-16-le', 'ignore')
    cls = sorted(set(re.findall(r'[A-Za-z][A-Za-z0-9_$.]{6,80}', u)))
    comps = [c for c in cls if ('/' in c or '.' in c)
             and not c.lower().startswith('android.')
             and 'manifest' not in c.lower()]
    print('  manifest 里的组件类名（删 dex 后这些类不存在，被触发才会崩）:')
    for c in comps[:30]:
        print('      ' + c)

    # ---- v1 验签 ----
    # 注意：本包签名文件叫 ANDROIDD.SF/.RSA（debug keystore），而 janus_poc.verify_v1
    # 硬编码找 CERT.SF/CERT.RSA。生成一个**只用于验证**的副本把签名文件改名即可复用，
    # 不改待安装的包（META-INF 条目不参与摘要校验，改名对真机无影响）。
    sys.path.insert(0, os.path.join(ROOT, 'diag', 'pandora'))
    try:
        import janus_poc
        vpath = DST + '.verify.apk'
        if os.path.exists(vpath):
            os.remove(vpath)
        vz = zipfile.ZipFile(vpath, 'w')
        for n in keep:
            item = src.getinfo(n)
            data = src.read(n)
            vn = n
            if n.startswith('META-INF/ANDROIDD.'):
                vn = n.replace('ANDROIDD.', 'CERT.')
            zi = zipfile.ZipInfo(vn, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zi.create_system = item.create_system
            vz.writestr(zi, data)
        vz.close()
        r = janus_poc.verify_v1(vpath, os.path.basename(DST))
        janus_poc.show(r)
        ok = (r['verdict'] == 'PASS')
    except Exception as e:
        print('  verify_v1 threw: %r' % (e,))
        ok = False

    print('  ==> %s' % ('SIGNATURE STILL VALID - 可直接 pm install -r' if ok
                         else '!!! 验签失败，不能用'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())

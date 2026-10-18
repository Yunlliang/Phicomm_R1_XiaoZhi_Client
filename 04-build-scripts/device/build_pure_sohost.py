#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 r1-sohost.apk 削成「真正的纯 so 宿主」 r1-pure.apk：

    只保留  AndroidManifest.xml / resources.arsc / lib/armeabi-v7a/libr1opus.so
    删除    classes.dex（2.8MB，含 MainActivity / BootReceiver / XiaozhiService /
            OkHttp WebSocket / AudioRecord 全套）
    重建    META-INF（交给 jarsigner）

目的：r1client 现在唯一的价值是提供一个 SELinux 允许 map 的 libr1opus.so 落点
      （/data/app/cn.workbuddy.r1client-N/lib/arm/）。dex 留着只会让它在开机时
      拉起一个 UI 进程 + 一个 :r1client 服务进程，白占内存、还连一路 WebSocket。

原跟踪：OpusCodec 枚举 /data/app/cn.workbuddy.r1client-{1..4}/lib/arm/...，
      卸载重装导致目录后缀变化不会打断加载。
"""
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, 'r1-sohost.apk')
OUT = os.path.join(ROOT, 'r1-pure.apk')

DROP_PREFIX = ('META-INF/',)
DROP_EXACT = ('classes.dex',)


def main():
    # 用法: build_pure_sohost.py [stub.dex]
    #  不带参数 → 只有 manifest + resources.arsc + lib（会被 PMS 判 INSTALL_FAILED_DEXOPT）
    #  带 dex    → 把 classes.dex 换成这个 stub dex（PMS 需要至少一个可 dexopt 的 dex）
    dex_path = sys.argv[1] if len(sys.argv) > 1 else None
    if dex_path is None:
        here = HERE
        cands = [os.path.join(here, 'sohost_stub.dex')]
        for c in cands:
            if os.path.exists(c):
                dex_path = c
                break
    if not os.path.exists(SRC):
        print('missing source: %s' % SRC)
        return 1
    dex = open(dex_path, 'rb').read() if dex_path and os.path.exists(dex_path) else None
    if dex is None:
        print('WARN: no stub dex -> output will fail INSTALL_FAILED_DEXOPT')
    zin = zipfile.ZipFile(SRC)
    if os.path.exists(OUT):
        os.remove(OUT)
    zout = zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED)
    kept, dropped = [], []
    for info in zin.infolist():
        n = info.filename
        if n in DROP_EXACT or n.startswith(DROP_PREFIX) or n.endswith('.dex'):
            dropped.append('%8d  %s' % (info.file_size, n))
            continue
        data = zin.read(n)
        zi = zipfile.ZipInfo(n, date_time=info.date_time)
        zi.compress_type = info.compress_type
        zi.external_attr = info.external_attr
        zout.writestr(zi, data)
        kept.append('%8d  %-6s %s' % (info.file_size,
                                      {0: 'store', 8: 'deflate'}.get(info.compress_type, str(info.compress_type)),
                                      n))
    if dex is not None:
        zi = zipfile.ZipInfo('classes.dex', date_time=(2019, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        zout.writestr(zi, dex)
        kept.append('%8d  deflate classes.dex  <- %s' % (len(dex), dex_path))
    zout.close()
    zin.close()
    print('=== kept (%d) ===' % len(kept))
    for x in kept:
        print('  ' + x)
    print('=== dropped (%d) ===' % len(dropped))
    for x in dropped:
        print('  ' + x)
    print('out: %s  (%d bytes)' % (OUT, os.path.getsize(OUT)))
    return 0


if __name__ == '__main__':
    sys.exit(main())

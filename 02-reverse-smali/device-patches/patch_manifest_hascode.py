#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
给某个 APK 的 AndroidManifest.xml 打一个二进制补丁：
在 <application> 元素上加 / 改  android:hasCode="false"

背景：没有 classes.dex 的 APK 如果不声明 hasCode=false，PMS 会尝试 dexopt 并直接
      INSTALL_FAILED_DEXOPT。而删掉 dex 正是把 r1client 削成「纯 so 宿主」的关键。

实现：直接改 ResXMLTree 二进制 —— 在 application 的 start-element chunk 尾部
      追加一条属性，并修正该 chunk 的 size/attributeCount 与文件总 size。
      属性带 attrRes = 0x0101000c(android:hasCode)，TypedValue 为
      TYPE_INT_BOOLEAN(0x12)/data=0，所以 Android 的 obtainAttributes 能按资源
      id 匹配到 AndroidManifestApplication_hasCode，与 name 字符串无关。
"""
import os
import struct
import sys
import zipfile

RES_STRING_POOL_TYPE = 0x0001
RES_XML_START_ELEMENT_TYPE = 0x0102
START_ELEMENT_HEADER = 36          # 8(header)+4+4+4+4+2*6
ATTR_SIZE = 20
HAS_CODE_ATTR_RES = 0x0101000C     # android:hasCode
TYPE_INT_BOOLEAN = 0x12


def decode_strings(data, pool_off):
    """返回 (字符串列表, pool 总长)"""
    _, _, size, scount, stycount, flags, strstart, stystart = struct.unpack_from(
        '<HHIIIIII', data, pool_off)
    utf8 = bool(flags & 0x100)
    out = []
    for i in range(scount):
        o = struct.unpack_from('<I', data, pool_off + 28 + 4 * i)[0]
        p = pool_off + strstart + o
        if utf8:
            n = data[p]
            p += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p]
                p += 1
            out.append(data[p:p + n].decode('utf-8', 'replace'))
        else:
            n = struct.unpack_from('<H', data, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from('<H', data, p)[0]
                p += 2
            out.append(data[p:p + 2 * n].decode('utf-16-le', 'replace'))
    return out, size


def find_application(data, strings):
    """返回 application start-element 的偏移与 (old_size, attr_count)"""
    pos = 8
    _, _, pool_size = struct.unpack_from('<HHI', data, pos)[0:3]
    strings2, _ = decode_strings(data, pos)
    pos += pool_size
    end = len(data)
    while pos + 8 <= end:
        ctype, chead, csize = struct.unpack_from('<HHI', data, pos)
        if csize == 0:
            break
        if ctype == RES_XML_START_ELEMENT_TYPE:
            name_idx = struct.unpack_from('<I', data, pos + 20)[0]
            name = strings2[name_idx] if name_idx < len(strings2) else '?'
            if name == 'application':
                attr_count = struct.unpack_from('<H', data, pos + 32)[0]
                return pos, csize, attr_count
        pos += csize
    return None, None, None


def patch(src_apk, out_apk):
    zin = zipfile.ZipFile(src_apk, 'r')
    manifest = zin.read('AndroidManifest.xml')
    strings, _ = decode_strings(manifest, 8)
    off, old_size, attr_count = find_application(manifest, strings)
    if off is None:
        print('ERROR: <application> not found')
        return 1
    print('found <application> @%d size=%d attrs=%d' % (off, old_size, attr_count))

    # 已存在 hasCode 就直接改值；否则追加
    for i in range(attr_count):
        p = off + START_ELEMENT_HEADER + i * ATTR_SIZE
        ns, name_idx, raw, vsize, vres0, vtype, vdata = struct.unpack_from(
            '<IIIHBBI', manifest, p)
        if struct.unpack_from('<I', manifest, p)[0] | True and \
                struct.unpack_from('<I', manifest, p + 4)[0] == struct.unpack_from('<I', manifest, p + 4)[0] and \
                struct.unpack_from('<I', manifest, p + 8)[0] == HAS_CODE_ATTR_RES:
            pass
        # 真正的 NameRes 字段并不在 20 字节里（NameRes 隐含于 attrs.xml 映射），
        # 这里靠 data type 无法区分，直接看 name 字符串
        nm = strings[name_idx] if name_idx < len(strings) else '?'
        if nm == 'hasCode':
            print('hasCode already present -> set false')
            new = manifest[:p + 12] + struct.pack('<HBBI', 8, 0, TYPE_INT_BOOLEAN, 0) + manifest[p + 20:]
            zout = zipfile.ZipFile(out_apk, 'w', zipfile.ZIP_DEFLATED)
            for info in zin.infolist():
                d = zin.read(info.filename)
                if info.filename == 'AndroidManifest.xml':
                    d = new
                zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                zi.compress_type = info.compress_type
                zi.external_attr = info.external_attr
                zout.writestr(zi, d)
            zout.close()
            return 0

    # 追加新属性：ns=-1, name=复用已有字符串索引, rawValue=-1, TypedValue(bool false)
    name_idx = 0
    for i, s in enumerate(strings):
        if s == 'hasCode':
            name_idx = i
            break
    attr = struct.pack('<IIIHBBI', 0xFFFFFFFF, name_idx, 0xFFFFFFFF,
                       8, 0, TYPE_INT_BOOLEAN, 0)
    ins_at = off + old_size
    new = manifest[:ins_at] + attr + manifest[ins_at:]
    # 修正 application chunk 的 size / attributeCount
    new = new[:off + 4] + struct.pack('<I', old_size + ATTR_SIZE) + new[off + 8:]
    nc = attr_count + 1
    new = new[:off + 32] + struct.pack('<H', nc) + new[off + 34:]
    # 修正文件总 size
    total = struct.unpack_from('<I', new, 4)[0]
    new = new[:4] + struct.pack('<I', total + ATTR_SIZE) + new[8:]
    print('inserted hasCode=false (false@%d, file size %d -> %d)' % (ins_at, len(manifest), len(new)))

    zout = zipfile.ZipFile(out_apk, 'w', zipfile.ZIP_DEFLATED)
    for info in zin.infolist():
        d = zin.read(info.filename)
        if info.filename == 'AndroidManifest.xml':
            d = new
        zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        zi.compress_type = info.compress_type
        zi.external_attr = info.external_attr
        zout.writestr(zi, d)
    zout.close()
    return 0


if __name__ == '__main__':
    src = sys.argv[1] if len(sys.argv) > 1 else 'r1-pure.apk'
    dst = sys.argv[2] if len(sys.argv) > 2 else src
    sys.exit(patch(src, dst))

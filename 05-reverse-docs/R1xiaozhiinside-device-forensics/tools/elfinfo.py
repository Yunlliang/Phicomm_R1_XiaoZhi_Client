#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
极简 ELF 分析器（无 readelf 环境下的替代品）
用法: python3 elfinfo.py <file.so> [--need] [--exp] [--str PATTERN] [--all]
  --need  列出 DT_NEEDED 依赖
  --exp   列出导出(定义)符号
  --imp   列出未定义(引用)符号
  --str P 在 .rodata/.dynstr 里搜字符串
  --all   全部
"""
import sys, struct, re, io, os


class ELF:
    def __init__(self, path):
        self.path = path
        with open(path, 'rb') as f:
            self.d = f.read()
        d = self.d
        if d[:4] != b'\x7fELF':
            raise ValueError('not ELF')
        self.is64 = (d[4] == 2)
        self.little = (d[5] == 1)
        e = '<' if self.little else '>'
        # ELF header
        if self.is64:
            (self.e_type, self.e_machine, self.e_version, self.e_entry,
             self.e_phoff, self.e_shoff, self.e_flags, self.e_ehsize,
             self.e_phentsize, self.e_phnum, self.e_shentsize,
             self.e_shnum, self.e_shstrndx) = struct.unpack(e + 'HHIQQQIHHHHHH', d[16:64])
        else:
            (self.e_type, self.e_machine, self.e_version, self.e_entry,
             self.e_phoff, self.e_shoff, self.e_flags, self.e_ehsize,
             self.e_phentsize, self.e_phnum, self.e_shentsize,
             self.e_shnum, self.e_shstrndx) = struct.unpack(e + 'HHIIIIIHHHHHH', d[16:52])
        self.sections = []
        for i in range(self.e_shnum):
            off = self.e_shoff + i * self.e_shentsize
            if self.is64:
                (name, stype, flags, addr, offset, size, link, info,
                 align, entsize) = struct.unpack(e + 'IIQQQQIIQQ', d[off:off + 64])
            else:
                (name, stype, flags, addr, offset, size, link, info,
                 align, entsize) = struct.unpack(e + 'IIIIIIIIII', d[off:off + 40])
            self.sections.append(dict(nameoff=name, type=stype, flags=flags,
                                      addr=addr, off=offset, size=size,
                                      link=link, info=info, entsize=entsize))
        if self.e_shstrndx < len(self.sections):
            sh = self.sections[self.e_shstrndx]
            self.shstr = d[sh['off']:sh['off'] + sh['size']]
        else:
            self.shstr = b''
        for s in self.sections:
            s['name'] = self._str(self.shstr, s['nameoff'])
        self.byname = {s['name']: s for s in self.sections}

    def _str(self, blob, off):
        end = blob.find(b'\x00', off)
        return blob[off:end].decode('utf-8', 'replace') if end >= 0 else ''

    def blob(self, sec_name):
        s = self.byname.get(sec_name)
        if not s:
            return b''
        return self.d[s['off']:s['off'] + s['size']]

    def dyn_entries(self):
        """返回 [(tag, val)]"""
        out = []
        for s in self.sections:
            if s['type'] == 6:  # SHT_DYNAMIC
                blob = self.blob(s['name'])
                sz = 16 if self.is64 else 8
                e = '<' if self.little else '>'
                for i in range(0, len(blob) - sz + 1, sz):
                    if self.is64:
                        tag, val = struct.unpack(e + 'QQ', blob[i:i + 16])
                    else:
                        tag, val = struct.unpack(e + 'II', blob[i:i + 8])
                    if tag == 0:
                        break
                    out.append((tag, val))
        return out

    def needed(self):
        strtab = self.blob('.dynstr')
        out = []
        for tag, val in self.dyn_entries():
            if tag == 1:  # DT_NEEDED
                out.append(self._str(strtab, val))
        return out

    def dynsyms(self):
        """返回 [(name, info, shndx)]"""
        strtab = self.blob('.dynstr')
        symtab = self.byname.get('.dynsym')
        if not symtab:
            return []
        blob = self.blob('.dynsym')
        e = '<' if self.little else '>'
        out = []
        if self.is64:
            sz = 24
            for i in range(0, len(blob) - sz + 1, sz):
                st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack(
                    e + 'IBBHQQ', blob[i:i + 24])
                nm = self._str(strtab, st_name)
                out.append((nm, st_info, st_shndx, st_value, st_size))
        else:
            sz = 16
            for i in range(0, len(blob) - sz + 1, sz):
                st_name, st_value, st_size, st_info, st_other, st_shndx = struct.unpack(
                    e + 'IIIBBH', blob[i:i + 16])
                nm = self._str(strtab, st_name)
                out.append((nm, st_info, st_shndx, st_value, st_size))
        return out

    def exported(self, filt=None):
        """shndx != 0 且 STB 非 UNDEF 的、带名字的符号"""
        out = []
        for nm, info, shndx, val, size in self.dynsyms():
            if not nm:
                continue
            bind = info >> 4
            if shndx == 0:
                continue
            if bind in (1, 2):  # GLOBAL / WEAK
                if filt and not re.search(filt, nm, re.I):
                    continue
                out.append((nm, val, size))
        return out

    def imported(self, filt=None):
        out = []
        for nm, info, shndx, val, size in self.dynsyms():
            if not nm or shndx != 0:
                continue
            if filt and not re.search(filt, nm, re.I):
                continue
            out.append(nm)
        return out

    def strings(self, minlen=4, pattern=None):
        """扫描 rodata / data 段的可打印串"""
        res = set()
        for sec in ('.rodata', '.data', '.rodata1', '.data.rel.ro', '.text'):
            blob = self.blob(sec)
            for m in re.finditer(rb'[\x20-\x7e]{%d,}' % minlen, blob):
                s = m.group().decode('ascii', 'replace')
                if pattern and not re.search(pattern, s, re.I):
                    continue
                res.add(s)
        return sorted(res)


def main():
    args = [a for a in sys.argv[1:]]
    path = args[0]
    flags = set(args[1:])
    do_all = '--all' in flags or not flags
    el = ELF(path)
    print('=== %s (%s, %s) ===' % (os.path.basename(path),
                                   '64bit' if el.is64 else '32bit',
                                   'LE' if el.little else 'BE'))
    if do_all or '--need' in flags:
        print('--- DT_NEEDED ---')
        for n in el.needed():
            print('  ', n)
    if do_all or '--exp' in flags:
        print('--- EXPORTED (%d) ---' % len(el.exported()))
        for nm, val, size in el.exported():
            print('   %s' % nm)
    if do_all or '--imp' in flags:
        imps = el.imported()
        print('--- IMPORTED (%d) ---' % len(imps))
        for n in imps:
            print('  ', n)
    for a in args[1:]:
        if a.startswith('--str='):
            pat = a[6:]
            ss = el.strings(minlen=4, pattern=pat)
            print('--- STRINGS match %r (%d) ---' % (pat, len(ss)))
            for s in ss[:400]:
                print('  ', s)
    if '--sec' in flags:
        print('--- SECTIONS ---')
        for s in el.sections:
            print('  %-20s size=%d' % (s['name'], s['size']))


if __name__ == '__main__':
    main()

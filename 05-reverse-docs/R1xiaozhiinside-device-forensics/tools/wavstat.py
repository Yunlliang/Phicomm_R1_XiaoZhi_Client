#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
wavstat.py — R1 录音能量分析

用途
----
1) 分析 HAL dump（/sdcard/unidata/waked_file_*.wav）
   对比 4mic -> 2aec -> out 三段，看 AEC 到底削了多少 dB。
2) 分析 capsave 产物（files/cap_*.wav）
   判断小智 A 通路拿到的音频质量：底噪、人声、回声残留。

用法
----
    python3 wavstat.py a.wav b.wav ...          # 逐个出统计 + 分段能量
    python3 wavstat.py --seg 250 a.wav          # 分段改为 250ms
    python3 wavstat.py --csv out.csv a.wav      # 分段数据导出 csv

输出解读
--------
  rms    整体均方根（幅度）
  dBFS   以满量程为 0dB 的对数电平；-60dBFS 以下基本是底噪
  peak   最大绝对值（削波看它是否 >= 32767）
  分段   每 N 毫秒一个 rms，用来看「哪一段有声音」
"""
import sys, os, struct, math, csv


def parse_wav(path):
    d = open(path, 'rb').read()
    if d[:4] != b'RIFF':
        raise ValueError('%s: not RIFF' % path)
    i, ch, sr, bits = 12, 1, 16000, 16
    data = b''
    while i + 8 <= len(d):
        cid = d[i:i + 4]
        sz = struct.unpack('<I', d[i + 4:i + 8])[0]
        if cid == b'fmt ':
            _, ch, sr, _, _, bits = struct.unpack('<HHIIHH', d[i + 8:i + 24])
        elif cid == b'data':
            data = d[i + 8:i + 8 + sz]
        i += 8 + sz + (sz % 2)
    return ch, sr, bits, data


def samples(data, bits):
    if bits == 16:
        n = len(data) // 2
        return list(struct.unpack('<%dh' % n, data[:n * 2]))
    if bits == 32:
        n = len(data) // 4
        return [x >> 16 for x in struct.unpack('<%di' % n, data[:n * 4])]
    if bits == 8:
        return [(x - 128) << 8 for x in data]
    raise ValueError('bits=%d unsupported' % bits)


def rms(vals):
    if not vals:
        return 0.0
    return math.sqrt(sum(x * x for x in vals) / len(vals))


def to_db(v, full=32768.0):
    if v <= 0:
        return -99.0
    return 20 * math.log10(v / full)


def analyze(path, seg_ms=500, csv_writer=None):
    ch, sr, bits, data = parse_wav(path)
    s = samples(data, bits)
    nch = max(1, ch)
    total = len(s)
    dur = total / nch / float(sr) if sr else 0
    name = os.path.basename(path)

    print('=' * 68)
    print('%s' % name)
    print('  ch=%d  sr=%d  bits=%d  samples=%d  %.2f s' % (ch, sr, bits, total, dur))
    if total == 0:
        print('  (无音频数据 —— 只有 WAV 头)')
        return

    r_all = rms(s)
    pk = max(abs(x) for x in s)
    print('  rms=%.1f  (%.1f dBFS)   peak=%d  %s' %
          (r_all, to_db(r_all), pk, '<< 削波!' if pk >= 32767 else ''))

    # 逐通道
    if nch > 1:
        for c in range(nch):
            sub = s[c::nch]
            rc = rms(sub)
            print('    ch%d: rms=%.1f (%.1f dBFS) peak=%d' %
                  (c, rc, to_db(rc), max(abs(x) for x in sub)))

    # 分段（按通道 0 或全通道混合）
    step = int(sr * seg_ms / 1000.0) * nch
    if step <= 0:
        return
    print('  --- 分段 rms (%d ms) ---' % seg_ms)
    mono = s if nch == 1 else s[0::nch]
    segs = []
    idx = 0
    k = 0
    while idx < len(mono):
        seg = mono[idx:idx + int(sr * seg_ms / 1000.0)]
        if not seg:
            break
        r = rms(seg)
        segs.append((k * seg_ms / 1000.0, r, to_db(r)))
        if csv_writer:
            csv_writer.writerow([name, '%.2f' % (k * seg_ms / 1000.0),
                                 '%.1f' % r, '%.1f' % to_db(r)])
        idx += int(sr * seg_ms / 1000.0)
        k += 1
    # 只打印有内容的段（或全部，若段数少）
    show = segs if len(segs) <= 40 else segs[:40]
    for t, r, db in show:
        bar = '#' * min(50, int(max(0, db + 70) / 1.4))
        print('    %6.2fs  rms=%8.1f  %6.1f dBFS  %s' % (t, r, db, bar))
    if len(segs) > 40:
        print('    ... 共 %d 段（已截断显示）' % len(segs))


def main():
    args = sys.argv[1:]
    seg_ms = 500
    csv_path = None
    files = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == '--seg':
            i += 1
            seg_ms = int(args[i])
        elif a == '--csv':
            i += 1
            csv_path = args[i]
        else:
            files.append(a)
        i += 1
    if not files:
        print(__doc__)
        return 1
    cf = None
    w = None
    if csv_path:
        cf = open(csv_path, 'w', newline='')
        w = csv.writer(cf)
        w.writerow(['file', 't_sec', 'rms', 'dbfs'])
    try:
        for f in files:
            try:
                analyze(f, seg_ms, w)
            except Exception as e:
                print('%s: ERROR %s' % (f, e))
    finally:
        if cf:
            cf.close()
    return 0


if __name__ == '__main__':
    sys.exit(main())

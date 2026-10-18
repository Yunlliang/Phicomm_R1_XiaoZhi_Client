#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""WAV 底噪频谱分析：判断本底噪声是白噪 / 工频 / 高频开关噪声 / 低频隆起。

用法: python spec.py <file.wav> [起始秒] [分析秒数]
输出: 基本信息 + 分频段能量(dBFS) + 平坦度判据
"""
import sys, math, wave, struct, os

def rms(x):
    if not x:
        return 0.0
    return math.sqrt(sum(float(v) * v for v in x) / len(x))

def dbfs(v, full=32768.0):
    if v <= 0:
        return -120.0
    return 20.0 * math.log10(v / full)

def fft_mag(block):
    """迭代式 radix-2 FFT，返回幅度谱（前半）"""
    n = len(block)
    re = [float(v) for v in block]
    im = [0.0] * n
    j = 0
    for i in range(1, n):
        bit = n >> 1
        while j & bit:
            bit >>= 1
        j |= bit
        if i < j:
            re[i], re[j] = re[j], re[i]
            im[i], im[j] = im[j], im[i]
    length = 2
    while length <= n:
        ang = -2.0 * math.pi / length
        wr = math.cos(ang); wi = math.sin(ang)
        for i in range(0, n, length):
            cr, ci = 1.0, 0.0
            for k in range(i, i + length // 2):
                ur, ui = re[k], im[k]
                vr, vi = re[k + length // 2], im[k + length // 2]
                tre = vr * cr - vi * ci
                tim = vr * ci + vi * cr
                re[k], im[k] = ur + tre, ui + tim
                re[k + length // 2], im[k + length // 2] = ur - tre, ui - tim
                cr, ci = cr * wr - ci * wi, cr * wi + ci * wr
        length <<= 1
    half = n // 2
    return [math.hypot(re[i], im[i]) / half for i in range(half)]

def main():
    path = sys.argv[1]
    t0 = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
    dur = float(sys.argv[3]) if len(sys.argv) > 3 else 3.0

    w = wave.open(path, 'rb')
    ch = w.getnchannels(); sw = w.getsampwidth(); fs = w.getframerate()
    n = w.getnframes()
    print("=== %s ===" % os.path.basename(path))
    print("ch=%d  width=%dB  fs=%d  frames=%d  (%.1f s)" % (ch, sw, fs, n, n / float(fs)))
    if sw != 2:
        print("!! 仅支持 16bit，退出"); return

    start = int(t0 * fs)
    want = int(dur * fs)
    w.setpos(start)
    raw = w.readframes(min(want, n - start))
    w.close()
    vals = list(struct.unpack('<%dh' % (len(raw) // 2), raw))
    mono = vals[0::ch] if ch > 1 else vals

    print("分析窗口: %.1fs ~ %.1fs (%d 样本)" % (t0, t0 + len(mono) / float(fs), len(mono)))
    print("整体 rms = %.1f  (%.2f dBFS)   peak=%d" % (
        rms(mono), dbfs(rms(mono)), max(abs(v) for v in mono)))

    # 逐帧 FFT 平均
    N = 1024
    nfr = len(mono) // N
    if nfr < 1:
        print("窗口太短"); return
    acc = [0.0] * (N // 2)
    for f in range(nfr):
        sp = fft_mag(mono[f * N:(f + 1) * N])
        for i in range(N // 2):
            acc[i] += sp[i] * sp[i]
    ps = [math.sqrt(a / nfr) for a in acc]
    bin_hz = fs / float(N)

    def band(lo, hi):
        s = 0.0; cnt = 0
        for i in range(1, N // 2):
            hz = i * bin_hz
            if lo <= hz < hi:
                s += ps[i] * ps[i]; cnt += 1
        if cnt == 0:
            return 0.0
        return dbfs(math.sqrt(s / cnt))

    print("\n--- 分频段平均能量 (dBFS) ---")
    bands = [(0, 100), (100, 300), (300, 1000), (1000, 2000), (2000, 4000),
             (4000, 6000), (6000, 8000)]
    res = []
    for lo, hi in bands:
        v = band(lo, hi)
        res.append((lo, hi, v))
        print("  %5d-%5d Hz : %7.2f dBFS" % (lo, hi, v))

    # 峰值谱线（找工频/开关电源类尖峰）
    print("\n--- 最强谱线 TOP8 ---")
    idx = sorted(range(1, N // 2), key=lambda i: ps[i], reverse=True)[:8]
    for i in sorted(idx):
        print("  %7.1f Hz : %7.2f dBFS" % (i * bin_hz, dbfs(ps[i])))

    # 平坦度：各频段最大值与最小值的差，差小=白噪，差大=有色噪声
    vals_ = [v for _, _, v in res if v > -120]
    if vals_:
        spread = max(vals_) - min(vals_)
        print("\n--- 判据 ---")
        print("  频段间最大差值 = %.1f dB" % spread)
        if spread < 12:
            print("  ⇒ 近似白噪（平坦）：更像量化/增益/热噪，NS 谱减法好削")
        else:
            print("  ⇒ 有色噪声（有隆起/尖峰）：先定位峰值来源，NS 也能削但可能留musical noise")

if __name__ == '__main__':
    main()

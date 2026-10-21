# patch package id inside the binary AndroidManifest.xml of an APK (string pool rebuild)
import sys, io, zipfile, struct, os, shutil

MAP = {
    "com.termux": "zhuxin.astrbot",
    "com.termux.RUN_COMMAND": "zhuxin.astrbot.RUN_COMMAND",
    "com.termux.documents": "zhuxin.astrbot.documents",
    "com.termux.filereceiver": "zhuxin.astrbot.filereceiver",
    "com.termux.files": "zhuxin.astrbot.files",
    "com.termux.permission.RUN_COMMAND": "zhuxin.astrbot.permission.RUN_COMMAND",
    "com.termux.HomeActivity": "zhuxin.astrbot.HomeActivity",
}

def parse_pool(d):
    t, hs, sz, sc, styc, flags, sstart, stystart = struct.unpack_from("<HHIIIIII", d, 0)
    assert t == 0x0001, hex(t)
    assert styc == 0
    enc = "utf-8" if (flags & 0x100) else "utf-16-le"
    base = sstart
    out = []
    for i in range(sc):
        o = struct.unpack_from("<I", d, hs + 4 * i)[0]
        q = base + o
        if flags & 0x100:
            l1 = d[q]; q += 1
            if l1 & 0x80: l1 = ((l1 & 0x7f) << 8) | d[q]; q += 1
            l2 = d[q]; q += 1
            if l2 & 0x80: l2 = ((l2 & 0x7f) << 8) | d[q]; q += 1
            out.append(d[q:q + l2].decode("utf-8", "replace"))
        else:
            l = struct.unpack_from("<H", d, q)[0]; q += 2
            if l & 0x8000:
                l = ((l & 0x7fff) << 16) | struct.unpack_from("<H", d, q)[0]; q += 2
            out.append(d[q:q + l * 2].decode("utf-16-le", "replace"))
    return out, sz

def build_pool(strings):
    n = len(strings)
    header_size = 28
    data = b""
    offsets = []
    for s in strings:
        offsets.append(len(data))
        raw = s.encode("utf-16-le")
        ln = len(raw) // 2
        if ln > 0x7fff:
            data += struct.pack("<HH", 0x8000 | (ln >> 16), ln & 0xffff)
        else:
            data += struct.pack("<H", ln)
        data += raw + b"\x00\x00"
    while len(data) % 4:
        data += b"\x00"
    strings_start = header_size + 4 * n
    size = strings_start + len(data)
    return (struct.pack("<HHIIIIII", 0x0001, header_size, size, n, 0, 0, strings_start, 0)
            + b"".join(struct.pack("<I", o) for o in offsets) + data)

def patch_manifest_bytes(d):
    assert struct.unpack_from("<H", d, 0)[0] == 0x0003
    total = struct.unpack_from("<I", d, 4)[0]
    t, hs, sz = struct.unpack_from("<HHI", d, 8)
    assert t == 0x0001, hex(t)
    strings, old_sz = parse_pool(d[8:8 + sz])
    changed = 0
    for i, s in enumerate(strings):
        if s in MAP:
            strings[i] = MAP[s]; changed += 1
    newpool = build_pool(strings)
    out = bytearray(d[:8]) + newpool + d[8 + old_sz:]
    struct.pack_into("<I", out, 4, len(out))
    return bytes(out), changed, len(strings)

def main():
    apk = sys.argv[1]
    tmp = apk + ".tmp"
    zin = zipfile.ZipFile(apk)
    zout = zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED)
    changed = 0
    for i in zin.infolist():
        data = zin.read(i.filename)
        if i.filename == "AndroidManifest.xml":
            data, changed, n = patch_manifest_bytes(data)
            print("manifest strings=%d changed=%d" % (n, changed))
        zi = zipfile.ZipInfo(i.filename, date_time=i.date_time)
        zi.external_attr = i.external_attr
        zi.internal_attr = i.internal_attr
        zi.create_system = i.create_system
        zi.compress_type = i.compress_type
        zi.comment = i.comment
        zout.writestr(zi, data)
    zout.close(); zin.close()
    shutil.move(tmp, apk)
    print("patched", apk, os.path.getsize(apk), "bytes, changed strings", changed)

main()

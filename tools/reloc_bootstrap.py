# relocate a Termux bootstrap .so from /data/data/com.termux to a new package prefix
# usage: python reloc_bootstrap.py <in.so> <out.so> <new.package> [pristine.so] [--report]
#
# Layout of libtermux-bootstrap.so (夹心结构，动不得):
#   [0 .. 1327]              ELF header + program headers + 小段
#   [1328 .. EOCD)           ZIP local headers + data
#   [EOCD]                   zip 结束
#   [EOCD+5 .. shoff)        动态段 .dynsym/.dynstr/.dynamic 等 section 11-21
#   [shoff .. EOF)           section header 表（22 x 64 = 1408 B，e_shoff=29391584）
# 因为 TermuxInstaller.loadZipBytes() 先 System.loadLibrary("termux-bootstrap") 再取字节，
# 该 .so 必须能被 dlopen：section header 表不能移位、不能丢。
# 所以重打包只能「原地换 ZIP + 用零填充对齐」，尾部（动态段 + 节头）必须从 pristine 原样搬过来，
# 使所有绝对偏移与 pristine 完全一致。砍掉尾部会报
#   dlopen failed: ... has invalid shdr offset/size: 29391584/1408
import sys, io, os, re, zipfile, tempfile, struct

def find_eocd(d):
    i = d.rfind(b"PK\x05\x06")
    if i < 0:
        raise SystemExit("no EOCD found")
    csize = struct.unpack_from("<H", d, i + 20)[0]
    return i + 22 + csize

def read_zip(path):
    d = open(path, "rb").read()
    end = find_eocd(d)
    z = zipfile.ZipFile(io.BytesIO(d[:end]))
    return d, z, end

def patch_elf(data, newlib):
    if not newlib:
        return data, False
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".elf")
    tmp.write(data); tmp.close()
    try:
        import lief
        b = lief.parse(tmp.name)
        if b is None or not isinstance(b, lief.ELF.Binary):
            return data, False
        changed = False
        for tag in (lief.ELF.DynamicEntry.TAG.RUNPATH, lief.ELF.DynamicEntry.TAG.RPATH):
            if b.has(tag):
                e = b.get(tag)
                if "com.termux" in "".join(e.paths):
                    e.paths = [newlib]
                    changed = True
        if not changed:
            return data, False
        out = tmp.name + ".out"
        b.write(out)
        nd = open(out, "rb").read()
        os.unlink(out)
        return nd, True
    finally:
        try: os.unlink(tmp.name)
        except OSError: pass

STAGE2 = "etc/termux/bootstrap/termux-bootstrap-second-stage.sh"

def patch_stage2(data):
    """让第二阶段的 postinst 失败不再中断整个 bootstrap（实测 22:44 的 Bootstrap Error）。
    nano 的 postinst 调 update-alternatives，而那个 ELF 里编译期写死了
    /data/data/com.termux/... 路径（长度不同，没法原地替换），必然失败；
    TermuxInstaller 见第二阶段返回非 0 就把整个 usr/ 删掉重来。
    我们只需要 bash/proot/tar/curl 这些运行时工具，nano 的 alternatives 链接无所谓。"""
    s = data.decode("utf-8", "replace")
    marker = "log_error \"Failed to run '$package_name' package postinst\""
    i = s.find(marker)
    if i < 0:
        return data, 0
    j = s.find("exit $return_value", i)
    if j < 0 or j - i > 200:
        return data, 0
    s = s[:j] + "true" + s[j + len("exit $return_value"):]
    return s.encode("utf-8"), 1

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    src, dst, pkg = args[0], args[1], args[2]
    pristine = args[3] if len(args) > 3 else ""
    report = "--report" in sys.argv
    old = b"/data/data/com.termux"
    new = ("/data/data/" + pkg).encode()
    newlib = "/data/data/%s/files/usr/lib" % pkg
    d, z, end = read_zip(src)
    head = d[:1328]
    # 尾部（动态段 + section header 表）必须按 pristine 的绝对偏移原样保留
    if not pristine:
        raise SystemExit("pristine .so path required (4th arg): its tail holds the ELF dynamic section + shdr")
    pd = open(pristine, "rb").read()
    tail_start = find_eocd(pd)
    tail = pd[tail_start:]
    if len(tail) < 1024 or len(pd) != len(d):
        raise SystemExit("pristine tail looks wrong: tail=%d pristine=%d in=%d" % (len(tail), len(pd), len(d)))
    infos = z.infolist()
    buf = io.BytesIO()
    zout = zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED)
    n_elf = n_txt = n_skip = 0
    for i in infos:
        data = b"" if i.is_dir() else z.read(i)
        if not i.is_dir():
            if data[:4] == b"\x7fELF":
                nd, ch = patch_elf(data, newlib)
                if ch: n_elf += 1
                else: n_skip += 1
                data = nd
            elif old in data and b"\x00" not in data:
                data = data.replace(old, new)
                n_txt += 1
            elif old in data:
                n_skip += 1
        if i.filename == STAGE2 and not i.is_dir():
            data, _n2 = patch_stage2(data)
            if _n2 == 0:
                raise SystemExit("stage2 patch anchor not found")
        zi = zipfile.ZipInfo(i.filename, date_time=i.date_time)
        zi.external_attr = i.external_attr
        zi.internal_attr = i.internal_attr
        zi.create_system = i.create_system
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.comment = i.comment
        if i.is_dir():
            zout.writestr(zi, b"")
        else:
            zout.writestr(zi, data)
    zout.close()
    body = buf.getvalue()
    if 1328 + len(body) > tail_start:
        raise SystemExit("new zip too big: %d > %d" % (1328 + len(body), tail_start))
    pad = tail_start - 1328 - len(body)
    with open(dst, "wb") as f:
        f.write(head); f.write(body); f.write(b"\x00" * pad); f.write(tail)
    print("entries=%d elf_patched=%d text_patched=%d untouched=%d" % (len(infos), n_elf, n_txt, n_skip))
    print("zip=%d pad=%d tail=%d" % (len(body), pad, len(tail)))
    print("size %d -> %d head_ok=%s tail_ok=%s" % (len(d), os.path.getsize(dst),
          open(dst, "rb").read(1328) == head, open(dst, "rb").read()[-len(tail):] == tail))
    if report:
        # sanity: reopen the new file sequentially
        d2, z2, end2 = read_zip(dst)
        print("reopen ok entries=%d names_equal=%s" % (len(z2.infolist()), [a.filename for a in z2.infolist()] == [a.filename for a in infos]))
        i = [x for x in z2.infolist() if x.filename == "bin/bash"]
        if i:
            # lief 只在 patch_elf() 的 try 里 import；--report 这段原来直接引用，
            # 没装 lief 的环境会在校验阶段抛 NameError，把已经生成好的 .so 判成失败。
            try:
                import lief
                _tmp2 = tempfile.NamedTemporaryFile(delete=False, suffix=".elf")
                _tmp2.write(z2.read(i[0]))
                _tmp2.close()
                b = lief.parse(_tmp2.name)
                print("bin/bash 可解析=%s" % (b is not None))
                os.unlink(_tmp2.name)
            except Exception as e:
                print("bin/bash 解析跳过：%s" % e)
main()

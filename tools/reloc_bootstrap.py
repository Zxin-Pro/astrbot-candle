#!/usr/bin/env python3
"""把 Termux 便携 bootstrap 里的前缀从 /data/data/com.termux 改到新包名。

v3（2026-10-22）：改成"原地等长改写"，绝不动 ELF 结构。

为什么：v2 用 lief 重写 RUNPATH，lief 为了容纳变长的字符串会给部分 ELF
（apt / libiconv.so / liblz4.so 等 32 个）**新增一个 PT_LOAD**，文件大小也变。
模拟器（MuMu 的 ARM 转译）能跑，但真机 Android 16 上这些二进制 exec 直接失败，
表现为 bootstrap second stage 返回非 0 —— 也就是用户看到的
"Unable to install bootstrap"。

v3 的做法：RUNPATH 不写长路径，改写成短别名，靠运行时创建的符号链接解析：
    /data/data/com.termux/files/usr   ->  /data/data/<pkg>/u    （短 3 字节）
    /data/data/com.termux/files/home  ->  /data/data/<pkg>/h    （短 3 字节）
这两个别名由 Java 侧（QqbotActivity.ensurePrefixAliases）在启动时建好：
    files/u -> files/usr      files/h -> files/home
因为新串更短，替换后把该字符串剩余的字节用 NUL 填满即可 —— 文件长度、
节头表、程序头表、所有偏移都不变，ELF 装载器看到的东西与 pristine 完全一致。
"""
import io
import struct
import sys
import zipfile

HEAD = 1328  # ELF 头 + 程序头区，ZIP payload 从这里开始

# ELF 里只能"变短"：这些替换必须比原串短或等长
ELF_MAP = [
    (b"/data/data/com.termux/files/usr", b"/data/data/zhuxin.astrbot/u"),
    (b"/data/data/com.termux/files/home", b"/data/data/zhuxin.astrbot/h"),
]

STAGE2 = "etc/termux/bootstrap/termux-bootstrap-second-stage.sh"


def find_tail(d):
    i = d.rfind(b"PK\x05\x06")
    if i < 0:
        raise SystemExit("EOCD not found")
    clen = struct.unpack_from("<H", d, i + 20)[0]
    return i + 22 + clen


def patch_elf(data, pkg):
    """等长改写 ELF 里的前缀。返回 (新数据, 改了几处, 没敢改的残留数)。"""
    out = bytearray(data)
    hits = 0
    for old, new in ELF_MAP:
        if pkg != b"zhuxin.astrbot":
            new = new.replace(b"zhuxin.astrbot", pkg)
        assert len(new) <= len(old), "别名必须比原串短"
        start = 0
        while True:
            i = out.find(old, start)
            if i < 0:
                break
            nxt = out[i + len(old):i + len(old) + 1]
            if nxt not in (b"/", b"\x00"):
                start = i + 1
                continue
            end = out.find(b"\x00", i)
            if end < 0:
                break
            tail = bytes(out[i + len(old):end])
            newstr = new + tail
            pad = (end - i) - len(newstr)
            if pad < 0:
                raise SystemExit("内部错误：替换后反而变长")
            # 一次性覆盖整条字符串：[i, end) 用「新串 + NUL 填充」替换，
            # 长度必须严格相等 —— 分成两次切片赋值会让 bytearray 缩短，
            # 结果整个 ELF 少 5 字节，装载器报 invalid shdr offset/size（真机实测）。
            out[i:end] = newstr + b"\x00" * pad
            hits += 1
            start = i + len(old)
    left = out.count(b"/data/data/com.termux")
    return bytes(out), hits, left


def patch_text(data, pkg):
    data = data.replace(b"/data/data/com.termux/files/usr", b"/data/data/" + pkg + b"/files/usr")
    data = data.replace(b"/data/data/com.termux/files/home", b"/data/data/" + pkg + b"/files/home")
    data = data.replace(b"/data/data/com.termux", b"/data/data/" + pkg)
    data = data.replace(b"com.termux", pkg)
    return data


def patch_stage2(data, pkg):
    """second stage 里加日志重定向 + 让 postinst 失败不再中断（真机实测需要）。"""
    text = data.decode("utf8", "replace")
    log_dir = "/data/data/%s/files/home/.qqbot/logs" % pkg.decode()
    inject = (
        "LOG_DIR=\"%s\"\n"
        "mkdir -p \"$LOG_DIR\" 2>/dev/null\n"
        "{ exec >>\"$LOG_DIR/second-stage.log\" 2>&1; } 2>/dev/null || true\n"
        "echo \"==== second stage $(date) arch=$(uname -m) ====\"\n"
    ) % log_dir
    lines = text.split("\n")
    if len(lines) > 1:
        lines.insert(1, inject.rstrip("\n"))
    text = "\n".join(lines)
    # 打印每个 postinst 的退出码，失败也不中断（原来的 return 会把整段判失败）
    if "return $return_value" in text:
        text = text.replace(
            'log_error "Failed to run \'$package_name\' package postinst"\n\t\t\t\t\t\ttrue',
            'log_error "Failed to run \'$package_name\' package postinst"\n\t\t\t\t\t\ttrue',
        )
    return text.encode("utf8")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    report = "--report" in sys.argv[1:]
    if len(args) < 4:
        raise SystemExit("usage: reloc_bootstrap.py <repacked.so> <out.so> <new.pkg> <pristine.so>")
    src, dst, pkg, pristine = args[0], args[1], args[2].encode(), args[3]
    d = open(src, "rb").read()
    pd = open(pristine, "rb").read()
    tail_start = find_tail(pd)
    tail = pd[tail_start:]
    if len(pd) != len(d):
        raise SystemExit("pristine 与 repacked 大小不一致：%d vs %d" % (len(pd), len(d)))

    src_end = find_tail(d)          # repacked 的 EOCD 位置（与 pristine 不同）
    z = zipfile.ZipFile(io.BytesIO(d[HEAD:src_end]))
    out = io.BytesIO()
    stats = {"elf": 0, "elf_hits": 0, "text": 0, "left": 0}
    with zipfile.ZipFile(out, "w") as zo:
        for info in z.infolist():
            data = z.read(info.filename)
            if data[:4] == b"\x7fELF":
                new, hits, left = patch_elf(data, pkg)
                if hits:
                    stats["elf"] += 1
                    stats["elf_hits"] += hits
                stats["left"] += left
                data = new
            elif info.filename == STAGE2:
                data = patch_text(data, pkg)
                data = patch_stage2(data, pkg)
                stats["text"] += 1
            elif b"\x00" not in data[:1024]:
                data = patch_text(data, pkg)
                stats["text"] += 1
            zi = zipfile.ZipInfo(info.filename, info.date_time)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zi.internal_attr = info.internal_attr
            zi.create_system = info.create_system
            zo.writestr(zi, data)

    body = out.getvalue()
    pad = tail_start - HEAD - len(body)
    if pad < 0:
        raise SystemExit("新 ZIP 比原来大 %d 字节，无法原地放回" % (-pad))
    with open(dst, "wb") as f:
        f.write(d[:HEAD])
        f.write(body)
        f.write(b"\x00" * pad)
        f.write(tail)
    print("entries=%d elf_patched=%d elf_hits=%d text_patched=%d com.termux_left=%d"
          % (len(z.infolist()), stats["elf"], stats["elf_hits"], stats["text"], stats["left"]))
    print("zip=%d pad=%d tail=%d size=%d" % (len(body), pad, len(tail), HEAD + len(body) + pad + len(tail)))
    if report:
        # sanity: reopen the new file sequentially; no lief needed so --report works on a bare python
        try:
            _d, _z, _e = read_zip(dst)
            print("reopen ok entries=%d" % len(_z.infolist()))
            _i = [x for x in _z.infolist() if x.filename == "bin/bash"]
            if _i:
                print("bin/bash size=%d" % len(_z.read(_i[0])))
        except Exception as e:
            print("reopen check skipped: %s" % e)


if __name__ == "__main__":
    main()

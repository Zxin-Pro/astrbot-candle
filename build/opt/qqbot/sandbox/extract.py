#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""沙盒内可靠的解包工具。

为什么不用 tar / dpkg-deb / unzip：
  proot 5.5.0 对「带目录 fd 的 *at 系统调用」（mkdirat / fchmodat / openat(dirfd)）
  翻译不正确。GNU tar 1.35 解包时会报
      tar: ./opt/QQ: Cannot mkdir: No such file or directory
  并把整个目录漏掉；dpkg-deb -x 内部就是调 tar，所以同样失败
  （apt/dpkg 安装 .deb 走 libdpkg 内置解包，因此 apt 反而正常）。
  Python 的 tarfile / zipfile 走普通路径解析，实测解包 Linux QQ 的 1586 个条目 0 失败。

用法：
  extract.py deb <deb 路径> <目标目录>
  extract.py tar <tar / tar.gz / tar.xz 路径> <目标目录>
  extract.py zip <zip 路径> <目标目录>
"""

import io
import os
import stat
import sys
import tarfile
import zipfile

CHUNK = 1 << 20


class Slice(object):
    """把文件的 [off, off+size) 区间包装成一个可 seek 的只读流。"""

    def __init__(self, fh, off, size):
        self._fh = fh
        self._off = off
        self._size = size
        self._pos = 0

    def readable(self):
        return True

    def writable(self):
        return False

    def seekable(self):
        return True

    def read(self, n=-1):
        if n is None or n < 0:
            n = self._size - self._pos
        n = min(n, self._size - self._pos)
        if n <= 0:
            return b''
        self._fh.seek(self._off + self._pos)
        chunk = self._fh.read(n)
        self._pos += len(chunk)
        return chunk

    def readinto(self, b):
        data = self.read(len(b))
        b[:len(data)] = data
        return len(data)

    def seek(self, pos, whence=0):
        if whence == 0:
            self._pos = pos
        elif whence == 1:
            self._pos += pos
        elif whence == 2:
            self._pos = self._size + pos
        else:
            raise ValueError('bad whence')
        return self._pos

    def tell(self):
        return self._pos

    def flush(self):
        pass

    def close(self):
        pass


def ar_find(path, wanted):
    """在 ar 归档（.deb）里找成员，返回 (偏移, 大小)。"""
    with open(path, 'rb') as fh:
        magic = fh.read(8)
        if magic != b'!<arch>\n':
            raise SystemExit('不是 ar 归档：%s' % path)
        off = 8
        while True:
            fh.seek(off)
            hdr = fh.read(60)
            if len(hdr) < 60:
                break
            name = hdr[0:16].decode('ascii', 'replace').strip().rstrip('/')
            try:
                size = int(hdr[48:58].decode('ascii').strip())
            except ValueError:
                break
            if name in wanted:
                return name, off + 60, size
            off += 60 + size + (size % 2)
    raise SystemExit('在 %s 中找不到 %s' % (path, list(wanted)))


def _mode_for(name):
    if name.endswith('.xz'):
        return 'r:xz'
    if name.endswith('.gz') or name.endswith('.tgz'):
        return 'r:gz'
    if name.endswith('.bz2'):
        return 'r:bz2'
    return 'r:'


def _drain(tf, dest):
    seen = 0
    errs = []
    for member in tf:
        seen += 1
        try:
            tf.extract(member, dest, filter='fully_trusted')
        except Exception as exc:  # noqa: BLE001
            errs.append('%s: %s' % (member.name, exc))
    tf.close()
    return seen, errs


def extract_tar_fileobj(fileobj, mode, dest):
    os.makedirs(dest, exist_ok=True)
    return _drain(tarfile.open(fileobj=fileobj, mode=mode), dest)


def extract_tar(path, dest):
    os.makedirs(dest, exist_ok=True)
    return _drain(tarfile.open(name=path, mode=_mode_for(path)), dest)


def extract_deb(path, dest):
    names = ('data.tar.xz', 'data.tar.gz', 'data.tar.bz2', 'data.tar')
    name, off, size = ar_find(path, names)
    fh = open(path, 'rb')
    try:
        return extract_tar_fileobj(Slice(fh, off, size), _mode_for(name), dest)
    finally:
        fh.close()


def extract_zip(path, dest):
    os.makedirs(dest, exist_ok=True)
    zf = zipfile.ZipFile(path)
    seen = 0
    errs = []
    for info in zf.infolist():
        seen += 1
        name = info.filename
        target = os.path.join(dest, name)
        mode = (info.external_attr >> 16) & 0xFFFF
        try:
            if name.endswith('/'):
                os.makedirs(target, exist_ok=True)
                continue
            parent = os.path.dirname(target)
            if parent:
                os.makedirs(parent, exist_ok=True)
            if stat.S_ISLNK(mode):
                linkto = zf.read(info).decode('utf-8', 'replace')
                if os.path.lexists(target):
                    os.remove(target)
                os.symlink(linkto, target)
                continue
            with zf.open(info) as src, open(target, 'wb') as dst:
                while True:
                    buf = src.read(CHUNK)
                    if not buf:
                        break
                    dst.write(buf)
            if mode:
                try:
                    os.chmod(target, mode & 0o7777)
                except OSError:
                    pass
        except Exception as exc:  # noqa: BLE001
            errs.append('%s: %s' % (name, exc))
    zf.close()
    return seen, errs


def main():
    if len(sys.argv) < 4:
        raise SystemExit('用法: extract.py deb|tar|zip <归档> <目标目录>')
    kind, src, dest = sys.argv[1], sys.argv[2], sys.argv[3]
    if kind == 'deb':
        seen, errs = extract_deb(src, dest)
    elif kind == 'tar':
        seen, errs = extract_tar(src, dest)
    elif kind == 'zip':
        seen, errs = extract_zip(src, dest)
    else:
        raise SystemExit('未知类型：%s' % kind)
    print('[extract] %s -> %s 条目 %d，失败 %d' % (src, dest, seen, len(errs)))
    for line in errs[:10]:
        print('[extract] 警告: %s' % line)
    return 0


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把仓库里的网页控制台静态资源同步进 iOS 工程目录。

做两件事：
  1. ui/assets/qqbot/  ->  ios/AstrBotIOS/Resources/web/
     先删后拷，保证目标目录不残留旧文件；跳过 .map、node_modules、隐藏文件。
  2. ios/shared/AndroidShim.js -> ios/AstrBotIOS/Resources/shared/AndroidShim.js
     代码里 Bundle.main.resourceURL + "shared/AndroidShim.js" 读的就是这一份。

脚本自身会做一次拷贝后校验（逐个文件比字节数 + 断言 index.html 存在且大小一致），
校验不过就以非零码退出，方便 CI 直接卡住。

用法：
    python ios/tools/sync_web.py
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC_WEB = REPO / "ui" / "assets" / "qqbot"
DST_WEB = REPO / "ios" / "AstrBotIOS" / "Resources" / "web"
SRC_SHIM = REPO / "ios" / "shared" / "AndroidShim.js"
DST_SHIM = REPO / "ios" / "AstrBotIOS" / "Resources" / "shared" / "AndroidShim.js"

# 不往包里塞的东西：sourcemap 对 App 没用，node_modules 是构建残留。
SKIP_SUFFIX = {".map"}
SKIP_DIRS = {"node_modules", "__pycache__", ".git"}
SKIP_FILES = {"Thumbs.db", "desktop.ini"}


def should_skip(rel: Path) -> bool:
    """rel 是相对源目录的路径；命中即跳过。"""
    parts = rel.parts
    if any(p.startswith(".") for p in parts):
        return True
    if any(p in SKIP_DIRS for p in parts[:-1]):
        return True
    if rel.name in SKIP_FILES:
        return True
    if rel.suffix.lower() in SKIP_SUFFIX:
        return True
    return False


def collect(src: Path) -> list[Path]:
    out = []
    for p in sorted(src.rglob("*")):
        if not p.is_file():
            continue
        if should_skip(p.relative_to(src)):
            continue
        out.append(p)
    return out


def safe_rmtree(target: Path) -> None:
    """只允许删 Resources/web 这个位置，防止路径写错把源码目录删光。"""
    allowed = (REPO / "ios" / "AstrBotIOS" / "Resources").resolve()
    resolved = target.resolve()
    if resolved.parent != allowed:
        raise SystemExit(f"[web] 拒绝删除非预期目录: {resolved}")
    if resolved.exists():
        shutil.rmtree(resolved)


def sync_web() -> tuple[int, int]:
    if not SRC_WEB.is_dir():
        raise SystemExit(f"[web] 源目录不存在: {SRC_WEB}")

    files = collect(SRC_WEB)
    if not files:
        raise SystemExit(f"[web] 源目录里没有可拷贝的文件: {SRC_WEB}")

    total = sum(f.stat().st_size for f in files)

    print(f"[web] 源目录: {SRC_WEB}")
    print(f"[web] 目标目录: {DST_WEB}")
    safe_rmtree(DST_WEB)
    DST_WEB.mkdir(parents=True, exist_ok=True)

    for f in files:
        rel = f.relative_to(SRC_WEB)
        dst = DST_WEB / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, dst)

    print(f"[web] 清空后拷入 {len(files)} 个文件, 共 {total} 字节")
    return len(files), total


def verify_web(expected: list[Path]) -> None:
    """逐个文件比对字节数，顺带断言 index.html 存在且与源文件同大小。"""
    problems = []
    for f in expected:
        rel = f.relative_to(SRC_WEB)
        dst = DST_WEB / rel
        if not dst.is_file():
            problems.append(f"缺失: {rel.as_posix()}")
            continue
        a, b = f.stat().st_size, dst.stat().st_size
        if a != b:
            problems.append(f"大小不一致: {rel.as_posix()} {a} != {b}")

    index_src = SRC_WEB / "index.html"
    index_dst = DST_WEB / "index.html"
    if not index_dst.is_file():
        problems.append("缺失: index.html")
    else:
        sa, sb = index_src.stat().st_size, index_dst.stat().st_size
        if sa != sb:
            problems.append(f"index.html 大小不一致 {sa} != {sb}")
        else:
            print(f"[web] index.html 源 {sa} 字节 / 目标 {sb} 字节 一致")
            # 只读第一行做冒烟断言，绝不整读 479KB
            with index_dst.open("r", encoding="utf-8", errors="replace") as fh:
                head = fh.readline().strip()
            if "<!doctype html" not in head.lower():
                problems.append(f"index.html 首行不像 HTML: {head[:60]!r}")

    extra = []
    for p in sorted(DST_WEB.rglob("*")):
        if p.is_file() and p.relative_to(DST_WEB) not in {f.relative_to(SRC_WEB) for f in expected}:
            extra.append(p.relative_to(DST_WEB).as_posix())
    if extra:
        print(f"[web] 提示: 目标目录有 {len(extra)} 个源目录里没有的文件: {', '.join(extra[:5])}")

    if problems:
        for msg in problems:
            print(f"[web] 校验失败: {msg}", file=sys.stderr)
        raise SystemExit(1)


def sync_shim() -> int:
    if not SRC_SHIM.is_file():
        raise SystemExit(f"[shim] 源文件不存在: {SRC_SHIM}")
    DST_SHIM.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(SRC_SHIM, DST_SHIM)
    size = DST_SHIM.stat().st_size
    if size != SRC_SHIM.stat().st_size:
        raise SystemExit("[shim] 拷贝后大小不一致")
    print(f"[shim] {SRC_SHIM.relative_to(REPO).as_posix()} -> "
          f"{DST_SHIM.relative_to(REPO).as_posix()} ({size} 字节)")
    return size


def main() -> int:
    count, total = sync_web()
    verify_web(collect(SRC_WEB))
    shim_size = sync_shim()
    print(f"SYNC OK  web_files={count} web_bytes={total} shim_bytes={shim_size}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

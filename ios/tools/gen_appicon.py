#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 ui/assets/qqbot/appicon.png 生成 iOS 的 1024x1024 AppIcon。

iOS 的 App 图标必须是「不透明正方形」，圆角由系统蒙版裁出来，所以这里不做圆角，
只在 1024 的画布上把原图等比缩到约 900px 居中，四周铺 #F7F9FC 的浅色底，
视觉上就是一张带边距的方形图标，套上系统圆角后正好。

源图只有 120x120，放大到 900 会糊，所以补一道轻微的 USM 锐化（radius 1.5 / 40%），
只是让边缘不至于完全奶油，不做超分。

输出（都是 Assets.xcassets 里的标准结构）：
    Resources/Assets.xcassets/Contents.json
    Resources/Assets.xcassets/AppIcon.appiconset/Contents.json
    Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png

用法：
    python ios/tools/gen_appicon.py
    python ios/tools/gen_appicon.py --check    # 只校验已生成的图标，不重写
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageFilter

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / "ui" / "assets" / "qqbot" / "appicon.png"
ASSETS = REPO / "ios" / "AstrBotIOS" / "Resources" / "Assets.xcassets"
ICONSET = ASSETS / "AppIcon.appiconset"
ICON_NAME = "AppIcon-1024.png"
ICON_PATH = ICONSET / ICON_NAME

TILE = 1024          # iOS 单尺寸图标边长
CONTENT = 900        # 原图占用的最大边长，剩下的 62px 是四周留白
BG = (247, 249, 252)  # #F7F9FC

ASSETS_CONTENTS = {"info": {"author": "xcode", "version": 1}}
APPICON_CONTENTS = {
    "images": [
        {
            "filename": ICON_NAME,
            "idiom": "universal",
            "platform": "ios",
            "size": "1024x1024",
        }
    ],
    "info": {"author": "xcode", "version": 1},
}


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    # 固定 LF，别让 Windows 写出 CRLF
    with path.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def build_icon() -> Image.Image:
    if not SRC.is_file():
        raise SystemExit(f"[icon] 源图不存在: {SRC}")
    with Image.open(SRC) as im:
        src = im.convert("RGBA")
    w, h = src.size
    scale = min(CONTENT / w, CONTENT / h)
    size = (max(1, round(w * scale)), max(1, round(h * scale)))
    art = src.resize(size, Image.LANCZOS)
    art = art.filter(ImageFilter.UnsharpMask(radius=1.5, percent=40, threshold=3))

    canvas = Image.new("RGB", (TILE, TILE), BG)  # RGB：App Store 不接受带 alpha 的图标
    canvas.paste(art, ((TILE - size[0]) // 2, (TILE - size[1]) // 2), art)
    return canvas


def verify(path: Path) -> dict:
    problems = []
    if not path.is_file():
        raise SystemExit(f"[icon] 图标不存在: {path}")
    with Image.open(path) as im:
        im.load()
        w, h = im.size
        mode = im.mode
        rgba = im.convert("RGBA")
        alpha_extrema = rgba.getchannel("A").getextrema()
        # 缩略图取色，判断是不是纯色/空白
        small = rgba.convert("RGB").resize((32, 32))
        colors = len(small.getcolors(maxcolors=4096) or [])

    if (w, h) != (TILE, TILE):
        problems.append(f"尺寸应为 {TILE}x{TILE}，实际 {w}x{h}")
    if alpha_extrema[1] == 0:
        problems.append("整张图全透明")
    if colors < 4:
        problems.append(f"颜色过少（{colors}），疑似空白图")
    if mode != "RGB":
        problems.append(f"图标模式应为 RGB（不能带 alpha），实际 {mode}")

    stats = {
        "size": f"{w}x{h}",
        "mode": mode,
        "alpha": f"{alpha_extrema[0]}..{alpha_extrema[1]}",
        "colors_32px": colors,
        "bytes": path.stat().st_size,
        "problems": problems,
    }
    if problems:
        for msg in problems:
            print(f"[icon] 校验失败: {msg}", file=sys.stderr)
        raise SystemExit(1)
    return stats


def main(argv: list[str]) -> int:
    check_only = "--check" in argv
    if not check_only:
        if not ICONSET.is_dir():
            ICONSET.mkdir(parents=True, exist_ok=True)
        icon = build_icon()
        icon.save(ICON_PATH, format="PNG", optimize=True)
        write_json(ASSETS / "Contents.json", ASSETS_CONTENTS)
        write_json(ICONSET / "Contents.json", APPICON_CONTENTS)
        print(f"[icon] 源图 {SRC.relative_to(REPO).as_posix()} "
              f"{Image.open(SRC).size[0]}x{Image.open(SRC).size[1]} -> "
              f"{ICON_PATH.relative_to(REPO).as_posix()} {TILE}x{TILE}")

    stats = verify(ICON_PATH)
    print(f"[icon] 尺寸 {stats['size']} / 模式 {stats['mode']} / "
          f"alpha {stats['alpha']} / 32px 采样色数 {stats['colors_32px']} / "
          f"{stats['bytes']} 字节")
    print("ICON OK" if check_only else "ICON GENERATED")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

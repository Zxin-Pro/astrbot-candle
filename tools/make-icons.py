#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""make-icons.py - build the Android launcher icons from one source image.

Everything is derived from the full 1254 px source with a single LANCZOS
resample per density (no progressive downscaling).  The font in the source
image is never re-rendered: the artwork is only scaled / padded / masked.

Outputs (relative to --out, normally ui/assets/icon/res):
    mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png        48/72/96/144/192  RGB
    mipmap-{...}/ic_launcher_round.png                             same, RGBA, content 84%
    drawable-{...}/ic_foreground.png                               108/162/216/324/432  RGBA
    drawable/ic_launcher_background.xml                            adaptive background colour

Self-check previews (relative to --preview-dir, normally <repo>/out):
    icon-preview.png        five sizes x (white bg / dark bg)
    icon-preview-mask.png   five sizes x (circle mask / rounded-square mask)

Usage:
    python tools/make-icons.py --src ui/assets/icon/source.png --out ui/assets/icon/res
    python tools/make-icons.py ... --crop 0,16,1254,1238 --text-gain 1.0
    python tools/make-icons.py ... --no-text-boost

Idempotent: identical arguments produce byte-identical PNG files
(the PNG encoder settings are pinned below).
"""

import argparse
import os
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageFont

# ---------------------------------------------------------------- constants
SOURCE_SIDE = 1254
DEFAULT_CROP = (0, 16, 1254, 1238)      # trims the near-black outer rim (16 px)
SQUARE_DENSITIES = (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                    ("xxhdpi", 144), ("xxxhdpi", 192))
FG_DENSITIES = (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
                ("xxhdpi", 324), ("xxxhdpi", 432))
ROUND_CONTENT = 0.84     # round icon: content is 84 % of the canvas, centred
# Adaptive foreground.  Android masks the 108dp layer down to a 72dp circle /
# squircle (288px of a 432px canvas), so the art must be small enough that no
# glyph of the neon text reaches that circle, while still covering it (so the
# background colour never shows at the icon edge).  0.72 keeps the leftmost
# 火 stroke inside the 72dp circle (radius 130 of 144); the +2.5 % right shift
# buys the last few pixels of margin.  See docs/icon-* for the measurements.
FG_CONTENT = 0.72        # adaptive foreground: content is 72 % of the canvas
FG_SHIFT = 0.025         # ... then shifted right by 2.5 % of the canvas
FG_BG_COLOR = "#FF1A0A12"   # adaptive background (deep violet, near black)

PNG_KW = dict(format="PNG", optimize=True, compress_level=9)


# ---------------------------------------------------------------- helpers
def med(vals):
    """Lower median - stable, no statistics import needed."""
    s = sorted(vals)
    return s[len(s) // 2]


def border_fill_color(im):
    """Median colour of the image border (used to pad a non-square crop)."""
    w, h = im.size
    px = im.load()
    step = max(1, min(w, h) // 180)
    samples = []
    for x in range(0, w, step):
        samples.append(px[x, 0])
        samples.append(px[x, h - 1])
    for y in range(0, h, step):
        samples.append(px[0, y])
        samples.append(px[w - 1, y])
    return (med([c[0] for c in samples]),
            med([c[1] for c in samples]),
            med([c[2] for c in samples]))


def square_canvas(im, color):
    """Centre a (possibly non-square) image on a canvas padded with `color`."""
    w, h = im.size
    side = max(w, h)
    canvas = Image.new("RGB", (side, side), color)
    canvas.paste(im, ((side - w) // 2, (side - h) // 2))
    return canvas


def pad_edge(im, side, x0, y0):
    """Place `im` at (x0, y0) on a `side` canvas, stretching each border band
    outwards so the surrounding area continues the edge colour (no hard seam)."""
    w, h = im.size
    canvas = Image.new("RGB", (side, side))
    # corners first: solid colour taken from each corner pixel
    tl = im.getpixel((0, 0))
    tr = im.getpixel((w - 1, 0))
    bl = im.getpixel((0, h - 1))
    br = im.getpixel((w - 1, h - 1))
    right_w = side - x0 - w
    bot_h = side - y0 - h
    if y0 > 0:
        if x0 > 0:
            canvas.paste(Image.new("RGB", (x0, y0), tl), (0, 0))
        if right_w > 0:
            canvas.paste(Image.new("RGB", (right_w, y0), tr), (x0 + w, 0))
        canvas.paste(im.crop((0, 0, w, 1)).resize((w, y0), Image.NEAREST), (x0, 0))
    if bot_h > 0:
        if x0 > 0:
            canvas.paste(Image.new("RGB", (x0, bot_h), bl), (0, y0 + h))
        if right_w > 0:
            canvas.paste(Image.new("RGB", (right_w, bot_h), br), (x0 + w, y0 + h))
        canvas.paste(im.crop((0, h - 1, w, h)).resize((w, bot_h), Image.NEAREST),
                     (x0, y0 + h))
    if x0 > 0:
        canvas.paste(im.crop((0, 0, 1, h)).resize((x0, h), Image.NEAREST), (0, y0))
    if right_w > 0:
        canvas.paste(im.crop((w - 1, 0, w, h)).resize((right_w, h), Image.NEAREST),
                     (x0 + w, y0))
    canvas.paste(im, (x0, y0))
    return canvas


def text_boost(im, gain):
    """Small-size legibility boost: sharpen, saturate, then screen a very
    narrow glow onto the neon tube cores (L > 190).  RGB in / RGB out."""
    im = im.filter(ImageFilter.UnsharpMask(radius=0.8, percent=110, threshold=3))
    im = ImageEnhance.Color(im).enhance(1.0 + 0.08 * gain)
    lum = im.convert("L")
    mask = lum.point(lambda v: 255 if v > 190 else 0)
    r, g, b = im.split()
    core = Image.merge("RGB", (ImageChops.multiply(r, mask),
                               ImageChops.multiply(g, mask),
                               ImageChops.multiply(b, mask)))
    glow = core.filter(ImageFilter.GaussianBlur(0.6))
    return ImageChops.screen(im, glow)


def circle_mask(n, super_sample=4):
    s = n * super_sample
    m = Image.new("L", (s, s), 0)
    ImageDraw.Draw(m).ellipse((0, 0, s - 1, s - 1), fill=255)
    return m.resize((n, n), Image.LANCZOS)


def round_rect_mask(n, radius_ratio=0.22, super_sample=4):
    s = n * super_sample
    m = Image.new("L", (s, s), 0)
    r = int(round(s * radius_ratio))
    ImageDraw.Draw(m).rounded_rectangle((0, 0, s - 1, s - 1), radius=r, fill=255)
    return m.resize((n, n), Image.LANCZOS)


def reduce_to_palette(im, colors):
    """Optional size knob (--png-colors).  Palette PNG (8 bit) is 4-5x smaller,
    but it cannot store soft alpha, so images whose alpha has intermediate
    values (the round icon's antialiased circle) are left in true colour."""
    alpha = im.getchannel("A") if im.mode == "RGBA" else None
    if alpha is not None:
        hist = alpha.histogram()
        if any(hist[1:255]):
            return im, False
        p = im.convert("RGB").quantize(colors=colors - 1, method=Image.MEDIANCUT,
                                       dither=Image.NONE)
        tidx = colors - 1
        pal = p.getpalette()[: 3 * tidx] + [0, 0, 0]
        p.putpalette(pal)
        p.putdata([tidx if a == 0 else v
                   for v, a in zip(p.getdata(), alpha.getdata())])
        p.info["transparency"] = tidx
        return p, True
    p = im.convert("RGB").quantize(colors=colors, method=Image.MEDIANCUT,
                                   dither=Image.NONE)
    return p, True


def save_png(im, path, colors=0):
    path.parent.mkdir(parents=True, exist_ok=True)
    if colors:
        im, _ok = reduce_to_palette(im, colors)
    im.save(str(path), **PNG_KW)


def load_font(size):
    try:
        return ImageFont.load_default(size=size)
    except TypeError:            # Pillow < 10.1: bitmap font, no size argument
        return ImageFont.load_default()


# ---------------------------------------------------------------- build steps
def build_square(base, size, boost, gain):
    im = base.resize((size, size), Image.LANCZOS)
    if boost and size <= 96:
        im = text_boost(im, gain)
    return im


def build_round(base, size, boost, gain):
    content = int(round(size * ROUND_CONTENT))
    inner = base.resize((content, content), Image.LANCZOS)
    pad = (size - content) // 2
    canvas = pad_edge(inner, size, pad, pad)
    if boost and size <= 96:
        canvas = text_boost(canvas, gain)
    out = canvas.convert("RGBA")
    out.putalpha(circle_mask(size))
    return out


def build_foreground(base, size):
    content = int(round(size * FG_CONTENT))
    inner = base.resize((content, content), Image.LANCZOS)
    pad = (size - content) // 2
    shift = int(round(size * FG_SHIFT))
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(inner, (pad + shift, pad))
    return canvas


def write_background_xml(path):
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:shape="rectangle">\n'
           '    <solid android:color="%s" />\n'
           '</shape>\n' % FG_BG_COLOR)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(str(path), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(xml)


# ---------------------------------------------------------------- previews
def preview_sheet(square_imgs):
    """Five sizes x two backgrounds (white / dark)."""
    cell_w, cell_h = 224, 244
    icon_area = 200
    pad = 24
    head = 34
    w = pad * 2 + cell_w * len(square_imgs)
    h = head + pad + cell_h * 2 + 24
    img = Image.new("RGB", (w, h), (240, 241, 245))
    d = ImageDraw.Draw(img)
    font = load_font(15)
    small = load_font(13)
    d.text((pad, 9), "ic_launcher (square) - white bg / dark bg, actual pixel size",
           fill=(30, 33, 40), font=font)
    rows = [((255, 255, 255), pad + head),
            ((20, 16, 26), pad + head + cell_h + 16)]
    for bg, top in rows:
        d.rectangle((pad, top, w - pad - 1, top + cell_h - 1), fill=bg)
        for i, (name, size, im) in enumerate(square_imgs):
            cx = pad + i * cell_w + (cell_w - size) // 2
            cy = top + (icon_area - size) // 2
            img.paste(im, (cx, cy))
            d.text((pad + i * cell_w + 16, top + icon_area + 6),
                   "%dpx %s" % (size, name),
                   fill=(110, 114, 130) if bg[0] > 128 else (188, 190, 205),
                   font=small)
    return img


def preview_masks(square_imgs):
    """Five sizes x (circle mask / rounded-square mask)."""
    cell_w, cell_h = 224, 244
    icon_area = 200
    pad = 24
    head = 34
    w = pad * 2 + cell_w * len(square_imgs)
    h = head + pad + cell_h * 2 + 14
    img = Image.new("RGB", (w, h), (17, 17, 22))
    d = ImageDraw.Draw(img)
    font = load_font(15)
    small = load_font(13)
    d.text((pad, 9), "launcher mask check - top: circle, bottom: rounded square",
           fill=(226, 228, 236), font=font)
    for row, masker in enumerate((circle_mask, round_rect_mask)):
        top = pad + head + row * cell_h
        for i, (name, size, im) in enumerate(square_imgs):
            masked = im.convert("RGBA")
            masked.putalpha(masker(size))
            cx = pad + i * cell_w + (cell_w - size) // 2
            img.paste(masked, (cx, top + (icon_area - size) // 2), masked)
            d.text((pad + i * cell_w + 16, top + icon_area + 6),
                   "%dpx %s" % (size, name), fill=(150, 152, 168), font=small)
    return img


# ---------------------------------------------------------------- main
def main(argv=None):
    ap = argparse.ArgumentParser(description="Generate Android launcher icons.")
    ap.add_argument("--src", required=True, help="source image (PNG)")
    ap.add_argument("--out", required=True, help="output res directory")
    ap.add_argument("--crop", default=None,
                    help="L,T,R,B in source pixels (default 0,16,1254,1238)")
    ap.add_argument("--text-gain", type=float, default=1.0,
                    help="strength of the <=96px legibility boost (default 1.0)")
    ap.add_argument("--no-text-boost", action="store_true",
                    help="disable the <=96px legibility boost")
    ap.add_argument("--preview-dir", default=None,
                    help="where to write the two preview sheets (default <repo>/out)")
    ap.add_argument("--no-preview", action="store_true", help="skip preview sheets")
    ap.add_argument("--png-colors", type=int, default=0,
                    help="palette-quantise PNG output to N colours (0 = true colour)")
    args = ap.parse_args(argv)

    src = Path(args.src)
    out = Path(args.out)
    if not src.is_file():
        print("ERROR: source not found: %s" % src)
        return 2

    with Image.open(str(src)) as raw:
        src_img = raw.convert("RGB")
    W, H = src_img.size

    if args.crop:
        parts = [int(v) for v in args.crop.replace(" ", "").split(",")]
        if len(parts) != 4:
            print("ERROR: --crop needs four integers L,T,R,B")
            return 2
        crop = tuple(parts)
    else:
        sx, sy = W / float(SOURCE_SIDE), H / float(SOURCE_SIDE)
        crop = (int(round(DEFAULT_CROP[0] * sx)), int(round(DEFAULT_CROP[1] * sy)),
                int(round(DEFAULT_CROP[2] * sx)), int(round(DEFAULT_CROP[3] * sy)))
    l, t, r, b = crop
    if not (0 <= l < r <= W and 0 <= t < b <= H):
        print("ERROR: crop %s outside %dx%d" % (str(crop), W, H))
        return 2

    cropped = src_img.crop(crop)
    fill = border_fill_color(cropped)
    base = square_canvas(cropped, fill)
    print("source %dx%d  crop %s -> %dx%d  fill #%02X%02X%02X"
          % (W, H, crop, base.size[0], base.size[1], fill[0], fill[1], fill[2]))
    print("text boost: %s (gain %.2f, <=96px only)"
          % ("off" if args.no_text_boost else "on", args.text_gain))

    total = 0
    square_imgs = []
    for name, size in SQUARE_DENSITIES:
        im = build_square(base, size, not args.no_text_boost, args.text_gain)
        p = out / ("mipmap-%s" % name) / "ic_launcher.png"
        save_png(im, p, args.png_colors)
        total += p.stat().st_size
        square_imgs.append((name, size, im))
        print("  %-46s %4dpx  %7d B" % (str(p), size, p.stat().st_size))

    for name, size in SQUARE_DENSITIES:
        im = build_round(base, size, not args.no_text_boost, args.text_gain)
        p = out / ("mipmap-%s" % name) / "ic_launcher_round.png"
        save_png(im, p, args.png_colors)
        total += p.stat().st_size
        print("  %-46s %4dpx  %7d B" % (str(p), size, p.stat().st_size))

    for name, size in FG_DENSITIES:
        im = build_foreground(base, size)
        p = out / ("drawable-%s" % name) / "ic_foreground.png"
        save_png(im, p, args.png_colors)
        total += p.stat().st_size
        print("  %-46s %4dpx  %7d B" % (str(p), size, p.stat().st_size))

    bg = out / "drawable" / "ic_launcher_background.xml"
    write_background_xml(bg)
    total += bg.stat().st_size
    print("  %-46s         %7d B" % (str(bg), bg.stat().st_size))
    print("total new resource bytes: %d" % total)

    if not args.no_preview:
        if args.preview_dir:
            pdir = Path(args.preview_dir)
        else:
            pdir = out.resolve()
            for _ in range(4):
                pdir = pdir.parent
            pdir = pdir / "out"
            if not pdir.parent.is_dir():
                pdir = Path(os.getcwd()) / "out"
        pdir.mkdir(parents=True, exist_ok=True)
        p1 = pdir / "icon-preview.png"
        p2 = pdir / "icon-preview-mask.png"
        preview_sheet(square_imgs).save(str(p1), **PNG_KW)
        preview_masks(square_imgs).save(str(p2), **PNG_KW)
        print("preview: %s (%d B)" % (p1, p1.stat().st_size))
        print("preview: %s (%d B)" % (p2, p2.stat().st_size))

    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())

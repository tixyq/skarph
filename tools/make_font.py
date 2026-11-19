#!/usr/bin/env python3
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
"""Makes a proportional Unicode bitmap font for skarph.core.BitmapFont from a TTF file.

  python3 tools/make_font.py /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf 16 fonts/dejavu16

writes fonts/dejavu16.png (white glyphs, alpha = coverage, power-of-two size) and fonts/dejavu16.fnt ("SKF1").
Default characters: ASCII, Latin-1, Latin Extended-A, Cyrillic (U+0400..U+04FF) and common punctuation; add more with
--ranges 0x370-0x3FF,0x2190-0x21FF. Needs Pillow. Check the license of the TTF you use (DejaVu: see fonts/LICENSE-DejaVu.txt).
"""
import argparse
import struct
import sys
from PIL import Image, ImageDraw, ImageFont

DEFAULT = [(0x20, 0x7E), (0xA0, 0xFF), (0x100, 0x17F), (0x400, 0x4FF),
           (0x2010, 0x2015), (0x2018, 0x201E), (0x2020, 0x2022), (0x2026, 0x2026), (0x2030, 0x2030),
           (0x2039, 0x203A), (0x20AC, 0x20AC), (0x2116, 0x2116), (0x2122, 0x2122), (0x2190, 0x2193)]


def parse_ranges(s):
    out = []
    for part in s.split(","):
        a, _, b = part.partition("-")
        out.append((int(a, 0), int(b or a, 0)))
    return out


def has_glyph(font, ch):
    # .notdef renders identically for every missing character: compare with a character that is surely missing
    try:
        m = font.getmask(ch)
        miss = font.getmask("\U0010FFFE")
    except Exception:
        return False
    return bytes(m) != bytes(miss) or ch == " "


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ttf")
    ap.add_argument("size", type=int)
    ap.add_argument("out")
    ap.add_argument("--ranges")
    ap.add_argument("--width", type=int, default=512)
    a = ap.parse_args()
    font = ImageFont.truetype(a.ttf, a.size)
    ascent, descent = font.getmetrics()
    ranges = parse_ranges(a.ranges) if a.ranges else DEFAULT
    glyphs = []
    for lo, hi in ranges:
        for code in range(lo, hi + 1):
            ch = chr(code)
            if not has_glyph(font, ch):
                continue
            bb = font.getbbox(ch, anchor="ls")
            w, h = max(0, bb[2] - bb[0]), max(0, bb[3] - bb[1])
            if ch == " ":
                w = h = 0
            img = None
            if w and h:
                img = Image.new("L", (w, h), 0)
                ImageDraw.Draw(img).text((-bb[0], -bb[1]), ch, font=font, anchor="ls", fill=255)
            glyphs.append((code, w, h, bb[0], ascent + bb[1], int(round(font.getlength(ch))), img))
    # shelf packing, 1 px gap
    W = a.width
    x = y = shelf = 0
    place = []
    for g in sorted(glyphs, key=lambda g: -g[2]):
        w, h = g[1], g[2]
        if x + w + 1 > W:
            x, y, shelf = 0, y + shelf + 1, 0
        place.append((g, x, y))
        x += w + 1
        shelf = max(shelf, h)
    H = 1
    while H < y + shelf + 1:
        H *= 2
    atlas = Image.new("RGBA", (W, H), (255, 255, 255, 0))
    entries = []
    for g, gx, gy in place:
        code, w, h, xo, yo, adv, img = g
        if img is not None:
            atlas.paste(Image.merge("RGBA", (Image.new("L", img.size, 255),) * 3 + (img,)), (gx, gy))
        if not (-128 <= xo <= 127 and -128 <= yo <= 127 and adv < 256 and w < 256 and h < 256):
            sys.exit("glyph U+%04X does not fit the format" % code)
        entries.append((code, gx, gy, w, h, xo, yo, adv))
    entries.sort()
    data = b"SKF1" + struct.pack(">HHH", ascent + descent, ascent, len(entries))
    for e in entries:
        data += struct.pack(">HHHBBbbB", *e)
    atlas.save(a.out + ".png")
    open(a.out + ".fnt", "wb").write(data)
    print("%d glyphs, %dx%d texture, line height %d" % (len(entries), W, H, ascent + descent))


main()

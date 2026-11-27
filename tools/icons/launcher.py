#!/usr/bin/env python3
"""The launcher icon (Nov 27, Tyler): his teardrop-eye painting, the ring only (everything outside the orange ring
cut away, tools/icons/launcher-ring.png), on one ink colour with a cream line just inside the edge of each format.

Android's adaptive icon is two 108 dp layers and the launcher picks the shape, so its line is a circle just inside
the middle 72 dp (exactly the edge on a Pixel; on a Samsung squircle it sits inside the corners). The store icons
draw the line to their own shape: Google Play's rounded square, Apple's squircle.

    python3 tools/icons/launcher.py [res_dir]     (default: the app's res folder; store icons go to tools/icons/store)
"""
import math, os, sys
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
INK = (24, 22, 26); LINE = (240, 222, 190)
RING = Image.open(os.path.join(HERE, 'launcher-ring.png')).convert('RGBA')


def ring(S, frac):
    k = S * frac / max(RING.size)
    return RING.resize((int(RING.width * k), int(RING.height * k)), Image.LANCZOS)


def centre(im, c): im.alpha_composite(c, ((im.width - c.width) // 2, (im.height - c.height) // 2))


def squircle(S, inset, exp=5.0):
    r = S / 2 - inset
    return [(S / 2 + r * math.copysign(abs(math.cos(t)) ** (2 / exp), math.cos(t)),
             S / 2 + r * math.copysign(abs(math.sin(t)) ** (2 / exp), math.sin(t))) for t in [2 * math.pi * i / 1440 for i in range(1440)]]


def square_icon(n, border, frac=.82, lw=.018, ins=.045):
    S = n * 2; im = Image.new('RGBA', (S, S), INK + (255,)); d = ImageDraw.Draw(im)
    i = S * ins; w = max(2, int(S * lw))
    if border == 'play': d.rounded_rectangle([i, i, S - i, S - i], int(S * .2 - i), outline=LINE, width=w)
    elif border == 'ios': p = squircle(S, i); d.line(p + [p[0]], fill=LINE, width=w, joint='curve')
    elif border == 'circle': d.ellipse([i, i, S - i, S - i], outline=LINE, width=w)
    centre(im, ring(S, frac))
    return im.resize((n, n), Image.LANCZOS)


def masked(im, shape):
    n = im.width; m = Image.new('L', (n * 4, n * 4), 0); d = ImageDraw.Draw(m)
    if shape == 'circle': d.ellipse([0, 0, n * 4 - 1, n * 4 - 1], fill=255)
    else: d.rounded_rectangle([0, 0, n * 4 - 1, n * 4 - 1], int(n * 4 * .2), fill=255)
    m = m.resize((n, n), Image.LANCZOS); o = Image.new('RGBA', (n, n), (0, 0, 0, 0)); o.paste(im, (0, 0), m); return o


def adaptive(px):
    S = px * 2; u = S / 108
    bg = Image.new('RGBA', (S, S), INK + (255,)); d = ImageDraw.Draw(bg); r = 33.2 * u
    d.ellipse([S / 2 - r, S / 2 - r, S / 2 + r, S / 2 + r], outline=LINE, width=max(2, int(1.35 * u)))
    fg = Image.new('RGBA', (S, S), (0, 0, 0, 0)); centre(fg, ring(S, 58 / 108))
    return bg.resize((px, px), Image.LANCZOS), fg.resize((px, px), Image.LANCZOS)


def main():
    res = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'res')
    for name, dens in [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]:
        f = os.path.join(res, 'mipmap-' + name); os.makedirs(f, exist_ok=True)
        bg, fg = adaptive(int(108 * dens))
        bg.convert('RGB').save(os.path.join(f, 'ic_launcher_background.png'))
        fg.save(os.path.join(f, 'ic_launcher_foreground.png'))
        n = int(48 * dens)
        masked(square_icon(n, 'play'), 'rounded').save(os.path.join(f, 'ic_launcher.png'))
        masked(square_icon(n, 'circle'), 'circle').save(os.path.join(f, 'ic_launcher_round.png'))
    store = os.path.join(HERE, 'store'); os.makedirs(store, exist_ok=True)
    square_icon(512, 'play').convert('RGB').save(os.path.join(store, 'icon-512.png'))
    square_icon(1024, 'ios').convert('RGB').save(os.path.join(store, 'icon-1024.png'))
    for old in ['adaptive-full-432.png']:
        p = os.path.join(store, old)
        if os.path.exists(p): os.remove(p)


if __name__ == '__main__':
    main()

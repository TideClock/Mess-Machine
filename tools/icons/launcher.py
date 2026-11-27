#!/usr/bin/env python3
"""The launcher icon (Nov 26, Tyler): the teardrop eye from his painting, drawn in the app's icon style.

Flat NSD colours, thick ink outlines, a darker lower rim on flat fills (lib.py). Android's adaptive icon is two
108 dp layers: the background (the orange ring with its dots) and the foreground (the blue disc, the yellow dotted
band and the teardrop with its eye). Every launcher shape shows at least the middle 72 dp circle, so the drop sits
inside it. Also writes the old-style square and round icons, a 512 store icon and a 1024 one for the App Store.

    python3 tools/icons/launcher.py [out_dir]     (default: the app's res folder)
"""
import math, os, sys
from PIL import Image, ImageDraw

K = (24, 22, 26); CR = (250, 240, 224); W = (255, 255, 255)
OR = (240, 120, 40); ORD = (196, 74, 30); YE = (255, 204, 51); YED = (205, 140, 20)
BL = (40, 128, 226); BLD = (22, 82, 170); BLL = (130, 196, 245)
ICE = (214, 236, 240); ICED = (150, 200, 210); PK = (255, 120, 150)
SS = 3                      # drawn this many times larger, then scaled down
L = 432 * SS                # one 108 dp layer at xxxhdpi
U = L / 108                 # one dp


def dp(*v): return [x * U for x in v]


def background():
    im = Image.new('RGBA', (L, L), OR + (255,)); d = ImageDraw.Draw(im)
    c = L / 2
    # a darker band hugging the lower half of the disc, like the painting's red-orange
    d.arc([c - 37 * U, c - 37 * U, c + 37 * U, c + 37 * U], 10, 170, fill=ORD, width=int(7 * U))
    # the painting's little ink dots, a ring of them round the disc
    for i in range(22):
        a = 2 * math.pi * i / 22 + .12
        r = 36.5 * U
        x, y = c + r * math.cos(a), c + r * math.sin(a)
        d.ellipse([x - 1.1 * U, y - 1.1 * U, x + 1.1 * U, y + 1.1 * U], fill=K)
    return im


def foreground():
    im = Image.new('RGBA', (L, L), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    c = L / 2; R = 31 * U; lw = int(2.6 * U)
    disc = [c - R, c - R, c + R, c + R]
    d.ellipse(disc, fill=BL)
    d.chord(disc, 38, 142, fill=BLD)
    # the yellow dotted band down the left, kept inside the disc
    band = Image.new('RGBA', (L, L), (0, 0, 0, 0)); bd = ImageDraw.Draw(band)
    bx = c - 17 * U
    bd.polygon([(bx - 4 * U, c - 40 * U), (bx + 3 * U, c - 40 * U), (bx + 1 * U, c + 40 * U), (bx - 6 * U, c + 40 * U)], fill=YE)
    for j in range(-6, 7):
        y = c + j * 5.2 * U; x = bx - 1.5 * U - j * .14 * U
        bd.ellipse([x - .9 * U, y - .9 * U, x + .9 * U, y + .9 * U], fill=K)
    m = Image.new('L', (L, L), 0); ImageDraw.Draw(m).ellipse(disc, fill=255)
    im.paste(band, (0, 0), Image.composite(band.split()[3], Image.new('L', (L, L), 0), m))
    d = ImageDraw.Draw(im)
    for a in range(0, 360, 30):  # dots in the blue
        r = 25 * U; x, y = c + r * math.cos(math.radians(a + 8)), c + r * math.sin(math.radians(a + 8))
        if x < c - 12 * U: continue
        d.ellipse([x - .8 * U, y - .8 * U, x + .8 * U, y + .8 * U], fill=K)
    d.ellipse(disc, outline=K, width=lw)
    # the teardrop: round body low and left, its point up and to the right
    bx_, by_, br = c + 1 * U, c + 5 * U, 15.5 * U
    tip = (c + 9 * U, c - 22 * U)
    ang = math.atan2(tip[1] - by_, tip[0] - bx_); dist = math.hypot(tip[0] - bx_, tip[1] - by_)
    al = math.acos(br / dist)   # the tangent points, seen from the body's centre
    pts = []
    for i in range(0, 121):
        t = ang + al + (2 * math.pi - 2 * al) * i / 120
        pts.append((bx_ + br * math.cos(t), by_ + br * math.sin(t)))
    drop = [tip] + pts
    d.polygon(drop, fill=ICE)
    lo = Image.new('RGBA', (L, L), (0, 0, 0, 0)); ld = ImageDraw.Draw(lo)
    ld.chord([bx_ - br, by_ - br, bx_ + br, by_ + br], 35, 145, fill=ICED)
    dm = Image.new('L', (L, L), 0); ImageDraw.Draw(dm).polygon(drop, fill=255)
    im.paste(lo, (0, 0), Image.composite(lo.split()[3], Image.new('L', (L, L), 0), dm))
    d = ImageDraw.Draw(im)
    # the pink tip with its dark hole
    ux, uy = (bx_ - tip[0]) / dist, (by_ - tip[1]) / dist
    tx, ty = tip[0] + ux * 6.5 * U, tip[1] + uy * 6.5 * U
    d.ellipse([tx - 3.2 * U, ty - 3.2 * U, tx + 3.2 * U, ty + 3.2 * U], fill=PK)
    d.ellipse([tx - 1.3 * U, ty - 1.3 * U, tx + 1.3 * U, ty + 1.3 * U], fill=K)
    d.line(drop + [drop[0]], fill=K, width=lw, joint='curve')
    # the eye
    ex, ey = bx_ + .5 * U, by_ + .5 * U
    for r, f in [(9.5, CR), (5.4, BL), (2.4, K)]:
        d.ellipse([ex - r * U, ey - r * U, ex + r * U, ey + r * U], fill=f, outline=K, width=int(1.6 * U) if r > 3 else 0)
    d.chord([ex - 5.4 * U, ey - 5.4 * U, ex + 5.4 * U, ey + 5.4 * U], 30, 150, fill=BLD)
    d.ellipse([ex - 2.4 * U, ey - 2.4 * U, ex + 2.4 * U, ey + 2.4 * U], fill=K)
    d.ellipse([ex - 4.2 * U, ey - 4.4 * U, ex - 2.2 * U, ey - 2.4 * U], fill=W)
    # the painting's scratchy lashes round the body
    for i in range(7):
        a = math.radians(125 + i * 15)
        r1, r2 = br + 2.2 * U, br + 4.6 * U
        d.line([(bx_ + r1 * math.cos(a), by_ + r1 * math.sin(a)), (bx_ + r2 * math.cos(a), by_ + r2 * math.sin(a))], fill=K, width=int(1.3 * U))
    return im


def small(im, n): return im.resize((n, n), Image.LANCZOS)


def mask(im, shape):
    n = im.width; m = Image.new('L', (n * 2, n * 2), 0); d = ImageDraw.Draw(m)
    if shape == 'circle': d.ellipse([0, 0, n * 2 - 1, n * 2 - 1], fill=255)
    else: d.rounded_rectangle([0, 0, n * 2 - 1, n * 2 - 1], int(n * 2 * .2), fill=255)
    m = m.resize((n, n), Image.LANCZOS); o = Image.new('RGBA', (n, n), (0, 0, 0, 0)); o.paste(im, (0, 0), m); return o


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main', 'res')
    bg, fg = background(), foreground()
    full = bg.copy(); full.alpha_composite(fg)
    # the visible part of an adaptive icon is its middle 72 of 108 dp: that is the legacy and store icon
    cut = full.crop((int(18 * U), int(18 * U), int(90 * U), int(90 * U)))
    for name, dens in [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]:
        f = os.path.join(out, 'mipmap-' + name); os.makedirs(f, exist_ok=True)
        small(fg, int(108 * dens)).save(os.path.join(f, 'ic_launcher_foreground.png'))
        small(bg, int(108 * dens)).convert('RGB').save(os.path.join(f, 'ic_launcher_background.png'))
        n = int(48 * dens)
        mask(small(cut, n), 'rounded').save(os.path.join(f, 'ic_launcher.png'))
        mask(small(cut, n), 'circle').save(os.path.join(f, 'ic_launcher_round.png'))
    store = os.path.join(out, '..', '..', '..', '..', 'tools', 'icons', 'store')
    os.makedirs(store, exist_ok=True)
    small(cut, 512).convert('RGB').save(os.path.join(store, 'icon-512.png'))
    small(cut, 1024).convert('RGB').save(os.path.join(store, 'icon-1024.png'))
    small(full, 432).save(os.path.join(store, 'adaptive-full-432.png'))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""
Loops and hits for the Mess Machine from the Qbldy songs (SE-9, SE-10 Loops shelf). Plan v2, piece 10.

For each song: find the bar length by self-similarity (the bar length B that makes the onset envelope of each
bar most like the next one's), find where bars start (the strongest low-band attacks on that grid), score every
bar (loud, repeats into the next bar, not a fade), keep the best two from different parts of the song, cut them
to the sample with a 5 ms crossfade so they loop without a click, and cut hits at real attacks (each hit runs
to the next attack, at most 0.6 s), so the chops follow the music instead of a fixed 0.35 s grid.

usage: make_loops.py <songs dir> [<songs dir>...] --out <assets/sounds> [--only slug,slug] [--report]
"""
import json, os, subprocess, sys
import numpy as np

SR = 44100
HOP = 441              # 10 ms frames
NFFT = 2048


def decode(path):
    raw = subprocess.run(['ffmpeg', '-v', 'quiet', '-i', path, '-ac', '1', '-ar', str(SR), '-f', 'f32le', '-'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def envelopes(x):
    """Onset strength (spectral flux, log), a low-band version (kicks), and RMS per 10 ms frame."""
    n = 1 + (len(x) - NFFT) // HOP
    win = np.hanning(NFFT)
    idx = np.arange(NFFT)[None, :] + HOP * np.arange(n)[:, None]
    frames = x[idx] * win
    mag = np.abs(np.fft.rfft(frames, axis=1))
    lm = np.log1p(100 * mag)
    d = np.maximum(0, np.diff(lm, axis=0, prepend=lm[:1]))
    lowbin = int(150 / (SR / NFFT))
    flux = d.sum(axis=1)
    low = d[:, 1:lowbin].sum(axis=1)
    rms = np.sqrt((frames ** 2).mean(axis=1))
    cent = (mag * np.arange(mag.shape[1])).sum(axis=1) / (mag.sum(axis=1) + 1e-9) * SR / NFFT
    return flux, low, rms, cent


def bar_length(flux, rms):
    """Bar length in frames (fractional) by self-similarity over the loud part of the song."""
    on = rms > rms.max() * .1
    f = (flux - flux.mean()) * on
    best, bestB = -1, None
    # 4/4 bars between 60 and 180 bpm: 1.33 s to 4 s
    for B in range(133, 401):
        a, b = f[:-B], f[B:]
        c = (a * b).sum() / (np.sqrt((a * a).sum() * (b * b).sum()) + 1e-9)
        # a bar should also repeat at two bars (it is a loop, not a beat)
        if 2 * B < len(f) - 10:
            a2, b2 = f[:-2 * B], f[2 * B:]
            c += .5 * (a2 * b2).sum() / (np.sqrt((a2 * a2).sum() * (b2 * b2).sum()) + 1e-9)
        if c > best:
            best, bestB = c, B
    # finer: parabola through the neighbours
    def score(B):
        a, b = f[:-B], f[B:]
        return (a * b).sum() / (np.sqrt((a * a).sum() * (b * b).sum()) + 1e-9)
    s0, s1, s2 = score(bestB - 1), score(bestB), score(bestB + 1)
    den = s0 - 2 * s1 + s2
    off = 0 if abs(den) < 1e-12 else .5 * (s0 - s2) / den
    return bestB + max(-.5, min(.5, off)), best


def fit_grid(flux, B):
    """
    Sharpen a rough bar length to a fraction of a millisecond: every strong attack in the song sits near a
    sixteenth-note line, so fit one straight line through all of them (time = phase + k * sixteenth).
    Returns (bar in frames, phase in frames, share of attacks within an eighth of a sixteenth).
    """
    thr = np.percentile(flux, 90)
    on = []
    for i in range(1, len(flux) - 1):
        if flux[i] > thr and flux[i] >= flux[i - 1] and flux[i] > flux[i + 1]:
            a, b, c = flux[i - 1], flux[i], flux[i + 1]
            den = a - 2 * b + c
            on.append(i + (0 if den == 0 else .5 * (a - c) / den))
    on = np.array(on)
    u = B / 16
    ph = on[0] % u if len(on) else 0
    keep = np.zeros(len(on), bool)
    for _ in range(8):
        k = np.round((on - ph) / u)
        r = on - (ph + k * u)
        keep = np.abs(r) < u * .2
        if keep.sum() < 20:
            break
        A = np.vstack([np.ones(keep.sum()), k[keep]]).T
        ph, u = np.linalg.lstsq(A, on[keep], rcond=None)[0]
    k = np.round((on - ph) / u)
    tight = float((np.abs(on - (ph + k * u)) < u / 8).mean()) if len(on) else 0
    return u * 16, ph, tight


def beat_check(flux, B):
    """How strongly the onsets fall on quarters of the bar, versus between them (1 = all on, 0 = none)."""
    q = B / 4
    n = len(flux)
    ph_scores = []
    for ph in np.arange(0, q, 1):
        on = [flux[int(round(ph + k * q))] for k in range(int((n - ph) / q)) if int(round(ph + k * q)) < n]
        off = [flux[int(round(ph + k * q + q / 2))] for k in range(int((n - ph) / q)) if int(round(ph + k * q + q / 2)) < n]
        ph_scores.append((np.mean(on), np.mean(off), ph))
    m = max(ph_scores)
    return m[0] / (m[0] + m[1] + 1e-9), m[2]


def bar_starts(low, flux, B, beat_ph):
    """Bar grid: of the four beat phases, the one whose beats carry the most low-band attack (the downbeat)."""
    q = B / 4
    n = len(flux)
    best, bp = -1, beat_ph
    for k in range(4):
        ph = beat_ph + k * q
        s = np.mean([low[int(round(ph + j * B))] for j in range(int((n - ph) / B)) if int(round(ph + j * B)) < n])
        if s > best:
            best, bp = s, ph
    starts = []
    t = bp % B
    while t + 2 * B < n:
        starts.append(t)
        t += B
    return starts


def refine_start(x, frame):
    """Sample position of the attack nearest a frame start: the steepest rise in the next 15 ms, minus 5 ms."""
    s0 = int(frame * HOP)
    seg = np.abs(x[max(0, s0 - 441): s0 + 662])
    if len(seg) < 100:
        return s0
    e = np.convolve(seg, np.ones(64) / 64, mode='same')
    rise = np.diff(e)
    i = int(np.argmax(rise))
    return max(0, s0 - 441 + i - int(.005 * SR))


def cut_loop(x, start, length):
    """Exactly length samples from start; the last 5 ms crossfade into what came just before the start."""
    L = int(round(length))
    xf = int(.005 * SR)
    seg = x[start:start + L].copy()
    if start >= xf and len(seg) == L:
        pre = x[start - xf:start]
        w = np.linspace(0, 1, xf)
        seg[-xf:] = seg[-xf:] * (1 - w) + pre * w
    return seg


def write_flac(path, y):
    pk = np.abs(y).max()
    if pk > 0:
        y = y / pk * 10 ** (-1 / 20)
    pcm = (np.clip(y, -1, 1) * 32767).astype('<i2').tobytes()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    subprocess.run(['ffmpeg', '-v', 'quiet', '-y', '-f', 's16le', '-ar', str(SR), '-ac', '1', '-i', '-', '-compression_level', '8', path], input=pcm, check=True)


def hit_type(seg):
    mag = np.abs(np.fft.rfft(seg[:4096] * np.hanning(min(4096, len(seg)))[:len(seg[:4096])], 8192))
    fr = np.fft.rfftfreq(8192, 1 / SR)
    tot = mag.sum() + 1e-9
    low = mag[fr < 150].sum() / tot
    high = mag[fr > 5000].sum() / tot
    cent = (mag * fr).sum() / tot
    if low > .35:
        return 'kick'
    if high > .35 or cent > 6000:
        return 'hat'
    if cent > 1800:
        return 'snare'
    return 'perc'


def song(path, slug, title, album, outdir, report):
    x = decode(path)
    flux, low, rms, cent = envelopes(x)
    B, sim = bar_length(flux, rms)
    B, _, tight = fit_grid(flux, B)
    bpm = 4 * 60 / (B * HOP / SR)
    # fold to a playable range: 70 to 160 bpm (4 beats a bar)
    while bpm > 160.5: bpm /= 2; B *= 2
    while bpm < 70: bpm *= 2; B /= 2
    # these songs were made at whole-number tempos (44 of 45 measure within 0.04 of one): snap when close
    if abs(bpm - round(bpm)) < .35:
        bpm = float(round(bpm)); B = 4 * 60 / bpm * SR / HOP
    beatness, ph = beat_check(flux, B)
    starts = bar_starts(low, flux, B, ph)
    # score bars: loud (not a fade), repeats into the next bar, busy enough
    peak = rms.max()
    cand = []
    for i, t in enumerate(starts):
        a, b = int(t), int(t + B)
        if b + int(B) >= len(rms):
            break
        loud = rms[a:b].mean() / peak
        if loud < .25 or rms[a:b].min() / peak < .02:
            continue
        e1, e2 = flux[a:b], flux[b:b + (b - a)]
        m = min(len(e1), len(e2))
        rep = float(np.corrcoef(e1[:m], e2[:m])[0, 1]) if m > 10 else 0
        cand.append((rep * .6 + loud * .4, i, t, float(cent[a:b].mean()), loud, rep))
    cand.sort(reverse=True)
    picked = []
    for c in cand:
        # from different parts of the song (15 s apart) and not the same colour
        if all(abs(c[2] - p[2]) * HOP / SR > 15 for p in picked) and all(abs(c[3] - p[3]) > 150 or abs(c[2] - p[2]) * HOP / SR > 40 for p in picked):
            picked.append(c)
        if len(picked) == 2:
            break
    out = {'slug': slug, 'title': title, 'album': album, 'bpm': round(bpm, 3), 'onGrid': round(tight, 3), 'barSec': round(B * HOP / SR, 4), 'selfSim': round(sim, 3), 'onBeat': round(beatness, 3), 'loops': [], 'hits': []}
    Lsamp = B * HOP
    for n, c in enumerate(picked):
        s = refine_start(x, c[2])
        y = cut_loop(x, s, Lsamp)
        f = 'loops/%s-%d.flac' % (slug, n + 1)
        if outdir:
            write_flac(os.path.join(outdir, f), y)
        out['loops'].append({'file': f, 'at': round(s / SR, 3), 'samples': len(y), 'score': round(c[0], 3), 'repeat': round(c[5], 3), 'loud': round(c[4], 3)})
    # hits: the strongest attacks anywhere loud, each to the next attack (at most 0.6 s), four of different colours
    thr = np.percentile(flux, 97)
    peaks = [i for i in range(2, len(flux) - 2) if flux[i] > thr and flux[i] == flux[i - 2:i + 3].max() and rms[i:i + 5].max() > peak * .2]
    peaks.sort(key=lambda i: -flux[i])
    hits = []
    for i in peaks:
        if any(abs(i - h['frame']) < 50 for h in hits):
            continue
        nxt = [j for j in peaks if j > i + 4]
        end = min([j for j in nxt] + [i + 60]) if nxt else i + 60
        s = refine_start(x, i)
        e = min(len(x), int(end * HOP))
        if e - s < int(.08 * SR):
            continue
        seg = x[s:e].copy()
        fo = min(len(seg) // 4, int(.015 * SR))
        seg[-fo:] *= np.linspace(1, 0, fo)
        c0 = float(cent[i:i + 5].mean())
        if any(abs(c0 - h['cent']) < 250 for h in hits):
            continue
        hits.append({'frame': i, 'cent': c0, 'seg': seg})
        if len(hits) == 4:
            break
    for n, h in enumerate(sorted(hits, key=lambda h: h['frame'])):
        f = 'hits/%s-%d.flac' % (slug, n + 1)
        if outdir:
            write_flac(os.path.join(outdir, f), h['seg'])
        out['hits'].append({'file': f, 'at': round(h['frame'] * HOP / SR, 3), 'seconds': round(len(h['seg']) / SR, 3), 'type': hit_type(h['seg'])})
    if report:
        print('%-36s %7.3f bpm  on-grid %.2f  self-sim %.2f  on-beat %.2f  loops %s  hits %d' % (slug, bpm, tight, sim, beatness, [l['at'] for l in out['loops']], len(out['hits'])))
    return out


if __name__ == '__main__':
    args = sys.argv[1:]
    out = args[args.index('--out') + 1] if '--out' in args else None
    only = set(args[args.index('--only') + 1].split(',')) if '--only' in args else None
    dirs = [a for a in args if os.path.isdir(a) and a != out]
    res = []
    for d in dirs:
        man = json.load(open(os.path.join(os.path.dirname(d.rstrip('/')), 'manifest.json')))
        for it in man['items']:
            if it.get('type') != 'song':
                continue
            web = it['web']
            slug = os.path.splitext(os.path.basename(web))[0]
            if only and slug not in only:
                continue
            title = os.path.splitext(os.path.basename(it['source']))[0]
            album = it['source'].split('/')[0]
            r = song(os.path.join(os.path.dirname(d.rstrip('/')), web), slug, title, album, out, '--report' in args)
            r['oldTempo'] = it.get('tempo')
            r['key'] = it.get('key', '')
            res.append(r)
    if out:
        json.dump(res, open(os.path.join(out, 'loops-made.json'), 'w'), indent=1)

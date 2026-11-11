#!/usr/bin/env python3
"""check.py <render dir>: contact sheet at the marks + audio facts (level per half second, silences, clipping)."""
import sys, subprocess, csv, numpy as np, os
d = sys.argv[1]; name = os.path.basename(d.rstrip('/'))
rows = list(csv.DictReader(open(f'{d}/touches.csv')))
marks = [(int(r['frame']), r['note']) for r in rows if r['type'] == 'mark']
n = int(open(f'{d}/info.txt').read().split('frames=')[1].split()[0])
raw = subprocess.run(['ffmpeg','-v','error','-i',f'{d}/audio.flac','-f','s16le','-ac','1','-'],capture_output=True).stdout
a = np.frombuffer(raw, dtype=np.int16).astype(float) / 32768
st = subprocess.run(['ffmpeg','-v','error','-i',f'{d}/audio.flac','-f','s16le','-'],capture_output=True).stdout
s2 = np.frombuffer(st, dtype=np.int16).astype(float) / 32768
rate = 48000
print(f'== {name}: {n} frames {n/30:.1f}s  peak {abs(s2).max():.3f}  clipped {(abs(s2)>0.999).sum()}')
print('marks:', ' '.join(f'{m}@{f/30:.1f}' for f, m in marks))
w = rate // 4
r = np.sqrt((a[:len(a)//w*w].reshape(-1, w) ** 2).mean(1))
db = np.where(r > 1e-5, 20 * np.log10(np.maximum(r, 1e-5)), -99).astype(int)
print('level dB per 1/4 s:', ' '.join(('..' if x < -60 else str(-x)) for x in db))
want = sorted(set([f + 12 for f, _ in marks] + list(np.linspace(5, n - 5, 6).astype(int))))[:14]
want = [min(n - 1, x) for x in want]
sel = '+'.join(f'eq(n\\,{x})' for x in want)
subprocess.run(['ffmpeg','-v','error','-y','-i',f'{d}/frames.mp4','-vf',f"select='{sel}',scale=274:-1,tile={len(want)}x1",'-frames:v','1','-vsync','0',f'{d}/sheet.png'],check=True)
print('sheet frames:', want)

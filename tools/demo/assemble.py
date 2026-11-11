#!/usr/bin/env python3
"""Turn one demo render (frames.mp4, audio.flac, touches.csv) into captioned 16:9 and 9:16 clips.
usage: assemble.py <render dir> <shot id> <library dir> <fonts dir>     (captions come from shots.py)"""
import sys, csv, subprocess, os, importlib.util
def load_shots():
    spec = importlib.util.spec_from_file_location('shots', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'shots.py'))
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); return m.SHOTS

def make(src, shot, cfg, fonts, outpath, only=None):
    SW, SH, FPS = 822, 1782, 30
    dur = float(subprocess.run(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',f'{src}/frames.mp4'],capture_output=True,text=True).stdout)
    rows = list(csv.DictReader(open(f'{src}/touches.csv')))
    marks = {r['note']: int(r['frame']) / FPS for r in rows if r['type'] == 'mark'}
    def when(v):
        if isinstance(v, (int, float)): return float(v)
        if '+' in v: a, b = v.split('+'); return marks[a] + float(b)
        if '-' in v and v.split('-')[-1].replace('.', '').isdigit() and v.rsplit('-', 1)[0] in marks: a, b = v.rsplit('-', 1); return marks[a] - float(b)
        return marks[v]
    t0 = when(cfg.get('start', 0)); t1 = min(dur, when(cfg['end'])) if 'end' in cfg else dur
    if 'length' in cfg: t1 = min(dur, t0 + cfg['length'])
    fo = cfg.get('fadeout', .15)
    caps = []
    cl = cfg['captions']
    for i, (a, text) in enumerate(cl):
        b = when(cl[i + 1][0]) if i + 1 < len(cl) else t1
        if text: caps.append((max(when(a), t0), b - .08, text))
    # finger tracks: note -> list of (time, x, y); closed by 'up'
    tracks, live = [], {}
    for r in rows:
        f = int(r['frame']); k = r['note']
        if r['type'] == 'down': live[k] = [(f, int(r['x']), int(r['y']))]
        elif r['type'] == 'move' and k in live: live[k].append((f, int(r['x']), int(r['y'])))
        elif r['type'] == 'up' and k in live:
            tr = live.pop(k); tr.append((max(f, tr[-1][0] + 1), tr[-1][1], tr[-1][2])); tracks.append(tr)
    def t(s):
        s = max(0, s); h = int(s // 3600); m = int(s % 3600 // 60); return f"{h}:{m:02d}:{s % 60:05.2f}"
    def build(name, W, H, ph, px, py, cap_cx, cap_y, cap_px, title_x, title_y, title_px, crop=None):
        cx0, cy0, cw_, ch_ = crop or (0, 0, SW, SH)
        sc = ph / ch_
        pw = int(cw_ * sc) // 2 * 2
        size = int(cap_px / .36); pitch = int(cap_px * 1.42)
        ass = [f"[Script Info]\nScriptType: v4.00+\nPlayResX: {W}\nPlayResY: {H}\nWrapStyle: 2\n",
               "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
               f"Style: Cap,Bungee,{size},&H00E6F6FF,&H00E6F6FF,&H00141214,&H009B3DFF,0,0,0,0,100,100,1,0,1,0,{max(3, cap_px // 14)},5,0,0,0,1",
               f"Style: Title,Archivo SemiBold,{title_px},&H0033C8FF,&H0033C8FF,&H00141214,&H00000000,0,0,0,0,100,100,3,0,1,0,0,5,0,0,0,1",
               "Style: Dot,Arial,20,&H5033C8FF,&H5033C8FF,&H20FFFFFF,&H00000000,0,0,0,0,100,100,0,0,1,3,0,7,0,0,0,1\n",
               "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"]
        ass.append(f"Dialogue: 0,{t(0)},{t(dur)},Title,,0,0,0,,{{\\pos({title_x},{title_y})}}" + ('MESS MACHINE  /  ' + cfg['title'].upper() if cfg.get('title') else 'MESS MACHINE'))
        for a, b, text in caps:
            lines = text.upper().split('~')
            y0 = cap_y - pitch * (len(lines) - 1) / 2
            for i, ln in enumerate(lines):
                ass.append(f"Dialogue: 1,{t(a)},{t(b)},Cap,,0,0,0,,{{\\pos({cap_cx},{int(y0 + i * pitch)})\\fad(100,100)}}{ln}")
        r = int(38 * sc)
        circle = f"m 0 {-r} b {r*.55:.0f} {-r} {r} {-r*.55:.0f} {r} 0 b {r} {r*.55:.0f} {r*.55:.0f} {r} 0 {r} b {-r*.55:.0f} {r} {-r} {r*.55:.0f} {-r} 0 b {-r} {-r*.55:.0f} {-r*.55:.0f} {-r} 0 {-r}"
        def P(x, y): return px + (x - cx0) * sc, py + (y - cy0) * sc
        for tr in tracks:
            for i in range(len(tr) - 1):
                (f0, x0, y0), (f1, x1, y1) = tr[i], tr[i + 1]
                a, b = f0 / FPS, f1 / FPS
                last = i == len(tr) - 2
                if last: b = max(b, a + .1) + .1
                X0, Y0 = P(x0, y0); X1, Y1 = P(x1, y1)
                if not (py - r <= Y0 <= py + ph + r): continue
                mv = f"\\pos({X0:.0f},{Y0:.0f})" if (x0, y0) == (x1, y1) else f"\\move({X0:.0f},{Y0:.0f},{X1:.0f},{Y1:.0f})"
                fade = "\\fad(0,100)" if last else ""
                ass.append("Dialogue: 5,%s,%s,Dot,,0,0,0,,{%s%s\\p1}%s{\\p0}" % (t(a), t(b), mv, fade, circle))
        path = f'/tmp/{shot}_{name}.ass'
        open(path, 'w').write('\n'.join(ass) + '\n')
        out = outpath(name)
        cropf = f"crop={cw_}:{ch_}:{cx0}:{cy0}," if crop else ""
        vf = (f"[0:v]{cropf}scale={pw}:{ph}:flags=lanczos[p];color=c=0x141214:s={W}x{H}:r={FPS}:d={dur}[bg];"
              f"[bg]drawbox=x={px-6}:y={py-6}:w={pw+12}:h={ph+12}:color=0xFF3D9B:t=fill[b2];"
              f"[b2][p]overlay={px}:{py}[v0];[v0]ass={path}:fontsdir={fonts},trim={t0}:{t1},setpts=PTS-STARTPTS[v];"
              f"[1:a]atrim={t0}:{t1},asetpts=PTS-STARTPTS,alimiter=limit=0.89,afade=t=in:d=0.03,afade=t=out:st={t1-t0-fo}:d={fo}[a]")
        subprocess.run(['ffmpeg','-v','error','-y','-i',f'{src}/frames.mp4','-i',f'{src}/audio.flac','-filter_complex',vf,
                        '-map','[v]','-map','[a]','-c:v','libx264','-crf','19','-preset','medium','-pix_fmt','yuv420p',
                        '-c:a','aac','-b:a','192k','-movflags','+faststart',out],check=True)
        print(out, os.path.getsize(out)//1024, 'KB', f'{t1-t0:.1f}s')
    crop = cfg.get('crop')          # (x, y, w, h) of the phone screen to show, in screen pixels
    cw_, ch_ = (crop[2], crop[3]) if crop else (SW, SH)
    # 9:16: phone centred under a caption band
    ph = min(1560, int(1000 * ch_ / cw_)); pw = int(cw_ * ph / ch_)
    if only in (None, '9x16'): build('9x16', 1080, 1920, ph, (1080 - pw) // 2, 330 + (1560 - ph) // 2, 540, 205, 62, 540, 70, 34, crop)
    # 16:9: phone on the right, caption on the left
    ph = 1020; pw = int(cw_ * ph / ch_)
    if pw > 900: pw = 900; ph = int(pw * ch_ / cw_)
    if only in (None, '16x9'): build('16x9', 1920, 1080, ph, 1920 - pw - 200 - (900 - pw) // 4, (1080 - ph) // 2, 560, 560, 74, 560, 130, 34, crop)


if __name__ == '__main__':
    src, shot, lib, fonts = sys.argv[1:5]
    make(src, shot, load_shots()[shot], fonts, lambda name: f'{lib}/clips/{name}/{shot}.mp4')

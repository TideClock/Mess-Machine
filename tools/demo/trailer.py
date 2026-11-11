#!/usr/bin/env python3
"""trailer.py <library dir> <fonts dir>: fast cuts from the best moments of the raw renders, in both shapes."""
import sys, os, subprocess, tempfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import assemble
lib, fonts = sys.argv[1:3]
SHOTS = assemble.load_shots()
# (shot, start, length in seconds, caption). Start is a mark name, "mark+0.5" or seconds.
CUTS = [
    ('b7', 'chord-sweep+0.4', 3.4, 'Play chords,~sweep the sound'),
    ('c2', 'clap-in+0.2', 3.8, 'Drums made~from scratch'),
    ('b6', 'slides+0.3', 3.8, 'Acid lines~that slide'),
    ('b5', 'analog+0.6', 2.3, 'Six kinds~of synth'),
    ('b5', 'grain+0.5', 2.3, 'Six kinds~of synth'),
    ('b5', 'pluck+0.3', 2.0, 'Six kinds~of synth'),
    ('b2', 'held+0.2', 3.2, 'Hold any sound~like a pad'),
    ('b9', 'inkey', 3.0, 'Every square~in key'),
    ('b12', 'slices-1.0', 4.0, 'Chop a loop~onto pads'),
    ('b13', 'stutter-0.5', 2.0, 'Effects~you hold'),
    ('b13', 'tape-0.3', 2.6, 'Effects~you hold'),
    ('b10', 'position+0.5', 3.5, 'Grains of your~own sounds'),
    ('b8', 'faster+0.2', 3.9, 'Loops follow~the tempo'),
    ('b14', 'play+1.6', 4.5, 'Paint loops~into a song'),
    ('c5', 'grains+1.0', 3.5, 'Turn a beat~into a cloud'),
    ('c3', 'xy+0.9', 6.0, 'All of it,~at once'),
    ('c1', 'cloud+1.0', 5.6, 'Sample, synth~and grains'),
]
tmp = tempfile.mkdtemp()
for name, W, H in (('9x16', 1080, 1920), ('16x9', 1920, 1080)):
    parts = []
    for i, (shot, start, length, cap) in enumerate(CUTS):
        base = SHOTS[shot]
        last = i == len(CUTS) - 1
        cfg = dict(start=start, captions=[(start, cap)], fadeout=.6 if last else .03)
        if 'crop' in base: cfg['crop'] = base['crop']
        # end = start + length, written as seconds after working the start out inside make()
        cfg['length'] = length
        out = {}
        assemble.make(f'{lib}/raw/{shot}', f'tr{i}', cfg, fonts, lambda n, i=i: f'{tmp}/{n}_{i:02d}.mp4', only=name)
        parts.append(f'{tmp}/{name}_{i:02d}.mp4')
    # end card
    ass = f'{tmp}/end_{name}.ass'
    open(ass, 'w').write(f"""[Script Info]
ScriptType: v4.00+
PlayResX: {W}
PlayResY: {H}

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Big,Bungee,{int(min(W, H) * .30)},&H0033C8FF,&H0033C8FF,&H00141214,&H009B3DFF,0,0,0,0,100,100,2,0,1,0,8,5,0,0,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:00:02.40,Big,,0,0,0,,{{\\pos({W//2},{H//2 - int(min(W,H)*.07)})\\fad(150,300)}}MESS
Dialogue: 0,0:00:00.00,0:00:02.40,Big,,0,0,0,,{{\\pos({W//2},{H//2 + int(min(W,H)*.07)})\\fad(150,300)}}MACHINE
""")
    end = f'{tmp}/{name}_end.mp4'
    subprocess.run(['ffmpeg','-v','error','-y','-f','lavfi','-i',f'color=c=0x141214:s={W}x{H}:r=30:d=2.4','-f','lavfi','-i','anullsrc=r=48000:cl=stereo',
                    '-vf',f'ass={ass}:fontsdir={fonts}','-t','2.4','-c:v','libx264','-crf','19','-pix_fmt','yuv420p','-c:a','aac','-b:a','192k',end],check=True)
    parts.append(end)
    ins = []
    for p in parts: ins += ['-i', p]
    n = len(parts)
    fc = ''.join(f'[{k}:v][{k}:a]' for k in range(n)) + f'concat=n={n}:v=1:a=1[v][a]'
    out = f'{lib}/trailer.mp4' if name == '16x9' else f'{lib}/trailer_9x16.mp4'
    subprocess.run(['ffmpeg','-v','error','-y'] + ins + ['-filter_complex', fc, '-map','[v]','-map','[a]','-c:v','libx264','-crf','19','-preset','medium','-pix_fmt','yuv420p','-c:a','aac','-b:a','192k','-movflags','+faststart',out],check=True)
    d = subprocess.run(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',out],capture_output=True,text=True).stdout.strip()
    print(out, os.path.getsize(out)//1024, 'KB', d, 's')

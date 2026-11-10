"""Sort every bundled sound by instrument type and a few qualities, from its name where the name
says it and from the audio where it does not (the Heap's song chops). Writes "ty" and "q" into
app/src/main/assets/sounds/library.json. Run from the repo root: python3 tools/classify_sounds.py"""
import json, wave, re, collections
import numpy as np

ROOT = 'app/src/main/assets/sounds/'
lib = json.load(open(ROOT + 'library.json'))
cache = {}

def load(f):
    if f not in cache:
        w = wave.open(ROOT + f)
        x = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32) / 32768
        if w.getnchannels() > 1: x = x.reshape(-1, w.getnchannels()).mean(1)
        cache[f] = (x, w.getframerate())
    return cache[f]

def features(s):
    x, sr = load(s['f'])
    if s.get('d'): x = x[int(s['a'] * sr):int((s['a'] + s['d']) * sr)]
    x = x - x.mean() if len(x) else x
    n = len(x)
    if n < 256: return None
    win = int(.01 * sr)
    env = np.sqrt(np.convolve(x * x, np.ones(win) / win, mode='same'))
    pk = env.max() + 1e-9
    above = np.where(env > pk * .1)[0]
    dur = (above[-1] - above[0]) / sr if len(above) else 0
    attack = (np.argmax(env) - above[0]) / sr if len(above) else 0
    spec = np.abs(np.fft.rfft(x * np.hanning(n)))
    fr = np.fft.rfftfreq(n, 1 / sr)
    tot = spec.sum() + 1e-9
    cent = float((spec * fr).sum() / tot)
    low = float(spec[fr < 150].sum() / tot)
    high = float(spec[fr > 5000].sum() / tot)
    zcr = float(np.mean(np.abs(np.diff(np.sign(x))) > 0))
    # how pitched: autocorrelation peak around the loudest 100 ms
    c = int(np.argmax(env)); a = max(0, c - int(.05 * sr)); seg = x[a:a + int(.1 * sr)]
    pitch = 0.0
    if len(seg) > 400:
        seg = seg - seg.mean()
        ac = np.correlate(seg, seg, 'full')[len(seg) - 1:]
        ac /= ac[0] + 1e-9
        lo, hi = int(sr / 1000), min(len(ac) - 1, int(sr / 50))
        pitch = float(ac[lo:hi].max()) if hi > lo else 0.0
    # how many separate hits
    on = 0; armed = True
    for v in env[::win]:
        if armed and v > pk * .5: on += 1; armed = False
        elif v < pk * .2: armed = True
    return dict(dur=dur, attack=attack, cent=cent, low=low, high=high, zcr=zcr, pitch=pitch, hits=on)

NAME_RULES = [
    ('kick', r'\bkick\b|thud|sub drop'),
    ('snare', r'snare|clap|slap'),
    ('hat', r'\bhat\b|cymbal|wash|crash'),
    ('perc', r'\btom\b|rim|lid|clank|pop|drip|hinge|ding'),
    ('bass', r'\bbass\b'),
    ('keys', r'chord|stab|organ|harp|pluck|bowl|doink'),
    ('lead', r'\blead\b'),
    ('pad', r'\bpad\b|glide'),
    ('voice', r'voice|burp|gulp|snore|spit|sneeze|cough|scream|gremlin'),
    ('fx', r'zap|wobble|sigh|whine|squelch|boil|splash|fax'),
]
SHELF = {'Hits': 'perc', 'Bass': 'bass', 'Stabs': 'keys', 'Pads': 'pad', 'Leads': 'lead'}

def by_audio(f):
    if f['hits'] >= 3 and f['dur'] > .6: return 'chop'
    if f['dur'] < .6 and f['attack'] < .03:
        if f['low'] > .35 and f['cent'] < 600: return 'kick'
        if (f['high'] > .35 or f['cent'] > 6500) and f['pitch'] < .45: return 'hat'
        if f['pitch'] < .45 and f['cent'] > 1500: return 'snare'
        return 'perc'
    if f['pitch'] > .6:
        if f['cent'] < 450: return 'bass'
        if f['dur'] > 1.4 and f['attack'] > .05: return 'pad'
        if f['cent'] > 2600 and f['dur'] < 1.4: return 'lead'
        return 'keys'
    if f['dur'] > 1.4: return 'pad' if f['pitch'] > .4 else 'fx'
    return 'fx'

def qualities(f):
    q = []
    if f['attack'] < .015 and f['dur'] < .5: q.append('punchy')
    if f['dur'] > 1.2: q.append('long')
    if f['cent'] < 700: q.append('dark')
    if f['cent'] > 4500: q.append('bright')
    if f['pitch'] > .8: q.append('tonal')
    if f['zcr'] > .18 and f['pitch'] < .5: q.append('gritty')
    if f['hits'] >= 3: q.append('busy')
    return q

counts = collections.Counter(); qc = collections.Counter()
for s in lib['sounds']:
    f = features(s) or dict(dur=0, attack=0, cent=1000, low=0, high=0, zcr=0, pitch=0, hits=1)
    t = None
    if s['c'] == 'qbldy': t = SHELF.get(s.get('g', ''))
    if t is None and s['c'] != 'heap':
        for ty, rx in NAME_RULES:
            if re.search(rx, s['t'], re.I): t = ty; break
    if t is None and s['c'] == 'bodily': t = 'voice'
    if t is None: t = by_audio(f)
    s['ty'] = t
    s['q'] = qualities(f)
    counts[t] += 1
    for x in s['q']: qc[x] += 1
json.dump(lib, open(ROOT + 'library.json', 'w'), separators=(',', ':'))
print('types', dict(counts)); print('qualities', dict(qc))

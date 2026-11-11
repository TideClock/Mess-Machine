#!/usr/bin/env python3
"""
Build a sound pack for the app (SE-17) from the Vault's decisions.

1. Export the Vault's store (Claude does this with the artifact data tool, list with out_dir):
     <export>/decisions/<clip id>.json   {"v": "keep"|"skip"|null, "packs": [...]}
     <export>/packs/<pack id>.json       {"name": ..., "status": "draft"|"ready"}
2. make_pack.py <export dir> <library dir> <pack id> <out dir> [--version N] [--blurb "..."]
   writes <out>/<pack id>.zip (pack.json + clips/*.flac) and updates <out>/packs.json.
3. Upload both to the `packs` release:  gh release upload packs <out>/<id>.zip <out>/packs.json --clobber

Only clips that are in the pack AND kept go in. A pack that is not marked ready is refused unless --draft.
"""
import json, os, sys, zipfile, glob

TYPE = {'kick': 'kick', 'snare or clap': 'snare', 'closed hat': 'hat', 'open hat or cymbal': 'hat', 'tom or thump': 'perc', 'knock': 'perc', 'click': 'perc', 'bell or ping': 'perc'}
FAM = {'bass': 'bass', 'synth lead': 'lead', 'guitar': 'lead', 'drums': 'chop', 'percussion': 'perc', 'mixed': 'chop'}


def rows(d):
    out = {}
    for f in glob.glob(os.path.join(d, '*.json')):
        o = json.load(open(f))
        out[o.get('id', os.path.splitext(os.path.basename(f))[0])] = o.get('data', o)
    return out


def main():
    a = sys.argv[1:]
    exp, libdir, pid, out = a[:4]
    version = int(a[a.index('--version') + 1]) if '--version' in a else 1
    blurb = a[a.index('--blurb') + 1] if '--blurb' in a else ''
    packs = rows(os.path.join(exp, 'packs'))
    if pid not in packs:
        sys.exit('no pack %s in the export (have %s)' % (pid, ', '.join(packs)))
    p = packs[pid]
    if p.get('status') != 'ready' and '--draft' not in a:
        sys.exit('%s is not marked ready in the Vault' % p.get('name', pid))
    dec = rows(os.path.join(exp, 'decisions'))
    lib = json.load(open(os.path.join(libdir, 'library.json')))['clips']
    by = {c['id'].split('/', 1)[1]: c for c in lib}
    chosen = [cid for cid, d in sorted(dec.items()) if pid in (d.get('packs') or []) and d.get('v') == 'keep' and cid in by]
    if not chosen:
        sys.exit('no kept clips in %s' % pid)
    clips = []
    os.makedirs(out, exist_ok=True)
    zpath = os.path.join(out, pid + '.zip')
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_STORED) as z:   # FLAC is already compressed
        for cid in chosen:
            c = by[cid]
            name = 'clips/%s.flac' % cid
            z.write(os.path.join(libdir, c['file']), name)
            k = c['kind']
            typ = TYPE.get(c['label'], 'perc') if k == 'hit' else 'chop' if k == 'loop' else FAM.get(c.get('family', ''), 'keys')
            lab = (('%d bar' % (c['beats'] // 4) if c['beats'] >= 4 else '%d beat' % c['beats']) + ' loop') if k == 'loop' else c['label']
            title = '%s · %s track %s' % (lab, c['memory'], c['track'])
            e = {'id': cid, 'file': name, 'title': title, 'type': typ, 'group': c['memory'], 'tags': [t for t in c.get('tags', []) if len(t) < 16][:4]}
            if k == 'loop':
                e['bpm'] = c['bpm']; e['beats'] = c['beats']
            clips.append(e)
        z.writestr('pack.json', json.dumps({'id': pid, 'name': p.get('name', pid), 'version': version, 'clips': clips}, indent=1))
    ip = os.path.join(out, 'packs.json')
    index = json.load(open(ip)) if os.path.exists(ip) else []
    index = [x for x in index if x['id'] != pid] + [{'id': pid, 'name': p.get('name', pid), 'blurb': blurb, 'version': version, 'clips': len(clips), 'file': pid + '.zip'}]
    json.dump(index, open(ip, 'w'), indent=1)
    print('%s: %d clips, %.1f MB -> %s' % (p.get('name', pid), len(clips), os.path.getsize(zpath) / 1e6, zpath))


if __name__ == '__main__':
    main()

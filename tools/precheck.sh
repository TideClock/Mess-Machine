#!/usr/bin/env bash
# Quick checks before a push, and the first step in CI (BT-6, BT-7). Seconds, not a 25 minute run.
#  1. braces and brackets balance in every Kotlin and Java file (the cloud workspace cannot compile the app)
#  2. every test tag a test asks for by name still exists in the app (a renamed or removed control)
#  3. no em dashes in anything Tyler reads: the start guide, the docs, the app's own strings
#  4. the start guide was rewritten since the build Tyler last got, when the app changed
# Usage: bash tools/precheck.sh        (from the repo root; needs gh for check 4, skipped without it)
set -uo pipefail
cd "$(dirname "$0")/.."
fail=0

python3 - <<'EOF' || fail=1
import re, os, sys, glob
bad = 0
# strings, characters and comments go in one left-to-right pass, so a '"' or a "//" inside a string can't confuse it
TOK = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])+\'|//[^\n]*|/\*[\s\S]*?\*/')
def strip(s):
    return TOK.sub(lambda m: '' if m.group(0).startswith('/') else '""', s)
src = glob.glob('app/src/**/*.kt', recursive=True) + glob.glob('app/src/**/*.java', recursive=True) + glob.glob('tools/*.java')
for f in src:
    s = strip(open(f, encoding='utf-8').read())
    for o, c in ('{}', '()', '[]'):
        d = s.count(o) - s.count(c)
        if d: print(f"PRECHECK braces: {f} has {d:+d} unmatched {o}{c}"); bad = 1

# 2. tags the tests ask for by name
# only strings on lines that set a tag count (tag =, testTag(, menu items), so a word used elsewhere can't hide a stale tag
taglines = [l for f in glob.glob('app/src/main/**/*.kt', recursive=True) for l in open(f, encoding='utf-8')
            if re.search(r'\btag\s*=|testTag\(|Triple\(|\bitem\(', l)]
lits = set(x for l in taglines for x in re.findall(r'"([^"\\\n]*)"', l))
# plus tag-shaped strings handed to helpers, like fxRow("Reverb", "dfx-rvb")
lits |= set(x for f in glob.glob('app/src/main/**/*.kt', recursive=True)
            for x in re.findall(r'"([a-z0-9]+(?:-[a-z0-9.${}+ ]+)+)"', open(f, encoding='utf-8').read()))
pats = [re.compile('^' + re.sub(r'\\\$\\\{[^}]*\\\}|\\\$[A-Za-z_][A-Za-z0-9_]*', '.+', re.escape(l)) + '$') for l in lits if '$' in l
        and len(re.sub(r'\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*', '', l)) >= 2]  # a bare "$x" would match anything
for f in glob.glob('app/src/test/**/*.kt', recursive=True):
    t = open(f, encoding='utf-8').read()
    for tag in sorted(set(re.findall(r'(?:onNodeWithTag|onAllNodesWithTag|hasTestTag|at|longPress)\(\s*"([^"$\\]+)"', t))):
        if tag in lits or any(p.match(tag) for p in pats): continue
        print(f"PRECHECK stale test: {f} asks for tag \"{tag}\", which nothing in the app has"); bad = 1

# 3. em dashes in what Tyler reads
for f in ['app/src/main/assets/guide.json', 'DESIGN.md', 'CLAUDE.md', 'README.md']:
    if os.path.exists(f) and '—' in open(f, encoding='utf-8').read():
        print(f"PRECHECK em dash in {f}"); bad = 1
for f in glob.glob('app/src/main/**/*.kt', recursive=True):
    for n, line in enumerate(open(f, encoding='utf-8'), 1):
        for lit in re.findall(r'"([^"\n]*)"', line):
            if '—' in lit: print(f"PRECHECK em dash in app text: {f}:{n}"); bad = 1
# 5. format() over a string with ${...} in it: any "%" in the inserted text is read as a format code and throws
#    (build 58: the audio thread crashed every 20 seconds on "0% average"). Format the number alone, or mark the line // format-ok
for f in glob.glob('app/src/main/**/*.kt', recursive=True):
    for n, line in enumerate(open(f, encoding='utf-8'), 1):
        if 'format-ok' in line: continue
        if re.search(r'"[^"\n]*\$\{[^"\n]*"\s*\)?\s*\.format\(', line):
            print(f"PRECHECK format over inserted text (a % in it would crash): {f}:{n}"); bad = 1
# 6. "/*" inside a Kotlin comment opens a NESTED comment, so the comment never closes and the file stops parsing
#    (build 67: "clips/*.flac" in a doc comment broke Packs.kt and everything that used it)
for f in glob.glob('app/src/**/*.kt', recursive=True):
    for n, line in enumerate(open(f, encoding='utf-8'), 1):
        t = line.strip()
        if (t.startswith('*') or t.startswith('//')) and '/*' in t.lstrip('/*'):
            print(f"PRECHECK /* inside a comment opens a nested comment in Kotlin: {f}:{n}"); bad = 1
sys.exit(bad)
EOF

# 4. the guide changes when the app changes (against the commit of the build Tyler last got)
if command -v gh >/dev/null 2>&1; then
  sha=$(gh api repos/TideClock/Mess-Machine/releases/tags/latest --jq .body 2>/dev/null | sed -n 's/.* from \([0-9a-f]\{7,\}\)\..*/\1/p' | head -1)
  if [ -n "$sha" ] && git cat-file -e "$sha^{commit}" 2>/dev/null; then
    if ! git diff --quiet "$sha" -- app/src/main ':!app/src/main/assets/guide.json' && git diff --quiet "$sha" -- app/src/main/assets/guide.json; then
      echo "PRECHECK the app changed since build $sha but app/src/main/assets/guide.json did not: rewrite thisBuild, watch and limits"; fail=1
    fi
  else echo "precheck: last published build not found here, guide check skipped"; fi
else echo "precheck: gh missing, guide check skipped"; fi

[ $fail = 0 ] && echo "precheck: all clear" || echo "precheck: fix the lines above before pushing"
exit $fail

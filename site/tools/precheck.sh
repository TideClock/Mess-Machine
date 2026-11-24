#!/bin/bash
# Run before every publish of the site (NSD-29). Builds, serves the build over http, then runs the checks that exit
# nonzero on failure.
#   ASSETS=/path/with/img  MM=/path/to/mm  bash site/tools/precheck.sh
# ASSETS holds the img/ folder from the live artifact (read it with Artifact read_file, paths=[...]); samples/, audio/,
# loops/ and models/ are used too when they are there. MM is the Mess Machine room's folder (mm/index.html and its
# files); it defaults to $ASSETS/mm. The machine is a module page, so the checks run over http, never file://.
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-/tmp/nsd-check}"
mkdir -p "$OUT/shots"
python3 "$HERE/build.py"
cp "$HERE/index.html" "$HERE/bw.html" "$OUT/"
# link a folder in, unless a real folder of that name is already there
link(){ [ -e "$2" ] && [ ! -L "$2" ] && return 0; ln -sfn "$1" "$2"; }
link "$HERE/fonts" "$OUT/fonts"
if [ -n "$ASSETS" ]; then for d in img samples audio loops models; do [ -d "$ASSETS/$d" ] && link "$ASSETS/$d" "$OUT/$d"; done; fi
MM="${MM:-${ASSETS:+$ASSETS/mm}}"
if [ -n "$MM" ] && [ -f "$MM/index.html" ]; then link "$(cd "$MM" && pwd)" "$OUT/mm"; fi
if [ ! -f "$OUT/mm/index.html" ]; then echo "precheck: the Mess Machine room is missing. Set MM to the folder that holds mm/index.html."; exit 1; fi

# a local server on a free port, stopped when the checks end
PORT=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$OUT" > /dev/null 2>&1 &
SRV=$!
trap 'kill $SRV 2>/dev/null' EXIT
for i in $(seq 1 50); do curl -s -o /dev/null "http://127.0.0.1:$PORT/index.html" && break; sleep 0.1; done
URL="http://127.0.0.1:$PORT"
export NODE_PATH="${NODE_PATH:-$(npm root -g)}"
cd "$OUT"

NSD_URL="$URL/index.html" node "$HERE/tools/mmroom.js"
NSD_URL="$URL/bw.html" node "$HERE/tools/mmroom.js"
NSD_URL="$URL/index.html" node "$HERE/tools/lab.js"
NSD_PAGE="$OUT/index.html" node "$HERE/tools/walk.js"
NSD_URL="$URL/index.html" node "$HERE/tools/run.js" > /dev/null
node -e "
const r = require('$OUT/results.json'); let bad = 0;
for (const [k, m] of Object.entries(r)) { const e = m.errors.filter(x => !/TUNNEL|ERR_FILE_NOT_FOUND|failed: .*\/(img|samples|audio|loops|models)\//.test(x)); if (e.length) { bad++; console.log('FAIL', k, e.join(' | ')); } }
console.log(bad ? bad + ' rooms with page errors' : 'PASS every room, phone and desktop, no page errors'); process.exit(bad ? 1 : 0);"
NSD_PAGE="$OUT/index.html" node "$HERE/tools/weight.js"
echo "precheck passed"

#!/usr/bin/env bash
# Compile the Android app's engine to JavaScript for the web Mess Machine.
# Runs on GitHub Actions (web-engine.yml); the cloud workspace cannot reach Maven Central.
set -euo pipefail
cd "$(dirname "$0")"
SRC=../../app/src/main/java/com/tideclock/messmachine/engine
rm -rf gen && mkdir -p gen/com/tideclock/messmachine/engine out
cp "$SRC"/*.java gen/com/tideclock/messmachine/engine/
# TeaVM's class library has no ConcurrentLinkedQueue. The web engine runs on one thread
# (the AudioWorklet), so a plain ArrayDeque behaves the same.
sed -i 's/java\.util\.concurrent\.ConcurrentLinkedQueue/java.util.ArrayDeque/; s/ConcurrentLinkedQueue</ArrayDeque</g' gen/com/tideclock/messmachine/engine/Engine.java
grep -q ConcurrentLinkedQueue gen/com/tideclock/messmachine/engine/Engine.java && { echo "queue swap failed"; exit 1; }
gradle --no-daemon --stacktrace generateJavaScript
JS=$(find build -name engine.js | head -1)
[ -n "$JS" ] || { echo "no engine.js"; find build -name "*.js"; exit 1; }
cp "$JS" out/engine.js
( cd ../.. && git rev-parse HEAD ) > out/source-commit.txt
ls -la out
node smoke.mjs | tee out/smoke.txt

#!/usr/bin/env bash
# Checks Kotlin versions of engine classes against the Java engine test, with no Gradle and no Android.
#
#   tools/kt-check.sh Theory Json        Kotlin Theory and Json (core/src/commonMain), Java for every other class
#   tools/kt-check.sh all                every class in Kotlin (no Java left), plus the cloud folder (accounts client core)
#   tools/kt-check.sh js                 compile all of core/src/commonMain (engine and cloud) for JavaScript (proves it is pure common code)
#
# Runs tools/EngineTest.java (or $ENGINETEST) and shared/fixtures/RoundTrip.java (or $ROUNDTRIP) against the result.
# Needs KOTLINC pointing at an unpacked kotlin-compiler 2.1.20 (default: the session scratchpad copy).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
KC=${KOTLINC:-/tmp/claude-0/-home-claude/b2ac25c9-5f43-53fc-b7d9-e580307d395c/scratchpad/kc/kotlinc}
J=$root/app/src/main/java/com/tideclock/messmachine/engine
K=$root/core/src/commonMain/kotlin/com/tideclock/messmachine/engine
C=$root/core/src/commonMain/kotlin/com/tideclock/messmachine/cloud
cloud=(); [ -d "$C" ] && while IFS= read -r f; do cloud+=("$f"); done < <(find "$C" -name '*.kt' | sort)
OPT=(-nowarn -opt-in=kotlin.concurrent.atomics.ExperimentalAtomicApi -opt-in=kotlin.time.ExperimentalTime)
SCR=/tmp/claude-0/-home-claude/b2ac25c9-5f43-53fc-b7d9-e580307d395c/scratchpad; [ -d "$SCR" ] || SCR=${TMPDIR:-/tmp}
out=$(mktemp -d "$SCR/ktcheck.XXXXXX")

if [ "${1:-}" = "js" ]; then
  srcs=("$K"/*.kt ${cloud[@]+"${cloud[@]}"})
  all=$(printf '%s,' "${srcs[@]}" | sed 's/,$//')
  "$KC/bin/kotlinc-js" "${OPT[@]}" -Xmulti-platform -Xcommon-sources="$all" -Xir-produce-klib-dir \
    -libraries "$KC/lib/kotlin-stdlib-js.klib" -ir-output-dir "$out/js" -ir-output-name core "${srcs[@]}" 2>&1 | grep -v JAVA_TOOL || true
  [ -d "$out/js" ] && echo "JS OK: core/src/commonMain compiles for JavaScript" || { echo "JS FAILED"; exit 1; }
  exit 0
fi

kt=("$K/Plat.kt"); javas=()
if [ "${1:-}" = "all" ]; then
  for f in "$K"/*.kt; do [ "$(basename "$f")" = Plat.kt ] || kt+=("$f"); done
  kt+=(${cloud[@]+"${cloud[@]}"})
else
  for n in "$@"; do kt+=("$K/$n.kt"); done
  for f in "$J"/*.java; do
    b=$(basename "$f" .java); skip=0
    for n in "$@"; do [ "$n" = "$b" ] && skip=1; done
    [ $skip = 0 ] && javas+=("$f")
  done
fi

echo "== kotlinc (${#kt[@]} Kotlin files, ${#javas[@]} Java files)"
"$KC/bin/kotlinc-jvm" "${OPT[@]}" -d "$out/kt" "${kt[@]}" ${javas[@]+"${javas[@]}"} 2>&1 | grep -v JAVA_TOOL || true
[ -d "$out/kt/com" ] || { echo "KOTLIN COMPILE FAILED"; exit 1; }
CP="$out/kt:$KC/lib/kotlin-stdlib.jar"
echo "== javac"
javac -nowarn -cp "$CP" -d "$out/j" ${javas[@]+"${javas[@]}"} "${ENGINETEST:-$root/tools/EngineTest.java}" "${ROUNDTRIP:-$root/shared/fixtures/RoundTrip.java}" 2>&1 | grep -v "JAVA_TOOL\|^Note:" || true
[ -f "$out/j/EngineTest.class" ] || { echo "JAVA COMPILE FAILED"; exit 1; }
echo "== EngineTest"
java -cp "$out/j:$CP" EngineTest "$root/app/src/main/assets/sounds/" "$out/j" 2>&1 | grep -v JAVA_TOOL | grep -E "^FAIL|FAILED|ALL GOOD|Exception|at com\.|Error" | head -40
echo "== RoundTrip"
java -cp "$out/j:$CP" RoundTrip "$root/shared/fixtures" "$root/app/src/main/assets/sounds/" 2>&1 | grep -v JAVA_TOOL | grep -E "^FAIL|FAILED|ALL PASS|Exception|at com\.|Error" | head -20
echo "(build output in $out)"

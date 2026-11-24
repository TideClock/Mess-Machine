#!/usr/bin/env bash
# Checks the Slice B sync core (Ops, SyncSession, FakeCloudB) with no Gradle and no Android.
#
#   tools/sync-check.sh              compile core/src/commonMain (engine + cloud) for the JVM, run tools/SyncTest.java
#   tools/sync-check.sh 200          the same with 200 fuzz seeds (default 150)
#   tools/sync-check.sh js           compile the engine and the whole cloud folder for JavaScript (proves it is common Kotlin)
#
# Prints FAIL lines and ends with SYNC GOOD or "N FAILED"; exits non-zero on a failure.
# Needs KOTLINC pointing at an unpacked kotlin-compiler 2.1.20 (default: the session scratchpad copy).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
KC=${KOTLINC:-/tmp/claude-0/-home-claude/b2ac25c9-5f43-53fc-b7d9-e580307d395c/scratchpad/kc/kotlinc}
E=$root/core/src/commonMain/kotlin/com/tideclock/messmachine/engine
C=$root/core/src/commonMain/kotlin/com/tideclock/messmachine/cloud
OPT=(-nowarn -opt-in=kotlin.concurrent.atomics.ExperimentalAtomicApi -opt-in=kotlin.time.ExperimentalTime)
SCR=/tmp/claude-0/-home-claude/b2ac25c9-5f43-53fc-b7d9-e580307d395c/scratchpad; [ -d "$SCR" ] || SCR=${TMPDIR:-/tmp}
out=$(mktemp -d "$SCR/synccheck.XXXXXX")
srcs=$(find "$E" "$C" -name '*.kt' | sort)

if [ "${1:-}" = "js" ]; then
  all=$(echo "$srcs" | tr '\n' ',' | sed 's/,$//')
  # shellcheck disable=SC2086
  "$KC/bin/kotlinc-js" "${OPT[@]}" -Xmulti-platform -Xcommon-sources="$all" -Xir-produce-klib-dir \
    -libraries "$KC/lib/kotlin-stdlib-js.klib" -ir-output-dir "$out/js" -ir-output-name core $srcs 2>&1 | grep -v "JAVA_TOOL\|_JAVA_OPTIONS" || true
  [ -d "$out/js" ] && echo "JS OK: engine and cloud compile for JavaScript" || { echo "JS FAILED"; exit 1; }
  exit 0
fi

echo "== kotlinc (engine + cloud)"
# shellcheck disable=SC2086
"$KC/bin/kotlinc-jvm" "${OPT[@]}" -d "$out/kt" $srcs 2>&1 | grep -v "JAVA_TOOL\|_JAVA_OPTIONS" || true
[ -d "$out/kt/com/tideclock/messmachine/cloud" ] || { echo "KOTLIN COMPILE FAILED"; exit 1; }
CP="$out/kt:$KC/lib/kotlin-stdlib.jar"
echo "== javac"
javac -nowarn -cp "$CP" -d "$out/j" "${SYNCTEST:-$root/tools/SyncTest.java}" 2>&1 | grep -v "JAVA_TOOL\|_JAVA_OPTIONS\|^Note:" || true
[ -f "$out/j/SyncTest.class" ] || { echo "JAVA COMPILE FAILED"; exit 1; }
echo "== SyncTest"
set +e
java -Xss8m -cp "$out/j:$CP" SyncTest "$root/app/src/main/assets/sounds/" "${1:-150}" 2>&1 | grep -v "JAVA_TOOL\|_JAVA_OPTIONS"
rc=${PIPESTATUS[0]}
set -e
echo "(build output in $out)"
exit "$rc"

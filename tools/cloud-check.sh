#!/usr/bin/env bash
# Checks the accounts client core (core/src/commonMain/.../cloud) with no Gradle and no Android:
# compiles the engine and the cloud folder with kotlinc, then compiles and runs tools/CloudTest.java against it.
#
#   tools/cloud-check.sh          prints FAIL lines and ends with CLOUD GOOD, or N FAILED (exit 1)
#
# If a Slice B file (Ops.kt, SyncSession.kt, fake/FakeCloudB.kt) does not compile right now, the check says so and
# runs without them, since another builder owns them. Needs KOTLINC pointing at an unpacked kotlin-compiler 2.1.20
# (default: the session scratchpad copy, as tools/kt-check.sh).
set -uo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
KC=${KOTLINC:-/tmp/claude-0/-home-claude/b2ac25c9-5f43-53fc-b7d9-e580307d395c/scratchpad/kc/kotlinc}
K=$root/core/src/commonMain/kotlin/com/tideclock/messmachine/engine
C=$root/core/src/commonMain/kotlin/com/tideclock/messmachine/cloud
OPT=(-nowarn -opt-in=kotlin.concurrent.atomics.ExperimentalAtomicApi -opt-in=kotlin.time.ExperimentalTime)
SCR=/tmp/claude-0/-home-claude/b2ac25c9-5f43-53fc-b7d9-e580307d395c/scratchpad; [ -d "$SCR" ] || SCR=${TMPDIR:-/tmp}
out=$(mktemp -d "$SCR/cloudcheck.XXXXXX")

all=(); mine=()
while IFS= read -r f; do
  all+=("$f")
  case "$(basename "$f")" in Ops.kt|SyncSession.kt|FakeCloudB.kt) ;; *) mine+=("$f") ;; esac
done < <(find "$C" -name '*.kt' | sort)

compile() {
  rm -rf "$out/kt"
  "$KC/bin/kotlinc-jvm" "${OPT[@]}" -d "$out/kt" "$K"/*.kt "$@" > "$out/kotlinc.txt" 2>&1
  [ -f "$out/kt/com/tideclock/messmachine/cloud/CloudStore.class" ]
}

echo "== kotlinc (engine and ${#all[@]} cloud files)"
if ! compile "${all[@]}"; then
  echo "NOTE: the whole cloud folder does not compile; trying without Ops.kt, SyncSession.kt and FakeCloudB.kt:"
  grep -E "error:" "$out/kotlinc.txt" | head -5
  if ! compile "${mine[@]}"; then
    grep -v JAVA_TOOL "$out/kotlinc.txt" | grep -E "error:" -A2 | head -40
    echo "KOTLIN COMPILE FAILED"; exit 1
  fi
fi
CP="$out/kt:$KC/lib/kotlin-stdlib.jar"
echo "== javac"
javac -nowarn -cp "$CP" -d "$out/j" "$root/tools/CloudTest.java" 2>&1 | grep -v "JAVA_TOOL\|^Note:"
[ -f "$out/j/CloudTest.class" ] || { echo "JAVA COMPILE FAILED"; exit 1; }
echo "== CloudTest"
java -cp "$out/j:$CP" CloudTest > "$out/test.txt" 2>&1
code=$?
grep -v JAVA_TOOL "$out/test.txt" | grep -E "^FAIL|^   got|FAILED|CLOUD GOOD|Exception|^\s+at " | head -60
grep -q "CLOUD GOOD" "$out/test.txt" || code=1
echo "(build output in $out)"
exit $code

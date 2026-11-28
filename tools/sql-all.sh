#!/bin/sh
# Rebuild server/supabase/all-in-one.sql from the migrations (for the dashboard SQL Editor).
set -e
cd "$(dirname "$0")/.."
d=server/supabase
{ echo "-- Mess Machine accounts: every file of supabase/migrations/ in name order, for pasting into the dashboard's SQL Editor in one go."; echo "-- Made by tools/sql-all.sh; do not edit here. Safe to run twice."; for f in $(ls $d/migrations/*.sql | sort); do echo; echo "-- ===== $(basename $f) ====="; cat $f; done; } > $d/all-in-one.sql
# one file per Edge Function for the dashboard editor (its index.ts with _shared/mm.ts pasted in)
mkdir -p $d/dashboard
for fn in sound-urls delete-account sweep; do
  { echo "// $fn for the Supabase dashboard's function editor: functions/$fn/index.ts with _shared/mm.ts pasted in, in one file."; echo "// Made by tools/sql-all.sh; edit the originals, not this."; sed 's/^export //' $d/functions/_shared/mm.ts; echo; grep -v '_shared/mm.ts' $d/functions/$fn/index.ts; } > $d/dashboard/$fn.ts
done

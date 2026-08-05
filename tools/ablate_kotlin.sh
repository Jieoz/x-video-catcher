#!/bin/bash
# Ablate each load-bearing clause of HostRow's label/id rules and require the suite to go RED.
#
# isSheetControllerShape ablations lived here in 1.11.0; that predicate was retired with the
# unused tweet-action controller. Keeping those anchors would count ANCHOR-MISS as "not
# load-bearing" and fail a correct build -- the opposite of what a gate is for.
#
# Each ablation is a single-clause edit, applied to a copy, tested, then reverted. The expectation
# is failure: an ablation that leaves the suite green means that clause is decoration.
set -u

REPO="${GITHUB_WORKSPACE:-$(cd "$(dirname "$0")/.." && pwd)}"
ROW=$REPO/app/src/main/java/com/jiesa/xvideocatcher/hook/HostRow.kt
SPY=$REPO/app/src/main/java/com/jiesa/xvideocatcher/hook/MediaSpy.kt

cp "$ROW" /tmp/HostRow.orig
cp "$SPY" /tmp/MediaSpy.orig
restore() { cp /tmp/HostRow.orig "$ROW"; cp /tmp/MediaSpy.orig "$SPY"; }
trap restore EXIT

run_suite() {
  cd "$REPO" || return 9
  timeout 1200 gradle :app:testDebugUnitTest --console=plain -q >/tmp/abl.out 2>&1
  return $?
}

fail=0
ablate() {
  local name="$1" file="$2" from="$3" to="$4"
  restore
  python3 - "$file" "$from" "$to" <<'PY'
import sys
path, frm, to = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
if s.count(frm) != 1:
    print("ANCHOR-MISS %d" % s.count(frm)); sys.exit(3)
open(path, "w").write(s.replace(frm, to))
PY
  if [ $? -ne 0 ]; then
    echo "  SKIP  $name (anchor not unique -- the ablation itself is broken)"
    fail=$((fail+1)); return
  fi
  run_suite
  local rc=$?
  if [ "$rc" -eq 0 ]; then
    echo "  GREEN $name  <-- NOT LOAD-BEARING"
    fail=$((fail+1))
  else
    echo "  red   $name"
  fi
}

echo "=== ablating HostRow ==="

# The scribe-key exclusion: choose the longest String outright.
ablate "scribe-key exclusion" "$ROW" \
  'if (v.isNullOrBlank() || looksLikeScribeKey(v)) null else f to v' \
  'if (v.isNullOrBlank()) null else f to v'

# The blank check: allow empty Strings to win.
ablate "blank-label exclusion" "$ROW" \
  'if (v.isNullOrBlank() || looksLikeScribeKey(v)) null else f to v' \
  'if (looksLikeScribeKey(v)) null else f to (v ?: "")'

# The id assignment itself: clone the row but leave the template's id on it.
#
# Two earlier versions of this ablation came back GREEN and both were measuring nothing, for two
# different reasons worth recording:
#
#   1. Removing the explicit `if (ints.isEmpty()) return null` left a runCatching that produced the
#      same null. Redundant mechanisms cannot be tested individually -- fixed in the source.
#   2. Replacing the guard with `?: fields.first()` still failed, because setInt on a String field
#      throws whatever the guard says. "A row with no int cannot be cloned" is guaranteed by
#      reflection semantics, so no test can make it fail and no ablation can prove one does.
#
# What IS worth defending is that the id gets *overwritten*. A clone carrying the template's id
# would be dispatched as the host's own row -- the download row would silently trigger Share via.
# That is a real, reachable bug, and this ablation is the one that exposes it.
ablate "id assignment" "$ROW" \
  'idField.setInt(copy, id)' \
  'idField.setInt(copy, idField.getInt(copy))'

echo "=== ablating MediaSpy ==="

# Finality is what separates DataSpec from its Builder (identical field types, no finals).
ablate "finality discriminator" "$SPY" \
  'return fields.all { Modifier.isFinal(it.modifiers) }' \
  'return true'

# The reflection spelling of byte[] is [B. Writing byte[] makes the predicate always false.
ablate "byte-array reflection name" "$SPY" \
  '"[B", "java.util.Map"' \
  '"byte[]", "java.util.Map"'

restore
echo
if [ "$fail" -ne 0 ]; then
  echo "RESULT: $fail clause(s) not load-bearing"
  exit 1
fi
echo "RESULT: all clauses load-bearing"

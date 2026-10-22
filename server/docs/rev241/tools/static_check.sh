#!/bin/bash
# rev241 static interface check: every (interface, component) the server names must exist in the cache.
#
#   docs/rev241/tools/static_check.sh <cacheDir> <out.txt> [<cache211Dir> <component-remap.tsv>]
#
# Needs the server's shadow jar (./gradlew :app:shadowJar). <cacheDir> is the cache the server runs on (cacheV);
# the optional <cache211Dir> (cacheU: GadScape's 211 interfaces) and gadconv's <cache>.component-remap.tsv add the type
# comparison (a component whose type differs from the 211 component it was remapped from is a WARN).
# Exit status 1 when a reference is missing from the cache (MISS) or names a relocated interface by literal.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
server="$(cd "$here/../../.." && pwd)"
cache="$1"; out="$2"; cache211="${3:-}"; remap="${4:-}"
jar="$(ls -t "$server"/app/build/libs/*.jar 2>/dev/null | head -1)"
[ -n "$jar" ] || { echo "no app shadow jar under $server/app/build/libs (run :app:shadowJar)"; exit 2; }
# STATIC_CHECK_WORK=<dir> keeps the intermediate dumps (comps.tsv, runtime.tsv, comps211.tsv) there
if [ -n "${STATIC_CHECK_WORK:-}" ]; then work="$STATIC_CHECK_WORK"; mkdir -p "$work"; else work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT; fi
javac -nowarn -d "$work" -cp "$jar" "$here/IfStaticCheck.java"
run() { (cd "$server" && java -Xmx3g -Dgadscape.cache.dir="$2" -cp "$jar:$work" IfStaticCheck "$1" "$2" "$3" > "$3.log" 2>&1) || { tail -20 "$3.log"; exit 2; }; }
run dump "$cache" "$work/comps.tsv"
run runtime "$cache" "$work/runtime.tsv"
extra=()
if [ -n "$cache211" ]; then
  run dump "$cache211" "$work/comps211.tsv"
  extra+=(--dump211 "$work/comps211.tsv")
fi
[ -n "$remap" ] && extra+=(--remap "$remap")
python3 -I "$here/static_check.py" --server "$server" --cache "$cache" --dump "$work/comps.tsv" \
  --runtime "$work/runtime.tsv" "${extra[@]}" --out "$out"

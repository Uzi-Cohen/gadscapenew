#!/usr/bin/env bash
# javac-compiles core + convert + the given packages (no Gradle) into $GADCONV_CLASSES.
#
#   dev/compile.sh [PKG ...]
#
# PKG is a package directory relative to src/main/java/com/gadscape/cacheconv (e.g. "cli", "maps", "steps/ui"),
# an absolute directory, or "all" for every package. Resources (src/main/resources) are copied next to the classes.
# Compiles are serialized with a lock, so several people can share one classes directory.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
mkdir -p "$GADCONV_CLASSES"
list=$(mktemp)
trap 'rm -f "$list"' EXIT
dirs=("$SRC_DIR/core" "$SRC_DIR/convert")
for p in "$@"; do
  if [[ "$p" == "all" ]]; then dirs=("$SRC_DIR"); break; fi
  if [[ "$p" == /* ]]; then dirs+=("$p"); else dirs+=("$SRC_DIR/${p#com/gadscape/cacheconv/}"); fi
done
for d in "${dirs[@]}"; do
  [[ -d "$d" ]] || { echo "no such package directory: $d" >&2; exit 2; }
  find "$d" -name '*.java' >> "$list"
done
sort -u -o "$list" "$list"
exec 9>"$GADCONV_CLASSES/.compile.lock"
flock 9
javac -nowarn -encoding UTF-8 --release 21 -Xlint:-options -cp "$GADCONV_CLASSES:$GADCONV_JAR" -d "$GADCONV_CLASSES" @"$list"
if [[ -d "$RES_DIR" ]]; then cp -r "$RES_DIR/." "$GADCONV_CLASSES/"; fi
echo "compiled $(wc -l < "$list") files into $GADCONV_CLASSES"

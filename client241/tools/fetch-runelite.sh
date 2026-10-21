#!/bin/bash
# Download every jar listed in a RuneLite lock file into a folder and check each sha256.
# Usage: tools/fetch-runelite.sh <runelite-X.lock> <dest-dir>
# Already-present files are only re-checked. Any hash or size mismatch stops with an error.
set -euo pipefail
lock="$1"; dest="$2"
mkdir -p "$dest"
n=0
while read -r sha size plat name url; do
  case "$sha" in ''|'#'*|version|jvm) continue;; esac
  f="$dest/$name"
  if [ ! -f "$f" ]; then
    echo "downloading $name"
    curl -fsSL --retry 3 -o "$f.part" "$url"
    mv "$f.part" "$f"
  fi
  got=$(sha256sum "$f" | cut -d' ' -f1)
  if [ "$got" != "$sha" ]; then
    echo "SHA-256 MISMATCH for $name: expected $sha, got $got" >&2
    exit 1
  fi
  if [ "$(stat -c %s "$f")" != "$size" ]; then
    echo "SIZE MISMATCH for $name" >&2
    exit 1
  fi
  n=$((n+1))
done < "$lock"
echo "ok: $n jars in $dest match $lock"

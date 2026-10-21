#!/bin/bash
# Download every jar listed in a RuneLite lock file into a folder and check each sha256.
# Usage: tools/fetch-runelite.sh <runelite-X.lock> <dest-dir>
# Already-present files are only re-checked. Any hash or size mismatch deletes the file and stops with an error
# (the next run downloads it again).
set -euo pipefail
lock="$1"; dest="$2"
sha256() { if command -v sha256sum > /dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
mkdir -p "$dest"
n=0
while read -r sha size plat name url; do
  case "$sha" in ''|'#'*|version|jvm|jvm-mac) continue;; esac
  f="$dest/$name"
  if [ ! -f "$f" ]; then
    echo "downloading $name"
    curl -fsSL --retry 3 -o "$f.part" "$url"
    mv "$f.part" "$f"
  fi
  got=$(sha256 "$f")
  if [ "$got" != "$sha" ]; then
    echo "SHA-256 MISMATCH for $name: expected $sha, got $got (deleted $f)" >&2
    rm -f "$f"
    exit 1
  fi
  got_size=$(wc -c < "$f" | tr -d ' ')
  if [ "$got_size" != "$size" ]; then
    echo "SIZE MISMATCH for $name: expected $size, got $got_size (deleted $f)" >&2
    rm -f "$f"
    exit 1
  fi
  n=$((n+1))
done < "$lock"
echo "ok: $n jars in $dest match $lock"

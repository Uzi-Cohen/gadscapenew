#!/bin/bash
# Build GadScape-Setup.exe (see main.go).   installer/build.sh [--url URL] [--host HOST] [--version V] [--out DIR]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
url="http://gadscape.duckdns.org:8088/GadScape.exe"; host="gadscape.duckdns.org"; ver="2.0"; out="$here/build"
while [ $# -gt 0 ]; do case "$1" in
  --url) url="$2"; shift 2;; --host) host="$2"; shift 2;; --version) ver="$2"; shift 2;; --out) out="$2"; shift 2;;
  *) echo "unknown option $1" >&2; exit 2;; esac; done
mkdir -p "$out"
(cd "$here" && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath \
  -ldflags "-s -w -H windowsgui -X 'main.exeURL=$url' -X 'main.serverHost=$host' -X 'main.version=$ver'" \
  -o "$out/GadScape-Setup.exe" .)
echo "built $out/GadScape-Setup.exe"

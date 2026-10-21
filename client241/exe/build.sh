#!/bin/bash
# Build GadScape-241-Test.exe: a Windows x64 exe with a Java JRE and the portable client folder inside.
# Usage: exe/build.sh <windows-x64-jre.zip> <GadScape-241-Test.zip> <out.exe>
#   The JRE zip must have one top folder with bin\javaw.exe (e.g. Temurin 21 JRE). Java 17+ works for RuneLite 1.13.3.
# Needs Go 1.21+. The icon/manifest resource (rsrc_windows_amd64.syso) is committed; make-assets.sh remakes it.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
jre="$1"; client="$2"; out="$3"
if ! unzip -Z1 "$jre" | grep '/bin/javaw.exe$' > /dev/null; then echo "$jre has no bin/javaw.exe (not a Windows JRE?)" >&2; exit 1; fi
rel=$(unzip -p "$jre" '*/release' 2>/dev/null | sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' | head -1)
if [ -n "$rel" ] && [ "$rel" -lt 17 ]; then echo "JRE is Java $rel; RuneLite 1.13.3 needs 17 or newer here" >&2; exit 1; fi
mkdir -p "$here/payload"
ln -f "$jre" "$here/payload/jre.zip" 2>/dev/null || cp "$jre" "$here/payload/jre.zip"
ln -f "$client" "$here/payload/client.zip" 2>/dev/null || cp "$client" "$here/payload/client.zip"
if command -v sha256sum > /dev/null; then sum=sha256sum; else sum="shasum -a 256"; fi
id=$(cat "$here/payload/jre.zip" "$here/payload/client.zip" | $sum | cut -c1-16)
(cd "$here" && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath \
  -ldflags "-s -w -H windowsgui -X main.build=241-$id" -o "$out" .)
rm -f "$here/payload/jre.zip" "$here/payload/client.zip"
echo "built $out (build 241-$id, Java ${rel:-?})"

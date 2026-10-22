#!/bin/bash
# Build the one-file Windows exe: a Windows x64 Java JRE and the portable client folder inside.
# Usage: exe/build.sh <windows-x64-jre.zip> <client.zip> <out.exe> [channel.properties]
#   The JRE zip must have one top folder with bin\javaw.exe (e.g. Temurin 21 JRE). Java 17+ works for RuneLite 1.13.3.
#   channel.properties (written by client241/build.sh) sets the names: title, home.windows, exe.server_file,
#   default.host, channel. Without it the exe is a release build named "GadScape".
# Needs Go 1.21+. The icon/manifest resources are committed (release: exe/, test: exe/test-res/); make-assets.sh
# remakes them. The build runs in a temporary copy, so this folder is never changed.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
jre="$1"; client="$2"; out="$3"; channel="${4:-}"
prop() { [ -n "$channel" ] && sed -n "s/^$1=//p" "$channel" | head -1 || true; }
ch="$(prop channel)"; ch="${ch:-release}"
title="$(prop title)"; title="${title:-GadScape}"
dir="$(prop home.windows)"; dir="${dir:-GadScape}"
sfile="$(prop exe.server_file)"; sfile="${sfile:-GadScape-server.txt}"
host="$(prop default.host)"; host="${host:-127.0.0.1}"
if ! unzip -Z1 "$jre" | grep '/bin/javaw.exe$' > /dev/null; then echo "$jre has no bin/javaw.exe (not a Windows JRE?)" >&2; exit 1; fi
rel=$(unzip -p "$jre" '*/release' 2>/dev/null | sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' | head -1)
if [ -n "$rel" ] && [ "$rel" -lt 17 ]; then echo "JRE is Java $rel; RuneLite 1.13.3 needs 17 or newer here" >&2; exit 1; fi
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cp "$here"/*.go "$here/go.mod" "$here/splash.png" "$here/rsrc_windows_amd64.syso" "$work/"
cp -R "$here/kit" "$work/kit"   # shared with the setup (client241/setup)
rm -f "$work"/kit/*_test.go
if [ "$ch" = "test" ]; then
  cp "$here/test-res/splash.png" "$here/test-res/rsrc_windows_amd64.syso" "$work/"
fi
mkdir -p "$work/payload"
ln -f "$jre" "$work/payload/jre.zip" 2>/dev/null || cp "$jre" "$work/payload/jre.zip"
ln -f "$client" "$work/payload/client.zip" 2>/dev/null || cp "$client" "$work/payload/client.zip"
if command -v sha256sum > /dev/null; then sum=sha256sum; else sum="shasum -a 256"; fi
id=$(cat "$work/payload/jre.zip" "$work/payload/client.zip" | $sum | cut -c1-16)
case "$out" in /*) ;; *) out="$PWD/$out";; esac
(cd "$work" && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath \
  -ldflags "-s -w -H windowsgui -X main.build=241-$id -X 'main.appName=$title' -X 'main.dirName=$dir' -X 'main.serverFile=$sfile' -X 'main.defaultHost=$host'" \
  -o "$out" .)
echo "built $out ($ch build 241-$id, '$title', %LOCALAPPDATA%\\$dir, $sfile, default server $host, Java ${rel:-?})"

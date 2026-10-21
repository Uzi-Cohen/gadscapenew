#!/bin/bash
# Build the GadScape build-241 TEST client.
#
#   client241/build.sh [--runelite-jars DIR] [--modulus FILE] [--out DIR] [--jre-zip FILE]
#
#   --runelite-jars  where the RuneLite 1.13.3 jars are kept (downloaded if missing, always hash-checked)
#                    default: client241/build/runelite-1.13.3
#   --modulus        public RSA modulus (hex) to put in the client. default: client241/test-rsa-public-modulus.hex
#   --out            output folder. default: client241/build/out
#   --jre-zip        a Windows x64 Java 17+ JRE zip (one top folder). With it, the Windows exe is built too.
#
# Output in --out:
#   injected-client-1.13.3-gadscape.jar   the patched game client
#   GadScape-241-Test/                     portable folder: run.sh / run.bat, lib*/ jars, server.txt
#   GadScape-241-Test.zip                  the same folder zipped
#   GadScape-241-Test.exe                  (with --jre-zip) one-file Windows launcher with Java inside
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
rl="$here/build/runelite-1.13.3"
modulus="$here/test-rsa-public-modulus.hex"
out="$here/build/out"
jre=""
while [ $# -gt 0 ]; do
  case "$1" in
    --runelite-jars) rl="$2"; shift 2;;
    --modulus) modulus="$2"; shift 2;;
    --out) out="$2"; shift 2;;
    --jre-zip) jre="$2"; shift 2;;
    *) echo "unknown option $1" >&2; exit 2;;
  esac
done
lock="$here/runelite-1.13.3.lock"
mkdir -p "$out"

echo "== 1/5 RuneLite 1.13.3 jars"
"$here/tools/fetch-runelite.sh" "$lock" "$rl"
stock_sha=$(awk '$4 ~ /^injected-client-/ {print $1}' "$lock")

echo "== 2/5 patcher"
"$here/patcher/build.sh"
pcp="$here/patcher/build/gadscape-patcher.jar:$here/patcher/build/deps/*"
patched="$out/injected-client-1.13.3-gadscape.jar"
java -cp "$pcp" gadscape.patcher.Patcher patch --in "$rl/injected-client-1.13.3.jar" --out "$patched" \
  --modulus "@$modulus" --expect-sha256 "$stock_sha"

echo "== 3/5 launcher"
"$here/launcher/build.sh" "$rl"

echo "== 4/5 portable folder"
dist="$out/GadScape-241-Test"
rm -rf "$dist" "$out/GadScape-241-Test.zip"
mkdir -p "$dist/lib" "$dist/lib-win" "$dist/lib-linux" "$dist/lib-macos"
cp "$here/launcher/build/gadscape-launcher.jar" "$dist/lib/"
cp "$patched" "$dist/lib/"
while read -r sha size plat name url; do
  case "$sha" in ''|'#'*|version|jvm) continue;; esac
  case "$name" in injected-client-*) continue;; esac   # the stock game client never goes in
  case "$plat" in
    all) d=lib;;
    win|win-x86|win-aarch64) [ "$plat" = win ] || continue; d=lib-win;;   # x64 Windows only
    linux) d=lib-linux;;
    macos|macos-aarch64) d=lib-macos;;
    *) continue;;
  esac
  cp "$rl/$name" "$dist/$d/"
done < "$lock"
# JVM options RuneLite's own launcher uses for this version (java @jvm-args.txt reads them)
awk '$1 == "jvm" {sub(/^jvm /, ""); print}' "$lock" > "$dist/jvm-args.txt"
printf '127.0.0.1\n' > "$dist/server.txt"
cp "$here/dist/run.sh" "$here/dist/run.bat" "$here/dist/README.txt" "$dist/"
chmod +x "$dist/run.sh"
(cd "$out" && zip -qr GadScape-241-Test.zip GadScape-241-Test)
echo "portable folder: $dist"

if [ -n "$jre" ]; then
  echo "== 5/5 Windows exe"
  "$here/exe/build.sh" "$jre" "$out/GadScape-241-Test.zip" "$out/GadScape-241-Test.exe"
else
  echo "== 5/5 Windows exe skipped (no --jre-zip)"
fi
echo "done: $out"

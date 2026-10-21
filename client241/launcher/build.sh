#!/bin/bash
# Build the launcher: plain javac against gson from the RuneLite jar set (it runs inside RuneLite's JVM, where
# gson is already on the classpath). Usage: launcher/build.sh <dir with the RuneLite 1.13.3 jars>
# Output: launcher/build/gadscape-launcher.jar
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
rl="${1:?usage: build.sh <runelite jar dir>}"
out="$here/build"
rm -rf "$out/classes"; mkdir -p "$out/classes"
javac --release 11 -encoding UTF-8 -nowarn -cp "$rl/gson-2.8.5.jar" -d "$out/classes" $(find "$here/src" -name '*.java')
jar --create --file "$out/gadscape-launcher.jar" --main-class gadscape.launcher.Launcher -C "$out/classes" .
echo "built $out/gadscape-launcher.jar"

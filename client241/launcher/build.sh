#!/bin/bash
# Build the launcher: plain javac against gson from the RuneLite jar set (it runs inside RuneLite's JVM, where
# gson is already on the classpath).
#   launcher/build.sh <dir with the RuneLite 1.13.3 jars> [channel.properties]
# channel.properties (written by client241/build.sh) goes into the jar as gadscape/launcher/channel.properties and
# sets the build channel (release or test, see Channel.java); without it the launcher is a test build.
# Output: launcher/build/gadscape-launcher.jar
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
rl="${1:?usage: build.sh <runelite jar dir> [channel.properties]}"
channel="${2:-}"
out="$here/build"
rm -rf "$out/classes"; mkdir -p "$out/classes"
javac --release 11 -encoding UTF-8 -nowarn -cp "$rl/gson-2.8.5.jar" -d "$out/classes" $(find "$here/src" -name '*.java')
if [ -n "$channel" ]; then
  cp "$channel" "$out/classes/gadscape/launcher/channel.properties"
fi
jar --create --file "$out/gadscape-launcher.jar" --main-class gadscape.launcher.Launcher -C "$out/classes" .
echo "built $out/gadscape-launcher.jar${channel:+ (channel: $(sed -n 's/^channel=//p' "$channel"))}"

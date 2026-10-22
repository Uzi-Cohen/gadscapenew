#!/bin/bash
# Build gadscape-plugins.jar: GadScape's own RuneLite plugins (today: GadScape Login, ported from the 211 client).
# They are ordinary core plugins (package net.runelite.client.plugins.*): RuneLite 1.13.3 finds them on its
# classpath at startup, so the jar only has to sit in lib/ next to RuneLite's jars.
#
#   plugins/build.sh <dir with the RuneLite 1.13.3 jars> <branded client jar (patcher brand output)>
#
# The branded client jar is needed because the login plugin draws through GadScapePaintHook, which the patcher adds.
# Output: plugins/build/gadscape-plugins.jar
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
rl="${1:?usage: build.sh <runelite jar dir> <branded client jar>}"
client="${2:?usage: build.sh <runelite jar dir> <branded client jar>}"
out="$here/build"
rm -rf "$out/classes"; mkdir -p "$out/classes"
cp_="$client"
for j in "$rl"/runelite-api-*.jar "$rl"/guice-*.jar "$rl"/javax.inject-*.jar "$rl"/slf4j-api-*.jar "$rl"/gson-*.jar "$rl"/guava-*.jar "$rl"/jsr305-*.jar; do
  cp_="$cp_:$j"
done
javac --release 11 -encoding UTF-8 -nowarn -proc:none -cp "$cp_" -d "$out/classes" $(find "$here/src" -name '*.java')
cp -R "$here/resources/." "$out/classes/"
jar --create --file "$out/gadscape-plugins.jar" -C "$out/classes" .
echo "built $out/gadscape-plugins.jar"

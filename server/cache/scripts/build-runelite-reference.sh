#!/usr/bin/env bash
# Builds the RuneLite reference jar that `./gradlew :cache:verifyCache -Dgadscape.rlref.jar=...` compares against.
# It compiles RuneLite's own cache library (BSD-2, https://github.com/runelite/runelite, module "cache") from a
# checkout; nothing from it is copied into GadScape.
#
# Usage: build-runelite-reference.sh <runelite checkout> <lombok jar (1.18.30+ for JDK 21)> <output dir>
# Prints the value to pass as -Dgadscape.rlref.jar (the jar plus the JNA and commons-io jars it needs at runtime).
# Verified with RuneLite master 330e650 and lombok 1.18.38.
set -euo pipefail
RL="$1"; LOMBOK="$2"; OUT="$3"
G="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
# newest version of a cached artifact (sorted by file name, version-aware)
dep() { find "$G/$1" -name "$2" ! -name '*sources*' ! -name '*javadoc*' -printf '%f %p\n' | sort -V | tail -1 | cut -d' ' -f2; }
CP="$(dep com.google.guava/guava 'guava-*.jar'):$(dep org.slf4j/slf4j-api 'slf4j-api-2*.jar'):$(dep org.apache.commons/commons-compress 'commons-compress-*.jar'):$(dep com.google.code.gson/gson 'gson-*.jar'):$(dep commons-cli/commons-cli 'commons-cli-*.jar'):$(dep net.java.dev.jna/jna 'jna-5*.jar'):$(dep org.antlr/antlr4-runtime 'antlr4-runtime-*.jar'):$(dep commons-io/commons-io 'commons-io-2*.jar')"
mkdir -p "$OUT/classes"
# the script assembler needs ANTLR-generated sources; the verifier does not use it
find "$RL/cache/src/main/java" -name '*.java' \
  | grep -v '/script/assembler/\|/script/disassembler/\|/script/Instructions\|/script/Instruction\|RuneLiteInstructions' > "$OUT/sources.txt"
javac -nowarn -proc:full -processorpath "$LOMBOK" -cp "$LOMBOK:$CP" -d "$OUT/classes" @"$OUT/sources.txt"
REV="$(git -C "$RL" rev-parse --short HEAD 2>/dev/null || echo unknown)"
(cd "$OUT/classes" && jar cf "../runelite-cache-$REV.jar" .)
echo "$OUT/runelite-cache-$REV.jar:$(dep net.java.dev.jna/jna 'jna-5*.jar'):$(dep commons-io/commons-io 'commons-io-2*.jar')"

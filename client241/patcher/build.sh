#!/bin/bash
# Build the patcher: plain javac + ASM 9.7.1 (hash-checked). Output: patcher/build/gadscape-patcher.jar
# plus patcher/build/deps/*.jar. Run it with:  java -cp "patcher/build/gadscape-patcher.jar:patcher/build/deps/*" gadscape.patcher.Patcher
#
# The ASM jars are taken from, in order: patcher/build/deps (an earlier build), $ASM_DIR (a folder with
# asm-9.7.1.jar and asm-tree-9.7.1.jar), the local Maven repository (~/.m2), the Gradle cache (~/.gradle), and
# only then downloaded from Maven Central. Every copy is checked against the sha256 below.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/build"
deps="$out/deps"
mkdir -p "$deps" "$out/classes"
sha256() { if command -v sha256sum > /dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
fetch() { # group/artifact/version/file.jar sha256
  local path="$1" sha="$2" name f
  name="$(basename "$path")"
  f="$deps/$name"
  if [ -f "$f" ] && [ "$(sha256 "$f")" != "$sha" ]; then rm -f "$f"; fi
  if [ ! -f "$f" ]; then
    local group artifact version c
    version="$(basename "$(dirname "$path")")"
    artifact="$(basename "$(dirname "$(dirname "$path")")")"
    group="$(dirname "$(dirname "$(dirname "$path")")")"
    for c in ${ASM_DIR:+"$ASM_DIR/$name"} "$HOME/.m2/repository/$path" \
        "$HOME"/.gradle/caches/modules-2/files-2.1/"${group//\//.}"/"$artifact"/"$version"/*/"$name"; do
      if [ -f "$c" ] && [ "$(sha256 "$c")" = "$sha" ]; then cp "$c" "$f"; echo "using $c"; break; fi
    done
  fi
  if [ ! -f "$f" ]; then
    curl -fsSL --retry 5 --retry-delay 3 -o "$f.part" "https://repo1.maven.org/maven2/$path"
    mv "$f.part" "$f"
  fi
  [ "$(sha256 "$f")" = "$sha" ] || { echo "bad hash for $f" >&2; rm -f "$f"; exit 1; }
}
fetch org/ow2/asm/asm/9.7.1/asm-9.7.1.jar 8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281
fetch org/ow2/asm/asm-tree/9.7.1/asm-tree-9.7.1.jar 9929881f59eb6b840e86d54570c77b59ce721d104e6dfd7a40978991c2d3b41f
rm -rf "$out/classes" "$out/hook"; mkdir -p "$out/classes/gadscape/patcher" "$out/hook"
javac --release 11 -encoding UTF-8 -nowarn -cp "$deps/asm-9.7.1.jar:$deps/asm-tree-9.7.1.jar" -d "$out/classes" $(find "$here/src" -name '*.java')
# the paint hook that 'brand' adds to RuneLite's client jar (a plain class: only java.desktop); kept in the patcher
# jar as a resource (.class.bin) so it is never on the patcher's own classpath as a class
javac --release 11 -encoding UTF-8 -nowarn -d "$out/hook" "$here/hook/net/runelite/client/callback/GadScapePaintHook.java"
cp "$out/hook/net/runelite/client/callback/GadScapePaintHook.class" "$out/classes/gadscape/patcher/GadScapePaintHook.class.bin"
jar --create --file "$out/gadscape-patcher.jar" --main-class gadscape.patcher.Patcher -C "$out/classes" .
echo "built $out/gadscape-patcher.jar"

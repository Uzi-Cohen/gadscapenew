#!/bin/bash
# Build the patcher: plain javac + ASM 9.7.1 from Maven Central (hash-checked). Output: patcher/build/gadscape-patcher.jar
# plus patcher/build/deps/*.jar. Run it with:  java -cp "patcher/build/gadscape-patcher.jar:patcher/build/deps/*" gadscape.patcher.Patcher
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/build"
deps="$out/deps"
mkdir -p "$deps" "$out/classes"
fetch() { # group/artifact/version sha256
  local path="$1" sha="$2" f="$deps/$(basename "$1")"
  if [ ! -f "$f" ]; then
    curl -fsSL --retry 5 --retry-delay 3 -o "$f.part" "https://repo1.maven.org/maven2/$path"
    mv "$f.part" "$f"
  fi
  echo "$sha  $f" | sha256sum -c --quiet - || { echo "bad hash for $f" >&2; rm -f "$f"; exit 1; }
}
fetch org/ow2/asm/asm/9.7.1/asm-9.7.1.jar 8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281
fetch org/ow2/asm/asm-tree/9.7.1/asm-tree-9.7.1.jar 9929881f59eb6b840e86d54570c77b59ce721d104e6dfd7a40978991c2d3b41f
rm -rf "$out/classes"; mkdir -p "$out/classes"
javac --release 11 -nowarn -cp "$deps/asm-9.7.1.jar:$deps/asm-tree-9.7.1.jar" -d "$out/classes" $(find "$here/src" -name '*.java')
jar --create --file "$out/gadscape-patcher.jar" --main-class gadscape.patcher.Patcher -C "$out/classes" .
echo "built $out/gadscape-patcher.jar"

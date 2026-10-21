#!/bin/bash
# GadScape build 241 TEST client for Linux (macOS untested). Needs Java 17 or newer: "java" on PATH, or JAVA_HOME.
# Server host: first line of server.txt next to this file (default 127.0.0.1), or ./run.sh --host <host>.
# RuneLite settings and the game cache go to ~/.gadscape-241-test/home (or --home <dir>), never to ~/.runelite
# or ~/jagexcache. RuneLite options go after "--", for example: ./run.sh -- --safe-mode --debug
cd "$(dirname "$0")"
java_bin=java
[ -n "${JAVA_HOME:-}" ] && java_bin="$JAVA_HOME/bin/java"
case "$(uname -s)" in
  Darwin) natives=lib-macos; jvm_args=jvm-args-mac.txt;;
  *) natives=lib-linux; jvm_args=jvm-args.txt;;
esac
# the test home is also Java's user.home from the start, so nothing Java caches at startup points at the real home
home="$HOME/.gadscape-241-test/home"
prev=""
for a in "$@"; do
  [ "$a" = "--" ] && break
  [ "$prev" = "--home" ] && home="$a"
  prev="$a"
done
mkdir -p "$home"
exec "$java_bin" "@$jvm_args" "-Duser.home=$home" -cp "lib/*:$natives/*" gadscape.launcher.Launcher \
  --host-file server.txt --home "$home" "$@"

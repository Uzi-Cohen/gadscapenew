#!/bin/bash
# GadScape build 241 TEST client for Linux (macOS untested). Needs Java 17 or newer: "java" on PATH, or JAVA_HOME.
# Server host: first line of server.txt next to this file (default 127.0.0.1), or ./run.sh --host <host>.
# RuneLite settings and the game cache go to ~/.gadscape-241-test/home, never to ~/.runelite or ~/jagexcache.
# RuneLite options go after "--", for example: ./run.sh -- --safe-mode --debug
cd "$(dirname "$0")"
java_bin=java
[ -n "${JAVA_HOME:-}" ] && java_bin="$JAVA_HOME/bin/java"
case "$(uname -s)" in Darwin) natives=lib-macos;; *) natives=lib-linux;; esac
exec "$java_bin" @jvm-args.txt -cp "lib/*:$natives/*" gadscape.launcher.Launcher --host-file server.txt "$@"

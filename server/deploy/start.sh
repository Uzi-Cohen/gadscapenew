#!/usr/bin/env bash
# Launches the GadScape server. Expected to run with its working directory set to
# /opt/gadscape (systemd sets this via WorkingDirectory=; the paths below - worlds.yml,
# data/, cache/, app/logback-*.xml - are all resolved relative to CWD by the server).
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p logs

# World profile key from worlds.yml - "main" for production, "beta" for the beta world.
WORLD="${1:-main}"

exec java \
  -Xmx6g \
  -XX:+UseZGC \
  -XX:-OmitStackTraceInFastThrow \
  -Dio.netty.tryReflectionSetAccessible=true \
  -Dio.netty.handler.ssl.openssl.useTasks=true \
  --add-opens=java.base/java.time=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/java.security=ALL-UNNAMED \
  --add-opens=java.base/sun.security.action=ALL-UNNAMED \
  --add-opens=jdk.naming.rmi/com.sun.jndi.rmi.registry=ALL-UNNAMED \
  --add-opens=java.base/sun.net=ALL-UNNAMED \
  -jar server.jar "$WORLD" >> logs/server.log 2>&1

#!/usr/bin/env bash
# Launches the GadScape server. Expected to run with its working directory set to
# /opt/gadscape (systemd sets this via WorkingDirectory=; the paths below - worlds.yml,
# data/, cache/, app/logback-*.xml - are all resolved relative to CWD by the server).
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p logs

# World profile key from worlds.yml - "main" for production, "beta" for the beta world.
WORLD="${1:-main}"

# ---- Rev 241 (RuneLite 1.13.3 client, rsprot networking) ------------------------------------------------
# Optional overrides live in start.env next to this script (sourced if present), e.g.
#   GADSCAPE_CACHE_DIR=/opt/gadscape/cache241
#   GADSCAPE_RSA_PRIVATE=/opt/gadscape/keys/private.key
#   GADSCAPE_LOGIN_TOKEN=<the launcher's --session value>
#   GADSCAPE_EXTRA_PORTS=443
#   GADSCAPE_MAX_CONNECTIONS_PER_IP=10
if [ -f ./start.env ]; then
  # shellcheck disable=SC1091
  . ./start.env
fi

# The 241 cache the server serves over JS5 and reads its definitions from. Without it the server would open the
# rev-211 cache in cache/data/cache, which a 241 client cannot use, so refuse to start.
CACHE_DIR="${GADSCAPE_CACHE_DIR:-/opt/gadscape/cache241}"
if [ ! -f "$CACHE_DIR/main_file_cache.dat2" ]; then
  echo "start.sh: no rev 241 cache at $CACHE_DIR (set GADSCAPE_CACHE_DIR in start.env)" >&2
  exit 1
fi

GADSCAPE_OPTS=(
  "-Dgadscape.cache.dir=$CACHE_DIR"
  # rsprot validates every packet in development mode; it must be off in production (the server also turns it
  # off by itself on non-DEVELOPER worlds, this makes it explicit).
  "-Dnet.rsprot.protocol.internal.development=false"
)
# Login RSA key; without it the server uses rsaPrivateExponent/rsaModulus from worlds.yml.
RSA_KEY="${GADSCAPE_RSA_PRIVATE:-/opt/gadscape/keys/private.key}"
if [ -f "$RSA_KEY" ]; then
  GADSCAPE_OPTS+=("-Dgadscape.rsa.private=$RSA_KEY")
fi
# The rev-211 client-token check (launcher jav_config param 9); empty = not checked (stock RuneLite sends nothing).
if [ -n "${GADSCAPE_LOGIN_TOKEN:-}" ]; then
  GADSCAPE_OPTS+=("-Dgadscape.login.token=$GADSCAPE_LOGIN_TOKEN")
fi
# Extra listening ports: the 211 server also listened on 443 (the client retries there when 43594 is blocked).
# Unset = 443 like before; set GADSCAPE_EXTRA_PORTS= (empty) in start.env to listen on 43594 only.
GADSCAPE_EXTRA_PORTS="${GADSCAPE_EXTRA_PORTS-443}"
if [ -n "$GADSCAPE_EXTRA_PORTS" ]; then
  GADSCAPE_OPTS+=("-Dgadscape.net.ports=$GADSCAPE_EXTRA_PORTS")
fi
if [ -n "${GADSCAPE_MAX_CONNECTIONS_PER_IP:-}" ]; then
  GADSCAPE_OPTS+=("-Dgadscape.net.maxConnectionsPerIp=$GADSCAPE_MAX_CONNECTIONS_PER_IP")
fi

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
  "${GADSCAPE_OPTS[@]}" \
  -jar server.jar "$WORLD" >> logs/server.log 2>&1

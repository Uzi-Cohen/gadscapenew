#!/usr/bin/env bash
# Runs a main class from $GADCONV_CLASSES (compile first with dev/compile.sh).
#
#   dev/run.sh <MainClass> [args...]
#
# MainClass may be short ("cli.GadConv", "convert.SelfCheck") or fully qualified. JVM options come from
# GADCONV_JAVA_OPTS (default -Xmx3g; the build box is shared). -Dgadconv.root is set so input paths default to the
# standard layout.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
main=${1:?usage: dev/run.sh <MainClass> [args...]}; shift
[[ "$main" == com.* ]] || main=com.gadscape.cacheconv.$main
exec java ${GADCONV_JAVA_OPTS:--Xmx3g} -XX:-OmitStackTraceInFastThrow -Dgadconv.root="$GADCONV_ROOT" \
  -cp "$GADCONV_CLASSES:$GADCONV_JAR" "$main" "$@"

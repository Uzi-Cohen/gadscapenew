# Sourced by compile.sh / run.sh. Resolves:
#   GADCONV_ROOT     scratch root holding rev241/run/server241.jar, audit/, c211/, osrs241/ (searched upwards from
#                    this module if unset)
#   GADCONV_JAR      classpath jar with mgi.tools.jagcached + RuneLite cache (default $GADCONV_ROOT/rev241/run/server241.jar)
#   GADCONV_CLASSES  javac output directory (default $GADCONV_ROOT/gadconv-work/classes; set your own to avoid
#                    sharing one directory with other people compiling at the same time)
MODULE_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SRC_DIR=$MODULE_DIR/src/main/java/com/gadscape/cacheconv
RES_DIR=$MODULE_DIR/src/main/resources
if [[ -z "${GADCONV_ROOT:-}" ]]; then
  d=$MODULE_DIR
  while [[ "$d" != "/" ]]; do
    if [[ -f "$d/rev241/run/server241.jar" ]]; then GADCONV_ROOT=$d; break; fi
    d=$(dirname "$d")
  done
fi
if [[ -z "${GADCONV_ROOT:-}" ]]; then echo "set GADCONV_ROOT (directory containing rev241/run/server241.jar)" >&2; exit 2; fi
GADCONV_JAR=${GADCONV_JAR:-$GADCONV_ROOT/rev241/run/server241.jar}
GADCONV_CLASSES=${GADCONV_CLASSES:-$GADCONV_ROOT/gadconv-work/classes}
export GADCONV_ROOT GADCONV_JAR GADCONV_CLASSES

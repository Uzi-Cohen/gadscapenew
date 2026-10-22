#!/bin/bash
# GadScape offline season / PvP reset. Never runs automatically: stop the game server, then run this by hand.
# Without --apply it is a dry run that only lists what would change. With --apply the whole data directory is first
# archived to <data>/../reset-archives/data-<time>/ (logs excluded).
#
#   ./season-reset.sh <server data dir> pvp|economy|full [--apply] [--season "Season 2"] [--port 43594]
#
#   pvp      PvP stats, BH points/emblems, blood money, loot keys and the anti-farm log are reset; accounts stay.
#   economy  pvp + every item container, GE offers/history, middleman, ToB chest and the well are emptied.
#   full     all player data is deleted (characters, hiscores, hardware ids, GE offers, clans, GIM, records...).
# Website accounts, donations and votes are in the website database and must be reset there.
# Details: core/src/main/java/com/gadscape/tools/reset/SeasonReset.java
set -e
cd "$(dirname "$0")"
if [ $# -lt 2 ]; then
  sed -n '2,13p' "$0"; exit 2
fi
DATA="$1"; MODE="$2"; shift 2
JAR="${JAR:-app/build/libs/shadow.jar}"
if [ ! -f "$JAR" ]; then echo "Build the server first (./gradlew :app:shadowJar) or set JAR=<server jar>."; exit 1; fi
exec java -cp "$JAR" com.gadscape.tools.reset.SeasonReset --data "$DATA" --mode "$MODE" "$@"

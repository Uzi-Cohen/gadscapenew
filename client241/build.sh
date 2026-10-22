#!/bin/bash
# Build the GadScape client (RuneLite 1.13.3 for Old School build 241, branded and patched for GadScape).
#
#   client241/build.sh [--test] [--runelite-jars DIR] [--modulus FILE] [--default-host HOST] [--out DIR] [--jre-zip FILE]
#   client241/build.sh --setup --setup-url URL --setup-key FILE [--setup-version N] [--test] [--out DIR]
#
#   (default)        release build: "GadScape" everywhere, GadScape's live login key (release-rsa-public-modulus.hex,
#                    the key of the 211 client), default server gadscape.duckdns.org, home %LOCALAPPDATA%\GadScape
#                    or ~/.gadscape-client
#   --test           test build: "GadScape (test)" window, world "GadScape 241 Test", TEST key
#                    (test-rsa-public-modulus.hex), default server 127.0.0.1, home %LOCALAPPDATA%\GadScape241Test or
#                    ~/.gadscape-241-test, TEST icon in the exe. Release and test can be installed side by side.
#   --runelite-jars  where the RuneLite 1.13.3 jars are kept (downloaded if missing, always hash-checked)
#                    default: client241/build/runelite-1.13.3
#   --modulus        public RSA modulus (hex) to put in the client (overrides the channel's key file)
#   --default-host   server address written into server.txt and used by the exe when its server file is missing
#   --out            output folder. default: client241/build/out
#   --jre-zip        a Windows x64 Java 17+ JRE zip (one top folder). With it, the Windows exe is built too.
#
#   --setup          build only the setup instead (GadScape-Setup.exe: downloads, installs, updates and starts the
#                    client; see setup/ and README "Setup and updates") and the release tool gadscape-release:
#   --setup-url      https address of the release's manifest.json (where you upload the release folder)
#   --setup-key      the release public key (.pub from `gadscape-release keygen`, or its base64 text)
#                    Without --setup-url/--setup-key the setup is built but refuses to install (fail closed).
#   --setup-version  the setup's version (default setup/VERSION); raise it for every new setup you publish
#   --setup-local-http  TEST builds only: the setup also accepts http://127.0.0.1 (a local test server)
#                    The setup takes the channel's names and default server from the client build in --out
#                    (channel-<ch>.properties), so build the client first; --default-host must match it if given.
#
# Output in --out (release names; the test build uses GadScape-241-Test instead of GadScape):
#   injected-client-1.13.3-gadscape.jar   the patched game client
#   client-1.13.3-gadscape.jar            RuneLite's client jar with GadScape's name, icons, accent colour, login hook
#   GadScape/                             portable folder: run.sh / run.bat, lib*/ jars, server.txt
#   GadScape.zip                          the same folder zipped
#   GadScape.exe                          (with --jre-zip) one-file Windows launcher with Java inside
#   GadScape-Setup.exe, gadscape-release  (with --setup) the setup and the release tool
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
rl="$here/build/runelite-1.13.3"
modulus=""
out="$here/build/out"
jre=""
channel=release
host=""; host_given=""
setup=""; setup_url=""; setup_key=""; setup_version=""; setup_localhttp=""
while [ $# -gt 0 ]; do
  case "$1" in
    --test) channel=test; shift;;
    --setup) setup=yes; shift;;
    --setup-url) setup_url="$2"; shift 2;;
    --setup-key) setup_key="$2"; shift 2;;
    --setup-version) setup_version="$2"; shift 2;;
    --setup-local-http) setup_localhttp=--local-http; shift;;
    --runelite-jars) rl="$2"; shift 2;;
    --modulus) modulus="$2"; shift 2;;
    --default-host) host="$2"; host_given=yes; shift 2;;
    --out) out="$2"; shift 2;;
    --jre-zip) jre="$2"; shift 2;;
    *) echo "unknown option $1" >&2; exit 2;;
  esac
done
if [ "$channel" = release ]; then
  name=GadScape; title=GadScape; activity=GadScape; winDir=GadScape; unixDir=.gadscape-client
  serverFile=GadScape-server.txt
  modulus="${modulus:-$here/release-rsa-public-modulus.hex}"; host="${host:-gadscape.duckdns.org}"
else
  name=GadScape-241-Test; title="GadScape (test)"; activity="GadScape 241 Test"; winDir=GadScape241Test
  unixDir=.gadscape-241-test; serverFile=GadScape-241-Test-server.txt
  modulus="${modulus:-$here/test-rsa-public-modulus.hex}"; host="${host:-127.0.0.1}"
fi
lock="$here/runelite-1.13.3.lock"
mkdir -p "$out"
chprops="$out/channel-$channel.properties"
write_channel_props() {
  {
    echo "# written by client241/build.sh"
    echo "channel=$channel"
    echo "title=$title"
    echo "activity=$activity"
    echo "default.host=$host"
    echo "home.windows=$winDir"
    echo "home.unix=$unixDir"
    echo "exe.server_file=$serverFile"
  } > "$chprops"
}

if [ -n "$setup" ]; then
  # the setup only needs the channel's names: no RuneLite jars, Java or patcher. A client already built into
  # --out keeps its channel file: gadscape-release build reads the release's server from it.
  if [ -f "$chprops" ]; then
    built_host="$(sed -n 's/^default.host=//p' "$chprops" | head -1)"
    if [ -n "$host_given" ] && [ "$built_host" != "$host" ]; then
      echo "the $channel client in $out was built with --default-host $built_host, not $host: build the client and the" \
        "setup with the same --default-host (or use another --out)" >&2
      exit 2
    fi
    echo "== using $chprops from the client build (default server ${built_host:-<none>})"
  else
    write_channel_props
  fi
  echo "== $channel setup: '$title', %LOCALAPPDATA%\\$winDir, manifest ${setup_url:-<none>}"
  "$here/setup/build.sh" --out "$out/$name-Setup.exe" --channel-props "$chprops" --url "$setup_url" \
    --key "$setup_key" ${setup_version:+--version "$setup_version"} $setup_localhttp --tool-out "$out/gadscape-release"
  echo "done: $out ($channel setup)"
  exit 0
fi
echo "== $channel build: '$title', key $(basename "$modulus"), default server $host"

echo "== 1/6 RuneLite 1.13.3 jars"
"$here/tools/fetch-runelite.sh" "$lock" "$rl"
stock_sha=$(awk '$4 ~ /^injected-client-/ {print $1}' "$lock")
client_sha=$(awk '$4 ~ /^client-1/ {print $1}' "$lock")
client_jar=$(awk '$4 ~ /^client-1/ {print $4}' "$lock")

echo "== 2/6 patcher: game client (RSA key, server check, friends chat size) and RuneLite branding"
"$here/patcher/build.sh"
pcp="$here/patcher/build/gadscape-patcher.jar:$here/patcher/build/deps/*"
patched="$out/injected-client-1.13.3-gadscape.jar"
branded="$out/${client_jar%.jar}-gadscape.jar"
rm -f "$patched" "$branded"
java -Djava.awt.headless=true -cp "$pcp" gadscape.patcher.Patcher patch --in "$rl/injected-client-1.13.3.jar" --out "$patched" \
  --modulus "@$modulus" --expect-sha256 "$stock_sha"
java -Djava.awt.headless=true -cp "$pcp" gadscape.patcher.Patcher brand --in "$rl/$client_jar" --out "$branded" \
  --title GadScape --icon "$here/branding/shield-256.png" --splash "$here/branding/splash-200.png" --expect-sha256 "$client_sha"

echo "== 3/6 GadScape plugins (login screen)"
"$here/plugins/build.sh" "$rl" "$branded"

echo "== 4/6 launcher"
write_channel_props
"$here/launcher/build.sh" "$rl" "$chprops"

echo "== 5/6 portable folder"
dist="$out/$name"
rm -rf "$dist" "$out/$name.zip"
mkdir -p "$dist/lib" "$dist/lib-win" "$dist/lib-linux" "$dist/lib-macos"
cp "$here/launcher/build/gadscape-launcher.jar" "$here/plugins/build/gadscape-plugins.jar" "$patched" "$branded" "$dist/lib/"
while read -r sha size plats jar url; do
  case "$sha" in ''|'#'*|version|jvm|jvm-mac) continue;; esac
  case "$jar" in injected-client-*|"$client_jar") continue;; esac   # the stock game client and RuneLite client never go in
  # one jar can be listed for several platforms ("linux,linux-aarch64"); LWJGL keeps each os/arch in its own
  # folder inside the jar, so x64 and arm64 natives can share lib-linux / lib-macos
  for plat in ${plats//,/ }; do
    case "$plat" in
      all) d=lib;;
      win) d=lib-win;;
      win-x86|win-aarch64) continue;;   # Windows: x64 only (the exe ships an x64 Java)
      linux|linux-aarch64) d=lib-linux;;
      macos|macos-aarch64) d=lib-macos;;
      *) echo "unknown platform '$plat' for $jar in $lock: add it to build.sh" >&2; exit 1;;
    esac
    cp "$rl/$jar" "$dist/$d/"
  done
done < "$lock"
# JVM options RuneLite's own launcher uses for this version (java @jvm-args.txt reads them); macOS has its own list
awk '$1 == "jvm" {sub(/^jvm /, ""); print}' "$lock" > "$dist/jvm-args.txt"
awk '$1 == "jvm-mac" {sub(/^jvm-mac /, ""); print}' "$lock" > "$dist/jvm-args-mac.txt"
[ -s "$dist/jvm-args-mac.txt" ] || cp "$dist/jvm-args.txt" "$dist/jvm-args-mac.txt"
printf '%s\n' "$host" > "$dist/server.txt"
rule="$(printf '%s' "$title" | sed 's/./=/g')"
for f in run.sh run.bat README.txt; do
  sed -e "s|@TITLE@|$title|g" -e "s|@TITLE_RULE@|$rule|g" -e "s|@UNIX_DIR@|$unixDir|g" -e "s|@WIN_DIR@|$winDir|g" \
    -e "s|@DEFAULT_HOST@|$host|g" "$here/dist/$f" > "$dist/$f"
done
# license and copyright notices of RuneLite and the libraries it ships with (BSD, MIT, Apache and OFL need them)
cp -R "$here/dist/licenses" "$dist/licenses"
chmod +x "$dist/run.sh"
(cd "$out" && zip -qr "$name.zip" "$name")
echo "portable folder: $dist"

if [ -n "$jre" ]; then
  echo "== 6/6 Windows exe"
  "$here/exe/build.sh" "$jre" "$out/$name.zip" "$out/$name.exe" "$chprops"
else
  echo "== 6/6 Windows exe skipped (no --jre-zip)"
fi
echo "done: $out ($channel)"

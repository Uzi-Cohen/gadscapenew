#!/bin/bash
# Build GadScape-Setup.exe (the small download that installs, updates and starts the client) and the release tool.
# Normally run through client241/build.sh --setup, which passes the channel's names.
#
#   setup/build.sh --out FILE.exe [--channel-props FILE] [--url URL] [--key FILE|BASE64] [--version N]
#                  [--local-http] [--tool-out FILE]
#
#   --channel-props  channel-<ch>.properties written by client241/build.sh (title, home.windows, exe.server_file,
#                    default.host, channel). Without it: release names (GadScape, %LOCALAPPDATA%\GadScape).
#   --url            address of the release's manifest.json (https). Empty: the setup refuses to install (unless
#                    a setup.txt next to it gives an address).
#   --key            the release public key: the .pub file from `gadscape-release keygen`, or its base64 text.
#                    Empty: the setup refuses to install anything (fail closed).
#   --version        this setup's version (default: setup/VERSION). Raise it for every setup you publish:
#                    installed launchers replace themselves with a newer one listed in the release.
#   --local-http     TEST builds only: also accept http://127.0.0.1 addresses (a local test server).
#   --tool-out       also build the release tool (gadscape-release, for this machine) there.
#
# Needs Go 1.21+. Builds in a temporary copy (with client241/exe/kit, the code it shares with the one-file exe), so
# nothing is written into the source folders.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
exe_dir="$here/../exe"
out=""; props=""; url=""; key=""; version=""; localhttp=""; toolout=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out) out="$2"; shift 2;;
    --channel-props) props="$2"; shift 2;;
    --url) url="$2"; shift 2;;
    --key) key="$2"; shift 2;;
    --version) version="$2"; shift 2;;
    --local-http) localhttp=yes; shift;;
    --tool-out) toolout="$2"; shift 2;;
    *) echo "unknown option $1" >&2; exit 2;;
  esac
done
[ -n "$out" ] || { echo "setup/build.sh: --out FILE.exe is needed" >&2; exit 2; }
GO="${GO:-$(command -v go || echo /usr/local/go/bin/go)}"
prop() { [ -n "$props" ] && sed -n "s/^$1=//p" "$props" | head -1 || true; }
ch="$(prop channel)"; ch="${ch:-release}"
title="$(prop title)"; title="${title:-GadScape}"
dir="$(prop home.windows)"; dir="${dir:-GadScape}"
sfile="$(prop exe.server_file)"; sfile="${sfile:-GadScape-server.txt}"
host="$(prop default.host)"; host="${host:-gadscape.duckdns.org}"
version="${version:-$(tr -d ' \r\n' < "$here/VERSION")}"
case "$version" in ''|*[!0-9]*) echo "bad --version '$version'" >&2; exit 2;; esac
# the key: a file with a public-key= line, or the base64 text itself
if [ -n "$key" ] && [ -f "$key" ]; then
  if grep -q '^private-key=' "$key"; then echo "$key is a PRIVATE key; give the .pub file" >&2; exit 2; fi
  key="$(sed -n 's/^public-key=//p' "$key" | head -1 | tr -d ' \r')"
fi
if [ -n "$key" ] && ! printf '%s' "$key" | grep -Eq '^[A-Za-z0-9+/]{43}=$'; then
  echo "--key is not an ed25519 public key (44 base64 characters)" >&2; exit 2
fi
case "$url" in ''|https://*) ;; http://127.0.0.1[:/]*) [ -n "$localhttp" ] || { echo "--url is http: only allowed with --local-http (test builds)" >&2; exit 2; };; *) echo "--url must be https" >&2; exit 2;; esac
splash="$exe_dir/splash.png"; icon="$exe_dir/icon.ico"
if [ "$ch" = test ]; then splash="$exe_dir/test-res/splash.png"; icon="$exe_dir/test-res/icon.ico"; fi
exename="$(basename "$out")"
stamp="GSSTAMP1 version=$version channel=$ch app=$dir key=${key:-none} GSEND"

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$work/exe" "$work/setup"
cp "$exe_dir/go.mod" "$work/exe/"
cp -R "$exe_dir/kit" "$work/exe/kit"
rm -f "$work"/exe/kit/*_test.go
cp "$here"/*.go "$here/go.mod" "$here/app.manifest" "$work/setup/"
cp -R "$here/internal" "$here/tools" "$work/setup/"
cp "$splash" "$work/setup/splash.png"
case "$out" in /*) ;; *) out="$PWD/$out";; esac
mkdir -p "$(dirname "$out")"
(cd "$work/setup" && "$GO" run ./tools syso --ico "$icon" --manifest app.manifest --version "$version" \
  --title "$title" --exe "$exename" --out rsrc_windows_amd64.syso > /dev/null)
(cd "$work/setup" && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 "$GO" build -trimpath \
  -ldflags "-s -w -H windowsgui -X 'main.appName=$title' -X 'main.dirName=$dir' -X 'main.serverFile=$sfile' \
-X 'main.defaultHost=$host' -X 'main.channel=$ch' -X 'main.manifestURL=$url' -X 'main.publicKey=$key' \
-X 'main.setupVersion=$version' -X 'main.allowLocalHTTP=$localhttp' -X 'main.stamp=$stamp'" -o "$out" .)
if [ -n "$toolout" ]; then
  case "$toolout" in /*) ;; *) toolout="$PWD/$toolout";; esac
  (cd "$work/setup" && CGO_ENABLED=0 "$GO" build -trimpath -o "$toolout" ./tools)
  echo "built $toolout (release tool)"
fi
echo "built $out ($ch setup $version, '$title', %LOCALAPPDATA%\\$dir, default server $host)"
if [ -z "$key" ]; then
  echo "WARNING: no --key: this setup refuses to install anything (fail closed). Make a key with gadscape-release keygen." >&2
fi
if [ -z "$url" ]; then
  echo "WARNING: no --url: this setup only works with a setup.txt next to it that gives the manifest address." >&2
fi
[ -n "$localhttp" ] && echo "TEST build: accepts http://127.0.0.1 addresses" >&2
exit 0

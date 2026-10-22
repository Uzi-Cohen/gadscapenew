#!/bin/bash
# End-to-end test of GadScape-Setup.exe under Wine + Xvfb, against a local release server. TEST channel only:
# test key, test client, http://127.0.0.1. Everything it writes goes under --work.
#
#   setup/e2e/wine-e2e.sh --work DIR --dist DIR --jre-zip FILE --cache-dir DIR [--display :95] [--port 18741]
#                         [--wine-java-security FILE] [--skip-java]
#
#   --dist     a client241/build.sh --test output folder (GadScape-241-Test.zip, channel-test.properties)
#   --wine-java-security  a java.security override with SunMSCAPI first (Java under Wine crashes seeding
#              SecureRandom without it; Wine-only, never shipped). Without it the client start is expected to crash.
#   An Xvfb must already run on --display. Needs wine (64-bit), xdotool, ImageMagick's import, curl, sha256sum.
#
# Checks: first install (with Cancel during the cache download, then a resumed download; an unrelated setup.txt
# next to the setup is ignored), installed files, cache bytes (the release's repacked cache, all groups kept by the
# 241 client's check), shortcuts, uninstall entry, server file; second run downloads nothing; a changed client is
# the only download; a tampered manifest is fetched again once, then refused (installed and fresh); the upload
# window (new manifest.sig, old manifest.json) and an older release offered again start the installed client
# quietly; the launcher replaces itself with a newer setup; offline start, with an interrupted update put back;
# no update while the client runs; the one-file exe's install is noticed and repaired; Java start (best effort);
# uninstall keeping / removing the home folder; a setup without a key refuses to install; a release-channel setup
# ignores GadScape-setup.txt; Cancel ends the wait for another setup, and the wait ends when that one does.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
c241="$(cd "$here/../.." && pwd)"
work=""; dist=""; jre=""; cache=""; disp=":95"; port=18741; winesec=""; skipjava=""
while [ $# -gt 0 ]; do
  case "$1" in
    --work) work="$2"; shift 2;;
    --dist) dist="$2"; shift 2;;
    --jre-zip) jre="$2"; shift 2;;
    --cache-dir) cache="$2"; shift 2;;
    --display) disp="$2"; shift 2;;
    --port) port="$2"; shift 2;;
    --wine-java-security) winesec="$2"; shift 2;;
    --skip-java) skipjava=yes; shift;;
    *) echo "unknown option $1" >&2; exit 2;;
  esac
done
for v in work dist jre cache; do [ -n "${!v}" ] || { echo "--$v is needed" >&2; exit 2; }; done
mkdir -p "$work"/{keys,logs,shots,bin,serve}
chmod 700 "$work/keys"
work="$(cd "$work" && pwd)"
export WINEPREFIX="$work/wineprefix" DISPLAY="$disp" WINEDEBUG=-all
export WINEDLLOVERRIDES="winemenubuilder.exe=d;mscoree=;mshtml="   # no Linux menu entries outside --work
GO="${GO:-$(command -v go || echo /usr/local/go/bin/go)}"
log="$work/logs/e2e.log"; : > "$log"
pass=0; fail=0
say() { echo "$*" | tee -a "$log"; }
ok() { pass=$((pass+1)); say "PASS  $*"; }
bad() { fail=$((fail+1)); say "FAIL  $*"; }
check() { local name="$1"; shift; if "$@" >> "$log" 2>&1; then ok "$name"; else bad "$name"; fi; }
shot() { import -display "$disp" -window root "$work/shots/$1.png" 2>/dev/null; }
# shot_when PATTERN NAME: in the background, screenshot once setup.log (emptied first) shows PATTERN
shot_when() {
  : > "$dest/setup.log" 2>/dev/null
  ( for i in $(seq 1 100); do grep -q "$1" "$dest/setup.log" 2>/dev/null && { sleep 0.4; shot "$2"; exit; }; sleep 0.2; done ) &
}

U="$WINEPREFIX/drive_c/users/$(whoami)"
dest="$U/AppData/Local/GadScape241Test"
wdest='C:\users\'"$(whoami)"'\AppData\Local\GadScape241Test'
live="$dest/home/.runelite/jagexcache/oldschool/LIVE"
desk="$U/Desktop/GadScape (test).lnk"
menu="$U/AppData/Roaming/Microsoft/Windows/Start Menu/Programs/GadScape (test).lnk"
regkey='HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\GadScape241Test'
srvlog="$work/logs/serve.log"
url="http://127.0.0.1:$port/test/manifest.json"
rel="$work/serve/test"

# ---- build: release tool, test key, setups v1/v2, keyless release-channel setup, one-file test exe
say "== build ($(date -u +%H:%M:%S))"
(cd "$c241/setup" && "$GO" build -o "$work/bin/gadscape-release" ./tools) || exit 1
tool="$work/bin/gadscape-release"
(cd "$c241/setup" && GOOS=windows GOARCH=amd64 "$GO" build -o "$work/bin/holdlock.exe" ./e2e/holdlock) || exit 1
[ -f "$work/keys/test-release.key" ] || "$tool" keygen --out "$work/keys" --name test-release >> "$log"
for v in 1 2; do
  mkdir -p "$work/setup-v$v"
  "$c241/setup/build.sh" --out "$work/setup-v$v/GadScape-241-Test-Setup.exe" --channel-props "$dist/channel-test.properties" \
    --url "$url" --key "$work/keys/test-release.pub" --version "$v" --local-http >> "$log" 2>&1 || { say "setup v$v build failed"; exit 1; }
done
"$c241/setup/build.sh" --out "$work/setup-nokey/GadScape-Setup.exe" >> "$log" 2>&1 || exit 1
"$c241/exe/build.sh" "$jre" "$dist/GadScape-241-Test.zip" "$work/one-file/GadScape-241-Test.exe" "$dist/channel-test.properties" >> "$log" 2>&1 \
  || { say "one-file exe build failed"; exit 1; }
"$tool" inspect --setup-exe "$work/setup-v1/GadScape-241-Test-Setup.exe" >> "$log"

# two client versions: the build's, and the same with one changed file
rm -rf "$work/dist2"; mkdir -p "$work/dist2"
cp "$dist/channel-test.properties" "$work/dist2/"
(cd "$work/dist2" && unzip -q "$dist/GadScape-241-Test.zip" && echo "e2e change" >> GadScape-241-Test/README.txt && zip -qr GadScape-241-Test.zip GadScape-241-Test)
release() { # dist setup-exe label
  "$tool" build --channel test --dist "$1" --jre-zip "$jre" --cache-dir "$cache" --setup-exe "$2" \
    --key "$work/keys/test-release.key" --out "$rel" --label "$3" >> "$log" 2>&1
}
rm -rf "$rel"
release "$dist" "$work/setup-v1/GadScape-241-Test-Setup.exe" "e2e 1" || { say "release build failed"; exit 1; }
check "release 1 verifies (cache: every group passes the 241 client's check)" "$tool" verify --dir "$rel" --pub "$work/keys/test-release.pub"
# cache_is_release DIR: DIR holds exactly the cache files of the release's cache zip, byte for byte
cache_is_release() {
  local z; z=$(ls "$rel"/files/cache-*.zip) || return 1
  local names; names=$(unzip -Z1 "$z") || return 1
  [ "$(echo "$names" | wc -l)" = "$(ls "$1"/main_file_cache.* | wc -l)" ] || return 1
  for f in $names; do unzip -p "$z" "$f" | cmp -s - "$1/$f" || { echo "$f differs"; return 1; }; done
}

# ---- server (15 MB/s so the window and Cancel can be seen)
: > "$srvlog"
"$tool" serve --dir "$work/serve" --addr "127.0.0.1:$port" --log "$srvlog" --rate 15000000 > /dev/null 2>&1 &
srvpid=$!
trap 'kill $srvpid 2>/dev/null; wineserver -k 2>/dev/null' EXIT
sleep 1
mark() { wc -l < "$srvlog"; }
since() { tail -n +$(($1 + 1)) "$srvlog"; }
files_since() { since "$1" | grep -o 'GET /test/files/[a-z]*-' | sed 's|GET /test/files/||; s/-$//' | tr '\n' ' '; }

# ---- clean Wine prefix state
[ -d "$WINEPREFIX" ] || wineboot -i >> "$log" 2>&1
wineserver -k 2>/dev/null; sleep 1
rm -rf "$dest" "$desk" "$menu"
wine reg delete "$regkey" /f > /dev/null 2>&1
mkdir -p "$U/Downloads"
cp "$work/setup-v1/GadScape-241-Test-Setup.exe" "$U/Downloads/"
# a setup.txt of some other program in Downloads: never an address for the setup
printf 'Thanks for downloading FooTool 2.1!\r\nRead the manual at:\r\nhttps://footool.example/manual\r\n' > "$U/Downloads/setup.txt"
dl='C:\users\'"$(whoami)"'\Downloads\GadScape-241-Test-Setup.exe'
run() { wine "$@"; local r=$?; wineserver -w; return $r; }

# ---- 1. first install: Cancel during the cache download, then again (resumes)
say "== 1. first install"
n=$(mark)
wine "$dl" --silent --no-start & wpid=$!
for i in $(seq 1 120); do grep -q "download cache" "$dest/setup.log" 2>/dev/null && break; sleep 0.5; done
sleep 3; shot setup-downloading
xdotool mousemove 790 433 click 1    # the Cancel button (420x140 window centred on 1280x800)
wait $wpid; r=$?
check "Cancel stops the setup (exit 2)" test "$r" = 2
check "partial cache download kept" bash -c "ls '$dest/downloads/'cache-*.zip.part"
check "nothing installed after Cancel" test ! -e "$dest/java/bin/javaw.exe"
n=$(mark)
shot_when "resuming at" setup-resumed
run "$dl" --silent --no-start; r=$?
check "second try installs (exit 0)" test "$r" = 0
got=$(files_since $n); resumed=$(since $n | grep -c 'files/cache-.*range="bytes=[1-9]')
check "only the cache is downloaded again ($got), resumed with a Range request" test "$got" = "cache " -a "$resumed" = 1
check "Java installed" test -f "$dest/java/bin/javaw.exe"
check "client installed" test -f "$dest/app/lib/gadscape-launcher.jar"
check "launcher installed" test -f "$dest/GadScape.exe"
check "an unrelated setup.txt next to the setup is ignored" bash -c "! grep -q 'manifest address from' '$dest/setup.log' && grep -q 'footool' '$U/Downloads/setup.txt'"
check "manifest requests carry a cache-busting query" bash -c "since() { tail -n +\$((\$1 + 1)) '$srvlog'; }; since $n | grep -q 'GET /test/manifest.json?r=' && since $n | grep -q 'GET /test/manifest.sig?r='"
check "cache seeded: the release's cache byte for byte" cache_is_release "$live"
check "seeded cache: every group passes the 241 client's check" "$tool" cachecheck --dir "$live" --quiet
check "cache marker written" grep -q '^version=' "$live/gadscape-cache-version.txt"
check "desktop shortcut" test -f "$desk"
check "start menu shortcut" test -f "$menu"
check "shortcut points at the launcher" bash -c "strings -el '$desk' | grep -q 'GadScape241Test.GadScape.exe'"
wine reg query "$regkey" > "$work/logs/reg.txt" 2>&1
check "uninstall entry: DisplayName" grep -q 'DisplayName.*GadScape (test)' "$work/logs/reg.txt"
check "uninstall entry: Publisher" grep -q 'Publisher.*GadScape' "$work/logs/reg.txt"
check "uninstall entry: UninstallString" grep -q 'UninstallString.*GadScape.exe" --uninstall' "$work/logs/reg.txt"
check "uninstall entry: DisplayIcon, DisplayVersion" bash -c "grep -q DisplayIcon '$work/logs/reg.txt' && grep -q 'DisplayVersion.*e2e 1' '$work/logs/reg.txt'"
check "server address file" grep -q '^127.0.0.1' "$dest/GadScape-241-Test-server.txt"
check "downloads cleaned up" bash -c "test -z \"\$(ls '$dest/downloads')\""

# ---- 2. second run: nothing downloaded
say "== 2. second run"
n=$(mark)
shot_when "client ready" launcher-checking
run "$wdest\\GadScape.exe" --silent --no-start; r=$?
check "second run exit 0" test "$r" = 0
check "second run: only manifest.json + manifest.sig" test "$(since $n | grep -c GET)" = 2

# ---- 3. a changed client: only the client is downloaded
say "== 3. changed client"
release "$work/dist2" "$work/setup-v1/GadScape-241-Test-Setup.exe" "e2e 2"
n=$(mark)
run "$wdest\\GadScape.exe" --silent --no-start; r=$?
check "update exit 0" test "$r" = 0
check "only the client zip downloaded" test "$(files_since $n)" = "client "
check "changed client file installed" grep -q "e2e change" "$dest/app/README.txt"

# ---- 4. tampered manifest
say "== 4. tampered manifest"
cp "$rel/manifest.json" "$work/manifest.good.json"
sed -i 's/"label": "e2e 2"/"label": "e2e 2 (tampered)"/' "$rel/manifest.json"
n=$(mark)
before=$(sha256sum "$dest/app/lib/"*.jar | sha256sum)
shot_when "notice:" tampered-refused
run "$wdest\\GadScape.exe" --silent --no-start
check "tampered manifest refused" grep -q "signature is not valid" "$dest/setup.log"
check "tampered manifest: fetched again once before refusing" bash -c "grep -q 'fetching both again' '$dest/setup.log' && test \"\$(tail -n +$((n + 1)) '$srvlog' | grep -c 'GET /test/manifest.json')\" = 2"
check "tampered manifest: no file downloaded" test -z "$(files_since $n)"
check "tampered manifest: installed client unchanged" test "$before" = "$(sha256sum "$dest/app/lib/"*.jar | sha256sum)"
cp "$work/manifest.good.json" "$rel/manifest.json"

# ---- 5. a newer setup in the release: the launcher replaces itself
say "== 5. launcher self-update"
cp "$rel/manifest.json" "$work/manifest.e2e2.json"     # the installed release
release "$work/dist2" "$work/setup-v2/GadScape-241-Test-Setup.exe" "e2e 3"
cp "$rel/manifest.json" "$work/manifest.e2e3.json"
# 5a. upload window: the new manifest.sig is online, manifest.json is still the old one
cp "$work/manifest.e2e2.json" "$rel/manifest.json"
n=$(mark)
run "$wdest\\GadScape.exe" --silent --no-start; r=$?
check "upload window (new manifest.sig, old manifest.json): exit 0, no signature failure" bash -c "test $r = 0 && ! grep -q 'release check failed' '$dest/setup.log'"
check "upload window: nothing downloaded" test -z "$(files_since $n)"
cp "$work/manifest.e2e3.json" "$rel/manifest.json"
n=$(mark)
run "$wdest\\GadScape.exe" --silent --no-start
check "launcher is now setup v2" test "$(sha256sum < "$dest/GadScape.exe")" = "$(sha256sum < "$work/setup-v2/GadScape-241-Test-Setup.exe")"
check "only the setup exe downloaded" test "$(files_since $n)" = "setup "
check "v2 ran after the update" grep -q 'setup 2 (test channel) starting.*--after-update=1' "$dest/setup.log"
check "old launcher log kept" grep -q 'setup 1 (test channel) starting' "$dest/setup.log"
check "old launcher removed" bash -c "sleep 3; ! ls '$dest'/GadScape.exe.old* 2>/dev/null"
# 5b. the previous release offered again (a cache on the way that has not seen e2e 3): the installed one starts
cp "$work/manifest.e2e2.json" "$rel/manifest.json"
n=$(mark)
run "$wdest\\GadScape.exe" --silent --no-start; r=$?
check "older release offered again: exit 0, started quietly (no notice)" bash -c "test $r = 0 && grep -q 'still offers the older release' '$dest/setup.log' && ! grep -q 'notice:' '$dest/setup.log'"
check "older release offered again: nothing downloaded" test -z "$(files_since $n)"
cp "$work/manifest.e2e3.json" "$rel/manifest.json"

# ---- 6. offline (setup.txt pointing at a server that is down)
say "== 6. offline"
printf '# e2e: a server that is down\r\nurl=http://127.0.0.1:1/test/manifest.json\r\n' > "$dest/GadScape-setup.txt"
# and an update that a power cut stopped between its two renames: app moved aside, the new one not in place
mkdir -p "$dest/.update" && mv "$dest/app" "$dest/.update/app.old"
shot_when "notice:" offline-notice
run "$wdest\\GadScape.exe" --silent --no-start; r=$?
check "offline: exit 0 (installed client would start)" test "$r" = 0
check "offline: address from GadScape-setup.txt (test build)" grep -q "manifest address from .*GadScape-setup.txt" "$dest/setup.log"
check "offline: notice shown" grep -q "Couldn't check for updates" "$dest/setup.log"
check "offline: the interrupted update is put back without a connection" bash -c "test -f '$dest/app/jvm-args.txt' && test ! -e '$dest/.update' && grep -q 'recovered .*app from an interrupted update' '$dest/setup.log'"
rm -f "$dest/GadScape-setup.txt"

# ---- 7. no update while the client runs (a stand-in program inside java\)
say "== 7. client running"
release "$dist" "$work/setup-v2/GadScape-241-Test-Setup.exe" "e2e 4"     # client back to the original: a change
cp "$WINEPREFIX/drive_c/windows/system32/notepad.exe" "$dest/java/bin/stand-in.exe"
wine "$wdest\\java\\bin\\stand-in.exe" & sleep 4
n=$(mark)
run_nowait() { wine "$@"; }
run_nowait "$wdest\\GadScape.exe" --silent --no-start
check "running client found" grep -q "client running: .*stand-in.exe" "$dest/setup.log"
check "no download while it runs" test -z "$(files_since $n)"
wineserver -k; sleep 2; rm -f "$dest/java/bin/stand-in.exe"
n=$(mark)
run "$wdest\\GadScape.exe" --silent --no-start
check "after closing it the update installs" test "$(files_since $n)" = "client "

# ---- 8. the one-file GadScape-241-Test.exe installs over it: the launcher notices and repairs
say "== 8. one-file exe compatibility"
mkdir -p "$U/Downloads/onefile"; cp "$work/one-file/GadScape-241-Test.exe" "$U/Downloads/onefile/"
( [ -n "$winesec" ] && export JAVA_TOOL_OPTIONS="-Djava.security.properties=Z:$(echo "$winesec" | tr / '\\')"
  timeout 90 wine 'C:\users\'"$(whoami)"'\Downloads\onefile\GadScape-241-Test.exe' > /dev/null 2>&1 )
sleep 2; shot one-file-exe; wineserver -k; sleep 2
check "one-file exe installed its own copy" bash -c "grep -q '^241-' '$dest/installed.txt'"
# (only the reference tables: the client the one-file exe started may have written groups)
check "one-file exe kept the setup's home and cache" bash -c "unzip -p $(ls "$rel"/files/cache-*.zip) main_file_cache.idx255 | cmp -s - '$live/main_file_cache.idx255'"
n=$(mark)
run "$wdest\\GadScape.exe" --silent --no-start
check "launcher re-installs Java and client after the one-file exe" test "$(files_since $n)" = "java client "
check "launcher marker back" grep -q '^setup-' "$dest/installed.txt"
cp "$dest/launcher.log" "$work/logs/one-file-launcher.log" 2>/dev/null

# ---- 9. start Java (best effort under Wine)
if [ -z "$skipjava" ]; then
  say "== 9. client start (best effort)"
  ( [ -n "$winesec" ] && export JAVA_TOOL_OPTIONS="-Djava.security.properties=Z:$(echo "$winesec" | tr / '\\')"
    timeout 120 wine "$wdest\\GadScape.exe" --silent > /dev/null 2>&1 ); r=$?
  sleep 15; shot client-started
  if grep -q "client window is up" "$dest/setup.log"; then say "INFO  client window came up: $(grep 'client window is up' "$dest/setup.log")"
  else say "INFO  client did not show a window (exit $r): $(grep -m1 FAIL "$dest/setup.log" | cut -c1-200)"; fi
  cp "$dest/client-output.log" "$work/logs/client-output.log" 2>/dev/null
  wineserver -k; sleep 2
fi

# ---- 10. uninstall keeping the home folder; fresh install refuses a tampered manifest; reinstall; full uninstall
say "== 10. uninstall"
run "$wdest\\GadScape.exe" --uninstall --silent --keep-home
check "uninstall: only home left" test "$(ls "$dest")" = "home"
check "uninstall: shortcuts removed" bash -c "test ! -e '$desk' && test ! -e '$menu'"
check "uninstall: entry removed" bash -c "! wine reg query '$regkey' > /dev/null 2>&1"
check "uninstall: saved settings kept" test -d "$dest/home/.runelite"
cp "$rel/manifest.json" "$work/manifest.good.json"
sed -i 's/"label": "e2e 4"/"label": "tampered"/' "$rel/manifest.json"
n=$(mark)
run "$dl" --silent --no-start; r=$?
check "fresh install with a tampered manifest: exit 3" test "$r" = 3
check "fresh install with a tampered manifest: nothing installed" bash -c "test ! -e '$dest/java' && test ! -e '$dest/GadScape.exe' && test -z '$(files_since $n)'"
cp "$work/manifest.good.json" "$rel/manifest.json"
release "$dist" "$work/setup-v2/GadScape-241-Test-Setup.exe" "e2e 5"    # a good release again
n=$(mark)
run "$dl" --silent --no-start; r=$?
check "reinstall with the old v1 setup: exit 0" test "$r" = 0
check "reinstall: v1 hands over to v2, cache kept (not downloaded)" test "$(files_since $n)" = "setup java client "
check "reinstall: launcher is v2" test "$(sha256sum < "$dest/GadScape.exe")" = "$(sha256sum < "$work/setup-v2/GadScape-241-Test-Setup.exe")"
run "$wdest\\GadScape.exe" --uninstall --silent --remove-home
check "full uninstall: folder gone" test ! -e "$dest"
check "full uninstall: entry removed" bash -c "! wine reg query '$regkey' > /dev/null 2>&1"

# ---- 11. a setup built without a key refuses to install (release names)
say "== 11. setup without a key"
cp "$work/setup-nokey/GadScape-Setup.exe" "$U/Downloads/"
run 'C:\users\'"$(whoami)"'\Downloads\GadScape-Setup.exe' --silent --no-start; r=$?
check "keyless setup: exit 3" test "$r" = 3
check "keyless setup: says why" grep -q "without a release key" "$U/AppData/Local/GadScape/setup.log"
check "keyless setup: installs nothing" test "$(ls "$U/AppData/Local/GadScape")" = "setup.log"
cp "$U/AppData/Local/GadScape/setup.log" "$work/logs/nokey-setup.log"; rm -rf "$U/AppData/Local/GadScape"

# ---- 12. a release-channel setup ignores GadScape-setup.txt (test key, an address that does not resolve)
say "== 12. release setup ignores GadScape-setup.txt"
"$c241/setup/build.sh" --out "$work/setup-rel/GadScape-Setup.exe" --url "https://downloads.example.invalid/gadscape/manifest.json" \
  --key "$work/keys/test-release.pub" >> "$log" 2>&1 || { say "release setup build failed"; exit 1; }
mkdir -p "$U/Downloads/rel"; cp "$work/setup-rel/GadScape-Setup.exe" "$U/Downloads/rel/"
printf 'url=http://127.0.0.1:%s/test/manifest.json\r\n' "$port" > "$U/Downloads/rel/GadScape-setup.txt"
n=$(mark)
run 'C:\users\'"$(whoami)"'\Downloads\rel\GadScape-Setup.exe' --silent --no-start; r=$?
rlog="$U/AppData/Local/GadScape/setup.log"
check "release setup: GadScape-setup.txt not read, built-in address used" bash -c "! grep -q 'manifest address from' '$rlog' && grep -q 'downloads.example.invalid' '$rlog'"
check "release setup: no request to the address in GadScape-setup.txt" test -z "$(since $n)"
check "release setup: download server unreachable, exit 1, nothing installed" bash -c "test $r = 1 && test \"\$(ls '$U/AppData/Local/GadScape')\" = setup.log"
cp "$rlog" "$work/logs/release-override-setup.log"; rm -rf "$U/AppData/Local/GadScape" "$U/Downloads/rel"

# ---- 13. another setup holds the install lock: Cancel ends the wait; the wait ends when the other one does
say "== 13. waiting for another setup"
run "$dl" --silent --no-start > /dev/null 2>&1       # installed again
wine "$work/bin/holdlock.exe" GadScape241Test 60 & sleep 2
wine "$wdest\\GadScape.exe" --no-start & bpid=$!
for i in $(seq 1 40); do grep -q "is running: waiting" "$dest/setup.log" 2>/dev/null && break; sleep 0.5; done
sleep 1; shot waiting-other-setup
xdotool mousemove 790 433 click 1
t0=$(date +%s); wait $bpid; r=$?; took=$(( $(date +%s) - t0 ))
check "Cancel while waiting for another setup: exit 2 within 3 s ($took s)" test "$r" = 2 -a "$took" -le 3
check "Cancel while waiting: logged" bash -c "grep -q 'Cancel pressed' '$dest/setup.log' && grep -q 'cancelled' '$dest/setup.log'"
wineserver -k; sleep 2
wine "$work/bin/holdlock.exe" GadScape241Test 4 & sleep 1
t0=$(date +%s); run "$wdest\\GadScape.exe" --silent --no-start; r=$?; took=$(( $(date +%s) - t0 ))
check "the waiting setup goes on when the other one ends (exit 0 after $took s)" bash -c "test $r = 0 && test $took -ge 2 && grep -q 'is running: waiting' '$dest/setup.log'"
run "$wdest\\GadScape.exe" --uninstall --silent --remove-home

say "== done: $pass passed, $fail failed (log $log, screenshots $work/shots)"
[ "$fail" = 0 ]

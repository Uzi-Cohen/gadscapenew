# GadScape client (build 241)

This folder builds the GadScape client for Old School build 241: the official **RuneLite 1.13.3** (the RuneLite
release for build 241) made into GadScape:

- RuneLite's game client, `injected-client-1.13.3.jar`, gets three small patches so it can talk to our server
  (login key, server address check, friends chat size).
- RuneLite's own `client-1.13.3.jar` is branded: window title "GadScape", the GadScape shield as window/tray icon
  and splash logo, GadScape gold as accent colour, and one hook so GadScape's login screen can draw.
- `gadscape-plugins.jar` adds GadScape's login screen (saved accounts with faces, Remember me), ported from the
  211 client.
- The launcher writes GadScape's default RuneLite settings (plugins that cannot work on a private server off, GPU on).

Everything else is RuneLite as RuneLite ships it, so the Plugin Hub (and 117 HD) works.

There are two **build channels**: **release** (the default: "GadScape", GadScape's live login key, default server
gadscape.duckdns.org) and **test** (`build.sh --test`: "GadScape (test)", the TEST key, 127.0.0.1, its own folders, a
TEST icon on the exe). They install side by side.

The old client in `client/` (build 211) is not used or changed by any of this.

## What is in here

| Path | What it is |
|---|---|
| `runelite-1.13.3.lock` | Every RuneLite 1.13.3 jar with its download address and SHA-256, copied from RuneLite's `bootstrap.json`. Also the JVM options RuneLite's own launcher uses (`jvm` lines). |
| `tools/fetch-runelite.sh` | Downloads the jars in the lock and checks every hash. |
| `tools/lock-from-bootstrap.py` | Makes a new lock from a `bootstrap.json` (for the next RuneLite version). |
| `patcher/` | The patcher (plain Java + ASM 9.7.1 from Maven Central): `patch` (game client), `brand` (RuneLite's client jar; `patcher/hook/` is the paint hook it adds). Also makes TEST RSA keys. |
| `release-rsa-public-modulus.hex` | The public half of GadScape's live login key (the 211 client's `RuneLite.MODULUS`). Release builds use it. |
| `test-rsa-public-modulus.hex` | The public half of the TEST RSA key. Test builds use it. |
| `branding/` | The GadScape shield (the 211 client's icon, `shield-256.png`), the splash logo (`splash-200.png`) and the Cinzel Decorative font (OFL) used for the exe splash. |
| `plugins/` | `gadscape-plugins.jar`: the GadScape Login plugin (sources ported from `client/.../plugins/gadscapelogin`). |
| `launcher/` | Starts RuneLite with the patched game client, serves its jav_config and world list, writes GadScape's default RuneLite settings. |
| `dist/` | `run.sh`, `run.bat`, the `README.txt` (templates: build.sh fills in the channel's names) and the `licenses/` folder that go into the portable folder. |
| `exe/` | The Windows exe (Go): Java and the client packed into one file. Release icon/splash in `exe/`, test ones in `exe/test-res/`. `exe/kit/` is the code it shares with the setup (loading window, safe unzip, starting the client). |
| `setup/` | `GadScape-Setup.exe`, the small download that installs, updates and starts the client, and `gadscape-release`, the tool that makes signed releases for it (see "Setup and updates"). |
| `devserver/TestServer.java` | A tiny stand-in server for testing the client alone (JS5 cache + login check). Development only. |
| `build.sh` | Builds all of the above. |

## The game client patches (`patcher patch`)

`patcher patch` changes exactly three things and stops with `PATCH FAILED` if one is not found exactly once:

1. **RSA key.** The client encrypts the login with Jagex's public key, built in one place as
   `new BigInteger("<256 hex digits>", 16)` next to `new BigInteger("10001", 16)` (class `ay`, in 1.13.3).
   That hex number is replaced with our public key. The new key must be the same size (1024 bits).
2. **Host check.** When the game starts it checks the server address and stops with the error `invalidhost`
   unless the address is jagex.com, *.runescape.com, 127.0.0.1 or 192.168.1.x. The one copy of that check the
   game calls (`to.ahl`, called from `client.init`, `client.aba` and `client.abu`) now always says yes, for every
   caller. (Two more copies, `to.ajp` and `to.aju`, are never called; they are left alone.)
3. **Friends chat size.** The friends chat member list is built with room for 500 (`super(500)` in the constructor
   of the class implementing `FriendsChatManager`, `rr` in 1.13.3); member 501 makes the client throw. GadScape's
   211 client raised it to 2000 (`NRFriendsChatMixin`) because the server's auto-joined "help" channel has no member
   cap; the patcher does the same (`--fc-capacity N`, 0 = leave it).

It also records, without changing anything, where the game keeps its login page index and login response lines
(`login.*` keys in `gadscape-patch.properties`), for the GadScape Login plugin.

The patched jar also gets a small file, `gadscape-patch.properties`, so the launcher can refuse a stock
injected-client. The patcher also refuses an input jar whose SHA-256 differs from the lock.

RuneLite 1.13.3 itself does **not** check the injected-client's hash or signature when it starts (only RuneLite's
own installer does, and we do not use it). It does have one check of its own: it only accepts a `--jav_config`
address on *.runescape.com or *.jagex.com. The launcher handles that without changing RuneLite (see below).

## RuneLite branding (`patcher brand`)

`patcher brand` turns RuneLite's `client-1.13.3.jar` (RuneLite's BSD code, not Jagex's) into
`client-1.13.3-gadscape.jar`; again every change must match exactly once:

1. Window/tray icon (`ui/runelite_128.png`, `ui/runelite_16.png`) and splash logo (`ui/runelite_splash.png`) =
   the GadScape shield (`branding/`).
2. `runelite.properties`: `runelite.title=GadScape` (window title, tray, notifications), `runelite.discord.enable=false`.
3. The splash window's title "RuneLite Launcher" (task bar) = "GadScape".
4. `ColorScheme.BRAND_ORANGE(_TRANSPARENT)` = GadScape gold F0C450 (the 211 client's accent; `--accent none` keeps
   RuneLite's orange).
5. `Hooks.draw` (once per frame, every game state) calls `GadScapePaintHook.paint(graphics)`, a small class the
   patcher adds. Stock RuneLite cannot draw on the login screen (overlays only render when logged in); the GadScape
   Login plugin registers its painter there.
6. RuneLite signs this jar; Java refuses a signed jar with changed entries, so the signature (`META-INF/RL.SF`,
   `RL.RSA`, per-entry digests) is removed. Nothing in RuneLite checks its own signature at run time.

## GadScape Login (`plugins/`)

The 211 client's login screen, on RuneLite 1.13.3: full-window GadScape design, Remember me (passwords encrypted
for this PC/user in `.runelite/gadscape-accounts.properties`), up to 10 saved accounts with a picture of each
character's head, friendly error messages. It is an ordinary core plugin (RuneLite finds
`net.runelite.client.plugins.*` on its classpath), listed as "GadScape Login"; turning it off brings back the game's
own login screen. It fills the game's username/password and presses Enter for it, so the game logs in as usual.
Differences from 211: RuneLite 1.13.3 has no chathead model, so the face picture is the top of the character model;
the login page index and response lines come from the fields the patcher found (if those are missing the plugin
does nothing and the game's own screen shows); build 241 shows "Incorrect username or password" on its own page
(index 3), which the plugin reads as that answer.

## Build

Needs: Linux with a Java 17+ JDK (`javac`, `jar`), `curl`, `zip`, `unzip`; Go 1.21+ for the exe. The scripts
avoid GNU-only tools (`shasum -a 256` is used when `sha256sum` is missing), so macOS should work, but it is untested.

```
client241/build.sh --jre-zip <Windows x64 Java 17+ JRE zip>          # release: GadScape
client241/build.sh --test --jre-zip <Windows x64 Java 17+ JRE zip>   # test: GadScape (test)
```

Options: `--test` (test channel), `--runelite-jars DIR` (where the RuneLite jars are kept, default
`client241/build/runelite-1.13.3`), `--modulus FILE` (default: `release-rsa-public-modulus.hex`, with `--test`
`test-rsa-public-modulus.hex`), `--default-host HOST` (default: gadscape.duckdns.org, with `--test` 127.0.0.1),
`--out DIR` (default `client241/build/out`). Without `--jre-zip` everything except the exe is built. The editor's
JRE zip (Temurin 21 JRE for Windows x64) works.

| | release (default) | test (`--test`) |
|---|---|---|
| Window title / world name | GadScape / GadScape | GadScape (test) / GadScape 241 Test |
| Login key | `release-rsa-public-modulus.hex` (the live server's key) | `test-rsa-public-modulus.hex` |
| Default server | gadscape.duckdns.org | 127.0.0.1 |
| Portable folder, zip, exe | `GadScape/`, `GadScape.zip`, `GadScape.exe` | `GadScape-241-Test/`, `.zip`, `.exe` |
| Home (Windows / Linux) | `%LOCALAPPDATA%\GadScape` / `~/.gadscape-client` | `%LOCALAPPDATA%\GadScape241Test` / `~/.gadscape-241-test` |
| Exe server file | `GadScape-server.txt` | `GadScape-241-Test-server.txt` |
| Exe icon / splash | GadScape shield | shield with a red TEST band, "TEST build (241)" |

Output in `--out` (release names):

- `injected-client-1.13.3-gadscape.jar` - the patched game client
- `client-1.13.3-gadscape.jar` - RuneLite's client jar, branded
- `channel-release.properties` - the channel values baked into the launcher jar and the exe
- `GadScape/` and `GadScape.zip` - portable folder: `run.bat`, `run.sh`, `server.txt`, jars
- `GadScape.exe` - one file for Windows, Java inside (about 70 MB)
- with `--setup` (instead of the above): `GadScape-Setup.exe` and `gadscape-release` (see "Setup and updates")

Nothing downloaded or built goes into git: `client241/.gitignore` ignores every `build/` folder (including the
default `client241/build/`), `exe/payload/`, and all `*.jar`, `*.zip` and `*.exe` files.

**Offline build.** The patcher needs ASM 9.7.1. `patcher/build.sh` takes it from `patcher/build/deps` (an earlier
build), then `$ASM_DIR` (a folder with `asm-9.7.1.jar` and `asm-tree-9.7.1.jar`), then `~/.m2`, then the Gradle cache
(`~/.gradle/caches/modules-2`), and downloads it from Maven Central only if none has it. Every copy is hash-checked.
The RuneLite jars come from `--runelite-jars DIR` and are only downloaded if missing; a jar with a wrong hash or
size is deleted, so the next run downloads it again.

## Setup and updates (GadScape-Setup.exe)

Players download one small file, `GadScape-Setup.exe` (about 7 MB; Windows 10 or 11, 64-bit: the Go runtime it is
built with does not run on Windows 7 or 8). It downloads Java, the client and the game cache from a folder you put
on any static web host, installs them for this Windows user (no administrator rights), and starts the client. It is the same client as `GadScape.exe`: same folder `%LOCALAPPDATA%\GadScape` (`java\`,
`app\`, `home\` with RuneLite's settings and saved logins), same `GadScape-server.txt`, so the two can be mixed.

What the setup does:

- **First run.** Shows the GadScape window (status, progress bar, Cancel). Downloads `manifest.json` and
  `manifest.sig` from the address built into it and checks the signature with the public key built into it; a
  manifest that is not signed with GadScape's key is refused and nothing is installed (a manifest and signature
  that do not match are fetched once more first: both requests carry a random `?r=` so caches on the way cannot
  mix two releases, and the two files are uploaded one after the other). Every redirect must be https as well.
  Downloads the files the
  manifest lists (Java zip, client zip, cache zip), each checked for size and SHA-256; a broken download is
  continued where it stopped (HTTP Range), retried with back-off, and kept across runs (Cancel, closed window).
  Unpacks safely (no `..`, absolute paths, drive letters, symbolic links or other special files; sizes limited to
  what the signed manifest says) into a staging folder and swaps each part in with a rename. Writes
  `GadScape-server.txt`, makes the "GadScape" shortcuts on the Desktop and in the Start menu, adds GadScape to
  Apps & features (HKCU: name, icon, version, publisher GadScape, uninstall), copies itself to
  `%LOCALAPPDATA%\GadScape\GadScape.exe` and starts the client. Renames and deletes are retried for a few
  seconds while Windows reports a file busy (Windows Defender scanning the `java.exe` it just unpacked, say).
- **Game cache.** The cache goes into `home\.runelite\jagexcache\oldschool\LIVE` only when there is none, or the
  one there is older than the release's: the setup compares every index's reference table (revision and CRC, the
  numbers the game itself checks with the server). A cache the game has already brought up to date from the server
  (newer) is never replaced; one that only differs is replaced only if the setup itself seeded it from an older
  release (`gadscape-cache-version.txt` in that folder). Only `main_file_cache.*` files are touched: the game's
  `preferences2.dat` and everything RuneLite keeps in `.runelite` stay as they are. The release tool stores the
  cache the way the 241 client does (every group with its full 4-byte version), so the client keeps all of it; a
  cache packed by older tools has 2-byte versions, and the client throws away every group whose version is above
  0xFFFF (about a third of `cacheV`) and downloads it from the game server on the first start.
- **Every start** (the shortcuts run the installed `GadScape.exe`): checks the manifest (8 seconds at most),
  downloads only what changed (by SHA-256), and starts the client. Files are only replaced while the client is
  closed (otherwise it asks to close it, or starts the installed version). No connection, or the server is down:
  it says so for a moment and starts the installed client. A release older than the installed one is never
  installed (an old signed manifest cannot downgrade players); the installed client just starts, since that is
  also what a cache still holding the previous release looks like. A newer setup in the release replaces the
  installed launcher (only one built with the same key and channel), which then carries on. An update that a
  crash or power cut interrupted is put back on the next start, with or without a connection, and a crash in the
  launcher itself still starts the installed client (the crash goes to the log).
- **Server address.** New installs get the manifest's `server` (the channel's default server unless the release
  says otherwise). A `GadScape-server.txt` the player edited is never changed; one the setup wrote follows a
  release that moves the server.
- **Uninstall** (Apps & features, or `GadScape.exe --uninstall`): removes the program folder, then the shortcuts and
  the Apps & features entry, and asks whether to delete the home folder too (settings, saved logins, game cache).
  If a file cannot be deleted, the launcher and the entry stay, so the uninstall can be run again.
- Logs: `%LOCALAPPDATA%\GadScape\setup.log` (the uninstaller: `%TEMP%\GadScape-uninstall.log`). Options for
  support: `--silent` (no message boxes), `--no-start`, `--uninstall [--keep-home|--remove-home]`.
- **Test builds only** (`--test` or `--setup-local-http`): a `GadScape-setup.txt` next to the exe whose first line
  is `url=<address of a manifest.json>` makes the setup use another release folder; the launcher it installs keeps
  using it until a setup without one is run. The key cannot be changed that way. Release builds ignore the file (a
  downloaded setup sits in Downloads or a repacked zip, where anyone could put one), so to try a release before it
  goes live, build a setup with that release's `--setup-url`.

### For the owner: making and publishing a release

All of this runs on Linux (macOS should work); nothing is uploaded by the tools.

1. **Make the signing key, once.** Build the release tool and make the key pair:

   ```
   client241/build.sh --setup --out client241/build/out        # also builds gadscape-release
   client241/build/out/gadscape-release keygen --out ~/gadscape-keys
   ```

   `~/gadscape-keys/gadscape-release.key` is the private key: keep it secret and backed up, never upload it, never
   commit it. Anyone with it can publish a release every installed GadScape accepts; losing it means everyone has
   to download a new setup. `gadscape-release.pub` is the public key that goes into the setup.

2. **Pick the address** where the release folder will live, for example
   `https://downloads.example.org/gadscape/manifest.json` (any static host with https: a web server, an S3/R2
   bucket, GitHub Pages, ...). The folder must be served as plain files; HTTP Range support makes broken downloads
   resume (most hosts have it).

3. **Build the client and the setup** (client first, into the same `--out`: the setup and the release tool take
   the channel's default server from the client build's `channel-release.properties`; with `--default-host` on
   the client build, give the same one to the setup build or leave it out):

   ```
   client241/build.sh --jre-zip <Windows x64 JRE zip>                     # the release client (GadScape.zip)
   client241/build.sh --setup --setup-url https://downloads.example.org/gadscape/manifest.json \
                      --setup-key ~/gadscape-keys/gadscape-release.pub      # GadScape-Setup.exe
   ```

   A setup built without `--setup-key` (or address) refuses to install anything. `--setup-version N` (default
   `setup/VERSION`) must go up for every setup you publish after the first; installed launchers then update
   themselves.

4. **Make the release folder** (signs `manifest.json` with the private key):

   ```
   client241/build/out/gadscape-release build --dist client241/build/out --jre-zip <the same JRE zip> \
       --cache-dir <the game cache folder> --setup-exe client241/build/out/GadScape-Setup.exe \
       --key ~/gadscape-keys/gadscape-release.key --out ~/gadscape-release
   client241/build/out/gadscape-release verify --dir ~/gadscape-release --pub ~/gadscape-keys/gadscape-release.pub
   ```

   The folder holds `manifest.json`, `manifest.sig`, `files/` (the zips and the setup, named by their hash) and
   `GadScape-Setup.exe` for your download button. The cache folder's `main_file_cache.dat2` and `.idx` files are
   rewritten the way the 241 client stores them (every group the reference tables list, CRC-checked, with its
   4-byte version; groups that do not match their table are left out with a warning, the game downloads them) and
   zipped the same way every time, so an unchanged cache keeps its hash and nobody downloads it again. The game
   server's own cache is not changed. `gadscape-release cachecheck --dir <cache folder>` shows how many groups the
   client would keep of any cache folder. For
   the next release build into the same folder: the previous manifest there must verify with your key, the
   release number goes up (the time, by default), the cache version stays when the cache did not change, and files
   no longer listed are deleted. The release prints the server address new installs get (the client build's
   default server unless `--server HOST`). Options: `--server HOST`, `--label TEXT`, `--version N`,
   `--cache-version N`, `--base-url URL` (files on another host). `gadscape-release inspect --setup-exe FILE` shows what a setup was
   built with.

5. **Upload** the whole folder to the address from step 2: `files/` first, then `manifest.sig`, then
   `manifest.json`, so players never see a manifest whose files are not there yet. `manifest.sig` holds the
   signatures of the new and the previous manifest, so the moment between the two uploads does no harm. If the
   host is behind a CDN, let it keep `manifest.json` and `manifest.sig` for a short time only (a minute) or pass
   query strings to the origin: the setup asks for both with a random `?r=`, and a mismatch that lasts makes new
   installs fail with "try again in a few minutes". Then check what players will get: `gadscape-release verify --url https://downloads.example.org/gadscape/manifest.json --pub
   ~/gadscape-keys/gadscape-release.pub`. Link players to `GadScape-Setup.exe`.

To test a release locally first: build a test setup (`build.sh --test --setup --setup-local-http --setup-url
http://127.0.0.1:8090/manifest.json --setup-key <test .pub>`), `gadscape-release serve --dir <release folder>`,
and run the setup (or put a `GadScape-setup.txt` with `url=<address>` next to a test setup). Only test builds accept
plain http, and only to 127.0.0.1. `setup/e2e/wine-e2e.sh` runs the whole cycle under Wine.

Tests: `cd client241/setup && go test ./...` (signatures, hashes, resumed downloads, zip safety, cache seeding and
repacking, update planning, manifest versions, redirects, retried renames) and `cd client241/exe && go test ./kit/`.

Not covered: the setup exe is not Authenticode-signed (Windows SmartScreen will warn about a new download until
it has a reputation; a code-signing certificate fixes that); Windows proxy settings are not read (only the
`HTTPS_PROXY` environment variable); it was tested under Wine on Linux, not on real Windows. Before the first
public release, run it once on real Windows 10 and 11 with Windows Defender on (first install, an update, the
uninstall): Wine does not lock files the way Windows does.

## Run it against a server

**Windows exe:** double-click `GadScape.exe` (test: `GadScape-241-Test.exe`). The first time, it unpacks Java and
the client to `%LOCALAPPDATA%\GadScape` (test: `GadScape241Test`) and creates `GadScape-server.txt` (test:
`GadScape-241-Test-server.txt`) next to the exe. The first line of that file that is not a comment is the server
address (host name or IP). It starts as the channel's default server (release gadscape.duckdns.org, test 127.0.0.1).

**Portable folder:** put the server address on the first line of `server.txt`, then `run.bat` (Windows, needs Java
17+ in `java\` next to it or on PATH) or `./run.sh` (Linux). `./run.sh --host <address>` also works.
RuneLite options go after `--`, for example `./run.sh -- --safe-mode --debug`. `run.bat` keeps its window open with
the client log, and pauses on an error so it can be read.

**The address** can be a host name or an IPv4 address. The launcher also accepts `http://name/`, a path, a
trailing `# comment` and `:port`; a port other than 43594 is ignored with a warning (the port cannot be changed, see
below). The file may be UTF-8 (with or without BOM) or UTF-16 (Notepad's "Unicode"). Lines starting with `#` are skipped.

The game connects to that address on **port 43594** (with a retry on 443, as Jagex's client does). The world
list has one world: world 1, "GadScape" (test: "GadScape 241 Test"), at that address.

### What the server needs

- Build 241 protocol (rsprot `osrs-241`) on port 43594, JS5 included. The client sends revision 241,
  sub-version 1, client type 1, platform 0. The RSA block arrives as 128 or 129 bytes (a leading zero byte).
- The server must listen on **43594**: if the test world profile sets another port, no client can connect. The
  jav_config says `param=15=0` (a live build), and for that the game always uses 43594 and then 443. If another port
  is ever needed, `param=15=<non-zero>` makes the game use 40000+world without a bytecode patch, but it changes other
  client behaviour too, so test it first.
- The private key matching the client's login key: release clients carry the live server's key
  (`release-rsa-public-modulus.hex`, the same key the 211 client used: worlds.yml `rsaModulus` /
  `rsaPrivateExponent` of the live server); test clients the TEST key, start the test server with
  `-Dgadscape.rsa.private=<path to private.key>` (see the next section).

## The TEST RSA key

Made with `java -cp "patcher/build/gadscape-patcher.jar:patcher/build/deps/*" gadscape.patcher.Patcher genkey --out-dir <dir>`:
a 1024-bit key with public exponent 65537, the same size as Jagex's. The private key is **not in git**; it is in
the test machine's `rev241/inputs/test-rsa/` folder:

| File | Format |
|---|---|
| `private.key` | PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`). Java reads it with the standard library: drop the two PEM lines, Base64-decode, `KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes))`. rsprot wants `RsaKeyPair(privateKey.privateExponent, privateKey.modulus)`. OpenRune's `RsaProvider` (BouncyCastle `PemReader`) reads it too. |
| `worlds-test-rsa.yml` | The same key as `rsaPrivateExponent` / `rsaModulus` in base 36, the format the old server's `worlds.yml` and `com.gadscape.crypto.Rsa.init` use. |
| `public-modulus.hex` | The public modulus in hex (same as `test-rsa-public-modulus.hex` here). |

This key is for the test server only. The live server's key is never used. To change the key: run `genkey`
into a new folder, copy its `public-modulus.hex` over `test-rsa-public-modulus.hex`, rebuild.

## Where files go (it never touches your normal RuneLite or OSRS)

RuneLite keeps everything under `<home>/.runelite` and the game keeps its cache in `<home>/.runelite/jagexcache`
(RuneLite tells the game to). The launcher points `<home>` (Java's `user.home`) at its own folder:

- Windows exe: `%LOCALAPPDATA%\GadScape\home` (test: `GadScape241Test`; Java in `...\java`, the client in
  `...\app`, logs `...\launcher.log`, `...\client-output.log`, `...\home\.runelite\logs\client.log`)
- Portable folder on Windows: `%LOCALAPPDATA%\GadScape\home`; on Linux: `~/.gadscape-client/home` (test:
  `%LOCALAPPDATA%\GadScape241Test\home`, `~/.gadscape-241-test/home`)
- Another folder: `--home <dir>`

`run.sh`, `run.bat` and the exe also start Java with `-Duser.home=<home>`, so what Java caches at startup (font
cache, Java preferences) points at the client's home too.

Checked on Linux: after logging in, installing 117 HD and closing, `~/.runelite`, `~/jagexcache`,
`~/jagex_cl_oldschool_LIVE.dat` and `~/random.dat` did not exist; all of those were inside the client's home, and no
other file in the real home was created or changed (apart from the graphics driver's own shader cache,
`~/.cache/mesa_shader_cache`, which `XDG_CACHE_HOME` moves). RuneLite's Discord integration is turned off (see below);
before that it registered a `discord-409416265891971072` URL handler outside the client's home
(`~/.local/share/applications` on Linux, `HKCU\Software\Classes` on Windows, replacing the one of a normal RuneLite).
The exe was checked under Wine too: nothing in the Windows profile folder, everything in `%LOCALAPPDATA%\GadScape241Test`.

## How the launcher works

`gadscape.launcher.Launcher` runs inside the same Java process as RuneLite:

1. Reads the build channel (`gadscape/launcher/channel.properties` in its jar, written by build.sh: title, world
   name, default server, home folder names; without it the launcher is a test build) and sets `user.home` to the
   client's home (above).
2. Checks there is exactly one injected-client on the classpath and that it is the patched one, and that RuneLite's
   client jar is there only once (a stock, unbranded one only gets a warning).
3. Serves `jav_config.ws` and `worldlist.ws` on 127.0.0.1 (a free port). The jav_config has the same keys and
   params as Jagex's (`https://oldschool.runescape.com/jav_config.ws`, build 241) with `codebase=http://<server>/`,
   `param=17` = our world list, `param=12` = world 1, `param=25=241`. `param=9` defaults to the value the old server
   used (`--session` changes it). The world list uses Jagex's binary `slr.ws` format.
4. RuneLite 1.13.3 only takes a `--jav_config` on *.runescape.com / *.jagex.com, so it gets
   `http://jav-config.gadscape-test.runescape.com/jav_config.ws`. That name is never looked up in DNS: a proxy rule
   inside this Java process sends requests for it to the local server from step 3.
   `--jav-config <url>` makes the launcher download that jav_config and serve it the same way.
5. Keeps private-server gameplay out of public services (see "What the client sends where" below).
6. Writes RuneLite's runtime config to `<home>/gadscape/runelite-runtime-config.json` and points RuneLite at it
   (`-Drunelite.rtconf`). It is RuneLite's live config minus the two login blocks, `outdatedClientVersions` and
   `outdatedPluginVersions`, and the outage fields. Without this, once RuneLite releases 1.13.4 (or lists an
   installed Plugin Hub plugin such as 117 HD as outdated) the game would refuse every login with "RuneLite has been
   updated! Please restart your client." It also sets three RuneLite properties: `runelite.discord.enable=false`,
   `runelite.title` (the channel's window title) and `runelite.api.base` (step 5). RuneLite logs the result at
   startup ("Using local runtime config: ...").
7. Writes GadScape's default RuneLite settings (`Defaults.java`, `--no-defaults` skips it), once per profile:

   | Setting | Why |
   |---|---|
   | HiScore off | looks players up on Jagex's OSRS hiscores |
   | Chat Commands off | !kc, !pb, !lvl, !qp answer with real OSRS data for the same name |
   | OSRS Wiki Crowdsourcing, XP Updater off | send gameplay to public services (also blocked, see below) |
   | World Hopper, Default World off | use Jagex's world list; hopping there cannot log in |
   | Discord, Twitch off | Discord registers a URL handler outside the client's home |
   | Login Screen off | its paste/username keys act on the game's hidden login fields under GadScape's login screen |
   | GPU on | smooth frame rate and draw distance (RuneLite falls back if the PC cannot do it) |

   On a fresh home they go into `.runelite/settings.properties`, which RuneLite imports into its "default" profile on
   first start (its own upgrade path); on an existing home into the profile RuneLite will use (the active one), under
   RuneLite's lock files. `gadscape.defaultsVersion` records what was applied, so a player who turns a plugin back
   on keeps it; a new entry is applied once when `Defaults.VERSION` goes up. Everything else keeps RuneLite's
   defaults. "Hide roofs" is the game's own setting (a varbit the server keeps), so the client does not touch it.
8. Starts RuneLite with `--disable-telemetry`.

## What the client sends where

RuneLite and some of its default plugins send what happens in the game to public services. On this private
server that data would be wrong for everyone else, so the launcher stops it:

- **Blocked hosts** (`Launcher.BLOCKED_HOSTS`): `crowdsource.runescape.wiki` (the OSRS Wiki Crowdsourcing plugin,
  on by default), `wiseoldman.net`, `templeosrs.com`, `crystalmathlabs.com` (the XP Updater plugin), and their
  subdomains. The proxy rule sends them to a local dead end that answers 403, so nothing leaves the computer. The
  launcher logs `blocked <host>` the first time.
- **RuneLite's API** (`api.runelite.net/runelite-1.13.3`): RuneLite is pointed at `http://127.0.0.1:<port>/runelite-api`
  on the launcher. Reads (GET/HEAD) are redirected to RuneLite, so item prices, the world list, Plugin Hub counts and
  chat lookups keep working. Writes (POST/PUT/PATCH/DELETE) are refused with 403: `!kc`, `!pb`, `!qp`, `!gc`, pets,
  `!task`, raid layouts and other chat-command submissions (which would overwrite the records of a real OSRS player
  with the same name), loot tracker sync, RuneLite account config sync and Plugin Hub install statistics. The
  launcher logs `api <METHOD> <path> refused`.
- Not changed: the Plugin Hub itself (`repo.runelite.net`), RuneLite account login, hiscore lookups (Jagex), and
  `static.runelite.net`. Plugin Hub plugins can talk to their own services; add a host to `BLOCKED_HOSTS` if one of
  them sends gameplay somewhere.

Checked: in the Xvfb run RuneLite logged `props={runelite.discord.enable=false, runelite.api.base=...}`, fetched
item prices, worlds and Plugin Hub counts through the filter, and 117 HD installed and started. A test with
RuneLite's own OkHttp 3.14.9 got `Unexpected response code for CONNECT: 403` for all four blocked hosts, HTTP 403 for
`POST /chat/kc`, and HTTP 200 from `api.runelite.net` for `GET /item/prices.js` and `GET /pluginhub`.

## Testing without the real server

`devserver/TestServer.java` (Java 11+, no build step):

```
java client241/devserver/TestServer.java --cache <241 cache dir> --rsa <private.key> [--login probe|refuse]
```

It serves the cache over JS5 so the client gets to its login screen. The master index gets each reference
table's real version; for the bzip2-compressed ones (in 241: archives 0, 1, 2, 3, 17, 18, 24) it runs the `bzip2`
program, since the JDK has no bzip2 decoder (without `bzip2` those get version 0, which the client still accepts). With `--login probe` it reads the login,
decrypts the RSA part with the TEST key, prints whether it is valid and the username, then answers
"incorrect username or password". With `--login refuse` it stops listening once the client has its cache
connection, which is the same as logging in with no server running.

## Known limits

- **Pinned to RuneLite 1.13.3 / build 241.** The jars come from RuneLite's repository by exact version and hash.
  A newer RuneLite means a new lock (`tools/lock-from-bootstrap.py`) and re-running the patcher, which stops
  loudly if Jagex changed the code it looks for.
- RuneLite's own **World Hopper** lists Jagex's worlds (it reads RuneLite's world API, not our world list). Hopping
  to one of those would fail to log in; it starts switched off (Defaults).
- With the GadScape Login plugin off, the game's own screens show (terms, "Jagex Account / Download Launcher");
  use **Accept**, **Existing User**, then **Login**.
- RuneLite 1.13.3 ships two scripts of its own, 12/10003 and 12/10004 (10004 re-sends a private message after a
  chat command consumed it). It only adds them when the cache has no such script; the 2746-based cache has Jagex's
  Raids 4 scripts there, so RuneLite's are not used. Only "!kc"-style commands typed in a private message with the
  Chat Commands plugin turned back on would hit this (the PM would not be sent).
- Script overlays: RuneLite replaces 30 game scripts with its own versions (bank tags, chat timestamps, skill tab,
  zoom, ...) only when the cache's script is byte-identical to the one RuneLite was built for. All 30 match the
  openrs2 2746 cache (checked); they do not match the old 211-converted cacheU, which is why RuneLite logged
  "Mismatch in overlaid cache archive hash" there. RuneLite 1.13.3's widget ids also follow 2746's interfaces
  (for example 303:21, used by Opponent Information, exists in 2746 but not in the older 2740).
- Port 43594 is fixed (the game's own rule for live builds). Running the test server next to the live server on the
  same machine would need another address.
- macOS: `run.sh` uses `lib-macos` (x64 and Apple silicon) and `jvm-args-mac.txt` (RuneLite's macOS JVM options)
  there, but it was not tested. ARM Linux gets LWJGL's arm64 natives in `lib-linux`, also untested.
- The exe was tested under Wine on Linux, not on real Windows. Under Wine, Java crashed in `NetworkInterface`
  while seeding `SecureRandom` until Wine-only test settings were used (`-Djava.security.properties` putting
  SunMSCAPI first); that is a Wine gap, not something real Windows needs.
- Shipping this means shipping RuneLite's injected-client (Jagex code) with changes. (Downloading
  the stock jar on the tester's computer and patching it there would avoid redistributing it; the lock, the hash check
  and the patcher are ready for that, but it is not built yet.)
- Licenses: the portable folder, the zip and the exe carry `licenses/`: RuneLite's BSD license, the license texts
  and copyright lines of every library jar (`licenses/THIRD-PARTY.txt` lists them) and the Apache NOTICE files.

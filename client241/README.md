# GadScape build 241 TEST client

This folder builds the client for testing the GadScape server on Old School build 241.
It is the official **RuneLite 1.13.3** (the RuneLite release for build 241) with one jar changed:
RuneLite's game client, `injected-client-1.13.3.jar`, gets two small patches so it can talk to our server.
Everything else is RuneLite as RuneLite ships it, so the Plugin Hub (and 117 HD) works.

The old client in `client/` (build 211) is not used or changed by any of this.

## What is in here

| Path | What it is |
|---|---|
| `runelite-1.13.3.lock` | Every RuneLite 1.13.3 jar with its download address and SHA-256, copied from RuneLite's `bootstrap.json`. Also the JVM options RuneLite's own launcher uses (`jvm` lines). |
| `tools/fetch-runelite.sh` | Downloads the jars in the lock and checks every hash. |
| `tools/lock-from-bootstrap.py` | Makes a new lock from a `bootstrap.json` (for the next RuneLite version). |
| `patcher/` | The patcher (plain Java + ASM 9.7.1 from Maven Central). Also makes TEST RSA keys. |
| `test-rsa-public-modulus.hex` | The public half of the TEST RSA key. The patcher writes it into the client. |
| `launcher/` | Starts RuneLite with the patched game client and serves its jav_config and world list. |
| `dist/` | `run.sh`, `run.bat`, the `README.txt` and the `licenses/` folder that go into the portable folder. |
| `exe/` | The Windows exe (Go): Java and the client packed into one file. |
| `devserver/TestServer.java` | A tiny stand-in server for testing the client alone (JS5 cache + login check). Development only. |
| `build.sh` | Builds all of the above. |

## The two patches

`patcher` changes exactly two things and stops with `PATCH FAILED` if either one is not found exactly once:

1. **RSA key.** The client encrypts the login with Jagex's public key, built in one place as
   `new BigInteger("<256 hex digits>", 16)` next to `new BigInteger("10001", 16)` (class `ay`, in 1.13.3).
   That hex number is replaced with our public key. The new key must be the same size (1024 bits).
2. **Host check.** When the game starts it checks the server address and stops with the error `invalidhost`
   unless the address is jagex.com, *.runescape.com, 127.0.0.1 or 192.168.1.x. The one copy of that check the
   game calls (`to.ahl`, called from `client.init`, `client.aba` and `client.abu`) now always says yes, for every
   caller. (Two more copies, `to.ajp` and `to.aju`, are never called; they are left alone.)

The patched jar also gets a small file, `gadscape-patch.properties`, so the launcher can refuse a stock
injected-client. The patcher also refuses an input jar whose SHA-256 differs from the lock.

RuneLite 1.13.3 itself does **not** check the injected-client's hash or signature when it starts (only RuneLite's
own installer does, and we do not use it). It does have one check of its own: it only accepts a `--jav_config`
address on *.runescape.com or *.jagex.com. The launcher handles that without changing RuneLite (see below).

## Build

Needs: Linux with a Java 17+ JDK (`javac`, `jar`), `curl`, `zip`, `unzip`; Go 1.21+ for the exe. The scripts
avoid GNU-only tools (`shasum -a 256` is used when `sha256sum` is missing), so macOS should work, but it is untested.

```
client241/build.sh --jre-zip <Windows x64 Java 17+ JRE zip>
```

Options: `--runelite-jars DIR` (where the RuneLite jars are kept, default `client241/build/runelite-1.13.3`),
`--modulus FILE` (default `test-rsa-public-modulus.hex`), `--out DIR` (default `client241/build/out`).
Without `--jre-zip` everything except the exe is built. The editor's JRE zip (Temurin 21 JRE for Windows x64) works.

Output in `--out`:

- `injected-client-1.13.3-gadscape.jar` - the patched game client
- `GadScape-241-Test/` and `GadScape-241-Test.zip` - portable folder: `run.bat`, `run.sh`, `server.txt`, jars
- `GadScape-241-Test.exe` - one file for Windows, Java inside (about 70 MB)

Nothing downloaded or built goes into git: `client241/.gitignore` ignores every `build/` folder (including the
default `client241/build/`), `exe/payload/`, and all `*.jar`, `*.zip` and `*.exe` files.

**Offline build.** The patcher needs ASM 9.7.1. `patcher/build.sh` takes it from `patcher/build/deps` (an earlier
build), then `$ASM_DIR` (a folder with `asm-9.7.1.jar` and `asm-tree-9.7.1.jar`), then `~/.m2`, then the Gradle cache
(`~/.gradle/caches/modules-2`), and downloads it from Maven Central only if none has it. Every copy is hash-checked.
The RuneLite jars come from `--runelite-jars DIR` and are only downloaded if missing; a jar with a wrong hash or
size is deleted, so the next run downloads it again.

## Run it against a server

**Windows exe:** double-click `GadScape-241-Test.exe`. The first time, it unpacks Java and the client to
`%LOCALAPPDATA%\GadScape241Test` and creates `GadScape-241-Test-server.txt` next to the exe. The first line of
that file that is not a comment is the server address (host name or IP). It starts as `127.0.0.1` (this computer).

**Portable folder:** put the server address on the first line of `server.txt`, then `run.bat` (Windows, needs Java
17+ in `java\` next to it or on PATH) or `./run.sh` (Linux). `./run.sh --host <address>` also works.
RuneLite options go after `--`, for example `./run.sh -- --safe-mode --debug`. `run.bat` keeps its window open with
the client log, and pauses on an error so it can be read.

**The address** can be a host name or an IPv4 address. The launcher also accepts `http://name/`, a path, a
trailing `# comment` and `:port`; a port other than 43594 is ignored with a warning (the port cannot be changed, see
below). The file may be UTF-8 (with or without BOM) or UTF-16 (Notepad's "Unicode"). Lines starting with `#` are skipped.

The game connects to that address on **port 43594** (with a retry on 443, as Jagex's client does). The world
list has one world: world 1, "GadScape 241 Test", at that address.

### What the server needs

- Build 241 protocol (rsprot `osrs-241`) on port 43594, JS5 included. The client sends revision 241,
  sub-version 1, client type 1, platform 0. The RSA block arrives as 128 or 129 bytes (a leading zero byte).
- The server must listen on **43594**: if the test world profile sets another port, no client can connect. The
  jav_config says `param=15=0` (a live build), and for that the game always uses 43594 and then 443. If another port
  is ever needed, `param=15=<non-zero>` makes the game use 40000+world without a bytecode patch, but it changes other
  client behaviour too, so test it first.
- The TEST private key, so it can read the login block: start the server with
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

- Windows exe: `%LOCALAPPDATA%\GadScape241Test\home` (Java in `...\java`, the client in `...\app`,
  logs `...\launcher.log`, `...\client-output.log`, `...\home\.runelite\logs\client.log`)
- Portable folder on Windows: `%LOCALAPPDATA%\GadScape241Test\home`; on Linux: `~/.gadscape-241-test/home`
- Another folder: `--home <dir>`

`run.sh`, `run.bat` and the exe also start Java with `-Duser.home=<home>`, so what Java caches at startup (font
cache, Java preferences) points at the test home too.

Checked on Linux: after logging in, installing 117 HD and closing, `~/.runelite`, `~/jagexcache`,
`~/jagex_cl_oldschool_LIVE.dat` and `~/random.dat` did not exist; all of those were inside the test home, and no
other file in the real home was created or changed (apart from the graphics driver's own shader cache,
`~/.cache/mesa_shader_cache`, which `XDG_CACHE_HOME` moves). RuneLite's Discord integration is turned off (see below);
before that it registered a `discord-409416265891971072` URL handler outside the test home
(`~/.local/share/applications` on Linux, `HKCU\Software\Classes` on Windows, replacing the one of a normal RuneLite).
The exe was checked under Wine too: nothing in the Windows profile folder, everything in `%LOCALAPPDATA%\GadScape241Test`.

## How the launcher works

`gadscape.launcher.Launcher` runs inside the same Java process as RuneLite:

1. Sets `user.home` to the test home (above).
2. Checks there is exactly one injected-client on the classpath and that it is the patched one.
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
   updated! Please restart your client." It also sets two RuneLite properties: `runelite.discord.enable=false` and
   `runelite.api.base` (step 5). RuneLite logs the result at startup ("Using local runtime config: ...").
7. Starts RuneLite with `--disable-telemetry`.

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
  to one of those would fail to log in. Use the login screen's world switch instead.
- The login screen still shows Jagex's "Jagex Account / Download Launcher" screens; use **Existing User**, then **Login**.
- Port 43594 is fixed (the game's own rule for live builds). Running the test server next to the live server on the
  same machine would need another address.
- macOS: `run.sh` uses `lib-macos` (x64 and Apple silicon) and `jvm-args-mac.txt` (RuneLite's macOS JVM options)
  there, but it was not tested. ARM Linux gets LWJGL's arm64 natives in `lib-linux`, also untested.
- The exe was tested under Wine on Linux, not on real Windows. Under Wine, Java crashed in `NetworkInterface`
  while seeding `SecureRandom` until Wine-only test settings were used (`-Djava.security.properties` putting
  SunMSCAPI first); that is a Wine gap, not something real Windows needs.
- Shipping this means shipping RuneLite's injected-client (Jagex code) with changes. Keep it to testers. (Downloading
  the stock jar on the tester's computer and patching it there would avoid redistributing it; the lock, the hash check
  and the patcher are ready for that, but it is not built yet.)
- Licenses: the portable folder, the zip and the exe carry `licenses/`: RuneLite's BSD license, the license texts
  and copyright lines of every library jar (`licenses/THIRD-PARTY.txt` lists them) and the Apache NOTICE files.

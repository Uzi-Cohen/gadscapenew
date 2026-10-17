# Deploying GadScape to a Ubuntu 24.04 VPS

## 0. Ports (open these in the firewall)

The server binds four ports; two are client-facing, two aren't. This was traced
through the actual decompiled client logic (`Client.java`, `worldPort`/`js5Port`),
not just guessed from server-side evidence alone - an earlier pass here wrongly
concluded 443 was dead weight based only on the world-hopper ping plugin and
`docker-compose.yml`, before finding the real fallback mechanism below.

| Port | Protocol | Purpose | Exposure |
|---|---|---|---|
| 22 | TCP | SSH | Public (ufw) |
| 43594 | TCP | Primary game/login port (`worlds.yml` world `port:`) | **Public (ufw)** |
| 443 | TCP | Automatic fallback: the client's `Client.java` retries here when a connection on 43594 fails, so players on networks that block 43594 still get in | **Public (ufw)** |
| 40001 | TCP | `40000 + world number` (world `main` = number 1) - only used when the client's `gameBuild` != 0, which the shipped config never sets (`param=15=0` in jav_config) | Loopback only (`127.0.0.1`), not in ufw |
| 50001 | TCP | `50000 + world number` - same `gameBuild != 0` gate as 40001, never reached in practice | Loopback only, not in ufw |

40001/50001 are bound to `127.0.0.1` by the server itself (`Main.kt`'s
`networkService.listen(port, "127.0.0.1")` calls) rather than the wildcard
address, so they aren't reachable from outside the VPS regardless of firewall
rules. Both 43594 and 443 are privileged-port-safe/unprivileged respectively,
but 443 specifically is a privileged (<1024) port on Linux, so `gadscape.service`
grants `AmbientCapabilities=CAP_NET_BIND_SERVICE` rather than running the whole
process as root just for that one bind.

If you ever run the `beta` world (number 2) instead, its two internal ports
become 40002/50002 - still loopback-only, nothing to change in the firewall.

## 1. Build locally (Windows, this machine)

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
cd gadscape-server-main
.\gradlew.bat :app:shadowJar
```

> **Note:** the task originally requested, `:app:distZip`, is explicitly disabled
> in `app/build.gradle.kts` (`tasks.named<Zip>("distZip").configure { enabled = false }`).
> The project's own `run-live.sh`/`run-beta.sh` scripts already use the fat jar
> from `shadowJar` instead - this is the pre-existing, intended production
> artifact, not a workaround. It comes out at `app/build/libs/shadow.jar`
> (~187 MB, everything bundled - no separate classpath to manage).
>
> The build is pinned to JDK 21 by the project itself
> (`buildSrc/.../gadscape-project-kotlin.gradle.kts:12` - `jvmToolchain(21)`),
> and has been verified to boot successfully under Temurin 21 (see Phase 1).

## 2. Assemble the upload bundle locally

Create a staging folder (anywhere, e.g. `gadscape-upload/`) containing:

```
gadscape-upload/
├── server.jar              <- copy of app/build/libs/shadow.jar, renamed
├── worlds.yml               <- YOUR real, filled-in worlds.yml (NOT .example)
├── data/                    <- the entire data/ folder (~146 MB)
├── cache/
│   └── data/
│       ├── cache/           <- ~165 MB
│       ├── grandexchange/   <- <1 MB
│       ├── objects/         <- ~2 MB
│       └── npcs/            <- ~21 MB
├── app/
│   ├── logback-live.xml
│   ├── logback-beta.xml
│   └── logback-dev.xml
└── deploy/
    ├── start.sh
    ├── gadscape.service
    └── setup.sh
```

The `cache/data/*` subfolders above are exactly the ones the project's own
`Dockerfile` copies for a running server (not the whole `cache/` directory,
which also contains build-time source/tooling you don't need at runtime) -
**do not run the cache generator on the VPS**, just copy this pre-built data.

`data/characters/` holds player saves. If this is a fresh public launch you can
leave it out (or empty it) to not carry over local dev-test accounts; if you
want to preserve any accounts you already created locally, include it as-is.

From PowerShell, an example using `scp` (adjust the path/user/host):

```powershell
Copy-Item .\gadscape-server-main\app\build\libs\shadow.jar .\gadscape-upload\server.jar
scp -r .\gadscape-upload user@your-vps-ip:~/gadscape-upload
```

## 3. Run setup on the VPS

```bash
ssh user@your-vps-ip
cd ~/gadscape-upload
sudo bash deploy/setup.sh
```

This installs OpenJDK 21 + ufw + rsync, creates a non-root `gadscape` system
user, copies everything into `/opt/gadscape`, opens the firewall, and installs
+ enables the `gadscape` systemd service (`Restart=always`). It does **not**
start the service yet.

## 4. Start it

```bash
sudo systemctl start gadscape
sudo systemctl status gadscape
sudo journalctl -u gadscape -f      # structured logs also land in /opt/gadscape/logs/game.log
```

## Updating later

Re-run step 1-2, `scp` the new `server.jar` (and `data/`/`cache/` only if they
changed) into `~/gadscape-upload/`, then on the VPS:

```bash
sudo cp ~/gadscape-upload/server.jar /opt/gadscape/server.jar
sudo systemctl restart gadscape
```

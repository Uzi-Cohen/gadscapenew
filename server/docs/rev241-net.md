# Rev 241 networking (NET track)

The server's hand-written rev-211 netty pipeline is replaced by
[rsprot](https://github.com/blurite/rsprot) `net.rsprot:osrs-241-api:1.0.0-ALPHA-20261014` (MIT).
rsprot owns the sockets, the login handshake (proof of work, RSA, ISAAC, session id), JS5, and the
byte layout of every game packet. The game keeps its own APIs: `PacketDispatcher`, the
`com.zenyte.game.packet.out` classes and the `com.zenyte.game.packet.in.event` classes are unchanged
for their callers.

This is a TEST build. Live stays on 211.

## Build and compatibility

- `gradle/libs.versions.toml`: `rsprot = "1.0.0-ALPHA-20261014"`, library `rsprot-osrs241-api`;
  `netty` bumped from 4.1.119.Final to **4.2.2.Final** (rsprot is built against netty 4.2 and imports
  its BOM; mixing 4.1 and 4.2 jars is not supported). The old `netty-incubator-transport-native-io_uring`
  stays on the classpath but is not used (the old `NetworkService` had it commented out already).
- `core/build.gradle.kts`: `api(libs.rsprot.osrs241.api)` and `implementation(libs.apache.ant)`
  (bzip2 for the JS5 reader, same decoder jagcached uses).
- rsprot is compiled with Kotlin 1.9.23; the server's Kotlin 2.1.20 consumes it fine. JDK 21.
- Only `osrs-241-api`, `-model`, `-common` and `protocol/buffer/crypto/compression` are on the compile
  classpath; `osrs-241-shared/internal/desktop` are runtime-only (so e.g. `RSProtConstants` is not
  visible: `Rsprot.REVISION = 241` is used instead).

## Running

```
java ... com.zenyte.Main localhost \
  -Dgadscape.cache.dir=<dir with main_file_cache.dat2 + idx*>   (default cache/data/cache/)
  -Dgadscape.rsa.private=<private key file>                     (default: worlds.yml rsaPrivateExponent/rsaModulus)
```

Other properties:

| Property | Default | Meaning |
|---|---|---|
| `gadscape.net.ports` | (none) | extra ports besides the world's `port` (43594), e.g. `443` |
| `gadscape.js5.preload` | false | prepare every JS5 group at boot instead of on first request |
| `gadscape.login.token` | (unset) | expected login "site settings" string (the jav_config param 9 value, which the rev-211 login compared with a hard-coded constant; that constant is gone). Unset = not checked, because stock RuneLite 241 sends an empty string. |
| `gadscape.login.skipCrc` | false | do not compare the client's archive CRCs with the served cache at login |

The RSA key file can be PEM PKCS#8 (`BEGIN PRIVATE KEY`, what `client241/patcher genkey` writes to
`inputs/test-rsa/private.key`), PEM PKCS#1, or `rsaPrivateExponent:` / `rsaModulus:` lines in base 36
(the worlds.yml format, e.g. `inputs/test-rsa/worlds-test-rsa.yml`). A directory is accepted too.

The network service is now bound right after the world profile is read (before the cache/definitions
load), so clients can download the cache while the world loads. Game logins are answered
`LoginServerOffline` until `Main` sets `Rsprot.acceptLogins = true` at the very end of the boot
(where the old pipeline used to be started). Nothing else binds 43594: the old `NetworkService`,
`MasterHandler` and the extra 443/40000+id/50000+id listeners are gone.

## Files

`core/src/main/kotlin/com/gadscape/network/rsprot/`

| File | What |
|---|---|
| `Rsprot.kt` | contract object (`service`, `start()`), plus `acceptLogins`, `js5`, `REVISION`; `GadScapeNetworkServiceFactory` (ports, desktop client, RSA, Huffman from cache 10/"huffman", JS5 provider, consumers, connection handler, proof of work with the world id). Factory modelled on OpenRune-Server's `NetworkFactory` (ISC, Copyright (c) 2025 RS Mod). |
| `RsprotJs5.kt` | `RawJs5Cache`: self-contained read-only dat2/idx reader (does not use jagcached, which the CACHE track changes); `RsprotJs5`: the `Js5GroupProvider` (raw containers minus version trailer, 255/x reference tables, 255/255 master index = crc+version of every archive slot, 0/0 for absent slots 16 and 23). |
| `RsprotRsa.kt` | key file loader (formats above). |
| `RsprotLogin.kt` | `RsprotConnectionHandler` (rsprot -> `World.addLoginRequest`), `RsprotLoginChecks` (the old `LoginPacketIn.getResponse()` checks), `RsprotLoginRequest` (port of the old `LoginRequest`: same account/password/2FA/website checks, registration on the world thread), `ClientResponse -> LoginResponse` mapping. |
| `RsprotSession.kt` | `RsprotSession implements com.zenyte.net.Session` (what `player.getSession()` returns): `send(GamePacketEncoder)` -> `toMessage()` -> rsprot queue; `process()` runs the incoming consumers on the world thread; same 20 s / 60 s (wilderness, gambling) expiry as `WorldSubHandler`; `close()` calls `RsprotInfo.logout` once and closes the channel after the pending packets. `RsprotDrops` logs dropped packet types once. |
| `RsprotIncoming.kt` | rsprot message consumers -> the old `ClientProtEvent`s. |
| `RsprotOut.kt` | helpers for the non-trivial outgoing messages (rebuilds, zone enclosed, clientscripts, hint arrows). |
| `RsprotInfo.kt` | STUB of the INFO track's file (see below). |

Changed: `GamePacketEncoder` (`toMessage()` added, `encode()` deprecated), all 86 classes in
`game/packet/out` (each `encode()` became `toMessage()`), `clans/ClanChannel*Builder` (friends chat
packets built by rsprot), `PacketDispatcher` (camera + map flag pass the player), `SceneSynchronization`
(UPDATE_ZONE_FULL_FOLLOWS gets the level), `World.addLoginRequest`, `GameLoader` (no JS5 preload, no
decoder table), `Main` (rsprot start), `DeveloperCommands`/`PvmArenaFakePlayers` (no more
`RebuildNormal(fake, true).encode()` for fake players).

Removed: `MasterHandler`, `PipelineFactory`, old `NetworkService`, `LoginService`,
`NetworkPipelineConstants`, `login/{LoginRequest, codec/LoginDecoder, packet/LoginPacketIn, ClientSettings, LoginCiphers}`,
`world/{WorldDecoder, WorldEncoder}`, `js5/{JS5Request, JS5RequestDecoder}`, `proofofwork/*`,
`packet/ClientProtLoader`, `packet/ClientProtDecoder`, the 60 classes in `packet/in/decoder`.
Kept but now unused (other tracks may still touch them; delete after the merge): `js5/JS5Responses.kt`,
the `net` module's `ClientProt`/`ServerProt`/`GamePacketOut`/handshake codecs (the rev-211
`PlayerInfo`/`NPCInfo` still compile against `GamePacketOut` until the INFO track removes them).

## Login sequence and the INFO contract

1. rsprot: handshake 14, proof of work (SHA-256, rsprot default difficulty), login block (RSA with our
   key, revision 241, session id checked by rsprot), then `RsprotConnectionHandler.onLogin` on rsprot's
   login executor.
2. NET: `Rsprot.acceptLogins`, password authentication only (token/Jagex-account logins ->
   `InvalidLoginPacket`), revision, CRCs (unless beta world / `gadscape.login.skipCrc`), site-settings token
   (if configured), world full, update timer, username -> `World.addLoginRequest` -> `LoginManager`.
3. `RsprotLoginRequest.loaded` (login pool): the unchanged rev-211 response logic.
4. World thread: `World.addPlayer` (assigns the index) -> `LoginResponse.Ok` (index, staff level,
   p-mod, members, authenticator trust code, a stable per-account hash) -> `RsprotSession` set on the
   player, disconnection hook -> `areaManager.onLogin`, `loadMapRegions(true)` ->
   **`RsprotInfo.login(player)`** -> queue **`RsprotInfo.rebuildLogin(player)`** (REBUILD_LOGIN, the
   first game packet; it carries the GPI init block from INFO's PlayerInfo) -> `afterLoadMapRegions`,
   `setInitialized(true)`, `player.onLogin()` -> flush.
5. `Rsprot.start()` installs `RsprotInfo.sink` so INFO's per-tick info packets are queued on the
   player's session (dropped if the session is closed).
6. Logout/disconnect: `World.unregisterPlayer` -> `Player.finish` -> `RsprotSession.close()` ->
   **`RsprotInfo.logout(player)`** (exactly once) and the channel is closed after the LOGOUT packet.

`RsprotInfo.kt` on this branch is a stub with the contract (`login/logout/tick`) plus the two hooks the
INFO branch exposes (`rebuildLogin(player)`, `sink`); INFO's file replaces it at merge. Without INFO
the login gets to `LoginResponse.Ok` but no REBUILD_LOGIN is sent (warning logged).

INFO must still (its side): call `infos.updateRootBuildAreaCenteredOnPlayer`/`updateRootBuildArea`
when `Player.loadMapRegions` rebuilds (REBUILD_NORMAL/REGION are queued by `PacketDispatcher` from
`processEntityUpdate`), and run `tick()` before `WorldThread.processPlayerLogouts`, which is where every
session is flushed once per cycle.

## JS5

Served by rsprot's single-threaded fair JS5 service from `RsprotJs5`. Groups are prepared on first
request (512-byte blocks, `Js5Service.prepareJs5Buffer`) and kept off-heap as unreleasable buffers.
The login CRC check compares the client's CRCs with the same master index.

## Outgoing packets (packet.out class -> rsprot 241 message)

Interfaces: `IfOpenTop`->IF_OPENTOP, `IfOpenSub`->IF_OPENSUB (type 1 = overlay), `IfCloseSub`->IF_CLOSESUB,
`IfMoveSub`->IF_MOVESUB, `IfSetEvents`->IF_SETEVENTS_V2 (events2 = 0), `IfSetText`, `IfSetHide`,
`IfSetModel`->IF_SETMODEL_V2, `IfSetAnim`, `IfSetNpcHead`, `IfSetPlayerHead`, `IfSetObject`,
`IfSetColour` (15-bit RGB), `IfSetPosition`, `IfSetScrollPos`, `IfSetAngle`, `IfClearItems`->IF_CLEARINV.

Vars/scripts: `VarpSmall`, `VarpLarge`, `ResetClientVarCache`->VARP_RESET, `SyncClientVarCache`->VARP_SYNC,
`RunClientScript`->RUNCLIENTSCRIPT (types inferred from the values: int, String, long, int[];
Boolean/Number arguments are converted to int).

Inventories/stats: `UpdateInvFull`, `UpdateInvPartial` (same combined id + inventory id as before,
`Container.AWAITING_RESET_CONTAINERS` kept), `ResetContainer`->UPDATE_INV_STOPTRANSMIT,
`UpdateStat`->UPDATE_STAT_V2 (invisible boosted level = current level), `UpdateRunEnergy` (x100),
`UpdateRunWeight`.

Map: `RebuildNormal`->REBUILD_NORMAL_V2 (zone x/z, world area -1, no XTEA keys), `RebuildRegion`->
REBUILD_REGION_V2 (same 4x13x13 zone copy table, no keys), login rebuild -> REBUILD_LOGIN_V2 (INFO).

Zones: `Player.processEntityUpdate` still collects zone packets per zone; `UpdateZonePartialEnclosed`
is built with rsprot's `ZonePartialEnclosedCacheBuffer` (released once per world cycle) and carries:
`LocAdd`->LOC_ADD_CHANGE_V2 (op flags 31), `LocDel`, `LocAnim`, `LocCombine`->LOC_MERGE, `MapAnim`,
`MapProjAnim`->MAP_PROJANIM_V2, `ObjAdd` (op flags 31), `ObjDel`, `ObjUpdate`->OBJ_COUNT,
`AreaSound`->SOUND_AREA. `UpdateZoneFullFollows` (now with the level) and `UpdateZonePartialFollows`
map directly.

Sound/music: `SynthSound`, `MidiSong`->MIDI_SONG_V2 (no fades; -1/65535 -> MIDI_SONG_STOP), `MidiJingle`.
Camera: `CamLookAt`->CAM_LOOKAT_V3 and `CamMoveTo`->CAM_MOVETO_V3 (build-area coords converted to
absolute), `CamShake`, `CamReset`, `FreeCam`->CAM_MODE, `SetCamType`->OCULUS_SYNC.
Chat/social: `MessageGame`, `MessagePrivate`, `MessagePrivateEcho`, `FriendListLoaded`,
`UpdateFriendList`, `UpdateIgnoreList`, `ChatFilterSettings`, `SetPrivateChatFilter`,
`MessageFriendChannel`, `ResetFriendChannel`->UPDATE_FRIENDCHAT_CHANNEL_FULL_V2(leave),
`ClanChannelFull`->UPDATE_FRIENDCHAT_CHANNEL_FULL_V2(join), `ClanChannelMember`->
UPDATE_FRIENDCHAT_CHANNEL_SINGLEUSER, `MessageClanChannel`, `MessageClanChannelSystem`.
Misc: `Logout`, `LogoutTransfer`, `SetMapFlag`->SET_MAP_FLAG_V2 (scene coords converted to absolute;
255,255 = reset), `SetHintArrow`->HINT_ARROW, `MinimapToggle`, `SetPlayerOp`, `OpenUrl`->URL_OPEN,
`Heatmap`->SET_HEATMAP_ENABLED, `ResetAnims`, `TriggerOnDialogAbort`, `UpdateRebootTimer`->
UPDATE_REBOOT_TIMER_V2 (empty message), `UpdateSiteSettings`->SITE_SETTINGS, `UpdateUID192`,
`PingStatisticsRequest`->SEND_PING (nano time split in two ints), `GraphicsObjectSend`->
MAP_ANIM_SPECIFIC, `GrandExchangeOffer`->UPDATE_STOCKMARKET_SLOT_V2.

### Approximated

- `IfModelRotate` (rev-211 IF1_MODELROTATE roll/pitch) -> IF_SETROTATESPEED(x speed = roll, y speed = pitch).
- `MapProjAnim`: heights are multiplied by 4 (241 no longer does it client-side), end coord is absolute,
  source index 0 (not locked to the shooter); `offset` is passed unchanged as `progress`.
- `MidiSong`: no fade in/out.
- `MessagePrivate`/`MessageClanChannel*`: world message counter is a server-wide counter (rev 211 sent
  5 random bytes); chat text is re-encoded by rsprot from `ChatMessage.getChatText()`.
- `MessageFriendChannel`: world id 1 and crown 0, exactly what the rev-211 encoder wrote.
- `UpdateFriendList`: notes/world name/platform are empty, previous name empty.
- Login response: `accountHash`/`userHash` = stable hash of the username, `userId` = the account's
  user identifier (rev 211 sent 0).
- Text in `IfSetText` null -> "" (rev 211 sent "null" from `PacketDispatcher`, which still does that).

### Dropped (logged once by `RsprotDrops`)

- `MapProjAnimSpecific`, `ObjFlags`: the rev-211 encoders wrote an empty payload already.
- `SetTradingPost`: the GE "trading post" browser, not supported on 241 yet.
- The rev-211 `PlayerInfo`/`NPCInfo` encoders (until INFO replaces them).

## Incoming packets (rsprot 241 message -> old event)

| rsprot | event |
|---|---|
| If3Button (IF_BUTTONX, op 1..32) | If3ButtonEvent(option = op) |
| IfSubOp | If3ButtonEvent(option = op) - the sub-option index is not passed (the old handlers have no sub-menus; the parent op runs, e.g. the old "Rub" dialogue) |
| If1Button | OpModelEvent (what rev-211 BUTTON_CLICK was) |
| IfButtonD / IfButtonT | IfButtonDEvent / IfButtonTEvent (sub = slot, obj = item) |
| OpNpcV2 / OpNpc6 / OpNpcT | OpNpcEvent(op, ctrl = run) / OpNpcExamineEvent / OpNpcUEvent if an item is selected, else OpNpcTEvent |
| OpLocV2 / OpLoc6 / OpLocT | OpLocEvent / OpLocExamineEvent / OpLocUEvent or OpLocTEvent |
| OpObjV2 / OpObj6 / OpObjT | OpObjEvent / OpObjEvent(option 6) / OpObjUEvent or InterfaceOnFloorItemEvent |
| OpPlayer / OpPlayerT | OpPlayerEvent / OpPlayerUEvent or OpPlayerTEvent |
| MoveGameClick / MoveMinimapClick | MoveGameClickEvent / MoveMinimapClickEvent (absolute coords, key combination) |
| Teleport / ClickWorldMap | ClickWorldMapEvent / DoubleClickWorldMapEvent (packed level<<28,x<<14,z) |
| MessagePublic / ClientCheat / MessagePrivate | MessagePublicEvent (colour pattern dropped) / CommandEvent (lower-cased) / MessagePrivateEvent |
| FriendListAdd/Del, IgnoreListAdd/Del | same-named events |
| FriendChatJoinLeave / FriendChatSetRank / FriendChatKick | ClanJoinChatLeaveChatEvent / FriendSetRankEvent / ClanKickUserEvent |
| ResumePauseButton / ResumePCountDialog / ResumePNameDialog / ResumePStringDialog / ResumePObjDialog | same-named events |
| CloseModal, ExitFreecam, SetChatFilterSettings, BugReport, SendSnapshot | CloseModalEvent, FreeCamResetEvent, ChatSetModeEvent, BugReportEvent, PlayerReportEvent |
| MapBuildComplete, WindowStatus, NoTimeout, Idle, EventAppletFocus, EventMouseMove/NativeMouseMove, EventMouseClickV1/V2, EventCameraPosition, EventKeyboard, SendPingReply, ConnectionTelemetry | MapBuildCompleteEvent, WindowStatusEvent, NoTimeOutEvent, EventMouseIdleEvent (once per tick), EventAppletFocusEvent, EventMouseMoveEvent, EventMouseClickEvent, EventCameraPosEvent, EventKeyboardEvent (last key), PingStatisticsEvent, LoginStatisticsEvent |

Not registered (rsprot skips them without decoding): IfScriptTrigger, IfCrmViewOp, ResumePLongDialog,
OpWorldEntity*, clan channel/settings requests, affined clan settings, ReflectionCheckReply,
DetectModifiedClient, MembershipPromotionEligibility, RSevenStatus, SoundJingleEnd, HiscoreRequest,
SetHeading, EventMouseScroll. The rev-211 server had no handlers for them either (OpHeld*,
SetAppearance and LoginInfo were already disabled).

## Verifying

Two protocol-level smoke tests (no game client needed) live in `ci/rev241/`:

- `python3 ci/rev241/js5test.py <host> <port> <cache dir>`: JS5 handshake (15 + 241), 255/255 compared
  byte for byte with the master index computed from the cache files, every 255/x checked against its
  master-index CRC, a few groups compared with the stored containers.
- `python3 ci/rev241/logintest.py <host> <port> <public-modulus.hex> <cache dir> [user] [pass]`: a minimal
  241 login (INIT_GAME_CONNECTION, GAMELOGIN with an RSA block made with the TEST public key, the XTEA
  part with the 23 live CRCs in the 241 order, SHA-256 proof of work) that prints the login response.

Results on the NET branch alone (2026-10-21, `inputs/cache241`, `inputs/test-rsa`, test profile on port
43611, no `data/` folder):

- the server binds the port right after the world profile is read ("Started network service");
- `js5test.py`: handshake ok, 255/255 ok (25 archive slots), 23 reference tables match, groups ok;
- `logintest.py`: session id, proof of work (difficulty 18), RSA/XTEA/session-id/CRC checks pass, the
  request reaches `LoginManager`, and the server answers 24 (LOGINSERVER_LOAD_ERROR) because
  `new Player(...)` fails: `CollectionLog.<clinit>` -> `StructDefinitions.definitions` is null, as
  the rev-211 jagcached cannot read the 241 cache on this branch (`GameLoader`: "Loaded game" fails
  with OutOfMemoryError while opening it) and `data/` is missing (ItemDefinitions.json etc.).
  The boot itself continues to "Accepting game logins." (Main logs those failures and goes on);
  the world thread then logs `BountyHunterController` init errors every tick for the same reason.
  The CACHE and INFO branches are needed for a login to get into the world.

## Not done / known gaps

- Reconnect (LoginResponse.ReconnectOk) is answered `ConnectFail`; the client falls back to a full
  login (rev 211 had no reconnect either).
- Clan channels (the new clan system, not friends chat) never had full/delta packets on this server;
  only the clan chat messages are mapped.
- World entities (sailing) are out of scope.
- The 117 HD / RuneLite plugins are not affected by this track.

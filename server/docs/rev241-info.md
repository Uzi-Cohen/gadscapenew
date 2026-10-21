# Rev 241 player info and NPC info (INFO track)

Branch `claude/rev241-info`. Test build only: the live server stays on 211.

The server's hand-written 211 player info and NPC info are gone:

- `PlayerInfo.java`
- `NPCInfo.java`
- `UpdateMask`
- `ModelOverrideMask`
- the 18 `player/update/mask/*` classes

rsprot 241 (`net.rsprot:osrs-241-api:1.0.0-ALPHA-20261014`, MIT) now builds those packets.

## Files

| File | What it does |
|---|---|
| `core/src/main/kotlin/com/gadscape/network/rsprot/RsprotInfo.kt` | Implements the contract (`login`, `logout`, `tick`). Holds per-player and per-NPC state, maps the Zenyte masks and sends the packets. |
| `core/src/main/kotlin/com/gadscape/network/rsprot/RsprotExtendedInfo.kt` | Maps each old mask to the matching rsprot extended info block, using plain values. Every value is clamped so rsprot never throws. |
| `core/src/main/kotlin/com/gadscape/network/rsprot/Rsprot.kt` | **Stub.** NET's real file replaces it at merge. |
| `core/src/test/kotlin/com/gadscape/network/rsprot/RsprotInfoTest.kt` | Offline test. It builds a real `NetworkService` (no port is bound), allocates two players and an NPC, runs three cycles and decodes the player info packet. |
| `core/src/test/kotlin/com/gadscape/network/rsprot/PlayerInfoTestClient.kt` | rsprot's MIT test client, copied with its licence header, that decodes the player info packet. |
| `core/src/test/resources/com/gadscape/network/rsprot/huffman.dat` | Copied from rsprot's test resources. |

Other changes:

- **`WorldThread.processPlayerEntityUpdate`** calls `RsprotInfo.INSTANCE.tick()` after the per-player `processEntityUpdate` loop. This is where the old packets were sent.
- **`Player.processEntityUpdate`** no longer sends `PlayerInfo`/`NPCInfo`. The viewport fields and getters were removed.
- **`UpdateFlag`** keeps every constant, so its 91 call sites are unchanged. The 211 wire bits were replaced by "applies to players / applies to NPCs" flags.
- **`Appearance`** lost its 211 encoder (`writeAppearanceData` and its buffer). `getWornItemId` and `getBeardIconWithAppearanceOffset` are now public, and `RsprotInfo.buildAppearance` uses them.
- **`HitType`** has `getId()` and `getDynamicId()` instead of `writeMask(RSBuffer)`.
- **`PrivateMask`** has `getViewers()`.
- **`RebuildNormal`** (`init` path) writes the GPI block through `RsprotInfo.writeAbsolutePlayerPositions`. Without rsprot infos it falls back to the same 30-bit + 18-bit layout.
- **The `::npcinfo` "view local" admin command** reads `RsprotInfo.localNpcs(player)`.
- **`gradle/libs.versions.toml` and `core/build.gradle.kts`** get the same rsprot and netty 4.2.2 lines as NET, plus JUnit 5 for core tests.

## Cycle order

Each world cycle runs in this order:

1. The game runs as before and flags Zenyte `UpdateFlags`.
2. `processEntityUpdate` runs for every player. It queues containers, map rebuild (REBUILD), vars and zone packets.
3. `RsprotInfo.tick()` runs:
   1. It allocates, moves or releases one rsprot NPC avatar for each NPC in `World.getNPCs()`.
   2. For each logged-in player, it calls `updateRootCoord`. When `lastLoadedMapRegionTile` has changed, it updates the build area (13x13 zones centred like REBUILD). It then copies the flagged masks into the avatar.
   3. It calls `infoProtocols.update()`.
   4. For each player it sends, in this order: SET_ACTIVE_WORLD_V2, SET_NPC_UPDATE_ORIGIN, WORLDENTITY_INFO_V7, PLAYER_INFO, NPC_INFO_*_V6 (dropped when empty, as OSRS does), SET_ACTIVE_WORLD_V2.
4. Logouts are processed, then `resetNpcMasks` and `resetPlayerMasks` run, as before.

Info packets are therefore the last thing in the cycle: rebuild and zone packets come first.

## What NET has to call

These are the only integration points. NET's `Rsprot.kt` replaces the stub, and the contract signatures are unchanged.

1. **`RsprotInfo.login(player)`.** Call it on the world thread once the player has an index and coords, for example right after `World.addPlayer` and `loadMapRegions(true)`. It allocates the player info, NPC info and world entity info.
2. **The GPI init, before the next `tick()`.** Do one of the following:
   - send `RsprotInfo.rebuildLogin(player)`, which is a ready `RebuildLoginV2`;
   - build `RebuildLoginV2(..., RsprotInfo.playerInfo(player))` yourself;
   - call `RsprotInfo.writeAbsolutePlayerPositions(player, buf)`.

   No info packet is sent to a player before its GPI init. If packets were computed and dropped first, the info state is reset before the init block is written.
3. **`RsprotInfo.sink = RsprotInfo.Sink { player, message -> session(player).queue(message); true }`.** Install this once, at start-up. Without a sink the packets are released and dropped, and a warning is logged at most once a minute.
4. **`RsprotInfo.logout(player)`.** Call it when the player leaves the world.
5. **`RsprotInfo.onReconnect(player)`.** Call it before LoginResponse.ReconnectOk, then write a new GPI init.

## Mask mapping

| Old 211 mask | rsprot 241 |
|---|---|
| Appearance (+ Nametag) | Appearance block: gender/body type, pronoun, skull, head icon, worn objs with hidden wearpos, ident kits, colours, BAS, name, combat level, hidden, name extras = nametags. |
| Animation | `setSequence(id, delay)` |
| Graphics | `setSpotAnim(slot 0, id, delay, height)` |
| Hit | `addHitMark`: tinted id for the receiver and the source, darkened id for everyone else. `addHeadBar`/`removeHeadBar` from HitBar. |
| ForceChat / Chat | `setSay` / `setChat` |
| FaceEntity / FaceLocation | `setFacePlayer`/`setFaceNpc`/`resetFacing`. Players use `setFaceAngle(direction)`, NPCs use `setFaceCoordGrid`. |
| Movement / TemporaryMovement | `setMoveSpeed(lastMovementType)` / `setTempMoveSpeed` (1 walk, 2 run, 127 teleport, as before). |
| ForceMovement | `setExactMove`, with deltas relative to the current tile as before. |
| Tinting | `setTinting(delay, delay + duration, ...)`. `setSpecificTinting` is used when the tint has viewers. |
| NPC Transformation | `avatar.setId` + `setTransmogrification` |
| CombatLevelChange, NameChange, HideOptions, NpcOverhead, ModelOverride | `setCombatLevelChange`, `setNameChange`, `setVisibleOps`, `setHeadIconChange(slot 0)` / `resetHeadIcon(0)`, `setBodyCustomisation(models)` / `resetBodyCustomisations` |

## Visibility and movement

**Players**
- `player.isHidden()` (admin invisibility) and players that are finishing are not sent to anyone (`avatar.hidden`).
- Zenyte's view distance becomes rsprot's preferred resize range. rsprot shrinks the range by itself in crowds, which replaces the old `resize()`.
- Heatmap mode uses `forceResizeRange(Int.MAX_VALUE)`.

**NPCs**
- An NPC with `isFinished()` (dead or not spawned) is marked inaccessible.
- The NPC render distance is 15. When the view distance is above 15 (cutscenes, the `::scene` admin command), the render distance is raised and the zone search radius grows with it.
- Movement is the tile delta since the last cycle:
  - one or two tiles are sent as walk, run or crawl;
  - a level change, more than two tiles, or Zenyte's `teleported` flag is sent as a teleport jump, which the client shows as remove + re-add, like the old code.
- Dynamic regions and instances need nothing special: Zenyte instances live at real coordinates, so the absolute coords and the rebuild-tile build area stay correct.

## Approximated or missing

**Display**
- **Names.** The appearance name is the plain display name. The vanilla 241 client has no `<title>` tag, so the crown images that `getTitleName()` added are not sent. The chat mod icon still uses `getRankIcon()`.
- **Pronoun.** The pronoun follows the gender. The old code always sent 0.
- **Name length.** The appearance block must stay within 255 bytes, so the name is cut at 64 characters and each nametag at 32.
- **Spotanims.** Only slot 0 is used; the old code supported one spotanim.
- **Restricted graphics.** Graphics restricted to some viewers (`PrivateMask.setViewers`) are not sent. Nothing in the code base uses that.
- **Restricted tinting.** Tinting restricted to some viewers uses rsprot's observer-specific tinting.
- **Chat colours.** Pattern chat colours (13-20) need pattern bytes that the old chat model does not carry, so they are sent as yellow.

**Facing**
- 241 has one face block where 211 had two masks. While a face target is set, it wins over a face direction. This matches what the 211 client showed, because the old code re-sent the target every cycle.
- Players added to someone's view get the last angle through `syncAngle`. The old code always sent the direction on add, so a standing player may face the default way until it turns or moves.

**Per-observer behaviour**
- The old NPC info forced an empty update block when `player.updateNPCOptions(npc)` returned true (varbit multi-NPCs). The 241 client resolves multi-NPC transforms by itself, so this was dropped.
- In heatmap mode, extended info is sent for every player. The old code skipped masks beyond `heatmapRenderDistance`.

**Map**
- The build area is always 104x104 (13 zones). REBUILD_NORMAL_V2 has no map size, so Zenyte's larger `mapSize` scenes are not supported in 241.

**rsprot features not wired**
- NPC base animation sets, head customisation, freeze, transparency and obj customisation are not used. Zenyte has no equivalent.
- World entity info is allocated and sent every cycle, but it stays empty because GadScape has no world entities.

## Verification

```
gbuild.sh <worktree>/server :core:test --tests 'com.gadscape.network.rsprot.*'
gbuild.sh <worktree>/server classes testClasses --offline
```

`RsprotInfoTest` has 5 tests:

- `tick()` is a no-op without the service.
- GPI init + appearance are decoded by rsprot's test client: name, combat level, objs at 0x800 + id, hidden arms and hair, kits, BAS.
- Two players and one NPC over three cycles:
  - the packet order is checked;
  - the second player is decoded into the first player's client;
  - every player mask and NPC mask is applied with no info exception;
  - the NPC walks;
  - the NPC teleports away and leaves the view.
- NPC step decomposition, including crawl, a level change, a teleport and an out-of-map coordinate.
- Out-of-range values are clamped instead of throwing.

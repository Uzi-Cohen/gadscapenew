# Uzi's Map Editor

OSRS/RSPS map editor built for Rsps'.

## Features
- Terrain tools: raise, lower, flatten, smooth, set exact height, roughen (noise), with brush size, strength, undo and redo
- Paint underlays and overlays (all 12 overlay shapes, 4 rotations) on any plane, with an eyedropper (I)
- Paint tile flags (blocked, bridge, remove roof, render on plane below, hide on minimap) and show flagged tiles in red
- Bridges: the map shows floors the way the game does, so a bridge deck (built on floor 1 over a bridge-flagged tile)
  shows at ground level. Make one with Area > "Make it a bridge" (or the Bridge flag): the flag always goes on floor 1,
  where the game reads it. Build the deck on floor 1; floor 0 is what's underneath
- View > Map size: 3 x 3, 5 x 5 (default) or 7 x 7 regions around you. Maps load in the background; each region has its
  own data on the graphics card, so an edit only re-uploads its own region and regions behind the camera aren't drawn
- Models, Animations and Items are tabs next to the Map tab (Ctrl+1-4)
- Select an area: copy/paste it (heights, underlays, overlays, flags, objects, all planes), clear it, or fill it
- Save selections as prefab files and paste them anywhere
- Find & replace object, underlay or overlay ids across the loaded regions or a selection
- Place, rotate, drag-place and drag-delete objects
- Go to coords, bookmarks, ::tele and spawn line copying
- Region backups, one-click deploy to the live server with rollback
- Portable packaging (editor + map + optional Java)
- Direct Editor to server editing, make sure to connect to your dns! 

## Models and animations
The tabs at the top of the window: **Map**, **Models**, **Animations**, **Items** (Ctrl+1 to Ctrl+4 from anywhere;
Tools > Models and animations is Ctrl+Shift+M, Tools > Items is Ctrl+Shift+I). The cache is opened the first time
one of the last three is shown, and the 3D map stops drawing while it isn't on screen.
- **Models**: every model in the cache with a 3D view. Export OBJ (with colours) or the model file, import OBJ (+MTL)
  or model files as new models or over an old one, change colours and size, save as new or over it.
- **Animations**: every animation, played on the NPC that uses it, any NPC / model you type, or a male or female
  player (holding an item if you type its id; animations that put something in the hand show it). Change frame timing,
  copy / remove / reorder frames, save. Export an animation as a bundle (with its frames, keyframes and skeleton) and
  import it into another cache, or copy animations straight from another cache (rev 211 or build 241: they are
  converted to the format of the cache they go into).
- **Items** (also Tools > Items, Ctrl+Shift+I): every item with its inventory picture drawn like the game does.
  **Make a new item...** walks through it step by step: start from an item (a whip, say) or nothing; name, examine
  text and right-click options; its look (each colour or the whole item one colour, textures, models, the inventory
  picture) with a live picture; the item on a male or female player playing its stand / walk / attack / special
  animations; what it does in game (bonuses, attack speed, animations, special attack); then a summary and Create.
  Nothing is written before Create, and one "Undo last change" takes it back.
  You can also make a new item by hand (blank or "Save as new item" from any item), change name, price, weight, stackable / members /
  tradeable, inventory and ground options, the inventory picture (model, zoom, angles, offsets, size), the equip slot
  and what it hides, worn models (male / female, shown alone or on a plain player), recolours and params (skill
  requirements, extra option texts). "Make noted version" adds the noted item. "Copy from another cache" copies items
  with their models from any cache (rev 211 or build 241, an OSRS cache, say), keeping the same ids where they're
  free. "Import from live OSRS" brings items from the newest game cache of build 241 (the build GadScape's game
  runs; when the live game is on a newer build, the window says so) with their own animations and server stats.
  Equipment bonuses, attack speed and what an item does are set on the server, not in the cache.
- Changes go into the exported cache (cache-out), which Deploy sends to the server. "Undo last change" puts it back.
- **In game (server stats)**: the Items tab also shows and edits what an item does in game, which the server keeps in
  `data/items/ItemDefinitions.json`: slot, two-handed, weight, the 14 bonuses, attack speed (ticks), weapon type,
  attack range, weapon animations and requirements. "Copy stats from item" copies what an item really has in game
  (the server also merges the OSRS wiki data for original items). "Save stats" writes the server folder's file,
  "Send stats to server" merges just those items into the live file and restarts the server. Special attacks and
  effects (like the scythe hitting 3 times) are code on the server, tied to the original item's id.

## AI bridge (Claude edits the map)
Tools > AI bridge turns on a small local connection (only this PC, with a key) that lets Claude read the loaded map
and change it: place / remove / turn objects, raise / lower / smooth ground, paint underlays and overlays, set flags,
take screenshots of the 3D view. Every request Claude sends is one Ctrl+Z.

1. Tools > AI bridge (it stays on next time).
2. Select the area you mean with the Select area tool (optional, but "this area" then means that box).
3. Tools > Copy what to tell Claude, paste it into Claude Code, and add what you want ("make this a ruined graveyard").

How Claude uses it is in AI-BRIDGE.md. Claude doesn't save or deploy; you do that (Ctrl+S, Deploy).

## Run
Requires Java 21 (built with Gradle 8.10, Kotlin 1.9 and JavaFX 21; the first run downloads Gradle once).
The 3D view uses LWJGL 3 with OpenGL 4.5 (4.3 at least: any graphics card from the last ten years with a current
driver). It draws on its own thread into an offscreen picture shown inside the window, so menus and panels sit over
it normally. Not on macOS (Apple stops at OpenGL 4.1).

    .\gradlew.bat run

Pick the folder containing `cache\` and `xteas.json` (a build 241 cache needs no `xteas.json`), then click Launch.

## Caches: rev 211 and build 241
The editor reads and writes both GadScape's old rev 211 caches and build 241 caches (the 241 client's). It tells
them apart when a cache is opened (a cache with archive 24 is build 241; the launcher shows which) and uses the right
format for each. The cache library is a fork of Displee's rs-cache-library 6.4 in `rs-cache-library/` (what changed
is in its README); `cache/CacheIO.kt` is where caches are opened and their format is decided.
- New things the editor adds to a build 241 cache get ids from GadScape's own ranges, never ids the game uses
  (`cache/IdAllocator.kt`): frame sets 30000-32767, keyframe sets 20000-29999, skeletons 30000-32767, animations
  40000-49999, sprites 20000-29999 (and scripts, enums, structs, params, varbits, varps, db rows). Models and
  objects stay at or below 65534 in both formats: the 241 client fetches models with 16-bit group ids (a model
  above 65535 is never downloaded) and the game protocol carries object ids in 16 bits (object 70000 would reach the
  server as object 4464), and 65535 means "none". So new models take the highest free id up to 65534 and new
  objects 65534 down to 64000, from the top down, which keeps the ids right after the game's own free for the next
  OSRS caches. In a rev 211 cache models and objects keep the old rule (the first run of 50 free ids, up to 65534).
- Animations: build 241 moved the animation settings after 12 (skeletal keyframe set 14 -> 13, sounds 13/15 -> 14,
  skeletal range 16 -> 15) and keeps skeletal keyframes in archive 22 instead of archive 0. The editor reads and
  writes each cache in its own layout (`utils/AnimData.kt`: `SeqDef`, `SeqConv`, `Keyframes`); animation bundles say
  which layout they hold (`format 241` in `bundle.txt`; older bundles are rev 211) and are converted on import.
  Keyframe sets a 211 -> 241 merge left only in archive 0 still show in the editor (with a warning); importing or
  saving an animation, or deploying, copies them to archive 22, where the game looks for them. That alone doesn't
  make an animation play: the game plays a skeletal animation for (range end - range start) cycles, and the same
  merge left 289 of them (e.g. 10174-10181, the Duke) with no range, so they play for 0 cycles. Importing or saving
  an animation, or deploying, also gives each one the range its keyframe set holds (for every skeletal animation of
  stock 241 that is its range, and for these 289 it is the stock range). Any it can't give a range to are listed:
  they need their stock record (audit BOSS-X1). The preview warns about both. The cache build should still take
  Jagex's own 241 records for these game animations (BOSS-X1): 418 of the 549 also differ from them in settings that
  don't stop them playing, such as 241's vertical offset and sound settings.
- Maps (`cache/MapStore.kt`): rev 211 keeps a square in two named groups, `m{x}_{y}` (ground) and `l{x}_{y}`
  (objects, XTEA-encrypted by Jagex: needs `xteas.json`); build 241 keeps it in one group numbered with the region id
  (file 0 ground, 1 objects, 2-4 lighting and fog), unencrypted, so a build 241 map folder needs no `xteas.json`.
  Build 241 ground ends with one extra flags byte (an underwater level follows when bit 0 is set; square 64,73 has
  one): the editor keeps both as they were. Squares new to a build 241 cache (imported, or from map files) get the
  lighting of the cache they came from, else of the nearest square of the same kind (surface / instances /
  underground). A square imported from another build 241 cache over one that is already there brings its lighting
  too (files 2-4 replaced), like its ground and objects; squares from a rev 211 cache or map files keep the lighting
  the square has. Import regions, Compare caches, the side map and region backups work across the two formats.
  In rev 211 caches, objects the editor saved unencrypted read and save fine even when `xteas.json` has a key for
  that square (before, that could run the editor out of memory), and objects files an old packer padded with zeros
  after the list (square 47,100 in GadScape's 211 cache) read as the game reads them; the padding is dropped on save.
  When a square's objects can't be read (rev 211 without a working XTEA key), saving leaves its objects in cache-out
  as they are and the save status names the square (objects placed there are not kept).
- Interfaces: build 241 model parts keep their model id in 4 bytes (2 in rev 211); the interface viewer reads both.
- Copying between caches and importing from live OSRS (`utils/ItemEdit.kt` `ItemCopy.copyInto`, `CacheEdits.importInto`,
  `utils/LiveOsrs.kt`): everything is written in the format of the cache it goes into. Live and build 241 items go
  into a build 241 cache exactly as the game has them (4-byte model ids, the newer settings); rev 211 items and
  sequences are converted (the converter's rules); only a rev 211 target gets 211 bytes (what it can't hold is left
  out and listed). Models from rev 211 caches get the face-depth ending build 241 models need (`utils/ModelTail.kt`).
  Ids follow the converter's policy: live / OSRS content keeps its own ids where they're free (and what is already
  there under its own id is used as it is); another server's content gets ids from GadScape's ranges; nothing in
  the cache is ever written over, in particular not the game's own frame sets and keyframe sets. The other cache is
  opened read-only. Importing the same animations again uses the skeletons, frame sets and keyframe sets an earlier
  import copied into GadScape's ranges (and, for live content, its animations) instead of copying them again. In a
  rev 211 cache, a model that differs from the live one only by the build 241 face-depth ending counts as the same
  model and is used as it is.
- Tools > Rebrand the cache to GadScape swaps old server names for GadScape in item / NPC / object names, enum,
  struct and db row strings, interface texts and client script strings, in either format (`utils/CacheRebrand.kt`;
  also a command-line tool: `java -cp <editor jar> utils.CacheRebrandKt [--write] <cache folder>`).
- Deploy sends the cache to the folder the live server runs with (`deployRemoteCache` = `auto` in the Settings file:
  the server's `-Dgadscape.cache.dir`, else `cache/data/cache` in its folder), and stops if your cache and the
  server's aren't the same format.
- Checks: `.\gradlew.bat test` (small generated caches); add `-Pgadscape.test.caches=<cache folder>;<cache folder>`
  to also check real caches: every reference table, every group's sizes, writing into a copy, and every item, NPC,
  object, animation, identity kit, spot animation and texture decoded and encoded again byte for byte.

## Keys
| Key | Does |
| --- | --- |
| 1-9, 0, -, = | Pick tool (Inspect, Raise, Lower, Flatten, Smooth, Remove, Set height, Roughen, Underlay, Overlay, Flags, Select) |
| Shift (while painting) | Flip raise/lower, erase paint or clear flags |
| [ ] | Brush size |
| I | Pick underlay/overlay/shape/height from the hovered tile |
| R | Rotate held object, or the overlay shape |
| Ctrl+C / Ctrl+V | Copy selection / paste with the hovered tile as the south-west corner |
| Ctrl+Z / Ctrl+Y | Undo / redo |
| Esc | Drop held object, or clear the selection |

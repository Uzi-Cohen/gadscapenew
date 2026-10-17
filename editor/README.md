# Uzi's Map Editor

OSRS/RSPS map editor built for Rsps'.

## Features
- Terrain tools: raise, lower, flatten, smooth, set exact height, roughen (noise), with brush size, strength, undo and redo
- Paint underlays and overlays (all 12 overlay shapes, 4 rotations) on any plane, with an eyedropper (I)
- Paint tile flags (blocked, bridge, remove roof, render on plane below, hide on minimap) and show flagged tiles in red
- Select an area: copy/paste it (heights, underlays, overlays, flags, objects, all planes), clear it, or fill it
- Save selections as prefab files and paste them anywhere
- Find & replace object, underlay or overlay ids across the loaded regions or a selection
- Place, rotate, drag-place and drag-delete objects
- Go to coords, bookmarks, ::tele and spawn line copying
- Region backups, one-click deploy to the live server with rollback
- Portable packaging (editor + map + optional Java)
- Direct Editor to server editing, make sure to connect to your dns! 

## Models and animations
Tools > Models and animations (Ctrl+Shift+M):
- **Models**: every model in the cache with a 3D view. Export OBJ (with colours) or the model file, import OBJ (+MTL)
  or model files as new models or over an old one, change colours and size, save as new or over it.
- **Animations**: every animation, played on the NPC that uses it (or any NPC / model you type). Change frame timing,
  copy / remove / reorder frames, save. Export an animation as a bundle (with its frames and skeleton) and import it
  into another cache, or copy animations straight from another 211-style cache.
- **Items** (also Tools > Items, Ctrl+Shift+I): every item with its inventory picture drawn like the game does.
  Make a new item (blank or "Save as new item" from any item), change name, price, weight, stackable / members /
  tradeable, inventory and ground options, the inventory picture (model, zoom, angles, offsets, size), the equip slot
  and what it hides, worn models (male / female, shown alone or on a plain player), recolours and params (skill
  requirements, extra option texts). "Make noted version" adds the noted item. "Copy from another cache" copies items
  with their models from any 211-style cache (an OSRS cache, say), keeping the same ids where they're free.
  Equipment bonuses, attack speed and what an item does are set on the server, not in the cache.
- Changes go into the exported cache (cache-out), which Deploy sends to the server. "Undo last change" puts it back.

## AI bridge (Claude edits the map)
Tools > AI bridge turns on a small local connection (only this PC, with a key) that lets Claude read the loaded map
and change it: place / remove / turn objects, raise / lower / smooth ground, paint underlays and overlays, set flags,
take screenshots of the 3D view. Every request Claude sends is one Ctrl+Z.

1. Tools > AI bridge (it stays on next time).
2. Select the area you mean with the Select area tool (optional, but "this area" then means that box).
3. Tools > Copy what to tell Claude, paste it into Claude Code, and add what you want ("make this a ruined graveyard").

How Claude uses it is in AI-BRIDGE.md. Claude doesn't save or deploy; you do that (Ctrl+S, Deploy).

## Run
Requires Java 21.

    .\gradlew.bat run

Pick the folder containing `cache\` and `xteas.json`, then click Launch.

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

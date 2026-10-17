# Uzi's Map Editor - AI bridge

The editor is open on the user's PC with its AI bridge on. Through it you can read the loaded map and change it.
Every change you send goes through the editor's undo, so the user can take it back with Ctrl+Z.

## Connecting
- `~/.uzis-map-editor/ai-bridge.json` (on Windows `%USERPROFILE%\.uzis-map-editor\ai-bridge.json`) has `url` and `key`.
- Send the key in the `X-Bridge-Key` header on every call. JSON in, JSON out.
- PowerShell example:
  ```powershell
  $b = Get-Content "$env:USERPROFILE\.uzis-map-editor\ai-bridge.json" | ConvertFrom-Json
  $h = @{ "X-Bridge-Key" = $b.key }
  Invoke-RestMethod "$($b.url)/api/state" -Headers $h
  Invoke-RestMethod "$($b.url)/api/apply" -Method Post -Headers $h -ContentType "application/json" -Body $json
  Invoke-WebRequest "$($b.url)/api/screenshot" -Headers $h -OutFile shot.png
  ```
- curl: `curl -s -H "X-Bridge-Key: KEY" URL/api/state`. On Windows use `curl.exe`, not `curl`, which is an alias for Invoke-WebRequest.
- Write big JSON bodies to a file and send the file. Quoting long JSON on the command line breaks.

## Coordinates
- World tiles: `x` grows east, `y` grows north. `z` is the floor (0 = ground, 1-3 upstairs).
- Only the loaded map can be read or changed (`loadedArea` in `/api/state`). `/api/goto` loads somewhere else.
- When the user says "this area" or "the selected area", use `selection` from `/api/state`. Changes stay inside it unless the user says otherwise.

## Reading
| Call | Gives |
| --- | --- |
| `GET /api/state` | Loaded area, selection, pinned tile, selected object, current floor, camera tile, undo steps. |
| `GET /api/area?x1=&y1=&x2=&y2=&z=0` | Tiles and objects in a box. Leave out the box to use the selection. At most 64 x 64 tiles. `allFloors=1` gives objects on every floor. |
| `GET /api/objects/used` | The objects already used in the selection (or the loaded map), with counts. Start here so new things match the area's style. |
| `GET /api/objects?q=dead tree&limit=40` | Search objects by name, most used nearby first. Shows `types` (what it can be placed as), size, actions and solid. |
| `GET /api/object?id=1276` | One object. |
| `GET /api/floors` | Every underlay (ground colour) and overlay (paint) with its colour, most used nearby first. |
| `GET /api/screenshot?x=&y=&pitch=&yaw=&distance=&max=1280` | A PNG of the editor's 3D view, optionally pointing the camera first. Look at it after changes. |

### Tile rows in `/api/area`
- Each row is one `y`, north first, and its tiles go west to east.
- Each tile is `height,underlay,overlay`, with `:shape/rot` added for a shaped overlay and `!flags` added when it has flags. `-1` means none.
- `height` is in steps of 8 game units: bigger is higher, and one floor is 30 steps. A difference of 1-2 steps between neighbours is gentle, 10 or more is a cliff.
- `-` means the tile isn't loaded.

## Changing: `POST /api/apply`
```json
{ "label": "graveyard in the selected area", "dryRun": false, "actions": [
  { "op": "place", "id": 1276, "x": 3200, "y": 3200, "type": 10, "rot": 0 },
  { "op": "remove", "x1": 3190, "y1": 3190, "x2": 3195, "y2": 3195, "ids": [1276] },
  { "op": "rotate", "x": 3200, "y": 3200, "rot": 2 },
  { "op": "height", "x1": 3190, "y1": 3190, "x2": 3199, "y2": 3199, "add": 2 },
  { "op": "height", "tiles": [[3190, 3190], [3191, 3190]], "set": 40 },
  { "op": "height", "x1": 3190, "y1": 3190, "x2": 3199, "y2": 3199, "smooth": true },
  { "op": "underlay", "x1": 3190, "y1": 3190, "x2": 3199, "y2": 3199, "id": 48 },
  { "op": "overlay", "tiles": [[3195, 3195]], "id": 10, "shape": 0, "rot": 0 },
  { "op": "overlay", "x": 3196, "y": 3195, "id": -1 },
  { "op": "flags", "x": 3197, "y": 3195, "add": 1 },
  { "op": "clear", "x1": 3190, "y1": 3190, "x2": 3199, "y2": 3199, "objects": true }
] }
```
- **Tiles:** an action covers one tile (`x`, `y`), a box (`x1`, `y1`, `x2`, `y2`), or a list (`tiles`). `z` defaults to the floor the user is on.
- **All or nothing:** every action is checked first. If any is wrong, nothing changes and you get the errors. Otherwise the whole request is one undo step.
- **`dryRun: true`** only checks the actions.
- **Size:** keep requests to a few hundred actions, one idea each (terrain, then paths, then buildings, then decoration). The user can then undo one step without losing the rest.
- **Undo:** `POST /api/undo` and `POST /api/redo` step back and forward one request at a time.

## Objects
- **`type`:**
  - `10` normal objects (trees, rocks, tables, statues). `11` is the same object turned 45 degrees.
  - `22` ground decorations (flowers, debris, rugs).
  - `0` straight wall and `1` wall corner piece. `2` an L-shaped wall corner. `3` a lone pillar at a corner.
  - `4` a wall decoration (torch, painting) on a wall. `5`-`8` are the same, offset or on the diagonal.
  - `9` a diagonal wall. `12`-`21` roof pieces.

  An object can only be placed as the types listed in its `types`. Check with `/api/objects` or `/api/object`.
- **`rot`** is 0-3, quarter turns clockwise. For walls (type 0) the wall sits on the tile's west side at 0, north at 1, east at 2 and south at 3.
- **Size:** big objects (`sizeX` x `sizeY`) take tiles to the north-east of `x`,`y`. An odd `rot` swaps the width and depth.
- **One per slot:** a tile holds one wall (0-3, 9), one wall decoration (4-8), one ground decoration (22), and type 10/11 objects. Placing replaces what's in that slot on the tile unless you send `"replace": false`.
- **`solid: true`** objects block walking. Leave paths and doorways clear.

## Ground
- **Underlay** is the ground colour; neighbouring underlays blend.
- **Overlay** is paint on top: paths, floors, water, sand. `shape` 0 covers the whole tile; `shape` 1-11 cover part of it, turned by `rot`. A shaped overlay shows the underlay in its other part, so the tile needs an underlay too.
- Overlays with `seeThrough: true` show as holes in game; don't use them for ground.
- **Flags** (add/remove bit masks): 1 = blocked (can't walk), 2 = bridge, 4 = remove roof, 8 = render below, 16 = hide on minimap.
- **Heights:** keep slopes smooth (`smooth: true` after raising or lowering). Players can't climb cliffs, and objects sit on the ground height.

## How to work
1. `GET /api/state`. If there's no selection and the user said "here" or "this area", ask them to drag a box with the Select area tool (or use `/api/select` with coordinates they give).
2. Read the area (`/api/area`), see what's already used (`/api/objects/used`, `/api/floors`) and take a screenshot.
3. Plan in passes and send each one as its own `apply` with a clear `label`. Prefer objects and floors the area already uses, so it fits the rest of the map.
4. After each pass, screenshot and check: nothing floating or buried, nothing blocking the way in, no holes or see-through tiles, walls joined at corners.
5. Tell the user what you did and that Ctrl+Z (or asking you) undoes each pass. Saving and deploying is theirs to do (Ctrl+S, then Deploy).

Don't save or deploy, and don't change anything outside the selection, unless the user asks.

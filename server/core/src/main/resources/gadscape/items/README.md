# Bundled item definitions

Every `*.json` file in this folder is loaded at boot by
`cache/src/main/java/com/zenyte/game/parser/impl/BundledItemDefinitions.java`, after
`data/items/ItemDefinitions.json` (the data dir, not in git): `base.json` first, then the other files in name order.

Each file is a JSON array of entries shaped like the data-dir entries
(`id`, `weight`, `slot`, `equipmentType`, `examine`, `equipmentDefinition` {`bonuses`, `requirements`,
`weaponDefinition` {...}}). Attack speed is stored as `10 - ticks`; ranged distances as `range - 1`.

- An id the data dir does not define is added.
- An id the data dir defines is changed only with `"override": true`; the given keys are merged in
  (nested objects key by key, `requirements`/`bonuses` whole, `null` removes a key).
- `"ifDataDir": {...}`: with `override`, apply only while the data-dir entry still has these values
  (so an item the owner edited later in the editor keeps the owner's values).
- `"name"`: the cache name (or an array of accepted names) the entry was written for; on another name at that id the entry is skipped with a warning.
- Keys starting with `_` (e.g. `_source`) are documentation.
- Later files win over earlier ones for the same id (`base.json` is the items-data area's file; add `<area>.json`).
- `-Dgadscape.items.bundled=false` turns the layer off; `-Dgadscape.items.nameCheck=false` skips the name check.

`base.json` is generated (items-data area, IC-07/SKSP-01/IC-08/IC-12); every entry says where its values came from.

Ids on the 241 cache (cacheV): gadconv moved a few GadScape ids that clash with 2746 (idmap.json, resolved by
`com.gadscape.game.rev241.Gad211`). The loader resolves each entry's `"id"` (GadScape's item, e.g. 34723 -> 60002) and
the `*Animation`/`interfaceVarbit` values inside `ifDataDir` (the migrated data dir holds the moved ids). The values an
entry sets are kept as written (the areas wrote them for the 241 cache, e.g. weapons.json's 2746 `tfa_human_*` seqs).
`"idSpace": "gadscape211"` also resolves the entry's own seqs/varbits; `"idSpace": "cacheV"` resolves nothing (an
entry for 2746 content at an id GadScape used, such as a Raids 4 item).

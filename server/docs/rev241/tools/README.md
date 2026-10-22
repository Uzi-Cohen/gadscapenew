# rev241 interface remap tools

Python 3 scripts that produced `../interface-remap.tsv` and `../../rev241-interfaces.md`.
They read a clone of https://github.com/Joshua-F/osrs-dumps (set `OSRS_DUMPS=/path/to/clone`;
commits `c43ec08` = rev 211 and `6e5033a` = rev 241 must be present).

    python3 crossref.py remap.json evidence.json      # script/enum evidence (needs a first remap.json)
    python3 build_remap.py <server-dir> remap.json    # structural alignment + anchors -> remap.json
    python3 ifargs.py remap.json evidence.json        # hook-argument evidence, then rerun build_remap
    python3 enumchk.py <interfaces-json> 1129 1130 1131 1132 1745   # verify panes against the remap enums
    python3 scripts.py <server-dir>; python3 scripts2.py  # clientscript signatures -> scripts.json
    python3 varchk.py                                  # varbit/varp definitions -> varchk.json
    python3 gen_doc.py <server-dir>                    # writes the docs
    python3 show.py remap.json <iface> [all]           # side-by-side view of one interface
    python3 usage.py <server-dir> remap.json <iface>.. # literal component ids used by the server
    python3 apply_puts.py remap.json <iface> <file>    # rewrite put(id, name) with high-confidence rows

`build_remap.py` holds the hand-verified overrides (`MANUAL`) and the prayer anchors.

## cacheV id map (gadconv idmap.json)

    python3 migrate_data_ids.py --idmap cacheV.idmap.json --root <server dir> --dry-run   # what would change
    python3 migrate_data_ids.py --idmap cacheV.idmap.json --root <server dir>             # rewrite data/ and cache/data/

Rewrites a server data directory from GadScape's 211 ids to the ids gadconv moved them to (items, animations, locs,
interfaces in data files). Code resolves the same map at run time (`Gad211`, resource
`cache/src/main/resources/gadscape/rev241/idmap.json`), player saves are migrated at login (`M005_Rev241Ids`). The applied
map is recorded in `data/rev241-idmap.applied.json`, so a later idmap only moves what changed.

## Static interface check (cacheV)

    ./gradlew :app:shadowJar
    docs/rev241/tools/static_check.sh <cacheV dir> <out.txt> [<cacheU dir> <cacheV.component-remap.tsv>]

Every (interface, component) the server names must exist in the cache it runs on. `IfStaticCheck.java` (run on the
shadow jar's classpath) decodes the cache with the server's own `ComponentDefinitions` and lists what the server's
tables name at run time: GameInterface ids (after `Gad211.iface`), every InterfacePosition on every pane (through the
pane enums, like `InterfaceHandler`), the `put(component, name)` table of every Interface plugin after `attach()` (and
runs `build()`, so a `bind()` to a missing name shows up as a plugin error) and `getInterfaceIds()` of every
UserInterface. `static_check.py` adds the references in the sources (sendComponent* / item containers / hashes /
`component` infix / pane `sendInterface` / handler `case`s in single-interface files) and writes the report: MISS
(absent), PRE-211 (absent and already absent on 211), LITERAL-RELOCATED (an interface id idmap.json moved reached a
call: use GameInterface or `Gad211.iface`), WARN (interfaces in `GameInterface.REUSED_BY_241`, type changes of
non-"high" remap rows), unresolved expressions, then every OK reference with the cache's component name. Exit status 1
on MISS or LITERAL-RELOCATED. `STATIC_CHECK_WORK=<dir>` keeps the intermediate dumps.

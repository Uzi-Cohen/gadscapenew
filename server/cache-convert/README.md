# gadconv: GadScape cache converter (`server/cache-convert`)

`gadconv` builds GadScape's build-241 cache in one reproducible run, replacing the hand-built `cacheU`:

* it starts from the stock 241 cache (**BASE**, openrs2 #2746), so stock content keeps its ids;
* it layers on GadScape's own 211 cache (**USER**) only where the user changed or added something compared with
  vanilla 211 (**VAN211**);
* it converts that content from the 211 format to 241, moves ids that clash into reserved GadScape ranges, and
  rewrites every reference to them;
* it records every decision in `report.json` and every id it moved in `idmap.json`.

The same library also converts any future 211-era content (caches, interfaces, maps, scripts) into a 241 cache.
Same inputs give a byte-identical output.

Java 21, package `com.gadscape.cacheconv`. It depends only on `:cache` (mgi `jagcached` for reading and writing
caches, plus RuneLite's cache loaders).

## Layout

| package | what |
|---|---|
| `core` | shared core: `Ctx`, `CacheView`/`JagCache`/`OutCache`, `GroupData`, `IdMap`/`Kind`, `Report`, `Origin`, `Step`, `Options`, `Xteas`, `RefTable`, `Bytes`/`Buf`/`BufOut`, `Json` |
| `convert` | the 211 -> 241 format converters, ported from `rev241/tools` with the same behaviour, plus `SelfCheck` |
| `cli` | `GadConv` (main), `Inspect`, `Diff`, `BuildCacheU`, `Pipeline`, and one class per remaining command |
| *area packages* | each area's `Step`s, in a package of its own: `anim`, `model`, `config`, `sprite` (sprites, fonts, mapscene), `db`, `map`, `ui` (GadScape's own UI), `uimerge` (three-way-merged vanilla UI), `rebrand`, `verify` |
| `dev/` | `compile.sh` and `run.sh`, for working without Gradle |
| `src/main/resources/ui-merge/` | three-way-merged UI groups stored as data (spec policy 8) |

## Core concepts

**`Ctx`** is everything a step gets:

* `ctx.base`, `ctx.user`, `ctx.van211`, `ctx.old241` are the inputs, opened read-only (`Cache.openCache(dir, true)`)
  as `JagCache`. They are never written.
* `ctx.out` is the `OutCache` being built.
* `ctx.ids` is the `IdMap`.
* `ctx.report` is the `Report`.
* `ctx.opt` holds the `Options`.
* `ctx.origin(kind, id)` classifies a USER entry against VAN211 as VANILLA, USER_CHANGED, USER_ADDED, USER_REMOVED
  or ABSENT.
* `ctx.xteas()` returns the map keys, `ctx.ref()` the hand-built cacheU (for comparisons only), and
  `ctx.work("area")` a scratch directory.

**`CacheView`** is the read API shared by all caches. Each `group(archive, id)` call returns a `GroupData`: the
group's files, sorted by id, with their name hashes and decoded bytes. Other helpers:

* `config(cfgGroup, id)` and `configRef(...)` read a config entry. `config` copies the bytes; `configRef` does not,
  so treat its result as read-only.
* `single(archive, id)` returns file 0 of a group (models, scripts).
* `groupIds`, `fileIds`, `hasGroup`, `hasFile`, `groupIdByName`, `usesNames` describe what a cache holds.
* `release(archive)` drops the decoded groups cached for one archive. Call it after walking a big archive.

`JagCache` reads containers straight from the index (decrypting with XTEA, then decompressing and splitting them),
so mgi `Group` objects are never loaded or mutated. Decoded groups go into an LRU of soft references (1/8 of
the heap, at most 512 MB per cache).

**`OutCache`** is the output:

* **Start.** `build-cacheu` copies BASE's `main_file_cache.*` files into `--out`. It refuses any output directory
  that is, contains or lies inside an input. It replaces an existing output cache only with `--force`.
* **Write API.** `putGroup`, `putSingle`, `putFile`, `putConfig`, `removeFile`, `removeGroup` and `forceName`.
  Every read through `ctx.out` already sees the pending changes.
* **`commit()`** writes the pending changes in a fixed order (archive id, then group id, ascending). Then it trims
  the zero padding mgi's packer leaves after reference tables, which the strict checker reports, and reopens the
  view. The pipeline commits after every step.
* **Pending changes stay in memory until commit.** mgi's `Index` reads go through a snapshot of the idx and data
  files taken when the cache is opened, so reading back a group written in the same session returns stale data.
* **Group names**, in archives that use names (scripts, sprites, fonts, music, ...):
  * A group that replaces an existing one keeps the existing name hash, unless you call `forceName`.
  * A new group takes the name its `GroupData` carries. A new group with no name in such an archive is reported
    as a warning, so carry the source group's name (`out.putGroup(arch, user.group(arch, id))` keeps it).
  * A file keeps the name of the existing file at its id. A file without a name gets the hash of "" (0), the same
    rule the old Overlay and UiOverlay tools used.
* **Limits.** `commit()` cannot create archives that BASE lacks. It deletes the empty `idx16`/`idx23` files that
  mgi's writable open creates.
* **`--dry-run`** writes nothing. Reads come from BASE plus the pending changes.

**`IdMap`** records every relocation. The kinds are listed in `Kind`: `seq`, `frame`, `framemap`, `keyframe`,
`script`, `sprite`, `dbrow`, `dbtable`, `model`, `loc`, `obj`, `npc`, `spot`, `varbit`, `varp`, `varc`, `struct`,
`enum`, `param`, `interface`, `inv`, `healthbar`.

* `record(kind, old, new, reason)` stores a relocation. Recording the same mapping again is fine; giving an id a
  different target, or reusing a target, throws.
* `alias(kind, old, existing, reason)` maps an id onto an id that already exists and is not itself moved (a user
  model identical to a BASE model at another id). Several ids may alias one target; `origin(kind, target)` still
  names the first recorded source. Aliases appear in the kind's map like other relocations and are listed under
  `_aliases`. Apply the map once, never transitively.
* `relocate(kind, old, reason)` allocates the next free id in the kind's reserved range and records it.
* `map(kind, id)` returns the new id, or the same id when the id was not moved. `mapFrameRef(group << 16 | file)`
  maps a seq frame reference.
* `allocate` hands out an id only when it is unused in BASE, USER and OUT, and not already allocated, reserved or
  targeted.

The reserved ranges come from the spec, corrected where the 241 client or protocol cannot carry the spec's ids:

| kind | range | note |
|---|---|---|
| seq | 40000-49999 | |
| frame (archive 0 groups) | 30000-32767 | |
| framemap | 30000-32767 | |
| keyframe | 20000-29999 | |
| script | 47000-47999 | |
| sprite | 20000-29999 | |
| dbrow | 60000-64999 | |
| dbtable | 4000-4999 | added; used only when a user table id clashes with BASE (none today) |
| model | BASE max + 1 to 65535 (62776-65535 on 2746) | not 100000-199999: the 241 client's JS5 requests and replies carry 16-bit group ids, so model 100000 would be requested as sprite group 34464. `Ctx.open` sets the range from BASE (`Kind.modelRange`). After this build 1,758 ids remain, and the next stock cache needs them too. |
| loc | 64500-65534 | not 70000-79999: loc ids are u16 on the 241 wire (rsprot `OpLoc1V2Decoder` g2, `LocAddChangeV2Encoder` p2) and in multiloc transform lists |
| varbit | 30000-34999 | |
| varp | 9000-9999 | |
| varc | 2016-2047 | set by configs-plan: the 241 client sizes its varc table from the config-19 file count |
| obj | 60000-64999 | set by configs-plan: obj ids are u16 in inventories and cert links; seq hand objs allow at most 65023 |
| struct | 13000-13999 | |
| enum | 20000-20999 | |
| param | 9000-9999 | |
| interface | 5200-5299 | added (free in BASE, max 979, and USER, max 5103) |
| spot | 6100-6999 | set by configs-plan for the forced relocations (`config/relocate.tsv`); USER uses 5005, 6000-6011 and 32767 |

Kinds without a range (npc, inv, healthbar) need `setRange` before `allocate`; inv and healthbar exist so
clashing user inventories (1000-1006) and health bars (75) can move later. Today they are kept at their ids and
reported as clashes. The first pipeline step, `check-ranges`, records how much of each range BASE, USER and OUT
already use. Today USER uses 9 frame groups and 4 framemaps from 30000 up, 14 models from 63000 up, and enum 20002;
the allocator skips those ids. idmap.json has this shape:

```json
{"seq": {"15000": 40000}, "frame": {}, "...": {},
 "_reasons": {"seq": {"15000": "raid seq clash"}}, "_aliases": {"model": [58013]},
 "_vanilla": {"seq": [189, 195], "model": [292]}, "_new": {"script": {"adapter:98": 47999}, "dbrow": {"slayer-reward:53": 60587}},
 "_newReasons": {"script": {"adapter:98": "..."}}, "_ranges": {"seq": [40000, 49999]}, "_format": 1}
```

Every kind key is always present. Keys that start with `_` are metadata.

* **`_vanilla`** lists relocations that are *ports of a vanilla 211 entry Jagex reused or re-rigged by 241*: model 292
  (an NPC torso on 211, the Cat mask in 241), seqs 189/195 (the gnome walk/stand on 211, player emotes on framemap 0
  in 241), GNOME_CATCH 200 (re-rigged onto framemap 2402). GadScape content that used the 211 entry was rewritten to the
  copy; stock 241 content keeps the id. The server applies these entries only where its code means the 211 animation or
  model on GadScape's own (211-era) content, never for stock content.
* **`_new`** lists ids the build created that replace nothing, by name: the clientscript adapters for vanilla procs
  whose 241 signature changed (`adapter:TARGET`, allocated from the top of the script range), GadScape's slayer
  reward rows (`slayer-reward:INDEX`), the kit results of `config/fixes.tsv` at the ids the server already uses
  (`sksp-15:NAME`, objs 60000-60008, claimed with `IdMap.claimNew` before configs-plan allocates) and new scripts the
  server calls (`gadscape_hide_roofs` 10719). `IdMap.allocate` never hands their ids out again.
* **Vanilla UI ports** (`_vanilla` interface/script): vanilla 211 interfaces the server still opens whose id 241 gave to
  another interface (`ui.UiScope.VANILLA_PORTS`: gnome cocktail 436, the poll screens 310/345/400) and the 211 scripts
  only the server runs (`VANILLA_SCRIPT_PORTS`: poll scripts 603/604/609/610) are ported like custom UI into the
  reserved ranges; BASE keeps 241's interface/script at the id. The server constants follow the id map.

How the kinds are keyed, and what the server rewrite must know:

* **`seq`**: USER seq id -> output id. The server applies this map (for example 10000 -> 40000 and the raid clash
  15000-15162 -> 40088-40250).
* **`frame`, `framemap`**: USER archive-0 group / archive-1 group -> output id. These include the vanilla 211 frame
  sets and framemaps that ported user seqs need, because Jagex renumbered or changed them by 241.
* **`keyframe`**: a 211 archive-0 keyframe group -> an archive-22 group. It is empty on the current inputs.
* User animation assets that are taken from BASE, or not carried over, are not in the id map. Each has a
  `taken-from-base` or `dropped` entry in report.json.
* **`model`**: user model -> output id. Twins map onto an identical existing model (several may share one target,
  as aliases).
* **Config kinds** (`obj`, `struct`, `enum`, `param`, `varbit`, `varp`, `varc`, `dbrow`): apply them only to
  GadScape-era server code and data, which uses USER ids. New Raids 4 server code is written against BASE ids:
  obj 34721-34723/34726 and structs 1216-1774 mean BASE's raid content now. Only old GadScape references to them move.
* **`script`, `interface`**: custom UI at ids BASE uses (10034-10121 -> 47000+, interface 866 -> 5200). The ports of
  211 vanilla scripts (2122 -> 47034, 2174 -> 47035) apply only to server calls that mean the 211 behaviour.
* **`loc`**: user locs that BASE squares place (64500+). The map squares written by `maps` already use the new ids.

**Rev-225 imports** (`core.Rev225`, resource `config/rev225.tsv`): GadScape's DataMigration copied Jagex content from
rev 225 (OpenRS2 1943) into its 211 cache. Those entries are absent from VAN211, so they look user-added, but BASE holds
Jagex's newer version: the converter treats them as vanilla (BASE's entry, BASE's models), and re-applies a user edit of
one onto BASE's entry (Duke Sucellus 12166 "Attack", locs 47567/47568 "Take"). The table is generated from the rev-225
evidence cache by `dev/run.sh config.Rev225Gen` and keyed by the CRC of the USER bytes, so a later user edit makes the
entry user content again. `--rev225.table=FILE|none` replaces it.

**`Report`** writes report.json. Log one entry per decision:

* `ctx.report.add(Decision.USER_ADDED, Kind.OBJ, id, "detail")`
* `relocated(kind, from, to, why)`
* `note`, `warn` and `error`

For bulk outcomes, use `count("key", n)` instead. The decisions are `taken-from-base`, `user-added`,
`user-changed`, `merged`, `relocated`, `clash-kept`, `same`, `dropped`, `rebranded`, `note`, `warning` and `error`.
The report holds no timestamps, so it is deterministic too. `build-cacheu` exits with code 1 if a step reported an
error.

## The converters (`convert`)

Each converter was ported from the old one-off tools with the same behaviour:

| class | what | ported from |
|---|---|---|
| `ConfigConv.loc/npc/seq/item/kit/spot` | config walkers that read exactly what the 211 client reads. They widen model ids to the int opcodes, renumber 211 opcodes (npc 111 -> 122; seq sounds, skeletal fields, range and masks), add the loc retain byte, and throw on unknown opcodes. `forGroup(cfg)` picks the walker for a config group. | `Conv` |
| `InterfaceConv.component/if1/group` | widen IF3/IF1 type-6 model ids to ints | `Conv.component`, `PortIf` |
| `ScriptTrailer` | `to241` (rev-237 trailer), `calls` (gosub targets and redirects), `adapter`, `args211`/`args241`, `verify241` (read-back check) | `PortIf`, `Overlay` |
| `Cs2` | instruction model: `parse211`/`parse241`, `splice` (keeps jumps and switches), `write241` | `CsRewrite` |
| `ScriptConv.convertAll` | full script conversion: arrays to object locals, removed commands, `cc_create` extra arg, hook descriptors | `CsAll` |
| `ModelConv.convert/toRev241`, `ModelTail` | old and FF FF encodings to FF FE / FF FD, the face-depth tail, and a decode-equality check | `ModelConv`, `ModelTail`, `MExtra` |
| `TrimPad` | remove the zero padding the strict verifier reports | `TrimPad` |
| `MapConv` | 211 named squares (`m`/`l` x_y, editor-plain or XTEA locs) to the 241 terrain byte | `PortMaps`, `Overlay.maps` |

**Golden check.** `SelfCheck` (`dev/run.sh convert.SelfCheck [--full]` or `gadconv selfcheck`) converts USER and
compares the result with cacheU, where the old tools wrote these exact bytes. On the current inputs:

* 30,938 objs, 14,354 NPCs, 55,434 locs, 12,065 seqs, 153 kits, 3,021 spotanims, all 7,512 scripts and all 56,359
  models are byte-identical.
* All 24,510 interface components match too: 381 of them only after the zero-padding trim that TrimPad applied to
  cacheU.

## How to add a Step

Put the class in your area's package (`com.gadscape.cacheconv.<area>`):

```java
package com.gadscape.cacheconv.anim;

import com.gadscape.cacheconv.core.*;
import com.gadscape.cacheconv.convert.ConfigConv;

public final class UserSeqsStep implements Step {
    @Override public String name() { return "user-seqs"; }

    @Override public void run(Ctx ctx) {
        for (int id : ctx.user.configIds(Arch.CFG_SEQ)) {                     // ascending: deterministic
            Origin o = ctx.origin(Kind.SEQ, id);
            if (!o.isUser()) continue;                                         // vanilla: BASE's version stays
            byte[] conv = ConfigConv.seq(id, ctx.user.configRef(Arch.CFG_SEQ, id));
            int to = id;
            if (ctx.base.hasConfig(Arch.CFG_SEQ, id)) {                        // clash with a BASE seq
                to = ctx.ids.relocate(Kind.SEQ, id, "user seq clashes with BASE");
                ctx.report.relocated(Kind.SEQ, id, to, "clash");
            } else ctx.report.add(o == Origin.USER_ADDED ? Report.Decision.USER_ADDED : Report.Decision.USER_CHANGED, Kind.SEQ, id, null);
            ctx.out.putConfig(Arch.CFG_SEQ, to, conv);
        }
    }
}
```

Then the integrator adds `s.add(new UserSeqsStep());` to `cli/Pipeline.steps()` in the right order.

Rules for steps:

* Iterate ids in ascending order. Never depend on hash order, time or the environment.
* Write only through `ctx.out`, and record every moved id in `ctx.ids`.
* Rewrite references with `ctx.ids.map(...)` in a later step. Steps that move ids must run before the steps that
  rewrite the references.
* Call `release(archive)` after walking a big archive (models, frames, maps).

## The build-cacheu pipeline

`cli/Pipeline.steps()` lists the steps in run order. All of them share one `IdMap`: every step that moves ids runs
before the steps that write references to those ids, and those steps map the references through `ctx.ids`.

| # | step | package | what | options |
|---|---|---|---|---|
| 1 | `check-ranges` | `cli` | how much of each reserved range BASE, USER and OUT use | |
| 2 | `ui-relocate` | `ui` | custom scripts/interfaces at ids BASE uses move (47000+, 5200+); reserves undefined var ids custom UI uses inside reserved ranges (varp 9998), so it runs before any var allocation | `--ui.interfaceRange=LO-HI` |
| 3 | `fonts` | `sprite` | user fonts (all vanilla today: BASE's) | |
| 4 | `sprites` | `sprite` | user sprites; GadScape art at ids BASE's UI uses moves to 20000+; edited vanilla sprites are merged frame by frame | `--sprites.sameThing` |
| 5 | `db-plan` | `db` | user dbrows at BASE ids (whole tables) move to 60000+ in order | `--db.tableRange` |
| 6 | `models` | `model` | user models; clashes move to the top of the model range or onto an identical twin; vanilla / rev-225 models GadScape content uses whose id Jagex reused are ported (`_vanilla`); a user edit of a vanilla model that BASE-only content also uses moves, its 211-era users follow it | `--models-range=LO-HI`, `--models-pack=top\|bottom`, `--models-dedup`, `--models-changed=auto\|relocate\|override`, `--models-prev-idmap=FILE`, `--models-census` |
| 7 | `configs-plan` | `config` | UI configs at BASE ids, Raids 4 protected ids, GadScape vars without a USER definition; forced relocations (`config/relocate.tsv`: the ankou set boxes off the castle puzzle pieces 2749-2758, GadScape spotanims off the Leviathan/Vardorvis VFX 2500-2515; locs at the end of maps-plan), after the rule-based moves so earlier ids stay put; the new objs of `config/fixes.tsv` claimed first | `--configs.range.KIND=LO-HI`, `--configs.portRemovedVanilla`, `--configs.serverVarps` |
| 8 | `maps-plan` | `map` | user locs that BASE squares place move to 64500+; then the forced loc relocations of `config/relocate.tsv` (Clan Wars FFA 50081) | `--maps.locRange=LO-HI` |
| 9 | `anim` | `anim` | seqs, frames (archive 0), framemaps (1), keyframes (22); BASE's groups never change; hand objs follow configs-plan; vanilla seqs whose BASE record animates another skeleton are ported (`_vanilla`); user frames byte-identical to BASE frames keep BASE's framemap | `--anim.takeBase=IDS`, `--anim.portRemovedVanilla`, `--anim.portRiggedVanilla`, `--anim.forcePort` |
| 10 | `configs` | `config` | archive 2 except seqs: vanilla = BASE, user entries converted, references mapped, three-way merges, enum unions, varc/varp slots | `--configs.varcSlots`, `--configs.varpSlots`, `--configs.lossy`, `--configs.enumRemovals` |
| 10b | `config-sprites` | `sprite` | vanilla 211 sprites BASE deleted that the written configs still name are copied from USER (enum 11001 -> sprite 4567) | |
| 10c | `config-fixes` | `config` | data-driven fixes of GadScape configs (`config/fixes.tsv`: placeholder names/links and typos, wearpos, two-tone ankou models, ankou set boxes, NR-letter recolours, Hagavik's stand/walk, kit result objs, spec cost/text of weapon copies, enum components on the old duel/tournament interfaces, the category spells' rune params); each result must pass the server's strict decoder | `--configs.fixes=FILE\|none` |
| 11 | `mapscene` | `sprite` | mapscene sheet: BASE's plus custom frames user locs use (none today) | `--mapscene.merge=auto\|never` |
| 12 | `ui-merge` | `uimerge` | the 14 interfaces and 31 scripts the user modified, three-way merged (data in `resources/ui-merge`) | `--uimerge.data=DIR` |
| 13 | `ui-convert` | `ui` | GadScape's own interfaces and scripts converted and written at the ids ui-relocate chose; vanilla UI ports (`_vanilla`) | `--ui.copyAssets`, `--ui.componentRemap=TSV`, `--ui.fixups`, `--ui.vanillaPorts=false` |
| 13b | `ui-fixes` | `ui` | QA fixes of GadScape UI: component edits (`ui/fixes.tsv`: Challenges frame hooks to onLoad, Blackjack fonts/layout, duel level boxes, broken hook calls), script recipes located by anchors (10593 bails on -1, 12576 staff debug line, 205 cc_create on a text component), new scripts (`ui/scripts/`: hide roofs 10719) | `--ui.fixesTable=FILE\|none` |
| 13c | `asset-fixes` | `sprite` | redrawn sprite frames (`sprite/replace.tsv`: the "G" credits coin for 5265 -> 20011) and the user-changed binary archive groups (GadScape's `title.jpg`) | `--assets.binary=false` |
| 14 | `maps` | `map` | user squares from the stored allow-list, loc ids mapped | `--maps.mode=merge\|overlay` |
| 14b | `db-slayer-rewards` | `db` | GadScape's own slayer rewards (USER enum 834 keys VAN211 lacks: 'Slayer Statues') as rows of 241's reward table 117, reward id from 95 down | |
| 15 | `db-index` | `db` | archive 21 rebuilt from the rows written | `--db.syncObjCopies` |
| 16 | `rebrand` | `rebrand` | branding replacement table (resource `rebrand/table.tsv`, the branding area's REBRAND_TABLE) applied to config, IF3/IF1 interface and script strings | `--rebrand.table=FILE\|none` |
| 16b | `models-prune` | `model` | vanilla model ports nothing in the finished output references are removed again (id freed, mapping dropped) | `--models.prune` |
| 17-19 | `configs-oracle`, `configs-refcheck`, `configs-expect` | `config` | the server's strict decoders on every written entry; references resolve; audit expectations | |
| 19b | `area-expect` | `verify` | the content areas' CACHE_NEEDS as data (`verify/area-expect.tsv`): ids that must be BASE's, relocated or present, and the RuneLite overlay hashes (`verify/runelite-overlays.tsv`) | |
| 20 | `models-refcheck` | `model` | every model reference resolves; nothing still points at a moved model; user entries written with their own model lists show the models the user saw (decode-equal) | |
| 21 | `anim-refcheck` | `anim` | frames/framemaps/keyframes resolve, BASE's animation data unchanged, BOSS-X1 lists, NPC/spotanim seqs; user NPC/spotanim seqs animate the skeleton they had in USER | |
| 22 | `ui-check` | `ui` | custom scripts/interfaces read back; gosub targets and arg counts; assets exist | |
| 23 | `verify` | `verify` | the whole-cache verifier with BASE as baseline (refs, anims, interfaces, maps, scripts, brand, strict) | `--verify.checks=a,b`, `--verify.oracle`, `--brand-errors` |

The checks report one error per failing check, so `build-cacheu` exits 1 on a broken output. Bad items listed in
`src/main/resources/verify/known-exceptions.tsv` (`CHECK<TAB>KEY_REGEX<TAB>REASON`) are documented exceptions: they
are reported with their reason but are not errors. Each one must say why it is acceptable; today every row is a
defect already present in GadScape's 211 cache that the converter carries over unchanged (for example script 2186
calling 2194 without its two arguments, and the user NPCs whose seqs 15170-15253 have no frames in USER). Fix them in
the 211 source and delete the row. `--verify.known=FILE|none` (pipeline) or `--known=FILE|none` (verify command)
replaces the list. Every step writes its
details to `WORK/<area>/` (`--work DIR`; for example `configs/clashes.tsv`, `models/moves.tsv`, `maps/loc-moves.tsv`,
`sprites-db/dbrow-moves.tsv`, `verify/verify.json`).

## Running

Without Gradle (fast iteration):

```bash
dev/compile.sh                    # core + convert
dev/compile.sh anim cli           # ... plus packages anim and cli (dirs under com/gadscape/cacheconv), or "all"
dev/run.sh cli.GadConv inspect user cfg obj 34715
dev/run.sh convert.SelfCheck
```

* `compile.sh` compiles against `$GADCONV_ROOT/rev241/run/server241.jar` into `$GADCONV_CLASSES` (default
  `$GADCONV_ROOT/gadconv-work/classes`; compiles are serialized with a lock).
* `GADCONV_ROOT` is found by searching upwards from the module for `rev241/run/server241.jar`, or you set it
  yourself.
* `run.sh` passes `-Dgadconv.root`, so every input path defaults to the standard layout:

| input | path under the root |
|---|---|
| BASE | `audit/raids4/c2746/cache` |
| USER | `rev241/inputs/gadcache/x/cache/data/cache` |
| VAN211 | `c211/cache` |
| OLD241 | `osrs241/cache` |
| cacheU (reference) | `rev241/inputs/cacheU` |
| xteas | `rev241/inputs/gadcache/x/cache/data/objects/xteas.json` |
| default output | `rev241/inputs/cacheV` |

* JVM options come from `GADCONV_JAVA_OPTS` (default `-Xmx3g`).

With Gradle (final build, serialized on the shared box):

```bash
gbuild.sh <server dir> :cache-convert:build --offline        # compile + unit tests (+ golden test with -Dgadconv.root)
gbuild.sh <server dir> :cache-convert:installDist --offline  # build/install/gadconv/bin/gadconv
```

Commands:

```text
gadconv inspect [CACHE] [ARCHIVE [GROUP [FILE]]]     archives / groups / files / hex  (CACHE = base|user|van211|old241|ref|out|DIR)
gadconv inspect CACHE cfg KIND ID [--hex]            a config entry (KIND = obj, npc, loc, seq, enum, 10, ...)
gadconv inspect CACHE name ARCHIVE NAME              group id of a named group
gadconv diff A B [ARCHIVE...] [--content] [--ids]    group-by-group comparison (configs per file)
gadconv build-cacheu [--base --user --van211 --out DIR] [--force] [--dry-run] [--only a,b] [--skip a,b]
gadconv selfcheck [--full]                           converters vs cacheU
gadconv port-interface --from=DIR211 [--into=DIR241] --out=DIR [--interfaces=1600,1700-1702] [--scripts=..] [--idmap=..]
gadconv port-map --squares=R1,R2|x_y|custom [--from=DIR] [--from-xteas=FILE] --out DIR [--env=neighbour|keep|REGION] [--idmap=FILE]
gadconv port-map --region=R --terrain-file=F --locs-file=F [--pre209] --out DIR
gadconv rebrand CACHE [--rebrand.table=FILE] [--out DIR --force]   (without a table or --out: lists the branding hits)
gadconv verify [CACHE] [--checks=refs,anims,interfaces,maps,scripts,brand,oracle,strict,determinism]
               [--baseline=base|none|DIR] [--compare=DIR] [--json=FILE] [--brand-errors] [--known=FILE|none]
gadconv port ...                                     (stub: single config entries; use build-cacheu or port-interface/port-map)
```

Determinism after two builds: `gadconv verify OUT1 --checks=determinism --compare=OUT2 --baseline=none`.

**Rebrand table** (`--rebrand.table=FILE`, else the resource `rebrand/table.tsv`, a copy of the branding area's
REBRAND_TABLE.tsv): UTF-8, one rule per line, `FROM<TAB>TO[<TAB>FLAGS]` with flags `i` (ignore case) and `w` (whole
words); `#` starts a comment. Rules are literal and applied in order. Config strings are rewritten only when the entry
still parses to the same fields and references; IF1 component texts are rewritten for the types `rebrand.If1Text`
walks (others are reported); script names are never rewritten. The verify brand check scans raw bytes (`BrandScan`)
and every decoded string (`StringScan`: config strings through the server decoders, IF3/IF1 texts, script constants;
near.?reality, osnr, roat, rune spawn and the whole word NR); with a table the hits are errors, and report.json has the
scan result under `inputs.brandScan`.

`build-cacheu` copies BASE to `--out` and runs `Pipeline.steps()`, committing after each step. Then it writes
`idmap.json` and `report.json` to `--report-dir`, which defaults to the output directory (`WORK/dry-run` with
`--dry-run`). Run it twice and compare `sha256sum out/main_file_cache.*` to check determinism. Command-specific
flags are written `--name=value` or `--name`; read them with `o.get("name", def)` or `o.flag("name")`.

## Caveats

* Reference tables written by mgi `Archive.pack` are padded. `OutCache` trims them on commit.
* `Cs2.BRANCH` holds every relative-jump opcode: 6-10, 31, 32 and build 241's long compares 68-73 (they pop two
  longs; stock script 778 uses them). `splice` keeps all of them pointing at the same instruction.
* `MapConv.terrain211`/`locs211` are deprecated: `groupIdByName` returns an arbitrary group when a square name is
  duplicated (84 in USER). Use `map.NamedMaps`.
* Facts the verify area established on stock 2746, which script rewriting relies on: db column refs
  (`db_find` 7500/7507, `db_getfield` 7502 constants) are `table << 12 | column << 4 | t` (t = 0: the whole tuple,
  t = n: element n - 1); hook descriptor chars `X`, `W` and `*` are object-stack arguments, like `s`; 1703/2703
  (`cc_param`/`if_param`) and 1704/2704 (`cc_setparam`/`if_setparam`) push/pop a value typed by their param constant;
  210 (`cc_find` by params) pops a value only for params that are not -1; arrays are objects in object locals (44
  `define_array` operand = local << 16 | element type; 45/46 operand = local) and 8000-8027 are the array commands.
* `JagCache` reports a map group that fails to decode without a key as an `IllegalStateException`;
  `group(arch, id, xtea)` returns null instead. `MapConv.locs211` tries the plain group first, then the key.

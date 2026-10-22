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
| *area packages* | each area's `Step`s, in a package of its own (for example `anim`, `maps`, `ui`, `raids`, `rebrand`, `verify`) |
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
`enum`, `param`, `interface`.

* `record(kind, old, new, reason)` stores a relocation. Recording the same mapping again is fine; giving an id a
  different target, or reusing a target, throws.
* `relocate(kind, old, reason)` allocates the next free id in the kind's reserved range and records it.
* `map(kind, id)` returns the new id, or the same id when the id was not moved. `mapFrameRef(group << 16 | file)`
  maps a seq frame reference.
* `allocate` hands out an id only when it is unused in BASE, USER and OUT, and not already allocated, reserved or
  targeted.

The reserved ranges come from the spec:

| kind | range |
|---|---|
| seq | 40000-49999 |
| frame (archive 0 groups) | 30000-32767 |
| framemap | 30000-32767 |
| keyframe | 20000-29999 |
| script | 47000-47999 |
| sprite | 20000-29999 |
| dbrow | 60000-64999 |
| model | 100000-199999 |
| loc | 70000-79999 |
| varbit | 30000-34999 |
| varp | 9000-9999 |
| struct | 13000-13999 |
| enum | 20000-20999 |
| param | 9000-9999 |

Kinds without a range need `setRange` before `allocate`. The first pipeline step, `check-ranges`, records how much
of each range BASE, USER and OUT already use. Today USER uses 9 frame groups and 4 framemaps from 30000 up, and enum
20002; the allocator skips those ids. idmap.json has this shape:

```json
{"seq": {"15000": 40000}, "frame": {}, "...": {},
 "_reasons": {"seq": {"15000": "raid seq clash"}}, "_ranges": {"seq": [40000, 49999]}, "_format": 1}
```

Every kind key is always present. Keys that start with `_` are metadata.

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
gadconv port | port-interface | port-map | rebrand | verify    (stubs, owned by their areas)
```

`build-cacheu` copies BASE to `--out` and runs `Pipeline.steps()`, committing after each step. Then it writes
`idmap.json` and `report.json` to `--report-dir`, which defaults to the output directory (`WORK/dry-run` with
`--dry-run`). Run it twice and compare `sha256sum out/main_file_cache.*` to check determinism. Command-specific
flags are written `--name=value` or `--name`; read them with `o.get("name", def)` or `o.flag("name")`.

## Caveats

* Reference tables written by mgi `Archive.pack` are padded. `OutCache` trims them on commit.
* For scripts read with `parse241`, `Cs2.BRANCH` covers the classic branch opcodes (6-10, 31, 32). Check
  this before you splice 241 scripts that use newer branch opcodes.
* `JagCache` reports a map group that fails to decode without a key as an `IllegalStateException`;
  `group(arch, id, xtea)` returns null instead. `MapConv.locs211` tries the plain group first, then the key.

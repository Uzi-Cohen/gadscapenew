package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.config.CfgCodec.Entry;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * configs: writes archive 2 (every config group except seq) on top of BASE, after {@link ConfigPlanStep} and every
 * step that moves ids configs refer to (models, anim, the UI and maps planners).
 * <p>
 * Per id of USER and VAN211 (ascending), USER compared with VAN211:
 * <ul>
 *   <li><b>vanilla</b>: BASE's entry stays. If BASE no longer has the id, the converted VAN211 entry is ported at its
 *       id ({@code --configs.portRemovedVanilla=false} to skip; groups without a walker are never ported). A vanilla
 *       copy {@link ConfigPlanStep} moved (BASE replaced it) is written at its new id.</li>
 *   <li><b>user removed</b>: BASE's entry stays.</li>
 *   <li><b>user added / changed</b>: converted to 241 and every reference mapped through the id map (models, seqs,
 *       objs, npcs, locs, varbits, varps, structs, enums, params, sprites, dbrows, interfaces; typed enum/param/dbrow
 *       values included). Then:
 *     <ul>
 *       <li>moved (plan, maps): written at the new id, BASE keeps the old one;</li>
 *       <li>BASE lacks the id: written at it;</li>
 *       <li>added, BASE has the same thing ({@link CfgModel#same}): BASE's entry stays;</li>
 *       <li>added, BASE has something else: the USER entry is written at the id and listed as a clash (entity
 *           configs only; UI configs were moved by the plan);</li>
 *       <li>changed: BASE's entry plus the user's edit ({@link Merge3}; enums {@link EnumMerge}, spec rule 10);
 *           an obj/npc/loc the user turned into something else (its name changed) is a clash instead: the
 *           USER entry is kept as it is and listed.</li>
 *     </ul></li>
 * </ul>
 * Then: client-variable slots (rule 11, VarcFix: plain entries for every varc id below {@code --configs.varcSlots},
 * default 2048, that has none), varp slots (VarpSlots: plain entries below {@code --configs.varpSlots}, default 65536,
 * because the GadScape server sends varps up to 19453 and the 241 client sizes its varp table from config 16), the
 * dbtable index (archive 21) of the user's tables with moved row ids, and a varbit overlap check (user varbits that
 * share varp bits with a BASE varbit). Decisions go to report.json; clashes and merges also to
 * {@code WORK/configs/clashes.tsv} and {@code merged.tsv}.
 */
public final class ConfigStep implements Step {
    public static final String NAME = "configs";

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) throws IOException {
        W w = new W(ctx, new CfgModel(ctx));
        for (int g : w.m.groups()) w.group(g);
        w.followModels();
        w.implicitVarTargets();
        w.varcSlots();
        w.varpSlots();
        w.dbIndex();
        w.varbitOverlaps();
        w.finish();
    }

    static final class W {
        final Ctx ctx;
        final CfgModel m;
        final CfgCodec.Mapper mapper;
        final boolean portRemovedVanilla;
        final TreeMap<String, Long> counts = new TreeMap<>();
        final List<String> clashes = new ArrayList<>(), merged = new ArrayList<>(), imports225 = new ArrayList<>();
        final TreeMap<Integer, List<Integer>> removedVanillaPorted = new TreeMap<>(), userRemoved = new TreeMap<>();

        W(Ctx ctx, CfgModel m) {
            this.ctx = ctx; this.m = m; this.mapper = m.mapper();
            this.portRemovedVanilla = Boolean.parseBoolean(ctx.opt.get("configs.portRemovedVanilla", "true"));
        }

        void count(int g, String what) { counts.merge(Arch.configName(g) + "." + what, 1L, Long::sum); }

        String kindKey(int g) { Kind k = CfgModel.kindOf(g); return k != null ? k.key : Arch.configName(g); }

        void write(int g, int id, byte[] b) {
            byte[] cur = ctx.out.configRef(g, id);
            if (cur != null && Arrays.equals(cur, b)) return;
            ctx.out.putConfig(g, id, b);
        }

        void group(int g) {
            Kind k = CfgModel.kindOf(g);
            TreeSet<Integer> ids = new TreeSet<>();
            for (int id : m.user.configIds(g)) ids.add(id);
            for (int id : m.van.configIds(g)) ids.add(id);
            for (int id : ids) {
                try {
                    one(g, k, id);
                } catch (RuntimeException e) {
                    ctx.report.error(kindKey(g), id, "not written: " + e.getMessage());
                    count(g, "failed");
                }
            }
            m.user.release(Arch.CONFIGS);
            m.van.release(Arch.CONFIGS);
        }

        void one(int g, Kind k, int id) {
            Origin o = m.origin(g, id);
            if (k != null && ctx.ids.isAlias(k, id)) {
                // a GadScape stand-in mapped onto BASE's native entry by configs-plan: BASE's stays, nothing written
                count(g, "stand-in-base-kept");
                ctx.report.add(Decision.SAME, kindKey(g), id, "stand-in for BASE " + Arch.configName(g) + " " + ctx.ids.map(k, id) + ": " + ctx.ids.reason(k, id));
                return;
            }
            int target = k == null ? -1 : ctx.ids.lookup(k, id);
            if (o.isUser()) {
                com.gadscape.cacheconv.core.Rev225.Status st = ctx.rev225.config(g, id, m.user.configRef(g, id));
                if (st == com.gadscape.cacheconv.core.Rev225.Status.IMPORT && rev225Import(g, k, id, o, target)) return;
                if (st == com.gadscape.cacheconv.core.Rev225.Status.EDIT && o == Origin.USER_ADDED && target == -1 && rev225Edit(g, id)) return;
            }
            switch (o) {
                case VANILLA -> vanilla(g, k, id, target);
                case USER_REMOVED -> { count(g, "user-removed-base-kept"); userRemoved.computeIfAbsent(g, x -> new ArrayList<>()).add(id); }
                case USER_ADDED, USER_CHANGED -> user(g, k, id, o, target);
                default -> { }
            }
        }

        void vanilla(int g, Kind k, int id, int target) {
            if (target != -1) {
                Entry v = CfgCodec.map(m.vanEntry(g, id), mapper);
                write(g, target, v.encode());
                count(g, "vanilla-copy-written-relocated"); // the relocation itself was reported by configs-plan
                return;
            }
            if (m.base.hasConfig(g, id)) { count(g, "vanilla-base-kept"); return; }
            if (!portRemovedVanilla || !CfgCodec.walked(g)) { count(g, "vanilla-missing-in-base-not-ported"); return; }
            Entry v = CfgCodec.map(m.vanEntry(g, id), mapper);
            write(g, id, v.encode());
            count(g, "vanilla-missing-in-base-ported");
            removedVanillaPorted.computeIfAbsent(g, x -> new ArrayList<>()).add(id);
        }

        /**
         * A rev-225 import (Jagex content GadScape's DataMigration copied, {@link com.gadscape.cacheconv.core.Rev225}):
         * BASE's newer entry stays, like for a vanilla entry. Returns false when the USER entry must be written
         * after all (BASE lacks the id, or a planner moved it: maps-plan does when the user's own squares place it).
         */
        boolean rev225Import(int g, Kind k, int id, Origin o, int target) {
            if (target != -1 || !m.base.hasConfig(g, id)) return false;
            count(g, "rev225-import-base-kept");
            Entry u = m.userEntry(g, id), b = m.baseEntry(g, id);
            if (CfgModel.same(g, u, CfgCodec.map(u, mapper), b).same()) return true;
            count(g, "rev225-import-base-newer");
            String d = "rev-225 import (Jagex content GadScape imported" + (o == Origin.USER_CHANGED ? " over the 211 entry" : "") + "): BASE's newer "
                    + describe(g, b) + " replaces the 225 copy " + describe(g, u);
            ctx.report.add(Decision.TAKEN_FROM_BASE, kindKey(g), id, d);
            imports225.add(Arch.configName(g) + "\t" + id + "\t" + tsv(describe(g, u)) + "\t" + tsv(describe(g, b)));
            return true;
        }

        /**
         * The user edited a rev-225 import that BASE still has as the same thing (Duke Sucellus 12166 got "Attack", the
         * Pestle and mortar 47567 and Pickaxe 47568 got "Take"): the edit (rev 225 -> USER) is re-applied onto BASE's
         * entry. Fields the user's entry lacks are not removed (GadScape's encoders drop fields they cannot write).
         * Returns false when BASE turned the id into something else (the ordinary clash rules apply then).
         */
        boolean rev225Edit(int g, int id) {
            Entry b = m.baseEntry(g, id);
            if (b == null) return false;
            byte[] rb = ctx.rev225.editBase(g, id, m.user.configRef(g, id));
            Entry r = CfgCodec.parse(g, id, CfgModel.conv(g, id, rb), m.userTypes), u = m.userEntry(g, id);
            if (!Rev225Gen.sameThing(g, r, b)) return false;
            Merge3.Result mr = Merge3.merge(g, r, u, b, mapper, m.lossy(g), false);
            byte[] out = mr.entry.encode();
            if (Arrays.equals(out, b.encode())) {
                count(g, "rev225-edit-already-in-base");
                ctx.report.add(Decision.SAME, kindKey(g), id, "user edit of a rev-225 import adds nothing to BASE's entry");
                return true;
            }
            write(g, id, out);
            count(g, "rev225-edit-merged");
            String detail = "user edit of a rev-225 import re-applied on BASE's entry: applied " + mr.applied + (mr.conflicts.isEmpty() ? "" : "; USER value wins over BASE for " + mr.conflicts);
            ctx.report.add(Decision.MERGED, kindKey(g), id, detail);
            merged.add(Arch.configName(g) + "\t" + id + "\tmerged-rev225-edit\t" + tsv(detail));
            return true;
        }

        /** Groups whose entries are things the client shows (an empty entry there is a placeholder, not content). */
        static boolean isShownThing(int g) {
            return g == Arch.CFG_OBJ || g == Arch.CFG_NPC || g == Arch.CFG_LOC || g == Arch.CFG_SPOT || g == Arch.CFG_IDENTKIT || g == Arch.CFG_HEALTHBAR || g == Arch.CFG_HITSPLAT;
        }

        void user(int g, Kind k, int id, Origin o, int target) {
            String key = kindKey(g);
            Entry u = m.userEntry(g, id);
            if (u.padding > 0) count(g, "user-zero-padding-trimmed");
            Entry um = CfgCodec.map(u, mapper);
            if (target != -1) {
                write(g, target, um.encode());
                count(g, "written-relocated");
                return; // the relocation itself was reported by the step that planned it
            }
            Entry b = m.baseEntry(g, id);
            if (b != null && o == Origin.USER_ADDED && isShownThing(g) && !u.opaque && u.items.isEmpty()) {
                // GadScape's editors leave empty entries ("00") up to the last id they wrote; BASE's real entry stays
                count(g, "empty-user-placeholder-base-kept");
                ctx.report.add(Decision.SAME, key, id, "empty USER placeholder (no fields): BASE's " + describe(g, b) + " kept");
                return;
            }
            if (b != null && g == Arch.CFG_PARAM && m.intRetyped.contains(id)) {
                Entry v0 = m.vanEntry(g, id);
                if (CfgModel.sameEffective(g, CfgModel.withTypeOf(u, v0), v0)) {
                    count(g, "int-retype-base-kept");
                    ctx.report.add(Decision.SAME, key, id, "the user only re-typed vanilla param " + id + " between int types (" + (char) ParamTypeTable.typeOf(m.vanBytes(g, id)) + " -> "
                            + (char) ParamTypeTable.typeOf(m.userBytes(g, id)) + "): the same param, BASE's definition kept and the user's values read with BASE's type");
                    return;
                }
            }
            if (b == null) {
                write(g, id, um.encode());
                count(g, o == Origin.USER_ADDED ? "user-added" : "user-changed-base-deleted");
                ctx.report.add(o == Origin.USER_ADDED ? Decision.USER_ADDED : Decision.USER_CHANGED, key, id,
                        o == Origin.USER_ADDED ? describe(g, um) : "vanilla id BASE no longer has; user version written");
                return;
            }
            if (o == Origin.USER_ADDED) {
                CfgModel.Same s = CfgModel.same(g, u, um, b);
                if (s.same()) {
                    count(g, "same-as-base");
                    ctx.report.add(Decision.SAME, key, id, s.why());
                    return;
                }
                write(g, id, um.encode());
                count(g, "clash-kept");
                String d = "USER " + describe(g, um) + " kept over BASE " + describe(g, b);
                if (CfgModel.isUiKind(k)) ctx.report.warn(key, id, "UI config clash not moved by configs-plan: " + d);
                ctx.report.add(Decision.CLASH_KEPT, key, id, d);
                clashes.add(Arch.configName(g) + "\t" + id + "\t" + tsv(describe(g, um)) + "\t" + tsv(describe(g, b)));
                return;
            }
            // user changed a vanilla entry that BASE still has
            Entry v = m.vanEntry(g, id);
            boolean jagexChanged = !CfgModel.sameEffective(g, v, b);
            if (k == Kind.OBJ || k == Kind.NPC || k == Kind.LOC) {
                // the user turned the vanilla entity into another one (castle puzzle pieces 2749-2758 -> 'ankou set'
                // store items): BASE's later fields belong to the original thing, so the USER entry is kept as it is
                String nv = CfgCodec.name(v), nu = CfgCodec.name(u);
                if (nv != null && !nv.equalsIgnoreCase("null") && !CfgModel.sameName(nv, nu)) {
                    write(g, id, um.encode());
                    count(g, "repurposed-kept");
                    String d = "user repurposed vanilla " + Arch.configName(g) + " " + id + " ('" + nv + "' -> '" + nu + "'): USER entry kept over BASE " + describe(g, b)
                            + (jagexChanged ? " (Jagex also changed it)" : "");
                    ctx.report.add(Decision.CLASH_KEPT, key, id, d);
                    clashes.add(Arch.configName(g) + "\t" + id + "\t" + tsv("repurposed: " + describe(g, um)) + "\t" + tsv(describe(g, b)));
                    return;
                }
            }
            if (u.opaque) {
                write(g, id, um.encode());
                count(g, "user-changed-opaque");
                ctx.report.add(Decision.USER_CHANGED, key, id, "no field model for " + Arch.configName(g) + ": USER entry written" + (jagexChanged ? " over a BASE entry Jagex also changed" : ""));
                return;
            }
            Entry result;
            String detail;
            List<String> conflicts;
            if (g == Arch.CFG_ENUM) {
                if (EnumMerge.isEmpty(b)) {
                    write(g, id, um.encode());
                    count(g, "user-changed");
                    ctx.report.add(Decision.USER_CHANGED, key, id, "BASE enum is empty: USER enum written");
                    return;
                }
                List<String> standIns = new ArrayList<>();
                Entry uf = EnumMerge.withoutUserPairs(u, v, (kk, val) -> ctx.ids.isAlias(kk, val), standIns);
                EnumMerge.Result r = EnumMerge.merge(v, uf, b, mapper, !"keep".equals(ctx.opt.get("configs.enumRemovals", "apply")));
                result = r.entry; detail = r.describe() + (standIns.isEmpty() ? "" : "; " + standIns.size() + " GadScape stand-in pair(s) not added (BASE lists its native entry itself): " + standIns);
                conflicts = r.conflicts;
                if (!standIns.isEmpty()) count(g, "stand-in-pairs-dropped");
            } else {
                Merge3.Result r = Merge3.merge(g, v, u, b, mapper, m.lossy(g));
                result = r.entry;
                detail = "applied " + r.applied + (r.conflicts.isEmpty() ? "" : "; USER value wins over BASE for " + r.conflicts);
                conflicts = r.conflicts;
            }
            byte[] out = result.encode();
            if (Arrays.equals(out, b.encode())) {
                count(g, "user-change-already-in-base");
                ctx.report.add(Decision.SAME, key, id, "user edit adds nothing to BASE's entry (encoder defaults/losses only)");
                return;
            }
            write(g, id, out);
            count(g, jagexChanged ? "merged" : "user-changed");
            if (!conflicts.isEmpty()) count(g, "merged-with-conflicts");
            ctx.report.add(jagexChanged ? Decision.MERGED : Decision.USER_CHANGED, key, id,
                    (jagexChanged ? "user edit re-applied on BASE's newer entry: " : "user edit applied (BASE unchanged since 211): ") + detail);
            merged.add(Arch.configName(g) + "\t" + id + "\t" + (jagexChanged ? "merged" : "user-changed") + "\t" + tsv(detail));
        }

        String describe(int g, Entry e) {
            Kind k = CfgModel.kindOf(g);
            if (k == Kind.OBJ || k == Kind.NPC || k == Kind.LOC || k == Kind.SPOT) return ConfigPlanStep.describe(g, e);
            if (g == Arch.CFG_ENUM) { int[] t = CfgCodec.enumTypes(e); return "enum " + ConfigPlanStep.Plan.types(e) + " with " + EnumMerge.pairsOf(e).size() + " pairs"; }
            return e.opaque ? e.encode().length + " bytes" : e.items.size() + " fields";
        }

        static String tsv(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' '); }

        // -------------------------------------------------------------- model edits the 211-era users follow

        /**
         * User edits of vanilla models that {@code models} moved because BASE content added after 211 uses the model
         * too ({@code models.follow} in {@link Ctx#shared}: model -> "kind:id" of the BASE configs that already used it
         * in 211): those configs point at the moved edit, as on 211; BASE's newer users keep BASE's model.
         */
        @SuppressWarnings("unchecked")
        void followModels() {
            Object o = ctx.shared.get("models.follow");
            if (!(o instanceof TreeMap<?, ?> f)) return;
            for (var e : ((TreeMap<Integer, java.util.TreeSet<String>>) f).entrySet()) {
                int model = e.getKey(), to = ctx.ids.map(Kind.MODEL, model);
                if (to == model) continue;
                for (String who : e.getValue()) {
                    int c = who.indexOf(':');
                    int g = groupByName(who.substring(0, c)), id = Integer.parseInt(who.substring(c + 1));
                    byte[] cur = g < 0 ? null : ctx.out.configRef(g, id);
                    if (cur == null) continue;
                    Entry en = CfgCodec.parse(g, id, cur, m.baseTypes);
                    byte[] nb = CfgCodec.map(en, (k, v) -> k == Kind.MODEL && v == model ? to : v).encode();
                    if (Arrays.equals(nb, cur)) continue;
                    write(g, id, nb);
                    count(g, "follows-moved-model-edit");
                    ctx.report.add(Decision.USER_CHANGED, kindKey(g), id, "uses vanilla model " + model + ", which the user edited: points at the edit (model " + to
                            + "); the edit moved because BASE content added after 211 uses model " + model + " too");
                }
            }
        }

        static int groupByName(String n) {
            for (int g : new int[]{Arch.CFG_OBJ, Arch.CFG_NPC, Arch.CFG_LOC, Arch.CFG_SPOT, Arch.CFG_IDENTKIT}) if (Arch.configName(g).equals(n)) return g;
            return -1;
        }

        // -------------------------------------------------------------- slots

        /**
         * Varps and varcs that configs-plan moved although USER has no definition for them (GadScape variables BASE
         * defines): a plain definition at the new id.
         */
        void implicitVarTargets() {
            for (Kind k : new Kind[]{Kind.VARP, Kind.VARC}) {
                int n = 0;
                for (var e : ctx.ids.all(k).entrySet()) {
                    if (m.user.hasConfig(k.configGroup, e.getKey()) || ctx.out.hasConfig(k.configGroup, e.getValue())) continue;
                    ctx.out.putConfig(k.configGroup, e.getValue(), new byte[]{0});
                    n++;
                }
                if (n > 0) ctx.report.count(k.key + ".plain-definition-at-relocated-id", n);
            }
        }

        /**
         * Rule 11 (VarcFix): BASE's client-variable entries stay; the user's own are written by {@link #group} (moved
         * when BASE uses the id); plain entries fill every other id below the slot count (default 2048, at least the
         * top of the reserved varc range + 1), because the 241 client sizes its varc table from the config-19 file
         * count and the GadScape scripts use varcs up to 2002.
         */
        void varcSlots() {
            int slots = Integer.parseInt(ctx.opt.get("configs.varcSlots", "2048"));
            com.gadscape.cacheconv.core.IdMap.Range r = ctx.ids.range(Kind.VARC);
            if (r != null) slots = Math.max(slots, r.hi() + 1);
            for (int to : ctx.ids.all(Kind.VARC).values()) slots = Math.max(slots, to + 1);
            int n = 0;
            for (int id = 0; id < slots; id++) if (!ctx.out.hasConfig(Arch.CFG_VARC, id)) { ctx.out.putConfig(Arch.CFG_VARC, id, new byte[]{0}); n++; }
            ctx.report.count("varc.plain-slots-added", n);
            if (n > 0) ctx.report.note("varc: " + n + " plain entries added so every id below " + slots + " has a definition (GadScape scripts use varcs up to 2002; VarcFix)");
        }

        /** VarpSlots: plain varp entries below the slot count (the GadScape server sends varps up to 19453). */
        void varpSlots() {
            int slots = Integer.parseInt(ctx.opt.get("configs.varpSlots", "65536"));
            int n = 0;
            for (int id = 0; id < slots; id++) if (!ctx.out.hasConfig(Arch.CFG_VARP, id)) { ctx.out.putConfig(Arch.CFG_VARP, id, new byte[]{0}); n++; }
            ctx.report.count("varp.plain-slots-added", n);
            if (n > 0) ctx.report.note("varp: " + n + " plain entries added so every id below " + slots + " has a definition (the 241 client sizes its varp table from config 16)");
        }

        // -------------------------------------------------------------- dbtable index

        /** Archive 21 groups of user dbtables (user-added/changed tables), row ids mapped; BASE's groups stay. */
        void dbIndex() {
            if (!m.user.hasArchive(Arch.DBTABLE_INDEX)) return;
            for (int t : m.user.groupIds(Arch.DBTABLE_INDEX)) {
                Origin o = m.origin(Arch.CFG_DBTABLE, t);
                if (!o.isUser()) continue;
                int to = ctx.ids.map(Kind.DBTABLE, t);
                GroupData src = m.user.group(Arch.DBTABLE_INDEX, t);
                if (src == null) { ctx.report.error("dbtable", t, "USER dbtable index group unreadable"); continue; }
                GroupData.Builder nb = new GroupData.Builder(to).name(src.name);
                int moved = 0, missing = 0, missingInUser = 0;
                for (int i = 0; i < src.fileCount(); i++) {
                    DbIndex d = DbIndex.parse(src.dataAt(i));
                    for (int r : d.rows()) {
                        if (ctx.ids.moved(Kind.DBROW, r)) moved++;
                        if (!ctx.out.hasConfig(Arch.CFG_DBROW, ctx.ids.map(Kind.DBROW, r))) { missing++; if (!m.user.hasConfig(Arch.CFG_DBROW, r)) missingInUser++; }
                    }
                    nb.put(src.fileIdAt(i), src.fileNameAt(i), d.mapRows(r -> ctx.ids.map(Kind.DBROW, r)).encode());
                }
                if (ctx.out.hasGroup(Arch.DBTABLE_INDEX, to) && !m.base.hasGroup(Arch.DBTABLE_INDEX, to)) { /* written earlier */ }
                if (m.base.hasGroup(Arch.DBTABLE_INDEX, to)) {
                    ctx.report.error("dbtable", t, "index group " + to + " is BASE's: user table index not written");
                    continue;
                }
                ctx.out.putGroup(Arch.DBTABLE_INDEX, nb.build());
                ctx.report.count("dbtable-index.groups-written", 1);
                ctx.report.count("dbtable-index.row-refs-moved", moved);
                if (missing > 0) ctx.report.warn("dbtable", t, missing + " row references in the index point at rows that do not exist"
                        + (missingInUser == missing ? " (the USER index already had them: stale index entries in the user's cache)" : " (" + missingInUser + " already missing in USER)"));
            }
        }

        // -------------------------------------------------------------- checks

        /** User varbits (written by this step) sharing varp bits with a BASE varbit: BASE scripts would see GadScape values. */
        void varbitOverlaps() {
            TreeMap<Integer, List<int[]>> baseBits = new TreeMap<>();
            for (int id : m.base.configIds(Arch.CFG_VARBIT)) {
                byte[] b = m.base.configRef(Arch.CFG_VARBIT, id);
                if (b.length >= 5 && b[0] == 1) baseBits.computeIfAbsent(((b[1] & 255) << 8) | (b[2] & 255), x -> new ArrayList<>()).add(new int[]{id, b[3] & 255, b[4] & 255});
            }
            int n = 0;
            for (int id : m.user.configIds(Arch.CFG_VARBIT)) {
                if (m.origin(Arch.CFG_VARBIT, id) != Origin.USER_ADDED) continue;
                int at = ctx.ids.map(Kind.VARBIT, id);
                byte[] u = ctx.out.configRef(Arch.CFG_VARBIT, at);
                if (u == null || u.length < 5 || u[0] != 1) continue;
                int varp = ((u[1] & 255) << 8) | (u[2] & 255), lo = u[3] & 255, hi = u[4] & 255;
                List<String> hits = new ArrayList<>();
                for (int[] x : baseBits.getOrDefault(varp, List.of())) if (x[0] != at && x[1] <= hi && lo <= x[2]) hits.add(x[0] + "[" + x[1] + "-" + x[2] + "]");
                if (hits.isEmpty()) continue;
                n++;
                ctx.report.warn("varbit", id, "user varbit " + id + (at != id ? " (now " + at + ")" : "") + " uses varp " + varp + " bits " + lo + "-" + hi
                        + ", which BASE varbits also use: " + (hits.size() > 6 ? hits.subList(0, 6) + "... (" + hits.size() + ")" : hits));
            }
            ctx.report.count("varbit.user-bits-overlapping-base", n);
        }

        void finish() throws IOException {
            for (var e : counts.entrySet()) ctx.report.count(e.getKey(), e.getValue());
            for (var e : removedVanillaPorted.entrySet())
                ctx.report.note(Arch.configName(e.getKey()) + ": " + e.getValue().size() + " vanilla 211 entries BASE no longer has were ported at their ids: " + ranges(e.getValue()));
            for (var e : userRemoved.entrySet())
                ctx.report.note(Arch.configName(e.getKey()) + ": the user deleted " + e.getValue().size() + " vanilla entries; BASE's kept: " + ranges(e.getValue()));
            Path dir = ctx.work("configs");
            Files.write(dir.resolve("clashes.tsv"), ("group\tid\tuser\tbase\n" + String.join("\n", clashes) + "\n").getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("merged.tsv"), ("group\tid\tdecision\tdetail\n" + String.join("\n", merged) + "\n").getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("rev225-imports-base-newer.tsv"), ("group\tid\tuser (rev 225)\tbase\n" + String.join("\n", imports225) + "\n").getBytes(StandardCharsets.UTF_8));
            ctx.log("%d clashes kept, %d merged/changed; see %s", clashes.size(), merged.size(), dir);
        }
    }

    /** "1-3,7,9-12" for an ascending id list. */
    public static String ranges(List<Integer> l) {
        StringBuilder s = new StringBuilder();
        int i = 0;
        while (i < l.size()) {
            int j = i;
            while (j + 1 < l.size() && l.get(j + 1) == l.get(j) + 1) j++;
            if (s.length() > 0) s.append(',');
            s.append(l.get(i));
            if (j > i) s.append('-').append(l.get(j));
            i = j + 1;
        }
        return s.toString();
    }
}

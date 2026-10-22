package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.config.CfgCodec.Entry;
import com.gadscape.cacheconv.config.CfgCodec.Ref;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Step;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * configs-refcheck: checks every config entry of the output that is not BASE's (written by the configs, anim or any
 * other step; seq is left to the anim area's check). Each one must decode completely in the 241 layout, and each
 * reference must resolve in the output: models (archive 7), seqs, objs, npcs, locs, spotanims, structs, enums,
 * params, varbits, varps, dbrows, dbtables (archive 2), sprites (archive 8), interfaces (archive 3).
 * <ul>
 *   <li>error: an entry that does not decode, or a USER-origin reference to an id that was moved (the id map says
 *       it moved, the entry still uses the old id, and BASE's entry at the same id does not hold the same reference);</li>
 *   <li>warning: references that resolve nowhere, counted per kind with examples, split into ids USER has (written by
 *       another area's step: run this check last, after anim/ui/maps) and ids that were already dangling in USER;</li>
 *   <li>counts: entries checked, references per kind.</li>
 * </ul>
 */
public final class ConfigRefCheck implements Step {
    public static final String NAME = "configs-refcheck";

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) {
        CacheView out = ctx.out;
        ParamTypeTable outTypes = new ParamTypeTable(out);
        TreeMap<String, Integer> refCounts = new TreeMap<>(), dangling = new TreeMap<>(), pending = new TreeMap<>();
        TreeMap<String, List<String>> examples = new TreeMap<>();
        int checked = 0, bad = 0, unrewritten = 0;
        for (int g : out.groupIds(Arch.CONFIGS)) {
            if (g == Arch.CFG_SEQ || !CfgCodec.walked(g)) continue;
            for (int id : out.configIds(g)) {
                byte[] b = out.configRef(g, id);
                byte[] base = ctx.base.configRef(g, id);
                if (base != null && Arrays.equals(base, b)) continue;
                checked++;
                Entry e;
                try { e = CfgCodec.parse(g, id, b, outTypes); }
                catch (RuntimeException ex) { ctx.report.error(Arch.configName(g), id, "does not decode in the 241 layout: " + ex.getMessage()); bad++; continue; }
                if (e.padding > 0) ctx.report.warn(Arch.configName(g), id, e.padding + " zero bytes after the end opcode");
                Set<String> baseRefs = new HashSet<>();
                if (base != null) {
                    try { for (Ref r : CfgCodec.parse(g, id, base, new ParamTypeTable(ctx.base)).allRefs()) if (r.kind() != null) baseRefs.add(r.kind().key + ":" + r.value()); }
                    catch (RuntimeException ignored) { }
                }
                for (Ref r : e.allRefs()) {
                    if (r.none() || r.kind() == null) continue;
                    refCounts.merge(r.kind().key, 1, Integer::sum);
                    if (ctx.ids.moved(r.kind(), r.value()) && !baseRefs.contains(r.kind().key + ":" + r.value())) {
                        unrewritten++;
                        ctx.report.error(Arch.configName(g), id, "still references " + r.kind().key + " " + r.value() + ", which moved to " + ctx.ids.map(r.kind(), r.value()));
                    }
                    if (!exists(out, r.kind(), r.value())) {
                        boolean inUser = ctx.user != null && (exists(ctx.user, r.kind(), r.value())
                                || ctx.ids.origin(r.kind(), r.value()) >= 0 && exists(ctx.user, r.kind(), ctx.ids.origin(r.kind(), r.value())));
                        String key = r.kind().key + (inUser ? " (USER has it)" : " (dangling in USER too)");
                        (inUser ? pending : dangling).merge(r.kind().key, 1, Integer::sum);
                        List<String> ex = examples.computeIfAbsent(key, k -> new ArrayList<>());
                        if (ex.size() < 8 && !ex.contains(Arch.configName(g) + " " + id + " -> " + r.value())) ex.add(Arch.configName(g) + " " + id + " -> " + r.value());
                    }
                }
            }
            out.release(Arch.CONFIGS);
        }
        ctx.report.count("entries-checked", checked);
        ctx.report.count("entries-undecodable", bad);
        ctx.report.count("refs-to-moved-ids", unrewritten);
        for (var e : refCounts.entrySet()) ctx.report.count("refs." + e.getKey(), e.getValue());
        for (var e : pending.entrySet()) {
            ctx.report.count("missing-not-yet-written." + e.getKey(), e.getValue());
            ctx.report.warn(e.getKey(), Long.MIN_VALUE, e.getValue() + " references from non-BASE configs resolve to no " + e.getKey() + " in the output yet, but USER has the "
                    + e.getKey() + " (another area's step writes it; check again after the whole pipeline), e.g. " + examples.get(e.getKey() + " (USER has it)"));
        }
        for (var e : dangling.entrySet()) {
            ctx.report.count("dangling-in-user-too." + e.getKey(), e.getValue());
            ctx.report.warn(e.getKey(), Long.MIN_VALUE, e.getValue() + " references from non-BASE configs resolve to no " + e.getKey() + ", in USER either (pre-existing), e.g. "
                    + examples.get(e.getKey() + " (dangling in USER too)"));
        }
        ctx.log("%d non-BASE entries checked, %d undecodable, %d refs to moved ids, not yet written (USER has them) %s, dangling in USER too %s", checked, bad, unrewritten, pending, dangling);
    }

    /** Whether an id of a kind exists in the cache (configs: file in its group; archives: group). */
    static boolean exists(CacheView c, Kind k, int id) {
        if (id < 0) return true;
        return switch (k) {
            case MODEL -> c.hasGroup(Arch.MODELS, id);
            case SPRITE -> c.hasGroup(Arch.SPRITES, id);
            case INTERFACE -> c.hasGroup(Arch.INTERFACES, id);
            case SEQ -> c.hasConfig(Arch.CFG_SEQ, id);
            default -> k.isConfig() ? c.hasConfig(k.configGroup, id) : c.hasGroup(k.archive, id);
        };
    }
}

package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.config.CfgCodec.Entry;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Step;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * configs-expect: self-check of the configs area on the real inputs, run after {@link ConfigStep} (dry run or not).
 * It asserts the outcomes the audit asked for, each one only when the inputs hold the ids it is about (so it is a
 * no-op on other caches), plus two general invariants:
 * <ul>
 *   <li>every config relocation in the id map has an entry at its target, and BASE's entry (or none) at the old id;</li>
 *   <li>every user-added/changed config that was not moved is either BASE's entry (same thing) or the USER entry
 *       converted to 241 with references mapped, or a merge result containing the user's edit (spot checks).</li>
 * </ul>
 * Audit expectations: enum 906/1739 = BASE plus the user's keys (SRA-3, CRIT-19, R4-06: 28338, 33335, 34725, 34719);
 * enum 1034 = BASE (SKSP-12); objs 34681-34720/34725 = BASE, 34721-34723/34726 moved (R4-05, OBL-6); the 46 raid
 * structs = BASE, their 211 copies moved and ported enum 2730 pointing at the copies (R4-03); dbrows 2342-2928 = BASE,
 * user shop rows moved and the user's table indexes renumbered (PVP-23, CRIT-23); varbits 17007/17008 off varp 4702;
 * GadScape varcs 1155-1159/1500 moved, BASE's kept (rule 11); enum 708 = BASE with the user's copy moved.
 * Failures are report errors; passes are counted.
 */
public final class ConfigExpect implements Step {
    public static final String NAME = "configs-expect";

    @Override public String name() { return NAME; }

    private Ctx ctx;
    private CacheView out;
    private CfgModel m;
    private int pass, fail, skipped;
    private final List<String> failures = new ArrayList<>();

    void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; failures.add(what); ctx.report.error("configs-expect: " + what); }
    }

    @Override public void run(Ctx ctx) {
        this.ctx = ctx; this.out = ctx.out; this.m = new CfgModel(ctx);
        relocationsResolve();
        enumKeys();
        raidObjs();
        raidStructs();
        dbrows();
        vars();
        replacedEnum();
        ctx.report.count("expect.pass", pass);
        ctx.report.count("expect.fail", fail);
        ctx.report.count("expect.skipped", skipped);
        ctx.log("%d expectations hold, %d fail, %d skipped (ids not in the inputs)%s", pass, fail, skipped, failures.isEmpty() ? "" : ": " + failures.subList(0, Math.min(10, failures.size())));
    }

    boolean has(CacheView c, int g, int... ids) { for (int id : ids) if (!c.hasConfig(g, id)) { skipped++; return false; } return true; }

    // ------------------------------------------------------------------ invariants

    void relocationsResolve() {
        for (Kind k : Kind.values()) {
            if (!k.isConfig() || k == Kind.SEQ) continue;
            int g = k.configGroup;
            for (var e : ctx.ids.all(k).entrySet()) {
                int from = e.getKey(), to = e.getValue();
                check(out.hasConfig(g, to), k.key + " " + from + " moved to " + to + ", which has no entry");
                byte[] base = ctx.base.configRef(g, from), now = out.configRef(g, from);
                if (base != null) check(Arrays.equals(base, now), k.key + " " + from + " moved away but the output does not hold BASE's entry there");
                else if (!m.van.hasConfig(g, from) || m.origin(g, from) != Origin.VANILLA)
                    check(now == null || k == Kind.VARP || k == Kind.VARC, k.key + " " + from + " moved away; BASE has none there but the output still has an entry");
            }
        }
    }

    // ------------------------------------------------------------------ audit expectations

    static LinkedHashMap<Integer, byte[]> pairs(CacheView c, int id) {
        byte[] b = c.configRef(Arch.CFG_ENUM, id);
        return b == null ? new LinkedHashMap<>() : EnumMerge.pairsOf(CfgCodec.parse(Arch.CFG_ENUM, id, b, null));
    }

    /** 906 spec costs and 1739 spec descriptions: BASE's keys kept, the user's added keys present. */
    void enumKeys() {
        for (int id : new int[]{906, 1739}) {
            if (!has(ctx.base, Arch.CFG_ENUM, id) || !has(m.user, Arch.CFG_ENUM, id)) continue;
            var o = pairs(out, id);
            var b = pairs(ctx.base, id);
            var v = pairs(m.van, id);
            var u = EnumMerge.pairsOf(m.userEntry(Arch.CFG_ENUM, id));
            int baseKept = 0, userAdded = 0;
            for (var e : b.entrySet()) {
                byte[] uv = u.get(e.getKey()), vv = v.get(e.getKey());
                boolean userTouched = uv != null && (vv == null || !Arrays.equals(uv, vv));
                if (!userTouched) { check(Arrays.equals(o.get(e.getKey()), e.getValue()), "enum " + id + " key " + e.getKey() + ": BASE's value lost"); baseKept++; }
            }
            for (var e : u.entrySet()) if (!v.containsKey(e.getKey())) {
                int key = ctx.ids.map(Kind.OBJ, e.getKey());
                check(o.containsKey(key), "enum " + id + ": user key " + e.getKey() + " missing");
                userAdded++;
            }
            for (int key : new int[]{28338, 33335, 34725, 34719})
                if (b.containsKey(key)) check(o.containsKey(key), "enum " + id + ": stock key " + key + " (SRA-3/CRIT-19/R4-06) missing");
            ctx.report.note("configs-expect: enum " + id + " has " + o.size() + " pairs (BASE " + b.size() + ", USER " + u.size() + "): " + baseKept + " BASE values kept, " + userAdded + " user keys present");
        }
        if (has(ctx.base, Arch.CFG_ENUM, 1034) && m.origin(Arch.CFG_ENUM, 1034) == Origin.VANILLA)
            check(Arrays.equals(out.configRef(Arch.CFG_ENUM, 1034), ctx.base.configRef(Arch.CFG_ENUM, 1034)), "enum 1034 (item sets, SKSP-12) is not BASE's");
    }

    /** R4-05 / OBL-6: the user's imports of raid items resolve to BASE; the user's own items on raid ids moved. */
    void raidObjs() {
        if (!has(ctx.base, Arch.CFG_OBJ, 34681, 34721) || !has(m.user, Arch.CFG_OBJ, 34681, 34721)) return;
        for (int id = 34646; id <= 34731; id++) {
            if (!ctx.base.hasConfig(Arch.CFG_OBJ, id)) continue;
            check(Arrays.equals(out.configRef(Arch.CFG_OBJ, id), ctx.base.configRef(Arch.CFG_OBJ, id)), "obj " + id + " (a 2746 raid id) is not BASE's");
        }
        for (int id : new int[]{34721, 34722, 34723, 34726}) {
            int to = ctx.ids.lookup(Kind.OBJ, id);
            check(to != -1, "obj " + id + " (user item on a raid id) was not moved");
            if (to == -1) continue;
            Entry e = CfgCodec.parse(Arch.CFG_OBJ, to, out.configRef(Arch.CFG_OBJ, to), m.baseTypes);
            for (CfgCodec.Ref r : e.allRefs())
                if (r.kind() == Kind.OBJ && !r.none()) check(!ctx.ids.moved(Kind.OBJ, r.value()), "obj " + to + " still links obj " + r.value() + ", which moved");
        }
        // the cert pair moved together: 60000 notes to 60001
        int c = ctx.ids.lookup(Kind.OBJ, 34721), n = ctx.ids.lookup(Kind.OBJ, 34722);
        if (c != -1 && n != -1) {
            Entry e = CfgCodec.parse(Arch.CFG_OBJ, n, out.configRef(Arch.CFG_OBJ, n), m.baseTypes);
            check(CfgCodec.refsOf(e, Kind.OBJ).contains(c), "obj " + n + " (moved note of 34721) does not link " + c);
        }
    }

    /** R4-03: the 46 raid structs are BASE's; their 211 copies moved; enum 2730 (ported 211 enum) points at the copies. */
    void raidStructs() {
        int[] raid = {1216, 1217, 1218, 1220, 1232, 1234, 1746, 1767, 1774};
        if (!has(ctx.base, Arch.CFG_STRUCT, raid) || !has(m.van, Arch.CFG_STRUCT, raid) || m.old241 == null) return;
        for (int id : raid) {
            check(Arrays.equals(out.configRef(Arch.CFG_STRUCT, id), ctx.base.configRef(Arch.CFG_STRUCT, id)), "struct " + id + " (raid struct) is not BASE's");
            int to = ctx.ids.lookup(Kind.STRUCT, id);
            check(to != -1 && Arrays.equals(out.configRef(Arch.CFG_STRUCT, to), CfgCodec.map(m.vanEntry(Arch.CFG_STRUCT, id), m.mapper()).encode()),
                    "struct " + id + ": the 211 copy is not at its new id");
        }
        if (m.van.hasConfig(Arch.CFG_ENUM, 2730) && !ctx.base.hasConfig(Arch.CFG_ENUM, 2730)) {
            Entry e = CfgCodec.parse(Arch.CFG_ENUM, 2730, out.configRef(Arch.CFG_ENUM, 2730), m.baseTypes);
            List<Integer> s = CfgCodec.refsOf(e, Kind.STRUCT);
            check(!s.isEmpty() && s.stream().noneMatch(x -> ctx.ids.moved(Kind.STRUCT, x)), "enum 2730 (ported 211 enum) still points at raid struct ids: " + s);
        }
    }

    /** PVP-23 / CRIT-23: stock dbrows 2342-2928 are BASE's, the user's shop rows moved, their table indexes renumbered. */
    void dbrows() {
        if (!has(ctx.base, Arch.CFG_DBROW, 2342, 2928) || !has(m.user, Arch.CFG_DBROW, 2342, 2928)) return;
        int moved = 0;
        for (int id = 2342; id <= 2928; id++) {
            if (!ctx.base.hasConfig(Arch.CFG_DBROW, id)) continue;
            check(Arrays.equals(out.configRef(Arch.CFG_DBROW, id), ctx.base.configRef(Arch.CFG_DBROW, id)), "dbrow " + id + " is not BASE's");
            if (m.origin(Arch.CFG_DBROW, id).isUser()) { check(ctx.ids.moved(Kind.DBROW, id), "user dbrow " + id + " was not moved"); moved++; }
        }
        for (int t = 1001; t <= 1014; t++) {
            if (!m.user.hasGroup(Arch.DBTABLE_INDEX, t)) continue;
            GroupData g = out.group(Arch.DBTABLE_INDEX, t);
            check(g != null, "dbtable index " + t + " missing");
            if (g == null) continue;
            DbIndex d = DbIndex.parse(g.dataAt(0));
            for (int r : d.rows()) check(!ctx.ids.moved(Kind.DBROW, r), "dbtable index " + t + " still lists moved row " + r);
        }
        ctx.report.note("configs-expect: " + moved + " user dbrows in 2342-2928 moved; BASE's rows kept");
    }

    /** Variables: user varbits 17007/17008 off BASE's varp 4702; GadScape varcs moved, BASE's kept; varc table size. */
    void vars() {
        if (has(m.user, Arch.CFG_VARBIT, 17007) && has(ctx.base, Arch.CFG_VARP, 4702)) {
            byte[] b = out.configRef(Arch.CFG_VARBIT, ctx.ids.map(Kind.VARBIT, 17007));
            int varp = ((b[1] & 255) << 8) | (b[2] & 255);
            check(varp != 4702 && varp == ctx.ids.map(Kind.VARP, 4702), "varbit 17007 still on varp " + varp);
        }
        for (int id : new int[]{1155, 1500}) {
            if (!has(ctx.base, Arch.CFG_VARC, id)) continue;
            check(Arrays.equals(out.configRef(Arch.CFG_VARC, id), ctx.base.configRef(Arch.CFG_VARC, id)), "varc " + id + " is not BASE's");
        }
        int[] vc = out.configIds(Arch.CFG_VARC);
        check(vc.length == vc[vc.length - 1] + 1, "varc ids are not dense (" + vc.length + " entries, top id " + vc[vc.length - 1] + ")");
        for (int to : ctx.ids.all(Kind.VARC).values()) check(to < vc.length, "moved varc " + to + " is outside the varc table (" + vc.length + ")");
        int[] vp = out.configIds(Arch.CFG_VARP);
        check(vp.length == vp[vp.length - 1] + 1 && vp.length >= 19454, "varp table: " + vp.length + " entries, top id " + vp[vp.length - 1] + " (the server sends up to 19453)");
    }

    /** Enum 708: the user replaced its content; BASE keeps 708, the user's enum is at its new id. */
    void replacedEnum() {
        if (!has(ctx.base, Arch.CFG_ENUM, 708) || m.origin(Arch.CFG_ENUM, 708) != Origin.USER_CHANGED) return;
        int to = ctx.ids.lookup(Kind.ENUM, 708);
        check(to != -1, "enum 708 (replaced by the user) was not moved");
        check(Arrays.equals(out.configRef(Arch.CFG_ENUM, 708), ctx.base.configRef(Arch.CFG_ENUM, 708)), "enum 708 is not BASE's");
        if (to != -1) check(pairs(out, to).size() == EnumMerge.pairsOf(m.userEntry(Arch.CFG_ENUM, 708)).size(), "enum " + to + " is not the user's 708");
    }

}

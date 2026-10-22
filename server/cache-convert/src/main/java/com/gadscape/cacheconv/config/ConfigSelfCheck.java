package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.config.CfgCodec.Entry;
import com.gadscape.cacheconv.config.CfgCodec.Ref;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.model.ModelRefs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * Self-check of the configs area on the real caches (no output is written):
 * <pre>
 *   dev/run.sh config.ConfigSelfCheck
 * </pre>
 * <ol>
 *   <li>codec round trip: every entry of every walked group of BASE, and of converted USER and VAN211, re-encodes to
 *       the same bytes (USER entries with zero padding after the end opcode re-encode to the bytes without it);</li>
 *   <li>model references the codec finds in opcode fields are exactly {@link ModelRefs}' (obj, npc, loc, spotanim,
 *       identkit); the codec also finds model-typed param values, which ModelRefs does not read;</li>
 *   <li>mapping with the identity returns the same entry; mapping every reference to id + 1 changes exactly those
 *       references (re-parse) and nothing else;</li>
 *   <li>archive 21 (dbtable index) files of BASE and USER round-trip;</li>
 *   <li>merges: with no user change the merge returns BASE's entry; merging is idempotent (merging the user's edit
 *       into a merge result changes nothing); every key the user added to an enum is in the merged enum.</li>
 * </ol>
 * Prints one line per check and exits 1 on any failure.
 */
public final class ConfigSelfCheck {
    private ConfigSelfCheck() {}

    static int failures;
    static final List<String> examples = new ArrayList<>();

    static void fail(String what) { failures++; if (examples.size() < 30) examples.add(what); }

    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        try (Ctx ctx = Ctx.open(o, false)) {
            CfgModel m = new CfgModel(ctx);
            roundTrip(m);
            identityAndShift(m);
            dbIndex(m);
            merges(m);
        }
        System.out.println(failures == 0 ? "ConfigSelfCheck: all checks passed" : "ConfigSelfCheck: " + failures + " FAILURES, e.g. " + examples);
        System.exit(failures == 0 ? 0 : 1);
    }

    static final int[] WALKED = {Arch.CFG_UNDERLAY, Arch.CFG_IDENTKIT, Arch.CFG_OVERLAY, Arch.CFG_INV, Arch.CFG_LOC, Arch.CFG_ENUM, Arch.CFG_NPC,
            Arch.CFG_OBJ, Arch.CFG_PARAM, Arch.CFG_SPOT, Arch.CFG_VARBIT, Arch.CFG_HEALTHBAR, Arch.CFG_STRUCT, Arch.CFG_DBROW, Arch.CFG_DBTABLE};

    static void roundTrip(CfgModel m) {
        for (String which : new String[]{"BASE", "USER", "VAN211"}) {
            CacheView c = which.equals("BASE") ? m.base : which.equals("USER") ? m.user : m.van;
            ParamTypeTable types = which.equals("BASE") ? m.baseTypes : which.equals("USER") ? m.userTypes : m.vanTypes;
            long n = 0, padded = 0, modelRefs = 0;
            int before = failures;
            for (int g : WALKED) {
                for (int id : c.configIds(g)) {
                    byte[] raw = c.configRef(g, id);
                    byte[] b = which.equals("BASE") ? raw : CfgModel.conv(g, id, raw);
                    Entry e;
                    try { e = CfgCodec.parse(g, id, b, types); }
                    catch (RuntimeException ex) { fail(which + " " + ex.getMessage()); continue; }
                    n++;
                    byte[] enc = e.encode();
                    if (e.padding > 0) {
                        padded++;
                        if (!Arrays.equals(enc, Arrays.copyOf(b, b.length - e.padding))) fail(which + " " + Arch.configName(g) + " " + id + ": padded entry re-encodes differently");
                    } else if (!Arrays.equals(enc, b)) fail(which + " " + Arch.configName(g) + " " + id + ": re-encode differs");
                    if (ModelRefs.hasModels(g)) {
                        // opcode fields only: ModelRefs does not read model-typed param values (obj param 186), the codec does
                        int[] mine = e.items.stream().filter(it -> !it.isContainer()).flatMap(it -> it.refs.stream())
                                .filter(r -> r.kind() == Kind.MODEL && !r.none()).mapToInt(CfgCodec.Ref::value).toArray();
                        int[] theirs = ModelRefs.models(g, id, enc, true);
                        modelRefs += mine.length;
                        if (!Arrays.equals(mine, theirs)) fail(which + " " + Arch.configName(g) + " " + id + ": model refs " + Arrays.toString(mine) + " vs ModelRefs " + Arrays.toString(theirs));
                    }
                }
                c.release(Arch.CONFIGS);
            }
            System.out.printf("round trip %-6s: %d entries (%d with zero padding), %d model refs equal to ModelRefs: %s%n", which, n, padded, modelRefs, failures == before ? "ok" : "FAILED");
        }
    }

    static void identityAndShift(CfgModel m) {
        long n = 0, shifted = 0;
        int before = failures;
        for (int g : WALKED) {
            int step = Math.max(1, m.base.configIds(g).length / 400);
            int[] ids = m.base.configIds(g);
            for (int i = 0; i < ids.length; i += step) {
                int id = ids[i];
                Entry e = CfgCodec.parse(g, id, m.base.configRef(g, id), m.baseTypes);
                if (CfgCodec.map(e, (k, v) -> v) != e) fail(Arch.configName(g) + " " + id + ": identity map returned a new entry");
                n++;
                List<Ref> refs = e.allRefs();
                if (refs.stream().noneMatch(r -> !r.none() && r.kind() != null)) continue;
                CfgCodec.Mapper plus = (k, v) -> k == Kind.PARAM || v >= 65533 ? v : v + 1; // keep param keys (types) and u16 bounds
                Entry s = CfgCodec.map(e, plus);
                Entry back = CfgCodec.parse(g, id, s.encode(), m.baseTypes);
                List<Integer> want = new ArrayList<>(), got = new ArrayList<>();
                for (Ref r : refs) if (!r.none() && r.kind() != null) want.add(plus.map(r.kind(), r.value()));
                for (Ref r : back.allRefs()) if (!r.none() && r.kind() != null) got.add(r.value());
                if (!want.equals(got)) { fail(Arch.configName(g) + " " + id + ": shifted refs " + got + " != " + want); continue; }
                // everything but the references is unchanged: mapping back restores the bytes
                Entry restored = CfgCodec.map(back, (k, v) -> k == Kind.PARAM || v >= 65534 ? v : v - 1);
                if (!Arrays.equals(restored.encode(), e.encode())) fail(Arch.configName(g) + " " + id + ": shift + unshift does not restore the entry");
                shifted++;
            }
            m.base.release(Arch.CONFIGS);
        }
        System.out.printf("mapping: %d sampled BASE entries, identity ok, %d shifted and restored: %s%n", n, shifted, failures == before ? "ok" : "FAILED");
    }

    static void dbIndex(CfgModel m) {
        int before = failures;
        long files = 0;
        for (CacheView c : new CacheView[]{m.base, m.user}) {
            for (int g : c.groupIds(Arch.DBTABLE_INDEX)) {
                GroupData gd = c.group(Arch.DBTABLE_INDEX, g);
                for (int i = 0; i < gd.fileCount(); i++) {
                    files++;
                    byte[] b = gd.dataAt(i);
                    DbIndex d = DbIndex.parse(b);
                    if (!Arrays.equals(d.encode(), Arrays.copyOf(b, b.length - d.padding))) fail(c.label() + " dbtable index " + g + ":" + gd.fileIdAt(i) + " re-encodes differently");
                    if (!Arrays.equals(d.mapRows(r -> r).encode(), d.encode())) fail("identity row map changed " + g);
                }
            }
        }
        System.out.printf("dbtable index: %d files of BASE and USER round-trip: %s%n", files, failures == before ? "ok" : "FAILED");
    }

    static void merges(CfgModel m) {
        int before = failures;
        TreeMap<String, Integer> n = new TreeMap<>();
        CfgCodec.Mapper id = (k, v) -> v;
        for (int g : new int[]{Arch.CFG_INV, Arch.CFG_LOC, Arch.CFG_ENUM, Arch.CFG_NPC, Arch.CFG_OBJ, Arch.CFG_PARAM, Arch.CFG_SPOT}) {
            for (int i : m.user.configIds(g)) {
                if (m.origin(g, i) != Origin.USER_CHANGED) continue;
                Entry b = m.baseEntry(g, i);
                if (b == null) continue;
                Entry v = m.vanEntry(g, i), u = m.userEntry(g, i);
                String gn = Arch.configName(g);
                if (g == Arch.CFG_ENUM) {
                    if (EnumMerge.isEmpty(b) || EnumMerge.incompatible(u, b)) continue;
                    EnumMerge.Result none = EnumMerge.merge(v, v, b, id);
                    if (!CfgModel.sameEffective(g, none.entry, b)) fail("enum " + i + ": merging no change altered BASE");
                    EnumMerge.Result r = EnumMerge.merge(v, u, b, id);
                    EnumMerge.Result again = EnumMerge.merge(v, u, r.entry, id);
                    if (!CfgModel.sameEffective(g, again.entry, r.entry)) fail("enum " + i + ": merge not idempotent");
                    var vp = EnumMerge.pairsOf(v); var up = EnumMerge.pairsOf(u); var rp = EnumMerge.pairsOf(r.entry);
                    if (!r.list) for (var e : up.entrySet()) if (!vp.containsKey(e.getKey()) && !Arrays.equals(rp.get(e.getKey()), e.getValue())) fail("enum " + i + ": user key " + e.getKey() + " missing from the merge");
                    n.merge(gn, 1, Integer::sum);
                    continue;
                }
                Merge3.Result none = Merge3.merge(g, v, v, b, id, m.lossy(g));
                if (!Arrays.equals(none.entry.encode(), b.encode())) fail(gn + " " + i + ": merging no change altered BASE");
                Merge3.Result r = Merge3.merge(g, v, u, b, id, m.lossy(g));
                Merge3.Result again = Merge3.merge(g, v, u, r.entry, id, m.lossy(g));
                if (!CfgModel.sameEffective(g, again.entry, r.entry)) fail(gn + " " + i + ": merge not idempotent");
                try { CfgCodec.parse(g, i, r.entry.encode(), m.baseTypes); } catch (RuntimeException ex) { fail(gn + " " + i + ": merge result does not decode: " + ex.getMessage()); }
                n.merge(gn, 1, Integer::sum);
            }
            m.user.release(Arch.CONFIGS);
        }
        System.out.printf("merges: %s user-changed entries merged (no-change = BASE, idempotent, user keys kept, results decode): %s%n", n, failures == before ? "ok" : "FAILED");
        for (int g : new int[]{Arch.CFG_OBJ, Arch.CFG_NPC, Arch.CFG_LOC}) System.out.println("  inferred encoder losses " + Arch.configName(g) + ": " + m.lossy(g));
    }
}

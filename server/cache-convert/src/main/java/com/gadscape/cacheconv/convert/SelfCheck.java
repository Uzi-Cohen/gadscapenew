package com.gadscape.cacheconv.convert;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Golden check of the ported converters: converts USER (GadScape 211) entries and compares them with the hand-built
 * cacheU, where the old one-off tools wrote exactly these bytes (Overlay/FixPass/FixObjs: configs, UiOverlay:
 * interfaces, CsAll: every script, ModelConv+ModelTail: models; TrimPad later removed zero padding, so a match "after
 * zero-tail trim" counts as a match). Entries a later tool changed on purpose (MapsceneFix, VarcFix...) show up as
 * mismatches and are listed. Exit code 1 if a check falls below its expected match count.
 * <pre>
 *   dev/run.sh convert.SelfCheck [--user DIR] [--ref DIR] [--full]
 * </pre>
 * Without --full, models are checked on a sample (every 40th id plus the custom range); everything else is complete.
 */
public final class SelfCheck {
    private SelfCheck() {}

    static final class Tally {
        final String what; int total, exact, trimmed, mismatch, failed, missingRef; final List<String> examples = new ArrayList<>();
        Tally(String what) { this.what = what; }
        void compare(String id, byte[] conv, byte[] ref) {
            total++;
            if (ref == null) { missingRef++; return; }
            if (java.util.Arrays.equals(conv, ref)) exact++;
            else if (Bytes.equalsIgnoringZeroTail(conv, ref)) trimmed++;
            else { mismatch++; if (examples.size() < 6) examples.add(id + " (len " + conv.length + " vs " + ref.length + ", first diff @" + Bytes.firstDiff(conv, ref) + ")"); }
        }
        void fail(String id, Throwable e) { total++; failed++; if (examples.size() < 6) examples.add(id + " FAILED " + e.getMessage()); }
        int matched() { return exact + trimmed; }
        @Override public String toString() {
            return String.format("%-22s %6d checked: %6d identical, %5d identical after zero-tail trim, %4d differ, %3d failed, %3d not in cacheU%s",
                    what, total, exact, trimmed, mismatch, failed, missingRef, examples.isEmpty() ? "" : "\n      e.g. " + String.join("; ", examples));
        }
    }

    public static void main(String[] a) throws Exception {
        System.exit(run(a));
    }

    /** Runs the check; returns 0 if it passed. */
    public static int run(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        boolean full = o.flag("full");
        o.require("user", o.user);
        o.require("ref", o.ref);
        long t0 = System.currentTimeMillis();
        Map<String, Tally> res = new LinkedHashMap<>();
        try (JagCache user = JagCache.open("USER", o.user); JagCache ref = JagCache.open("REF(cacheU)", o.ref)) {
            known(user, ref);
            // configs: FixPass (identkit, npc, item, spotanim) + FixObjs (objects) + Overlay (sequences), then TrimPad
            int[][] cfgs = {{Arch.CFG_OBJ}, {Arch.CFG_NPC}, {Arch.CFG_LOC}, {Arch.CFG_SEQ}, {Arch.CFG_IDENTKIT}, {Arch.CFG_SPOT}};
            for (int[] c : cfgs) {
                int g = c[0];
                ConfigConv.Fn fn = ConfigConv.forGroup(g);
                Tally t = new Tally("config " + Arch.configName(g));
                GroupData ug = user.group(Arch.CONFIGS, g), rg = ref.group(Arch.CONFIGS, g);
                for (int i = 0; i < ug.fileCount(); i++) {
                    int id = ug.fileIdAt(i);
                    byte[] conv;
                    try { conv = fn.apply(id, ug.dataAt(i)); } catch (RuntimeException e) { t.fail(Arch.configName(g) + " " + id, e); continue; }
                    t.compare(Arch.configName(g) + " " + id, conv, rg.dataRef(id));
                }
                res.put(t.what, t);
            }
            // interfaces: UiOverlay put every USER interface with Conv.component
            Tally ti = new Tally("interface components");
            for (int id : user.groupIds(Arch.INTERFACES)) {
                GroupData g = user.group(Arch.INTERFACES, id), r = ref.group(Arch.INTERFACES, id);
                GroupData c = InterfaceConv.group(g);
                for (int i = 0; i < c.fileCount(); i++) ti.compare("component " + id + ":" + c.fileIdAt(i), c.dataAt(i), r == null ? null : r.dataRef(c.fileIdAt(i)));
            }
            user.release(Arch.INTERFACES); ref.release(Arch.INTERFACES);
            res.put(ti.what, ti);
            // scripts: CsAll converted every USER script (whole-set array analysis)
            Map<Integer, byte[]> raw = new TreeMap<>();
            for (int id : user.groupIds(Arch.SCRIPTS)) raw.put(id, user.single(Arch.SCRIPTS, id));
            ScriptConv.Result sr = ScriptConv.convertAll(raw);
            Tally ts = new Tally("clientscripts");
            for (var e : sr.scripts.entrySet()) ts.compare("script " + e.getKey(), e.getValue(), ref.single(Arch.SCRIPTS, e.getKey()));
            System.out.println("ScriptConv: " + sr + ", " + sr.notes.size() + " notes");
            user.release(Arch.SCRIPTS); ref.release(Arch.SCRIPTS);
            res.put(ts.what, ts);
            // models: Overlay copied every USER model, ModelConv then ModelTail rewrote them in place
            Tally tm = new Tally("models" + (full ? "" : " (sample)"));
            int n = 0;
            for (int id : user.groupIds(Arch.MODELS)) {
                if (!full && id % 40 != 0 && id < 62051) continue;
                byte[] b = user.single(Arch.MODELS, id);
                if (b == null || b.length < 26) continue;
                try { tm.compare("model " + id, ModelConv.toRev241(id, b, true), ref.single(Arch.MODELS, id)); }
                catch (RuntimeException e) { tm.fail("model " + id, e); }
                if (++n % 2000 == 0) { user.release(Arch.MODELS); ref.release(Arch.MODELS); }
            }
            res.put(tm.what, tm);
        }
        System.out.println();
        res.values().forEach(System.out::println);
        // expectations: what cacheU holds today (other tools changed a few entries after the conversion)
        Map<String, Double> minRatio = Map.of("config obj", 0.99, "config npc", 0.99, "config loc", 0.99, "config seq", 0.99,
                "config identkit", 0.99, "config spotanim", 0.99, "interface components", 0.99, "clientscripts", 1.0);
        boolean ok = true;
        for (Tally t : res.values()) {
            double need = minRatio.getOrDefault(t.what, 0.99);
            double got = t.total == 0 ? 1 : (double) t.matched() / (t.total - t.missingRef);
            if (got < need) { ok = false; System.out.println("BELOW EXPECTATION: " + t.what + " " + String.format("%.4f < %.2f", got, need)); }
        }
        System.out.println((ok ? "SELF-CHECK PASSED" : "SELF-CHECK FAILED") + " in " + (System.currentTimeMillis() - t0) / 1000 + " s");
        return ok ? 0 : 1;
    }

    /** A few well-known GadScape entries, printed one by one. */
    static void known(JagCache user, JagCache ref) {
        int[][] items = {{Arch.CFG_OBJ, 34715}, {Arch.CFG_OBJ, 34719}, {Arch.CFG_OBJ, 34725}, {Arch.CFG_NPC, 14900}, {Arch.CFG_NPC, 14696}};
        for (int[] it : items) {
            byte[] u = user.configRef(it[0], it[1]), r = ref.configRef(it[0], it[1]);
            if (u == null) { System.out.println("known " + Arch.configName(it[0]) + " " + it[1] + ": not in USER"); continue; }
            byte[] c = ConfigConv.forGroup(it[0]).apply(it[1], u);
            System.out.println("known " + Arch.configName(it[0]) + " " + it[1] + ": " + (java.util.Arrays.equals(c, r) ? "identical to cacheU" : Bytes.equalsIgnoringZeroTail(c, r) ? "identical after zero-tail trim" : "DIFFERS from cacheU"));
        }
    }
}

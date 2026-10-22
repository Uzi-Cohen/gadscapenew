package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.verify.CfgRefs.Target;
import com.gadscape.cacheconv.verify.Findings.Severity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Clientscript checks over a whole cache:
 * <ul>
 *   <li>{@code cs/parse}: every script parses in the 241 layout;</li>
 *   <li>{@code cs/<problem>}: {@link CsStack}'s stack analysis of every script (underflow, values left at a statement
 *       boundary, merges and returns that disagree, locals out of range, ...);</li>
 *   <li>{@code cs/gosub-target}: every gosub target exists; {@code cs/gosub-args}: at every gosub the caller pushes
 *       what the callee takes (underflow, or values left after a callee that returns nothing);</li>
 *   <li>{@code cs/signature-vs-base} (with a baseline): scripts whose arg/return counts differ from BASE's script of
 *       the same id, at every call from a script that is byte-identical to BASE's (compiled against BASE's
 *       signature);</li>
 *   <li>{@code cs/hook-setter-script}, {@code cs/hook-setter-args}: scripts set as hooks by cc_seton/if_seton exist and
 *       get the arguments they take; {@code cs/const-refs}: enum, param, dbtable ids used as constants exist;</li>
 *   <li>{@code cs/unknown-op} (warning): opcodes no stock 241 script uses (the client may not know them).</li>
 * </ul>
 */
public final class ScriptCheck {
    private ScriptCheck() {}

    /** Result kept for callers (e.g. the gosub mismatch summary of the verify command). */
    public static final class Out {
        public CsWorld world;
        public TreeMap<Integer, CsStack.Result> results;
        /** callee -> summary of the call sites whose arg counts do not match. */
        public final TreeMap<Integer, Map<String, Object>> mismatchedCallees = new TreeMap<>();
        /** Scripts whose signature differs from BASE's: id -> "out (i,o,l)->ret vs base ...". */
        public final TreeMap<Integer, String> signatureDiffs = new TreeMap<>();
    }

    public static Out run(CacheView c, CsTable table, CacheView base, Exists ex, Findings f) {
        Out out = new Out();
        CsWorld w = new CsWorld(c);
        out.world = w;
        for (var e : w.parseErrors.entrySet()) f.bad("cs/parse", Severity.ERROR, "script " + e.getKey(), "script " + e.getKey() + ": " + e.getValue());
        f.ok("cs/parse", Severity.ERROR, w.scripts.size());
        if (!w.stubs.isEmpty()) f.info("cs.stubs", w.stubs.toString());
        TreeMap<Integer, CsStack.Result> res = w.analyseAll(table::counts);
        out.results = res;
        TreeMap<Integer, Integer> unknownOps = new TreeMap<>();
        long complete = 0, clean = 0;
        TreeMap<Integer, List<String>> calleeSites = new TreeMap<>();
        for (CsStack.Result r : res.values()) {
            if (r.complete()) complete++;
            if (r.complete() && r.violations().isEmpty()) clean++;
            for (int op : r.unknownOps) unknownOps.merge(op, 1, Integer::sum);
            if (r.unknownOps.isEmpty()) f.ok("cs/unknown-op", Severity.WARNING);
            for (CsStack.Problem p : r.problems)
                if (p.kind().equals("unknown-op")) f.bad("cs/unknown-op", Severity.WARNING, "script " + r.id + " op " + p.op(),
                        "script " + r.id + " @" + p.index() + ": op " + p.op() + " is not a command the stock 241 scripts use (no stack effect known; the rest of the path is not checked)");
            TreeSet<Integer> badCalls = new TreeSet<>();
            for (CsStack.Problem p : r.problems) {
                if (CsStack.isIncomplete(p.kind())) continue;
                String key = "script " + r.id + " @" + p.index() + " " + p.kind();
                f.bad("cs/" + p.kind(), Severity.ERROR, key, "script " + r.id + " " + p);
                if (p.kind().equals("gosub-underflow") || p.kind().equals("gosub-leftover")) badCalls.add(p.index());
            }
            for (CsStack.Call call : r.calls) {
                String key = "script " + r.id + " @" + call.index() + " -> " + call.callee();
                if (badCalls.contains(call.index())) {
                    f.bad("cs/gosub-args", Severity.ERROR, key, "script " + r.id + " @" + call.index() + " -> script " + call.callee()
                            + " takes " + CsTable.sig(call.args()) + ", caller has " + CsTable.sig(call.before()) + " on the stack");
                    calleeSites.computeIfAbsent(call.callee(), k -> new ArrayList<>()).add(r.id + "@" + call.index() + " pushes " + CsTable.sig(call.before()));
                } else f.ok("cs/gosub-args", Severity.ERROR);
            }
            for (CsStack.ConstRef cr : r.refs) {
                Target t = switch (cr.kind()) { case "enum" -> Target.ENUM; case "param" -> Target.PARAM; case "dbtable" -> Target.DBTABLE; default -> Target.SCRIPT; };
                String name = t == Target.SCRIPT ? (cr.op() == 40 ? "cs/gosub-target" : "cs/hook-setter-script") : "cs/const-refs";
                f.check(name, Severity.ERROR, ex.exists(t, cr.id()), "script " + r.id + " -> " + t.key + " " + cr.id(),
                        "script " + r.id + " @" + cr.index() + " (op " + cr.op() + ") -> " + t.key + " " + cr.id());
            }
            for (CsStack.HookSet h : r.hooks) {
                if (h.script() < 0) continue;
                Cs2.Script s = w.scripts.get(h.script());
                if (s == null) continue;
                int[] a = h.argCounts();
                String key = "script " + r.id + " @" + h.index() + " sets " + h.script();
                f.check("cs/hook-setter-locals", Severity.ERROR, a[0] <= s.localInt && a[1] <= s.localObj && a[2] <= s.localLong, key,
                        key + " with \"" + h.desc() + "\" (" + CsTable.sig(a) + "), the script has locals " + s.localInt + "," + s.localObj + "," + s.localLong);
                f.check("cs/hook-setter-args", Severity.WARNING, a[0] == s.intArgs && a[1] == s.objArgs && a[2] == s.longArgs, key,
                        key + " with \"" + h.desc() + "\" (" + CsTable.sig(a) + "), the script takes " + CsTable.sig(new int[]{s.intArgs, s.objArgs, s.longArgs}));
            }
        }
        for (var e : calleeSites.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            int[] a = w.args(e.getKey());
            m.put("takes", a == null ? "missing" : CsTable.sig(a));
            m.put("returns", CsTable.sig(w.returns(e.getKey()) == null ? new int[3] : w.returns(e.getKey())));
            m.put("badSites", e.getValue().size());
            m.put("examples", e.getValue().subList(0, Math.min(8, e.getValue().size())));
            out.mismatchedCallees.put(e.getKey(), m);
        }
        f.info("cs.scripts", w.scripts.size());
        f.info("cs.complete", complete);
        f.info("cs.clean", clean);
        f.info("cs.incomplete", w.scripts.size() - complete);
        if (!unknownOps.isEmpty()) f.info("cs.unknownOps", unknownOps.toString());
        if (base != null) signatures(c, w, base, table, f, out);
        return out;
    }

    /** Signature (args and return counts) differences against BASE, checked at every call from a stock-identical script. */
    static void signatures(CacheView c, CsWorld w, CacheView base, CsTable table, Findings f, Out out) {
        CsWorld bw = new CsWorld(base);
        bw.analyseAll(table::counts);
        TreeMap<Integer, String> diffs = new TreeMap<>();
        for (int id : w.scripts.keySet()) {
            if (!bw.scripts.containsKey(id)) continue;
            int[] ao = w.args(id), ab = bw.args(id), ro = w.returns(id), rb = bw.returns(id);
            boolean argsDiffer = !Arrays.equals(ao, ab), retDiffer = ro != null && rb != null && !Arrays.equals(ro, rb);
            if (argsDiffer || retDiffer)
                diffs.put(id, "takes " + CsTable.sig(ao) + " returns " + (ro == null ? "?" : CsTable.sig(ro)) + ", BASE takes " + CsTable.sig(ab) + " returns " + (rb == null ? "?" : CsTable.sig(rb)));
        }
        out.signatureDiffs.putAll(diffs);
        if (diffs.isEmpty()) { f.ok("cs/signature-vs-base", Severity.ERROR); return; }
        // callers byte-identical to BASE were compiled against BASE's signature of their callees
        for (int caller : w.scripts.keySet()) {
            Cs2.Script s = w.scripts.get(caller);
            boolean calls = false;
            for (Cs2.Ins in : s.code) if (in.op == 40 && diffs.containsKey(in.i)) { calls = true; break; }
            if (!calls) continue;
            byte[] mine = c.single(Arch.SCRIPTS, caller), theirs = base.single(Arch.SCRIPTS, caller);
            if (theirs == null || !Arrays.equals(mine, theirs)) continue;
            for (int i = 0; i < s.code.size(); i++) {
                Cs2.Ins in = s.code.get(i);
                if (in.op != 40 || !diffs.containsKey(in.i)) continue;
                f.bad("cs/signature-vs-base", Severity.ERROR, "script " + caller + " @" + i + " -> " + in.i,
                        "stock script " + caller + " @" + i + " calls " + in.i + ", which " + diffs.get(in.i));
            }
        }
        f.info("cs.signatureDiffsVsBase", diffs.size());
    }
}

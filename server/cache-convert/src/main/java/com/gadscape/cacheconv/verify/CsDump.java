package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists clientscripts with the stack depths CsStack computes before every instruction and the problems it finds
 * (dev tool). Usage: {@code dev/run.sh verify.CsDump CACHE_DIR SCRIPT_ID... [--table=FILE]}.
 */
public final class CsDump {
    private CsDump() {}

    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        CsTable t = o.get("table", null) == null ? CsTable.load() : CsTable.parse(java.nio.file.Files.readAllLines(Path.of(o.get("table", null))));
        try (JagCache c = JagCache.open("C", Path.of(o.args.get(0)))) {
            CsWorld w = new CsWorld(c);
            w.analyseAll(t::counts);
            int around = Integer.parseInt(o.get("around", "-1"));
            for (String id : o.args.subList(1, o.args.size())) System.out.println(String.join("\n", list(w, t, Integer.parseInt(id), around)));
        }
    }

    public static List<String> list(CsWorld w, CsTable t, int id) { return list(w, t, id, -1); }

    /** Listing; with {@code around >= 0} only the instructions within that distance before each problem. */
    public static List<String> list(CsWorld w, CsTable t, int id, int around) {
        List<String> out = new ArrayList<>();
        Cs2.Script s = w.scripts.get(id);
        if (s == null) { out.add("script " + id + " missing"); return out; }
        CsStack.Result r = CsStack.analyseKeep(id, s, w, t::counts);
        out.add("script " + id + " args " + s.intArgs + "," + s.objArgs + "," + s.longArgs + " locals " + s.localInt + "," + s.localObj + "," + s.localLong
                + " returns " + java.util.Arrays.toString(r.returns));
        java.util.BitSet show = new java.util.BitSet();
        if (around < 0) show.set(0, s.code.size());
        else for (CsStack.Problem p : r.problems) show.set(Math.max(0, p.index() - around), Math.min(s.code.size(), p.index() + 2));
        for (int i = 0; i < s.code.size(); i++) {
            if (!show.get(i)) { if (i > 0 && show.get(i - 1)) out.add("   ..."); continue; }
            Cs2.Ins in = s.code.get(i);
            int[] d = r.depths[i];
            String extra = "";
            if (in.op == 40) extra = " -> args " + java.util.Arrays.toString(w.args(in.i)) + " returns " + java.util.Arrays.toString(w.returns(in.i));
            else if (in.op >= 100) {
                CsTable.Effect e = t.get(in.op);
                extra = CsStack.isSpecial(in.op) ? " (special)" : e == null ? " (unknown)" : " (" + e.pops() + " -> " + e.pushes() + " " + e.source() + ")";
            }
            out.add(String.format("%5d %-10s %-28s %s", i, d == null ? "-" : CsTable.sig(d), in.toString(), extra));
        }
        for (CsStack.Problem p : r.problems) out.add("  ! " + p);
        return out;
    }
}

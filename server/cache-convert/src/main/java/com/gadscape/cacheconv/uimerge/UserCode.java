package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.convert.ScriptConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.ui.CsSim;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.TreeMap;

/**
 * GadScape's own clientscript code (from USER, build 211) prepared for a build-241 script: typed with the UI area's
 * stack simulator ({@link CsSim}, over all USER scripts) so every id it names is tagged, vanilla component constants
 * moved to their BASE numbers, and the 211 -> 241 command changes applied (convert.ScriptConv rules).
 */
public final class UserCode {
    private final UiMergeGen g;

    UserCode(UiMergeGen g) { this.g = g; }

    /** CsSim result of a USER script. */
    CsSim.Result sim(int id) {
        TreeMap<Integer, CsSim.Result> r = g.scripts().results();
        CsSim.Result res = r.get(id);
        if (res == null) throw new IllegalStateException("USER script " + id + " was not analysed");
        return res;
    }

    /** The kind of id USER script {@code id}'s instruction {@code k} names, or null. */
    String tag(int id, Cs2.Script s, int k) {
        Cs2.Ins in = s.code.get(k);
        String implied = RefKinds.implied(in.op);
        if (implied != null) return implied;
        if (in.op != Cs2.ICONST) return null;
        String t = sim(id).constType.get(k);
        if (t == null || t.indexOf('|') >= 0) return null;
        return RefKinds.movable(t) ? t : null;
    }

    /**
     * A USER instruction ready for a 241 script: same opcode and operand, marked GadScape's, tagged; a component
     * constant of a vanilla interface is moved to its BASE number now (custom interfaces follow ctx.ids at build time).
     */
    Cs2.Ins copy(ScriptEdit e, int id, Cs2.Script s, int k) {
        Cs2.Ins in = s.code.get(k);
        Cs2.Ins n = new Cs2.Ins(in.op, in.i, in.s);
        n.l = in.l;
        String t = tag(id, s, k);
        if ("component".equals(t) && n.i > 0) n.i = g.otherComponent(n.i);
        e.doc.meta.put(n, new CsText.Meta(true, t));
        return n;
    }

    /**
     * USER instructions [from, to) as a 241 snippet: copied and tagged, then converted the way ScriptConv converts a
     * whole script (cc_create's extra int, removed commands, hook descriptors) with jumps kept on their targets. A jump
     * may target {@code to} (the instruction after the snippet once inserted) but nothing outside the range.
     */
    List<Cs2.Ins> snippet(ScriptEdit e, int id, int from, int to) {
        Cs2.Script s = Cs2.parse211(g.user.single(Arch.SCRIPTS, id));
        Cs2.Script tmp = new Cs2.Script();
        tmp.name = new byte[]{0};
        for (int k = from; k < to; k++) {
            Cs2.Ins in = s.code.get(k);
            if (Cs2.BRANCH.contains(in.op)) {
                int t = k + 1 + in.i;
                if (t < from || t > to) throw new IllegalStateException("script " + id + ": branch at " + k + " leaves the copied range " + from + ".." + to);
            }
            if (in.op == Cs2.SWITCH) throw new IllegalStateException("script " + id + ": switch inside a copied range");
            if (in.op >= 44 && in.op <= 46) throw new IllegalStateException("script " + id + ": array op inside a copied range");
            tmp.code.add(copy(e, id, s, k));
        }
        Cs2.Ins sentinel = new Cs2.Ins(Cs2.RETURN, 0, null);
        tmp.code.add(sentinel);
        ScriptConv.rewriteRemovedCommands(id, tmp);
        ScriptConv.ccCreate(tmp);
        ScriptConv.hookDescriptors(id, tmp, new ScriptConv.Result());
        if (tmp.code.get(tmp.code.size() - 1) != sentinel) throw new IllegalStateException("snippet end moved");
        tmp.code.remove(tmp.code.size() - 1);
        for (Cs2.Ins in : tmp.code) if (!e.doc.meta.containsKey(in)) e.doc.meta.put(in, new CsText.Meta(true, null));
        return new ArrayList<>(tmp.code);
    }

    /**
     * The whole USER script converted to the 241 layout, every instruction marked GadScape's and tagged, vanilla
     * component constants renumbered (the case where BASE kept the 211 script: only the user's side changed).
     */
    CsText.Doc whole(int id, List<String> notes) {
        Cs2.Script s = Cs2.parse211(g.user.single(Arch.SCRIPTS, id));
        CsText.Doc d = new CsText.Doc(id, s);
        List<Cs2.Ins> code = new ArrayList<>(s.code);
        IdentityHashMap<Cs2.Ins, CsText.Meta> meta = new IdentityHashMap<>();
        for (int k = 0; k < code.size(); k++) {
            Cs2.Ins in = code.get(k);
            String t = tag(id, s, k);
            if ("component".equals(t) && in.i > 0) {
                int to = g.otherComponent(in.i);
                if (to != in.i) notes.add("component constant " + (in.i >>> 16) + ":" + (in.i & 0xffff) + " -> " + (to >>> 16) + ":" + (to & 0xffff) + " (instruction " + k + ")");
                in.i = to;
            }
            meta.put(in, new CsText.Meta(true, t));
        }
        for (Cs2.Ins in : code) if (in.op >= 44 && in.op <= 46) throw new IllegalStateException("script " + id + " uses 211 arrays: port it with the custom UI (ui-convert) instead");
        List<String> did = ScriptConv.rewriteRemovedCommands(id, s);
        for (String x : did) notes.add("241 command change: " + x);
        int cc = ScriptConv.ccCreate(s);
        if (cc > 0) notes.add("cc_create takes an extra int in 241: " + cc + " site(s)");
        ScriptConv.Result r = new ScriptConv.Result();
        ScriptConv.hookDescriptors(id, s, r);
        if (r.descChanged > 0) notes.add("hook descriptors in 241 letters: " + r.descChanged);
        for (Cs2.Ins in : s.code) d.meta.put(in, meta.getOrDefault(in, new CsText.Meta(true, null)));
        return d;
    }

}

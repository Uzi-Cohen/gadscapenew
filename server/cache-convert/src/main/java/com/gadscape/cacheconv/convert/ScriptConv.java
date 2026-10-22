package com.gadscape.cacheconv.convert;

import com.gadscape.cacheconv.convert.Cs2.Ins;
import com.gadscape.cacheconv.convert.Cs2.Script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Converts GadScape 211 clientscripts to the 241 engine and the 241 layout. Ported from rev241/tools/CsAll (which wrote
 * every script of cacheU's archive 12) with the same rules, in the same order:
 * <ol>
 *   <li>arrays: 211 script arrays are five global slots shared by every frame; 241 arrays are objects held in the
 *       script's own object locals. Each script gets a local per array it defines; a script that reads an array it
 *       does not define (directly or through the procs it calls) receives it as an extra object argument, and every
 *       call site passes its own array along ({@link #arrays}, a whole-set analysis);</li>
 *   <li>commands 241 removed: 4105 text_gender -> push "" + 4123; 6212 -> jump 0 (nop); 6210 -> (script 2358: dup +
 *       6201) else pop; 2308 -> pop, pop; 7508 -> 7500 + pop ({@link #rewriteRemovedCommands});</li>
 *   <li>cc_create (100) takes an extra int in 241: push 0 right before it;</li>
 *   <li>hook descriptors: 211 read 's' as string and every other letter as int (a trailing 'Y' is a trigger list);
 *       241 gives other letters their own types, so every non-'s' letter becomes 'i' ({@link #desc}).</li>
 * </ol>
 * Jumps and switch targets are re-pointed so control flow is unchanged, and every output is read back
 * ({@link ScriptTrailer#verify241}).
 */
public final class ScriptConv {
    private ScriptConv() {}

    public static String desc(String d) {
        StringBuilder b = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) { char c = d.charAt(i); b.append(c == 's' || (c == 'Y' && i == d.length() - 1) ? c : 'i'); }
        return b.toString();
    }

    public static boolean looksDesc(String s) { return s.length() < 64 && s.indexOf(' ') < 0 && s.indexOf('<') < 0; }

    /** Result of {@link #convertAll}. */
    public static final class Result {
        /** Converted scripts (241 layout), by id. */
        public final TreeMap<Integer, byte[]> scripts = new TreeMap<>();
        public final List<String> notes = new ArrayList<>();
        public int arrays, ccCreate, descChanged, descIndirect;
        @Override public String toString() {
            return scripts.size() + " scripts, " + arrays + " with arrays moved to locals, " + ccCreate + " cc_create, " + descChanged + " descriptors changed (" + descIndirect + " indirect)";
        }
    }

    /**
     * Converts a set of 211 scripts. The array analysis spans the whole set (pass every script of the source cache so
     * call chains are complete, then use the ids you need). Throws on a script that does not round-trip or does not
     * read back.
     */
    public static Result convertAll(Map<Integer, byte[]> raw211) {
        Result r = new Result();
        Map<Integer, Script> all = new TreeMap<>();
        for (var e : new TreeMap<>(raw211).entrySet()) {
            byte[] raw = e.getValue();
            if (!Arrays.equals(Cs2.write241(Cs2.parse211(raw)), ScriptTrailer.to241(raw))) throw new IllegalStateException("script " + e.getKey() + " does not round-trip");
            all.put(e.getKey(), Cs2.parse211(raw));
        }
        r.arrays = arrays(all, r.notes);
        for (var en : all.entrySet()) {
            int id = en.getKey();
            Script s = en.getValue();
            for (Ins in : s.code) if (in.op == 61 || in.op == 63) throw new IllegalStateException("script " + id + " uses " + in.op);
            rewriteRemovedCommands(id, s);
            r.ccCreate += ccCreate(s);
            hookDescriptors(id, s, r);
            byte[] b = Cs2.write241(s);
            ScriptTrailer.verify241(id, b);
            r.scripts.put(id, b);
        }
        return r;
    }

    /** Rewrites the commands build 241 removed; returns what it did. */
    public static List<String> rewriteRemovedCommands(int id, Script s) {
        List<String> did = new ArrayList<>();
        int k;
        // text_gender (male, female) is gone in 241; 4123 is the same command with a third (no appearance) text, as vanilla 211 script 687 uses
        while ((k = Cs2.find(s, 4105, 0)) >= 0) { Cs2.splice(s, k, Cs2.L(new Ins(3, 0, ""), new Ins(4123, 0, null))); did.add("text_gender->4123"); }
        while ((k = Cs2.find(s, 6212, 0)) >= 0) { Cs2.splice(s, k, Cs2.L(new Ins(Cs2.JUMP, 0, null))); did.add("6212->nop"); }                 // dev reset-zoom: gone
        if (id == 2358) while ((k = Cs2.find(s, 6210, 0)) >= 0) { Ins prev = s.code.get(k - 1);                                                  // zoom slider: 241 sets fixed + resizable
            Cs2.splice(s, k, Cs2.L(new Ins(Cs2.ILOAD, prev.i, null), new Ins(6201, 0, null))); did.add("6210->dup+6201"); }
        while ((k = Cs2.find(s, 6210, 0)) >= 0) { Cs2.splice(s, k, Cs2.L(new Ins(Cs2.POP_INT, 0, null))); did.add("6210->pop"); }             // dev zoom: gone
        while ((k = Cs2.find(s, 2308, 0)) >= 0) { Cs2.splice(s, k, Cs2.L(new Ins(Cs2.POP_INT, 0, null), new Ins(Cs2.POP_INT, 0, null))); did.add("2308->pop,pop"); } // mobile-only setting
        while ((k = Cs2.find(s, 7508, 0)) >= 0) { Cs2.splice(s, k, Cs2.L(new Ins(7500, 0, null), new Ins(Cs2.POP_INT, 0, null))); did.add("7508->7500+pop"); }
        return did;
    }

    /** cc_create: extra trailing int 0. Returns how many were patched. */
    public static int ccCreate(Script s) {
        int n = 0, k = 0;
        while ((k = Cs2.find(s, Cs2.CC_CREATE, k)) >= 0) { Ins cc = s.code.get(k);
            Cs2.splice(s, k, Cs2.L(new Ins(Cs2.ICONST, 0, null), cc)); n++; k += 2; }
        return n;
    }

    /** Hook descriptor strings of cc_seton* (1400-1499) and if_seton* (2400-2499) calls. */
    public static void hookDescriptors(int id, Script s, Result r) {
        for (int i = 0; i < s.code.size(); i++) { int op = s.code.get(i).op;
            Ins d = null;
            if (op >= 1400 && op < 1500) { if (i > 0 && s.code.get(i - 1).op == 3) d = s.code.get(i - 1); }
            else if (op >= 2400 && op < 2500) {
                int p1 = s.code.get(i - 1).op;
                if (i > 1 && s.code.get(i - 2).op == 3 && (p1 == 0 || p1 == 33)) d = s.code.get(i - 2);
                else { for (int j = i - 1; j >= Math.max(0, i - 6); j--) if (s.code.get(j).op == 3) { d = s.code.get(j); break; }
                    r.notes.add("script " + id + " hook " + op + " descriptor found " + (d == null ? "NOWHERE" : "\"" + d.s + "\"")); r.descIndirect++; } }
            else continue;
            if (d == null) { r.notes.add("script " + id + " hook " + op + " at " + i + ": no descriptor"); continue; }
            if (!looksDesc(d.s)) { r.notes.add("script " + id + " hook " + op + ": odd descriptor \"" + d.s + "\""); continue; }
            String n = desc(d.s); if (!n.equals(d.s)) { d.s = n; r.descChanged++; } }
    }

    /**
     * 211 arrays -> 241 object locals (see class doc). Mutates the scripts; returns how many it touched and adds a
     * note for every script that receives arrays as arguments.
     */
    public static int arrays(Map<Integer, Script> all, List<String> notes) {
        Map<Integer, TreeSet<Integer>> defs = new HashMap<>(), uses = new HashMap<>(), calls = new HashMap<>(), need = new HashMap<>();
        for (var e : all.entrySet()) { TreeSet<Integer> d = new TreeSet<>(), u = new TreeSet<>(), c = new TreeSet<>();
            for (Ins in : e.getValue().code) { if (in.op == 44) d.add(in.i >> 16); else if (in.op == 45 || in.op == 46) u.add(in.i); else if (in.op == 40) c.add(in.i); }
            defs.put(e.getKey(), d); uses.put(e.getKey(), u); calls.put(e.getKey(), c);
            TreeSet<Integer> n = new TreeSet<>(u); n.removeAll(d); need.put(e.getKey(), n); }
        for (boolean changed = true; changed; ) { changed = false;
            for (int id : all.keySet()) { TreeSet<Integer> n = need.get(id);
                for (int c : calls.get(id)) { TreeSet<Integer> cn = need.get(c); if (cn == null) continue;
                    for (int k : cn) if (!defs.get(id).contains(k) && n.add(k)) changed = true; } } }
        int touched = 0;
        for (var e : all.entrySet()) { int id = e.getKey(); Script s = e.getValue();
            TreeSet<Integer> d = defs.get(id), n = need.get(id);
            boolean callsNeedy = false; for (int c : calls.get(id)) if (need.containsKey(c) && !need.get(c).isEmpty()) callsNeedy = true;
            if (d.isEmpty() && n.isEmpty() && !callsNeedy) continue;
            touched++;
            int m = s.objArgs, extra = n.size();
            Map<Integer, Integer> slot = new HashMap<>(); int j = 0;
            for (int k : n) slot.put(k, m + j++);
            j = 0; for (int k : d) slot.put(k, s.localObj + extra + j++);
            for (Ins in : s.code) {
                if ((in.op == 35 || in.op == 36) && in.i >= m) in.i += extra;
                else if (in.op == 44) in.i = (slot.get(in.i >> 16) << 16) | (in.i & 0xffff);
                else if (in.op == 45 || in.op == 46) { Integer sl = slot.get(in.i); if (sl == null) throw new IllegalStateException("script " + id + " array " + in.i + " has no slot"); in.i = sl; } }
            for (int k = 0; k < s.code.size(); k++) { Ins in = s.code.get(k);
                if (in.op != 40) continue; TreeSet<Integer> cn = need.get(in.i); if (cn == null || cn.isEmpty()) continue;
                List<Ins> with = new ArrayList<>(); for (int a : cn) with.add(new Ins(35, slot.get(a), null)); with.add(in);
                Cs2.splice(s, k, with); k += with.size() - 1; }
            s.objArgs = m + extra; s.localObj += extra + d.size();
            if (extra > 0) notes.add("script " + id + " receives arrays " + n + " as object args " + m + ".." + (m + extra - 1));
        }
        return touched;
    }
}

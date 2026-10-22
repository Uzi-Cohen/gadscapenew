package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.convert.Cs2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Edits on a build-241 script (BASE's body) that keep every jump and switch target on the same instruction:
 * GadScape's instructions are inserted at anchors found by content, so a recipe documents where an edit goes and fails
 * loudly when BASE no longer has that code. Inserted instructions are marked as GadScape's ({@code u}) with the kind
 * of id their operand is.
 */
public final class ScriptEdit {
    public final CsText.Doc doc;
    private final List<String> log = new ArrayList<>();

    public ScriptEdit(int id, Cs2.Script base) { this.doc = new CsText.Doc(id, base); }

    public Cs2.Script s() { return doc.s; }
    public List<String> log() { return log; }
    public void note(String l) { log.add(l); }

    // ------------------------------------------------------------------ instructions

    /** A GadScape instruction (int operand), with the kind of id the operand is (or null). */
    public Cs2.Ins u(int op, int operand, String tag) {
        Cs2.Ins in = new Cs2.Ins(op, operand, null);
        String t = tag != null ? tag : RefKinds.implied(op);
        doc.meta.put(in, new CsText.Meta(true, t));
        return in;
    }
    public Cs2.Ins u(int op, int operand) { return u(op, operand, null); }
    public Cs2.Ins str(String s) { Cs2.Ins in = new Cs2.Ins(Cs2.SCONST, 0, s); doc.meta.put(in, new CsText.Meta(true, null)); return in; }

    public List<Cs2.Ins> L(Cs2.Ins... a) { return new ArrayList<>(List.of(a)); }

    // ------------------------------------------------------------------ anchors

    /**
     * Index of the only place where the instructions match {@code pattern} (CsDiff keys: "op:operand", "3:\"text",
     * "6:*" for any jump), starting at the instruction {@code offset} into the pattern. Throws when there is no
     * match or more than one.
     */
    public int find(int offset, String... pattern) {
        int found = -1, n = doc.s.code.size();
        for (int i = 0; i + pattern.length <= n; i++) {
            boolean ok = true;
            for (int k = 0; k < pattern.length && ok; k++) ok = matches(doc.s.code.get(i + k), pattern[k]);
            if (!ok) continue;
            if (found >= 0) throw new IllegalStateException("script " + doc.id + ": anchor " + String.join(" ", pattern) + " matches at " + found + " and " + i);
            found = i;
        }
        if (found < 0) throw new IllegalStateException("script " + doc.id + ": anchor " + String.join(" ", pattern) + " not found in BASE");
        return found + offset;
    }

    /** Every index where the pattern starts. */
    public List<Integer> findAll(String... pattern) {
        List<Integer> r = new ArrayList<>();
        for (int i = 0; i + pattern.length <= doc.s.code.size(); i++) {
            boolean ok = true;
            for (int k = 0; k < pattern.length && ok; k++) ok = matches(doc.s.code.get(i + k), pattern[k]);
            if (ok) r.add(i);
        }
        return r;
    }

    static boolean matches(Cs2.Ins in, String p) {
        if (p.equals("*")) return true;
        int colon = p.indexOf(':');
        int op = Integer.parseInt(p.substring(0, colon));
        if (in.op != op) return false;
        String v = p.substring(colon + 1);
        if (v.equals("*")) return true;
        if (op == Cs2.SCONST) return v.startsWith("\"") && in.s.equals(v.substring(1));
        if (op == Cs2.LCONST) return in.l == Long.parseLong(v);
        return in.i == Integer.parseInt(v);
    }

    // ------------------------------------------------------------------ edits

    /** Inserts before instruction {@code at}: jumps to {@code at} now land on the first inserted instruction. */
    public void insertBefore(int at, List<Cs2.Ins> ins, String why) {
        List<Cs2.Ins> with = new ArrayList<>(ins);
        with.add(doc.s.code.get(at));
        Cs2.splice(doc.s, at, with);
        log.add("inserted " + ins.size() + " instruction(s) before BASE instruction " + at + ": " + why);
    }

    /** Inserts after instruction {@code at}: jumps to {@code at} still land on it. */
    public void insertAfter(int at, List<Cs2.Ins> ins, String why) {
        List<Cs2.Ins> with = new ArrayList<>();
        with.add(doc.s.code.get(at));
        with.addAll(ins);
        Cs2.splice(doc.s, at, with);
        log.add("inserted " + ins.size() + " instruction(s) after BASE instruction " + at + ": " + why);
    }

    /** Sets an int operand (the instruction becomes GadScape's). */
    public void operand(int at, int value, String tag, String why) {
        Cs2.Ins in = doc.s.code.get(at);
        int old = in.i;
        in.i = value;
        doc.meta.put(in, new CsText.Meta(true, tag != null ? tag : RefKinds.implied(in.op)));
        log.add("instruction " + at + " (" + CsText.name(in.op) + ") operand " + old + " -> " + value + ": " + why);
    }

    /**
     * Removes BASE instructions [from, to) that GadScape's edit replaced and nothing reaches any more: no jump or switch
     * case outside the range may land inside it (one landing on {@code from} is refused too; insert the replacement
     * before {@code from} first, so those land on it). Jumps inside the range go with it.
     */
    public void remove(int from, int to, String why) {
        List<Cs2.Ins> code = doc.s.code;
        if (from < 0 || to > code.size() || from >= to) throw new IllegalStateException("script " + doc.id + ": bad range " + from + ".." + to);
        for (int k = 0; k < code.size(); k++) {
            if (k >= from && k < to) continue;
            Cs2.Ins in = code.get(k);
            if (Cs2.BRANCH.contains(in.op)) {
                int t = k + 1 + in.i;
                if (t >= from && t < to) throw new IllegalStateException("script " + doc.id + ": instruction " + k + " jumps into the removed range " + from + ".." + to);
            } else if (in.op == Cs2.SWITCH) {
                for (int off : doc.s.switches.get(in.i).values()) {
                    int t = k + 1 + off;
                    if (t >= from && t < to) throw new IllegalStateException("script " + doc.id + ": switch at " + k + " lands in the removed range " + from + ".." + to);
                }
            }
        }
        for (int k = from; k < to; k++) if (code.get(k).op == Cs2.SWITCH) throw new IllegalStateException("script " + doc.id + ": cannot remove a switch (its table would stay)");
        for (int k = from; k < to; k++) Cs2.splice(doc.s, from, new ArrayList<>());
        log.add("removed BASE instructions " + from + ".." + (to - 1) + " (" + (to - from) + "): " + why);
    }

    /** Appends a block after the last instruction (reached only through jumps or switch cases); returns its first index. */
    public int append(List<Cs2.Ins> ins, String why) {
        int at = doc.s.code.size();
        doc.s.code.addAll(ins);
        log.add("appended " + ins.size() + " instruction(s) at " + at + ": " + why);
        return at;
    }

    /** A new case of GadScape's whose key is an id of kind {@code tag} (followed through the id map at build time). */
    public void switchCase(int sw, int key, String tag, int target, String why) {
        switchCase(sw, key, target, why);
        doc.switchTags.put(doc.s.code.get(sw).i + ":" + key, tag);
    }

    /** Points case {@code key} of the switch at instruction {@code sw} to instruction {@code target}. */
    public void switchCase(int sw, int key, int target, String why) {
        Cs2.Ins in = doc.s.code.get(sw);
        if (in.op != Cs2.SWITCH) throw new IllegalStateException("script " + doc.id + ": instruction " + sw + " is not a switch");
        LinkedHashMap<Integer, Integer> t = doc.s.switches.get(in.i);
        Integer old = t.put(key, target - sw - 1);
        log.add("switch case " + key + " -> instruction " + target + (old == null ? " (new case)" : " (was -> " + (sw + 1 + old) + ")") + ": " + why);
    }

    /** Jump target of the case {@code key} of the switch at {@code sw}, or -1 (default). */
    public int caseTarget(int sw, int key) {
        Integer off = doc.s.switches.get(doc.s.code.get(sw).i).get(key);
        return off == null ? -1 : sw + 1 + off;
    }

    /** A new int local. */
    public int newIntLocal() { return doc.s.localInt++; }

    public byte[] bytes() { return Cs2.write241(doc.s); }
}

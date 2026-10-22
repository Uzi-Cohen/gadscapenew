package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.convert.Cs2.Ins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Stack simulator and constant typer for build-211 clientscripts (audit finding B8: table-driven, with stack checks).
 * <p>
 * It walks a script's instructions in order, as the client does and as RuneStar's interpreter does (scripts compiled by
 * the Jagex/RuneStar compilers have an empty stack at every jump target), keeping for every stack slot where the value
 * came from: an int/string constant instruction, an argument of the script, or "computed". When a command consumes a
 * value with a known type (a {@code graphic} for cc_setgraphic, a {@code clientscript} id for a hook setter, a
 * {@code component}, an {@code enum} for the enum command, the argument types of a called proc, ...), the constant that
 * produced it gets that type. That is what lets the converter rewrite relocated ids inside scripts without guessing
 * (spec rule 6: "constant pushes typed as the moved kind").
 * <p>
 * The result also gives the script's return counts (int, string), the types of its int arguments (inferred from how
 * they are used, for callers and IF3 hooks), its gosub and hook-setter sites, and every problem found (stack underflow,
 * unknown command, returns that disagree, values left at a jump target).
 */
public final class CsSim {
    /** Provenance: >= 0 = index of the constant instruction, UNKNOWN = computed, <= -2 = int local k (-2 - k; k < intArgs = argument). */
    public static final int UNKNOWN = -1;
    static int arg(int k) { return -2 - k; }
    static boolean isArg(int p) { return p <= -2; }
    static int argIndex(int p) { return -2 - p; }

    /** Event-property constants (event_com, event_opindex, ...) passed as hook args are not ids. */
    public static boolean isEventConstant(int v) { return v <= Integer.MIN_VALUE + 64 && v != Integer.MIN_VALUE; }

    /** What the simulator needs to know about the rest of the script set and the configs. */
    public interface World {
        /** {intArgs, objArgs} of a script (211 trailer), or null if it does not exist. */
        int[] args(int script);
        /** {intReturns, stringReturns} of a script, or null if unknown (missing, recursive or not analysable). */
        int[] returns(int script);
        /** Inferred type per int argument of a script (entries may be null), or null. */
        String[] argTypes(int script);
        /** Whether a param config holds strings; null if the param is unknown. */
        Boolean paramIsString(int param);
        /** String flags of a dbtable column's tuple (table, column), or null if unknown. */
        boolean[] dbColumn(int table, int column);
    }

    /** A gosub (op 40) site. */
    public record Call(int index, int target, int[] intArgProv) {}

    /** A hook setter (cc_seton* 1400-1499, if_seton* 2400-2499) site. */
    public record Hook(int index, int op, int scriptProv, String desc, int[] intArgProv, int[] triggerProv, int componentProv) {}

    public static final class Result {
        public final int id;
        public boolean complete = true;
        public String problem;
        public int stoppedAt = -1;
        /** {ints, strings} at the first return, or null if the script never returns a value set. */
        public int[] returns;
        /** Int constant instruction index -> the type the consuming command gives it. */
        public final TreeMap<Integer, String> constType = new TreeMap<>();
        /** Type per int argument (null = unknown). */
        public String[] argTypes;
        public final List<Call> calls = new ArrayList<>();
        public final List<Hook> hooks = new ArrayList<>();
        /** Jump targets reached with a non-empty stack (should be 0 for compiler output). */
        public int dirtyLabels;
        /** Constants typed twice with different types: "index:a/b". */
        public final List<String> conflicts = new ArrayList<>();

        Result(int id, int intArgs) { this.id = id; this.argTypes = new String[intArgs]; }

        @Override public String toString() {
            return "script " + id + (complete ? "" : " INCOMPLETE at " + stoppedAt + ": " + problem) + " returns " + Arrays.toString(returns)
                    + " args " + Arrays.toString(argTypes) + " typed " + constType.size();
        }
    }

    private static final Set<Integer> HANDLED = new HashSet<>(List.of(0, 1, 2, 3, 6, 7, 8, 9, 10, 21, 25, 27, 31, 32, 33, 34, 35, 36, 37, 38, 39,
            40, 42, 43, 44, 45, 46, 47, 48, 49, 50, 60, 74, 76, 3408, 6513, 6514, 6515, 6516, 7500, 7501, 7502, 7503, 7504, 7505, 7506, 7507, 7508, 7509));

    /** Whether the op's stack effect is coded here rather than in the table. */
    public static boolean isHandledInCode(int op) { return HANDLED.contains(op) || isHook(op); }

    public static boolean isHook(int op) { return (op >= 1400 && op < 1500) || (op >= 2400 && op < 2500); }

    /** cs2 type for a type character (211 descriptors and enum key/value types). */
    public static String typeOfChar(char c) {
        return switch (c) {
            case 'i' -> "int"; case '1' -> "boolean"; case 'A' -> "seq"; case 'C' -> "colour"; case 'I' -> "component";
            case 'K' -> "dbrow"; case 'M' -> "midi"; case 'O' -> "namedobj"; case 'P' -> "synth"; case 'c' -> "coord";
            case 'd' -> "graphic"; case 'f' -> "fontmetrics"; case 'g' -> "enum"; case 'j' -> "jingle"; case 'l' -> "loc";
            case 'm' -> "model"; case 'n' -> "npc"; case 'o' -> "obj"; case 's' -> "string"; case 't' -> "spotanim";
            case 'v' -> "inv"; case 'x' -> "texture"; case 'z' -> "char"; case 'J' -> "struct"; case 'S' -> "stat";
            case 'a' -> "interface"; case 'y' -> "category"; case 'R' -> "area"; case '`' -> "maparea"; case 'µ' -> "mapelement";
            case '£' -> "mapsceneicon"; case '×' -> "hitmark";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ simulation

    private final CsCommands table;
    private final World world;
    private final Cs2.Script s;
    private final Result r;
    private final Set<Integer> labels = new HashSet<>();
    private int[] is = new int[64], ss = new int[64];
    private int isp, ssp;
    /** Array type char per array slot defined in this script (op 44 operand low 16 bits). */
    private final char[] arrayType = new char[8];
    /** Type per int local (arguments first), from how its values are used; constants stored into it get it at the end. */
    private final String[] localType;
    /** Constants stored into each int local, and locals copied into each local (types flow both ways). */
    private final List<List<Integer>> storedConst = new ArrayList<>(), storedLocal = new ArrayList<>();

    private CsSim(CsCommands table, World world, int id, Cs2.Script s) {
        this.table = table; this.world = world; this.s = s;
        this.r = new Result(id, s.intArgs);
        int n = Math.max(s.localInt, s.intArgs);
        this.localType = new String[n];
        for (int i = 0; i < n; i++) { storedConst.add(new ArrayList<>()); storedLocal.add(new ArrayList<>()); }
    }

    /** Simulates one 211 script. */
    public static Result run(CsCommands table, World world, int id, Cs2.Script s) {
        CsSim sim = new CsSim(table, world, id, s);
        sim.go();
        sim.resolveLocals();
        return sim.r;
    }

    /** Spreads local types over local-to-local copies, types the constants stored into typed locals, sets argTypes. */
    private void resolveLocals() {
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int k = 0; k < localType.length; k++) for (int j : storedLocal.get(k)) {
                if (localType[k] != null && !localType[k].equals("int") && (localType[j] == null || localType[j].equals("int"))) { localType[j] = localType[k]; changed = true; }
                if (localType[j] != null && !localType[j].equals("int") && (localType[k] == null || localType[k].equals("int"))) { localType[k] = localType[j]; changed = true; }
            }
        }
        for (int k = 0; k < localType.length; k++) if (localType[k] != null) for (int c : storedConst.get(k)) type(c, localType[k]);
        for (int k = 0; k < s.intArgs && k < localType.length; k++) r.argTypes[k] = localType[k];
    }

    private void stop(int at, String why) {
        if (!r.complete) return;
        r.complete = false; r.problem = why; r.stoppedAt = at;
    }

    private void pushI(int prov) { if (isp == is.length) is = Arrays.copyOf(is, isp * 2); is[isp++] = prov; }
    private void pushS(int prov) { if (ssp == ss.length) ss = Arrays.copyOf(ss, ssp * 2); ss[ssp++] = prov; }
    private int popI() { if (isp == 0) throw new Underflow("int"); return is[--isp]; }
    private int popS() { if (ssp == 0) throw new Underflow("string"); return ss[--ssp]; }

    private static final class Underflow extends RuntimeException {
        Underflow(String what) { super(what + " stack underflow", null, false, false); }
    }

    /** Gives the value's producer a type: a constant gets typed, an argument records the type for callers. */
    private void type(int prov, String type) {
        if (type == null || prov == UNKNOWN) return;
        if (isArg(prov)) {
            int k = argIndex(prov);
            if (k < localType.length) {
                String cur = localType[k];
                if (cur == null || cur.equals("int")) localType[k] = type;
                else if (!cur.equals(type) && !type.equals("int")) localType[k] = unify(cur, type);
            }
            return;
        }
        Ins in = s.code.get(prov);
        if (in.op != Cs2.ICONST) return;
        if (in.i == -1 || isEventConstant(in.i)) return;          // null / event values are never ids to remap
        String cur = r.constType.get(prov);
        if (cur == null || cur.equals("int")) r.constType.put(prov, type);
        else if (!cur.equals(type) && !type.equals("int")) { r.conflicts.add(prov + ":" + cur + "/" + type); r.constType.put(prov, unify(cur, type)); }
    }

    private static String unify(String a, String b) {
        if ((a.equals("obj") && b.equals("namedobj")) || (a.equals("namedobj") && b.equals("obj"))) return "obj";
        if ((a.equals("graphic") && b.equals("fontmetrics")) || (a.equals("fontmetrics") && b.equals("graphic"))) return "fontmetrics";
        java.util.TreeSet<String> t = new java.util.TreeSet<>(Arrays.asList(a.split("\\|")));
        t.addAll(Arrays.asList(b.split("\\|")));
        return String.join("|", t);
    }

    /** Instructions reachable from the entry (fall-through, branches, switch cases), and the jump targets among them. */
    private boolean[] reachability() {
        List<Ins> code = s.code;
        int n = code.size();
        boolean[] reach = new boolean[n];
        java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>();
        if (n > 0) todo.add(0);
        while (!todo.isEmpty()) {
            int k = todo.poll();
            if (k < 0 || k >= n || reach[k]) continue;
            reach[k] = true;
            Ins in = code.get(k);
            if (Cs2.BRANCH.contains(in.op)) { int t = k + 1 + in.i; labels.add(t); todo.add(t); }
            if (in.op == Cs2.SWITCH && in.i >= 0 && in.i < s.switches.size())
                for (int off : s.switches.get(in.i).values()) { int t = k + 1 + off; labels.add(t); todo.add(t); }
            if (in.op != 21 && in.op != Cs2.JUMP) todo.add(k + 1);
        }
        return reach;
    }

    private void go() {
        List<Ins> code = s.code;
        boolean[] reach = reachability();
        int k = 0;
        boolean live = true;   // the previous instruction falls through to this one
        try {
            for (; k < code.size(); k++) {
                if (!reach[k]) { live = false; continue; }      // dead code (e.g. the compiler's default return)
                if (labels.contains(k)) {
                    if (!live) { isp = 0; ssp = 0; }
                    else if (isp != 0 || ssp != 0) r.dirtyLabels++;
                }
                Ins in = code.get(k);
                if (!step(k, in)) return;
                live = in.op != 21 && in.op != Cs2.JUMP;
            }
        } catch (Underflow u) {
            stop(k, u.getMessage() + " at op " + code.get(k).op);
        }
    }

    private boolean step(int k, Ins in) {
        int op = in.op;
        switch (op) {
            case 0 -> pushI(k);                                   // iconst
            case 3 -> pushS(k);                                   // sconst
            case 1, 25, 42, 74, 76 -> pushI(UNKNOWN);             // varp / varbit / varc int / clan vars
            case 2, 27, 43 -> popI();
            case 47, 49 -> pushS(UNKNOWN);                        // varc string (old/new)
            case 48, 50 -> popS();
            case 33 -> pushI(in.i >= 0 && in.i < localType.length ? arg(in.i) : UNKNOWN);
            case 34 -> {
                int p = popI();
                if (in.i >= 0 && in.i < localType.length) {
                    if (p >= 0) storedConst.get(in.i).add(p);
                    else if (isArg(p) && argIndex(p) < localType.length) storedLocal.get(in.i).add(argIndex(p));
                }
            }
            case 35 -> pushS(UNKNOWN);
            case 36 -> popS();
            case 37 -> { for (int j = 0; j < in.i; j++) popS(); pushS(UNKNOWN); }
            case 38 -> popI();
            case 39 -> popS();
            case 6 -> { }
            case 7, 8, 9, 10, 31, 32 -> { popI(); popI(); }
            case 60 -> popI();
            case 21 -> {
                int[] ret = {isp, ssp};
                if (r.returns == null) r.returns = ret;
                else if (!Arrays.equals(r.returns, ret)) { stop(k, "returns " + Arrays.toString(ret) + " after " + Arrays.toString(r.returns)); return false; }
                isp = 0; ssp = 0;
            }
            case 44 -> { popI(); int slot = in.i >> 16; if (slot >= 0 && slot < arrayType.length) arrayType[slot] = (char) (in.i & 0xffff); }
            case 45 -> { popI(); pushI(UNKNOWN); }
            case 46 -> { int v = popI(); popI(); int slot = in.i; if (slot >= 0 && slot < arrayType.length && arrayType[slot] != 0) type(v, typeOfChar(arrayType[slot])); }
            case 40 -> { return gosub(k, in.i); }
            case 3408 -> { return enumOp(k); }
            case 6513, 6514, 6515, 6516 -> { return paramOp(k, op); }
            case 7500, 7507, 7508, 7509 -> { return dbFind(k, op); }
            case 7501 -> pushI(UNKNOWN);
            case 7502 -> { return dbGetField(k); }
            case 7503 -> { int col = popI(); type(col, "dbcolumn"); int row = popI(); type(row, "dbrow"); pushI(UNKNOWN); }
            case 7504 -> { int t = popI(); type(t, "dbtable"); pushI(UNKNOWN); }
            case 7505 -> { int row = popI(); type(row, "dbrow"); pushI(UNKNOWN); }
            case 7506 -> { popI(); pushI(UNKNOWN); }
            default -> {
                if (isHook(op)) return hook(k, op);
                CsCommands.Cmd c = table.cmd(op);
                if (c == null) { stop(k, "unknown command " + op); return false; }
                for (int j = c.argString().length - 1; j >= 0; j--) {
                    if (c.argString()[j]) popS(); else type(popI(), c.argType()[j]);
                }
                for (boolean str : c.defString()) if (str) pushS(UNKNOWN); else pushI(UNKNOWN);
            }
        }
        return true;
    }

    private boolean gosub(int k, int target) {
        int[] a = world.args(target);
        if (a == null) { stop(k, "gosub to missing script " + target); return false; }
        int[] ret = world.returns(target);
        if (ret == null) { stop(k, "gosub " + target + ": unknown returns"); return false; }
        String[] at = world.argTypes(target);
        for (int j = 0; j < a[1]; j++) popS();
        int[] prov = new int[a[0]];
        for (int j = a[0] - 1; j >= 0; j--) { prov[j] = popI(); if (at != null && j < at.length) type(prov[j], at[j]); }
        r.calls.add(new Call(k, target, prov));
        for (int j = 0; j < ret[0]; j++) pushI(UNKNOWN);
        for (int j = 0; j < ret[1]; j++) pushS(UNKNOWN);
        return true;
    }

    private int constValue(int prov) { return prov >= 0 && s.code.get(prov).op == Cs2.ICONST ? s.code.get(prov).i : Integer.MIN_VALUE; }

    private boolean enumOp(int k) {
        int key = popI(), enumId = popI(), vt = popI(), kt = popI();
        type(enumId, "enum");
        int vtc = constValue(vt), ktc = constValue(kt);
        if (vtc == Integer.MIN_VALUE) { stop(k, "enum with computed value type"); return false; }
        if (ktc != Integer.MIN_VALUE) { String t = typeOfChar((char) (ktc & 0xff)); if (t != null) type(key, t); }
        if (vtc == 's') pushS(UNKNOWN); else pushI(UNKNOWN);
        return true;
    }

    private boolean paramOp(int k, int op) {
        int param = popI(), recv = popI();
        type(param, "param");
        type(recv, switch (op) { case 6513 -> "npc"; case 6514 -> "loc"; case 6515 -> "obj"; default -> "struct"; });
        int pid = constValue(param);
        Boolean str = pid == Integer.MIN_VALUE ? null : world.paramIsString(pid);
        if (str == null) { stop(k, "param op with " + (pid == Integer.MIN_VALUE ? "computed" : "unknown") + " param " + (pid == Integer.MIN_VALUE ? "" : pid)); return false; }
        if (str) pushS(UNKNOWN); else pushI(UNKNOWN);
        return true;
    }

    /**
     * db_find* (7500 with count, 7507 refine with count, 7508, 7509): the script pushes the dbcolumn, the value, then
     * the value's type id (36 = string); the client pops the type, the value of that type, then the column.
     */
    private boolean dbFind(int k, int op) {
        int tp = popI();
        int typeId = constValue(tp);
        if (typeId == Integer.MIN_VALUE) { stop(k, "db_find with a computed value type"); return false; }
        if (typeId == 36) popS(); else type(popI(), typeOfId(typeId));
        type(popI(), "dbcolumn");
        if (op == 7500 || op == 7507) pushI(UNKNOWN);
        return true;
    }

    /** cs2 type for a ScriptVarType id (dbtable column types, db_find value types). */
    public static String typeOfId(int id) {
        return switch (id) {
            case 0 -> "int"; case 1 -> "boolean"; case 6 -> "seq"; case 7 -> "colour"; case 9 -> "component"; case 10 -> "idkit";
            case 11 -> "midi"; case 13 -> "namedobj"; case 14 -> "synth"; case 22 -> "coord"; case 23 -> "graphic"; case 25 -> "fontmetrics";
            case 26 -> "enum"; case 28 -> "jingle"; case 30 -> "loc"; case 31 -> "model"; case 32 -> "npc"; case 33 -> "obj"; case 36 -> "string";
            case 37 -> "spotanim"; case 39 -> "inv"; case 40 -> "texture"; case 42 -> "char"; case 55 -> "mapsceneicon"; case 59 -> "mapelement";
            case 62 -> "hitmark"; case 73 -> "struct"; case 74 -> "dbrow";
            default -> "int";
        };
    }

    private int tupleOf(int prov) { int v = constValue(prov); return v == Integer.MIN_VALUE ? 0 : v & 0xf; }

    private boolean[] column(int prov) {
        int v = constValue(prov);
        if (v == Integer.MIN_VALUE || v < 0) return null;
        return world.dbColumn(v >>> 12, (v >>> 4) & 0xff);
    }

    /** db_getfield(dbrow, dbcolumn, listindex): pushes the column's tuple. */
    private boolean dbGetField(int k) {
        popI();
        int colProv = popI();
        type(colProv, "dbcolumn");
        type(popI(), "dbrow");
        boolean[] col = column(colProv);
        if (col == null) { stop(k, "db_getfield on a computed or unknown column"); return false; }
        for (boolean str : col) if (str) pushS(UNKNOWN); else pushI(UNKNOWN);
        return true;
    }

    private boolean hook(int k, int op) {
        int comp = UNKNOWN;
        if (op >= 2000) { comp = popI(); type(comp, "component"); }
        int dp = popS();
        if (dp < 0 || s.code.get(dp).op != Cs2.SCONST) { stop(k, "hook " + op + " with computed descriptor"); return false; }
        String desc = s.code.get(dp).s;
        int[] trig = new int[0];
        if (!desc.isEmpty() && desc.charAt(desc.length() - 1) == 'Y') {
            int n = constValue(popI());
            if (n == Integer.MIN_VALUE || n < 0 || n > 64) { stop(k, "hook " + op + " with computed trigger count"); return false; }
            trig = new int[n];
            String tt = triggerType(op);
            for (int j = n - 1; j >= 0; j--) { trig[j] = popI(); type(trig[j], tt); }
            desc = desc.substring(0, desc.length() - 1);
        }
        int ints = 0; for (int j = 0; j < desc.length(); j++) if (desc.charAt(j) != 's') ints++;
        int[] prov = new int[ints];
        int ii = ints;
        for (int j = desc.length() - 1; j >= 0; j--) {
            char c = desc.charAt(j);
            if (c == 's') popS();
            else { int p = popI(); prov[--ii] = p; String t = typeOfChar(c); if (t != null && !t.equals("int")) type(p, t); }
        }
        int sp = popI();
        type(sp, "clientscript");
        int target = constValue(sp);
        if (target >= 0) {
            String[] at = world.argTypes(target);
            if (at != null) for (int j = 0; j < prov.length && j < at.length; j++) if (constValue(prov[j]) == Integer.MIN_VALUE || !isEventConstant(constValue(prov[j]))) type(prov[j], at[j]);
        }
        r.hooks.add(new Hook(k, op, sp, s.code.get(dp).s, prov, trig, comp));
        return true;
    }

    private static String triggerType(int op) {
        int base = op % 1000;
        return switch (base) { case 407 -> "varp"; case 414 -> "inv"; case 415 -> "stat"; default -> "int"; };
    }
}

package com.gadscape.cacheconv.verify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stack effects of the build-241 clientscript commands: how many values each opcode pops from and pushes onto the int,
 * object (string) and long stacks. Loaded from {@code verify/cs2-241-stack.tsv}, which {@link CsLearn} derives from the
 * stock 241 cache: it starts from the 211-era RuneStar table and keeps only effects under which every stock script is
 * balanced (empty stack at every statement boundary), learning the commands 241 added or changed.
 * <p>
 * Opcodes whose effect depends on an operand or on constants (gosub, join_string, switch, return, branches, hook
 * setters, enum, the param commands, the db commands) are "special": {@link CsStack} handles them itself; the table
 * lists them for documentation only.
 * <pre>
 *   # opcode	pops	pushes	source	name
 *   1100	ii	-	211	cc_setscrollpos
 *   40	special	gosub	core	gosub
 * </pre>
 * pops/pushes are strings over {@code i} (int), {@code s} (object/string) and {@code l} (long), bottom to top; "-" is
 * none.
 */
public final class CsTable {
    public static final int INT = 0, OBJ = 1, LONG = 2;
    public static final String RESOURCE = "/verify/cs2-241-stack.tsv";

    /** One regular command's effect. {@code pop}/{@code push} are counts per stack (int, obj, long). */
    public record Effect(int op, String pops, String pushes, String source, String name) {
        public int[] pop() { return counts(pops); }
        public int[] push() { return counts(pushes); }
        public boolean special() { return "special".equals(pops); }
        /** Pushes nothing: a statement on its own (the stack is empty after it in compiled code). */
        public boolean isVoid() { return !special() && (pushes.isEmpty() || pushes.equals("-")); }
        @Override public String toString() { return op + " " + (pops.isEmpty() ? "-" : pops) + " -> " + (pushes.isEmpty() ? "-" : pushes) + " (" + source + ")"; }
    }

    private final TreeMap<Integer, Effect> effects = new TreeMap<>();
    private final TreeMap<Integer, int[][]> counts = new TreeMap<>();

    public CsTable() {}

    public CsTable(Iterable<Effect> e) { for (Effect x : e) put(x); }

    public void put(Effect e) {
        effects.put(e.op, e);
        if (!e.special()) counts.put(e.op, new int[][]{e.pop(), e.push()});
        else counts.remove(e.op);
    }

    public void remove(int op) { effects.remove(op); counts.remove(op); }

    public Effect get(int op) { return effects.get(op); }

    /** {pops, pushes} (each int/obj/long counts) of a regular command, or null (unknown or special). */
    public int[][] counts(int op) { return counts.get(op); }

    public boolean has(int op) { return effects.containsKey(op); }

    public Map<Integer, Effect> all() { return java.util.Collections.unmodifiableMap(effects); }

    public CsTable copy() { CsTable t = new CsTable(); for (Effect e : effects.values()) t.put(e); return t; }

    /** Counts per stack of a signature string ("iis" = 2 ints, 1 obj). */
    public static int[] counts(String sig) {
        int[] c = new int[3];
        if (sig == null || sig.equals("-")) return c;
        for (int i = 0; i < sig.length(); i++) {
            switch (sig.charAt(i)) {
                case 'i' -> c[INT]++;
                case 's' -> c[OBJ]++;
                case 'l' -> c[LONG]++;
                default -> throw new IllegalArgumentException("bad stack signature " + sig);
            }
        }
        return c;
    }

    /** Signature string of counts ({1, 1, 0} -> "is"; empty -> "-"). */
    public static String sig(int[] c) {
        StringBuilder s = new StringBuilder();
        s.append("i".repeat(c[INT])).append("s".repeat(c[OBJ])).append("l".repeat(c[LONG]));
        return s.isEmpty() ? "-" : s.toString();
    }

    // ------------------------------------------------------------------ io

    /** The table shipped with the module. */
    public static CsTable load() {
        try (InputStream in = CsTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("resource " + RESOURCE + " missing (run verify.CsLearn)");
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static CsTable parse(List<String> lines) {
        CsTable t = new CsTable();
        for (String l : lines) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] f = l.split("\t", -1);
            if (f.length < 4) throw new IllegalArgumentException("bad table line: " + l);
            t.put(new Effect(Integer.parseInt(f[0].trim()), f[1].trim(), f[2].trim(), f[3].trim(), f.length > 4 ? f[4].trim() : ""));
        }
        return t;
    }

    public List<String> format(List<String> header) {
        List<String> out = new ArrayList<>(header);
        out.add("# opcode\tpops\tpushes\tsource\tname");
        for (Effect e : effects.values())
            out.add(e.op + "\t" + (e.pops.isEmpty() ? "-" : e.pops) + "\t" + (e.pushes.isEmpty() ? "-" : e.pushes) + "\t" + e.source + "\t" + e.name);
        return out;
    }

    /**
     * Reads a RuneStar-style 211 command table (opcode, name, args, defs, dot; args/defs like {@code I:int,S:string}).
     * {@code #hook}/{@code #special} rows are skipped (CsStack handles those opcodes itself).
     */
    public static List<Effect> parseRuneStar(List<String> lines, String source) {
        List<Effect> out = new ArrayList<>();
        for (String l : lines) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] f = l.split("\t", -1);
            if (f.length < 4) continue;
            out.add(new Effect(Integer.parseInt(f[0].trim()), runeStarSig(f[2].trim()), runeStarSig(f[3].trim()), source, f[1].trim()));
        }
        return out;
    }

    private static String runeStarSig(String s) {
        if (s.equals("-") || s.isEmpty()) return "-";
        StringBuilder r = new StringBuilder();
        for (String p : s.split(",")) {
            char c = p.charAt(0);
            r.append(c == 'S' ? 's' : c == 'L' ? 'l' : 'i');
        }
        // canonical order: ints, objs, longs (only the counts matter for the stack check)
        return sig(counts(r.toString()));
    }

    @Override public String toString() { return "CsTable(" + effects.size() + " opcodes)"; }

    /** Ops below 100 (the interpreter's core instructions) with a fixed effect. */
    public static List<Effect> core() {
        List<Effect> c = new ArrayList<>();
        Object[][] rows = {
                {0, "-", "i", "push_constant_int"}, {1, "-", "i", "push_var"}, {2, "i", "-", "pop_var"},
                {3, "-", "s", "push_constant_string"}, {25, "-", "i", "push_varbit"}, {27, "i", "-", "pop_varbit"},
                {33, "-", "i", "push_int_local"}, {34, "i", "-", "pop_int_local"}, {38, "i", "-", "pop_int_discard"}, {39, "s", "-", "pop_string_discard"},
                {42, "-", "i", "push_varc_int"}, {43, "i", "-", "pop_varc_int"}, {47, "-", "s", "push_varc_string_old"},
                {48, "s", "-", "pop_varc_string_old"}, {51, "-", "l", "push_varc_long"}, {52, "l", "-", "pop_varc_long"}, {61, "-", "l", "push_constant_long"},
                {63, "-", "s", "push_null"}, {64, "-", "l", "push_var_long"}, {65, "l", "-", "pop_var_long"},
                {66, "-", "l", "push_long_local"}, {67, "l", "-", "pop_long_local"},
                {74, "-", "i", "push_varclansetting"}, {76, "-", "i", "push_varclan"},
        };
        for (Object[] r : rows) c.add(new Effect((Integer) r[0], (String) r[1], (String) r[2], "core", (String) r[3]));
        Object[][] special = {
                {6, "jump"}, {7, "branch_not"}, {8, "branch_equals"}, {9, "branch_less_than"}, {10, "branch_greater_than"},
                {21, "return"}, {31, "branch_less_than_or_equals"}, {32, "branch_greater_than_or_equals"}, {37, "join_string"},
                {40, "gosub_with_params"}, {60, "switch"}, {68, "long_branch_not"}, {69, "long_branch_equals"},
                {70, "long_branch_less_than"}, {71, "long_branch_greater_than"}, {72, "long_branch_less_than_or_equals"},
                {73, "long_branch_greater_than_or_equals"}, {3408, "enum"}, {6513, "nc_param"}, {6514, "lc_param"},
                {6515, "oc_param"}, {6516, "struct_param"}, {7500, "db_find"}, {7502, "db_getfield"}, {7507, "db_find_refine"},
                {44, "define_array"}, {45, "push_array_int"}, {46, "pop_array_int"}, {35, "push_string_local"}, {36, "pop_string_local"},
                {49, "push_varc_string"}, {50, "pop_varc_string"}, {8005, "array_indexof"}, {8007, "array_lastindexof"}, {8010, "array_fill"},
                {8011, "array_value_op"}, {8018, "string_split"}, {8020, "array_make"}, {8021, "array_make"}, {8022, "array_create"},
                {8023, "array_push"}, {8024, "array_insert"}, {8025, "array_value_op"}, {8026, "array_remove"},
                {1703, "cc_param"}, {2703, "if_param"}, {1704, "cc_setparam"}, {2704, "if_setparam"}, {2929, "descriptor_command"},
        };
        for (Object[] r : special) c.add(new Effect((Integer) r[0], "special", (String) r[1], "core", (String) r[1]));
        return c;
    }

    static boolean isHookSetter(int op) { return (op >= 1400 && op < 1500) || (op >= 2400 && op < 2500); }

    static String describe(int[] c) { return Arrays.toString(c); }
}

package com.gadscape.cacheconv.convert;

import com.gadscape.cacheconv.core.Bytes;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Instruction-level clientscript model, ported from rev241/tools/CsRewrite: parse a 211 (or 241) script into
 * instructions, splice instructions in/out while keeping every jump and switch target on the same instruction, and
 * write it in the 241 layout.
 * <p>
 * Operand encoding: op 3 = string; ops below 100 except 21/38/39 = 4-byte int (211; in 241 op 61 is an 8-byte long and
 * 63 a 1-byte operand); everything else = 1 byte. Scripts read with {@link #parse211} are written exactly as the old
 * tool wrote them; scripts read with {@link #parse241} keep their long locals/args and 61/63 operands.
 */
public final class Cs2 {
    private Cs2() {}

    /**
     * Ops whose int operand is a relative jump (target = index + 1 + operand): jump (6), the int compares (7-10, 31, 32) and build 241's long compares
     * (68-73, which pop two longs; they never occur in 211 scripts).
     */
    public static final Set<Integer> BRANCH = Set.of(6, 7, 8, 9, 10, 31, 32, 68, 69, 70, 71, 72, 73);
    public static final int ICONST = 0, SCONST = 3, JUMP = 6, RETURN = 21, ILOAD = 33, POP_INT = 38, POP_OBJ = 39, GOSUB = 40, SWITCH = 60, LCONST = 61, PUSH_NULL = 63;
    public static final int CC_CREATE = 100;

    /** One instruction: opcode, int operand {@link #i} (or switch table index), string {@link #s} (op 3), long {@link #l} (op 61, 241 only). */
    public static final class Ins {
        public int op; public int i; public String s; public long l;
        public Ins(int op, int i, String s) { this.op = op; this.i = i; this.s = s; }
        @Override public String toString() { return op == 3 ? op + " \"" + s + "\"" : op == LCONST ? op + " " + l + "L" : op + " " + i; }
    }

    public static final class Script {
        /** Name bytes including the terminating 0. */
        public byte[] name;
        public List<Ins> code = new ArrayList<>();
        public int localInt, localObj, localLong, intArgs, objArgs, longArgs;
        /** Switch tables: case value -> relative jump (target = switch index + 1 + jump). */
        public List<LinkedHashMap<Integer, Integer>> switches = new ArrayList<>();
        /** True when parsed from the 241 layout (61/63 operands are 241's). */
        public boolean rev241;
    }

    private static int u16(byte[] b, int p) { return Bytes.u16(b, p); }
    private static int i32(byte[] b, int p) { return Bytes.i32(b, p); }

    public static Script parse211(byte[] b) {
        Script s = new Script();
        int ne = ScriptTrailer.nameEnd(b); s.name = Arrays.copyOf(b, ne);
        int sw = u16(b, b.length - 2), end = b.length - 2 - sw - 12;
        int ops = i32(b, end); s.localInt = u16(b, end + 4); s.localObj = u16(b, end + 6); s.intArgs = u16(b, end + 8); s.objArgs = u16(b, end + 10);
        int p = ne;
        while (p < end) { int op = u16(b, p); p += 2;
            if (op == 3) { int q = p; while (b[p] != 0) p++; s.code.add(new Ins(op, 0, new String(b, q, p - q, StandardCharsets.ISO_8859_1))); p++; }
            else if (op < 100 && op != 21 && op != 38 && op != 39) { s.code.add(new Ins(op, i32(b, p), null)); p += 4; }
            else { s.code.add(new Ins(op, b[p] & 255, null)); p++; } }
        if (s.code.size() != ops) throw new IllegalStateException("instruction count " + s.code.size() + " != " + ops);
        if (sw > 0) readSwitches(s, b, end + 12);
        return s;
    }

    public static Script parse241(byte[] b) {
        Script s = new Script();
        s.rev241 = true;
        int ne = ScriptTrailer.nameEnd(b); s.name = Arrays.copyOf(b, ne);
        int sw = u16(b, b.length - 2), end = b.length - 2 - sw - 16;
        int ops = i32(b, end); s.localInt = u16(b, end + 4); s.localObj = u16(b, end + 6); s.localLong = u16(b, end + 8);
        s.intArgs = u16(b, end + 10); s.objArgs = u16(b, end + 12); s.longArgs = u16(b, end + 14);
        int p = ne;
        while (p < end) { int op = u16(b, p); p += 2;
            if (op == 3) { int q = p; while (b[p] != 0) p++; s.code.add(new Ins(op, 0, new String(b, q, p - q, StandardCharsets.ISO_8859_1))); p++; }
            else if (op == LCONST) { Ins in = new Ins(op, 0, null); in.l = Bytes.i64(b, p); s.code.add(in); p += 8; }
            else if (op < 100 && op != 21 && op != 38 && op != 39 && op != 63) { s.code.add(new Ins(op, i32(b, p), null)); p += 4; }
            else { s.code.add(new Ins(op, b[p] & 255, null)); p++; } }
        if (s.code.size() != ops || p != end) throw new IllegalStateException("instruction count " + s.code.size() + " != " + ops + " or end " + p + " != " + end);
        if (sw > 0) readSwitches(s, b, end + 16);
        return s;
    }

    private static void readSwitches(Script s, byte[] b, int q) {
        int n = b[q++] & 255;
        for (int t = 0; t < n; t++) { int size = u16(b, q); q += 2; LinkedHashMap<Integer, Integer> m = new LinkedHashMap<>();
            for (int e = 0; e < size; e++) { m.put(i32(b, q), i32(b, q + 4)); q += 8; } s.switches.add(m); }
    }

    /** Replaces instruction at index i with the given instructions, keeping every jump/switch pointing at the same instruction. */
    public static void splice(Script s, int i, List<Ins> with) {
        int add = with.size() - 1, n = s.code.size();
        int[] map = new int[n + 1]; for (int k = 0; k <= n; k++) map[k] = k <= i ? k : k + add;
        // absolute targets first (target = index + 1 + offset)
        int[] target = new int[n]; for (int k = 0; k < n; k++) target[k] = BRANCH.contains(s.code.get(k).op) ? k + 1 + s.code.get(k).i : -1;
        List<int[]> swAbs = new ArrayList<>();
        for (int k = 0; k < n; k++) if (s.code.get(k).op == SWITCH) swAbs.add(new int[]{k, s.code.get(k).i});
        Map<Integer, Map<Integer, Integer>> swTargets = new HashMap<>();
        for (int[] e : swAbs) { Map<Integer, Integer> m = new LinkedHashMap<>(); for (var en : s.switches.get(e[1]).entrySet()) m.put(en.getKey(), e[0] + 1 + en.getValue()); swTargets.put(e[0], m); }
        List<Ins> code = new ArrayList<>(s.code.subList(0, i)); code.addAll(with); code.addAll(s.code.subList(i + 1, n));
        for (int k = 0; k < n; k++) { if (k == i || target[k] < 0) continue; Ins in = s.code.get(k); int nk = map[k], nt = target[k] <= i ? target[k] : target[k] + add; in.i = nt - nk - 1; }
        for (int[] e : swAbs) { if (e[0] == i) continue; int nk = map[e[0]]; LinkedHashMap<Integer, Integer> m = s.switches.get(e[1]); for (var en : swTargets.get(e[0]).entrySet()) { int t = en.getValue(); m.put(en.getKey(), (t <= i ? t : t + add) - nk - 1); } }
        s.code = code;
    }

    /** Writes the script in the 241 layout (16-byte trailer). */
    public static byte[] write241(Script s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); o.write(s.name, 0, s.name.length);
        for (Ins in : s.code) { o.write(in.op >> 8); o.write(in.op);
            if (in.op == 3) { byte[] t = in.s.getBytes(StandardCharsets.ISO_8859_1); o.write(t, 0, t.length); o.write(0); }
            else if (s.rev241 && in.op == LCONST) { Bytes.w32(o, (int) (in.l >>> 32)); Bytes.w32(o, (int) in.l); }
            else if (s.rev241 && in.op == PUSH_NULL) o.write(in.i);
            else if (in.op < 100 && in.op != 21 && in.op != 38 && in.op != 39) { Bytes.w32(o, in.i); }
            else o.write(in.i); }
        Bytes.w32(o, s.code.size()); Bytes.w16(o, s.localInt); Bytes.w16(o, s.localObj); Bytes.w16(o, s.localLong);
        Bytes.w16(o, s.intArgs); Bytes.w16(o, s.objArgs); Bytes.w16(o, s.longArgs);
        ByteArrayOutputStream sw = new ByteArrayOutputStream(); sw.write(s.switches.size());
        for (var m : s.switches) { Bytes.w16(sw, m.size()); for (var e : m.entrySet()) { Bytes.w32(sw, e.getKey()); Bytes.w32(sw, e.getValue()); } }
        byte[] t = sw.toByteArray(); o.write(t, 0, t.length); Bytes.w16(o, t.length);
        return o.toByteArray();
    }

    public static List<Ins> L(Ins... a) { return new ArrayList<>(List.of(a)); }
    public static int find(Script s, int op, int from) { for (int k = from; k < s.code.size(); k++) if (s.code.get(k).op == op) return k; return -1; }
}

package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.convert.Cs2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.gadscape.cacheconv.verify.CsTable.INT;
import static com.gadscape.cacheconv.verify.CsTable.LONG;
import static com.gadscape.cacheconv.verify.CsTable.OBJ;

/**
 * Static stack analysis of one build-241 clientscript: walks every path with the int, object and long stack depths
 * (and the constants pushed), as the client's interpreter would, and reports every place where the code cannot be
 * balanced. Scripts compiled by Jagex's (or RuneStar's) compiler always satisfy these rules, so a violation means a
 * broken script, a wrong callee signature, or a missing table entry:
 * <ul>
 *   <li>no stack underflow;</li>
 *   <li>the stacks are empty at every statement boundary: after a conditional branch, a switch or a jump has popped
 *       its operands, after a command that pushes nothing, after a hook setter, and after a gosub to a script that
 *       returns nothing;</li>
 *   <li>every path reaching an instruction arrives with the same depths; every {@code return} of a script returns the
 *       same counts (those are the script's return counts);</li>
 *   <li>local indexes are below the script's local counts; code never runs off its end.</li>
 * </ul>
 * Gosub (op 40) sites are recorded with the depths before the call, so a caller that pushes fewer values than the
 * callee takes ({@code gosub-underflow}) or more ({@code gosub-leftover}, for callees that return nothing) is flagged
 * at the call. Constants are tracked so the commands whose effect depends on them can be followed: hook setters (the
 * descriptor string), enum (the output type), the param commands (the param id) and db_find/db_getfield (the column).
 * <p>
 * Build 241 arrays are objects (held in object locals, passed as object args, returned, kept in varcs) with an element
 * type; the commands that read or write elements (44-46 by local, and the 8000-range array commands by stack) push or
 * pop values of that type. Element types are tracked from where arrays are made (define_array's operand,
 * array_create's type, string split) through locals, and across scripts and varcs through the {@link World}.
 */
public final class CsStack {
    private CsStack() {}

    /** What the analysis needs to know about the rest of the cache. */
    public interface World {
        /** {intArgs, objArgs, longArgs} of a script, or null if it does not exist. */
        int[] args(int script);
        /** {ints, objs, longs} a script returns, or null if not known (yet). */
        int[] returns(int script);
        /** Stack (INT/OBJ/LONG) a param's values live on, or -1 if the param does not exist. */
        int paramBase(int param);
        /** Stack per tuple element of a dbtable column, or null if the table/column does not exist. */
        int[] dbColumn(int table, int column);
        /** Stack of a script var type char (enum output types, hook descriptor chars, array element types). */
        int charBase(int ch);

        /** Element type of an array passed as object arg k of a script (-1 = unknown). */
        default int argArray(int script, int k) { return -1; }
        default void noteArgArray(int script, int k, int type) {}
        /** Element type of an array a script returns as object return k (-1 = unknown). */
        default int returnArray(int script, int k) { return -1; }
        default void noteReturnArray(int script, int k, int type) {}
        /** Element type of an array kept in a varc (string varc slot), -1 = unknown. */
        default int varcArray(int varc) { return -1; }
        default void noteVarcArray(int varc, int type) {}
    }

    /** Effect source: {pops, pushes} per stack for a regular opcode, or null if unknown. */
    @FunctionalInterface
    public interface Effects { int[][] counts(int op); }

    /**
     * One finding. {@code suspects} are the opcodes of the statement the problem was seen in; {@code size} is how many
     * stack values are off (left over, missing, or different between paths).
     */
    public record Problem(String kind, int index, int op, String detail, int[] suspects, int size) {
        @Override public String toString() { return kind + " @" + index + " (op " + op + "): " + detail; }
    }

    /** A gosub site: depths before the call, the callee's args and returns (null if unknown). */
    public record Call(int index, int callee, int[] before, int[] args, int[] returns) {}

    /** A hook setter site (cc_seton* / if_seton*): the script id (-1 if not a constant) and the argument counts. */
    public record HookSet(int index, int op, int script, String desc, int[] argCounts) {}

    /** A constant id a command consumes: kind "enum", "param", "dbtable", "script". */
    public record ConstRef(String kind, int id, int index, int op) {}

    /** An array value on the object stack, with its element type char (-1 = unknown). */
    public record Arr(int type) {}

    /** Problem kinds that mean "could not follow this path" rather than "this code is wrong". */
    public static boolean isIncomplete(String kind) { return kind.equals("unresolved") || kind.equals("unknown-op"); }

    public static final class Result {
        public final int id;
        /** {ints, objs, longs} at the first return reached, or null if no return was reached. */
        public int[] returns;
        public final List<Problem> problems = new ArrayList<>();
        public final List<Call> calls = new ArrayList<>();
        public final List<HookSet> hooks = new ArrayList<>();
        public final List<ConstRef> refs = new ArrayList<>();
        /** Opcodes with no known effect (the paths through them were not followed). */
        public final TreeSet<Integer> unknownOps = new TreeSet<>();
        /** Callees whose return counts were not known (paths through those calls were not followed). */
        public final TreeSet<Integer> blockedOn = new TreeSet<>();
        /** Stack depths on entry to each instruction with an unknown opcode (index -> int/obj/long depths). */
        public final TreeMap<Integer, int[]> unknownAt = new TreeMap<>();
        /** Instructions visited / total. */
        public int visited, size;
        /** Entry depths per instruction (null = not reached); kept only by {@link #analyseKeep}. */
        public int[][] depths;

        Result(int id) { this.id = id; }

        /** Every path was followed to its end. */
        public boolean complete() { return unknownOps.isEmpty() && blockedOn.isEmpty() && problems.stream().noneMatch(p -> isIncomplete(p.kind)); }

        public long count(String kind) { return problems.stream().filter(p -> p.kind.equals(kind)).count(); }

        /** Problems other than the "could not follow" ones. */
        public List<Problem> violations() { return problems.stream().filter(p -> !isIncomplete(p.kind)).toList(); }

        @Override public String toString() {
            return "script " + id + " returns " + Arrays.toString(returns) + " problems " + problems + (unknownOps.isEmpty() ? "" : " unknown ops " + unknownOps)
                    + (blockedOn.isEmpty() ? "" : " blocked on " + blockedOn);
        }
    }

    // ------------------------------------------------------------------ state

    private static final class St {
        final Object[][] val = {new Object[8], new Object[8], new Object[8]};
        final int[] n = new int[3];
        /** Element type of the array in each object local (-1 = unknown / not an array). */
        int[] objLocal;
        int stmt;

        St(int objLocals) { objLocal = new int[objLocals]; Arrays.fill(objLocal, -1); }

        St copy() {
            St c = new St(0);
            for (int t = 0; t < 3; t++) c.val[t] = val[t].clone();
            System.arraycopy(n, 0, c.n, 0, 3);
            c.objLocal = objLocal.clone();
            c.stmt = stmt;
            return c;
        }

        void push(int t, Object v) {
            if (n[t] == val[t].length) val[t] = Arrays.copyOf(val[t], n[t] * 2);
            val[t][n[t]] = v; n[t]++;
        }

        /** Pops k values; returns false on underflow (the stack is then emptied). */
        boolean pop(int t, int k) {
            if (n[t] < k) { n[t] = 0; return false; }
            n[t] -= k;
            return true;
        }

        /** Value (constant / array marker) at depth d below the top (0 = top), or null. */
        Object peek(int t, int d) { int i = n[t] - 1 - d; return i < 0 ? null : val[t][i]; }

        boolean empty() { return n[INT] == 0 && n[OBJ] == 0 && n[LONG] == 0; }
        void clear() { n[INT] = n[OBJ] = n[LONG] = 0; }
        int[] depths() { return n.clone(); }
    }

    private static final int[] INT_BRANCH = {7, 8, 9, 10, 31, 32};
    private static final int[] LONG_BRANCH = {68, 69, 70, 71, 72, 73};

    public static boolean isIntBranch(int op) { for (int o : INT_BRANCH) if (o == op) return true; return false; }
    public static boolean isLongBranch(int op) { for (int o : LONG_BRANCH) if (o == op) return true; return false; }
    /** Ops whose operand is a relative jump (target = index + 1 + operand). */
    public static boolean isBranch(int op) { return op == 6 || isIntBranch(op) || isLongBranch(op); }

    /**
     * Array commands that take an array and one element value from the stack: op -> {ints after the value, ints
     * pushed}. Stack: array, value (element type), ints...
     */
    static final Map<Integer, int[]> ARRAY_VALUE_OPS = Map.of(
            8005, new int[]{3, 1},  // array_indexof(array, value, start, end, ...) -> index
            8007, new int[]{3, 1},  // array_lastindexof
            8010, new int[]{3, 0},  // array_fill(array, value, start, end, ...)
            8011, new int[]{2, 0},
            8023, new int[]{0, 0},  // array_push(array, value)
            8024, new int[]{1, 0},  // array_insert(array, value, index)
            8025, new int[]{2, 0});
    /** Array commands that make an array whose element type is an int constant at this depth below the top: op -> {ints popped, depth}. */
    static final Map<Integer, int[]> ARRAY_MAKE_OPS = Map.of(
            8020, new int[]{2, 1},
            8021, new int[]{2, 1},
            8022, new int[]{3, 2});

    /** Component param getters: op -> {ints popped, depth of the param id}; push one value of the param's type. */
    static final Map<Integer, int[]> PARAM_GET_OPS = Map.of(
            1703, new int[]{1, 0},  // cc_param(param)
            2703, new int[]{3, 2}); // if_param(param, component, slot)
    /** Component param setters: op -> ints above the value. Stack: param, value (the param's type), ints... */
    static final Map<Integer, Integer> PARAM_SET_OPS = Map.of(
            1704, 1,  // cc_setparam(param, value, int)
            2704, 3); // if_setparam(param, value, component, int, int)
    /** Commands with a hook-style descriptor (besides the hook setters): op -> ints below the arguments. */
    static final Map<Integer, Integer> DESC_OPS = Map.of(2929, 3);

    /** Opcodes CsStack handles itself (besides the core instructions below 100). */
    public static boolean isSpecial(int op) {
        return op < 100 || CsTable.isHookSetter(op) || op == 3408 || (op >= 6513 && op <= 6516) || op == 7500 || op == 7502 || op == 7507
                || ARRAY_VALUE_OPS.containsKey(op) || ARRAY_MAKE_OPS.containsKey(op) || op == 8018 || op == 8026
                || PARAM_GET_OPS.containsKey(op) || PARAM_SET_OPS.containsKey(op) || DESC_OPS.containsKey(op) || op == FIND_BY_PARAMS;
    }

    /** cc_find by component params: comp, paramA, [valueA], paramB, [valueB], int, int -> component (a value is absent when its param is -1). */
    static final int FIND_BY_PARAMS = 210;

    /** Stack of a hook descriptor char: 's' strings, 'X' arrays (objects), long types, else int. */
    static int descBase(World w, char c) { return c == 'X' || c == 'W' || c == '*' ? OBJ : w.charBase(c); }

    /**
     * Tuple element a db column ref names: its low 4 bits are element + 1 (0 = the whole tuple, element 0 for db_find).
     * Checked on the stock cache: with "low bits = element" 3 more stock scripts fail.
     */
    static int tupleElement(int columnRef) { return Math.max(0, (columnRef & 15) - 1); }

    // ------------------------------------------------------------------ analysis

    public static Result analyse(int id, Cs2.Script s, World w, Effects fx) {
        return new Run(id, s, w, fx).run();
    }

    /** Same, keeping the entry depths of every instruction ({@link Result#depths}) for listings. */
    public static Result analyseKeep(int id, Cs2.Script s, World w, Effects fx) {
        Run run = new Run(id, s, w, fx);
        Result r = run.run();
        r.depths = run.seen;
        return r;
    }

    private static final class Run {
        final int id;
        final Cs2.Script s;
        final World w;
        final Effects fx;
        final Result r;
        final int n;
        final int[][] seen;
        final Deque<Object[]> work = new ArrayDeque<>();
        /** Statement start of the first return reached (its ops are suspects of a later return-mismatch). */
        int firstReturnStmt = -1, firstReturnAt = -1;

        Run(int id, Cs2.Script s, World w, Effects fx) {
            this.id = id; this.s = s; this.w = w; this.fx = fx;
            this.r = new Result(id);
            this.n = s.code.size();
            this.seen = new int[n][];
            r.size = n;
        }

        void problem(String kind, int pc, String detail, St st) { problem(kind, pc, detail, st, 1); }

        void problem(String kind, int pc, String detail, St st, int size) {
            int from = st == null ? pc : Math.max(0, Math.min(st.stmt, pc));
            int to = Math.min(pc, n - 1);
            int[] ops = new int[Math.max(0, to - from + 1)];
            for (int i = from; i <= to; i++) ops[i - from] = s.code.get(i).op;
            r.problems.add(new Problem(kind, pc, pc >= 0 && pc < n ? s.code.get(pc).op : -1, detail, ops, size));
        }

        static int sum(int[] d) { return d[0] + d[1] + d[2]; }
        static int diff(int[] a, int[] b) { return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]); }

        /** Statement boundary: the stacks must be empty. Resyncs (empties) them after a violation. */
        void boundary(St st, int pc, String kind, String what) {
            if (!st.empty()) {
                problem(kind, pc, "stack " + CsTable.sig(st.n) + " left after " + what, st, sum(st.n));
                st.clear();
            }
            st.stmt = pc + 1;
        }

        void enqueue(int target, St st, int from) {
            if (target < 0 || target >= n) { problem("bad-jump", from, "target " + target + " outside 0.." + (n - 1), st); return; }
            work.push(new Object[]{target, st});
        }

        Result run() {
            St st = new St(Math.max(s.localObj, s.objArgs));
            for (int k = 0; k < s.objArgs; k++) st.objLocal[k] = w.argArray(id, k);
            work.push(new Object[]{0, st});
            drain();
            // code no path reaches (the compiler's trailing default return, e.g. "0, return" in a proc returning an
            // int) is still compiled to the script's signature: walk it from an empty stack too. Only when every path
            // was followed (else "unreached" may just mean "not followed"), and only from instructions that follow a
            // return or a jump (statement starts).
            if (!r.complete()) return r;
            for (int pc = 1; pc < n; pc++) {
                if (seen[pc] != null) continue;
                int prev = s.code.get(pc - 1).op;
                if (prev != 21 && prev != 6) continue;
                St d = new St(Math.max(s.localObj, s.objArgs));
                d.stmt = pc;
                work.push(new Object[]{pc, d});
                drain();
            }
            return r;
        }

        void drain() {
            while (!work.isEmpty()) {
                Object[] it = work.pop();
                walk((Integer) it[0], (St) it[1]);
            }
        }

        void walk(int pc, St st) {
            while (true) {
                if (pc >= n) { problem("falls-off-end", n - 1, "code ends without return", st); return; }
                if (seen[pc] != null) {
                    if (!Arrays.equals(seen[pc], st.n)) problem("merge-mismatch", pc, "reached with " + CsTable.sig(seen[pc]) + " and " + CsTable.sig(st.n), st, diff(seen[pc], st.n));
                    return;
                }
                seen[pc] = st.depths();
                r.visited++;
                Cs2.Ins in = s.code.get(pc);
                int op = in.op;
                switch (op) {
                    case 0 -> st.push(INT, in.i);
                    case 3 -> st.push(OBJ, in.s);
                    case 61 -> st.push(LONG, in.l);
                    case 6 -> {
                        boundary(st, pc, "jump-dirty", "jump");
                        int t = pc + 1 + in.i;
                        if (t < 0 || t >= n) { problem("bad-jump", pc, "target " + t, st); return; }
                        st.stmt = t;
                        pc = t;
                        continue;
                    }
                    case 21 -> {
                        int[] d = st.depths();
                        for (int k = 0; k < d[OBJ]; k++) if (st.val[OBJ][k] instanceof Arr a && a.type >= 0) w.noteReturnArray(id, k, a.type);
                        if (r.returns == null) { r.returns = d; firstReturnStmt = st.stmt; firstReturnAt = pc; }
                        else if (!Arrays.equals(r.returns, d)) {
                            problem("return-mismatch", pc, "returns " + CsTable.sig(d) + ", the return at " + firstReturnAt + " returns " + CsTable.sig(r.returns), st, diff(d, r.returns));
                            // blame both statements
                            Problem p = r.problems.remove(r.problems.size() - 1);
                            int from = Math.max(0, Math.min(firstReturnStmt, firstReturnAt));
                            int[] more = new int[firstReturnAt - from + 1];
                            for (int i = from; i <= firstReturnAt; i++) more[i - from] = s.code.get(i).op;
                            int[] all = Arrays.copyOf(p.suspects(), p.suspects().length + more.length);
                            System.arraycopy(more, 0, all, p.suspects().length, more.length);
                            r.problems.add(new Problem(p.kind(), p.index(), p.op(), p.detail(), all, p.size()));
                        }
                        return;
                    }
                    case 33, 34 -> { local(pc, in.i, s.localInt, "int", st); if (op == 33) st.push(INT, null); else pop(st, pc, INT, 1, op); }
                    case 35 -> {
                        local(pc, in.i, s.localObj, "object", st);
                        int t = in.i >= 0 && in.i < st.objLocal.length ? st.objLocal[in.i] : -1;
                        st.push(OBJ, t >= 0 ? new Arr(t) : null);
                    }
                    case 36 -> {
                        local(pc, in.i, s.localObj, "object", st);
                        Object v = st.peek(OBJ, 0);
                        pop(st, pc, OBJ, 1, op);
                        if (in.i >= 0 && in.i < st.objLocal.length) st.objLocal[in.i] = v instanceof Arr a ? a.type : -1;
                    }
                    case 66, 67 -> { local(pc, in.i, s.localLong, "long", st); if (op == 66) st.push(LONG, null); else pop(st, pc, LONG, 1, op); }
                    case 37 -> { pop(st, pc, OBJ, in.i, op); st.push(OBJ, null); }
                    case 40 -> { if (!gosub(pc, in.i, st)) return; }
                    case 44 -> {
                        // define_array: operand = object local << 16 | element type; pops the size
                        int local = in.i >>> 16, type = in.i & 0xffff;
                        local(pc, local, s.localObj, "object", st);
                        pop(st, pc, INT, 1, op);
                        if (local < st.objLocal.length) st.objLocal[local] = type;
                    }
                    case 45, 46 -> {
                        // push_array_int / pop_array_int: operand = object local of the array; elements have its type
                        local(pc, in.i, s.localObj, "object", st);
                        int type = in.i >= 0 && in.i < st.objLocal.length ? st.objLocal[in.i] : -1;
                        if (type < 0) { problem("unresolved", pc, "element type of the array in object local " + in.i + " is not known", st); return; }
                        int base = w.charBase(type);
                        if (op == 45) { pop(st, pc, INT, 1, op); st.push(base, null); }
                        else { pop(st, pc, base, 1, op); pop(st, pc, INT, 1, op); }
                    }
                    case 49 -> { Object v = null; int t = w.varcArray(in.i); if (t >= 0) v = new Arr(t); st.push(OBJ, v); }
                    case 50 -> { Object v = st.peek(OBJ, 0); if (v instanceof Arr a && a.type >= 0) w.noteVarcArray(in.i, a.type); pop(st, pc, OBJ, 1, op); }
                    case 60 -> {
                        pop(st, pc, INT, 1, op);
                        boundary(st, pc, "branch-dirty", "switch");
                        if (in.i < 0 || in.i >= s.switches.size()) { problem("bad-jump", pc, "switch table " + in.i + " missing", st); return; }
                        for (Map.Entry<Integer, Integer> e : s.switches.get(in.i).entrySet()) enqueue(pc + 1 + e.getValue(), stmtCopy(st, pc + 1 + e.getValue()), pc);
                    }
                    case 3408 -> { if (!enumOp(pc, st)) return; }
                    case 6513, 6514, 6515, 6516 -> { if (!paramOp(pc, op, st)) return; }
                    case 7500, 7507 -> { if (!dbFind(pc, op, st)) return; }
                    case 7502 -> { if (!dbGetField(pc, st)) return; }
                    case 8018 -> { pop(st, pc, OBJ, 2, op); st.push(OBJ, new Arr('s')); }
                    case 8026 -> {
                        // array_remove(array, index) -> the removed element
                        Object a = st.peek(OBJ, 0);
                        if (!(a instanceof Arr arr) || arr.type < 0) { problem("unresolved", pc, "array element type not known", st); return; }
                        pop(st, pc, INT, 1, op); pop(st, pc, OBJ, 1, op);
                        st.push(w.charBase(arr.type), null);
                    }
                    default -> {
                        if (isIntBranch(op) || isLongBranch(op)) {
                            pop(st, pc, isIntBranch(op) ? INT : LONG, 2, op);
                            boundary(st, pc, "branch-dirty", "branch " + op);
                            int t = pc + 1 + in.i;
                            enqueue(t, stmtCopy(st, t), pc);
                        } else if (CsTable.isHookSetter(op)) {
                            if (!hookSetter(pc, op, st)) return;
                        } else if (ARRAY_MAKE_OPS.containsKey(op)) {
                            int[] spec = ARRAY_MAKE_OPS.get(op);
                            Object t = st.peek(INT, spec[1]);
                            pop(st, pc, INT, spec[0], op);
                            st.push(OBJ, new Arr(t instanceof Integer v ? v : -1));
                        } else if (ARRAY_VALUE_OPS.containsKey(op)) {
                            if (!arrayValueOp(pc, op, st)) return;
                        } else if (PARAM_GET_OPS.containsKey(op)) {
                            int[] spec = PARAM_GET_OPS.get(op);
                            Object p = st.peek(INT, spec[1]);
                            if (!(p instanceof Integer param)) { problem("unresolved", pc, "param id is not a constant", st); return; }
                            r.refs.add(new ConstRef("param", param, pc, op));
                            int base = w.paramBase(param);
                            if (base < 0) { problem("param-missing", pc, "param " + param + " does not exist", st); return; }
                            pop(st, pc, INT, spec[0], op);
                            st.push(base, null);
                        } else if (PARAM_SET_OPS.containsKey(op)) {
                            int above = PARAM_SET_OPS.get(op);
                            // the param is under the value when the value is an int, else right under the ints above it
                            Object asInt = st.peek(INT, above + 1), other = st.peek(INT, above);
                            int param = -1, base = -1;
                            if (asInt instanceof Integer p && w.paramBase(p) == INT) { param = p; base = INT; }
                            else if (other instanceof Integer p && w.paramBase(p) > INT) { param = p; base = w.paramBase(p); }
                            if (param < 0) { problem("unresolved", pc, "param id is not a constant of an existing param", st); return; }
                            r.refs.add(new ConstRef("param", param, pc, op));
                            pop(st, pc, INT, above, op);
                            pop(st, pc, base, 1, op);
                            pop(st, pc, INT, 1, op);
                            boundary(st, pc, "statement-dirty", "command " + op);
                        } else if (DESC_OPS.containsKey(op)) {
                            if (!descOp(pc, op, st)) return;
                        } else if (op == FIND_BY_PARAMS) {
                            if (!findByParams(pc, op, st)) return;
                        } else {
                            int[][] c = fx.counts(op);
                            if (c == null) { r.unknownOps.add(op); r.unknownAt.put(pc, st.depths()); problem("unknown-op", pc, "no stack effect known for op " + op, st); return; }
                            for (int t = 0; t < 3; t++) if (c[0][t] > 0) pop(st, pc, t, c[0][t], op);
                            boolean pushes = false;
                            for (int t = 0; t < 3; t++) for (int k = 0; k < c[1][t]; k++) { st.push(t, null); pushes = true; }
                            if (!pushes && op >= 100) boundary(st, pc, "statement-dirty", "command " + op + " (pushes nothing)");
                        }
                    }
                }
                pc++;
            }
        }

        St stmtCopy(St st, int target) { St c = st.copy(); c.stmt = target; return c; }

        void pop(St st, int pc, int t, int k, int op) {
            int have = st.n[t];
            if (!st.pop(t, k)) problem("underflow", pc, "op " + op + " pops " + k + " " + stackName(t) + (k == 1 ? "" : "s") + ", stack has " + have, st, k - have);
        }

        void local(int pc, int idx, int count, String what, St st) {
            if (idx < 0 || idx >= count) problem("local-range", pc, what + " local " + idx + " but the script has " + count, st);
        }

        boolean gosub(int pc, int callee, St st) {
            int[] a = w.args(callee);
            r.refs.add(new ConstRef("script", callee, pc, 40));
            if (a == null) { problem("gosub-missing", pc, "script " + callee + " does not exist", st); return false; }
            int[] before = st.depths();
            int[] ret = w.returns(callee);
            r.calls.add(new Call(pc, callee, before, a, ret));
            boolean under = false;
            for (int t = 0; t < 3; t++) if (before[t] < a[t]) under = true;
            int missing = 0;
            for (int t = 0; t < 3; t++) missing += Math.max(0, a[t] - before[t]);
            if (under) problem("gosub-underflow", pc, "script " + callee + " takes " + CsTable.sig(a) + " but the stack has only " + CsTable.sig(before), st, missing);
            else for (int k = 0; k < a[OBJ]; k++) if (st.peek(OBJ, a[OBJ] - 1 - k) instanceof Arr arr && arr.type >= 0) w.noteArgArray(callee, k, arr.type);
            for (int t = 0; t < 3; t++) st.pop(t, a[t]);
            if (ret == null) { r.blockedOn.add(callee); return false; }
            for (int t = 0; t < 3; t++) for (int k = 0; k < ret[t]; k++) {
                int at = t == OBJ ? w.returnArray(callee, k) : -1;
                st.push(t, at >= 0 ? new Arr(at) : null);
            }
            if (ret[0] == 0 && ret[1] == 0 && ret[2] == 0) {
                if (!st.empty() && !under) {
                    int[] left = st.depths();
                    problem("gosub-leftover", pc, "script " + callee + " takes " + CsTable.sig(a) + " and returns nothing, but " + CsTable.sig(before)
                            + " were pushed (" + CsTable.sig(left) + " left)", st, sum(left));
                }
                st.clear();
                st.stmt = pc + 1;
            }
            return true;
        }

        boolean hookSetter(int pc, int op, St st) {
            if (op >= 2000) pop(st, pc, INT, 1, op); // the component (on top)
            Object d = st.peek(OBJ, 0);
            if (!(d instanceof String desc)) { problem("unresolved", pc, "hook descriptor is not a constant string", st); return false; }
            pop(st, pc, OBJ, 1, op);
            int[] cnt = new int[3];
            String body = desc;
            if (!body.isEmpty() && body.charAt(body.length() - 1) == 'Y') {
                Object c = st.peek(INT, 0);
                if (!(c instanceof Integer k)) { problem("unresolved", pc, "hook trigger count is not a constant", st); return false; }
                pop(st, pc, INT, 1 + Math.max(0, k), op);
                body = body.substring(0, body.length() - 1);
            }
            for (int i = body.length() - 1; i >= 0; i--) { int t = descBase(w, body.charAt(i)); cnt[t]++; pop(st, pc, t, 1, op); }
            Object sc = st.peek(INT, 0);
            int script = sc instanceof Integer v ? v : -1;
            pop(st, pc, INT, 1, op);
            r.hooks.add(new HookSet(pc, op, script, desc, cnt));
            if (script >= 0) r.refs.add(new ConstRef("script", script, pc, op));
            boundary(st, pc, "statement-dirty", "hook setter " + op);
            return true;
        }

        boolean findByParams(int pc, int op, St st) {
            int d = 2, other = 0, otherBase = -1;
            int[] extra = new int[3];
            for (int pair = 0; pair < 2; pair++) {
                Object deeper = st.peek(INT, d + 1), here = st.peek(INT, d);
                if (deeper instanceof Integer p && w.paramBase(p) == INT) { r.refs.add(new ConstRef("param", p, pc, op)); d += 2; }
                else if (here instanceof Integer p && w.paramBase(p) > INT) { r.refs.add(new ConstRef("param", p, pc, op)); extra[w.paramBase(p)]++; d += 1; }
                else if (here instanceof Integer p && p == -1) d += 1;
                else { problem("unresolved", pc, "param ids of " + op + " are not constants of existing params", st); return false; }
            }
            pop(st, pc, INT, d + 1, op);
            for (int t = 1; t < 3; t++) if (extra[t] > 0) pop(st, pc, t, extra[t], op);
            st.push(INT, null);
            return true;
        }

        /** A command with a descriptor string on top: pops it, one value per descriptor char, then fixed ints. */
        boolean descOp(int pc, int op, St st) {
            Object d = st.peek(OBJ, 0);
            if (!(d instanceof String desc)) { problem("unresolved", pc, "descriptor is not a constant string", st); return false; }
            pop(st, pc, OBJ, 1, op);
            for (int i = desc.length() - 1; i >= 0; i--) pop(st, pc, descBase(w, desc.charAt(i)), 1, op);
            pop(st, pc, INT, DESC_OPS.get(op), op);
            boundary(st, pc, "statement-dirty", "command " + op);
            return true;
        }

        boolean enumOp(int pc, St st) {
            Object vt = st.peek(INT, 2), en = st.peek(INT, 1);
            if (!(vt instanceof Integer t)) { problem("unresolved", pc, "enum output type is not a constant", st); return false; }
            if (en instanceof Integer e) r.refs.add(new ConstRef("enum", e, pc, 3408));
            pop(st, pc, INT, 4, 3408);
            st.push(w.charBase(t), null);
            return true;
        }

        boolean paramOp(int pc, int op, St st) {
            Object p = st.peek(INT, 0);
            if (!(p instanceof Integer param)) { problem("unresolved", pc, "param id is not a constant", st); return false; }
            r.refs.add(new ConstRef("param", param, pc, op));
            int base = w.paramBase(param);
            if (base < 0) { problem("param-missing", pc, "param " + param + " does not exist", st); return false; }
            pop(st, pc, INT, 2, op);
            st.push(base, null);
            return true;
        }

        boolean arrayValueOp(int pc, int op, St st) {
            int[] spec = ARRAY_VALUE_OPS.get(op);
            // the array is the bottom object: right under an object value, or on top when the value is an int/long
            Object top = st.peek(OBJ, 0), below = st.peek(OBJ, 1);
            int type = -1;
            boolean valueIsObj = false;
            if (top instanceof Arr a && a.type >= 0 && w.charBase(a.type) != OBJ) type = a.type;
            else if (below instanceof Arr b && b.type >= 0 && w.charBase(b.type) == OBJ) { type = b.type; valueIsObj = true; }
            if (type < 0) { problem("unresolved", pc, "array element type not known", st); return false; }
            pop(st, pc, INT, spec[0], op);
            pop(st, pc, w.charBase(type), 1, op);
            pop(st, pc, OBJ, 1, op);
            for (int k = 0; k < spec[1]; k++) st.push(INT, null);
            if (spec[1] == 0) boundary(st, pc, "statement-dirty", "array command " + op);
            return true;
        }

        /** db column ref: table << 12 | column << 4 | tuple index. */
        int[] column(Object c) {
            if (!(c instanceof Integer v)) return null;
            return w.dbColumn(v >>> 12, (v >>> 4) & 255);
        }

        boolean dbFind(int pc, int op, St st) {
            // stack: column, value (typed by the column's tuple element), int (top); pushes the row count
            Object cAsInt = st.peek(INT, 2);
            int[] asInt = column(cAsInt);
            if (asInt != null && tupleElement((Integer) cAsInt) < asInt.length && asInt[tupleElement((Integer) cAsInt)] == INT) {
                r.refs.add(new ConstRef("dbtable", (Integer) cAsInt >>> 12, pc, op));
                pop(st, pc, INT, 3, op);
            } else {
                Object c = st.peek(INT, 1);
                int[] types = column(c);
                if (types == null || tupleElement((Integer) c) >= types.length || types[tupleElement((Integer) c)] == INT) {
                    problem("unresolved", pc, "db column is not a constant column of an existing dbtable", st);
                    return false;
                }
                r.refs.add(new ConstRef("dbtable", (Integer) c >>> 12, pc, op));
                pop(st, pc, INT, 2, op);
                pop(st, pc, types[tupleElement((Integer) c)], 1, op);
            }
            st.push(INT, null);
            return true;
        }

        boolean dbGetField(int pc, St st) {
            // stack: row, column, field index (top); pushes every element of the column's tuple
            Object c = st.peek(INT, 1);
            int[] types = column(c);
            if (types == null) { problem("unresolved", pc, "db column is not a constant column of an existing dbtable", st); return false; }
            r.refs.add(new ConstRef("dbtable", (Integer) c >>> 12, pc, 7502));
            pop(st, pc, INT, 3, 7502);
            // low 4 bits: 0 = the whole tuple, n = tuple element n - 1
            int low = (Integer) c & 15;
            if (low == 0) for (int t : types) st.push(t, null);
            else if (low <= types.length) st.push(types[low - 1], null);
            else { problem("db-column", pc, "column ref " + c + " names tuple element " + (low - 1) + " of a " + types.length + "-tuple", st); return false; }
            return true;
        }
    }

    public static String stackName(int t) { return t == INT ? "int" : t == OBJ ? "object" : "long"; }
}

package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.convert.InterfaceConv;
import com.gadscape.cacheconv.convert.ScriptConv;
import com.gadscape.cacheconv.convert.ScriptTrailer;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Origin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Self-checks of the UI area (also run by the unit tests):
 * <ul>
 *   <li>synthetic: the simulator types constants (direct, through locals, through proc arguments, hook setters, enum),
 *       skips dead code, operand patching keeps the script readable, the component walker finds and patches every
 *       field and drops hooks cleanly, the data files load;</li>
 *   <li>golden (with the real caches): every USER script analysed (custom ones completely), and the custom scripts and
 *       interfaces converted without any rewrite are byte-identical to the hand-built cacheU (what the old
 *       CsAll/UiOverlay wrote, after TrimPad).</li>
 * </ul>
 * <pre>  dev/run.sh ui.UiSelfCheck [--golden]</pre>
 */
public final class UiSelfCheck {
    private UiSelfCheck() {}

    // ------------------------------------------------------------------ tiny 211 assembler

    /** A 211 script: {op, operand} pairs (operand ignored for 1-byte ops), strings for op 3 given as String. */
    public static byte[] script211(int intArgs, int objArgs, int localInt, int localObj, Object... ins) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0);
        int n = 0;
        for (int i = 0; i < ins.length; i += 2) {
            int op = (Integer) ins[i];
            o.write(op >> 8); o.write(op);
            if (op == 3) { byte[] t = ((String) ins[i + 1]).getBytes(StandardCharsets.ISO_8859_1); o.write(t, 0, t.length); o.write(0); }
            else if (op < 100 && op != 21 && op != 38 && op != 39) Bytes.w32(o, (Integer) ins[i + 1]);
            else o.write((Integer) ins[i + 1]);
            n++;
        }
        Bytes.w32(o, n); Bytes.w16(o, localInt); Bytes.w16(o, localObj); Bytes.w16(o, intArgs); Bytes.w16(o, objArgs);
        o.write(0); Bytes.w16(o, 1);
        return o.toByteArray();
    }

    /** A world over a few in-memory 211 scripts. */
    public static final class MemWorld implements CsSim.World {
        final Map<Integer, byte[]> scripts = new HashMap<>();
        final Map<Integer, String[]> argTypes = new HashMap<>();
        final Map<Integer, int[]> returns = new HashMap<>();
        public MemWorld put(int id, byte[] b) { scripts.put(id, b); return this; }
        public CsSim.Result run(int id) {
            CsSim.Result r = CsSim.run(CsCommands.get(), this, id, Cs2.parse211(scripts.get(id)));
            argTypes.put(id, r.argTypes);
            returns.put(id, r.returns == null ? new int[]{0, 0} : r.returns);
            return r;
        }
        @Override public int[] args(int s) { byte[] b = scripts.get(s); return b == null ? null : ScriptTrailer.args211(b); }
        @Override public int[] returns(int s) { if (!returns.containsKey(s) && scripts.containsKey(s)) run(s); return returns.get(s); }
        @Override public String[] argTypes(int s) { return argTypes.get(s); }
        @Override public Boolean paramIsString(int p) { return p == 7 ? Boolean.TRUE : Boolean.FALSE; }
        @Override public boolean[] dbColumn(int t, int c) { return null; }
    }

    /** Synthetic checks; returns the failures (empty = pass). */
    public static List<String> synthetic() {
        List<String> f = new ArrayList<>();
        // 1. direct, through a local, dead code after return
        byte[] a = script211(0, 0, 1, 0,
                0, 1234, 1105, 0,                 // iconst 1234; cc_setgraphic
                0, 777, 34, 0, 33, 0, 1108, 0,    // iconst 777; istore 0; iload 0; cc_setmodel
                21, 0, 3, "", 21, 0);             // return; (dead) sconst ""; return
        MemWorld w = new MemWorld().put(1, a);
        CsSim.Result r = w.run(1);
        expect(f, "direct graphic", "graphic", r.constType.get(0));
        expect(f, "model through local", "model", r.constType.get(2));
        expect(f, "complete", true, r.complete);
        expect(f, "returns", "[0, 0]", Arrays.toString(r.returns));
        // 2. proc argument types flow to the caller's constants
        byte[] proc = script211(2, 0, 2, 0, 33, 0, 33, 1, 2105, 0, 21, 0);   // if_setgraphic(arg0 graphic, arg1 component)
        byte[] caller = script211(0, 0, 0, 0, 0, 5555, 0, (1600 << 16) | 3, 40, 10, 21, 0);
        w.put(10, proc).put(11, caller);
        w.run(10);
        r = w.run(11);
        expect(f, "proc arg graphic", "graphic", r.constType.get(0));
        expect(f, "proc arg component", "component", r.constType.get(1));
        // 3. hook setter: script id, descriptor-typed arg, target component; enum id
        byte[] hook = script211(0, 0, 0, 0,
                0, 10500, 0, (1601 << 16) | 2, 3, "I", 0, (1601 << 16) | 9, 2400, 0,
                0, (int) 'i', 0, (int) 'o', 0, 3000, 0, 5, 3408, 0, 38, 0, 21, 0);
        w.put(12, hook);
        r = w.run(12);
        expect(f, "hook script id", "clientscript", r.constType.get(0));
        expect(f, "hook arg component", "component", r.constType.get(1));
        expect(f, "hook target component", "component", r.constType.get(3));
        expect(f, "enum id", "enum", r.constType.get(7));
        expect(f, "enum key typed by key type", "int", r.constType.get(8));
        expect(f, "hooks recorded", 1, r.hooks.size());
        // 4. operand patching keeps the script readable and length
        byte[] p = caller.clone();
        int[] off = ScriptPatch.operandOffsets211(p);
        ScriptPatch.set(p, off, 0, 20005);
        ScriptPatch.set(p, off, 2, 47000);
        Cs2.Script ps = Cs2.parse211(p);
        expect(f, "patched graphic", 20005, ps.code.get(0).i);
        expect(f, "patched gosub", 47000, ps.code.get(2).i);
        expect(f, "patched length", caller.length, p.length);
        ScriptTrailer.verify241(11, ScriptConv.convertAll(Map.of(11, p)).scripts.get(11));
        // 5. component walker: IF3 graphic + hooks + var trigger
        byte[] comp = if3Graphic(4321, new Object[]{10500, (1601 << 16) | 2, "txt"}, new Object[]{38, -2147483645}, 261);
        IfRefs.Comp c = IfRefs.parse(comp);
        expect(f, "component end", comp.length, c.end());
        expect(f, "refs", "[SPRITE=4321, HOOK_SCRIPT=10500, HOOK_INT=104923138, HOOK_SCRIPT=38, HOOK_INT=-2147483645, VAR_TRIGGER=261]",
                c.refs().stream().map(x -> x.field() + "=" + x.value()).toList().toString());
        byte[] cp = comp.clone();
        IfRefs.patch(cp, c.refs().get(0), 20001);
        IfRefs.patch(cp, c.refs().get(1), 47001);
        IfRefs.Comp c2 = IfRefs.parse(cp);
        expect(f, "patched sprite", 20001, c2.refs().get(0).value());
        expect(f, "patched hook", 47001, c2.refs().get(1).value());
        byte[] dropped = IfRefs.dropHooks(cp, List.of(c2.hooks().get(0)));
        IfRefs.Comp c3 = IfRefs.parse(dropped);
        expect(f, "dropped end", dropped.length, c3.end());
        expect(f, "dropped hooks", 1, c3.hooks().size());
        expect(f, "remaining hook script", 38, c3.refs().get(1).value());
        // 6. data files
        expect(f, "rules", "[98, 2064, 4218, 6156]", UiData.signatures().keySet().toString());
        UiData.SigRule r2064 = UiData.signatures().get(2064);
        Cs2.Script ad = Cs2.parse241(UiAdapters.build(r2064));
        expect(f, "mapped adapter code", "[33 0, 0 9, 33 1, 35 0, 40 2064, 21 0]", ad.code.toString());
        expect(f, "mapped adapter args", "2/1/0", ad.intArgs + "/" + ad.objArgs + "/" + ad.longArgs);
        expect(f, "plain adapter = ScriptTrailer.adapter (cacheU bytes)", true,
                Arrays.equals(UiAdapters.build(UiData.signatures().get(98)), ScriptTrailer.adapter(98, 11, new int[]{1}, 1)));
        expect(f, "remap row to another interface", 5201 << 16 | 7,
                new UiData.ComponentRemap("t", List.<String[]>of(new String[]{"215", "x", "30", "5201:7", "high"})).get(215 << 16 | 30).packed());
        expect(f, "unchanged component shape", true, Arrays.equals(IfRefs.shape(comp), IfRefs.shape(cp)));
        expect(f, "fixup range", 1710, UiData.fixups().get("script:10036").get(0).to());
        UiData.ComponentRemap rm = UiData.componentRemap(null);
        expect(f, "remap rows", true, rm.size() > 7000);
        expect(f, "orbs +1 (160:8 -> 160:9)", 9, rm.get((160 << 16) | 8) == null ? -1 : rm.get((160 << 16) | 8).to());
        // 7. adapters read back
        for (UiData.SigRule rule : UiData.signatures().values()) ScriptTrailer.verify241(rule.adapter(), UiAdapters.build(rule));
        return f;
    }

    /** An IF3 graphic component with an onLoad hook, an onMouseOver hook and one var trigger. */
    public static byte[] if3Graphic(int sprite, Object[] onLoad, Object[] onMouseOver, int varp) {
        BufOut o = new BufOut(64);
        o.u8(255).u8(5).u16(0).u16(0).u16(0).u16(32).u16(32).u8(0).u8(0).u8(0).u8(0).u16(65535).u8(0);
        o.i32(sprite).u16(0).u8(0).u8(0).u8(0).i32(0).u8(0).u8(0);
        o.u24(0).str("").u8(0).u8(0).u8(0).u8(0).str("");
        for (int h = 0; h < IfRefs.HOOKS.length; h++) {
            Object[] args = h == 0 ? onLoad : h == 1 ? onMouseOver : null;
            if (args == null) { o.u8(0); continue; }
            o.u8(args.length);
            for (Object x : args) { if (x instanceof String s) o.u8(1).str(s); else o.u8(0).i32((Integer) x); }
        }
        o.u8(1).i32(varp).u8(0).u8(0);
        return o.done();
    }

    private static void expect(List<String> f, String what, Object want, Object got) {
        if (!java.util.Objects.equals(want, got)) f.add(what + ": expected " + want + ", got " + got);
    }

    // ------------------------------------------------------------------ golden (real caches)

    /** Golden checks against USER/VAN211/cacheU; returns the failures. */
    public static List<String> golden(CacheView user, CacheView van, CacheView ref, StringBuilder log) {
        List<String> f = new ArrayList<>();
        ScriptSet set = new ScriptSet(user);
        TreeMap<Integer, CsSim.Result> res = set.analyseAll();
        TreeMap<Integer, byte[]> custom = new TreeMap<>();
        int incomplete = 0, customIncomplete = 0;
        for (var r : res.values()) {
            boolean cu = Origin.ofGroup(Arch.SCRIPTS, r.id, user, van) == Origin.USER_ADDED;
            if (cu) custom.put(r.id, user.single(Arch.SCRIPTS, r.id));
            if (!r.complete) { incomplete++; if (cu && !r.problem.startsWith("gosub to missing script")) { customIncomplete++; f.add("custom script " + r.id + " not fully typed: " + r.problem); } }
        }
        log.append("analysed ").append(res.size()).append(" USER scripts, ").append(incomplete).append(" stop early (vanilla commands without a signature), custom scripts not fully typed: ").append(customIncomplete).append('\n');
        ScriptConv.Result conv = ScriptConv.convertAll(custom);
        int eq = 0;
        for (var e : custom.entrySet()) {
            if (Arrays.equals(conv.scripts.get(e.getKey()), ref.single(Arch.SCRIPTS, e.getKey()))) eq++;
            else f.add("custom script " + e.getKey() + " differs from cacheU");
        }
        log.append("custom scripts converted without rewrites == cacheU: ").append(eq).append('/').append(custom.size()).append('\n');
        int ceq = 0, cn = 0;
        for (int id : user.groupIds(Arch.INTERFACES)) {
            if (Origin.ofGroup(Arch.INTERFACES, id, user, van) != Origin.USER_ADDED) continue;
            GroupData g = InterfaceConv.group(user.group(Arch.INTERFACES, id)), r = ref.group(Arch.INTERFACES, id);
            for (int i = 0; i < g.fileCount(); i++) {
                byte[] b = g.dataAt(i);
                IfRefs.Comp c = IfRefs.parse(b);
                byte[] t = c.end() < b.length && Bytes.isZero(b, c.end(), b.length) ? Arrays.copyOf(b, c.end()) : b;
                cn++;
                if (r != null && Arrays.equals(t, r.dataRef(g.fileIdAt(i)))) ceq++;
                else f.add("component " + id + ":" + g.fileIdAt(i) + " differs from cacheU");
            }
        }
        log.append("custom interface components converted+trimmed without rewrites == cacheU: ").append(ceq).append('/').append(cn).append('\n');
        return f;
    }

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args, 0);
        List<String> f = synthetic();
        System.out.println("synthetic: " + (f.isEmpty() ? "PASS" : "FAIL " + f));
        boolean ok = f.isEmpty();
        if (o.flag("golden") || args.length > 0 && Arrays.asList(args).contains("--golden")) {
            try (JagCache user = JagCache.open("USER", o.user); JagCache van = JagCache.open("VAN211", o.van211); JagCache ref = JagCache.open("REF", o.ref)) {
                StringBuilder log = new StringBuilder();
                List<String> g = golden(user, van, ref, log);
                System.out.print(log);
                System.out.println("golden: " + (g.isEmpty() ? "PASS" : "FAIL (" + g.size() + ") " + g.subList(0, Math.min(10, g.size()))));
                ok &= g.isEmpty();
            }
        }
        System.exit(ok ? 0 : 1);
    }
}

package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Self-checks of the verify area (unit-test style, no caches needed): the stack analysis on hand-made scripts (clean
 * code, the gosub mismatches it must flag, hook setters, enum, arrays, the compiler's default return), the config and
 * interface walkers, the branding patterns, and a whole {@link Verifier} run on a tiny in-memory cache. With
 * {@code --full} (and the standard layout root) also: every stock BASE script analyses without a violation with the
 * shipped table, and every BASE config entry and interface component walks exactly.
 * <p>
 * {@code dev/run.sh verify.VerifySelfCheck [--full]}; VerifyTest runs the same checks under JUnit.
 */
public final class VerifySelfCheck {
    private VerifySelfCheck() {}

    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        List<String> fails = run();
        if (o.flag("full")) fails.addAll(full(o.base));
        if (o.flag("step")) fails.addAll(step(o));
        fails.forEach(f -> System.out.println("FAIL " + f));
        System.out.println(fails.isEmpty() ? "verify self-check OK" : fails.size() + " self-check failures");
        System.exit(fails.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------ builders

    /** A 241 script: args/locals, then code as (op, operand) pairs; a String operand is an sconst. */
    public static Cs2.Script script(int intArgs, int objArgs, int localInt, int localObj, Object... code) {
        Cs2.Script s = new Cs2.Script();
        s.rev241 = true;
        s.name = new byte[]{0};
        s.intArgs = intArgs; s.objArgs = objArgs; s.localInt = localInt; s.localObj = localObj;
        for (int i = 0; i < code.length; i += 2) {
            int op = (Integer) code[i];
            Object v = code[i + 1];
            s.code.add(v instanceof String str ? new Cs2.Ins(op, 0, str) : new Cs2.Ins(op, (Integer) v, null));
        }
        return s;
    }

    static CsTable table() {
        CsTable t = new CsTable(CsTable.core());
        t.put(new CsTable.Effect(4000, "ii", "i", "test", "add"));
        t.put(new CsTable.Effect(3100, "s", "-", "test", "mes"));
        t.put(new CsTable.Effect(1100, "ii", "-", "test", "cc_setscrollpos"));
        return t;
    }

    static CsStack.Result analyse(Map<Integer, Cs2.Script> scripts, int id, com.gadscape.cacheconv.core.CacheView c) {
        CsWorld w = new CsWorld(c, scripts);
        return w.analyseAll(table()::counts).get(id);
    }

    static void expect(List<String> fails, String what, boolean ok) { if (!ok) fails.add(what); }

    // ------------------------------------------------------------------ checks

    public static List<String> run() {
        List<String> f = new ArrayList<>();
        stack(f);
        walkers(f);
        brand(f);
        verifier(f);
        return f;
    }

    static void stack(List<String> f) {
        MemView c = new MemView("T");
        // a void proc taking one int, and one returning an int
        Map<Integer, Cs2.Script> s = new TreeMap<>();
        s.put(10, script(1, 0, 1, 0, 21, 0));
        s.put(11, script(0, 0, 0, 0, 0, 7, 21, 0));
        // clean: 1 + ~p11 -> local; ~p10(local)
        s.put(1, script(0, 0, 1, 0, 0, 1, 40, 11, 4000, 0, 34, 0, 33, 0, 40, 10, 21, 0));
        // caller pushes one int too many for void 10 (the cacheU create_graphic case)
        s.put(2, script(0, 0, 0, 0, 0, 1, 0, 2, 40, 10, 21, 0));
        // caller pushes nothing for 10 (the cacheU error/608 case)
        s.put(3, script(0, 0, 0, 0, 40, 10, 21, 0));
        // values left at a branch
        s.put(4, script(0, 0, 0, 0, 0, 1, 0, 2, 0, 3, 8, 0, 21, 0));
        // hook setter: script 10 with one int arg, then a void command; enum pushing a string into mes
        s.put(5, script(0, 0, 0, 0, 0, 10, 0, 5, 3, "i", 1400, 0, 0, 105, 0, 115, 0, 77, 0, 0, 3408, 0, 3100, 0, 21, 0));
        // array in object local 0 of ints: define, set [0] = 7, read [0] into an int local
        s.put(6, script(0, 0, 1, 1, 0, 5, 44, 105, 0, 0, 0, 7, 46, 0, 0, 0, 45, 0, 34, 0, 21, 0));
        // returns one int, but the compiler's default return after it (dead code) returns two: signature mismatch
        s.put(7, script(0, 0, 0, 0, 0, 1, 21, 0, 0, 0, 0, 0, 21, 0));
        // gosub to a missing script
        s.put(8, script(0, 0, 0, 0, 40, 999, 21, 0));
        for (int id : new int[]{1, 5, 6}) {
            CsStack.Result r = analyse(s, id, c);
            expect(f, "script " + id + " should be clean: " + r, r.violations().isEmpty() && r.complete());
        }
        CsStack.Result r2 = analyse(s, 2, c);
        expect(f, "script 2 should have a gosub-leftover: " + r2, r2.count("gosub-leftover") == 1);
        CsStack.Result r3 = analyse(s, 3, c);
        expect(f, "script 3 should have a gosub-underflow: " + r3, r3.count("gosub-underflow") == 1);
        CsStack.Result r4 = analyse(s, 4, c);
        expect(f, "script 4 should have a branch-dirty: " + r4, r4.count("branch-dirty") == 1);
        CsStack.Result r5 = analyse(s, 5, c);
        expect(f, "script 5 should record the hook setter of script 10 with one int: " + r5.hooks,
                r5.hooks.size() == 1 && r5.hooks.get(0).script() == 10 && r5.hooks.get(0).argCounts()[0] == 1);
        expect(f, "script 5 should record enum 77", r5.refs.stream().anyMatch(x -> x.kind().equals("enum") && x.id() == 77));
        CsStack.Result r7 = analyse(s, 7, c);
        expect(f, "script 7 should have a return-mismatch (dead default return): " + r7, r7.count("return-mismatch") == 1);
        CsStack.Result r8 = analyse(s, 8, c);
        expect(f, "script 8 should have a gosub-missing: " + r8, r8.count("gosub-missing") == 1);
        CsWorld w = new CsWorld(c, s);
        w.analyseAll(table()::counts);
        expect(f, "script 11 returns one int", java.util.Arrays.equals(w.returns(11), new int[]{1, 0, 0}));
        // the shipped table loads and knows the commands the checks above rely on
        CsTable t = CsTable.load();
        expect(f, "shipped table has cc_create with 4 ints", t.get(100) != null && t.get(100).pops().equals("iiii"));
        expect(f, "shipped table lists gosub as special", t.get(40) != null && t.get(40).special());
    }

    static void walkers(List<String> f) {
        MemView c = new MemView("T");
        CacheTypes types = new CacheTypes(c);
        // obj: model op 44 (int), cert link 97, end
        byte[] obj = new BufOut().u8(44).i32(1234).u8(97).u16(4151).u8(2).str("Whip").u8(0).done();
        List<String> refs = new ArrayList<>();
        CfgRefs.walk(Arch.CFG_OBJ, 1, obj, types, (t, v, field) -> refs.add(t.key + " " + v));
        expect(f, "obj refs " + refs, refs.equals(List.of("model 1234", "obj 4151")));
        // seq: one frame (group 5 file 2), a placeholder frame (file 0xffff), keyframe 7:0, right hand obj 100
        byte[] seq = new BufOut().u8(1).u16(2).u16(3).u16(3).u16(2).u16(0xffff).u16(5).u16(9).u8(13).i32(7 << 16).u8(7).u16(612).u8(0).done();
        refs.clear();
        CfgRefs.walk(Arch.CFG_SEQ, 1, seq, types, (t, v, field) -> refs.add(t.key + " " + (v >>> 16) + ":" + (v & 0xffff)));
        expect(f, "seq refs " + refs, refs.equals(List.of("frame 5:2", "keyframe 7:0", "obj 0:100")));
        boolean threw = false;
        try { CfgRefs.walk(Arch.CFG_OBJ, 2, new byte[]{(byte) 199, 0}, types, (t, v, x) -> { }); } catch (IllegalStateException e) { threw = true; }
        expect(f, "unknown obj opcode must throw", threw);
        threw = false;
        try { CfgRefs.walk(Arch.CFG_OBJ, 3, new byte[]{44, 0, 0}, types, (t, v, x) -> { }); } catch (IllegalStateException e) { threw = true; }
        expect(f, "truncated obj must throw", threw);
        // IF3 graphic component with an onLoad hook (script 77, int 5, string "x")
        byte[] comp = if3Graphic(321, 77, 5, "x");
        IfWalk.Comp ic = IfWalk.parse(comp);
        expect(f, "IF3 sprite", ic.sprites.equals(List.of(321)));
        expect(f, "IF3 hook", ic.hooks.size() == 1 && ic.hooks.get(0).script() == 77 && ic.hooks.get(0).ints() == 1 && ic.hooks.get(0).strings() == 1);
        threw = false;
        try { IfWalk.parse(java.util.Arrays.copyOf(comp, comp.length + 1)); } catch (IllegalStateException e) { threw = true; }
        expect(f, "IF3 with a trailing byte must throw", threw);
        // map locs: loc 5 at two positions, loc 40000 (an id delta past 32767) at one
        byte[] locs = new BufOut().smart(6).smart(1).u8(10).smart(2).u8(0).smart(0).smart(32767).smart(39995 - 32767 + 0).smart(1).u8(0).smart(0).smart(0).done();
        List<int[]> l = RefCheck.locs(locs);
        expect(f, "map locs " + l.size(), l.size() == 3 && l.get(0)[0] == 5 && l.get(2)[0] == 40000);
    }

    /** IF3 type-5 component bytes with one onLoad hook. */
    public static byte[] if3Graphic(int sprite, int script, int intArg, String strArg) {
        BufOut o = new BufOut();
        o.u8(255).u8(5).u16(0).u16(0).u16(0).u16(10).u16(10).u8(0).u8(0).u8(0).u8(0).u16(65535).u8(0);
        o.i32(sprite).u16(0).u8(0).u8(0).u8(0).i32(0).u8(0).u8(0);
        o.u24(0).str("").u8(0).u8(0).u8(0).u8(0).str("");
        for (int h = 0; h < IfWalk.HOOKS.length; h++) {
            if (h == 0) o.u8(3).u8(0).i32(script).u8(0).i32(intArg).u8(1).str(strArg);
            else o.u8(0);
        }
        o.u8(0).u8(0).u8(0);
        return o.done();
    }

    static void brand(List<String> f) {
        for (String s : new String[]{"Near-Reality Store", "near reality", "NearReality", "Roat Pkz Blackjack", "the OSNR box", "RuneSpawn Credits"})
            expect(f, "brand pattern should match: " + s, !BrandScan.find(s.getBytes()).isEmpty());
        for (String s : new String[]{"GadScape Store", "nearly real", "cosnr", "OSNRX"})
            expect(f, "brand pattern should not match: " + s, BrandScan.find(s.getBytes()).isEmpty());
        List<String> hit = BrandScan.find(new BufOut().u8(2).str("OSNR Mystery Box").u8(0).done());
        expect(f, "brand hit is the whole string: " + hit, hit.equals(List.of("OSNR Mystery Box")));
    }

    static void verifier(List<String> f) {
        MemView c = new MemView("T");
        c.single(Arch.MODELS, 1234, new byte[]{1, 2, 3});
        c.config(Arch.CFG_OBJ, 1, new BufOut().u8(44).i32(1234).u8(2).str("Fine").u8(0).done());
        c.config(Arch.CFG_OBJ, 2, new BufOut().u8(44).i32(5555).u8(2).str("Near-Reality cape").u8(0).done());
        c.single(Arch.SCRIPTS, 77, Cs2.write241(script(1, 1, 1, 1, 21, 0)));
        c.file(Arch.INTERFACES, 9, 0, if3Graphic(-1, 77, 5, "x"));
        c.file(Arch.INTERFACES, 9, 1, if3Graphic(-1, 78, 5, "x"));
        Verifier.Opts o = new Verifier.Opts();
        o.checks.clear();
        o.checks.addAll(List.of("scripts", "refs", "interfaces", "brand"));
        o.log = x -> { };
        o.work = Path.of(System.getProperty("java.io.tmpdir"), "gadconv-verify-selfcheck");
        try {
            Findings fi = Verifier.run(c, o).findings;
            expect(f, "missing model found: " + fi.lines(), fi.badCount("refs/obj->model") == 1 && fi.get("refs/obj->model").ok == 1);
            expect(f, "missing hook script found", fi.badCount("if/hook-script") == 1);
            expect(f, "brand hit found", fi.badCount("brand/configs") == 1);
            expect(f, "the clean script analyses", fi.badCount("cs/parse") == 0);
        } catch (Exception e) {
            f.add("verifier run threw " + e);
        }
    }

    /**
     * Runs {@link VerifyStep} the way build-cacheu does, on a dry-run output (BASE + nothing; writes no cache), with
     * the cheap checks: the step must report no error (dev check of the step wiring; {@code --step}).
     */
    public static List<String> step(Options o) throws Exception {
        List<String> f = new ArrayList<>();
        o.dryRun = true;
        if (o.out == null || o.out.equals(Options.defaults().out)) o.out = (o.work != null ? o.work : Path.of("gadconv-work")).resolve("verify/dry-out");
        if (!o.extra.containsKey("verify.checks")) o.extra.put("verify.checks", "scripts,refs,interfaces,brand");
        try (com.gadscape.cacheconv.core.Ctx ctx = com.gadscape.cacheconv.core.Ctx.open(o, true)) {
            ctx.report.begin("verify");
            new VerifyStep().run(ctx);
            ctx.report.end();
            expect(f, "verify step on BASE reports " + ctx.report.errors() + " errors", ctx.report.errors() == 0);
        }
        return f;
    }

    // ------------------------------------------------------------------ on the real BASE cache

    /** BASE must be clean: no stack violation with the shipped table, every config entry and component walks. */
    public static List<String> full(Path baseDir) throws Exception {
        List<String> f = new ArrayList<>();
        if (baseDir == null || !Files.isDirectory(baseDir)) { f.add("--full needs --base (or -Dgadconv.root)"); return f; }
        try (JagCache base = JagCache.open("BASE", baseDir)) {
            CsWorld w = new CsWorld(base);
            CsTable t = CsTable.load();
            long bad = 0;
            for (CsStack.Result r : w.analyseAll(t::counts).values()) bad += r.violations().size();
            expect(f, "BASE scripts have " + bad + " stack violations with the shipped table", bad == 0);
            Findings fi = new Findings();
            Exists ex = new Exists(base);
            RefCheck.configs(base, ex, new CacheTypes(base), fi);
            RefCheck.interfaces(base, ex, w, fi);
            expect(f, "BASE config entries walk: " + fi.lines(), fi.badCount("cfg/parse") == 0);
            expect(f, "BASE components walk", fi.badCount("if/parse") == 0);
        }
        return f;
    }
}

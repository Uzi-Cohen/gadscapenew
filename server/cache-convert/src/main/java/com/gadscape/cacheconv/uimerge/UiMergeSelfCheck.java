package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.convert.InterfaceConv;
import com.gadscape.cacheconv.convert.ScriptTrailer;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-checks of the UI merge area. {@link #synthetic} needs no cache (unit tests run it); {@link #golden} checks the
 * stored data against the caches: codecs round-trip over every component and script, every manifest entry matches
 * BASE, every stored interface/script decodes and assembles, the component remap only names existing components, and
 * regenerating gives byte-identical data (so the resources are up to date and generation is deterministic).
 */
public final class UiMergeSelfCheck {
    private UiMergeSelfCheck() {}

    // ------------------------------------------------------------------ synthetic

    /** Failures of the cache-free checks (empty = all good). */
    public static List<String> synthetic() {
        List<String> f = new ArrayList<>();
        // IF3 component: every field kind round-trips, diff names the changed field
        byte[] text = comp(4, 1, 2, "Hello", List.of("Op"), 227);
        IfComp c = IfComp.decode(text);
        if (!Arrays.equals(c.encode(), text)) f.add("IfComp round trip (text)");
        IfComp d = c.dup();
        d.f.put("text", "Bye");
        if (!IfComp.diff(c, d).equals(List.of("text"))) f.add("IfComp.diff: " + IfComp.diff(c, d));
        // text edit transfer
        String t = IfMerge.transfer("The Bank of Gielinor - Deposit Box", "The Bank of GadScape - Deposit Box", "The Bank of Gielinor - Item Deposits");
        if (!"The Bank of GadScape - Item Deposits".equals(t)) f.add("transfer: " + t);
        if (IfMerge.transfer("Select skin colour and gender", "Select skin colour", "Select skin colour, body type and pronouns") != null) f.add("transfer: removal not found must give null");
        // element-wise list merge: user adds op 3, BASE added op 2
        List<Object> m = IfMerge.mergeList(List.of("*"), List.of("*", "", "Prev"), List.of("*", "New"));
        if (!m.equals(List.of("*", "New", "Prev"))) f.add("mergeList: " + m);
        // field merge on a tiny interface: user changes text of comp 1 and adds comp 2 under it; BASE inserted a comp before it
        java.util.TreeMap<Integer, IfComp> van = new java.util.TreeMap<>(), user = new java.util.TreeMap<>(), base = new java.util.TreeMap<>();
        van.put(0, IfComp.decode(comp(0, 0, -1, null, List.of(), -1)));
        van.put(1, IfComp.decode(comp(4, 1, 0, "A", List.of(), -1)));
        user.put(0, van.get(0).copy());
        user.put(1, IfComp.decode(comp(4, 1, 0, "B", List.of(), -1)));
        user.put(2, IfComp.decode(comp(4, 2, 1, "C", List.of(), -1)));
        base.put(0, van.get(0).copy());
        base.put(1, IfComp.decode(comp(5, 9, 0, null, List.of(), -1)));
        base.put(2, IfComp.decode(comp(4, 1, 0, "A", List.of(), -1)));
        java.util.TreeMap<Integer, CompRemap.Row> rm = new java.util.TreeMap<>();
        rm.put(0, new CompRemap.Row(0, 0, "high", "t"));
        rm.put(1, new CompRemap.Row(1, 2, "high", "t"));
        IfMerge.Result r = IfMerge.merge(7, van, user, base, rm, new IfMerge.Options(), new IfMerge.Env() {
            public int otherComponent(int p) { return p; }
            public String[] argTypes(int s) { return null; }
            public boolean looksLikeComponent(int v) { return false; }
        });
        if (r.comps.size() != 2 || !"B".equals(r.comps.get(2).c.s("text")) || r.comps.get(3) == null || r.comps.get(3).c.i("parent") != 2)
            f.add("IfMerge synthetic: " + r.comps.keySet() + " applied " + r.applied + " added " + r.added);
        // CsText: write/read round trip of a script with a switch, a long and GadScape lines
        Cs2.Script s = new Cs2.Script();
        s.rev241 = true; s.name = new byte[]{0}; s.localInt = 1; s.intArgs = 1;
        s.code.add(new Cs2.Ins(33, 0, null));
        s.code.add(new Cs2.Ins(60, 0, null));
        s.code.add(new Cs2.Ins(6, 2, null));
        s.code.add(new Cs2.Ins(3, 0, "a \"q\" é"));
        s.code.add(new Cs2.Ins(39, 0, null));
        Cs2.Ins l = new Cs2.Ins(61, 0, null); l.l = 1L << 40; s.code.add(l);
        s.code.add(new Cs2.Ins(40, 10593, null));
        s.code.add(new Cs2.Ins(21, 0, null));
        LinkedHashMap<Integer, Integer> sw = new LinkedHashMap<>(); sw.put(3, 4); s.switches.add(sw);
        CsText.Doc doc = new CsText.Doc(1, s);
        doc.meta.put(s.code.get(6), new CsText.Meta(true, "script"));
        byte[] b = Cs2.write241(s);
        CsText.Doc back = CsText.read(CsText.write(doc));
        if (!Arrays.equals(Cs2.write241(back.s), b)) f.add("CsText round trip");
        if (!back.meta(back.s.code.get(6)).user() || !"script".equals(back.meta(back.s.code.get(6)).tag())) f.add("CsText meta round trip");
        // ScriptEdit: insert before a jump target keeps the jump on the inserted code
        Cs2.Script e2 = Cs2.parse241(b);
        ScriptEdit e = new ScriptEdit(1, e2);
        int ret = e.find(0, "21:0");
        e.insertBefore(ret, e.L(e.u(33, 0), e.u(40, 47000)), "test");
        byte[] eb = e.bytes();
        ScriptTrailer.verify241(1, eb);
        Cs2.Script es = Cs2.parse241(eb);
        if (es.code.get(es.code.size() - 3).op != 33 || es.code.get(es.code.size() - 1).op != 21) f.add("ScriptEdit insert position");
        // id mapping of GadScape references
        com.gadscape.cacheconv.core.IdMap ids = new com.gadscape.cacheconv.core.IdMap();
        ids.record(com.gadscape.cacheconv.core.Kind.SCRIPT, 10593, 47001, "test");
        ids.setRange(com.gadscape.cacheconv.core.Kind.INTERFACE, 5200, 5299);
        ids.record(com.gadscape.cacheconv.core.Kind.INTERFACE, 866, 5200, "test");
        if (RefKinds.map(ids, "script", 10593) != 47001 || RefKinds.map(ids, "component", (866 << 16) | 5) != ((5200 << 16) | 5) || RefKinds.map(ids, "graphic", -1) != -1)
            f.add("RefKinds.map");
        return f;
    }

    /** A minimal IF3 component: type 0 (layer), 4 (text with font 494) or 5 (graphic). */
    static byte[] comp(int type, int x, int parent, String text, List<String> ops, int hookScript) {
        com.gadscape.cacheconv.core.BufOut o = new com.gadscape.cacheconv.core.BufOut();
        o.u8(255).u8(type).u16(0).u16(x).u16(0).u16(10).u16(10).u8(0).u8(0).u8(0).u8(0).u16(parent < 0 ? 65535 : parent).u8(0);
        if (type == 0) o.u16(0).u16(0).u8(0);
        if (type == 5) o.i32(1234).u16(0).u8(0).u8(0).u8(0).i32(0).u8(0).u8(0);
        if (type == 4) o.u16(494).str(text).u8(0).u8(1).u8(1).u8(1).i32(0xff9040);
        o.u24(0).str("").u8(ops.size());
        for (String op : ops) o.str(op);
        o.u8(0).u8(0).u8(0).str("");
        for (int h = 0; h < IfComp.HOOKS.length; h++) {
            if (h == 0 && hookScript >= 0) { o.u8(3).u8(0).i32(hookScript).u8(0).i32(Integer.MIN_VALUE + 3).u8(1).str("title"); }
            else o.u8(0);
        }
        o.u8(0).u8(0).u8(0);
        return o.done();
    }

    // ------------------------------------------------------------------ golden

    static int run(Options o) throws Exception {
        List<String> f = synthetic();
        System.out.println("synthetic checks: " + (f.isEmpty() ? "ok" : f));
        List<String> g = golden(o);
        for (String x : g) System.out.println("  FAIL " + x);
        System.out.println("golden checks: " + (g.isEmpty() ? "ok" : g.size() + " failure(s)"));
        return f.isEmpty() && g.isEmpty() ? 0 : 1;
    }

    /** Checks against the caches (see class doc). {@code --data=DIR} checks a generated directory instead of the classpath data. */
    public static List<String> golden(Options o) throws Exception {
        List<String> f = new ArrayList<>();
        Path dir = o.get("data", null) == null ? null : Path.of(o.get("data", null));
        try (JagCache base = JagCache.open("BASE", o.base); JagCache user = JagCache.open("USER", o.user); JagCache van = JagCache.open("VAN211", o.van211)) {
            // codecs over every component and script
            for (JagCache c : new JagCache[]{base, user, van}) {
                int bad = 0;
                for (int g : c.groupIds(Arch.INTERFACES)) {
                    GroupData gd = c.group(Arch.INTERFACES, g);
                    if (gd == null) continue;
                    for (int k = 0; k < gd.fileCount(); k++) {
                        byte[] b = c == base ? gd.dataAt(k) : InterfaceConv.component(gd.dataAt(k));
                        if (b.length > 0 && !Arrays.equals(IfComp.decode(b).encode(), b)) bad++;
                    }
                }
                c.release(Arch.INTERFACES);
                if (bad > 0) f.add(c.label() + ": " + bad + " components do not round-trip through IfComp");
            }
            int badCs = 0;
            for (int id : base.groupIds(Arch.SCRIPTS)) {
                byte[] b = base.single(Arch.SCRIPTS, id);
                if (b == null || b.length < 18) continue;
                Cs2.Script s = Cs2.parse241(b);
                if (!Arrays.equals(Cs2.write241(CsText.read(CsText.write(s, id, null)).s), b)) badCs++;
            }
            base.release(Arch.SCRIPTS);
            if (badCs > 0) f.add("BASE: " + badCs + " scripts do not round-trip through CsText");
            // stored data vs BASE
            List<MergeData.Entry> man = MergeData.manifest(dir);
            if (man.size() != UiMergeSpecs.INTERFACES.length + UiMergeSpecs.SCRIPTS.length) f.add("manifest has " + man.size() + " entries, expected " + (UiMergeSpecs.INTERFACES.length + UiMergeSpecs.SCRIPTS.length));
            for (MergeData.Entry e : man) {
                boolean iface = e.kind().equals("interface");
                GroupData bg = base.group(iface ? Arch.INTERFACES : Arch.SCRIPTS, e.id());
                if (!MergeData.sha(bg).equals(e.baseSha())) f.add(e.kind() + " " + e.id() + ": BASE sha differs from the manifest");
                if (e.decision().equals("base")) continue;
                String t = MergeData.text(dir, e.file());
                if (t == null) { f.add(e.file() + " missing"); continue; }
                if (iface) {
                    MergeData.IfData d = MergeData.readIf(t);
                    for (MergeData.IfLine l : d.comps()) {
                        IfComp c = IfComp.decode(l.bytes());
                        if (!Arrays.equals(c.encode(), l.bytes())) f.add("interface " + e.id() + ":" + l.comp() + " does not round-trip");
                        if (c.if3 && c.i("parent") >= 0 && d.mode().equals("merge") && bg.dataRef(c.i("parent")) == null && d.comps().stream().noneMatch(x -> x.comp() == c.i("parent")))
                            f.add("interface " + e.id() + ":" + l.comp() + " parent " + c.i("parent") + " exists nowhere");
                    }
                } else {
                    byte[] b = Cs2.write241(CsText.read(t).s);
                    try { ScriptTrailer.verify241(e.id(), b); } catch (RuntimeException ex) { f.add("script " + e.id() + ": " + ex.getMessage()); }
                    int[] a241 = ScriptTrailer.args241WithLongs(b), ab = ScriptTrailer.args241WithLongs(base.single(Arch.SCRIPTS, e.id()));
                    if (!Arrays.equals(a241, ab)) f.add("script " + e.id() + ": arguments " + Arrays.toString(a241) + " differ from BASE's " + Arrays.toString(ab) + " (callers would break)");
                }
            }
            // component remap: every target exists in BASE or in the merged data
            String tsv = MergeData.text(dir, "component-remap.tsv");
            if (tsv == null) f.add("component-remap.tsv missing");
            else {
                Map<Integer, MergeData.IfData> merged = new java.util.HashMap<>();
                for (MergeData.Entry e : man) if (e.kind().equals("interface") && !e.decision().equals("base")) merged.put(e.id(), MergeData.readIf(MergeData.text(dir, e.file())));
                int rows = 0;
                for (String l : tsv.split("\n")) {
                    if (l.isBlank() || l.startsWith("#")) continue;
                    String[] x = l.split("\t", -1);
                    int g = Integer.parseInt(x[0]), to = Integer.parseInt(x[3]);
                    rows++;
                    if (to < 0) continue;
                    boolean ok = base.hasFile(Arch.INTERFACES, g, to) || (merged.containsKey(g) && merged.get(g).comps().stream().anyMatch(c -> c.comp() == to));
                    if (!ok) f.add("component-remap.tsv: " + g + ":" + x[2] + " -> " + to + " does not exist");
                }
                if (rows < 7000) f.add("component-remap.tsv has only " + rows + " rows");
            }
            // regeneration is identical
            UiMergeGen gen = new UiMergeGen(base, user, van, null, o.get("server", null) != null ? Path.of(o.get("server", null)) : UiMergeMain.guessServer(UiMergeMain.resourcesDirOrNull(o)));
            gen.run();
            for (var e : gen.files.entrySet()) {
                String stored = MergeData.text(dir, e.getKey());
                if (!e.getValue().equals(stored)) f.add("regenerated " + e.getKey() + " differs from the stored data (run UiMergeMain gen)");
            }
            for (String p : gen.problems) f.add("generator: " + p);
        }
        return f;
    }

    // ------------------------------------------------------------------ dry run and show

    /** Runs the step against a dry-run output (BASE + pending changes) and prints what it would write. */
    static int dry(Options o) throws Exception {
        o.dryRun = true;
        try (Ctx ctx = Ctx.open(o, true)) {
            ctx.report.begin("ui-merge");
            new UiMergeStep().run(ctx);
            ctx.report.end();
            for (var e : ctx.report.entries()) System.out.println("  " + e);
            System.out.println("errors " + ctx.report.errors() + ", warnings " + ctx.report.warnings() + ", pending groups " + ctx.out.pendingCount());
            return ctx.report.errors() == 0 ? 0 : 1;
        }
    }

    /** Writes BASE + the ui-merge step into --out (a test cache; never an input) and checks every written group reads back. */
    static int write(Options o) throws Exception {
        if (o.extra.containsKey("out")) o.out = Path.of(o.get("out", null));
        try (Ctx ctx = Ctx.open(o, true)) {
            ctx.report.begin("ui-merge");
            new UiMergeStep().run(ctx);
            ctx.report.end();
            ctx.out.commit();
            int bad = 0;
            for (MergeData.Entry e : MergeData.manifest(o.get("uimerge.data", null) == null ? null : Path.of(o.get("uimerge.data", null)))) {
                if (e.decision().equals("base")) continue;
                if (e.kind().equals("script")) {
                    byte[] b = ctx.out.single(Arch.SCRIPTS, e.id());
                    try { ScriptTrailer.verify241(e.id(), b); } catch (RuntimeException ex) { bad++; System.out.println("  script " + e.id() + ": " + ex.getMessage()); }
                } else {
                    GroupData g = ctx.out.group(Arch.INTERFACES, e.id());
                    for (int k = 0; k < g.fileCount(); k++) if (!Arrays.equals(IfComp.decode(g.dataAt(k)).encode(), g.dataAt(k))) { bad++; System.out.println("  interface " + e.id() + ":" + g.fileIdAt(k) + " does not decode"); }
                }
            }
            System.out.println("written to " + ctx.out.dir + ": errors " + ctx.report.errors() + ", read-back failures " + bad);
            return ctx.report.errors() == 0 && bad == 0 ? 0 : 1;
        }
    }

    static int show(Options o) throws Exception {
        Path dir = o.get("data", null) == null ? null : Path.of(o.get("data", null));
        String kind = o.args.get(1);
        int id = Integer.parseInt(o.args.get(2));
        if (kind.startsWith("if")) {
            String t = MergeData.text(dir, "interfaces/" + id + ".if");
            if (t == null) { System.out.println("no merged interface " + id); return 1; }
            MergeData.IfData d = MergeData.readIf(t);
            System.out.println("interface " + id + " mode " + d.mode());
            for (MergeData.IfLine l : d.comps()) System.out.println("  " + l.comp() + " " + l.origin() + " " + IfComp.decode(l.bytes()) + (l.refs().isEmpty() ? "" : "  refs " + l.refs()));
        } else {
            String t = MergeData.text(dir, "scripts/" + id + ".cs2");
            if (t == null) { System.out.println("no merged script " + id); return 1; }
            System.out.print(t);
        }
        return 0;
    }
}

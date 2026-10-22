package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Options;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone entry point of the maps area (no Gradle needed):
 * <pre>
 *   dev/run.sh map.MapsMain classify [--maps.rev225=DIR ...]   classify every square, write WORK/maps/classify.tsv and,
 *                                                              with --update-resource=FILE, the allow-list squares.tsv
 * </pre>
 */
public final class MapsMain {
    private MapsMain() {}

    public static void main(String[] a) throws Exception {
        if (a.length == 0) { System.out.println("usage: MapsMain classify|dry-run|instances|selfcheck [options]"); return; }
        Options o = Options.parse(a, 1);
        switch (a[0]) {
            case "classify" -> classify(o);
            case "square" -> square(o);
            case "keysearch" -> keySearch(o);
            case "instances" -> instances(o);
            case "port-map" -> System.exit(PortMap.run(o));
            case "dry-run", "run" -> run(o, a[0].equals("dry-run"));
            default -> throw new IllegalArgumentException("unknown command " + a[0]);
        }
    }

    /**
     * Runs the maps area's steps alone: maps-plan then maps (plus, with {@code --maps.with=a,b}, other steps by class
     * name first, e.g. {@code --maps.with=com.gadscape.cacheconv.config.ConfigPlanStep}). A dry run writes nothing (the
     * report and id map go to WORK/maps/dry-run/); {@code run} needs {@code --out DIR} outside the inputs and writes a
     * BASE copy with the maps applied.
     */
    static void run(Options o, boolean dry) throws Exception {
        if (dry) { o.dryRun = true; if (o.extra.get("maps.out") == null) o.out = o.work == null ? Path.of("gadconv-work/maps/dry-out") : o.work.resolve("maps/dry-out"); }
        else if (o.get("maps.out", null) != null) o.out = Path.of(o.get("maps.out", null));
        List<com.gadscape.cacheconv.core.Step> steps = new java.util.ArrayList<>();
        for (String c : o.get("maps.with", "").split(",")) if (!c.isBlank()) steps.add((com.gadscape.cacheconv.core.Step) Class.forName(c.trim()).getDeclaredConstructor().newInstance());
        steps.add(new MapLocPlanStep());
        steps.add(new MapsStep());
        long t0 = System.currentTimeMillis();
        Path reportDir;
        Ctx ctx = Ctx.open(o, true);
        try {
            reportDir = ctx.work("maps").resolve(dry ? "dry-run" : "run");
            java.nio.file.Files.createDirectories(reportDir);
            for (var s : steps) {
                long t = System.currentTimeMillis();
                System.out.println("> " + s.name());
                ctx.report.begin(s.name());
                try { s.run(ctx); ctx.out.commit(); } finally { ctx.report.end(); }
                System.out.printf("  %s done in %.1f s%n", s.name(), (System.currentTimeMillis() - t) / 1000.0);
            }
        } finally {
            ctx.close();
        }
        ctx.ids.save(reportDir.resolve("idmap.json"));
        ctx.report.save(reportDir.resolve("report.json"));
        for (var e : ctx.report.toJson().entrySet()) if (e.getKey().equals("steps")) System.out.println(com.gadscape.cacheconv.core.Json.write(e.getValue()));
        System.out.printf("%s in %.1f s: %d relocations, %d warnings, %d errors; report %s%n", dry ? "dry run" : "run", (System.currentTimeMillis() - t0) / 1000.0,
                ctx.ids.size(), ctx.report.warnings(), ctx.report.errors(), reportDir.resolve("report.json"));
    }

    /** The server source tree: --maps.server, else the first parent of the working directory holding core/ and plugins/. */
    static Path serverDir(Options o) {
        String s = o.get("maps.server", null);
        if (s != null) return Path.of(s);
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isDirectory(p.resolve("core/src/main")) && Files.isDirectory(p.resolve("plugins"))) return p;
        throw new IllegalArgumentException("server sources not found: pass --maps.server=DIR");
    }

    /**
     * Lists the squares the server copies into instances (InstanceScan) and how the user's square differs from BASE's
     * and what the output holds there: WORK/maps/instance-squares.tsv and instance-unresolved.txt.
     */
    static void instances(Options o) throws Exception {
        Path server = serverDir(o);
        InstanceScan.Result res = InstanceScan.scan(server);
        try (Ctx ctx = Ctx.open(o, false); Evidence ev = Evidence.load(ctx)) {
            AllowList al = AllowList.load();
            NamedMaps u = new NamedMaps(ctx.user(), ev.userKeys);
            List<String> out = new java.util.ArrayList<>();
            out.add("region	x	y	inUser	inBase	terrain	tilesDiffering	locs	userPlacements	basePlacements	output	sites");
            int differ = 0, n = 0;
            for (var e : res.bySquare().entrySet()) {
                int r = e.getKey();
                byte[] uT = u.terrain(r);
                NamedMaps.Locs uL = u.locs(r);
                boolean inB = Square241.isSquare(ctx.base, r);
                if (uT == null && !inB) continue; // the default 8 x 8 chunk extent reaches past the template
                String terr = "-", locs = "-";
                int tiles = -1, up = uL.ok() ? uL.file().size() : -1, bp = -1;
                if (inB) {
                    byte[] bT = Square241.terrain(ctx.base, r);
                    LocFile bL = Square241.locs(ctx.base, r);
                    bp = bL == null ? -1 : bL.size();
                    if (uT != null) { terr = java.util.Arrays.equals(Terrain.to241(uT), bT) ? "same" : "differs"; tiles = Terrain.tilesDiffering(uT, bT); }
                    if (uL.ok() && bL != null) locs = uL.file().samePlacements(bL) ? "same" : "differs";
                }
                AllowList.Square s = al.squares.get(r);
                String output = s == null ? (inB ? "BASE" : "none") : s.action() + (s.merge() ? " (merged)" : "");
                boolean d = uT != null && inB && (terr.equals("differs") || locs.equals("differs"));
                if (d) differ++;
                StringBuilder sites = new StringBuilder();
                for (var st : e.getValue()) { if (sites.length() > 0) sites.append("; "); sites.append(st.file().replaceAll(".*/", "")).append(':').append(st.line()).append(' ').append(st.call()); }
                out.add(r + "	" + (r >> 8) + "	" + (r & 255) + "	" + (uT != null) + "	" + inB + "	" + terr + "	" + tiles + "	" + locs + "	" + up + "	" + bp + "	" + output + "	" + sites);
                if (++n % 100 == 0) { ctx.user().release(5); ctx.base.release(5); }
            }
            Path dir = ctx.work("maps");
            Files.write(dir.resolve("instance-squares.tsv"), out, StandardCharsets.UTF_8);
            Files.write(dir.resolve("instance-unresolved.txt"), res.unresolved(), StandardCharsets.UTF_8);
            System.out.println(res.resolved().size() + " instance call sites resolved to " + res.bySquare().size() + " squares (" + differ + " where the user's square differs from BASE's), "
                    + res.unresolved().size() + " unresolved -> " + dir.resolve("instance-squares.tsv"));
        }
    }

    /**
     * Evidence: brute-forces keys (OpenRS2 keys/valid.json plus every key file) for the loc groups of USER, VAN211 and
     * REV225 squares no key file decrypts, and writes found_*.json key files next to the evidence.
     */
    static void keySearch(Options o) throws Exception {
        try (Ctx ctx = Ctx.open(o, false); Evidence ev = Evidence.load(ctx)) {
            Path dir = Evidence.defaultDir(ctx);
            List<int[]> keys = new java.util.ArrayList<>(KeySearch.loadKeyList(Path.of(o.get("maps.validKeys", dir.resolve("valid_keys_all.json").toString()))));
            try (var s = Files.list(dir.resolve("keys211"))) {
                for (Path p : s.filter(p -> p.getFileName().toString().startsWith("keys_")).sorted().toList()) keys.addAll(KeySearch.loadKeyList(p));
            }
            System.out.println(keys.size() + " candidate keys");
            Object[][] targets = {
                    {"VAN211", ctx.van211(), ev.van211Keys, dir.resolve("keys211/found_van211.json")},
                    {"USER", ctx.user(), ev.userKeys, dir.resolve("found_user.json")},
                    {"REV225", ev.rev225, ev.keys225, dir.resolve("rev225/1943/found_225.json")}};
            for (Object[] t : targets) {
                if (t[1] == null) continue;
                com.gadscape.cacheconv.core.JagCache c = (com.gadscape.cacheconv.core.JagCache) t[1];
                @SuppressWarnings("unchecked") NamedMaps m = new NamedMaps(c, (List<com.gadscape.cacheconv.core.Xteas>) t[2]);
                java.util.TreeSet<Integer> want = new java.util.TreeSet<>();
                for (int r : m.squares()) if (m.locs(r).status().equals("unreadable")) want.add(r);
                var found = KeySearch.search(c, m, want, keys);
                com.gadscape.cacheconv.core.Json.write((Path) t[3], found);
                java.util.TreeSet<Integer> got = new java.util.TreeSet<>();
                for (var e : found) got.add(com.gadscape.cacheconv.core.Json.i(e.get("mapsquare")));
                java.util.TreeSet<Integer> missing = new java.util.TreeSet<>(want);
                missing.removeAll(got);
                System.out.println(t[0] + ": " + want.size() + " unreadable loc groups, keys found for " + got.size() + " -> " + t[3] + "; still unreadable: " + missing);
            }
        }
    }

    /** Prints what every cache holds for the given squares (region ids or x_y). */
    static void square(Options o) throws Exception {
        try (Ctx ctx = Ctx.open(o, false); Evidence ev = Evidence.load(ctx)) {
            NamedMaps u = new NamedMaps(ctx.user(), ev.userKeys);
            NamedMaps v = new NamedMaps(ctx.van211(), ev.van211Keys);
            NamedMaps r = ev.rev225 == null ? null : new NamedMaps(ev.rev225, ev.keys225);
            for (String s : o.args) {
                int region = s.contains("_") ? (Integer.parseInt(s.split("_")[0]) << 8 | Integer.parseInt(s.split("_")[1])) : Integer.parseInt(s);
                System.out.println("== square " + region + " (" + (region >> 8) + "," + (region & 255) + ")");
                for (var e : new Object[][]{{"USER", u}, {"VAN211", v}, {"REV225", r}}) {
                    NamedMaps m = (NamedMaps) e[1];
                    if (m == null) continue;
                    StringBuilder b = new StringBuilder("  " + e[0] + ": m groups " + m.terrainGroups(region) + ", l groups " + m.locGroups(region));
                    for (int g : m.terrainGroups(region)) {
                        byte[] t = null;
                        try { var d = m.cache.group(5, g); t = d == null ? null : d.dataAt(0); } catch (RuntimeException ex) { b.append(" [m ").append(g).append(": ").append(ex.getMessage()).append("]"); }
                        if (t != null) b.append(" [m ").append(g).append(": ").append(t.length).append(" bytes, tiles end ").append(Terrain.tilesLength(t)).append(", valid211 ").append(Terrain.valid211(t)).append("]");
                    }
                    NamedMaps.Locs l = m.locs(region);
                    b.append(" locs ").append(l.status()).append(l.ok() ? " " + l.file().size() + " placements (group " + l.group() + ")" : "");
                    System.out.println(b);
                }
                for (var c : new com.gadscape.cacheconv.core.CacheView[]{ctx.base, ctx.old241}) {
                    if (c == null) continue;
                    int[] f = c.fileIds(5, region);
                    StringBuilder b = new StringBuilder("  " + c.label() + ": files " + java.util.Arrays.toString(f));
                    if (f.length > 0) {
                        var d = c.group(5, region);
                        for (int i = 0; i < d.fileCount(); i++) b.append(" f").append(d.fileIdAt(i)).append("=").append(d.dataAt(i).length);
                        LocFile lf = LocFile.tryParse(d.dataRef(1));
                        b.append(" locs ").append(lf == null ? "?" : lf.size()).append(" terrain241 ").append(Terrain.valid241(d.dataRef(0)));
                    }
                    System.out.println(b);
                }
            }
        }
    }

    static void classify(Options o) throws Exception {
        long t0 = System.currentTimeMillis();
        try (Ctx ctx = Ctx.open(o, false); Evidence ev = Evidence.load(ctx)) {
            ev.describe.forEach(s -> System.out.println("evidence " + s));
            Classifier c = new Classifier(ctx.base, ctx.user(), ctx.van211(), ctx.old241, ev.userKeys, ev.van211Keys, ev.rev225, ev.keys225);
            List<Classifier.Row> rows = c.run();
            Path out = ctx.work("maps").resolve(o.get("maps.classifyOut", "classify.tsv"));
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
                w.println(Classifier.header());
                for (Classifier.Row r : rows) w.println(r.tsv());
            }
            c.stats.forEach((k, v) -> System.out.println(k + "\t" + v));
            System.out.println("allow-list: " + c.allow.counts() + ", merged " + c.allow.masks.size() + ", squares with vanilla placements of possible movers "
                    + c.allow.keep.size() + " (" + c.allow.keep.values().stream().mapToInt(java.util.Set::size).sum() + " placements), restore loc files " + c.allow.restoreLocs.size()
                    + ", possible movers " + c.movers().size());
            String res = o.get("maps.writeResources", null);
            if (res != null) { c.allow.save(Path.of(res)); System.out.println("resources written to " + res); }
            c.allow.save(ctx.work("maps").resolve("allow-list"));
            System.out.printf("%d squares -> %s in %.1f s%n", rows.size(), out, (System.currentTimeMillis() - t0) / 1000.0);
        }
    }
}

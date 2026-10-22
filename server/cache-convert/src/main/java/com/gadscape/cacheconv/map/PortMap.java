package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Xteas;
import com.gadscape.cacheconv.map.Classifier.Action;
import net.runelite.cache.definitions.loaders.MapLoaderPre209;
import net.runelite.cache.definitions.savers.MapSaver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * {@code gadconv port-map}: ports map squares from a 211-layout cache (named "m"/"l" groups, XTEA-aware: 211, 225, the
 * GadScape editor's caches) or from raw square files into a copy of a 241 cache, under their region ids (the
 * PortMaps / PackIsland / Overlay.maps logic of the old tools):
 * <pre>
 * gadconv port-map --squares=12342,48_55|custom [--from=DIR] [--from-xteas=FILE] [--base DIR] --out DIR [--force] [--dry-run]
 *                  [--env=neighbour|keep|REGION] [--idmap=FILE]
 * gadconv port-map --region=R --terrain-file=F --locs-file=F [--pre209] [--base DIR] --out DIR ...
 * </pre>
 * <ul>
 *   <li>terrain: 211 terrain + the 241 flags byte (0); a pre-209 terrain file is first re-encoded with RuneLite's
 *       MapLoaderPre209 + MapSaver (PackIsland);</li>
 *   <li>locs: the loc file re-encoded canonically (the layout is the same in both builds); {@code --idmap} maps loc ids
 *       through an idmap.json (a build's relocations);</li>
 *   <li>files 2-4: kept from the target square when it exists ({@code --env=keep}, the default for existing
 *       squares); for new squares the nearest square of the target ({@code neighbour}, default) or a given region.</li>
 * </ul>
 * {@code --squares=custom} ports every square of the stored allow-list the build writes (OVERLAY as replacement, no
 * merge). Without --from the source is USER (and --xteas its keys). The output starts as a copy of --base (default
 * BASE) and never overwrites an input.
 */
public final class PortMap {
    private PortMap() {}

    public static int run(Options o) throws Exception {
        String sq = o.get("squares", null), region = o.get("region", null);
        if (sq == null && region == null) {
            System.out.println("usage: gadconv port-map --squares=R1,R2|x_y|custom [--from=DIR] [--from-xteas=FILE] --out DIR [--env=neighbour|keep|REGION] [--idmap=FILE]\n"
                    + "       gadconv port-map --region=R --terrain-file=F --locs-file=F [--pre209] --out DIR");
            return 2;
        }
        if (o.out == null) throw new IllegalArgumentException("--out is required");
        Ctx ctx = Ctx.open(o, true);
        int written = 0, failed = 0;
        try {
            ctx.report.begin("port-map");
            IdMap ids = o.get("idmap", null) == null ? null : IdMap.load(Path.of(o.get("idmap", null)));
            TreeSet<Integer> targetSquares = Square241.squares(ctx.out);
            Env env = new Env(ctx.out, targetSquares);
            String envOpt = o.get("env", "neighbour");
            if (region != null) {
                int r = parseRegion(region);
                byte[] t = Files.readAllBytes(Path.of(req(o, "terrain-file"))), l = Files.readAllBytes(Path.of(req(o, "locs-file")));
                if (o.flag("pre209")) t = new MapSaver().save(new MapLoaderPre209().load(r >> 8, r & 255, t));
                if (put(ctx, r, t, LocFile.parse(l), ids, env, envOpt, targetSquares, "files " + o.get("terrain-file", "") + " + " + o.get("locs-file", ""))) written++; else failed++;
            } else {
                JagCache from = o.get("from", null) == null ? ctx.user() : JagCache.open("FROM", Path.of(o.get("from", null)));
                List<Xteas> keys = new ArrayList<>();
                String kf = o.get("from-xteas", null);
                if (kf != null) keys.add(Xteas.load(Path.of(kf)));
                else if (o.xteas != null && Files.isRegularFile(o.xteas)) keys.add(ctx.xteas());
                NamedMaps src = new NamedMaps(from, keys);
                TreeSet<Integer> regions = new TreeSet<>();
                if (sq.equals("custom")) {
                    for (AllowList.Square s : AllowList.load().squares.values())
                        if (s.action() == Action.OVERLAY || s.action() == Action.OVERLAY_NEW || s.action() == Action.RESTORE) regions.add(s.region());
                } else for (String s : sq.split(",")) if (!s.isBlank()) regions.add(parseRegion(s.trim()));
                for (int r : regions) {
                    byte[] t = src.terrain(r);
                    NamedMaps.Locs l = src.locs(r);
                    if (t == null || (!l.ok() && !l.status().equals("none"))) {
                        ctx.report.error("square", r, "(" + (r >> 8) + "," + (r & 255) + ") not ported: " + (t == null ? "no valid terrain" : "locs " + l.status()));
                        failed++;
                        continue;
                    }
                    if (put(ctx, r, t, l.ok() ? l.file() : LocFile.EMPTY, ids, env, envOpt, targetSquares, from.label())) written++; else failed++;
                    if (written % 50 == 0) { from.release(Arch.MAPS); ctx.out.release(Arch.MAPS); }
                }
                if (from != ctx.user()) from.close();
            }
            ctx.report.end();
        } finally {
            ctx.close();
            Path rd = o.reportDir() == null ? o.out : o.reportDir();
            if (!o.dryRun || o.reportDir != null) { Files.createDirectories(rd); ctx.report.save(rd.resolve("port-map-report.json")); }
        }
        System.out.println("port-map: " + written + " squares written, " + failed + " failed" + (o.dryRun ? " (dry run)" : " -> " + o.out));
        return failed > 0 ? 1 : 0;
    }

    private static String req(Options o, String k) {
        String v = o.get(k, null);
        if (v == null) throw new IllegalArgumentException("--" + k + " is required");
        return v;
    }

    static int parseRegion(String s) {
        if (s.contains("_")) { String[] p = s.split("_"); return Integer.parseInt(p[0]) << 8 | Integer.parseInt(p[1]); }
        return Integer.parseInt(s);
    }

    /** Writes one square: terrain (211 layout) + locs, environment files per --env. */
    static boolean put(Ctx ctx, int r, byte[] terrain211, LocFile locs, IdMap ids, Env env, String envOpt, TreeSet<Integer> targetSquares, String from) {
        if (!Terrain.valid211(terrain211)) { ctx.report.error("square", r, "terrain from " + from + " does not parse"); return false; }
        LocFile l = ids == null ? locs : locs.mapIds(id -> ids.map(Kind.LOC, id));
        GroupData.Builder b = new GroupData.Builder(r).put(0, Terrain.to241(terrain211)).put(1, l.encode());
        String envWhy;
        boolean exists = targetSquares.contains(r);
        if (exists && !envOpt.matches("\\d+")) {
            GroupData g = ctx.out.group(Arch.MAPS, r);
            for (int i = 0; i < g.fileCount(); i++) if (g.fileIdAt(i) >= 2) b.put(g.fileIdAt(i), g.dataAt(i).clone());
            envWhy = "kept from the target square";
        } else {
            int src;
            if (envOpt.matches("\\d+")) { src = Integer.parseInt(envOpt); envWhy = "from square " + src; }
            else { Env.Choice c = env.choose(r); src = c.source(); envWhy = "from " + src + " (" + c.why() + ")"; }
            if (!targetSquares.contains(src)) { ctx.report.error("square", r, "environment source " + src + " is not a square of the target cache"); return false; }
            env.copyInto(b, src);
        }
        ctx.out.putGroup(Arch.MAPS, b.build());
        ctx.report.add(exists ? Decision.USER_CHANGED : Decision.USER_ADDED, "square", r, "(" + (r >> 8) + "," + (r & 255) + ") ported from " + from + ": "
                + l.size() + " placements; environment " + envWhy);
        return true;
    }
}

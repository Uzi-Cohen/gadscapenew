package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;
import com.gadscape.cacheconv.core.Xteas;
import com.gadscape.cacheconv.map.Classifier.Action;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * maps: archive 5 (241 map squares, group = region id, files 0 terrain, 1 locs, 2-4 environment), spec rule 9.
 * The output starts as BASE's archive 5; this step writes only the squares of the stored allow-list
 * ({@link AllowList}, made by {@link Classifier}):
 * <ul>
 *   <li>OVERLAY: GadScape custom square on a square BASE has. With a known start it is merged with BASE tile by tile
 *       ({@link SquareMerge}); otherwise the user's square replaces BASE's (terrain + 1 flags byte, locs). BASE's
 *       environment files stay.</li>
 *   <li>OVERLAY_NEW: GadScape square BASE does not have; RESTORE: vanilla square 241 removed that the user kept (POH
 *       templates, ...). Both get files 2-4 from {@link Env} (a copy's source square or the nearest BASE square, never
 *       Edgeville's by default).</li>
 *   <li>BASE_UNCOMPARABLE / NONE: nothing written, reported.</li>
 * </ul>
 * Loc ids in the written loc files go through the id map (locs moved by {@link MapLocPlanStep} or configs-plan),
 * except the vanilla placements recorded in {@link AllowList#keep}, which keep BASE's id. A user square that no
 * longer matches the CRC it was classified with is not written (error: re-run the classification). Per-square
 * details go to {@code WORK/maps/squares-written.tsv}.
 */
public final class MapsStep implements Step {
    public static final String NAME = "maps";

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) throws Exception {
        AllowList al = AllowList.load();
        // every written user square is stored plain; the user's keys only matter for squares saved encrypted
        List<Xteas> keys = ctx.opt.xteas != null && java.nio.file.Files.isRegularFile(ctx.opt.xteas) ? List.of(ctx.xteas()) : List.of();
        NamedMaps u = new NamedMaps(ctx.user(), keys);
        TreeSet<Integer> baseSquares = Square241.squares(ctx.base);
        Env env = new Env(ctx.base, baseSquares);
        if (ctx.out.usesNames(Arch.MAPS)) ctx.report.warn("output archive 5 uses names: region-id groups are written without one");
        boolean mergeOn = !ctx.opt.get("maps.mode", "merge").equals("overlay");

        List<String> tsv = new ArrayList<>();
        tsv.add("region\tx\ty\taction\thow\tenvSource\tterrainBytes\tplacements\tuserTiles\tuserPlacements\tbasePlacements\tbaseDropped\tlocIdsRewritten\tkeptVanillaIds\twhy");
        TreeMap<String, Long> counts = new TreeMap<>();
        TreeSet<Integer> written = new TreeSet<>();
        int n = 0;
        for (AllowList.Square s : al.squares.values()) {
            int r = s.region();
            String where = "(" + (r >> 8) + "," + (r & 255) + ")";
            switch (s.action()) {
                case BASE_UNCOMPARABLE -> {
                    ctx.report.add(Decision.TAKEN_FROM_BASE, "square", r, where + " " + s.why());
                    counts.merge("squares.base-uncomparable", 1L, Long::sum);
                    continue;
                }
                case NONE -> {
                    ctx.report.warn("square", r, where + " not written: " + s.why());
                    counts.merge("squares.missing-not-restorable", 1L, Long::sum);
                    continue;
                }
                case BASE -> { continue; }
                default -> { }
            }
            byte[] uT = u.terrain(r);
            NamedMaps.Locs uL = u.locs(r);
            long crc = uT == null ? 0 : AllowList.userCrc(uT, uL.bytes());
            if (uT == null && al.restoreTerrain.containsKey(r) && s.userCrc() == 0) {
                uT = al.restoreTerrain.get(r); // the user's copy has no terrain: restored from vanilla 211
                crc = 0;
            }
            if (uT == null || (!uL.ok() && !uL.status().equals("none") && !al.restoreLocs.containsKey(r)) || crc != s.userCrc()) {
                ctx.report.error("square", r, where + " user square " + (uT == null ? "has no terrain" : !uL.ok() && !uL.status().equals("none") ? "locs unreadable (" + uL.status() + ")"
                        : "changed since it was classified (crc " + Long.toHexString(crc) + " != " + Long.toHexString(s.userCrc()) + ")") + ": not written; re-run MapsMain classify");
                counts.merge("squares.stale-classification", 1L, Long::sum);
                continue;
            }
            LocFile userLocs = al.restoreLocs.containsKey(r) ? LocFile.parse(al.restoreLocs.get(r)) : uL.ok() ? uL.file() : LocFile.EMPTY;
            // loc ids through the id map, except the vanilla placements
            TreeSet<Long> keep = al.keep.getOrDefault(r, new TreeSet<>());
            int[] rewritten = {0}, kept = {0};
            List<LocFile.Loc> mapped = new ArrayList<>(userLocs.size());
            for (LocFile.Loc l : userLocs.locs) {
                int to = ctx.ids.map(Kind.LOC, l.id());
                if (to != l.id()) {
                    if (keep.contains((long) l.id() << 24 | (long) l.pos() << 8 | l.attr())) { kept[0]++; to = l.id(); }
                    else rewritten[0]++;
                }
                mapped.add(new LocFile.Loc(to, l.pos(), l.attr()));
            }
            LocFile locs = new LocFile(mapped);

            GroupData.Builder b = new GroupData.Builder(r);
            String how, envWhy = "-";
            SquareMerge.Result mr = null;
            if (s.action() == Action.OVERLAY) {
                BitSet[] mask = al.masks.get(r);
                byte[] bT = Square241.terrain(ctx.base, r);
                LocFile bL = Square241.locs(ctx.base, r);
                if (mergeOn && s.merge() && mask != null && bT != null && bL != null) {
                    mr = SquareMerge.merge(uT, locs, bT, bL, mask[0], mask[1]);
                    b.put(0, mr.terrain()).put(1, mr.locs().encode());
                    how = "merged";
                } else {
                    b.put(0, Terrain.to241(uT)).put(1, locs.encode());
                    how = "replaced";
                }
                GroupData bg = ctx.base.group(Arch.MAPS, r);
                for (int i = 0; i < bg.fileCount(); i++) if (bg.fileIdAt(i) >= 2) b.put(bg.fileIdAt(i), bg.dataAt(i).clone());
                envWhy = "BASE " + r;
            } else {
                b.put(0, Terrain.to241(uT)).put(1, locs.encode());
                Env.Choice c = env.choose(r);
                env.copyInto(b, c.source());
                envWhy = c.source() + " (" + c.why() + ")";
                how = s.action() == Action.RESTORE ? (al.restoreLocs.containsKey(r) ? "restored (locs from " + s.start() + ")" : "restored") : "added";
            }
            GroupData g = b.build();
            if (!Terrain.valid241(g.dataRef(0)) || LocFile.tryParse(g.dataRef(1)) == null) {
                ctx.report.error("square", r, where + " produced an invalid square (terrain/locs do not parse): not written");
                continue;
            }
            ctx.out.putGroup(Arch.MAPS, g);
            written.add(r);
            Decision d = switch (s.action()) {
                case OVERLAY -> how.equals("merged") ? Decision.MERGED : (s.start().equals("-") ? Decision.CLASH_KEPT : Decision.USER_CHANGED);
                default -> Decision.USER_ADDED;
            };
            String detail = where + " " + how + (mr != null ? " (" + mr.userTiles() + " user tiles, " + mr.userPlacements() + " user + " + mr.basePlacements() + " BASE placements, "
                    + mr.baseDropped() + " BASE placements on user tiles dropped)" : "") + (rewritten[0] > 0 ? ", " + rewritten[0] + " loc ids rewritten" : "")
                    + (s.action() != Action.OVERLAY ? ", environment from " + envWhy : "") + "; " + s.why() + (s.known().equals("-") ? "" : " [" + s.known() + "]");
            ctx.report.add(d, "square", r, detail);
            String key = switch (s.action()) {
                case OVERLAY -> how.equals("merged") ? "squares.custom-merged" : "squares.custom-replaced";
                case OVERLAY_NEW -> "squares.custom-new";
                default -> "squares.missing-restored";
            };
            counts.merge(key, 1L, Long::sum);
            counts.merge("placements.loc-ids-rewritten", (long) rewritten[0], Long::sum);
            counts.merge("placements.vanilla-ids-kept", (long) kept[0], Long::sum);
            if (mr != null) counts.merge("placements.base-dropped-on-user-tiles", (long) mr.baseDropped(), Long::sum);
            tsv.add(r + "\t" + (r >> 8) + "\t" + (r & 255) + "\t" + s.action() + "\t" + how + "\t" + envWhy.replace('\t', ' ') + "\t" + g.dataRef(0).length + "\t"
                    + LocFile.parse(g.dataRef(1)).size() + "\t" + (mr == null ? -1 : mr.userTiles()) + "\t" + (mr == null ? -1 : mr.userPlacements()) + "\t"
                    + (mr == null ? -1 : mr.basePlacements()) + "\t" + (mr == null ? -1 : mr.baseDropped()) + "\t" + rewritten[0] + "\t" + kept[0] + "\t" + s.why().replace('\t', ' '));
            if (++n % 50 == 0) { ctx.user().release(Arch.MAPS); ctx.base.release(Arch.MAPS); }
        }
        ctx.user().release(Arch.MAPS);
        ctx.base.release(Arch.MAPS);
        int fromBase = 0;
        for (int r : baseSquares) if (!written.contains(r)) fromBase++;
        counts.put("squares.taken-from-base", (long) fromBase);
        counts.put("squares.written", (long) written.size());
        for (var e : counts.entrySet()) ctx.report.count(e.getKey(), e.getValue());
        MapLocPlanStep.writeTsv(ctx, "squares-written.tsv", tsv);
        ctx.log("archive 5: %d BASE squares kept, %d written %s", fromBase, written.size(), counts);
    }
}

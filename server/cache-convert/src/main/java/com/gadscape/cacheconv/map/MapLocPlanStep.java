package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.config.CfgCodec;
import com.gadscape.cacheconv.config.CfgModel;
import com.gadscape.cacheconv.config.LocWriter;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;
import com.gadscape.cacheconv.map.Classifier.Action;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * maps-plan (audit MAP-6, TELE-08): decides which USER loc definitions move to the reserved loc range because BASE's
 * own map squares place their ids. It writes nothing; it records the moves in the id map ({@link LocWriter#plan}),
 * so it must run before {@code configs} (which then writes each moved USER loc at its new id and keeps BASE's at the
 * old one) and before {@code maps} (which rewrites the user's own placements of a moved id).
 * <p>
 * BASE-rendered loc ids: every loc placed in a BASE square the output keeps (all BASE squares except those a user
 * square replaces wholesale; in merged squares, the placements on tiles the user did not touch), plus the vanilla
 * placements inside written user squares that keep their id ({@link AllowList#keep}), closed over BASE's multiloc
 * transforms. Such an id moves when the USER defines it differently from BASE and the definition is the user's:
 * <ul>
 *   <li>user-added at an id BASE uses for something else (not {@link CfgModel#same}): the Christmas customs 50000+,
 *       Easter 60022-60024, ...;</li>
 *   <li>user-changed into a different object (another name and none of the vanilla models): the GadScape Portal and
 *       the other customs on the nameless scenery ids 35000-35023 (TELE-08), 40027-40033, ...</li>
 * </ul>
 * It stays when it is the same object as BASE's ({@link #sameThing}) and when the user only edited a vanilla loc
 * (same object as vanilla 211's: {@code configs} merges the edit into BASE's definition).
 * <p>
 * A user definition that is rev 225's own (a Jagex object DataMigration imported: {@link com.gadscape.cacheconv.core.Rev225})
 * is Jagex content: {@code configs} gives the id BASE's newer definition, so BASE's squares show BASE's object. It moves
 * only when the user's own written squares also place it and BASE's definition is another object (the user's squares
 * then keep rev 225's object at the new id).
 * <p>
 * A user change that turns the object into another one <i>in place</i> (same name, or same models: the Grand Exchange
 * clan portal 41724 turned Tournament Portal, the clan recruitment board 41728 turned Hiscores) moves too when the
 * user's written squares place it (spec rule 3: BASE's squares keep BASE's object; the user's squares are rewritten to
 * the moved id). Only when no written user square places it (the feature exists only on BASE's squares) does the USER
 * entry stay at the id, so BASE's squares show it (reported as clash-kept).
 * Range: loc ids are u16 on the 241 wire (rsprot OpLoc1V2Decoder reads the id with g2, LocAddChangeV2Encoder writes it
 * with p2) and in multiloc transform lists, so the spec's 70000-79999 cannot hold a loc a player clicks or the server
 * spawns. This step uses {@code --maps.locRange=LO-HI} (default 64500-65534, free in BASE and USER) unless the loc
 * range was already set below 65535. Decisions go to report.json and {@code WORK/maps/loc-moves.tsv}.
 */
public final class MapLocPlanStep implements Step {
    public static final String NAME = "maps-plan";
    public static final int[] DEFAULT_LOC_RANGE = {64500, 65534};

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) throws Exception {
        AllowList al = AllowList.load();
        setRange(ctx);
        CfgModel m = new CfgModel(ctx);
        TreeMap<Integer, TreeSet<Integer>> placedIn = basePlacements(ctx, al);
        TreeMap<Integer, TreeSet<Integer>> userPlaced = userPlacements(ctx, al);
        TreeMap<Integer, String> via = new TreeMap<>();
        for (int id : placedIn.keySet()) via.put(id, "placed");
        closeOverTransforms(m, placedIn.keySet(), via);

        List<String> tsv = new ArrayList<>();
        tsv.add("loc\tto\torigin\tuser\tbase\twhy\tsquares(BASE squares placing it, first 10)");
        int moved = 0, same = 0, edited = 0, vanilla = 0, inPlace = 0, imported = 0, importedMoved = 0, inPlaceMoved = 0;
        for (int id : via.keySet()) {
            if (!ctx.user().hasConfig(Arch.CFG_LOC, id) || !ctx.base.hasConfig(Arch.CFG_LOC, id)) continue;
            Origin o = m.origin(Arch.CFG_LOC, id);
            if (!o.isUser()) { vanilla++; continue; }
            CfgCodec.Entry u, b;
            try { u = m.userEntry(Arch.CFG_LOC, id); b = m.baseEntry(Arch.CFG_LOC, id); }
            catch (RuntimeException e) { ctx.report.error("loc", id, "loc does not decode: " + e.getMessage()); continue; }
            CfgCodec.Entry um = CfgCodec.map(u, m.mapper());
            com.gadscape.cacheconv.core.Rev225.Status r225 = ctx.rev225.config(Arch.CFG_LOC, id, ctx.user().configRef(Arch.CFG_LOC, id));
            TreeSet<Integer> userSq = userPlaced.getOrDefault(id, new TreeSet<>());
            if (r225 != null) {
                // rev 225's own object (or the user's small edit of it): configs gives the id BASE's newer definition
                if (userSq.isEmpty() || sameThing(um, b) || sameThing(u, b)) { imported++; continue; }
                String what = "rev-225 import loc " + id + " (" + describe(u) + ") that the user's own squares place " + userSq.size() + "x where BASE has " + describe(b)
                        + "; BASE " + (via.get(id).equals("placed") ? "places it in its squares" : "renders it as " + via.get(id)) + ": the user's squares keep the 225 object at the new id";
                int to = LocWriter.plan(ctx, id, "MAP-6: " + what);
                importedMoved++;
                TreeSet<Integer> sq = placedIn.getOrDefault(id, new TreeSet<>());
                tsv.add(id + "\t" + to + "\t" + o + "+rev225\t" + tab(describe(u)) + "\t" + tab(describe(b)) + "\t" + tab(what) + "\t" + sq.size() + ":" + sq.stream().limit(10).toList());
                continue;
            }
            if (sameThing(um, b) || sameThing(u, b)) { same++; continue; }
            CfgCodec.Entry ref = b;
            if (o == Origin.USER_CHANGED) {
                ref = m.vanEntry(Arch.CFG_LOC, id);
                if (sameThing(u, ref)) { edited++; continue; }
            }
            if (inPlace(u, ref, o == Origin.USER_CHANGED) && userSq.isEmpty()) {
                // the user turned the object into another one (same look or same name) and no square of the user's
                // places it: the feature lives on BASE's squares only, so the USER entry stays at the id (configs
                // keeps it as a clash) and BASE's squares show it. With user squares placing it, it moves like any
                // other user object: BASE's squares keep BASE's object, the user's squares the moved one.
                inPlace++;
                ctx.report.add(Decision.CLASH_KEPT, "loc", id, "MAP-6: " + (o == Origin.USER_ADDED ? "user-added" : "user-changed") + " loc " + id + " (" + describe(u)
                        + ") repurposes " + (o == Origin.USER_ADDED ? "BASE's " : "vanilla ") + describe(ref) + " in place (same name or models): not moved; BASE squares show the user's object");
                continue;
            }
            if (inPlace(u, ref, o == Origin.USER_CHANGED)) inPlaceMoved++;
            String what = (o == Origin.USER_ADDED ? "user-added" : "user-repurposed") + " loc " + id + " (" + describe(u) + ") where BASE has " + describe(b)
                    + "; BASE " + (via.get(id).equals("placed") ? "places it in its squares" : "renders it as " + via.get(id))
                    + (userSq.isEmpty() ? "" : "; the user's squares place it " + userSq.size() + "x (rewritten to the new id)");
            int to = LocWriter.plan(ctx, id, "MAP-6: " + what);
            moved++;
            TreeSet<Integer> sq = placedIn.getOrDefault(id, new TreeSet<>());
            tsv.add(id + "\t" + to + "\t" + o + "\t" + tab(describe(u)) + "\t" + tab(describe(b)) + "\t" + tab(what) + "\t" + sq.size() + ":" + sq.stream().limit(10).toList());
        }
        ctx.report.count("loc.base-rendered-ids", via.size());
        ctx.report.count("loc.relocated", moved);
        ctx.report.count("loc.same-as-base", same);
        ctx.report.count("loc.vanilla", vanilla);
        ctx.report.count("loc.user-edited-vanilla-kept-for-configs", edited);
        ctx.report.count("loc.repurposed-in-place-kept", inPlace);
        ctx.report.count("loc.rev225-imports-base-definition", imported);
        ctx.report.count("loc.rev225-imports-moved-for-user-squares", importedMoved);
        ctx.report.count("loc.repurposed-in-place-moved-for-user-squares", inPlaceMoved);
        Files.write(ctx.work("maps").resolve("loc-moves.tsv"), tsv, StandardCharsets.UTF_8);
        ctx.log("%d loc ids rendered by BASE squares; %d relocated (%d repurposed in place, placed by user squares), %d same thing, %d user edits of vanilla locs left to configs, %d repurposed in place (kept: no user square places them), %d rev-225 imports (BASE's definition), %d rev-225 imports moved for the user's squares",
                via.size(), moved + importedMoved, inPlaceMoved, same, edited, inPlace, imported, importedMoved);
    }

    /**
     * Whether two loc definitions are the same object for map purposes: {@link CfgModel#same}, or the same name
     * (case, punctuation and word order ignored) and an overlapping model set (Jagex added a model to an object the
     * user imported, or the user's editor wrote the model list twice), or, for objects without models, the same name and
     * overlapping (or no) multiloc transforms. A user edit of a vanilla loc that keeps it the same object (ops, flags,
     * params) is merged into BASE's definition by configs instead of moving.
     */
    static boolean sameThing(CfgCodec.Entry a, CfgCodec.Entry b) {
        if (a == null || b == null) return false;
        if (CfgModel.same(Arch.CFG_LOC, a, b).same()) return true;
        if (!CfgModel.sameName(CfgCodec.name(a), CfgCodec.name(b))) return false;
        TreeSet<Integer> ma = new TreeSet<>(CfgCodec.refsOf(a, Kind.MODEL)), mb = new TreeSet<>(CfgCodec.refsOf(b, Kind.MODEL));
        if (!ma.isEmpty() || !mb.isEmpty()) { ma.retainAll(mb); return !ma.isEmpty(); }
        TreeSet<Integer> ta = new TreeSet<>(CfgCodec.refsOf(a, Kind.LOC)), tb = new TreeSet<>(CfgCodec.refsOf(b, Kind.LOC));
        ta.remove(-1); tb.remove(-1);
        if (ta.isEmpty() && tb.isEmpty()) return true;
        ta.retainAll(tb);
        return !ta.isEmpty();
    }

    /**
     * Whether the user's object replaces the reference object where it stands: an overlapping model set (the same
     * physical object renamed or re-scripted), or, for a vanilla loc the user changed ({@code byName}), the same name
     * (both named: a re-skin). A deliberate change of that object, not a new object that happens to reuse the id (the
     * Christmas packer handing out 50000+ over rev 225's objects).
     */
    static boolean inPlace(CfgCodec.Entry u, CfgCodec.Entry ref, boolean byName) {
        if (u == null || ref == null) return false;
        String nu = CfgCodec.name(u), nr = CfgCodec.name(ref);
        boolean named = nu != null && nr != null && !nu.equalsIgnoreCase("null") && !nr.equalsIgnoreCase("null");
        if (byName && named && CfgModel.sameName(nu, nr)) return true;
        TreeSet<Integer> ma = new TreeSet<>(CfgCodec.refsOf(u, Kind.MODEL));
        ma.retainAll(new TreeSet<>(CfgCodec.refsOf(ref, Kind.MODEL)));
        return !ma.isEmpty();
    }

    /** Applies the loc range: --maps.locRange, else the default below 65535 when the current range reaches past it. */
    static void setRange(Ctx ctx) {
        String opt = ctx.opt.get("maps.locRange", null);
        IdMap.Range cur = ctx.ids.range(Kind.LOC);
        if (opt != null) {
            String[] p = opt.split("-");
            ctx.ids.setRange(Kind.LOC, Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
        } else if (cur == null || cur.hi() > 65534) {
            ctx.ids.setRange(Kind.LOC, DEFAULT_LOC_RANGE[0], DEFAULT_LOC_RANGE[1]);
            ctx.report.note("loc range set to " + DEFAULT_LOC_RANGE[0] + "-" + DEFAULT_LOC_RANGE[1] + " (was " + (cur == null ? "none" : cur.lo() + "-" + cur.hi())
                    + "): loc ids are u16 in 241's OPLOC/LOC_ADD_CHANGE packets and in multiloc transforms");
        }
        IdMap.Range r = ctx.ids.range(Kind.LOC);
        int used = 0;
        for (int id = r.lo(); id <= r.hi(); id++) if (!ctx.ids.isFree(Kind.LOC, id) && !ctx.ids.all(Kind.LOC).containsValue(id)) used++;
        if (used > 0) ctx.report.warn("loc", Long.MIN_VALUE, "loc range " + r.lo() + "-" + r.hi() + " already has " + used + " used ids (the allocator skips them)");
    }

    /** Loc id -> BASE squares placing it, over the BASE squares (and tiles) the output keeps, plus kept vanilla placements. */
    static TreeMap<Integer, TreeSet<Integer>> basePlacements(Ctx ctx, AllowList al) {
        TreeMap<Integer, TreeSet<Integer>> placed = new TreeMap<>();
        int n = 0;
        for (int r : Square241.squares(ctx.base)) {
            AllowList.Square s = al.squares.get(r);
            boolean wholesale = s != null && s.action() == Action.OVERLAY && !s.merge();
            if (wholesale) continue;
            LocFile f = Square241.locs(ctx.base, r);
            if (f == null) continue;
            BitSet[] mask = s != null && s.action() == Action.OVERLAY ? al.masks.get(r) : null;
            for (LocFile.Loc l : f.locs) {
                if (mask != null && (mask[0].get(l.pos()) || mask[1].get(l.pos()))) continue; // user's tile
                placed.computeIfAbsent(l.id(), k -> new TreeSet<>()).add(r);
            }
            if (++n % 300 == 0) ctx.base.release(Arch.MAPS);
        }
        ctx.base.release(Arch.MAPS);
        for (var e : al.keep.entrySet())
            for (long k : e.getValue()) placed.computeIfAbsent((int) (k >>> 24), x -> new TreeSet<>()).add(e.getKey());
        return placed;
    }

    /**
     * Loc id -> written user squares placing it on the user's own tiles (OVERLAY squares: every placement when the
     * square replaces BASE's, the placements on mask tiles when it is merged; OVERLAY_NEW / RESTORE: every placement),
     * without the vanilla placements that keep BASE's id ({@link AllowList#keep}).
     */
    static TreeMap<Integer, TreeSet<Integer>> userPlacements(Ctx ctx, AllowList al) throws java.io.IOException {
        java.util.List<com.gadscape.cacheconv.core.Xteas> keys = ctx.opt.xteas != null && Files.isRegularFile(ctx.opt.xteas) ? java.util.List.of(ctx.xteas()) : java.util.List.of();
        NamedMaps u = new NamedMaps(ctx.user(), keys);
        boolean mergeOn = !ctx.opt.get("maps.mode", "merge").equals("overlay");
        TreeMap<Integer, TreeSet<Integer>> placed = new TreeMap<>();
        int n = 0;
        for (AllowList.Square s : al.squares.values()) {
            int r = s.region();
            if (!al.writes(r)) continue;
            LocFile f;
            if (al.restoreLocs.containsKey(r)) f = LocFile.parse(al.restoreLocs.get(r));
            else { NamedMaps.Locs l = u.locs(r); f = l.ok() ? l.file() : null; }
            if (f == null) continue;
            BitSet[] mask = s.action() == Action.OVERLAY && s.merge() && mergeOn ? al.masks.get(r) : null;
            TreeSet<Long> keep = al.keep.getOrDefault(r, new TreeSet<>());
            for (LocFile.Loc l : f.locs) {
                if (mask != null && !(mask[0].get(l.pos()) || mask[1].get(l.pos()))) continue;
                if (keep.contains((long) l.id() << 24 | (long) l.pos() << 8 | l.attr())) continue;
                placed.computeIfAbsent(l.id(), k -> new TreeSet<>()).add(r);
            }
            if (++n % 50 == 0) ctx.user().release(Arch.MAPS);
        }
        ctx.user().release(Arch.MAPS);
        return placed;
    }

    /** Adds the multiloc children (BASE definitions) of every rendered id, transitively. */
    static void closeOverTransforms(CfgModel m, java.util.Collection<Integer> start, TreeMap<Integer, String> via) {
        Deque<Integer> q = new ArrayDeque<>(start);
        while (!q.isEmpty()) {
            int id = q.poll();
            CfgCodec.Entry b;
            try { b = m.baseEntry(Arch.CFG_LOC, id); } catch (RuntimeException e) { continue; }
            if (b == null) continue;
            for (int c : CfgCodec.refsOf(b, Kind.LOC)) {
                if (c < 0 || via.containsKey(c)) continue;
                via.put(c, "a transform of BASE loc " + id);
                q.add(c);
            }
        }
    }

    static String describe(CfgCodec.Entry e) {
        if (e == null) return "-";
        String n = CfgCodec.name(e);
        List<Integer> models = CfgCodec.refsOf(e, Kind.MODEL);
        return "'" + (n == null ? "null" : n) + "' models " + (models.size() > 4 ? models.subList(0, 4) + "..." : models);
    }

    private static String tab(String s) { return s.replace('\t', ' '); }

    /** Writes a TSV with the given header and rows to the maps work directory (used by the maps step too). */
    static void writeTsv(Ctx ctx, String name, List<String> rows) throws java.io.IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(ctx.work("maps").resolve(name), StandardCharsets.UTF_8))) {
            for (String r : rows) w.println(r);
        }
    }

    /** Reports a decision about one square. */
    static void square(Ctx ctx, Decision d, int region, String detail) {
        ctx.report.add(d, "square", region, "(" + (region >> 8) + "," + (region & 255) + ") " + detail);
    }
}

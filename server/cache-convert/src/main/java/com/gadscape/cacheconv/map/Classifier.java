package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Xteas;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Decides, per map square, whether GadScape changed it (spec rule 9, audit MAP-1..MAP-5), by comparing the user's
 * square with the squares the user's cache can have <i>started from</i> (its candidates):
 * <ul>
 *   <li>rev 225's square, where GadScape's DataMigration could have ported it (TypeParser: squares with regionX &le; 99
 *       that 225 has with a readable loc group, except DataMigration's skip list; whether a square really was ported
 *       depended on the keys GadScape had, so both candidates are kept), and</li>
 *   <li>vanilla 211's square (VAN211, loc groups decrypted with the public 211 keys; the key-free {@link TailProbe}
 *       where no key is known).</li>
 * </ul>
 * The user's square is <b>not custom</b> when it equals one candidate: same terrain bytes and same loc placements. A
 * user loc file that is empty, or missing, where the candidate has placements also counts as equal: it is what the
 * 225 port's group-id collisions and keyless editor saves left behind (audit MAP-5), not an edit. The square is
 * <b>custom</b> when it differs from every candidate (or there is none: a square GadScape added), and
 * <b>uncomparable</b> when its terrain equals a candidate whose locs cannot be read or probed: such a square is never
 * written over BASE ("never write a square whose vanilla objects cannot be compared").
 * <p>
 * A custom square whose every difference from its start is also in BASE (identical to BASE, or only tiles/placements
 * BASE has too: the user imported newer Jagex content) is BASE's.
 * <p>
 * Evidence inputs: USER, VAN211, BASE (2746), OLD241 (2740), the user's xteas.json, and optionally (see
 * {@link Evidence}) the rev-225 cache with its keys and the public vanilla-211 keys. Without the 225 cache every
 * square starts from 211 and 225 imports show up as custom (the audit's 563 MAP-2 squares).
 */
public final class Classifier {
    /** DataMigration.skipRegion: squares the 225 port left at their 211 version. */
    public static final Set<Integer> PORT225_SKIP = Set.of(12889,
            13136, 13137, 13138, 13139, 13140, 13141, 13142, 13143, 13144, 13145,
            13392, 13393, 13394, 13395, 13396, 13397, 13398, 13399, 13400, 13401,
            12610, 12611, 12612, 12613, 12866, 12867, 12868, 12869, 13122, 13123, 13124, 13125);

    public enum Action {
        /** GadScape custom square on a square BASE also has: the user's square replaces BASE's. */
        OVERLAY,
        /** GadScape custom square BASE does not have: added, environment files from a neighbouring BASE square. */
        OVERLAY_NEW,
        /** Vanilla (211/225) square that BASE no longer has: the user's copy is restored (nothing of BASE is lost). */
        RESTORE,
        /** BASE's square (vanilla, 225 import, or the user's square equals BASE's). */
        BASE,
        /** BASE's square: the user's square may be custom but cannot be compared (never written over BASE). */
        BASE_UNCOMPARABLE,
        /** Nothing is written: not in BASE and the user's square cannot be ported (missing terrain, unreadable locs). */
        NONE
    }

    /** Loc comparison of the user's square with one candidate. */
    enum LocCmp { EQUAL, LOST, EMPTY_UNKNOWN, DIFF, UNKNOWN }

    /** One square's evidence and decision. */
    public static final class Row {
        public int region;
        public boolean inU, inV, inB, inO, in225, port225;
        public String start = "-";       // 225 | 211 | - : the candidate the user's square equals, or the closest one
        public String uLocs = "-";       // plain | key | none | unreadable | -
        public String startLocs = "-";   // plain | key | none | unreadable | -
        public String terrainVsStart = "-", locsVsStart = "-", terrainVsBase = "-", locsVsBase = "-";
        public int tilesVsStart = -1, tilesVsBase = -1, tilesTowardBase = -1;
        public int locsAdded = -1, locsRemoved = -1, addedInBase = -1, removedInBase = -1, uLocCount = -1;
        public boolean dupNames;
        public String known = "-";
        public Action action = Action.NONE;
        public String why = "";

        public boolean custom() { return action == Action.OVERLAY || action == Action.OVERLAY_NEW; }

        public static final String HEADER = "region\tx\ty\tinU\tinV\tinB\tinOld241\tin225\tport225\tstart\tuLocs\tstartLocs\ttVsStart\tlVsStart\ttilesVsStart\tlocsAdded\tlocsRemoved"
                + "\ttVsBase\tlVsBase\ttilesVsBase\ttilesTowardBase\taddedInBase\tremovedInBase\tuLocCount\tdupNames\tknown\taction\twhy";

        public String tsv() {
            return region + "\t" + (region >> 8) + "\t" + (region & 255) + "\t" + b(inU) + "\t" + b(inV) + "\t" + b(inB) + "\t" + b(inO) + "\t" + b(in225) + "\t" + b(port225)
                    + "\t" + start + "\t" + uLocs + "\t" + startLocs + "\t" + terrainVsStart + "\t" + locsVsStart + "\t" + tilesVsStart + "\t" + locsAdded + "\t" + locsRemoved
                    + "\t" + terrainVsBase + "\t" + locsVsBase + "\t" + tilesVsBase + "\t" + tilesTowardBase + "\t" + addedInBase + "\t" + removedInBase + "\t" + uLocCount
                    + "\t" + b(dupNames) + "\t" + known + "\t" + action + "\t" + why;
        }

        private static String b(boolean v) { return v ? "1" : "0"; }
    }

    /** A square the user's cache may have started from. */
    private record Cand(String name, NamedMaps maps, byte[] terrain, NamedMaps.Locs locs) {}

    private final CacheView base, user, van, old241, rev225;
    private final NamedMaps u, v, r225;
    private final TreeMap<Integer, String> known;
    public final TreeMap<String, Integer> stats = new TreeMap<>();
    /** What the build needs: written/reported squares, merge masks, vanilla placements, restore loc files. */
    public final AllowList allow = new AllowList();
    /** Loc ids the user (re)defined that BASE also defines: the ones the maps plan may relocate (MAP-6). */
    private final TreeSet<Integer> movers = new TreeSet<>();
    private TreeMap<Integer, Env.Choice> copySources;

    /**
     * @param userKeys  the user's xteas.json (+ found keys): USER loc groups saved by Jagex tools are encrypted
     * @param vanKeys   vanilla 211 keys (may be empty: VAN211 locs are then compared with the tail probe only)
     * @param rev225    the rev-225 cache GadScape ported (null: every square starts from 211)
     * @param keys225   its keys
     */
    public Classifier(CacheView base, CacheView user, CacheView van, CacheView old241, List<Xteas> userKeys, List<Xteas> vanKeys,
                      CacheView rev225, List<Xteas> keys225) throws IOException {
        this.base = base; this.user = user; this.van = van; this.old241 = old241; this.rev225 = rev225;
        this.u = new NamedMaps(user, userKeys);
        this.v = new NamedMaps(van, vanKeys);
        this.r225 = rev225 == null ? null : new NamedMaps(rev225, keys225);
        this.known = loadKnown();
        for (int id : user.configIds(com.gadscape.cacheconv.core.Arch.CFG_LOC)) {
            if (!base.hasConfig(com.gadscape.cacheconv.core.Arch.CFG_LOC, id)) continue;
            if (com.gadscape.cacheconv.core.Origin.ofConfig(com.gadscape.cacheconv.core.Arch.CFG_LOC, id, user, van).isUser()) movers.add(id);
        }
        this.copySources = Env.loadOverrides();
        if (rev225 != null) {
            int a = com.gadscape.cacheconv.core.Arch.CFG_LOC;
            for (int id : user.configIds(a)) {
                if (van.hasConfig(a, id)) continue;
                byte[] ub = user.configRef(a, id), rb = rev225.configRef(a, id);
                if (ub != null && rb != null && Arrays.equals(ub, rb)) allow.loc225.set(id);
            }
            user.release(com.gadscape.cacheconv.core.Arch.CONFIGS);
            rev225.release(com.gadscape.cacheconv.core.Arch.CONFIGS);
            stats.put("locs.225-imports", allow.loc225.cardinality());
        }
    }

    /** Loc ids the user (re)defined that BASE also defines. */
    public TreeSet<Integer> movers() { return movers; }

    /** The packer-made squares (resource map/known-custom.tsv): region -> provenance. */
    public static TreeMap<Integer, String> loadKnown() throws IOException {
        TreeMap<Integer, String> m = new TreeMap<>();
        try (InputStream in = Classifier.class.getResourceAsStream("/map/known-custom.tsv")) {
            if (in == null) throw new IOException("resource map/known-custom.tsv missing");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                m.put(Integer.parseInt(f[0]), f.length > 4 ? f[4] : "known");
            }
        }
        return m;
    }

    public NamedMaps userMaps() { return u; }

    private void stat(String k) { stats.merge(k, 1, Integer::sum); }

    /** Classifies every square of USER, VAN211 and BASE, ascending. */
    public List<Row> run() {
        TreeSet<Integer> baseSquares = Square241.squares(base);
        TreeSet<Integer> oldSquares = old241 == null ? new TreeSet<>() : Square241.squares(old241);
        TreeSet<Integer> all = new TreeSet<>(u.squares());
        all.addAll(v.squares());
        all.addAll(baseSquares);
        List<Row> rows = new ArrayList<>();
        int n = 0;
        for (int r : all) {
            Row row = classify(r, baseSquares.contains(r), oldSquares.contains(r));
            rows.add(row);
            stat("action." + row.action);
            if (!row.known.equals("-") && !row.custom()) stat("known-custom-not-overlaid");
            if (++n % 200 == 0) { user.release(5); van.release(5); base.release(5); if (rev225 != null) rev225.release(5); if (old241 != null) old241.release(5); }
        }
        return rows;
    }

    /** Compares the user's locs with a candidate's. */
    static LocCmp compareLocs(NamedMaps.Locs uL, NamedMaps.Locs cL, NamedMaps cMaps, int r) {
        boolean uEmpty = uL.status().equals("none") || (uL.ok() && uL.file().size() == 0);
        if (!uL.ok() && !uL.status().equals("none")) return LocCmp.UNKNOWN;
        if (cL.ok()) {
            if (uEmpty) return cL.file().size() == 0 ? LocCmp.EQUAL : LocCmp.LOST;
            return uL.file().samePlacements(cL.file()) ? LocCmp.EQUAL : LocCmp.DIFF;
        }
        if (cL.status().equals("none")) return uEmpty ? LocCmp.EQUAL : LocCmp.DIFF;
        // candidate unreadable: key-free probe
        if (uL.status().equals("none")) return LocCmp.EMPTY_UNKNOWN;
        TailProbe.Result t = TailProbe.compare(cMaps.rawLocContainer(r), uL.bytes());
        if (t == TailProbe.Result.SAME) return LocCmp.EQUAL;
        if (uEmpty) return t == TailProbe.Result.DIFF ? LocCmp.LOST : LocCmp.EMPTY_UNKNOWN;
        return t == TailProbe.Result.DIFF ? LocCmp.DIFF : LocCmp.UNKNOWN;
    }

    Row classify(int r, boolean inB, boolean inO) {
        Row row = classify0(r, inB, inO);
        if (row.action != Action.BASE) record(row);
        return row;
    }

    /** The start candidate chosen for the last classified square (for {@link #record}). */
    private Cand lastStart;
    private byte[] lastUT;
    private NamedMaps.Locs lastUL;
    private boolean lastLost;
    /** The last square is restored wholly from vanilla 211 (the user's copy has no terrain). */
    private boolean restoreFromVanilla;

    Row classify0(int r, boolean inB, boolean inO) {
        lastStart = null; lastUT = null; lastUL = null; lastLost = false; restoreFromVanilla = false;
        Row row = new Row();
        row.region = r;
        row.inU = u.has(r); row.inV = v.has(r); row.inB = inB; row.inO = inO;
        row.in225 = r225 != null && r225.has(r);
        row.known = known.getOrDefault(r, "-");
        row.dupNames = u.terrainGroups(r).size() > 1 || u.locGroups(r).size() > 1;
        if (!row.inU) {
            row.action = inB ? Action.BASE : Action.NONE;
            row.why = inB ? (row.inV ? "user removed the square; BASE has it" : "BASE only") : "vanilla 211 only (removed by the user and by 241)";
            return row;
        }
        byte[] uT = u.terrain(r);
        NamedMaps.Locs uL = u.locs(r);
        lastUT = uT; lastUL = uL;
        row.uLocs = uL.status();
        row.uLocCount = uL.ok() ? uL.file().size() : -1;

        List<Cand> cands = new ArrayList<>();
        if (r225 != null && (r >> 8) <= 99 && !PORT225_SKIP.contains(r) && r225.hasTerrain(r) && r225.hasLocs(r)) {
            NamedMaps.Locs l = r225.locs(r);
            byte[] t = r225.terrain(r);
            if (l.ok() && t != null) { cands.add(new Cand("225", r225, t, l)); row.port225 = true; }
        }
        if (row.inV) cands.add(new Cand("211", v, v.terrain(r), v.locs(r)));

        if (uT == null) {
            // the user's cache lost the terrain group (225-port id collisions, audit MAP-4): a square BASE lacks is
            // restored from vanilla 211 when both its terrain and its locs can be read, else nothing can be ported
            row.start = cands.isEmpty() ? "-" : cands.get(cands.size() - 1).name;
            Cand van = null;
            for (Cand c : cands) if (c.name.equals("211")) van = c;
            if (!inB && van != null && van.terrain != null && van.locs.ok()) {
                row.action = Action.RESTORE;
                row.why = "user cache lost this square's terrain (225-port group-id collision); BASE lacks it: vanilla 211 square restored";
                lastStart = van;
                restoreFromVanilla = true;
                stat("restore.from-vanilla");
                return row;
            }
            row.action = inB ? Action.BASE : Action.NONE;
            row.why = "user cache has no terrain for this square (lost to the 225 port's group-id collisions)"
                    + (inB ? "; BASE kept" : "; " + (row.inV ? "vanilla 211 terrain exists but its locs " + (v.locs(r).ok() ? "are readable" : "cannot be decrypted (no key known)") : "no source") + ", not restored");
            stat("user.terrain-missing");
            return row;
        }
        if (!uL.ok() && !uL.status().equals("none")) {
            row.action = inB ? Action.BASE_UNCOMPARABLE : Action.NONE;
            row.why = "user locs unreadable (no key decrypts them)";
            stat("user.locs-unreadable");
            return row;
        }

        // compare with every candidate
        Cand match = null, uncomparable = null, best = null;
        LocCmp matchCmp = null;
        int bestTiles = Integer.MAX_VALUE;
        for (Cand c : cands) {
            boolean tEq = c.terrain != null && Arrays.equals(uT, c.terrain);
            LocCmp lc = compareLocs(uL, c.locs, c.maps, r);
            boolean lEq = lc == LocCmp.EQUAL || lc == LocCmp.LOST || lc == LocCmp.EMPTY_UNKNOWN;
            if (tEq && lEq && match == null) { match = c; matchCmp = lc; }
            if (tEq && lc == LocCmp.UNKNOWN && uncomparable == null) uncomparable = c;
            int tiles = c.terrain == null ? 16384 : tEq ? 0 : Terrain.tilesDiffering(uT, c.terrain);
            if (tiles < 0) tiles = 16384;
            if (tiles < bestTiles) { bestTiles = tiles; best = c; }
        }
        Cand start = match != null ? match : uncomparable != null ? uncomparable : best;
        lastStart = start;
        lastLost = match != null && matchCmp == LocCmp.LOST;
        if (start != null) fillStart(row, start, uT, uL, r);

        // user vs BASE
        if (inB) fillBase(row, start, uT, uL, r);
        boolean eqBase = inB && "=".equals(row.terrainVsBase) && (row.locsVsBase.equals("=") || row.locsVsBase.equals("=p"));

        if (match != null) {
            String note = matchCmp == LocCmp.LOST ? " (user loc file " + (uL.ok() ? "empty" : "missing") + " where " + match.name + " has " + (match.locs.ok() ? match.locs.file().size() + " placements" : "placements")
                    + ": a keyless save / 225-port artifact, not an edit)" : matchCmp == LocCmp.EMPTY_UNKNOWN ? " (user loc file empty, " + match.name + "'s cannot be read)" : "";
            if (matchCmp != LocCmp.EQUAL) stat("user.locs-lost-artifact");
            if (inB) { row.action = Action.BASE; row.why = "user square equals " + match.name + note; }
            else if (matchCmp == LocCmp.EQUAL) { row.action = Action.RESTORE; row.why = "unchanged " + match.name + " square that 241 removed: user copy restored"; }
            else { row.action = Action.RESTORE; row.why = "unchanged " + match.name + " square that 241 removed: user copy restored" + note; }
            return row;
        }
        if (uncomparable != null) {
            if (inB) { row.action = Action.BASE_UNCOMPARABLE; row.why = "terrain equals " + uncomparable.name + " but its locs cannot be decrypted or probed: never written over BASE"; }
            else { row.action = Action.RESTORE; row.why = "BASE lacks it; user copy restored (terrain equals " + uncomparable.name + ", locs not comparable)"; }
            return row;
        }
        // custom (or a square GadScape added)
        boolean added = cands.isEmpty();
        String what = added ? "square GadScape added (not in 211" + (r225 != null ? "/225" : "") + ")"
                : "user changed " + describeChange(row) + " vs " + row.start;
        if (eqBase) { row.action = Action.BASE; row.why = what + ", but it equals BASE's square"; return row; }
        if (inB && !added && allTowardBase(row)) { row.action = Action.BASE; row.why = what + ", but every change is in BASE too (an import of newer Jagex content)"; return row; }
        boolean lostLocs = uL.ok() && uL.file().size() == 0 || uL.status().equals("none");
        if (lostLocs && start != null && start.locs.ok() && start.locs.file().size() > 0) {
            // custom terrain with an empty loc file: writing it would wipe the square's objects
            row.action = inB ? Action.BASE_UNCOMPARABLE : Action.RESTORE;
            row.why = what + ", but the user loc file is empty where " + start.name + " has " + start.locs.file().size() + " placements (keyless save?)" + (inB ? ": BASE kept" : ": restored as is");
            stat("user.custom-terrain-empty-locs");
            return row;
        }
        row.action = inB ? Action.OVERLAY : Action.OVERLAY_NEW;
        row.why = what + (inB ? "" : "; BASE has no such square");
        return row;
    }

    /** Adds a non-BASE square to the allow-list (with masks, vanilla placements and restore data as needed). */
    private void record(Row row) {
        int r = row.region;
        boolean written = row.action == Action.OVERLAY || row.action == Action.OVERLAY_NEW || row.action == Action.RESTORE;
        Cand st = lastStart;
        boolean merge = false;
        if (row.action == Action.OVERLAY && st != null && st.terrain != null && st.locs.ok() && lastUL != null && lastUL.ok()) {
            BitSet[] m = masks(lastUT, lastUL.file(), st.terrain, st.locs.file());
            if (m != null) { allow.masks.put(r, m); merge = true; }
        }
        long crc = lastUT == null ? 0 : AllowList.userCrc(lastUT, lastUL == null ? null : lastUL.bytes());
        allow.squares.put(r, new AllowList.Square(r, row.action, merge, row.start, crc, row.uLocs, row.known.replace('\t', ' '), row.why.replace('\t', ' ')));
        if (!written) return;
        // the loc file that will be written: the user's, or the start's for a restore whose user copy lost its locs
        LocFile written0 = lastUL != null && lastUL.ok() ? lastUL.file() : LocFile.EMPTY;
        if (restoreFromVanilla && st != null) {
            allow.restoreTerrain.put(r, st.terrain.clone());
            allow.restoreLocs.put(r, st.locs.file().encode());
            written0 = st.locs.file();
        }
        if (row.action == Action.RESTORE && lastLost && st != null && st.locs.ok() && st.locs.file().size() > 0) {
            allow.restoreLocs.put(r, st.locs.file().encode());
            written0 = st.locs.file();
            stat("restore.from-start-locs");
        }
        // placements of possible movers that are not the user's work keep their id
        List<LocFile> refs = new ArrayList<>();
        if (st != null && st.locs.ok()) refs.add(st.locs.file());
        if (row.inB) { LocFile b = Square241.locs(base, r); if (b != null) refs.add(b); }
        Env.Choice copy = copySources.get(r);
        if (copy != null && copy.why().contains("copy")) {
            NamedMaps.Locs src = u.locs(copy.source());
            if (src.ok()) refs.add(src.file());
            LocFile bsrc = Square241.locs(base, copy.source());
            if (bsrc != null) refs.add(bsrc);
        }
        TreeSet<Long> keep = new TreeSet<>();
        for (LocFile.Loc l : written0.locs) {
            if (!movers.contains(l.id())) continue;
            for (LocFile ref : refs) if (LocFile.contains(ref, l.id(), l.pos(), l.attr())) { keep.add((long) l.id() << 24 | (long) l.pos() << 8 | l.attr()); break; }
        }
        if (!keep.isEmpty()) allow.keep.put(r, keep);
    }

    /** Tiles whose terrain / placements differ between the user's square and the start (null if a terrain does not parse). */
    static BitSet[] masks(byte[] uT, LocFile uL, byte[] sT, LocFile sL) {
        int[] ou = Terrain.offsets(uT), os = Terrain.offsets(sT);
        if (ou == null || os == null) return null;
        BitSet t = new BitSet(16384), l = new BitSet(16384);
        for (int i = 0; i < 16384; i++) if (!Arrays.equals(uT, ou[i], ou[i + 1], sT, os[i], os[i + 1])) t.set(i);
        TreeMap<Integer, List<Long>> a = byTile(uL), b = byTile(sL);
        TreeSet<Integer> tiles = new TreeSet<>(a.keySet());
        tiles.addAll(b.keySet());
        for (int i : tiles) if (!a.getOrDefault(i, List.of()).equals(b.getOrDefault(i, List.of()))) l.set(i);
        return new BitSet[]{t, l};
    }

    /** Placements per tile (pos), each as a sorted list of id << 8 | attr. */
    static TreeMap<Integer, List<Long>> byTile(LocFile f) {
        TreeMap<Integer, List<Long>> m = new TreeMap<>();
        for (LocFile.Loc x : f.locs) m.computeIfAbsent(x.pos(), k -> new ArrayList<>()).add((long) x.id() << 8 | x.attr());
        for (List<Long> v : m.values()) v.sort(null);
        return m;
    }

    private static String describeChange(Row row) {
        boolean t = "x".equals(row.terrainVsStart), l = row.locsVsStart.startsWith("x");
        String s = (t ? "terrain (" + row.tilesVsStart + " tiles)" : "") + (t && l ? " and " : "")
                + (l ? "locs" + (row.locsAdded >= 0 ? " (+" + row.locsAdded + " -" + row.locsRemoved + ")" : " (tail probe)") : "");
        return s.isEmpty() ? "?" : s;
    }

    private void fillStart(Row row, Cand c, byte[] uT, NamedMaps.Locs uL, int r) {
        row.start = c.name;
        row.startLocs = c.locs.status();
        boolean tEq = c.terrain != null && Arrays.equals(uT, c.terrain);
        row.terrainVsStart = c.terrain == null ? "-" : tEq ? "=" : "x";
        row.tilesVsStart = c.terrain == null ? -1 : tEq ? 0 : Terrain.tilesDiffering(uT, c.terrain);
        LocCmp lc = compareLocs(uL, c.locs, c.maps, r);
        row.locsVsStart = switch (lc) {
            case EQUAL -> c.locs.ok() ? "=" : "=t";
            case LOST -> "user-empty";
            case EMPTY_UNKNOWN -> "user-empty?";
            case DIFF -> c.locs.ok() ? "x" : "xt";
            case UNKNOWN -> "?";
        };
        if (uL.ok() && c.locs.ok()) {
            row.locsAdded = uL.file().countNotIn(c.locs.file());
            row.locsRemoved = c.locs.file().countNotIn(uL.file());
        }
    }

    private void fillBase(Row row, Cand start, byte[] uT, NamedMaps.Locs uL, int r) {
        byte[] bT = Square241.terrain(base, r);
        LocFile bL = Square241.locs(base, r);
        row.terrainVsBase = Arrays.equals(Terrain.to241(uT), bT) ? "=" : "x";
        row.tilesVsBase = Terrain.tilesDiffering(uT, bT);
        if (start != null && start.terrain != null && "x".equals(row.terrainVsStart)) row.tilesTowardBase = towardBase(uT, start.terrain, bT);
        if (uL.ok() && bL != null) {
            row.locsVsBase = Arrays.equals(uL.bytes(), Square241.locBytes(base, r)) ? "=" : uL.file().samePlacements(bL) ? "=p" : "x";
            if (start != null && start.locs.ok()) {
                long[] added = diff(uL.file(), start.locs.file()), removed = diff(start.locs.file(), uL.file());
                row.addedInBase = countIn(added, bL);
                row.removedInBase = removed.length - countIn(removed, bL);
            }
        } else if (uL.status().equals("none")) row.locsVsBase = bL == null || bL.size() == 0 ? "=" : "user-has-none";
    }

    /** Every tile and placement the user changed relative to the start is the same in BASE. */
    static boolean allTowardBase(Row row) {
        boolean tOk = "=".equals(row.terrainVsStart) || (row.tilesTowardBase >= 0 && row.tilesTowardBase == row.tilesVsStart);
        boolean lOk = row.locsVsStart.equals("=") || (row.locsAdded >= 0 && row.addedInBase == row.locsAdded && row.removedInBase == row.locsRemoved);
        return tOk && lOk;
    }

    /** Tiles where the user differs from the start and equals BASE (BASE's tile block). */
    static int towardBase(byte[] uT, byte[] sT, byte[] bT) {
        int[] ou = Terrain.offsets(uT), os = Terrain.offsets(sT), ob = Terrain.offsets(bT);
        if (ou == null || os == null || ob == null) return -1;
        int n = 0;
        for (int i = 0; i < 16384; i++) {
            if (Arrays.equals(uT, ou[i], ou[i + 1], sT, os[i], os[i + 1])) continue;
            if (Arrays.equals(uT, ou[i], ou[i + 1], bT, ob[i], ob[i + 1])) n++;
        }
        return n;
    }

    /** Placement keys of a not in b (multiset difference). */
    static long[] diff(LocFile a, LocFile b) {
        long[] x = a.keys(), y = b.keys();
        long[] out = new long[x.length];
        int i = 0, j = 0, n = 0;
        while (i < x.length) {
            if (j >= y.length || x[i] < y[j]) out[n++] = x[i++];
            else if (x[i] > y[j]) j++;
            else { i++; j++; }
        }
        return Arrays.copyOf(out, n);
    }

    static int countIn(long[] keys, LocFile f) {
        long[] y = f.keys();
        int n = 0, j = 0;
        for (long k : keys) {
            while (j < y.length && y[j] < k) j++;
            if (j < y.length && y[j] == k) { n++; j++; }
        }
        return n;
    }

    public static String header() { return Row.HEADER; }
}

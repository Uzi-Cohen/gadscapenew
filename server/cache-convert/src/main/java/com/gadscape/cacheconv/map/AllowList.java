package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.map.Classifier.Action;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * The stored result of the square classification (spec rule 9): what the {@code maps} step writes, as data in the
 * module so the build is reproducible without the classification evidence (rev-225 cache, public 211 keys):
 * <ul>
 *   <li>{@code map/squares.tsv}: every square that is not simply BASE's: OVERLAY / OVERLAY_NEW / RESTORE (written) and
 *       BASE_UNCOMPARABLE / NONE (reported), with the start it was compared with, the CRC of the user's square the
 *       decision was made on (the build refuses to use a decision for a user square that changed since), whether the
 *       square is merged with BASE (3-way, see {@link SquareMerge}) and why.</li>
 *   <li>{@code map/merge-masks.tsv}: per merged square the tiles whose terrain the user changed and the tiles whose
 *       placements the user changed, relative to the start, as index ranges (tile = level * 4096 + x * 64 + y).</li>
 *   <li>{@code map/vanilla-placements.tsv}: placements in written user squares that are not the user's work (they are
 *       in the start square, in BASE's square, or in the square a copy was made from) and whose loc id has a user
 *       definition that may be relocated (MAP-6): they keep BASE's id when the loc moves.</li>
 *   <li>{@code map/restore/REGION.locs} (and {@code .terrain}): loc files (terrain) of restored squares whose user copy
 *       lost them (taken from the start square / vanilla 211).</li>
 *   <li>{@code map/loc-225-imports.txt}: USER loc ids that are rev 225's own definitions (not GadScape customs).</li>
 * </ul>
 */
public final class AllowList {
    public record Square(int region, Action action, boolean merge, String start, long userCrc, String uLocs, String known, String why) {}

    public final TreeMap<Integer, Square> squares = new TreeMap<>();
    /** region -> {terrain mask, loc mask} (16384 bits each). */
    public final TreeMap<Integer, BitSet[]> masks = new TreeMap<>();
    /** region -> placement keys (LocFile.keys layout: id << 24 | pos << 8 | attr) that keep their id. */
    public final TreeMap<Integer, TreeSet<Long>> keep = new TreeMap<>();
    /** region -> loc file to restore with. */
    public final TreeMap<Integer, byte[]> restoreLocs = new TreeMap<>();
    /** region -> 211 terrain to restore with (squares whose user copy has no terrain; then the CRC is 0). */
    public final TreeMap<Integer, byte[]> restoreTerrain = new TreeMap<>();
    /** USER loc ids that are not in vanilla 211 but byte-identical to rev 225's: Jagex content DataMigration imported. */
    public final BitSet loc225 = new BitSet();

    public static final String RES = "/map/";

    public boolean writes(int region) {
        Square s = squares.get(region);
        return s != null && (s.action == Action.OVERLAY || s.action == Action.OVERLAY_NEW || s.action == Action.RESTORE);
    }

    /** CRC32 of a user square as the classification read it (211 terrain bytes, then the loc file bytes). */
    public static long userCrc(byte[] terrain211, byte[] locs) {
        CRC32 c = new CRC32();
        if (terrain211 != null) c.update(terrain211);
        c.update(new byte[]{(byte) 0xff, (byte) 0xfe});
        if (locs != null) c.update(locs);
        return c.getValue();
    }

    // ------------------------------------------------------------------ masks as text

    static String runs(BitSet b) {
        StringBuilder s = new StringBuilder();
        for (int i = b.nextSetBit(0); i >= 0; ) {
            int end = b.nextClearBit(i) - 1;
            if (s.length() > 0) s.append(',');
            s.append(i);
            if (end > i) s.append('-').append(end);
            i = b.nextSetBit(end + 1);
        }
        return s.length() == 0 ? "-" : s.toString();
    }

    static BitSet parseRuns(String s) {
        BitSet b = new BitSet(16384);
        if (s.equals("-") || s.isEmpty()) return b;
        for (String r : s.split(",")) {
            int d = r.indexOf('-');
            if (d < 0) b.set(Integer.parseInt(r));
            else b.set(Integer.parseInt(r.substring(0, d)), Integer.parseInt(r.substring(d + 1)) + 1);
        }
        return b;
    }

    // ------------------------------------------------------------------ persistence

    public void save(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("restore"));
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("squares.tsv"), StandardCharsets.UTF_8))) {
            w.println("# Map squares the maps step writes or reports (generated by: dev/run.sh map.MapsMain classify --maps.writeResources=DIR;");
            w.println("# evidence: USER, VAN211, BASE, OLD241, rev-225 cache OpenRS2 1943, public 211 keys). Columns: region, x, y, action,");
            w.println("# merge (1 = 3-way merge with BASE), start (what the user's square was compared with), userCrc (CRC32 of the user's");
            w.println("# 211 terrain + loc bytes when classified), user loc status, known packer provenance, why.");
            for (Square s : squares.values())
                w.println(s.region + "\t" + (s.region >> 8) + "\t" + (s.region & 255) + "\t" + s.action + "\t" + (s.merge ? 1 : 0) + "\t" + s.start + "\t"
                        + Long.toHexString(s.userCrc) + "\t" + s.uLocs + "\t" + s.known + "\t" + s.why);
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("merge-masks.tsv"), StandardCharsets.UTF_8))) {
            w.println("# Per merged square: tiles (level*4096 + x*64 + y) whose terrain / placements the user changed relative to the start.");
            w.println("# Columns: region, terrain tile ranges, placement tile ranges.");
            for (var e : masks.entrySet()) w.println(e.getKey() + "\t" + runs(e.getValue()[0]) + "\t" + runs(e.getValue()[1]));
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("vanilla-placements.tsv"), StandardCharsets.UTF_8))) {
            w.println("# Placements in written user squares that are not the user's work (also in the start square, in BASE's square, or in");
            w.println("# the square a copy was made from) and whose loc has a user definition BASE also defines: when the maps plan");
            w.println("# relocates that loc (MAP-6), these keep BASE's id. Columns: region, loc id, level, x, y, type, rotation.");
            for (var e : keep.entrySet())
                for (long k : e.getValue()) {
                    int id = (int) (k >>> 24), pos = (int) (k >> 8) & 0xffff, attr = (int) k & 255;
                    w.println(e.getKey() + "\t" + id + "\t" + (pos >> 12) + "\t" + (pos >> 6 & 63) + "\t" + (pos & 63) + "\t" + (attr >> 2) + "\t" + (attr & 3));
                }
        }
        Files.writeString(dir.resolve("loc-225-imports.txt"), "# USER loc definitions absent from vanilla 211 and byte-identical to rev 225's (OpenRS2 1943): Jagex objects\n"
                + "# GadScape's DataMigration imported, not GadScape customs. Ranges of loc ids.\n" + runs(loc225).replace(",", "\n") + "\n", StandardCharsets.UTF_8);
        // restore/: exactly the current set
        try (var s = Files.list(dir.resolve("restore"))) { for (Path p : s.toList()) Files.delete(p); }
        for (var e : restoreLocs.entrySet()) Files.write(dir.resolve("restore").resolve(e.getKey() + ".locs"), e.getValue());
        for (var e : restoreTerrain.entrySet()) Files.write(dir.resolve("restore").resolve(e.getKey() + ".terrain"), e.getValue());
    }

    /** Loads the stored allow-list from the classpath (resources map/...). */
    public static AllowList load() throws IOException {
        AllowList a = new AllowList();
        for (String[] f : rows("squares.tsv")) {
            int r = Integer.parseInt(f[0]);
            a.squares.put(r, new Square(r, Action.valueOf(f[3]), f[4].equals("1"), f[5], Long.parseLong(f[6], 16), f[7], f[8], f.length > 9 ? f[9] : ""));
        }
        for (String[] f : rows("merge-masks.tsv")) a.masks.put(Integer.parseInt(f[0]), new BitSet[]{parseRuns(f[1]), parseRuns(f[2])});
        for (String[] f : rows("vanilla-placements.tsv")) {
            int r = Integer.parseInt(f[0]), id = Integer.parseInt(f[1]);
            int pos = Integer.parseInt(f[2]) << 12 | Integer.parseInt(f[3]) << 6 | Integer.parseInt(f[4]);
            int attr = Integer.parseInt(f[5]) << 2 | Integer.parseInt(f[6]);
            a.keep.computeIfAbsent(r, k -> new TreeSet<>()).add((long) id << 24 | (long) pos << 8 | attr);
        }
        for (String[] f : rows("loc-225-imports.txt")) if (!f[0].equals("-")) a.loc225.or(parseRuns(f[0]));
        for (Square s : a.squares.values()) {
            try (InputStream in = AllowList.class.getResourceAsStream(RES + "restore/" + s.region + ".locs")) {
                if (in != null) a.restoreLocs.put(s.region, in.readAllBytes());
            }
            try (InputStream in = AllowList.class.getResourceAsStream(RES + "restore/" + s.region + ".terrain")) {
                if (in != null) a.restoreTerrain.put(s.region, in.readAllBytes());
            }
        }
        return a;
    }

    private static List<String[]> rows(String name) throws IOException {
        List<String[]> out = new ArrayList<>();
        try (InputStream in = AllowList.class.getResourceAsStream(RES + name)) {
            if (in == null) throw new IOException("resource " + RES + name + " missing (run MapsMain classify --maps.writeResources)");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                out.add(line.split("\t", -1));
            }
        }
        return out;
    }

    public Map<Action, Integer> counts() {
        TreeMap<Action, Integer> m = new TreeMap<>();
        for (Square s : squares.values()) m.merge(s.action, 1, Integer::sum);
        return m;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        in.transferTo(o);
        return o.toByteArray();
    }
}

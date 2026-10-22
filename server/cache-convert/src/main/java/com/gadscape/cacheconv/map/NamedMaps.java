package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Xteas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Map squares of a cache that stores them the 211 way (also 225): archive 5 groups named "m{x}_{y}" (terrain) and
 * "l{x}_{y}" (locs, XTEA-encrypted by Jagex, saved plain by the GadScape editor and packers).
 * <p>
 * Unlike {@link CacheView#groupIdByName}, this index keeps every group carrying a name: the GadScape cache has 84
 * duplicate square names left by the 225 port (DataMigration added 225's groups under 225's group ids). Duplicates are
 * resolved deterministically: the copy that decodes, with the most placements (locs) / most bytes (terrain), lowest
 * group id on a tie. {@link #duplicates} lists them for the report.
 */
public final class NamedMaps {
    public final CacheView cache;
    private final List<Xteas> keys;
    /** name hash -> group ids carrying it, ascending. */
    private final Map<Integer, List<Integer>> byName = new HashMap<>();
    private final TreeSet<Integer> squares = new TreeSet<>();

    /** Result of reading one square's locs. */
    public record Locs(byte[] bytes, LocFile file, int group, boolean encrypted, int candidates, String status) {
        public boolean ok() { return file != null; }
    }

    public NamedMaps(CacheView cache, List<Xteas> keys) {
        this.cache = cache;
        this.keys = keys == null ? List.of() : keys;
        for (int g : cache.groupIds(Arch.MAPS)) {
            int h = cache.groupName(Arch.MAPS, g);
            if (h == -1) continue;
            byName.computeIfAbsent(h, k -> new ArrayList<>()).add(g);
        }
        // squares: every x,y whose m or l name exists
        for (int x = 0; x < 256; x++)
            for (int y = 0; y < 256; y++)
                if (byName.containsKey(hash("m" + x + "_" + y)) || byName.containsKey(hash("l" + x + "_" + y))) squares.add(x << 8 | y);
    }

    private static int hash(String s) { return com.gadscape.cacheconv.core.Bytes.nameHash(s); }

    /** Every square (region id) with a terrain or loc group, ascending. */
    public TreeSet<Integer> squares() { return new TreeSet<>(squares); }

    public boolean has(int region) { return squares.contains(region); }
    public boolean hasTerrain(int region) { return byName.containsKey(hash(terrainName(region))); }
    public boolean hasLocs(int region) { return byName.containsKey(hash(locsName(region))); }

    public static String terrainName(int region) { return "m" + (region >> 8) + "_" + (region & 255); }
    public static String locsName(int region) { return "l" + (region >> 8) + "_" + (region & 255); }

    /** Group ids named like the square's terrain / locs (more than one = duplicate). */
    public List<Integer> terrainGroups(int region) { return byName.getOrDefault(hash(terrainName(region)), List.of()); }
    public List<Integer> locGroups(int region) { return byName.getOrDefault(hash(locsName(region)), List.of()); }

    /** Squares whose terrain or loc name is carried by more than one group. */
    public TreeSet<Integer> duplicates() {
        TreeSet<Integer> d = new TreeSet<>();
        for (int r : squares) if (terrainGroups(r).size() > 1 || locGroups(r).size() > 1) d.add(r);
        return d;
    }

    /** The square's 211 terrain (copied), or null: the valid copy with the most bytes when the name is duplicated. */
    public byte[] terrain(int region) {
        byte[] best = null;
        for (int g : terrainGroups(region)) {
            GroupData d;
            try { d = cache.group(Arch.MAPS, g); } catch (RuntimeException e) { continue; }
            if (d == null || d.fileCount() == 0) continue;
            byte[] t = d.dataAt(0);
            if (!Terrain.valid211(t)) continue;
            if (best == null || t.length > best.length) best = t;
        }
        return best == null ? null : best.clone();
    }

    /** Distinct keys known for a square (by name, then by region id), in the order of the key files given. */
    public List<int[]> keysFor(int region) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        for (Xteas x : keys) {
            for (int[] k : new int[][]{x.forName(locsName(region)), x.forSquare(region)}) {
                if (k == null || (k[0] | k[1] | k[2] | k[3]) == 0) continue;
                if (seen.add(k[0] + "," + k[1] + "," + k[2] + "," + k[3])) out.add(k);
            }
        }
        return out;
    }

    /**
     * The square's locs: every group carrying the name is tried plain first (editor/packer saves), then with each
     * known key; a candidate counts only if it parses as exactly one loc file. Among the candidates the one with the
     * most placements wins (lowest group id on a tie). Status: "plain", "key", "none" (no group), "unreadable".
     */
    public Locs locs(int region) {
        List<Integer> gs = locGroups(region);
        if (gs.isEmpty()) return new Locs(null, null, -1, false, 0, "none");
        List<int[]> ks = keysFor(region);
        Locs best = null;
        int decodable = 0;
        for (int g : gs) {
            Locs c = tryGroup(g, null);
            if (c == null) for (int[] k : ks) { c = tryGroup(g, k); if (c != null) break; }
            if (c == null) continue;
            decodable++;
            if (best == null || c.file.size() > best.file.size()) best = c;
        }
        if (best == null) return new Locs(null, null, gs.get(0), false, gs.size(), "unreadable");
        return new Locs(best.bytes, best.file, best.group, best.encrypted, gs.size(), best.encrypted ? "key" : "plain");
    }

    private Locs tryGroup(int g, int[] key) {
        if (!plausible(g, key)) return null;
        GroupData d;
        try { d = key == null ? cache.group(Arch.MAPS, g) : cache.group(Arch.MAPS, g, key); }
        catch (RuntimeException e) { return null; }
        if (d == null || d.fileCount() == 0) return null;
        byte[] b = d.dataAt(0);
        LocFile f = LocFile.tryParse(b);
        return f == null ? null : new Locs(b.clone(), f, g, key != null, 1, key == null ? "plain" : "key");
    }

    /** Largest decompressed map group accepted (a wrong key gives a random length, which must not be allocated). */
    static final int MAX_GROUP = 4 << 20;

    /**
     * Whether the container decrypts (with the key, or as stored) to a plausible header: known compression type and
     * a decompressed length in range. Containers that fail this are never handed to the decoder.
     */
    private boolean plausible(int g, int[] key) {
        if (!(cache instanceof com.gadscape.cacheconv.core.JagCache j)) return true;
        byte[] raw = j.rawContainer(Arch.MAPS, g);
        if (raw == null || raw.length < 5) return false;
        int type = raw[0] & 255, comp = com.gadscape.cacheconv.core.Bytes.i32(raw, 1);
        if (type > 2 || comp < 0 || comp > raw.length) return false;
        if (type == 0) return true;
        if (raw.length < 9) return false;
        byte[] c = raw.clone();
        if (key != null) mgi.tools.jagcached.Helper.decryptContainer(c, key);
        int ulen = com.gadscape.cacheconv.core.Bytes.i32(c, 5);
        return ulen > 0 && ulen <= MAX_GROUP;
    }

    /** Raw packed container of the square's first loc group (for the key-free tail probe), or null. */
    public byte[] rawLocContainer(int region) {
        List<Integer> gs = locGroups(region);
        if (gs.isEmpty() || !(cache instanceof com.gadscape.cacheconv.core.JagCache j)) return null;
        return j.rawContainer(Arch.MAPS, gs.get(0));
    }
}

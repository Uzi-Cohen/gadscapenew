package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Environment files (2-4: lighting/fog/sky, and two small water/flag files) for squares BASE does not have (audit
 * MAP-10: the old tools gave every such square Edgeville's, 12342's). Source per square:
 * <ol>
 *   <li>an explicit source from resource map/env-sources.tsv (copies of known squares: catacombs, Whisperer, GWD
 *       instances, Barrows copies), if BASE has it;</li>
 *   <li>else the nearest BASE square (Chebyshev rings 1..16 in region space, fixed order inside a ring: rows from
 *       south to north, columns west to east);</li>
 *   <li>else the BASE square with the smallest squared distance (ties: lowest region id).</li>
 * </ol>
 */
public final class Env {
    public record Choice(int source, String why) {}

    private final CacheView base;
    private final Set<Integer> baseSquares;
    private final TreeMap<Integer, Choice> overrides;

    public Env(CacheView base, Set<Integer> baseSquares) throws IOException {
        this.base = base; this.baseSquares = baseSquares;
        this.overrides = loadOverrides();
    }

    public static TreeMap<Integer, Choice> loadOverrides() throws IOException {
        TreeMap<Integer, Choice> m = new TreeMap<>();
        try (InputStream in = Env.class.getResourceAsStream("/map/env-sources.tsv")) {
            if (in == null) throw new IOException("resource map/env-sources.tsv missing");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                m.put(Integer.parseInt(f[0]), new Choice(Integer.parseInt(f[1]), f.length > 2 ? f[2] : "env-sources.tsv"));
            }
        }
        return m;
    }

    /** Candidate neighbours of a square in search order (rings 1..16, rows south to north, columns west to east). */
    public static List<Integer> neighbourOrder(int region) {
        int x = region >> 8, y = region & 255;
        List<Integer> out = new ArrayList<>();
        for (int d = 1; d <= 16; d++)
            for (int dy = -d; dy <= d; dy++)
                for (int dx = -d; dx <= d; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != d) continue;
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx > 255 || ny > 255) continue;
                    out.add(nx << 8 | ny);
                }
        return out;
    }

    /** The BASE square whose environment files a new square gets. */
    public Choice choose(int region) {
        Choice o = overrides.get(region);
        if (o != null && baseSquares.contains(o.source)) return o;
        for (int n : neighbourOrder(region))
            if (baseSquares.contains(n)) {
                int dx = (n >> 8) - (region >> 8), dy = (n & 255) - (region & 255);
                return new Choice(n, "nearest BASE square (" + dx + "," + dy + ")" + (o != null ? "; listed source " + o.source + " not in BASE" : ""));
            }
        int best = -1;
        long bd = Long.MAX_VALUE;
        for (int s : new java.util.TreeSet<>(baseSquares)) {
            long dx = (s >> 8) - (region >> 8), dy = (s & 255) - (region & 255), d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = s; }
        }
        return new Choice(best, "closest BASE square (no BASE square within 16)");
    }

    /** Adds the environment files (ids >= 2) of {@code source} in BASE to a group builder. */
    public void copyInto(GroupData.Builder b, int source) {
        GroupData g = base.group(Arch.MAPS, source);
        if (g == null) throw new IllegalStateException("BASE has no square " + source);
        for (int i = 0; i < g.fileCount(); i++) if (g.fileIdAt(i) >= 2) b.put(g.fileIdAt(i), g.dataAt(i).clone());
    }
}

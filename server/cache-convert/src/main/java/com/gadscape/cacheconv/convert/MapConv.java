package com.gadscape.cacheconv.convert;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Xteas;
import net.runelite.cache.definitions.loaders.LocationsLoader;

import java.util.Arrays;

/**
 * Map square helpers ported from rev241/tools/PortMaps and Overlay.maps. 211 caches store a square as two named groups
 * in archive 5 ("m{x}_{y}" terrain, "l{x}_{y}" locs, the latter XTEA-encrypted by Jagex but saved plain by the editor);
 * build 241 stores one group per square at its region id (x << 8 | y) with files 0 terrain, 1 locs, 2-4 lighting /
 * environment. 241 terrain has one trailing flags byte more.
 */
public final class MapConv {
    private MapConv() {}

    public static int region(int x, int y) { return x << 8 | y; }
    public static String terrainName(int x, int y) { return "m" + x + "_" + y; }
    public static String locsName(int x, int y) { return "l" + x + "_" + y; }

    /** 211 terrain -> 241 terrain (appends the flags byte, 0). */
    public static byte[] terrain241(byte[] terrain211) { return Arrays.copyOf(terrain211, terrain211.length + 1); }

    /**
     * Terrain bytes of a 211 square (file 0 of "m{x}_{y}"), or null.
     *
     * @deprecated {@link CacheView#groupIdByName} returns an arbitrary one of several groups carrying the same name (the
     * GadScape cache has 84 duplicated square names left by the 225 port). Use {@code com.gadscape.cacheconv.map.NamedMaps},
     * which picks the copy that decodes with the most data, lowest group id on a tie.
     */
    @Deprecated
    public static byte[] terrain211(CacheView c, int x, int y) {
        int g = c.groupIdByName(Arch.MAPS, terrainName(x, y));
        if (g < 0) return null;
        GroupData d = c.group(Arch.MAPS, g);
        return d == null || d.fileCount() == 0 ? null : d.dataAt(0).clone();
    }

    /**
     * Loc bytes of a 211 square: tries the group unencrypted first (editor-saved), then with the key; keeps whichever
     * decodes with RuneLite's LocationsLoader. Null if there is no such group or neither decodes.
     *
     * @deprecated picks an arbitrary group when the square name is duplicated (see {@link #terrain211}); use
     * {@code com.gadscape.cacheconv.map.NamedMaps}.
     */
    @Deprecated
    public static byte[] locs211(CacheView c, Xteas keys, int x, int y) {
        int g = c.groupIdByName(Arch.MAPS, locsName(x, y));
        if (g < 0) return null;
        int[] key = keys == null ? null : keys.forName(locsName(x, y));
        if (key == null && keys != null) key = keys.forSquare(region(x, y));
        byte[] plain = tryLocs(c, g, null, x, y);
        if (plain != null) return plain;
        return key == null ? null : tryLocs(c, g, key, x, y);
    }

    private static byte[] tryLocs(CacheView c, int g, int[] key, int x, int y) {
        try {
            GroupData d = key == null ? c.group(Arch.MAPS, g) : c.group(Arch.MAPS, g, key);
            if (d == null || d.fileCount() == 0) return null;
            byte[] b = d.dataAt(0);
            new LocationsLoader().load(x, y, b);
            return b.clone();
        } catch (Throwable e) {
            return null;
        }
    }
}

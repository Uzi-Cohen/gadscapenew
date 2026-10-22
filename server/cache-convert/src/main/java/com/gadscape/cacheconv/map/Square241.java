package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;

import java.util.TreeSet;

/**
 * Map squares of a build-241 cache: archive 5 group = region id (x << 8 | y), file 0 terrain (241 layout), file 1
 * locs, files 2-4 lighting/environment. Archive 5 also holds groups that are not squares (the world-area group 25287
 * with 37 files); {@link #squares} keeps only groups whose file 0 is a 241 terrain and whose file 1 is a loc file.
 */
public final class Square241 {
    private Square241() {}

    /** Region ids of the real squares of a 241 cache, ascending. */
    public static TreeSet<Integer> squares(CacheView c) {
        TreeSet<Integer> out = new TreeSet<>();
        for (int g : c.groupIds(Arch.MAPS)) if (isSquare(c, g)) out.add(g);
        return out;
    }

    /** Whether archive 5 group g is a map square (files 0 and 1, 2-5 files, terrain and loc layout). */
    public static boolean isSquare(CacheView c, int g) {
        int[] f = c.fileIds(Arch.MAPS, g);
        if (f.length < 2 || f.length > 5 || f[0] != 0 || f[1] != 1) return false;
        GroupData d = c.group(Arch.MAPS, g);
        return d != null && Terrain.valid241(d.dataRef(0)) && LocFile.tryParse(d.dataRef(1)) != null;
    }

    public static byte[] terrain(CacheView c, int region) { return c.fileRef(Arch.MAPS, region, 0); }
    public static byte[] locBytes(CacheView c, int region) { return c.fileRef(Arch.MAPS, region, 1); }
    public static LocFile locs(CacheView c, int region) { return LocFile.tryParse(locBytes(c, region)); }
}

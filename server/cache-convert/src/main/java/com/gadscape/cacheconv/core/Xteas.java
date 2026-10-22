package com.gadscape.cacheconv.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Map XTEA keys from an xteas.json: the GadScape/RuneLite dump format
 * {@code [{"archive":5,"group":1,"name_hash":..,"name":"l42_42","mapsquare":10794,"key":[a,b,c,d]}, ...]}
 * and the openrs2 format {@code [{"mapsquare":10794,"key":[...]}]} (also "region"/"keys" spellings).
 */
public final class Xteas {
    private final Map<Integer, int[]> bySquare = new HashMap<>();
    private final Map<String, int[]> byName = new HashMap<>();

    public static Xteas load(Path file) throws IOException {
        Xteas x = new Xteas();
        Object root = Json.read(file);
        List<Object> list = root instanceof List<?> ? Json.list(root) : List.copyOf(Json.obj(root).values());
        for (Object o : list) {
            Map<String, Object> e = Json.obj(o);
            Object k = e.containsKey("key") ? e.get("key") : e.get("keys");
            if (k == null) continue;
            List<Object> kl = Json.list(k);
            int[] key = {Json.i(kl.get(0)), Json.i(kl.get(1)), Json.i(kl.get(2)), Json.i(kl.get(3))};
            Object sq = e.containsKey("mapsquare") ? e.get("mapsquare") : e.get("region");
            Object name = e.get("name");
            if (sq != null) {
                int s = Json.i(sq);
                x.bySquare.put(s, key);
                if (name == null) x.byName.put("l" + (s >> 8) + "_" + (s & 255), key);
            }
            if (name != null) x.byName.put(name.toString(), key);
        }
        return x;
    }

    /** Key for a map square (region id = x << 8 | y), or null. */
    public int[] forSquare(int square) { return bySquare.get(square); }
    /** Key for a named map group ("l50_50"), or null. */
    public int[] forName(String name) { return byName.get(name); }
    public int size() { return Math.max(bySquare.size(), byName.size()); }
}

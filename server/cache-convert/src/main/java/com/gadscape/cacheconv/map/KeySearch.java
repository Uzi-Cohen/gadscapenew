package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds XTEA keys for encrypted loc groups no key file names, by trying a list of known-valid keys (OpenRS2
 * {@code keys/valid.json}, every key ever seen) on the first cipher block only: a gzip container decrypts to a
 * plausible length followed by the gzip magic 1f 8b 08, which a wrong key matches about once in 2^32 tries. Each hit
 * is then confirmed by decoding the whole group as a loc file. Evidence tool: the result is a key file in the OpenRS2
 * layout that {@link Evidence} reads with the other keys.
 */
public final class KeySearch {
    private KeySearch() {}

    private static final int DELTA = 0x9E3779B9;

    /** XTEA-decrypts one 8-byte block at off (in place on a copy) and returns the 8 plain bytes. */
    static byte[] decryptBlock(byte[] b, int off, int[] k) {
        int v0 = Bytes.i32(b, off), v1 = Bytes.i32(b, off + 4);
        int sum = DELTA * 32;
        for (int i = 0; i < 32; i++) {
            v1 -= (((v0 << 4) ^ (v0 >>> 5)) + v0) ^ (sum + k[(sum >>> 11) & 3]);
            sum -= DELTA;
            v0 -= (((v1 << 4) ^ (v1 >>> 5)) + v1) ^ (sum + k[sum & 3]);
        }
        byte[] o = new byte[8];
        Bytes.w32(o, 0, v0);
        Bytes.w32(o, 4, v1);
        return o;
    }

    /** Whether the key turns the container's first cipher block into a gzip header with a plausible length. */
    static boolean headerMatches(byte[] raw, int[] key) {
        if (raw == null || raw.length < 15 || (raw[0] & 255) != 2) return false;
        byte[] p = decryptBlock(raw, 5, key);
        int ulen = Bytes.i32(p, 0);
        return ulen > 0 && ulen <= NamedMaps.MAX_GROUP && (p[4] & 255) == 0x1f && (p[5] & 255) == 0x8b && (p[6] & 255) == 8;
    }

    /** Loads a JSON list of keys ([[a,b,c,d], ...] or OpenRS2 entries with "key"). */
    public static List<int[]> loadKeyList(Path file) throws IOException {
        List<int[]> out = new ArrayList<>();
        for (Object o : Json.list(Json.read(file))) {
            List<Object> k = o instanceof List<?> ? Json.list(o) : Json.list(Json.obj(o).get("key"));
            out.add(new int[]{Json.i(k.get(0)), Json.i(k.get(1)), Json.i(k.get(2)), Json.i(k.get(3))});
        }
        return out;
    }

    /**
     * Searches keys for the loc groups of the given squares in a named (211/225) cache. Returns OpenRS2-style entries
     * {"archive":5,"group":g,"name":"l{x}_{y}","mapsquare":r,"key":[...]} for every confirmed key, ascending.
     */
    public static List<Map<String, Object>> search(JagCache cache, NamedMaps maps, Iterable<Integer> squares, List<int[]> keys) {
        List<Map<String, Object>> found = new ArrayList<>();
        for (int r : squares) {
            for (int g : maps.locGroups(r)) {
                byte[] raw = cache.rawContainer(Arch.MAPS, g);
                for (int[] k : keys) {
                    if (!headerMatches(raw, k)) continue;
                    var d = cache.group(Arch.MAPS, g, k);
                    if (d == null || d.fileCount() == 0 || LocFile.tryParse(d.dataAt(0)) == null) continue;
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("archive", 5); e.put("group", g); e.put("name", NamedMaps.locsName(r)); e.put("mapsquare", r);
                    e.put("key", List.of(k[0], k[1], k[2], k[3]));
                    found.add(e);
                    break;
                }
            }
        }
        return found;
    }
}

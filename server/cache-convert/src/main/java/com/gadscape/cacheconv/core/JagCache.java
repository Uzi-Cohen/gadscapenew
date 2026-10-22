package com.gadscape.cacheconv.core;

import mgi.tools.jagcached.Helper;
import mgi.tools.jagcached.cache.Archive;
import mgi.tools.jagcached.cache.Cache;
import mgi.tools.jagcached.cache.File;
import mgi.tools.jagcached.cache.Group;
import mgi.tools.jagcached.cache.Index;

import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A cache directory opened read-only with mgi {@code Cache.openCache(dir, true)}. Group data is read straight from the
 * index (container -> optional XTEA -> decompress -> split into files using the reference table's file ids), so the mgi
 * Group objects are never loaded or mutated. Decoded groups are kept in a byte-bounded LRU of soft references.
 */
public final class JagCache implements CacheView, AutoCloseable {
    private final String label;
    public final Path dir;
    private Cache cache;
    private final long maxCachedBytes;
    private long cachedBytes;
    /** Decoded groups, least recently used first; values are soft so the GC can reclaim them under pressure. */
    private final LinkedHashMap<Long, Cached> lru = new LinkedHashMap<>(256, 0.75f, true);
    private record Cached(SoftReference<GroupData> ref, long size) {}
    private final Map<Integer, int[]> ids = new HashMap<>();
    private final Map<Integer, Map<Integer, Group>> refs = new HashMap<>();
    private final Map<Integer, Map<Integer, Integer>> byName = new HashMap<>();

    private JagCache(String label, Path dir, Cache cache, long maxCachedBytes) {
        this.label = label; this.dir = dir; this.cache = cache; this.maxCachedBytes = maxCachedBytes;
    }

    /** Opens with a decoded-group cache of 1/8 of the max heap (at most 512 MB). */
    public static JagCache open(String label, Path dir) { return open(label, dir, Math.min(512L << 20, Runtime.getRuntime().maxMemory() / 8)); }

    public static JagCache open(String label, Path dir, long maxCachedBytes) {
        if (!Files.isRegularFile(dir.resolve("main_file_cache.dat2")) || !Files.isRegularFile(dir.resolve("main_file_cache.idx255")))
            throw new IllegalArgumentException(label + ": no cache at " + dir);
        Cache c = Cache.openCache(dir.toString(), true);
        if (c.getArchives() == null) throw new IllegalStateException(label + ": could not open cache at " + dir);
        return new JagCache(label, dir, c, maxCachedBytes);
    }

    /** The underlying mgi cache (read-only). For decoders that want mgi types; do not write through it. */
    public Cache mgi() { return cache; }

    @Override public String label() { return label; }

    private Archive archive(int a) {
        if (cache == null) throw new IllegalStateException(label + " is closed");
        if (a < 0 || a >= cache.getArchiveCount()) return null;
        Archive ar = cache.getArchive(a);
        return ar != null && ar.isLoaded() ? ar : null;
    }

    @Override public boolean hasArchive(int a) { return archive(a) != null; }

    private Map<Integer, Group> refs(int a) {
        return refs.computeIfAbsent(a, k -> {
            Archive ar = archive(k);
            Map<Integer, Group> m = new HashMap<>();
            if (ar != null) for (Group g : ar.getGroups()) if (g != null) m.put(g.getID(), g);
            return m;
        });
    }

    @Override public int[] groupIds(int a) {
        return ids.computeIfAbsent(a, k -> refs(k).keySet().stream().mapToInt(Integer::intValue).sorted().toArray()).clone();
    }

    @Override public boolean hasGroup(int a, int g) { return refs(a).containsKey(g); }

    @Override public int groupName(int a, int g) { Group r = refs(a).get(g); return r == null ? -1 : r.getName(); }

    @Override public int groupIdByName(int a, String name) {
        Map<Integer, Integer> m = byName.computeIfAbsent(a, k -> {
            Map<Integer, Integer> r = new HashMap<>();
            for (Group g : refs(k).values()) if (g.getName() != -1) r.putIfAbsent(g.getName(), g.getID());
            return r;
        });
        Integer id = m.get(Bytes.nameHash(name));
        return id == null ? -1 : id;
    }

    @Override public boolean usesNames(int a) { Archive ar = archive(a); return ar != null && ar.usesNames(); }

    @Override public int[] fileIds(int a, int g) {
        Group r = refs(a).get(g);
        if (r == null) return new int[0];
        File[] fs = r.getFiles();
        int[] out = new int[fs.length];
        for (int i = 0; i < fs.length; i++) out[i] = fs[i].getID();
        return out;
    }

    @Override public boolean hasFile(int a, int g, int f) {
        Group r = refs(a).get(g);
        if (r == null) return false;
        File[] fs = r.getFiles();
        int lo = 0, hi = fs.length - 1;
        while (lo <= hi) { int mid = (lo + hi) >>> 1, v = fs[mid].getID(); if (v < f) lo = mid + 1; else if (v > f) hi = mid - 1; else return true; }
        return false;
    }

    /** Version of the group in the reference table (-1 if absent). */
    public int groupVersion(int a, int g) { Group r = refs(a).get(g); return r == null ? -1 : r.getVersion(); }
    /** CRC of the packed group in the reference table (compare groups across caches without decoding). */
    public int groupCrc(int a, int g) { Group r = refs(a).get(g); return r == null ? 0 : r.getCrc(); }

    @Override public GroupData group(int a, int g) {
        long key = ((long) a << 32) | (g & 0xffffffffL);
        Cached c = lru.get(key);
        if (c != null) {
            GroupData d = c.ref.get();
            if (d != null) return d;
            lru.remove(key); cachedBytes -= c.size;
        }
        GroupData d = read(a, g, null);
        if (d == null) return null;
        long size = d.totalBytes() + 64L * d.fileCount();
        if (size <= maxCachedBytes / 4) {
            lru.put(key, new Cached(new SoftReference<>(d), size)); cachedBytes += size;
            var it = lru.entrySet().iterator();
            while (cachedBytes > maxCachedBytes && it.hasNext()) {
                var e = it.next();
                if (e.getKey() == key) continue;
                cachedBytes -= e.getValue().size;
                it.remove();
            }
        }
        return d;
    }

    @Override public GroupData group(int a, int g, int[] xtea) {
        if (xtea == null || (xtea[0] | xtea[1] | xtea[2] | xtea[3]) == 0) return group(a, g);
        try { return read(a, g, xtea); } catch (RuntimeException e) { return null; }
    }

    /** Raw packed container of a group as stored (null if absent). */
    public byte[] rawContainer(int a, int g) {
        if (archive(a) == null) return null;
        Index ix = cache.getIndex(a);
        if (ix == null) return null;
        mgi.utilities.ByteBuffer raw = ix.get(g);
        return raw == null ? null : raw.getBuffer();
    }

    private GroupData read(int a, int g, int[] xtea) {
        Group r = refs(a).get(g);
        if (r == null) return null;
        byte[] c = rawContainer(a, g);
        if (c == null) return null;
        if (xtea != null) Helper.decryptContainer(c, xtea);
        byte[] d;
        try {
            d = Helper.decodeFilesContainer(c);
        } catch (RuntimeException e) {
            throw new IllegalStateException(label + ": archive " + a + " group " + g + " does not decode: " + e, e);
        }
        File[] fs = r.getFiles();
        int n = fs.length;
        int[] fids = new int[n], fnames = new int[n];
        for (int i = 0; i < n; i++) { fids[i] = fs[i].getID(); fnames[i] = fs[i].getName(); }
        return new GroupData(g, r.getName(), r.getVersion(), fids, fnames, split(d, n, label + " " + a + "/" + g));
    }

    /** Splits a decompressed group into its files (same layout as mgi Group.unpack: chunked length table at the end). */
    public static byte[][] split(byte[] d, int n, String what) {
        if (n == 0) return new byte[0][];
        if (n == 1) return new byte[][]{d};
        int chunks = d[d.length - 1] & 255;
        int table = d.length - 1 - chunks * n * 4;
        if (table < 0) throw new IllegalStateException(what + ": bad file table");
        int[] sizes = new int[n];
        int q = table;
        for (int ch = 0; ch < chunks; ch++) { int len = 0; for (int i = 0; i < n; i++) { len += Bytes.i32(d, q); q += 4; sizes[i] += len; } }
        byte[][] out = new byte[n][];
        for (int i = 0; i < n; i++) out[i] = new byte[sizes[i]];
        int[] written = new int[n];
        q = table;
        int read = 0;
        for (int ch = 0; ch < chunks; ch++) {
            int len = 0;
            for (int i = 0; i < n; i++) {
                len += Bytes.i32(d, q); q += 4;
                System.arraycopy(d, read, out[i], written[i], len);
                written[i] += len; read += len;
            }
        }
        return out;
    }

    /** Decoded reference table of an archive (null if absent). */
    public RefTable refTable(int a) {
        if (archive(a) == null) return null;
        mgi.utilities.ByteBuffer raw = cache.getMasterIndex().get(a);
        return raw == null ? null : RefTable.parse(Helper.decodeFITContainer(raw.getBuffer()));
    }

    @Override public void release(int a) {
        var it = lru.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if ((int) (e.getKey() >> 32) == a) { cachedBytes -= e.getValue().size; it.remove(); }
        }
    }

    public void releaseAll() { lru.clear(); cachedBytes = 0; }

    @Override public void close() {
        if (cache != null) { cache.close(); cache = null; }
        lru.clear(); refs.clear(); ids.clear(); byName.clear();
    }

    @Override public String toString() { return label + " (" + dir + ")"; }

    /** Helper for callers holding raw id arrays. */
    public static boolean contains(int[] sortedIds, int id) { return Arrays.binarySearch(sortedIds, id) >= 0; }
}

package com.gadscape.cacheconv.core;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * Snapshot of one group: its files (sorted by id) with their name hashes and decoded (decompressed, decrypted) bytes.
 * Instances handed out by a {@link CacheView} are shared: never change the arrays {@link #dataRef} returns;
 * {@link #data} returns a copy. Build modified groups with {@link #toBuilder()} / {@link Builder}.
 */
public final class GroupData {
    public final int id;
    /** Group name hash, -1 when the archive has no names. */
    public final int name;
    /** Group version as the reference table holds it (0 for groups that are not on disk yet). */
    public final int version;
    private final int[] fileIds;
    private final int[] fileNames;
    private final byte[][] data;

    public GroupData(int id, int name, int version, int[] fileIds, int[] fileNames, byte[][] data) {
        if (fileIds.length != fileNames.length || fileIds.length != data.length) throw new IllegalArgumentException("length mismatch");
        for (int i = 1; i < fileIds.length; i++) if (fileIds[i] <= fileIds[i - 1]) throw new IllegalArgumentException("file ids not ascending in group " + id);
        this.id = id; this.name = name; this.version = version;
        this.fileIds = fileIds; this.fileNames = fileNames; this.data = data;
    }

    /** One-file group (file 0, no file name). */
    public static GroupData single(int id, int name, byte[] data) {
        return new GroupData(id, name, 0, new int[]{0}, new int[]{-1}, new byte[][]{data});
    }

    public int fileCount() { return fileIds.length; }
    public int[] fileIds() { return fileIds.clone(); }
    public int fileIdAt(int index) { return fileIds[index]; }
    public int fileNameAt(int index) { return fileNames[index]; }
    public byte[] dataAt(int index) { return data[index]; }

    private int index(int fileId) { return Arrays.binarySearch(fileIds, fileId); }
    public boolean has(int fileId) { return index(fileId) >= 0; }
    /** Copy of the file's bytes, or null. */
    public byte[] data(int fileId) { int i = index(fileId); return i < 0 ? null : data[i].clone(); }
    /** The file's bytes without copying (read only!), or null. */
    public byte[] dataRef(int fileId) { int i = index(fileId); return i < 0 ? null : data[i]; }
    /** File name hash (-1 = none), or -1 if the file does not exist. */
    public int fileName(int fileId) { int i = index(fileId); return i < 0 ? -1 : fileNames[i]; }
    /** Bytes of the only/first file (single-file groups such as models, scripts, interfaces' files are separate). */
    public byte[] first() { return data.length == 0 ? null : data[0]; }
    public long totalBytes() { long n = 0; for (byte[] d : data) n += d.length; return n; }

    /** Same files with the same bytes (names and versions ignored). */
    public boolean sameContent(GroupData o) {
        if (o == null || o.fileIds.length != fileIds.length || !Arrays.equals(fileIds, o.fileIds)) return false;
        for (int i = 0; i < data.length; i++) if (!Arrays.equals(data[i], o.data[i])) return false;
        return true;
    }

    public Builder toBuilder() {
        Builder b = new Builder(id).name(name).version(version);
        for (int i = 0; i < fileIds.length; i++) b.put(fileIds[i], fileNames[i], data[i]);
        return b;
    }

    @Override public String toString() { return "group " + id + " (" + fileIds.length + " files, name " + name + ")"; }

    /** Mutable group under construction. Bytes passed in are not copied. */
    public static final class Builder {
        public int id;
        public int name = -1;
        public int version;
        public final TreeMap<Integer, Entry> files = new TreeMap<>();

        public record Entry(int name, byte[] data) {}

        public Builder(int id) { this.id = id; }
        public Builder name(int nameHash) { this.name = nameHash; return this; }
        public Builder name(String name) { this.name = Bytes.nameHash(name); return this; }
        public Builder version(int v) { this.version = v; return this; }
        public Builder put(int fileId, byte[] data) { Entry e = files.get(fileId); files.put(fileId, new Entry(e == null ? -1 : e.name, data)); return this; }
        public Builder put(int fileId, int nameHash, byte[] data) { files.put(fileId, new Entry(nameHash, data)); return this; }
        public Builder remove(int fileId) { files.remove(fileId); return this; }
        public byte[] get(int fileId) { Entry e = files.get(fileId); return e == null ? null : e.data; }

        public GroupData build() {
            int n = files.size(), i = 0;
            int[] ids = new int[n], names = new int[n]; byte[][] d = new byte[n][];
            for (Map.Entry<Integer, Entry> e : files.entrySet()) { ids[i] = e.getKey(); names[i] = e.getValue().name; d[i] = e.getValue().data; i++; }
            return new GroupData(id, name, version, ids, names, d);
        }
    }
}

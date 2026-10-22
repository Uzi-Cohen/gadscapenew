package com.gadscape.cacheconv.core;

/**
 * A decoded JS5 reference table (the archive index stored in idx255), parsed exactly as mgi Archive.load and the
 * client read it: ids, names (0x1), crcs, uncompressed crcs (0x8), whirlpool (0x2), sizes (0x4), versions, file counts,
 * file ids, file names. {@link #end} is where the table's data ends; anything after it is padding.
 */
public final class RefTable {
    public static final int FLAG_NAMES = 0x1, FLAG_DIGESTS = 0x2, FLAG_SIZES = 0x4, FLAG_UNCOMPRESSED_CRCS = 0x8;

    public int protocol, version, flags;
    public int[] groupIds, groupNames, crcs, uncompressedCrcs, compressedSizes, uncompressedSizes, versions;
    public int[][] fileIds, fileNames;
    /** Offset just after the last byte the table uses. */
    public int end;
    /** Total length of the decoded table (end < length means trailing bytes). */
    public int length;

    public boolean names() { return (flags & FLAG_NAMES) != 0; }
    public int groupCount() { return groupIds.length; }
    public long fileCount() { long n = 0; for (int[] f : fileIds) n += f.length; return n; }

    public static RefTable parse(byte[] t) {
        RefTable r = new RefTable();
        Buf in = new Buf(t);
        r.protocol = in.u8();
        if (r.protocol < 5 || r.protocol > 7) throw new IllegalStateException("reference table protocol " + r.protocol);
        boolean smart = r.protocol >= 7;
        if (r.protocol >= 6) r.version = in.i32();
        r.flags = in.u8();
        int n = smart ? in.smartInt() : in.u16();
        r.groupIds = new int[n];
        for (int i = 0, acc = 0; i < n; i++) r.groupIds[i] = acc += smart ? in.smartInt() : in.u16();
        r.groupNames = new int[n];
        for (int i = 0; i < n; i++) r.groupNames[i] = r.names() ? in.i32() : -1;
        r.crcs = new int[n];
        for (int i = 0; i < n; i++) r.crcs[i] = in.i32();
        r.uncompressedCrcs = new int[n];
        if ((r.flags & FLAG_UNCOMPRESSED_CRCS) != 0) for (int i = 0; i < n; i++) r.uncompressedCrcs[i] = in.i32();
        if ((r.flags & FLAG_DIGESTS) != 0) in.skip(64 * n);
        r.compressedSizes = new int[n]; r.uncompressedSizes = new int[n];
        if ((r.flags & FLAG_SIZES) != 0) for (int i = 0; i < n; i++) { r.compressedSizes[i] = in.i32(); r.uncompressedSizes[i] = in.i32(); }
        r.versions = new int[n];
        for (int i = 0; i < n; i++) r.versions[i] = in.i32();
        r.fileIds = new int[n][];
        for (int i = 0; i < n; i++) r.fileIds[i] = new int[smart ? in.smartInt() : in.u16()];
        for (int i = 0; i < n; i++) for (int j = 0, acc = 0; j < r.fileIds[i].length; j++) r.fileIds[i][j] = acc += smart ? in.smartInt() : in.u16();
        r.fileNames = new int[n][];
        for (int i = 0; i < n; i++) {
            r.fileNames[i] = new int[r.fileIds[i].length];
            for (int j = 0; j < r.fileIds[i].length; j++) r.fileNames[i][j] = r.names() ? in.i32() : -1;
        }
        r.end = in.p;
        r.length = t.length;
        if (r.end > r.length) throw new IllegalStateException("reference table runs past its end");
        return r;
    }
}

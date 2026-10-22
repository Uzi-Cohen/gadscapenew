package com.gadscape.cacheconv.map;

import java.util.zip.CRC32;

/**
 * Key-free comparison of an XTEA-encrypted loc group with candidate plain bytes (ported from audit/map Classify.tail).
 * XTEA encrypts whole 8-byte blocks of the container payload only, so the last (length - 5) % 8 bytes stay clear;
 * for a gzip container they end the gzip trailer (CRC32 and ISIZE of the plain bytes, little-endian).
 */
public final class TailProbe {
    private TailProbe() {}

    public enum Result {
        /** Five or more clear trailer bytes match (CRC byte(s) + full length). */
        SAME,
        /** The clear bytes contradict the candidate. */
        DIFF,
        /** Too few clear bytes, or not a gzip container: cannot tell. */
        UNKNOWN
    }

    /** Compares the raw (still encrypted) container with candidate plain loc bytes. */
    public static Result compare(byte[] raw, byte[] plain) {
        if (raw == null || plain == null || raw.length < 11) return Result.UNKNOWN;
        int comp = ((raw[1] & 255) << 24) | ((raw[2] & 255) << 16) | ((raw[3] & 255) << 8) | (raw[4] & 255);
        int encEnd = raw.length - 2; // container + 2-byte version trailer
        if (raw[0] != 2 || 9 + comp != encEnd) return Result.UNKNOWN; // only gzip, with a version trailer
        int blocks = (encEnd - 5) / 8, from = 5 + blocks * 8, k = encEnd - from;
        if (k == 0) return Result.UNKNOWN;
        CRC32 c = new CRC32();
        c.update(plain);
        long crc = c.getValue();
        int len = plain.length;
        byte[] trailer = new byte[8];
        for (int i = 0; i < 4; i++) { trailer[i] = (byte) (crc >>> (8 * i)); trailer[4 + i] = (byte) (len >>> (8 * i)); }
        for (int i = 0; i < k; i++) if (raw[from + i] != trailer[8 - k + i]) return Result.DIFF;
        return k >= 5 ? Result.SAME : Result.UNKNOWN;
    }
}

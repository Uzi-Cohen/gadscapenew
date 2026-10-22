package com.gadscape.cacheconv.core;

import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

/**
 * Sequential big-endian reader over a byte array, with the read and skip primitives the config walkers need.
 * Strings are Jagex cp1252-ish bytes terminated by 0; they are read as ISO-8859-1 so bytes round-trip exactly.
 */
public final class Buf {
    public final byte[] b;
    public int p;
    /** Optional hook told every opcode read through {@link #op()} (opcode census tools). */
    public IntConsumer census;

    public Buf(byte[] b) { this.b = b; }
    public Buf(byte[] b, int p) { this.b = b; this.p = p; }

    public int remaining() { return b.length - p; }
    public boolean eof() { return p >= b.length; }

    public int u8() { return b[p++] & 255; }
    public int s8() { return b[p++]; }
    /** Reads an opcode byte (tells {@link #census}). */
    public int op() { int o = u8(); if (census != null) census.accept(o); return o; }
    public int u16() { int v = Bytes.u16(b, p); p += 2; return v; }
    public int s16() { return (short) u16(); }
    public int u24() { int v = Bytes.u24(b, p); p += 3; return v; }
    public int i32() { int v = Bytes.i32(b, p); p += 4; return v; }
    public long i64() { long v = Bytes.i64(b, p); p += 8; return v; }

    /** 1 byte below 128, else 2 bytes minus 32768 (client readUSmart). */
    public int smart() { return (b[p] & 255) < 128 ? u8() : u16() - 32768; }
    /** 2 bytes (0..32766, 32767 = -1) or 4 bytes with the top bit set (client readNullableLargeSmart/readBigSmart). */
    public int bigSmart() { if (b[p] < 0) return i32() & 0x7fffffff; int v = u16(); return v == 32767 ? -1 : v; }
    /** Reference-table smart: 2 bytes, or 4 bytes with the top bit set. */
    public int smartInt() { return (b[p] & 0x80) == 0 ? u16() : i32() & 0x7fffffff; }

    public String str() {
        int s = p;
        while (b[p] != 0) p++;
        String r = new String(b, s, p - s, StandardCharsets.ISO_8859_1);
        p++;
        return r;
    }

    public void skip(int n) { p += n; }
    public void skipStr() { while (b[p] != 0) p++; p++; }
    public void skipBigSmart() { if (b[p] < 0) p += 4; else p += 2; }
    public void skipSmart() { if ((b[p] & 255) < 128) p += 1; else p += 2; }
    /** Skips a params block (opcode 249): count, then per entry a type byte (1 = string), 3-byte key, value (2 = long, 8 bytes). */
    public void skipParams() {
        int n = u8();
        for (int i = 0; i < n; i++) { int t = u8(); skip(3); if (t == 1) skipStr(); else if (t == 2) skip(8); else skip(4); }
    }

    public byte[] slice(int from, int to) { return java.util.Arrays.copyOfRange(b, from, to); }
}

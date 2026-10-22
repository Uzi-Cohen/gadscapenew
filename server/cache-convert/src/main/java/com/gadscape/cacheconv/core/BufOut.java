package com.gadscape.cacheconv.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Big-endian byte writer (the counterpart of {@link Buf}). */
public final class BufOut {
    private final ByteArrayOutputStream o;

    public BufOut() { o = new ByteArrayOutputStream(); }
    public BufOut(int capacity) { o = new ByteArrayOutputStream(capacity); }

    public BufOut u8(int v) { o.write(v); return this; }
    public BufOut u16(int v) { o.write(v >> 8); o.write(v); return this; }
    public BufOut u24(int v) { o.write(v >> 16); o.write(v >> 8); o.write(v); return this; }
    public BufOut i32(int v) { o.write(v >>> 24); o.write(v >> 16); o.write(v >> 8); o.write(v); return this; }
    public BufOut i64(long v) { i32((int) (v >>> 32)); i32((int) v); return this; }
    public BufOut raw(byte[] b) { o.write(b, 0, b.length); return this; }
    /** Copies b[from, to). */
    public BufOut raw(byte[] b, int from, int to) { o.write(b, from, to - from); return this; }
    public BufOut str(String s) { byte[] t = s.getBytes(StandardCharsets.ISO_8859_1); o.write(t, 0, t.length); o.write(0); return this; }
    public BufOut smart(int v) { if (v >= 0 && v < 128) u8(v); else u16(v + 32768); return this; }
    /** Client big smart: 2 bytes for 0..32766 (-1 as 32767), else 4 bytes with the top bit set. */
    public BufOut bigSmart(int v) { if (v == -1) u16(32767); else if (v >= 0 && v < 32767) u16(v); else i32(v | 0x80000000); return this; }
    public BufOut smartInt(int v) { if (v >= 0 && v <= 0x7fff) u16(v); else i32(0x80000000 | v); return this; }

    public int size() { return o.size(); }
    public byte[] done() { return o.toByteArray(); }
}

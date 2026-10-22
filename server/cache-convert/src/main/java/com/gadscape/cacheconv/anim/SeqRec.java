package com.gadscape.cacheconv.anim;

import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.BufOut;

import com.gadscape.cacheconv.core.Bytes;

import java.util.Arrays;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;

/**
 * A build-241 sequence record (config group 12), walked with the opcode layout the 241 client reads (rev 226+):
 * <pre>
 *  1  frames: u16 n, n x u16 length, n x u16 file, n x u16 group   (ref = group << 16 | file, archive 0)
 *  2  u16 frame step          3  u8 n, n bytes (merged bone groups)    4  stretches
 *  5  u8 priority             6/7 u16 left/right hand obj + 512 (0 = none)   8-11 u8
 * 12  chat/extra frames: u8 n, n x u16 file, n x u16 group
 * 13  i32 skeletal keyframe ref (group << 16 | file, archive 22)
 * 14  u16 n, n x (u16 frame, u16 sound, u8 weight, u8 loops, u8 location, u8 retain)
 * 15  u16 start, u16 end      16 i8 vertical offset   17 u8 n, n x u8 mask   18 string   19 flag
 * </pre>
 * {@link #parse} reads the references; {@link #rewrite} copies a record with its frame, keyframe and hand-obj references
 * mapped (frame placeholders with file 0xffff are left alone) and every other byte unchanged. Both throw {@link IllegalStateException} on an unknown opcode or a record that
 * does not end exactly at its terminating 0.
 */
public final class SeqRec {
    public final int id;
    /** op 1: frame refs (group << 16 | file) and lengths; empty if absent. */
    public int[] frames = new int[0], lengths = new int[0];
    /** op 12: extra (chat-head) frame refs. */
    public int[] extra = new int[0];
    /** op 13: skeletal keyframe ref (archive 22 group << 16 | file), -1 if none. */
    public int keyframe = -1;
    /** op 15 keyframe range; -1 if absent. */
    public int rangeStart = -1, rangeEnd = -1;
    /** op 6/7 obj ids (decoded: raw - 512); -1 if absent or 0. */
    public int leftHand = -1, rightHand = -1;

    /** The record bytes this was parsed from (not copied: read only). */
    public final byte[] bytes;
    /** Canonical opcode payloads (see {@link #fields()}). */
    private final TreeMap<Integer, String> fields = new TreeMap<>();

    private SeqRec(int id, byte[] bytes) { this.id = id; this.bytes = bytes; }

    /**
     * Opcode -> payload (hex) of every field except the references (op 1 keeps only the frame lengths, op 12 only its
     * count, op 13 is left out), with sound weights zeroed and without the fields a 211 record cannot carry (16
     * vertical offset, 18 debug name, 19). Two records with equal fields differ only in what they reference.
     */
    public TreeMap<Integer, String> fields() { return new TreeMap<>(fields); }

    public boolean skeletal() { return keyframe != -1; }

    /**
     * Whether a frame ref is the "no frame" placeholder: file 0xffff (Jagex writes it in op 12 chat-head frame lists,
     * e.g. seq 1854, with group 0 in 211 and arbitrary groups in 241). The client never loads such a frame.
     */
    public static boolean isNullRef(int ref) { return (ref & 0xffff) == 0xffff; }

    /** Every frame ref of op 1 and op 12, in record order (placeholders included). */
    public int[] allFrameRefs() { return IntStream.concat(Arrays.stream(frames), Arrays.stream(extra)).toArray(); }

    /** Frame refs of op 1 and op 12 that name a frame (placeholders left out), in record order. */
    public int[] realFrameRefs() { return Arrays.stream(allFrameRefs()).filter(r -> !isNullRef(r)).toArray(); }

    /** Distinct archive-0 group ids referenced by op 1 and op 12 (placeholders left out), ascending. */
    public int[] frameGroups() { return Arrays.stream(realFrameRefs()).map(r -> r >>> 16).distinct().sorted().toArray(); }

    /** Sum of the op-1 frame lengths (client ticks of 20 ms). */
    public int totalLength() { int n = 0; for (int l : lengths) n += l; return n; }

    public static SeqRec parse(int id, byte[] b) {
        SeqRec r = new SeqRec(id, b);
        walk(id, b, r, null, null, null, null);
        return r;
    }

    /**
     * Copy of a 241 record with frame refs (op 1, 12) mapped by {@code frame}, the keyframe ref (op 13) by
     * {@code keyframe} and the hand objs (op 6, 7: obj ids, not the +512 encoding) by {@code obj}. Any mapper may be
     * null (identity). Every other byte is copied unchanged.
     */
    public static byte[] rewrite(int id, byte[] b, IntUnaryOperator frame, IntUnaryOperator keyframe, IntUnaryOperator obj) {
        BufOut out = new BufOut(b.length);
        walk(id, b, null, out, frame, keyframe, obj);
        return out.done();
    }

    private static int map(IntUnaryOperator m, int v) { return m == null ? v : m.applyAsInt(v); }

    private static void walk(int id, byte[] b, SeqRec r, BufOut out, IntUnaryOperator fm, IntUnaryOperator km, IntUnaryOperator om) {
        Buf in = new Buf(b);
        while (true) {
            if (in.eof()) throw new IllegalStateException("sequence " + id + ": no terminating opcode 0");
            int start = in.p, op = in.u8();
            if (op == 0) {
                if (!in.eof()) throw new IllegalStateException("sequence " + id + ": " + in.remaining() + " bytes after opcode 0");
                if (out != null) out.u8(0);
                return;
            }
            switch (op) {
                case 1 -> {
                    int n = in.u16();
                    int[] len = new int[n], refs = new int[n];
                    for (int i = 0; i < n; i++) len[i] = in.u16();
                    for (int i = 0; i < n; i++) refs[i] = in.u16();
                    for (int i = 0; i < n; i++) refs[i] |= in.u16() << 16;
                    if (r != null) {
                        r.lengths = len; r.frames = refs;
                        StringBuilder sb = new StringBuilder();
                        for (int l : len) sb.append(l).append(',');
                        r.fields.put(1, sb.toString());
                    }
                    if (out != null) {
                        out.u8(1).u16(n);
                        int[] m = new int[n];
                        for (int i = 0; i < n; i++) m[i] = isNullRef(refs[i]) ? refs[i] : map(fm, refs[i]);
                        for (int l : len) out.u16(l);
                        for (int v : m) out.u16(v & 0xffff);
                        for (int v : m) out.u16(v >>> 16);
                    }
                    continue;
                }
                case 12 -> {
                    int n = in.u8();
                    int[] refs = new int[n];
                    for (int i = 0; i < n; i++) refs[i] = in.u16();
                    for (int i = 0; i < n; i++) refs[i] |= in.u16() << 16;
                    if (r != null) { r.extra = refs; r.fields.put(12, String.valueOf(n)); }
                    if (out != null) {
                        out.u8(12).u8(n);
                        int[] m = new int[n];
                        for (int i = 0; i < n; i++) m[i] = isNullRef(refs[i]) ? refs[i] : map(fm, refs[i]);
                        for (int v : m) out.u16(v & 0xffff);
                        for (int v : m) out.u16(v >>> 16);
                    }
                    continue;
                }
                case 13 -> {
                    int k = in.i32();
                    if (r != null) r.keyframe = k;
                    if (out != null) out.u8(13).i32(k == -1 ? -1 : map(km, k));
                    continue;
                }
                case 6, 7 -> {
                    int raw = in.u16();
                    int obj = raw > 0 ? raw - 512 : -1;
                    if (r != null) { if (op == 6) r.leftHand = obj; else r.rightHand = obj; r.fields.put(op, String.valueOf(raw)); }
                    if (out != null) {
                        int to = obj < 0 ? raw : map(om, obj) + 512;
                        if (to < 0 || to > 0xffff) throw new IllegalStateException("sequence " + id + ": hand obj " + obj + " -> " + (to - 512) + " does not fit op " + op);
                        out.u8(op).u16(to);
                    }
                    continue;
                }
                case 15 -> { int s = in.u16(), e = in.u16(); if (r != null) { r.rangeStart = s; r.rangeEnd = e; } }
                case 2 -> in.skip(2);
                case 3, 17 -> { int n = in.u8(); in.skip(n); }
                case 4, 19 -> { }
                case 5, 8, 9, 10, 11, 16 -> in.skip(1);
                case 14 -> {
                    int n = in.u16();
                    if (r != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < n; i++) { int q = in.p + i * 8; sb.append(Bytes.hex(b, q, q + 4)).append("00").append(Bytes.hex(b, q + 5, q + 8)).append(' '); }
                        r.fields.put(14, sb.toString());
                    }
                    in.skip(n * 8);
                }
                case 18 -> in.skipStr();
                default -> throw new IllegalStateException("sequence " + id + ": unknown 241 opcode " + op + " at " + start);
            }
            if (in.p > b.length) throw new IllegalStateException("sequence " + id + ": truncated at opcode " + op);
            if (r != null && op != 14 && op != 16 && op != 18 && op != 19) r.fields.put(op, Bytes.hex(b, start + 1, in.p));
            if (out != null) out.raw(b, start, in.p);
        }
    }
}

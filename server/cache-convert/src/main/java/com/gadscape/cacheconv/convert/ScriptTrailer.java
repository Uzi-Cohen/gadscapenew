package com.gadscape.cacheconv.convert;

import com.gadscape.cacheconv.core.Bytes;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Clientscript byte-level helpers ported from rev241/tools/PortIf and Overlay: the 211 -> 241 trailer change
 * (rev 237 added long locals and long args), gosub (op 40) target rewriting, adapter scripts, and the read-back check
 * that a 241 script's instruction count and end match its trailer.
 * <p>
 * 211 layout: name string, instructions, then trailer: ops(int) localInt localObj intArgs objArgs (u16 each), switch
 * tables, switch length (u16). 241 adds localLong after localObj and longArgs after objArgs (16-byte trailer).
 */
public final class ScriptTrailer {
    private ScriptTrailer() {}

    /** Scripts start with their name (a string, or a single 0 byte for none). Returns the offset after it. */
    public static int nameEnd(byte[] b) { int p = 0; while (b[p] != 0) p++; return p + 1; }

    /** 211 trailer (ops, localInt, localObj, intArgs, objArgs) -> 241 (ops, localInt, localObj, localLong, intArgs, objArgs, longArgs). */
    public static byte[] to241(byte[] b) {
        int switchLength = Bytes.u16(b, b.length - 2), end = b.length - 2 - switchLength - 12;
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length + 4);
        o.write(b, 0, end + 8);              // instructions + ops + localInt + localObj
        o.write(0); o.write(0);              // localLong
        o.write(b, end + 8, 4);              // intArgs + objArgs
        o.write(0); o.write(0);              // longArgs
        o.write(b, end + 12, b.length - (end + 12)); // switch tables + switch length
        return o.toByteArray();
    }

    /**
     * Walks a 211 script, rewrites gosub (op 40) targets found in {@code redirect} in place (same length) and returns
     * every gosub target it saw.
     */
    public static List<Integer> calls(byte[] b, Map<Integer, Integer> redirect) {
        int switchLength = Bytes.u16(b, b.length - 2), end = b.length - 2 - switchLength - 12, p = nameEnd(b);
        List<Integer> out = new ArrayList<>();
        while (p < end) {
            int op = Bytes.u16(b, p); p += 2;
            if (op == 3) { while (b[p] != 0) p++; p++; }
            else if (op < 100 && op != 21 && op != 38 && op != 39 && op != 63) {
                int v = Bytes.i32(b, p);
                if (op == 40) { out.add(v); Integer to = redirect.get(v); if (to != null) Bytes.w32(b, p, to); }
                p += 4;
            } else p++;
        }
        return out;
    }

    /**
     * Adapter for a vanilla proc that gained trailing int args in 241: takes the 211 args, pushes the extras, calls it,
     * drops its int returns. Written in the 241 layout.
     */
    public static byte[] adapter(int target, int oldIntArgs, int[] extraInts, int intReturns) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0); // no name
        int ops = 0;
        for (int i = 0; i < oldIntArgs; i++) { o.write(0); o.write(33); Bytes.w32(o, i); ops++; }       // iload i
        for (int v : extraInts) { o.write(0); o.write(0); Bytes.w32(o, v); ops++; }                     // iconst v
        o.write(0); o.write(40); Bytes.w32(o, target); ops++;                                           // gosub target
        for (int i = 0; i < intReturns; i++) { o.write(0); o.write(38); o.write(0); ops++; }          // pop_int
        o.write(0); o.write(21); o.write(0); ops++;                                                     // return
        Bytes.w32(o, ops); Bytes.w16(o, oldIntArgs); Bytes.w16(o, 0); Bytes.w16(o, 0); Bytes.w16(o, oldIntArgs); Bytes.w16(o, 0); Bytes.w16(o, 0); // 241 trailer
        o.write(0); Bytes.w16(o, 1);                                                                    // switch section: 0 tables (1 byte)
        return o.toByteArray();
    }

    /** {intArgs, objArgs} of a 211 script. */
    public static int[] args211(byte[] b) { int sw = Bytes.u16(b, b.length - 2), e = b.length - 2 - sw - 12; return new int[]{Bytes.u16(b, e + 8), Bytes.u16(b, e + 10)}; }
    /** {intArgs, objArgs} of a 241 script. */
    public static int[] args241(byte[] b) { int sw = Bytes.u16(b, b.length - 2), e = b.length - 2 - sw - 16; return new int[]{Bytes.u16(b, e + 10), Bytes.u16(b, e + 12)}; }
    /** {intArgs, objArgs, longArgs} of a 241 script. */
    public static int[] args241WithLongs(byte[] b) { int sw = Bytes.u16(b, b.length - 2), e = b.length - 2 - sw - 16; return new int[]{Bytes.u16(b, e + 10), Bytes.u16(b, e + 12), Bytes.u16(b, e + 14)}; }

    /**
     * Walks a 241 script the way the client does; the instruction count and the end must match the trailer exactly.
     * Throws IllegalStateException otherwise (the old Overlay.quietVerify / PortIf.verify241).
     */
    public static void verify241(int id, byte[] b) {
        int sw = Bytes.u16(b, b.length - 2), end = b.length - 2 - sw - 16, ops = Bytes.i32(b, end), p = nameEnd(b), n = 0;
        while (p < end) {
            int op = Bytes.u16(b, p); p += 2; n++;
            if (op == 3) { while (b[p] != 0) p++; p++; }
            else if (op == 61) p += 8;
            else if (op < 100 && op != 21 && op != 38 && op != 39 && op != 63) p += 4;
            else p++;
        }
        if (p != end || n != ops) throw new IllegalStateException("script " + id + " does not read back: " + n + "/" + ops + " instructions, end " + p + "/" + end);
    }

    /** {@link #verify241} as a boolean. */
    public static boolean reads241(int id, byte[] b) {
        try { verify241(id, b); return true; } catch (RuntimeException e) { return false; }
    }
}

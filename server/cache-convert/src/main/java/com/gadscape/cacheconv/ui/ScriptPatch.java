package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.convert.ScriptTrailer;
import com.gadscape.cacheconv.core.Bytes;

/**
 * Byte-level access to the int operands of a build-211 script, so ids can be rewritten in place before the 211 -> 241
 * conversion (every rewrite keeps the 4-byte width: no jump or switch offset changes).
 */
public final class ScriptPatch {
    private ScriptPatch() {}

    /** Offset of each instruction's 4-byte int operand, or -1 (string, 1-byte operand). Indexes match Cs2.parse211. */
    public static int[] operandOffsets211(byte[] b) {
        int sw = Bytes.u16(b, b.length - 2), end = b.length - 2 - sw - 12, ops = Bytes.i32(b, end);
        int[] off = new int[ops];
        int p = ScriptTrailer.nameEnd(b), n = 0;
        while (p < end) {
            int op = Bytes.u16(b, p); p += 2;
            if (n >= ops) throw new IllegalStateException("more instructions than the trailer says");
            if (op == 3) { off[n] = -1; while (b[p] != 0) p++; p++; }
            else if (op < 100 && op != 21 && op != 38 && op != 39) { off[n] = p; p += 4; }
            else { off[n] = -1; p++; }
            n++;
        }
        if (n != ops || p != end) throw new IllegalStateException("script does not walk: " + n + "/" + ops + " instructions, end " + p + "/" + end);
        return off;
    }

    /** Writes an int operand. */
    public static void set(byte[] b, int[] off, int index, int value) {
        if (off[index] < 0) throw new IllegalArgumentException("instruction " + index + " has no int operand");
        Bytes.w32(b, off[index], value);
    }

    public static int get(byte[] b, int[] off, int index) { return Bytes.i32(b, off[index]); }
}

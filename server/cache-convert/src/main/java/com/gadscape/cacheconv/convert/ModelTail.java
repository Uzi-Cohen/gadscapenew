package com.gadscape.cacheconv.convert;

/**
 * Face-depth tail of 241 models, ported from rev241/tools/ModelTail and MExtra. Build 241 models end their data with
 * a face-depth-offset flag (0 = none, 1 = one byte per face follows), and FD models have a 1-byte field before it
 * (0, or non-zero + 10 bytes). Models made before that lack these; {@link #fix} adds them (as "none") right before the
 * header.
 */
public final class ModelTail {
    private ModelTail() {}

    private static int u16(byte[] b, int p) { return ((b[p] & 255) << 8) | (b[p + 1] & 255); }

    /** {dataEnd, headerStart, faceCount} of an FF FE / FF FD model, or null for other encodings. */
    public static int[] layout(byte[] b) {
        int L = b.length;
        if (b[L-2] == -1 && b[L-1] == -2) { int h = L - 23; int vc = u16(b,h), fc = u16(b,h+2), tc = b[h+4]&255, tex = b[h+5]&255, prio = b[h+6]&255, alpha = b[h+7]&255, trans = b[h+8]&255;
            int x = u16(b,h+11), y = u16(b,h+13), z = u16(b,h+15), fi = u16(b,h+17), vg = u16(b,h+19);
            int d = vc + fc + (prio == 255 ? fc : 0) + (trans == 1 ? fc : 0) + (tex == 1 ? fc : 0) + vg + (alpha == 1 ? fc : 0) + fi + fc * 2 + tc * 6 + x + y + z; return new int[]{d, h, fc}; }
        if (b[L-2] == -1 && b[L-1] == -3) { int h = L - 26; int vc = u16(b,h), fc = u16(b,h+2), tc = b[h+4]&255, f12 = b[h+5]&255, prio = b[h+6]&255, alpha = b[h+7]&255, trans = b[h+8]&255, hasTex = b[h+9]&255;
            int x = u16(b,h+12), y = u16(b,h+14), z = u16(b,h+16), fi = u16(b,h+18), tcoord = u16(b,h+20), vg = u16(b,h+22);
            int c0 = 0, c13 = 0, c2 = 0; for (int i = 0; i < tc; i++) { int t = b[i]; if (t == 0) c0++; if (t >= 1 && t <= 3) c13++; if (t == 2) c2++; }
            int d = tc + vc + (f12 == 1 ? fc : 0) + fc + (prio == 255 ? fc : 0) + (trans == 1 ? fc : 0) + vg + (alpha == 1 ? fc : 0) + fi + (hasTex == 1 ? fc * 2 : 0) + tcoord + fc * 2 + x + y + z
                  + c0 * 6 + c13 * 6 + c13 * 6 + c13 * 2 + c13 + c13 * 2 + c2 * 2; return new int[]{d, h, fc}; }
        return null;
    }

    /** Classifies a model's tail; {@code insert[0]} receives how many zero bytes {@link #fix} would insert. */
    public static String classify(byte[] b, int[] insert) {
        int[] l = layout(b); if (l == null) return "other encoding";
        int d = l[0], h = l[1], fc = l[2], rem = h - d; boolean fe = b[b.length - 1] == -2;
        if (rem < 0) return "BAD (data longer than header says)";
        if (fe) {
            if (rem == 0) { insert[0] = 1; return "FE needs flag"; }
            if (rem == 1 && b[d] == 0) return "FE ok (flag 0)";
            if (rem == 1 + fc && b[d] == 1) return "FE ok (flag 1)";
            return "FE UNKNOWN rem " + (rem > fc + 1 ? ">" : ""); }
        if (rem == 0) { insert[0] = 2; return "FD needs field+flag"; }
        int base = b[d] == 0 ? 1 : 11;
        if (rem == base) { insert[0] = 1; return "FD needs flag"; }
        if (rem == base + 1 && b[d + base] == 0) return "FD ok (flag 0)";
        if (rem == base + 1 + fc && b[d + base] == 1) return "FD ok (flag 1)";
        return "FD UNKNOWN"; }

    /** The model with its missing tail bytes inserted (zeros, right before the header), or null if nothing is needed. */
    public static byte[] fix(int id, byte[] b) {
        int[] ins = {0};
        classify(b, ins);
        if (ins[0] == 0) return null;
        int h = layout(b)[1];
        byte[] nb = new byte[b.length + ins[0]];
        System.arraycopy(b, 0, nb, 0, h); System.arraycopy(b, h, nb, h + ins[0], b.length - h);   // inserted bytes stay 0
        int[] chk = {0}; String k2 = classify(nb, chk);
        if (!k2.contains("ok")) throw new IllegalStateException("model " + id + " still " + k2);
        return nb;
    }
}

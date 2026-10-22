package com.gadscape.cacheconv.convert;

import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.GroupData;

/**
 * Interface components 211 -> 241. Build 241 (rev 237 layout) reads type-6 (model) component model ids as ints;
 * everything else in a component is byte-compatible. Ported from rev241/tools/Conv.component/if1 and PortIf
 * (what UiOverlay used to put GadScape's interfaces into cacheU).
 */
public final class InterfaceConv {
    private InterfaceConv() {}

    /** One component file. IF3 (first byte 255): widen the model id at offset 19 of type-6 components; IF1: see {@link #if1}. */
    public static byte[] component(byte[] b) {
        if ((b[0] & 255) != 255) return if1(b);
        if (b[1] != 6) return b.clone();
        int model = ((b[19] & 255) << 8) | (b[20] & 255);
        byte[] n = new byte[b.length + 2];
        System.arraycopy(b, 0, n, 0, 19);
        int v = model == 65535 ? -1 : model;
        n[19] = (byte) (v >> 24); n[20] = (byte) (v >> 16); n[21] = (byte) (v >> 8); n[22] = (byte) v;
        System.arraycopy(b, 21, n, 23, b.length - 21);
        return n;
    }

    /** IF1 component: walk to the type-6 model ids (the only fields 241 reads differently) and widen both to ints. */
    public static byte[] if1(byte[] b) {
        Buf in = new Buf(b);
        int type = in.u8(); in.u8(); in.skip(15);
        int n = in.u8(); in.skip(n * 3);
        n = in.u8(); for (int i = 0; i < n; i++) { int c = in.u16(); in.skip(c * 2); }
        if (type != 6) return b.clone();
        int p = in.p, m = in.u16(), alt = in.u16();
        BufOut out = new BufOut(b.length + 4); out.raw(b, 0, p);
        out.i32(m == 65535 ? -1 : m); out.i32(alt == 65535 ? -1 : alt);
        out.raw(b, in.p, b.length);
        return out.done();
    }

    /** Whether a component file is IF3 (first byte 255). */
    public static boolean isIf3(byte[] b) { return b.length > 0 && (b[0] & 255) == 255; }

    /** Converts every component of a 211 interface group (file ids and names kept). */
    public static GroupData group(GroupData g211) {
        GroupData.Builder b = new GroupData.Builder(g211.id).name(g211.name).version(g211.version);
        for (int i = 0; i < g211.fileCount(); i++) b.put(g211.fileIdAt(i), g211.fileNameAt(i), component(g211.dataAt(i)));
        return b.build();
    }
}

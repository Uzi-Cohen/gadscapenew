package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.convert.ScriptTrailer;
import com.gadscape.cacheconv.core.Bytes;

import java.io.ByteArrayOutputStream;

/**
 * Adapter scripts (build-241 layout) that let GadScape's 211-era callers use a vanilla proc whose signature changed
 * ({@link UiData.SigRule}). A rule without an argument map gives exactly {@link ScriptTrailer#adapter} (the adapters
 * the hand-built cacheU carried); a mapped rule loads each 241 argument from the adapter's own int/object arguments or
 * pushes a constant, so an argument 241 inserted in the middle of the list works too.
 */
public final class UiAdapters {
    private UiAdapters() {}

    private static final int ICONST = 0, GOSUB = 40, RETURN = 21, ILOAD = 33, OLOAD = 35, POP_INT = 38;

    /** The adapter bytes for a rule (241 layout, read-back checked). */
    public static byte[] build(UiData.SigRule r) {
        byte[] b = r.mapped() ? mapped(r) : ScriptTrailer.adapter(r.target(), r.int211(), r.extraInts(), r.dropIntReturns());
        ScriptTrailer.verify241(r.target(), b);
        return b;
    }

    private static void op4(ByteArrayOutputStream o, int op, int v) { o.write(op >> 8); o.write(op); Bytes.w32(o, v); }
    private static void op1(ByteArrayOutputStream o, int op, int v) { o.write(op >> 8); o.write(op); o.write(v); }

    private static byte[] mapped(UiData.SigRule r) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0);                                                            // no name
        int ops = 0;
        for (String e : r.intMap()) {
            if (e.startsWith("i")) op4(o, ILOAD, Integer.parseInt(e.substring(1)));
            else op4(o, ICONST, Integer.parseInt(e));
            ops++;
        }
        for (String e : r.objMap()) { op4(o, OLOAD, Integer.parseInt(e.substring(1))); ops++; }
        op4(o, GOSUB, r.target()); ops++;
        for (int i = 0; i < r.dropIntReturns(); i++) { op1(o, POP_INT, 0); ops++; }
        op1(o, RETURN, 0); ops++;
        // 241 trailer: ops, localInt, localObj, localLong, intArgs, objArgs, longArgs; then no switch tables
        Bytes.w32(o, ops);
        Bytes.w16(o, r.int211()); Bytes.w16(o, r.obj211()); Bytes.w16(o, 0);
        Bytes.w16(o, r.int211()); Bytes.w16(o, r.obj211()); Bytes.w16(o, 0);
        o.write(0); Bytes.w16(o, 1);
        return o.toByteArray();
    }
}

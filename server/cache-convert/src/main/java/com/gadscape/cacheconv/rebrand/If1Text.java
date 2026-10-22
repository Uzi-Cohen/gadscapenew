package com.gadscape.cacheconv.rebrand;

import com.gadscape.cacheconv.core.Buf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The strings of an IF1 (old-format) component, walked the way the client decodes it (RuneLite InterfaceLoader
 * {@code decodeIf1}): header, value comparators, cs1 scripts, then the type's fields. Text (type 4: text and active text;
 * type 8: text) and the menu strings (target verb, spell name, tooltip) are rewritten for types 0, 1, 3, 4, 5 and 8;
 * other types (inventory 2, model 6, item list 7) are not walked: {@link #rewrite} returns null for them. Strings are
 * cp1252 bytes held one char per byte.
 */
public final class If1Text {
    private If1Text() {}

    /**
     * The component with {@code f} applied to each of its strings, or null when the type is not walked; the same array
     * when nothing changed. {@code changes} collects (before, after) pairs.
     */
    public static byte[] rewrite(byte[] b, UnaryOperator<String> f, List<String[]> changes) {
        Buf in = new Buf(b);
        int type = in.u8(), menu = in.u8();
        if (type == 2 || type == 6 || type == 7 || type > 8) return null;
        in.skip(15);
        int n = in.u8();
        in.skip(n * 3);
        n = in.u8();
        for (int i = 0; i < n; i++) { int c = in.u16(); in.skip(c * 2); }
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length + 16);
        o.write(b, 0, in.p);
        int from = in.p;
        if (type == 0) in.skip(3);
        if (type == 1) in.skip(3);
        if (type == 3) in.skip(1);
        if (type == 4 || type == 1) in.skip(6);
        copy(o, b, from, in.p);
        if (type == 4) { str(in, o, f, changes); str(in, o, f, changes); }
        from = in.p;
        if (type == 1 || type == 3 || type == 4) in.skip(4);
        if (type == 3 || type == 4) in.skip(12);
        if (type == 5) in.skip(8);
        copy(o, b, from, in.p);
        if (type == 8) str(in, o, f, changes);
        if (menu == 2) { str(in, o, f, changes); str(in, o, f, changes); copy(o, b, in.p, in.p + 2); in.skip(2); }
        if (menu == 1 || menu == 4 || menu == 5 || menu == 6) str(in, o, f, changes);
        if (in.p != b.length) {
            // trailing bytes the walk does not know: keep them, but only if they are the zero padding some packers leave
            for (int i = in.p; i < b.length; i++) if (b[i] != 0) return null;
            copy(o, b, in.p, b.length);
        }
        byte[] r = o.toByteArray();
        return changes.isEmpty() ? b : r;
    }

    private static void copy(ByteArrayOutputStream o, byte[] b, int from, int to) { o.write(b, from, to - from); }

    private static void str(Buf in, ByteArrayOutputStream o, UnaryOperator<String> f, List<String[]> changes) {
        String s = in.str();
        String r = f.apply(s);
        if (!r.equals(s)) changes.add(new String[]{s, r});
        byte[] w = r.getBytes(StandardCharsets.ISO_8859_1);
        o.write(w, 0, w.length);
        o.write(0);
    }
}

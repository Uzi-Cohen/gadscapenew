package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.Buf;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the references of one build-241 interface component (IF3, first byte 255, or IF1), walking exactly what the
 * 241 client reads; a component that does not end exactly at its last byte is an error ({@link IllegalStateException}),
 * except the client quirk of IF3 model components with exactly one of width/height mode set ({@link Comp#quirk}).
 * Independent of the converters' interface code.
 * <ul>
 *   <li>IF3: type 5 graphic, type 6 model and seq, type 4 font, the parent, the 18 hooks (script id, int / string
 *       arguments) and the var/inv/stat triggers;</li>
 *   <li>IF1: type 5 graphics, type 6 models and seqs, type 1/4/7 fonts, type 2 slot graphics, the parent.</li>
 * </ul>
 */
public final class IfWalk {
    private IfWalk() {}

    public static final String[] HOOKS = {"onLoad", "onMouseOver", "onMouseLeave", "onTargetLeave", "onTargetEnter", "onVarTransmit",
            "onInvTransmit", "onStatTransmit", "onTimer", "onOp", "onMouseRepeat", "onClick", "onClickRepeat", "onRelease", "onHold",
            "onDrag", "onDragComplete", "onScrollWheel"};

    /** A hook: script id, and its arguments (Integer or String) after the script id. */
    public record Hook(String name, int script, List<Object> args) {
        public int ints() { return (int) args.stream().filter(a -> a instanceof Integer).count(); }
        public int strings() { return (int) args.stream().filter(a -> a instanceof String).count(); }
    }

    /** A parsed component. Ids are -1 when absent. */
    public static final class Comp {
        public boolean if3;
        /** IF3 model component with exactly one of width/height mode set that ends 2 bytes early (see {@link #parse}). */
        public boolean quirk;
        public int type, parent = -1;
        public final List<Integer> sprites = new ArrayList<>(), models = new ArrayList<>(), seqs = new ArrayList<>(), fonts = new ArrayList<>();
        public final List<Hook> hooks = new ArrayList<>();
        /** Every string in the component (text, name, ops, target verb), for the branding scan. */
        public final List<String> strings = new ArrayList<>();
    }

    public static Comp parse(byte[] b) {
        if (b.length == 0) throw new IllegalStateException("empty component");
        try {
            Comp c = (b[0] & 255) == 255 ? if3(b) : if1(b);
            return c;
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalStateException("truncated component (" + b.length + " bytes)");
        }
    }

    private static int u16v(Buf in) { int v = in.u16(); return v == 65535 ? -1 : v; }
    private static void add(List<Integer> l, int v) { if (v != -1) l.add(v); }

    private static Comp if3(byte[] b) {
        Buf in = new Buf(b);
        Comp c = new Comp();
        c.if3 = true;
        in.u8();
        c.type = in.u8();
        in.u16(); in.s16(); in.s16(); in.u16(); in.u16();
        int wm = in.s8(), hm = in.s8();
        in.s8(); in.s8();
        c.parent = u16v(in);
        in.u8();
        switch (c.type) {
            case 0 -> { in.u16(); in.u16(); in.u8(); }
            case 5 -> { add(c.sprites, in.i32()); in.u16(); in.u8(); in.u8(); in.u8(); in.i32(); in.u8(); in.u8(); }
            case 6 -> {
                add(c.models, in.i32());
                in.s16(); in.s16(); in.u16(); in.u16(); in.u16(); in.u16();
                add(c.seqs, u16v(in));
                in.u8(); in.u16();
                if (wm != 0) in.u16();
                if (hm != 0) in.u16();
            }
            case 4 -> { add(c.fonts, u16v(in)); c.strings.add(in.str()); in.u8(); in.u8(); in.u8(); in.u8(); in.i32(); }
            case 3 -> { in.i32(); in.u8(); in.u8(); }
            case 9 -> { in.u8(); in.i32(); in.u8(); }
            default -> { }
        }
        in.u24();
        c.strings.add(in.str());
        int n = in.u8();
        for (int i = 0; i < n; i++) c.strings.add(in.str());
        in.u8(); in.u8(); in.u8();
        c.strings.add(in.str());
        for (String h : HOOKS) {
            int count = in.u8();
            if (count == 0) continue;
            int script = -1;
            List<Object> args = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int kind = in.u8();
                Object v;
                if (kind == 0) v = in.i32();
                else if (kind == 1) { String s = in.str(); v = s; c.strings.add(s); }
                else throw new IllegalStateException("hook " + h + " argument kind " + kind);
                if (i == 0) {
                    if (!(v instanceof Integer id)) throw new IllegalStateException("hook " + h + " script id is a string");
                    script = id;
                } else args.add(v);
            }
            c.hooks.add(new Hook(h, script, args));
        }
        for (int t = 0; t < 3; t++) { int count = in.u8(); in.skip(count * 4); }
        // Jagex's tool writes both model size shorts when one of width/height mode is set, the client reads only the
        // one whose mode is set (the server's ComponentDefinitions notes the same): such components end 2 bytes early
        if (in.p == b.length - 2 && c.type == 6 && (wm != 0) != (hm != 0)) { c.quirk = true; return c; }
        if (in.p != b.length) throw new IllegalStateException((b.length - in.p) + " bytes after the IF3 component");
        return c;
    }

    private static Comp if1(byte[] b) {
        Buf in = new Buf(b);
        Comp c = new Comp();
        c.type = in.u8();
        int menuType = in.u8();
        in.u16(); in.s16(); in.s16(); in.u16(); in.u16(); in.u8();
        c.parent = u16v(in);
        in.u16();
        int n = in.u8();
        in.skip(n * 3);
        n = in.u8();
        for (int i = 0; i < n; i++) { int k = in.u16(); in.skip(k * 2); }
        int type = c.type;
        if (type == 0) { in.u16(); in.u8(); }
        if (type == 1) { in.u16(); in.u8(); }
        if (type == 2) {
            in.skip(4); in.u8(); in.u8();
            for (int i = 0; i < 20; i++) if (in.u8() == 1) { in.s16(); in.s16(); add(c.sprites, in.i32()); }
            for (int i = 0; i < 5; i++) c.strings.add(in.str());
        }
        if (type == 3) in.u8();
        if (type == 4 || type == 1) { in.u8(); in.u8(); in.u8(); add(c.fonts, u16v(in)); in.u8(); }
        if (type == 4) { c.strings.add(in.str()); c.strings.add(in.str()); }
        if (type == 1 || type == 3 || type == 4) in.i32();
        if (type == 3 || type == 4) { in.i32(); in.i32(); in.i32(); }
        if (type == 5) { add(c.sprites, in.i32()); add(c.sprites, in.i32()); }
        if (type == 6) {
            add(c.models, in.i32()); add(c.models, in.i32());
            add(c.seqs, u16v(in)); add(c.seqs, u16v(in));
            in.u16(); in.u16(); in.u16();
        }
        if (type == 7) {
            in.u8(); add(c.fonts, u16v(in)); in.u8(); in.i32(); in.s16(); in.s16(); in.u8();
            for (int i = 0; i < 5; i++) c.strings.add(in.str());
        }
        if (type == 8) c.strings.add(in.str());
        if (menuType == 2 || type == 2) { c.strings.add(in.str()); c.strings.add(in.str()); in.u16(); }
        if (menuType == 1 || menuType == 4 || menuType == 5 || menuType == 6) c.strings.add(in.str());
        if (in.p != b.length) throw new IllegalStateException((b.length - in.p) + " bytes after the IF1 component");
        return c;
    }
}

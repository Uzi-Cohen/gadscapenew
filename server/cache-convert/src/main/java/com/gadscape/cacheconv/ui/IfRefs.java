package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.Bytes;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds every id an interface component refers to, with its byte offset, so the converter can rewrite relocated ids
 * in place (all fields keep their width). Works on components in the build-241 layout (after
 * {@link com.gadscape.cacheconv.convert.InterfaceConv}: IF3 and IF1 type-6 model ids are ints); IF3 and IF1 as the
 * RuneLite InterfaceLoader reads them.
 * <ul>
 *   <li>IF3: type 5 graphic, type 6 model and seq, type 4 font, every hook's script id and int arguments, var/inv/stat
 *       transmit triggers;</li>
 *   <li>IF1: type 5 graphics, type 6 models and seqs, type 1/4/7 fonts, type 2 inventory slot graphics. Old-style
 *       component scripts (cs1) are reported, not rewritten.</li>
 * </ul>
 */
public final class IfRefs {
    private IfRefs() {}

    public enum Field { SPRITE, MODEL, SEQ, FONT, HOOK_SCRIPT, HOOK_INT, VAR_TRIGGER, INV_TRIGGER, STAT_TRIGGER, CS1 }

    /** Hook names in IF3 order. */
    public static final String[] HOOKS = {"onLoad", "onMouseOver", "onMouseLeave", "onTargetLeave", "onTargetEnter", "onVarTransmit",
            "onInvTransmit", "onStatTransmit", "onTimer", "onOp", "onMouseRepeat", "onClick", "onClickRepeat", "onRelease", "onHold",
            "onDrag", "onDragComplete", "onScrollWheel"};

    /**
     * One reference. {@code width} is 2 (u16, 65535 = -1) or 4. For hook fields, {@code hook} is the hook index,
     * {@code script} the hook's script id and {@code argIndex} the position of the int among the script's int args.
     */
    public record Ref(Field field, int offset, int width, int value, int hook, int script, int argIndex) {
        Ref(Field f, int offset, int width, int value) { this(f, offset, width, value, -1, -1, -1); }
    }

    /** Byte span of one IF3 hook: {@code start} is its count byte, {@code end} the offset after its last argument. */
    public record HookSpan(int hook, int start, int end) {}

    /** Parsed component: IF3 flag, type, references and hook spans. {@code end} must equal the length for a well-formed component. */
    public record Comp(boolean if3, int type, List<Ref> refs, List<HookSpan> hooks, int end) {}

    public static Comp parse(byte[] b) {
        return (b[0] & 255) == 255 ? if3(b) : if1(b);
    }

    private static int u16v(Buf in) { int v = in.u16(); return v == 65535 ? -1 : v; }

    private static void str(Buf in) { while (in.u8() != 0) { } }

    private static Comp if3(byte[] b) {
        Buf in = new Buf(b);
        List<Ref> refs = new ArrayList<>();
        in.u8();
        int type = in.u8();
        in.u16(); in.s16(); in.s16(); in.u16();
        in.u16();
        int wm = in.s8(), hm = in.s8();
        in.s8(); in.s8();
        in.u16(); in.u8();
        switch (type) {
            case 0 -> { in.u16(); in.u16(); in.u8(); }
            case 5 -> { refs.add(new Ref(Field.SPRITE, in.p, 4, in.i32())); in.u16(); in.u8(); in.u8(); in.u8(); in.i32(); in.u8(); in.u8(); }
            case 6 -> {
                refs.add(new Ref(Field.MODEL, in.p, 4, in.i32()));
                in.s16(); in.s16(); in.u16(); in.u16(); in.u16(); in.u16();
                int p = in.p; refs.add(new Ref(Field.SEQ, p, 2, u16v(in)));
                in.u8(); in.u16();
                if (wm != 0) in.u16();
                if (hm != 0) in.u16();
            }
            case 4 -> { int p = in.p; refs.add(new Ref(Field.FONT, p, 2, u16v(in))); str(in); in.u8(); in.u8(); in.u8(); in.u8(); in.i32(); }
            case 3 -> { in.i32(); in.u8(); in.u8(); }
            case 9 -> { in.u8(); in.i32(); in.u8(); }
            default -> { }
        }
        in.u24();
        str(in);
        int n = in.u8();
        for (int i = 0; i < n; i++) str(in);
        in.u8(); in.u8(); in.u8();
        str(in);
        List<HookSpan> spans = new ArrayList<>();
        for (int h = 0; h < HOOKS.length; h++) {
            int start = in.p;
            int count = in.u8();
            if (count == 0) continue;
            int script = -1, ints = 0;
            for (int i = 0; i < count; i++) {
                int kind = in.u8();
                if (kind == 0) {
                    int p = in.p, v = in.i32();
                    if (i == 0) { script = v; refs.add(new Ref(Field.HOOK_SCRIPT, p, 4, v, h, v, -1)); }
                    else refs.add(new Ref(Field.HOOK_INT, p, 4, v, h, script, ints++));
                } else if (kind == 1) {
                    str(in);
                } else throw new IllegalStateException("hook " + HOOKS[h] + " argument kind " + kind);
            }
            spans.add(new HookSpan(h, start, in.p));
        }
        Field[] trig = {Field.VAR_TRIGGER, Field.INV_TRIGGER, Field.STAT_TRIGGER};
        for (Field f : trig) {
            int count = in.u8();
            for (int i = 0; i < count; i++) refs.add(new Ref(f, in.p, 4, in.i32()));
        }
        return new Comp(true, type, refs, spans, in.p);
    }

    private static Comp if1(byte[] b) {
        Buf in = new Buf(b);
        List<Ref> refs = new ArrayList<>();
        int type = in.u8(), menuType = in.u8();
        in.u16(); in.s16(); in.s16(); in.u16(); in.u16(); in.u8(); in.u16(); in.u16();
        int n = in.u8();
        in.skip(n * 3);
        n = in.u8();
        if (n > 0) refs.add(new Ref(Field.CS1, in.p, 0, n));
        for (int i = 0; i < n; i++) { int c = in.u16(); in.skip(c * 2); }
        if (type == 0) { in.u16(); in.u8(); }
        if (type == 1) { in.u16(); in.u8(); }
        if (type == 2) {
            in.skip(4); in.u8(); in.u8();
            for (int i = 0; i < 20; i++) if (in.u8() == 1) { in.s16(); in.s16(); refs.add(new Ref(Field.SPRITE, in.p, 4, in.i32())); }
            for (int i = 0; i < 5; i++) str(in);
        }
        if (type == 3) in.u8();
        if (type == 4 || type == 1) { in.u8(); in.u8(); in.u8(); int p = in.p; refs.add(new Ref(Field.FONT, p, 2, u16v(in))); in.u8(); }
        if (type == 4) { str(in); str(in); }
        if (type == 1 || type == 3 || type == 4) in.i32();
        if (type == 3 || type == 4) { in.i32(); in.i32(); in.i32(); }
        if (type == 5) { refs.add(new Ref(Field.SPRITE, in.p, 4, in.i32())); refs.add(new Ref(Field.SPRITE, in.p, 4, in.i32())); }
        if (type == 6) {
            refs.add(new Ref(Field.MODEL, in.p, 4, in.i32())); refs.add(new Ref(Field.MODEL, in.p, 4, in.i32()));
            int p = in.p; refs.add(new Ref(Field.SEQ, p, 2, u16v(in)));
            p = in.p; refs.add(new Ref(Field.SEQ, p, 2, u16v(in)));
            in.u16(); in.u16(); in.u16();
        }
        if (type == 7) {
            in.u8(); int p = in.p; refs.add(new Ref(Field.FONT, p, 2, u16v(in))); in.u8(); in.i32(); in.s16(); in.s16(); in.u8();
            for (int i = 0; i < 5; i++) str(in);
        }
        if (type == 8) str(in);
        if (menuType == 2 || type == 2) { str(in); str(in); in.u16(); }
        if (menuType == 1 || menuType == 4 || menuType == 5 || menuType == 6) str(in);
        return new Comp(false, type, refs, List.of(), in.p);
    }

    /**
     * The component's header: {IF3 (1/0), type, content type, x, y, width, height, x/y/width/height modes (IF3; 0 for
     * IF1), parent (-1 = none)}. Two components with the same shape are the same element laid out the same way.
     */
    public static int[] shape(byte[] b) {
        Buf in = new Buf(b);
        if ((b[0] & 255) == 255) {
            in.u8();
            int type = in.u8(), content = in.u16(), x = in.s16(), y = in.s16(), w = in.u16(), h = in.u16();
            int wm = in.s8(), hm = in.s8(), xm = in.s8(), ym = in.s8(), parent = u16v(in);
            return new int[]{1, type, content, x, y, w, h, wm, hm, xm, ym, parent};
        }
        int type = in.u8();
        in.u8();
        int content = in.u16(), x = in.s16(), y = in.s16(), w = in.u16(), h = in.u16();
        in.u8();
        int parent = u16v(in);
        return new int[]{0, type, content, x, y, w, h, 0, 0, 0, 0, parent};
    }

    /** A copy of the component without the given hooks (their count byte becomes 0). */
    public static byte[] dropHooks(byte[] b, List<HookSpan> drop) {
        List<HookSpan> d = new ArrayList<>(drop);
        d.sort((x, y) -> Integer.compare(y.start(), x.start()));
        byte[] out = b;
        for (HookSpan h : d) {
            byte[] n = new byte[out.length - (h.end() - h.start() - 1)];
            System.arraycopy(out, 0, n, 0, h.start());
            n[h.start()] = 0;
            System.arraycopy(out, h.end(), n, h.start() + 1, out.length - h.end());
            out = n;
        }
        return out;
    }

    /** Writes a new value into a reference's field (u16 fields store -1 as 65535). */
    public static void patch(byte[] b, Ref r, int value) {
        if (r.width() == 4) Bytes.w32(b, r.offset(), value);
        else if (r.width() == 2) {
            if (value != -1 && (value < 0 || value > 65534)) throw new IllegalArgumentException(r.field() + " " + value + " does not fit a u16 field");
            Bytes.w16(b, r.offset(), value == -1 ? 65535 : value);
        } else throw new IllegalArgumentException("cannot patch " + r.field());
    }
}

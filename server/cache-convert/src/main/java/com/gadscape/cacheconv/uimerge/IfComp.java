package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.BufOut;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Field-level codec for one interface component in the build-241 layout (IF3 model ids as ints; 211 components are
 * read after {@link com.gadscape.cacheconv.convert.InterfaceConv#component}). IF3 components are decoded into an
 * ordered field map so two versions can be diffed and edits re-applied field by field; {@link #encode} writes the
 * exact bytes back (round trip checked over every component of BASE, USER and VAN211 by {@link UiMergeSelfCheck}).
 * IF1 components (first byte != 255) are kept as raw bytes.
 * <p>
 * Field names: type contentType x y width height widthMode heightMode xMode yMode parent hidden; type 0: scrollWidth
 * scrollHeight noClickThrough; type 5: sprite rotation tiling opacity border shadowColor flipV flipH; type 6: model
 * offX offY rotX rotZ rotY zoom anim orthogonal m6a [m6w] [m6h]; type 4: font text lineHeight xAlign yAlign shadow
 * color; type 3: color filled opacity; type 9: lineWidth color lineDir; then clickMask opBase ops dragDeadZone
 * dragDeadTime dragRender targetVerb, the 18 hooks by their names ({@link #HOOKS}; value: list of Integer/String or
 * null), varTriggers invTriggers statTriggers (list of Integer or null). Bytes after what the client reads (the type-6
 * width/height quirk) are kept in {@link #tail}.
 */
public final class IfComp {
    public static final String[] HOOKS = {"onLoad", "onMouseOver", "onMouseLeave", "onTargetLeave", "onTargetEnter", "onVarTransmit",
            "onInvTransmit", "onStatTransmit", "onTimer", "onOp", "onMouseRepeat", "onClick", "onClickRepeat", "onRelease", "onHold",
            "onDrag", "onDragComplete", "onScrollWheel"};
    public static final String[] TRIGGERS = {"varTriggers", "invTriggers", "statTriggers"};

    public final boolean if3;
    /** IF1: the raw component. */
    public final byte[] raw;
    /** IF3: the fields, in byte order. */
    public final LinkedHashMap<String, Object> f = new LinkedHashMap<>();
    public byte[] tail = new byte[0];

    private IfComp(boolean if3, byte[] raw) { this.if3 = if3; this.raw = raw; }

    public static IfComp decode(byte[] b) {
        if (b.length == 0 || (b[0] & 255) != 255) return new IfComp(false, b.clone());
        IfComp c = new IfComp(true, null);
        Buf in = new Buf(b);
        in.u8();
        int type = in.u8();
        c.put("type", type);
        c.put("contentType", in.u16());
        c.put("x", in.s16());
        c.put("y", in.s16());
        c.put("width", in.u16());
        c.put("height", in.u16());
        int wm = in.s8(), hm = in.s8();
        c.put("widthMode", wm);
        c.put("heightMode", hm);
        c.put("xMode", in.s8());
        c.put("yMode", in.s8());
        int parent = in.u16();
        c.put("parent", parent == 65535 ? -1 : parent);
        c.put("hidden", in.u8());
        switch (type) {
            case 0 -> { c.put("scrollWidth", in.u16()); c.put("scrollHeight", in.u16()); c.put("noClickThrough", in.u8()); }
            case 5 -> {
                c.put("sprite", in.i32()); c.put("rotation", in.u16()); c.put("tiling", in.u8()); c.put("opacity", in.u8());
                c.put("border", in.u8()); c.put("shadowColor", in.i32()); c.put("flipV", in.u8()); c.put("flipH", in.u8());
            }
            case 6 -> {
                c.put("model", in.i32()); c.put("offX", in.s16()); c.put("offY", in.s16()); c.put("rotX", in.u16()); c.put("rotZ", in.u16());
                c.put("rotY", in.u16()); c.put("zoom", in.u16());
                int anim = in.u16(); c.put("anim", anim == 65535 ? -1 : anim);
                c.put("orthogonal", in.u8()); c.put("m6a", in.u16());
                if (wm != 0) c.put("m6w", in.u16());
                if (hm != 0) c.put("m6h", in.u16());
            }
            case 4 -> {
                int font = in.u16(); c.put("font", font == 65535 ? -1 : font);
                c.put("text", in.str()); c.put("lineHeight", in.u8()); c.put("xAlign", in.u8()); c.put("yAlign", in.u8());
                c.put("shadow", in.u8()); c.put("color", in.i32());
            }
            case 3 -> { c.put("color", in.i32()); c.put("filled", in.u8()); c.put("opacity", in.u8()); }
            case 9 -> { c.put("lineWidth", in.u8()); c.put("color", in.i32()); c.put("lineDir", in.u8()); }
            default -> { }
        }
        c.put("clickMask", in.u24());
        c.put("opBase", in.str());
        int n = in.u8();
        List<String> ops = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ops.add(in.str());
        c.put("ops", ops);
        c.put("dragDeadZone", in.u8());
        c.put("dragDeadTime", in.u8());
        c.put("dragRender", in.u8());
        c.put("targetVerb", in.str());
        for (String h : HOOKS) {
            int count = in.u8();
            if (count == 0) { c.put(h, null); continue; }
            List<Object> args = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int kind = in.u8();
                if (kind == 0) args.add(in.i32());
                else if (kind == 1) args.add(in.str());
                else throw new IllegalStateException("hook " + h + " argument kind " + kind);
            }
            c.put(h, args);
        }
        for (String t : TRIGGERS) {
            int count = in.u8();
            if (count == 0) { c.put(t, null); continue; }
            List<Object> v = new ArrayList<>(count);
            for (int i = 0; i < count; i++) v.add(in.i32());
            c.put(t, v);
        }
        if (in.p > b.length) throw new IllegalStateException("component overruns its data");
        c.tail = Arrays.copyOfRange(b, in.p, b.length);
        return c;
    }

    private void put(String k, Object v) { f.put(k, v); }

    public int i(String k) { Object v = f.get(k); return v == null ? 0 : (Integer) v; }
    public String s(String k) { Object v = f.get(k); return v == null ? "" : (String) v; }
    @SuppressWarnings("unchecked") public List<Object> list(String k) { return (List<Object>) f.get(k); }
    public int type() { return if3 ? i("type") : raw[0] & 255; }
    public int parent() {
        if (if3) return i("parent");
        int p = ((raw[13] & 255) << 8) | (raw[14] & 255);   // IF1: type menuType content x y w h opacity parent
        return p == 65535 ? -1 : p;
    }

    public byte[] encode() {
        if (!if3) return raw.clone();
        BufOut o = new BufOut(256);
        int type = i("type"), wm = i("widthMode"), hm = i("heightMode");
        o.u8(255).u8(type).u16(i("contentType")).u16(i("x")).u16(i("y")).u16(i("width")).u16(i("height"))
                .u8(wm).u8(hm).u8(i("xMode")).u8(i("yMode"));
        int parent = i("parent");
        o.u16(parent == -1 ? 65535 : parent).u8(i("hidden"));
        switch (type) {
            case 0 -> o.u16(i("scrollWidth")).u16(i("scrollHeight")).u8(i("noClickThrough"));
            case 5 -> o.i32(i("sprite")).u16(i("rotation")).u8(i("tiling")).u8(i("opacity")).u8(i("border")).i32(i("shadowColor")).u8(i("flipV")).u8(i("flipH"));
            case 6 -> {
                int anim = i("anim");
                o.i32(i("model")).u16(i("offX")).u16(i("offY")).u16(i("rotX")).u16(i("rotZ")).u16(i("rotY")).u16(i("zoom"))
                        .u16(anim == -1 ? 65535 : anim).u8(i("orthogonal")).u16(i("m6a"));
                if (wm != 0) o.u16(i("m6w"));
                if (hm != 0) o.u16(i("m6h"));
            }
            case 4 -> { int font = i("font"); o.u16(font == -1 ? 65535 : font).str(s("text")).u8(i("lineHeight")).u8(i("xAlign")).u8(i("yAlign")).u8(i("shadow")).i32(i("color")); }
            case 3 -> o.i32(i("color")).u8(i("filled")).u8(i("opacity"));
            case 9 -> o.u8(i("lineWidth")).i32(i("color")).u8(i("lineDir"));
            default -> { }
        }
        o.u24(i("clickMask")).str(s("opBase"));
        List<Object> ops = list("ops");
        int n = ops == null ? 0 : ops.size();
        o.u8(n);
        for (int k = 0; k < n; k++) o.str((String) ops.get(k));
        o.u8(i("dragDeadZone")).u8(i("dragDeadTime")).u8(i("dragRender")).str(s("targetVerb"));
        for (String h : HOOKS) {
            List<Object> args = list(h);
            if (args == null || args.isEmpty()) { o.u8(0); continue; }
            o.u8(args.size());
            for (Object a : args) {
                if (a instanceof Integer v) o.u8(0).i32(v);
                else o.u8(1).str((String) a);
            }
        }
        for (String t : TRIGGERS) {
            List<Object> v = list(t);
            if (v == null || v.isEmpty()) { o.u8(0); continue; }
            o.u8(v.size());
            for (Object x : v) o.i32((Integer) x);
        }
        o.raw(tail);
        return o.done();
    }

    public IfComp copy() { return decode(encode()); }

    /** Field-by-field copy (lists copied), without encoding: values may be out of range while a merge works on them. */
    public IfComp dup() {
        if (!if3) return new IfComp(false, raw.clone());
        IfComp c = new IfComp(true, null);
        for (Map.Entry<String, Object> e : f.entrySet()) c.f.put(e.getKey(), e.getValue() instanceof List<?> l ? new ArrayList<>(l) : e.getValue());
        c.tail = tail.clone();
        return c;
    }

    /** Field names whose values differ (IF3 both; an IF1/IF3 mismatch or IF1 byte change returns ["*raw"]). */
    public static List<String> diff(IfComp a, IfComp b) {
        List<String> d = new ArrayList<>();
        if (!a.if3 || !b.if3) {
            if (a.if3 != b.if3 || !Arrays.equals(a.encode(), b.encode())) d.add("*raw");
            return d;
        }
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(a.f.keySet());
        keys.addAll(b.f.keySet());
        for (String k : keys) if (!Objects.equals(norm(a.f.get(k)), norm(b.f.get(k)))) d.add(k);
        if (!Arrays.equals(a.tail, b.tail)) d.add("*tail");
        return d;
    }

    private static Object norm(Object v) { return v instanceof List<?> l && l.isEmpty() ? null : v; }

    public static String show(Object v) {
        if (v == null) return "-";
        if (v instanceof String s) return '"' + s.replace("\"", "\\\"") + '"';
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int k = 0; k < l.size(); k++) { if (k > 0) sb.append(", "); sb.append(show(l.get(k))); }
            return sb.append(']').toString();
        }
        return String.valueOf(v);
    }

    @Override public String toString() {
        if (!if3) return "IF1 " + raw.length + "B type " + (raw[0] & 255);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : f.entrySet()) {
            Object v = e.getValue();
            if (v == null || (v instanceof List<?> l && l.isEmpty())) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(show(v));
        }
        if (tail.length > 0) sb.append(" tail=").append(tail.length).append('B');
        return sb.toString();
    }
}

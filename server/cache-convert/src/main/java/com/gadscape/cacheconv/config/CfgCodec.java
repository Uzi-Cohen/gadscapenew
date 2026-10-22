package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.Kind;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a build-241 config entry (archive 2) into keyed items and finds every id reference inside them, so entries
 * can be compared field by field, merged three ways and have their references rewritten without touching any other
 * byte. {@code encode(parse(b)) == b} for every entry of every walked group (checked by {@link ConfigSelfCheck}).
 * <p>
 * An {@link Entry} is the ordered list of its opcodes ({@link Item}). Plain opcodes are leaves holding their raw bytes
 * (opcode + payload). Container opcodes hold one child per element, keyed by what identifies the element:
 * <ul>
 *   <li>249 params (obj, npc, loc, struct, inv): child per param, key {@code p<param id>}, raw = type byte, u24 key,
 *       value;</li>
 *   <li>enum 5/6/7 maps: child per pair, key {@code k<key>}, raw = i32 key + value;</li>
 *   <li>dbrow 3 / dbtable 1 columns: child per column, key {@code c<column>}.</li>
 * </ul>
 * Leaf keys are the opcode, except opcodes that occur once per index (obj 43, sub-ops / conditional ops
 * 100-102/200-202/251-253: {@code op:index[:subop]}) and opcodes that set the same property (loc 1/5/6/7
 * {@code models}; npc 1/61 {@code models}, 60/62 {@code heads}; spotanim 1/3 {@code model}; identkit 2/5
 * {@code models}). Conditional ops key on their whole condition (they accumulate). A key that still repeats (Jagex
 * entries sometimes repeat an opcode; the client keeps the last value) is marked {@link Entry#duplicateKeys}. Zero
 * padding after the end opcode (left by some packers) is accepted and dropped on encode ({@link Entry#padding}).
 * <p>
 * References ({@link Ref}) carry the {@link Kind} they point at: models, seqs, objs (cert/placeholder/bought links,
 * count objs, inventory stock), npcs and locs (multi-loc/npc transforms), varbits/varps (transforms, conditional
 * ops, varbit base var), sprites (npc head icons, health bars), params (param keys), and typed values (enum keys and
 * values, param defaults and values, dbrow columns) through their script var type. Groups without a walker
 * (hitsplat, area, varp, varc, ...) are one opaque item without references.
 */
public final class CfgCodec {
    private CfgCodec() {}

    /** How a reference is stored. */
    public enum Enc {
        /** unsigned short, 65535 = none */ U16,
        /** int, -1 = none */ I32,
        /** u24 (param keys) */ U24,
        /** client nullable big smart: 2 bytes (32767 = none) or 4 bytes with the top bit set */ BIGSMART,
        /** seq hand obj: u16 obj + 512, 0 = none */ HAND,
        /** component: int interface << 16 | component, -1 = none (only the interface part is mapped) */ COMPONENT,
        /** protobuf-style varint (dbrow table id) */ VARINT
    }

    /** One id reference: byte offset inside the item's raw bytes. {@code value} is the decoded id (-1 = none). */
    public record Ref(int off, Enc enc, Kind kind, int value) {
        public boolean none() { return value < 0; }
    }

    /** One opcode (leaf) or one element of a container. */
    public static final class Item {
        public final String key;
        public final int op;
        /** Leaf / child: the raw bytes (leaf: opcode + payload; child: the element). Container: null. */
        public final byte[] raw;
        public final List<Ref> refs;
        /** Container elements in order (null for leaves). */
        public final List<Item> children;

        Item(String key, int op, byte[] raw, List<Ref> refs, List<Item> children) {
            this.key = key; this.op = op; this.raw = raw; this.refs = refs; this.children = children;
        }

        public static Item leaf(String key, int op, byte[] raw, List<Ref> refs) { return new Item(key, op, raw, refs, null); }
        public static Item container(String key, int op, List<Item> children) { return new Item(key, op, null, List.of(), children); }

        public boolean isContainer() { return children != null; }

        /** Content equality (key, op, bytes, children). */
        public boolean same(Item o) {
            if (o == null || op != o.op || !key.equals(o.key)) return false;
            if (isContainer() != o.isContainer()) return false;
            if (!isContainer()) return Arrays.equals(raw, o.raw);
            if (children.size() != o.children.size()) return false;
            for (int i = 0; i < children.size(); i++) if (!children.get(i).same(o.children.get(i))) return false;
            return true;
        }

        /** Content equality of a container ignoring element order. */
        public boolean sameIgnoringOrder(Item o) {
            if (!isContainer() || o == null || !o.isContainer()) return same(o);
            if (op != o.op || children.size() != o.children.size()) return false;
            Map<String, Item> m = new HashMap<>();
            for (Item c : o.children) m.put(c.key, c);
            for (Item c : children) if (!c.same(m.get(c.key))) return false;
            return true;
        }

        public Item withChildren(List<Item> ch) { return new Item(key, op, null, List.of(), ch); }

        @Override public String toString() { return key + (isContainer() ? "[" + children.size() + "]" : "=" + Bytes.hex(raw)); }
    }

    /** A parsed entry. */
    public static final class Entry {
        public final int group, id;
        public final List<Item> items;
        public final boolean opaque;
        /** Some key occurs more than once (the client applies the last one). */
        public boolean duplicateKeys;
        /** Zero bytes found after the end opcode (dropped by {@link #encode}). */
        public int padding;

        Entry(int group, int id, List<Item> items, boolean opaque) { this.group = group; this.id = id; this.items = items; this.opaque = opaque; }

        /** Items by key in first-occurrence order; a repeated key maps to its last occurrence (what the client keeps). */
        public LinkedHashMap<String, Item> map() {
            LinkedHashMap<String, Item> m = new LinkedHashMap<>();
            for (Item i : items) m.put(i.key, i);
            return m;
        }

        /** The last item with the key (the one the client keeps), or null. */
        public Item get(String key) { Item r = null; for (Item i : items) if (i.key.equals(key)) r = i; return r; }

        /** Every reference of the entry, children included (offsets are item-relative). */
        public List<Ref> allRefs() {
            List<Ref> r = new ArrayList<>();
            for (Item i : items) { r.addAll(i.refs); if (i.isContainer()) for (Item c : i.children) r.addAll(c.refs); }
            return r;
        }

        public byte[] encode() { return CfgCodec.encode(this); }
    }

    /** Type char of a param (-1 if unknown); used to type param values. */
    @FunctionalInterface
    public interface ParamTypes { int typeOf(int param); }

    public static final ParamTypes UNTYPED = p -> -1;

    // ------------------------------------------------------------------ script var types

    /** Base value encoding of a dbrow/dbtable cell type id (script var type id): 0 int, 1 long, 2 string. */
    public static int baseOfTypeId(int typeId) {
        if (typeId == 36) return 2;
        if (typeId == 35 || typeId == 49 || typeId == 56 || typeId == 71 || typeId == 110 || typeId == 115 || typeId == 116) return 1;
        return 0;
    }

    /** The type char of a script var type id (from RuneLite's table), or -1. */
    public static int charOfTypeId(int typeId) {
        net.runelite.cache.util.ScriptVarType t = net.runelite.cache.util.ScriptVarType.forId(typeId);
        return t == null ? -1 : (t.getKeyChar() & 0xff);
    }

    /**
     * The id kind an int value of this script var type char refers to, or null (plain ints, colours, coords, kinds
     * nothing relocates such as inv, idkit, synth, texture, font). {@code I} (component) is reported as
     * {@link Kind#INTERFACE} and stored with {@link Enc#COMPONENT}.
     */
    public static Kind kindOfType(int c) {
        switch (c) {
            case 'o': case 'O': return Kind.OBJ;
            case 'n': return Kind.NPC;
            case 'l': return Kind.LOC;
            case 'A': return Kind.SEQ;
            case 't': return Kind.SPOT;
            case 'J': return Kind.STRUCT;
            case 'g': return Kind.ENUM;
            case 'd': return Kind.SPRITE;
            case 'm': return Kind.MODEL;
            case 0xD0: return Kind.DBROW;   // 'Ð'
            case 'I': return Kind.INTERFACE; // component
            default: return null;
        }
    }

    private static Ref typed(int off, int typeChar, int value) {
        Kind k = kindOfType(typeChar);
        if (k == null) return null;
        if (k == Kind.INTERFACE) return new Ref(off, Enc.COMPONENT, k, value == -1 ? -1 : value >>> 16);
        return new Ref(off, Enc.I32, k, value);
    }

    // ------------------------------------------------------------------ parsing

    /** Whether the group has a walker (refs, field keys); other groups are a single opaque item. */
    public static boolean walked(int group) {
        return switch (group) {
            case Arch.CFG_OBJ, Arch.CFG_NPC, Arch.CFG_LOC, Arch.CFG_SPOT, Arch.CFG_IDENTKIT, Arch.CFG_ENUM, Arch.CFG_PARAM,
                 Arch.CFG_STRUCT, Arch.CFG_VARBIT, Arch.CFG_INV, Arch.CFG_UNDERLAY, Arch.CFG_OVERLAY, Arch.CFG_HEALTHBAR,
                 Arch.CFG_DBROW, Arch.CFG_DBTABLE -> true;
            default -> false;
        };
    }

    /** Parses a build-241 entry. Throws IllegalStateException on unknown opcodes, truncation or trailing bytes. */
    public static Entry parse(int group, int id, byte[] b, ParamTypes types) {
        if (!walked(group)) {
            Item it = Item.leaf("raw", -1, b, List.of());
            return new Entry(group, id, List.of(it), true);
        }
        String what = Arch.configName(group) + " " + id;
        try {
            P p = new P(group, id, b, types == null ? UNTYPED : types);
            if (group == Arch.CFG_ENUM) p.enumTypes();
            Entry e = new Entry(group, id, p.items, false);
            while (true) {
                int start = p.in.p;
                int op = p.in.op();
                if (op == 0) break;
                p.op(op, start);
            }
            if (p.in.p != b.length) {
                if (!Bytes.isZero(b, p.in.p, b.length)) throw new IllegalStateException(what + ": " + (b.length - p.in.p) + " bytes after the end opcode");
                e.padding = b.length - p.in.p; // zero padding some packers leave after the end opcode: the client ignores it
            }
            e.duplicateKeys = p.dupes;
            return e;
        } catch (ArrayIndexOutOfBoundsException | NegativeArraySizeException ex) {
            throw new IllegalStateException(what + ": truncated entry (" + b.length + " bytes)");
        }
    }

    /** Re-encodes an entry (items in order, containers with their counts, then the end opcode). */
    public static byte[] encode(Entry e) {
        if (e.opaque) return e.items.get(0).raw.clone();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (Item i : e.items) writeItem(o, e.group, i);
        if (!e.opaque) o.write(0);
        return o.toByteArray();
    }

    static void writeItem(ByteArrayOutputStream o, int group, Item i) {
        if (!i.isContainer()) { o.write(i.raw, 0, i.raw.length); return; }
        o.write(i.op);
        int n = i.children.size();
        if (group == Arch.CFG_ENUM) { o.write(n >> 8); o.write(n); }
        else if (group == Arch.CFG_DBROW && i.op == 3) { o.write(dbColumnCount(i)); }
        else if (group == Arch.CFG_DBTABLE && i.op == 1) { o.write(dbColumnCount(i)); }
        else { if (n > 255) throw new IllegalStateException("too many params (" + n + ")"); o.write(n); }
        for (Item c : i.children) o.write(c.raw, 0, c.raw.length);
        if ((group == Arch.CFG_DBROW && i.op == 3) || (group == Arch.CFG_DBTABLE && i.op == 1)) o.write(255);
    }

    /** dbrow/dbtable column containers keep the declared column count in the key ("cols<n>"). */
    private static int dbColumnCount(Item i) { return Integer.parseInt(i.key.substring(i.key.indexOf('/') + 1)); }

    /** Builds an entry from items (for merges). Opaque entries cannot be built this way. */
    public static Entry build(int group, int id, List<Item> items) { return new Entry(group, id, items, false); }

    /** Parser state for one entry. */
    private static final class P {
        final int group, id;
        final byte[] b;
        final Buf in;
        final ParamTypes types;
        final List<Item> items = new ArrayList<>();
        final Map<String, Integer> seen = new HashMap<>();
        boolean dupes;
        int keyType = -1, valType = -1;
        final List<Ref> refs = new ArrayList<>();

        P(int group, int id, byte[] b, ParamTypes types) { this.group = group; this.id = id; this.b = b; this.in = new Buf(b); this.types = types; }

        /** Repeated keys stay as they are (the client applies opcodes in order: the last one wins); flagged. */
        String unique(String key) {
            Integer n = seen.merge(key, 1, Integer::sum);
            if (n > 1) dupes = true;
            return key;
        }

        void leaf(String key, int op, int start) {
            List<Ref> rel = new ArrayList<>(refs.size());
            for (Ref r : refs) rel.add(new Ref(r.off - start, r.enc, r.kind, r.value));
            refs.clear();
            items.add(Item.leaf(unique(key), op, Arrays.copyOfRange(b, start, in.p), rel));
        }

        Item child(String key, int op, int start) {
            List<Ref> rel = new ArrayList<>(refs.size());
            for (Ref r : refs) rel.add(new Ref(r.off - start, r.enc, r.kind, r.value));
            refs.clear();
            return Item.leaf(key, op, Arrays.copyOfRange(b, start, in.p), rel);
        }

        // ---- reference readers (absolute offsets; made item-relative in leaf()/child())
        void u16(Kind k) { int p = in.p, v = in.u16(); refs.add(new Ref(p, Enc.U16, k, v == 65535 ? -1 : v)); }
        void i32(Kind k) { int p = in.p, v = in.i32(); refs.add(new Ref(p, Enc.I32, k, v)); }
        void bigSmart(Kind k) { int p = in.p, v = in.bigSmart(); refs.add(new Ref(p, Enc.BIGSMART, k, v)); }
        void u16list(Kind k, boolean withType) { int n = in.u8(); for (int i = 0; i < n; i++) { u16(k); if (withType) in.skip(1); } }
        void i32list(Kind k, boolean withType) { int n = in.u8(); for (int i = 0; i < n; i++) { i32(k); if (withType) in.skip(1); } }
        void typedInt(int typeChar) { int p = in.p, v = in.i32(); Ref r = typed(p, typeChar, v); if (r != null) refs.add(r); }

        /** Transform block (npc 106/118, loc 77/92): varbit, varp, [default], count, (count + 1) targets. */
        void transforms(int op, int withDefault, Kind target) {
            u16(Kind.VARBIT); u16(Kind.VARP);
            if (op == withDefault) u16(target);
            int n = in.u8();
            for (int i = 0; i <= n; i++) u16(target);
        }

        void params(int start) {
            int n = in.u8();
            List<Item> ch = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int s = in.p;
                int t = in.u8();
                int kp = in.p, key = in.u24();
                refs.add(new Ref(kp, Enc.U24, Kind.PARAM, key));
                if (t == 1) in.skipStr();
                else if (t == 2) in.skip(8);
                else typedInt(types.typeOf(key));
                ch.add(child("p" + key, 249, s));
            }
            items.add(Item.container(unique("params"), 249, ch));
        }

        /** Enum: key/value types are read first so the map entries can be typed. */
        void enumTypes() {
            Buf s = new Buf(b);
            while (s.p < b.length) {
                int op = s.u8();
                switch (op) {
                    case 0: return;
                    case 1: keyType = s.u8(); break;
                    case 2: valType = s.u8(); break;
                    case 3: s.skipStr(); break;
                    case 4: s.skip(4); break;
                    case 5: { int n = s.u16(); for (int i = 0; i < n; i++) { s.skip(4); s.skipStr(); } break; }
                    case 6: { int n = s.u16(); s.skip(n * 8); break; }
                    case 7: { int n = s.u16(); s.skip(n * 12); break; }
                    case 8: s.skip(8); break;
                    default: return; // reported by the main pass
                }
            }
        }

        void enumMap(int op, int start) {
            int n = in.u16();
            List<Item> ch = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int s = in.p;
                int kp = in.p, key = in.i32();
                Ref kr = typed(kp, keyType, key);
                if (kr != null) refs.add(kr);
                if (op == 5) in.skipStr();
                else if (op == 6) typedInt(valType);
                else in.skip(8);
                ch.add(child("k" + key, op, s));
            }
            items.add(Item.container(unique("map"), op, ch));
        }

        int smart() { return in.smart(); }

        /** dbrow (op 3) / dbtable (op 1) cell values of one column: count, then per field one value per type. */
        void dbValues(int[] typeIds) {
            int fields = smart();
            for (int f = 0; f < fields; f++) {
                for (int t : typeIds) {
                    int base = baseOfTypeId(t);
                    if (base == 2) in.skipStr();
                    else if (base == 1) in.skip(8);
                    else typedInt(charOfTypeId(t));
                }
            }
        }

        void dbColumns(int op, boolean table) {
            int count = in.u8();
            List<Item> ch = new ArrayList<>();
            while (true) {
                int s = in.p;
                int setting = in.u8();
                if (setting == 255) break;
                int col = table ? setting & 127 : setting;
                boolean values = !table || (setting & 128) != 0;
                int nt = in.u8();
                int[] types = new int[nt];
                for (int i = 0; i < nt; i++) types[i] = smart();
                if (values) dbValues(types);
                ch.add(child("c" + col, op, s));
            }
            items.add(Item.container(unique("cols/" + count), op, ch));
        }

        /**
         * Conditional op (obj 201/202, npc 252/253, loc 101/102): op index, [sub-op u16], varp, varbit, min, max, text.
         * Several conditions per op index accumulate, so the key holds the whole condition.
         */
        String condOp(int op, boolean sub) {
            int idx = in.u8();
            int so = sub ? in.u16() : -1;
            int vp = Bytes.u16(b, in.p), vb = Bytes.u16(b, in.p + 2);
            u16(Kind.VARP); u16(Kind.VARBIT);
            int min = in.i32(), max = in.i32();
            in.skipStr();
            return op + ":" + idx + (sub ? ":" + so : "") + ":" + vp + ":" + vb + ":" + min + ":" + max;
        }

        int varint() { int v = 0, bits = 0, r; do { r = in.u8(); v |= (r & 0x7f) << bits; bits += 7; } while (r > 127); return v; }

        void op(int op, int start) {
            switch (group) {
                case Arch.CFG_OBJ -> obj(op, start);
                case Arch.CFG_NPC -> npc(op, start);
                case Arch.CFG_LOC -> loc(op, start);
                case Arch.CFG_SPOT -> spot(op, start);
                case Arch.CFG_IDENTKIT -> kit(op, start);
                case Arch.CFG_ENUM -> enm(op, start);
                case Arch.CFG_PARAM -> param(op, start);
                case Arch.CFG_STRUCT -> { if (op == 249) params(start); else unknown(op, start); }
                case Arch.CFG_VARBIT -> { if (op == 1) { u16(Kind.VARP); in.skip(2); leaf("1", op, start); } else unknown(op, start); }
                case Arch.CFG_INV -> inv(op, start);
                case Arch.CFG_UNDERLAY -> { if (op == 1) { in.skip(3); leaf("1", op, start); } else unknown(op, start); }
                case Arch.CFG_OVERLAY -> overlay(op, start);
                case Arch.CFG_HEALTHBAR -> healthbar(op, start);
                case Arch.CFG_DBROW -> dbrow(op, start);
                case Arch.CFG_DBTABLE -> { if (op == 1) dbColumns(op, true); else unknown(op, start); }
                default -> unknown(op, start);
            }
        }

        void unknown(int op, int start) {
            throw new IllegalStateException(Arch.configName(group) + " " + id + ": unknown 241 opcode " + op + " at " + start);
        }

        void obj(int op, int start) {
            String key = String.valueOf(op);
            switch (op) {
                case 1, 23, 24, 25, 26, 78, 79, 90, 91, 92, 93 -> { } // 211 model opcodes: no payload in 241
                case 2, 3, 9, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39 -> in.skipStr();
                case 4, 5, 6, 7, 8, 75, 94, 95, 110, 111, 112 -> in.skip(2);
                case 97, 98, 139, 140, 148, 149 -> u16(Kind.OBJ);
                case 11, 15, 16, 65, 160, 251 -> { }
                case 12 -> in.skip(4);
                case 13, 14, 27, 42, 113, 114, 115 -> in.skip(1);
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                case 43 -> { int idx = in.u8(); key = "43:" + idx; while (in.u8() != 0) in.skipStr(); }
                case 44, 46, 47, 49, 50, 51, 52, 53, 54 -> i32(Kind.MODEL);
                case 45, 48 -> { i32(Kind.MODEL); in.skip(1); }
                case 100, 101, 102, 103, 104, 105, 106, 107, 108, 109 -> { u16(Kind.OBJ); in.skip(2); }
                case 161 -> { int n = in.u16(); in.skip(n * 2); }
                case 200 -> { int idx = in.u8(), sub = in.u8(); key = "200:" + idx + ":" + sub; in.skipStr(); }
                case 201 -> key = condOp(op, false);
                case 202 -> key = condOp(op, true);
                case 249 -> { params(start); return; }
                default -> unknown(op, start);
            }
            leaf(key, op, start);
        }

        void npc(int op, int start) {
            String key = String.valueOf(op);
            switch (op) {
                case 1 -> { key = "models"; u16list(Kind.MODEL, false); }
                case 60 -> { key = "heads"; u16list(Kind.MODEL, false); }
                case 61 -> { key = "models"; i32list(Kind.MODEL, false); }
                case 62 -> { key = "heads"; i32list(Kind.MODEL, false); }
                case 2, 30, 31, 32, 33, 34 -> in.skipStr();
                case 12, 100, 101 -> in.skip(1);
                case 13, 14, 15, 16, 114, 116 -> u16(Kind.SEQ);
                case 17, 115, 117 -> { u16(Kind.SEQ); u16(Kind.SEQ); u16(Kind.SEQ); u16(Kind.SEQ); }
                case 18, 42, 74, 75, 76, 77, 78, 79, 95, 97, 98, 103, 124, 126, 146 -> in.skip(2);
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                case 93, 99, 107, 109, 111, 122, 123, 129, 130, 145, 147 -> { }
                case 102 -> { int mask = in.u8(); for (int i = 0; mask >> i != 0; i++) if ((mask & 1 << i) != 0) { bigSmart(Kind.SPRITE); in.skipSmart(); } }
                case 106, 118 -> transforms(op, 118, Kind.NPC);
                case 249 -> { params(start); return; }
                case 251 -> { int idx = in.u8(), sub = in.u8(); key = "251:" + idx + ":" + sub; in.skipStr(); }
                case 252 -> key = condOp(op, false);
                case 253 -> key = condOp(op, true);
                default -> unknown(op, start);
            }
            leaf(key, op, start);
        }

        void loc(int op, int start) {
            String key = String.valueOf(op);
            switch (op) {
                case 1 -> { key = "models"; u16list(Kind.MODEL, true); }
                case 5 -> { key = "models"; u16list(Kind.MODEL, false); }
                case 6 -> { key = "models"; i32list(Kind.MODEL, true); }
                case 7 -> { key = "models"; i32list(Kind.MODEL, false); }
                case 2, 30, 31, 32, 33, 34 -> in.skipStr();
                case 14, 15, 19, 28, 29, 39, 69, 75, 81, 91, 95, 96 -> in.skip(1);
                case 17, 18, 21, 22, 23, 27, 62, 64, 73, 74, 89, 90, 94 -> { }
                case 24 -> u16(Kind.SEQ);
                case 42, 61, 65, 66, 67, 68, 70, 71, 72, 82 -> in.skip(2);
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                case 77, 92 -> transforms(op, 92, Kind.LOC);
                case 78 -> in.skip(4);
                case 79 -> { in.skip(6); int n = in.u8(); in.skip(n * 2); }
                case 93 -> in.skip(6);
                case 100 -> { int idx = in.u8(), sub = in.u8(); key = "100:" + idx + ":" + sub; in.skipStr(); }
                case 101 -> key = condOp(op, false);
                case 102 -> key = condOp(op, true);
                case 249 -> { params(start); return; }
                default -> unknown(op, start);
            }
            leaf(key, op, start);
        }

        void spot(int op, int start) {
            String key = String.valueOf(op);
            switch (op) {
                case 1 -> { key = "model"; u16(Kind.MODEL); }
                case 3 -> { key = "model"; i32(Kind.MODEL); }
                case 2 -> u16(Kind.SEQ);
                case 4, 5, 6, 42 -> in.skip(2);
                case 7, 8 -> in.skip(1);
                case 9 -> in.skipStr();
                case 10 -> { }
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                default -> unknown(op, start);
            }
            leaf(key, op, start);
        }

        void kit(int op, int start) {
            String key = String.valueOf(op);
            if (op >= 60 && op < 70) { key = "head" + (op - 60); u16(Kind.MODEL); }
            else if (op >= 70 && op < 80) { key = "head" + (op - 70); i32(Kind.MODEL); }
            else switch (op) {
                case 1 -> in.skip(1);
                case 2 -> { key = "models"; u16list(Kind.MODEL, false); }
                case 3 -> { }
                case 5 -> { key = "models"; i32list(Kind.MODEL, false); }
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                default -> unknown(op, start);
            }
            leaf(key, op, start);
        }

        void enm(int op, int start) {
            switch (op) {
                case 1, 2 -> in.skip(1);
                case 3 -> in.skipStr();
                case 4 -> typedInt(valType);
                case 5, 6, 7 -> { enumMap(op, start); return; }
                case 8 -> in.skip(8);
                default -> unknown(op, start);
            }
            leaf(String.valueOf(op), op, start);
        }

        void param(int op, int start) {
            switch (op) {
                case 1 -> in.skip(1);
                case 2 -> { int own = ownParamType(); typedInt(own); }
                case 4 -> { }
                case 5 -> in.skipStr();
                case 7 -> in.skip(8);
                case 8 -> in.skip(1);
                default -> unknown(op, start);
            }
            leaf(String.valueOf(op), op, start);
        }

        /** A param's own type char (op 1, or op 8's type id) for typing its default (op 2). */
        int ownParamType() {
            Buf s = new Buf(b);
            int t = -1;
            while (s.p < b.length) {
                int op = s.u8();
                switch (op) {
                    case 0: return t;
                    case 1: t = s.u8(); break;
                    case 2: s.skip(4); break;
                    case 5: s.skipStr(); break;
                    case 7: s.skip(8); break;
                    case 8: t = charOfTypeId(s.u8()); break;
                    default: break;
                }
            }
            return t;
        }

        void inv(int op, int start) {
            switch (op) {
                case 2 -> in.skip(2);
                case 4 -> { int n = in.u8(); for (int i = 0; i < n; i++) { u16(Kind.OBJ); in.skip(2); } }
                case 249 -> { params(start); return; }
                default -> unknown(op, start);
            }
            leaf(String.valueOf(op), op, start);
        }

        void overlay(int op, int start) {
            switch (op) {
                case 1, 7 -> in.skip(3);
                case 2 -> in.skip(1);
                case 5 -> { }
                default -> unknown(op, start);
            }
            leaf(String.valueOf(op), op, start);
        }

        void healthbar(int op, int start) {
            switch (op) {
                case 1, 5, 11 -> in.skip(2);
                case 2, 3, 6, 14, 15 -> in.skip(1);
                case 4 -> { }
                case 7, 8 -> bigSmart(Kind.SPRITE);
                default -> unknown(op, start);
            }
            leaf(String.valueOf(op), op, start);
        }

        void dbrow(int op, int start) {
            switch (op) {
                case 3 -> { dbColumns(op, false); return; }
                case 4 -> { int p = in.p, v = varint(); refs.add(new Ref(p, Enc.VARINT, Kind.DBTABLE, v)); }
                default -> unknown(op, start);
            }
            leaf(String.valueOf(op), op, start);
        }
    }

    // ------------------------------------------------------------------ rewriting

    /** Maps one id of a kind (return the id itself when it does not move). */
    @FunctionalInterface
    public interface Mapper { int map(Kind k, int id); }

    /**
     * The item with every reference whose new id differs patched in (children of containers too; child keys follow
     * their mapped param/enum key). Returns the same instance when nothing changes. Throws when a new id does not fit
     * its field.
     */
    public static Item map(Item it, Mapper m) {
        if (it.isContainer()) {
            List<Item> ch = null;
            for (int i = 0; i < it.children.size(); i++) {
                Item c = it.children.get(i), n = map(c, m);
                if (n != c) { if (ch == null) ch = new ArrayList<>(it.children); ch.set(i, n); }
            }
            return ch == null ? it : it.withChildren(ch);
        }
        byte[] out = it.raw;
        List<Ref> refs = it.refs;
        boolean changed = false;
        // patch from the last ref to the first so variable-width encodings can shift what follows
        List<Ref> newRefs = new ArrayList<>(refs);
        for (int ri = refs.size() - 1; ri >= 0; ri--) {
            Ref r = refs.get(ri);
            if (r.none() || r.kind == null) continue;
            int to = m.map(r.kind, r.value);
            if (to == r.value) continue;
            if (!changed) { out = out.clone(); changed = true; }
            byte[] enc = encodeRef(r, to, out);
            int oldLen = refLength(r, out);
            if (enc.length == oldLen) System.arraycopy(enc, 0, out, r.off, enc.length);
            else {
                out = Bytes.concat(Arrays.copyOfRange(out, 0, r.off), enc, Arrays.copyOfRange(out, r.off + oldLen, out.length));
                int delta = enc.length - oldLen;
                for (int k = ri + 1; k < newRefs.size(); k++) { Ref q = newRefs.get(k); newRefs.set(k, new Ref(q.off + delta, q.enc, q.kind, q.value)); }
            }
            newRefs.set(ri, new Ref(r.off, r.enc, r.kind, to));
        }
        if (!changed) return it;
        String key = it.key;
        if (key.startsWith("p") && it.op == 249) key = "p" + Bytes.u24(out, 1);
        else if (key.startsWith("k") && (it.op == 5 || it.op == 6 || it.op == 7)) key = "k" + Bytes.i32(out, 0);
        return Item.leaf(key, it.op, out, newRefs);
    }

    /** An entry with every reference mapped (the same instance if nothing changes). */
    public static Entry map(Entry e, Mapper m) {
        if (e.opaque) return e;
        List<Item> items = null;
        for (int i = 0; i < e.items.size(); i++) {
            Item it = e.items.get(i), n = map(it, m);
            if (n != it) { if (items == null) items = new ArrayList<>(e.items); items.set(i, n); }
        }
        if (items == null) return e;
        Entry r = new Entry(e.group, e.id, items, false);
        r.duplicateKeys = e.duplicateKeys;
        return r;
    }

    private static int refLength(Ref r, byte[] b) {
        return switch (r.enc) {
            case U16, HAND -> 2;
            case I32, COMPONENT -> 4;
            case U24 -> 3;
            case BIGSMART -> b[r.off] < 0 ? 4 : 2;
            case VARINT -> { int n = 0; while ((b[r.off + n] & 0x80) != 0) n++; yield n + 1; }
        };
    }

    private static byte[] encodeRef(Ref r, int to, byte[] b) {
        String what = r.kind.key + " " + r.value + " -> " + to;
        switch (r.enc) {
            case U16: if (to < 0 || to >= 65535) throw new IllegalStateException(what + " does not fit a 2-byte field"); return new byte[]{(byte) (to >> 8), (byte) to};
            case HAND: { int v = to + 512; if (to < 0 || v > 65535) throw new IllegalStateException(what + " does not fit a hand-obj field"); return new byte[]{(byte) (v >> 8), (byte) v}; }
            case I32: return new BufOut(4).i32(to).done();
            case COMPONENT: { int old = Bytes.i32(b, r.off); return new BufOut(4).i32((to << 16) | (old & 0xffff)).done(); }
            case U24: if (to < 0 || to > 0xffffff) throw new IllegalStateException(what + " does not fit a 3-byte field"); return new BufOut(3).u24(to).done();
            case BIGSMART: return new BufOut(4).bigSmart(to).done();
            case VARINT: { BufOut o = new BufOut(5); int v = to; do { int x = v & 0x7f; v >>>= 7; o.u8(v != 0 ? x | 0x80 : x); } while (v != 0); return o.done(); }
            default: throw new IllegalStateException();
        }
    }

    // ------------------------------------------------------------------ small decoders

    /** Name (op 2) of an obj/npc/loc entry, or null (other groups have no name; spotanim op 2 is its seq). */
    public static String name(Entry e) {
        if (e.group != Arch.CFG_OBJ && e.group != Arch.CFG_NPC && e.group != Arch.CFG_LOC) return null;
        Item it = e.get("2");
        if (it == null) return null;
        return new String(it.raw, 1, it.raw.length - 2, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** Every reference of one kind, in order. */
    public static List<Integer> refsOf(Entry e, Kind k) {
        List<Integer> r = new ArrayList<>();
        for (Ref x : e.allRefs()) if (x.kind == k && !x.none()) r.add(x.value);
        return r;
    }

    /** Key/value type chars of an enum entry ({-1, -1} if absent). */
    public static int[] enumTypes(Entry e) {
        Item k = e.get("1"), v = e.get("2");
        return new int[]{k == null ? -1 : k.raw[1] & 255, v == null ? -1 : v.raw[1] & 255};
    }
}

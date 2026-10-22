package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.config.CfgCodec.Entry;
import com.gadscape.cacheconv.config.CfgCodec.Item;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Bytes;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Opcode-level edits of one build-241 config entry (archive 2), on top of {@link CfgCodec}: set or remove a plain
 * opcode, copy opcodes from another entry, add a recolour pair, add an enum pair. Used by {@link ConfigFixesStep} for
 * the data-driven fixes ({@code config/fixes.tsv}). Every edit keeps the other opcodes and their order; a new opcode
 * goes before the end opcode.
 * <p>
 * Payloads are written as typed values separated by spaces: {@code u8:N}, {@code u16:N}, {@code i32:N},
 * {@code str:TEXT} (cp1252, NUL-terminated; the rest of the argument), e.g. {@code 13 u16:808} (npc stand seq) or
 * {@code 2 str:Malevolent kiteshield} (name).
 */
public final class ConfigEdit {
    private ConfigEdit() {}

    private static final Charset CP1252 = Charset.forName("windows-1252");

    /** The item list of an entry as a mutable copy. */
    static List<Item> items(Entry e) { return new ArrayList<>(e.items); }

    /** The leaf key {@link CfgCodec} gives a plain opcode of the group. */
    static String key(int group, int op) {
        if (group == Arch.CFG_OBJ && op >= 44 && op <= 54) return String.valueOf(op);
        return String.valueOf(op);
    }

    /** Whether the entry has a plain (leaf) item for the opcode. */
    public static boolean has(Entry e, int op) {
        for (Item i : e.items) if (!i.isContainer() && i.op == op) return true;
        return false;
    }

    /** The raw bytes (opcode + payload) of the last leaf with the opcode, or null. */
    public static byte[] raw(Entry e, int op) {
        byte[] r = null;
        for (Item i : e.items) if (!i.isContainer() && i.op == op) r = i.raw;
        return r;
    }

    /** Sets a plain opcode to the given raw bytes (opcode first): replaces every leaf with that opcode, else appends. */
    public static Entry set(Entry e, byte[] raw) {
        int op = raw[0] & 255;
        List<Item> l = new ArrayList<>();
        boolean done = false;
        for (Item i : e.items) {
            if (!i.isContainer() && i.op == op) {
                if (!done) { l.add(Item.leaf(i.key, op, raw.clone(), List.of())); done = true; }
                continue;
            }
            l.add(i);
        }
        if (!done) l.add(Item.leaf(key(e.group, op), op, raw.clone(), List.of()));
        return CfgCodec.build(e.group, e.id, l);
    }

    /** Removes every leaf with the opcode. */
    public static Entry unset(Entry e, int op) {
        List<Item> l = new ArrayList<>();
        for (Item i : e.items) if (i.isContainer() || i.op != op) l.add(i);
        return CfgCodec.build(e.group, e.id, l);
    }

    /** For each opcode: the source's leaf if it has one, else the opcode removed. */
    public static Entry copy(Entry target, Entry source, int... ops) {
        Entry r = target;
        for (int op : ops) {
            byte[] s = raw(source, op);
            r = s != null ? set(r, s) : unset(r, op);
        }
        return r;
    }

    /** Removes params (op 249 children {@code p<id>}); the container goes when it is left empty. */
    public static Entry unsetParams(Entry e, int... params) {
        java.util.Set<String> drop = new java.util.HashSet<>();
        for (int p : params) drop.add("p" + p);
        List<Item> l = new ArrayList<>();
        for (Item i : e.items) {
            if (!i.isContainer() || i.op != 249) { l.add(i); continue; }
            List<Item> ch = new ArrayList<>();
            for (Item c : i.children) if (!drop.contains(c.key)) ch.add(c);
            if (!ch.isEmpty()) l.add(i.withChildren(ch));
        }
        return CfgCodec.build(e.group, e.id, l);
    }

    /** Copies the whole entry (every item) of {@code source} as entry {@code id} of the same group. */
    public static Entry clone(Entry source, int id) { return CfgCodec.build(source.group, id, items(source)); }

    /** Adds a recolour pair (opcode 40: u8 count, then u16 from, u16 to per pair); an existing pair for {@code from} is replaced. */
    public static Entry recolour(Entry e, int from, int to) {
        byte[] cur = raw(e, 40);
        TreeMap<Integer, Integer> pairs = new TreeMap<>();
        List<int[]> order = new ArrayList<>();
        if (cur != null) {
            int n = cur[1] & 255;
            for (int k = 0; k < n; k++) order.add(new int[]{Bytes.u16(cur, 2 + k * 4), Bytes.u16(cur, 4 + k * 4)});
        }
        boolean replaced = false;
        for (int[] p : order) if (p[0] == (from & 0xffff)) { p[1] = to & 0xffff; replaced = true; }
        if (!replaced) order.add(new int[]{from & 0xffff, to & 0xffff});
        byte[] raw = new byte[2 + order.size() * 4];
        raw[0] = 40;
        raw[1] = (byte) order.size();
        for (int k = 0; k < order.size(); k++) {
            Bytes.w16(raw, 2 + k * 4, order.get(k)[0]);
            Bytes.w16(raw, 4 + k * 4, order.get(k)[1]);
        }
        pairs.clear();
        return set(e, raw);
    }

    /** An enum with pair {@code key -> value bytes} added (or replaced); the value bytes are what the enum's map stores. */
    public static Entry enumPut(Entry e, int key, byte[] value) {
        if (e.group != Arch.CFG_ENUM) throw new IllegalArgumentException("not an enum");
        List<Item> l = new ArrayList<>();
        boolean found = false;
        for (Item i : e.items) {
            if (!i.isContainer()) { l.add(i); continue; }
            List<Item> ch = new ArrayList<>();
            boolean put = false;
            for (Item c : i.children) {
                if (Bytes.i32(c.raw, 0) == key) { ch.add(child(key, value, c)); put = true; }
                else ch.add(c);
            }
            if (!put) ch.add(child(key, value, null));
            l.add(i.withChildren(ch));
            found = true;
        }
        if (!found) throw new IllegalStateException("enum " + e.id + " has no pair map");
        return CfgCodec.build(e.group, e.id, l);
    }

    private static Item child(int key, byte[] value, Item like) {
        byte[] raw = new byte[4 + value.length];
        Bytes.w32(raw, 0, key);
        System.arraycopy(value, 0, raw, 4, value.length);
        return Item.leaf(like != null ? like.key : "k" + key, like != null ? like.op : -1, raw, List.of());
    }

    /** The value bytes of an enum pair, or null. */
    public static byte[] enumGet(Entry e, int key) {
        Map<Integer, byte[]> m = EnumMerge.pairsOf(e);
        return m.get(key);
    }

    /** Encodes a payload description ("u16:808 u8:1", "str:Open") after the opcode. */
    public static byte[] payload(int op, String spec) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(op);
        String s = spec.trim();
        while (!s.isEmpty()) {
            int colon = s.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException("bad payload '" + spec + "' (TYPE:VALUE expected)");
            String t = s.substring(0, colon);
            if (t.equals("str")) {
                byte[] b = s.substring(colon + 1).getBytes(CP1252);
                o.write(b, 0, b.length);
                o.write(0);
                break;
            }
            int sp = s.indexOf(' ', colon);
            String v = sp < 0 ? s.substring(colon + 1) : s.substring(colon + 1, sp);
            s = sp < 0 ? "" : s.substring(sp + 1).trim();
            long n = Long.parseLong(v);
            switch (t) {
                case "u8" -> o.write((int) n);
                case "u16" -> { o.write((int) (n >> 8)); o.write((int) n); }
                case "i32" -> { o.write((int) (n >> 24)); o.write((int) (n >> 16)); o.write((int) (n >> 8)); o.write((int) n); }
                default -> throw new IllegalArgumentException("bad payload type '" + t + "' in '" + spec + "'");
            }
        }
        return o.toByteArray();
    }
}

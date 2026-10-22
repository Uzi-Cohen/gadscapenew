package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.config.CfgCodec.Entry;
import com.gadscape.cacheconv.config.CfgCodec.Item;
import com.gadscape.cacheconv.convert.ConfigConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the configs steps share: reading and converting entries of the three inputs, the "same thing" rules, the
 * defaults and encoder-loss tables the three-way merge needs, and the kind of each config group.
 * <p>
 * Every USER and VAN211 entry is converted to the 241 layout first ({@link ConfigConv} for identkit, loc, npc, obj,
 * spotanim; the other groups have the same layout in 211 and 241), so all comparisons and merges work on 241 bytes.
 */
public final class CfgModel {
    public final Ctx ctx;
    public final JagCache base, user, van, old241;
    public final ParamTypeTable baseTypes, userTypes, vanTypes;
    private final Map<Integer, Lossy> lossy = new HashMap<>();

    public CfgModel(Ctx ctx) {
        this.ctx = ctx;
        this.base = ctx.base; this.user = ctx.user(); this.van = ctx.van211(); this.old241 = ctx.old241;
        this.baseTypes = new ParamTypeTable(base);
        this.userTypes = new ParamTypeTable(user);
        this.vanTypes = new ParamTypeTable(van);
    }

    // ------------------------------------------------------------------ groups

    /** Config groups the configs area owns: every archive-2 group of any input except seq (12, the anim area). */
    public TreeSet<Integer> groups() {
        TreeSet<Integer> g = new TreeSet<>();
        for (CacheView c : new CacheView[]{base, user, van}) for (int x : c.groupIds(Arch.CONFIGS)) g.add(x);
        g.remove(Arch.CFG_SEQ);
        return g;
    }

    /** The relocatable kind stored in a config group, or null. */
    public static Kind kindOf(int group) {
        return switch (group) {
            case Arch.CFG_OBJ -> Kind.OBJ;
            case Arch.CFG_NPC -> Kind.NPC;
            case Arch.CFG_LOC -> Kind.LOC;
            case Arch.CFG_SPOT -> Kind.SPOT;
            case Arch.CFG_ENUM -> Kind.ENUM;
            case Arch.CFG_STRUCT -> Kind.STRUCT;
            case Arch.CFG_PARAM -> Kind.PARAM;
            case Arch.CFG_VARBIT -> Kind.VARBIT;
            case Arch.CFG_VARP -> Kind.VARP;
            case Arch.CFG_VARC -> Kind.VARC;
            case Arch.CFG_DBROW -> Kind.DBROW;
            case Arch.CFG_DBTABLE -> Kind.DBTABLE;
            default -> null;
        };
    }

    /** Entity configs: a clash keeps the USER entry at its id (reported) unless BASE added the id after OLD241. */
    public static boolean isEntity(Kind k) { return k == Kind.OBJ || k == Kind.NPC || k == Kind.LOC || k == Kind.SPOT; }

    /** Configs BASE's own UI and scripts read: any clash moves the USER entry to the reserved range. */
    public static boolean isUiKind(Kind k) {
        return k == Kind.ENUM || k == Kind.STRUCT || k == Kind.PARAM || k == Kind.VARBIT || k == Kind.VARP || k == Kind.VARC
                || k == Kind.DBROW || k == Kind.DBTABLE;
    }

    // ------------------------------------------------------------------ reading

    /** 211 -> 241 layout for one entry (identity for groups whose layout did not change). */
    public static byte[] conv(int group, int id, byte[] b211) {
        if (b211 == null) return null;
        ConfigConv.Fn f = group == Arch.CFG_SEQ ? null : ConfigConv.forGroup(group);
        return f == null ? b211 : f.apply(id, b211);
    }

    public Origin origin(int group, int id) { return Origin.ofConfig(group, id, user, van); }

    public byte[] userBytes(int g, int id) { return conv(g, id, user.configRef(g, id)); }
    public byte[] vanBytes(int g, int id) { return conv(g, id, van.configRef(g, id)); }
    public byte[] baseBytes(int g, int id) { return base.configRef(g, id); }

    public Entry userEntry(int g, int id) { byte[] b = userBytes(g, id); return b == null ? null : CfgCodec.parse(g, id, b, userTypes); }
    public Entry vanEntry(int g, int id) { byte[] b = vanBytes(g, id); return b == null ? null : CfgCodec.parse(g, id, b, vanTypes); }
    public Entry baseEntry(int g, int id) { byte[] b = baseBytes(g, id); return b == null ? null : CfgCodec.parse(g, id, b, baseTypes); }

    /** BASE has the id but OLD241 does not: content the newest update added (the Raids 4 ids), never overwritten. */
    public boolean newInBase(int g, int id) {
        return old241 != null && base.hasConfig(g, id) && !old241.hasConfig(g, id);
    }

    public CfgCodec.Mapper mapper() { return (k, v) -> ctx.ids.map(k, v); }

    // ------------------------------------------------------------------ comparisons

    /** Leaf/container equality (containers ignore element order). */
    public static boolean equalItems(Item a, Item b) {
        if (a == null || b == null) return a == b;
        return a.isContainer() ? a.sameIgnoringOrder(b) : a.same(b);
    }

    /**
     * Decoded equality: same effective value for every key (last occurrence wins, as in the client), fields at their
     * client default count as absent, containers compare element by element in any order.
     */
    public static boolean sameEffective(int g, Entry a, Entry b) {
        if (a == null || b == null) return a == b;
        if (a.opaque || b.opaque) return Arrays.equals(a.encode(), b.encode());
        Map<String, Item> ma = a.map(), mb = b.map();
        Set<String> keys = new LinkedHashSet<>(ma.keySet());
        keys.addAll(mb.keySet());
        for (String k : keys) {
            Item x = ma.get(k), y = mb.get(k);
            if (equalItems(x, y)) continue;
            if (x == null && isDefault(g, y)) continue;
            if (y == null && isDefault(g, x)) continue;
            if (x != null && y != null && x.isContainer() && y.isContainer() && x.children.isEmpty() && y.children.isEmpty()) continue;
            if (x == null && y.isContainer() && y.children.isEmpty()) continue;
            if (y == null && x.isContainer() && x.children.isEmpty()) continue;
            return false;
        }
        return true;
    }

    /** Result of a "same thing" test. */
    public record Same(boolean same, String why) {
        static final Same NO = new Same(false, null);
    }

    /**
     * {@link #same(int, Entry, Entry)} for a USER entry before and after its references were mapped: the mapped entry
     * ({@code um}) is tried first; then the converted USER entry with its own ids ({@code u}). The second test finds
     * Jagex entries the user imported at their Jagex ids: they name exactly BASE's model/seq/varbit ids, while the
     * id map may have moved the user's (older) copies of those models or varbits away.
     */
    public static Same same(int g, Entry u, Entry um, Entry b) {
        Same s = same(g, um, b);
        if (s.same() || u == null || u == um) return s;
        s = same(g, u, b);
        return s.same() ? new Same(true, s.why() + " (USER's own ids equal BASE's)") : s;
    }

    /**
     * Name comparison for the same-thing test: case, punctuation and word order are ignored, so Jagex renames of an
     * imported entry ("Helm of neitiznot (or)" / "Helm of Neitiznot (or)", "Dizana's quiver (uncharged) (l)" /
     * "Dizana's quiver (l) (uncharged)") still match. Both null (unnamed) also match.
     */
    public static boolean sameName(String a, String b) {
        if (a == null || b == null) return a == b;
        return normName(a).equals(normName(b));
    }

    static String normName(String s) {
        String[] t = s.toLowerCase(java.util.Locale.ROOT).replaceAll("<[^>]*>", " ").replaceAll("[^a-z0-9]+", " ").trim().split(" +");
        Arrays.sort(t);
        return String.join(" ", t);
    }

    /**
     * Whether a user entry (already converted and with its references mapped) is the same thing as BASE's entry at
     * the same id (spec policy 3): identical bytes, decoded equal, or for obj/npc/loc the same name and models (obj:
     * name + inventory model, or the same cert/placeholder link for noted/placeholder variants; npc/loc: name, models
     * and transforms), for spotanims the same model and seq. Names compare with {@link #sameName}.
     */
    public static Same same(int g, Entry u, Entry b) {
        if (u == null || b == null) return Same.NO;
        byte[] ub = u.encode(), bb = b.encode();
        if (Arrays.equals(ub, bb)) return new Same(true, "identical");
        if (sameEffective(g, u, b)) return new Same(true, "decoded equal");
        switch (g) {
            case Arch.CFG_OBJ: {
                String nu = CfgCodec.name(u), nb = CfgCodec.name(b);
                boolean linkU = u.get("97") != null || u.get("98") != null || u.get("148") != null || u.get("149") != null;
                if (linkU && sameItem(u, b, "97") && sameItem(u, b, "98") && sameItem(u, b, "148") && sameItem(u, b, "149")
                        && (u.get("98") != null || u.get("149") != null) && sameName(nu, nb))
                    return new Same(true, "same cert/placeholder link" + (nu == null ? "" : " and name"));
                if (sameName(nu, nb) && u.get("44") != null && sameItem(u, b, "44"))
                    return new Same(true, nu == null ? "same inventory model, both unnamed" : Objects.equals(nu, nb) ? "same name and inventory model" : "same inventory model, name renamed by Jagex ('" + nu + "' -> '" + nb + "')");
                return Same.NO;
            }
            case Arch.CFG_NPC:
            case Arch.CFG_LOC: {
                String nu = CfgCodec.name(u), nb = CfgCodec.name(b);
                if (!sameName(nu, nb)) return Same.NO;
                if (!Objects.equals(CfgCodec.refsOf(u, Kind.MODEL), CfgCodec.refsOf(b, Kind.MODEL))) return Same.NO;
                if (!Objects.equals(transforms(u, g), transforms(b, g))) return Same.NO;
                return new Same(true, "same name, models and transforms");
            }
            case Arch.CFG_SPOT: {
                if (CfgCodec.refsOf(u, Kind.MODEL).equals(CfgCodec.refsOf(b, Kind.MODEL)) && CfgCodec.refsOf(u, Kind.SEQ).equals(CfgCodec.refsOf(b, Kind.SEQ)))
                    return new Same(true, "same model and seq");
                return Same.NO;
            }
            default:
                return Same.NO;
        }
    }

    private static boolean sameItem(Entry a, Entry b, String key) { return equalItems(a.get(key), b.get(key)); }

    /**
     * The transform block (npc 106/118, loc 77/92: the last one wins) as decoded ids [varbit, varp, targets...], or
     * null. A 118/92 block whose default target is none is the same as a 106/77 block (the user's encoder writes both).
     */
    static List<Integer> transforms(Entry e, int g) {
        Item t = null;
        for (Item x : e.items) if (x.key.equals(g == Arch.CFG_NPC ? "106" : "77") || x.key.equals(g == Arch.CFG_NPC ? "118" : "92")) t = x;
        if (t == null) return null;
        List<Integer> v = new java.util.ArrayList<>(t.refs.stream().map(CfgCodec.Ref::value).toList());
        boolean withDefault = t.op == 118 || t.op == 92;
        if (withDefault && v.size() > 2 && v.get(2) == -1) v.remove(2);
        else if (withDefault) v.add(0, -2); // a real default target: never equal to a 106/77 block
        return v;
    }

    // ------------------------------------------------------------------ defaults (fields equal to the client default are no-ops)

    private static final Map<Integer, Map<String, byte[]>> DEFAULTS = new HashMap<>();

    private static void def(int g, String key, int op, byte... payload) {
        byte[] raw = new byte[payload.length + 1];
        raw[0] = (byte) op;
        System.arraycopy(payload, 0, raw, 1, payload.length);
        DEFAULTS.computeIfAbsent(g, x -> new HashMap<>()).put(key, raw);
    }

    private static void defStr(int g, int op, String s) {
        byte[] t = s.getBytes(StandardCharsets.ISO_8859_1);
        byte[] p = Arrays.copyOf(t, t.length + 1);
        def(g, String.valueOf(op), op, p);
    }

    private static byte[] u16(int v) { return new byte[]{(byte) (v >> 8), (byte) v}; }

    static {
        // obj (241 ItemComposition defaults); the user's 211 encoders write these explicitly on every item they save
        int o = Arch.CFG_OBJ;
        def(o, "4", 4, u16(2000)); def(o, "5", 5, u16(0)); def(o, "6", 6, u16(0)); def(o, "7", 7, u16(0)); def(o, "8", 8, u16(0));
        def(o, "95", 95, u16(0)); def(o, "12", 12, (byte) 0, (byte) 0, (byte) 0, (byte) 1); def(o, "42", 42, (byte) -2);
        def(o, "75", 75, u16(0)); def(o, "110", 110, u16(128)); def(o, "111", 111, u16(128)); def(o, "112", 112, u16(128));
        def(o, "113", 113, (byte) 0); def(o, "114", 114, (byte) 0); def(o, "115", 115, (byte) 0); def(o, "94", 94, u16(65535));
        defStr(o, 32, "Take"); defStr(o, 39, "Drop");
        // npc
        int n = Arch.CFG_NPC;
        def(n, "12", 12, (byte) 1); def(n, "95", 95, u16(65535)); def(n, "97", 97, u16(128)); def(n, "98", 98, u16(128));
        def(n, "100", 100, (byte) 0); def(n, "101", 101, (byte) 0); def(n, "103", 103, u16(32)); def(n, "18", 18, u16(65535));
        def(n, "13", 13, u16(65535)); def(n, "14", 14, u16(65535)); def(n, "15", 15, u16(65535)); def(n, "16", 16, u16(65535));
        // loc
        int l = Arch.CFG_LOC;
        def(l, "14", 14, (byte) 1); def(l, "15", 15, (byte) 1); def(l, "28", 28, (byte) 16); def(l, "29", 29, (byte) 0);
        def(l, "39", 39, (byte) 0); def(l, "65", 65, u16(128)); def(l, "66", 66, u16(128)); def(l, "67", 67, u16(128));
        def(l, "69", 69, (byte) 0); def(l, "70", 70, u16(0)); def(l, "71", 71, u16(0)); def(l, "72", 72, u16(0));
        def(l, "24", 24, u16(65535)); def(l, "68", 68, u16(65535)); def(l, "61", 61, u16(65535));
        // spotanim
        int s = Arch.CFG_SPOT;
        def(s, "4", 4, u16(128)); def(s, "5", 5, u16(128)); def(s, "6", 6, u16(0)); def(s, "7", 7, (byte) 0); def(s, "8", 8, (byte) 0);
        def(s, "2", 2, u16(65535));
    }

    /** Whether the item only sets its field to the client default (adding or removing it changes nothing). */
    public static boolean isDefault(int g, Item it) {
        if (it == null) return true;
        if (it.isContainer()) return it.children.isEmpty();
        Map<String, byte[]> m = DEFAULTS.get(g);
        if (m == null) return false;
        byte[] d = m.get(it.key);
        return d != null && Arrays.equals(d, it.raw);
    }

    // ------------------------------------------------------------------ encoder losses (inferred)

    /**
     * Keys (or key=value pairs) the user's editors cannot write: in every USER_CHANGED entry of the group whose
     * VAN211 version has the key (at least {@link #LOSSY_MIN} of them), the USER version lacks it. Their absence is
     * an encoder loss, not an edit, so the merge keeps BASE's value (npc 18 category, loc 61 category, obj 13 = 0 on
     * the real inputs).
     */
    public static final int LOSSY_MIN = 5;

    public static final class Lossy {
        public final TreeSet<String> keys = new TreeSet<>(), keyValues = new TreeSet<>();
        public boolean contains(Item v) {
            return v != null && (keys.contains(v.key) || (!v.isContainer() && keyValues.contains(v.key + "=" + Bytes.hex(v.raw))));
        }
        @Override public String toString() { return "keys " + keys + ", key=value " + keyValues; }
    }

    public synchronized Lossy lossy(int g) {
        Lossy l = lossy.get(g);
        if (l != null) return l;
        l = new Lossy();
        if (!Boolean.parseBoolean(ctx.opt.get("configs.lossy", "true")) || !CfgCodec.walked(g)) { lossy.put(g, l); return l; }
        TreeMap<String, int[]> perKey = new TreeMap<>(), perKv = new TreeMap<>();
        for (int id : user.configIds(g)) {
            if (origin(g, id) != Origin.USER_CHANGED) continue;
            Entry v, u;
            try { v = vanEntry(g, id); u = userEntry(g, id); } catch (RuntimeException e) { continue; }
            Map<String, Item> vm = v.map(), um = u.map();
            for (var e : vm.entrySet()) {
                Item vi = e.getValue();
                if (vi.isContainer()) {
                    Item ui = um.get(e.getKey());
                    Map<String, Item> uc = new HashMap<>();
                    if (ui != null && ui.isContainer()) for (Item c : ui.children) uc.put(c.key, c);
                    for (Item c : vi.children) tally(perKey, c.key, uc.containsKey(c.key));
                    continue;
                }
                boolean kept = um.containsKey(e.getKey());
                tally(perKey, e.getKey(), kept);
                tally(perKv, e.getKey() + "=" + Bytes.hex(vi.raw), kept);
            }
        }
        for (var e : perKey.entrySet()) if (e.getValue()[0] >= LOSSY_MIN && e.getValue()[1] == 0) l.keys.add(e.getKey());
        for (var e : perKv.entrySet()) if (e.getValue()[0] >= LOSSY_MIN && e.getValue()[1] == 0) {
            String key = e.getKey().substring(0, e.getKey().indexOf('='));
            if (!l.keys.contains(key)) l.keyValues.add(e.getKey());
        }
        lossy.put(g, l);
        return l;
    }

    private static void tally(Map<String, int[]> m, String k, boolean kept) { int[] c = m.computeIfAbsent(k, x -> new int[2]); c[0]++; if (kept) c[1]++; }
}

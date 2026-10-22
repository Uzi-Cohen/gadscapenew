package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.config.CfgCodec;
import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.rebrand.If1Text;
import com.gadscape.cacheconv.uimerge.IfComp;
import com.gadscape.cacheconv.verify.Findings.Severity;
import mgi.tools.jagcached.CacheFormat;
import mgi.utilities.ByteBuffer;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Branding scan over every <em>decoded</em> string a player can see (owner rule: no Near Reality / NR / OSNR / Roat
 * Pkz / RuneSpawn branding anywhere in the cache), complementing {@link BrandScan}'s raw-byte scan:
 * <ul>
 *   <li>configs (obj, npc, loc, enum, struct, param, dbrow, dbtable, spotanim, inv, varbit, healthbar), decoded with the
 *       server's strict 241 decoders; every String field, String array element and String map value (names, examines,
 *       ops, params, enum/struct/dbrow strings);</li>
 *   <li>interfaces: IF3 component strings ({@link IfComp}: text, ops, op base, target verb, string hook arguments) and
 *       IF1 texts ({@link If1Text});</li>
 *   <li>clientscripts: string constants.</li>
 * </ul>
 * Patterns: {@link #BRAND} (case-insensitive) and the whole word {@code NR} (case-sensitive), which raw bytes cannot be
 * scanned for (it occurs in binary data). With BASE as baseline, hits in stock entries are stock (Jagex text).
 */
public final class StringScan {
    private StringScan() {}

    public static final Pattern BRAND = Pattern.compile("near[\\s_\\-.]*reality|(?<![a-z0-9])osnr(?![a-z0-9])|(?<![a-z])roat|rune[\\s_\\-]*spawn",
            Pattern.CASE_INSENSITIVE);
    public static final Pattern NR = Pattern.compile("(?<![A-Za-z0-9])NR(?![A-Za-z0-9])");

    public record Hit(String where, String text, String pattern) {}

    static final int[] GROUPS = {Arch.CFG_OBJ, Arch.CFG_NPC, Arch.CFG_LOC, Arch.CFG_ENUM, Arch.CFG_STRUCT, Arch.CFG_PARAM, Arch.CFG_DBROW,
            Arch.CFG_DBTABLE, Arch.CFG_SPOT, Arch.CFG_INV, Arch.CFG_HEALTHBAR};

    /** Every string of the cache, with where it is. */
    public static void strings(CacheView c, BiConsumer<String, String> out) {
        int rev = CacheFormat.revision();
        boolean strict = CacheFormat.isStrict();
        CacheFormat.setRevision(CacheFormat.REV_241);
        CacheFormat.setStrict(true);
        java.io.PrintStream so = System.out, se = System.err;
        try {
            java.io.PrintStream quiet = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
            System.setOut(quiet); System.setErr(quiet);
            if (c.hasArchive(Arch.CONFIGS)) for (int g : GROUPS) {
                if (!c.hasGroup(Arch.CONFIGS, g)) continue;
                GroupData d = c.group(Arch.CONFIGS, g);
                for (int i = 0; i < d.fileCount(); i++) {
                    int id = d.fileIdAt(i);
                    Object o = decode(g, id, d.dataAt(i));
                    if (o != null) collect(o, Arch.configName(g) + " " + id, out, new IdentityHashMap<>(), 0);
                }
                c.release(Arch.CONFIGS);
            }
        } finally {
            System.setOut(so); System.setErr(se);
            CacheFormat.setRevision(rev);
            CacheFormat.setStrict(strict);
        }
        if (c.hasArchive(Arch.INTERFACES)) {
            for (int g : c.groupIds(Arch.INTERFACES)) {
                GroupData d;
                try { d = c.group(Arch.INTERFACES, g); } catch (RuntimeException e) { continue; }
                if (d == null) continue;
                for (int i = 0; i < d.fileCount(); i++) {
                    String where = "component " + g + ":" + d.fileIdAt(i);
                    byte[] b = d.dataAt(i);
                    try {
                        IfComp ic = IfComp.decode(b);
                        if (ic.if3) {
                            for (Object v : ic.f.values()) {
                                if (v instanceof String s) out.accept(where, s);
                                else if (v instanceof List<?> l) for (Object x : l) if (x instanceof String s) out.accept(where, s);
                            }
                        } else {
                            List<String[]> none = new ArrayList<>();
                            If1Text.rewrite(b, s -> { out.accept(where, s); return s; }, none);
                        }
                    } catch (RuntimeException e) {
                        // undecodable components are the interface checks' business
                    }
                }
            }
            c.release(Arch.INTERFACES);
        }
        if (c.hasArchive(Arch.SCRIPTS)) {
            for (int g : c.groupIds(Arch.SCRIPTS)) {
                byte[] b = c.single(Arch.SCRIPTS, g);
                if (b == null) continue;
                try {
                    for (Cs2.Ins in : Cs2.parse241(b).code) if (in.op == Cs2.SCONST && in.s != null) out.accept("script " + g, in.s);
                } catch (RuntimeException e) {
                    // the script checks report these
                }
            }
            c.release(Arch.SCRIPTS);
        }
    }

    static Object decode(int g, int id, byte[] b) {
        ByteBuffer buf = new ByteBuffer(b.clone());
        try {
            return switch (g) {
                case Arch.CFG_OBJ -> new mgi.types.config.items.ItemDefinitions(id, buf);
                case Arch.CFG_NPC -> new mgi.types.config.npcs.NPCDefinitions(id, buf);
                case Arch.CFG_LOC -> new mgi.types.config.ObjectDefinitions(id, buf);
                case Arch.CFG_SPOT -> new mgi.types.config.SpotAnimationDefinition(id, buf);
                case Arch.CFG_INV -> new mgi.types.config.InventoryDefinitions(id, buf);
                case Arch.CFG_HEALTHBAR -> new mgi.types.config.HitbarDefinitions(id, buf);
                case Arch.CFG_ENUM -> { var e = new mgi.types.config.enums.EnumDefinitions(); e.decode(buf); yield e; }
                case Arch.CFG_STRUCT -> { var e = new mgi.types.config.StructDefinitions(id); e.decode(buf); yield e; }
                case Arch.CFG_PARAM -> { var e = new mgi.types.config.ParamDefinitions(); e.decode(buf); yield e; }
                case Arch.CFG_DBROW -> { var e = new mgi.types.config.DBRowDefinition(id); e.decode(buf); yield e; }
                case Arch.CFG_DBTABLE -> { var e = new mgi.types.config.DBTableDefinition(); e.decode(buf); yield e; }
                default -> null;
            };
        } catch (Throwable t) {
            // undecodable entries are the oracle's business; scan their NUL-terminated runs instead
            return CfgCodec.walked(g) ? new RawStrings(b) : null;
        }
    }

    /** Fallback for an entry the decoder rejects: its NUL-terminated printable runs. */
    record RawStrings(byte[] b) {}

    private static void collect(Object o, String where, BiConsumer<String, String> out, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (o == null || depth > 4 || seen.put(o, Boolean.TRUE) != null) return;
        if (o instanceof String s) { out.accept(where, s); return; }
        if (o instanceof RawStrings r) {
            byte[] b = r.b;
            int i = 0;
            while (i < b.length) {
                if ((b[i] & 255) < 32) { i++; continue; }
                int j = i;
                while (j < b.length && (b[j] & 255) >= 32) j++;
                if (j < b.length && b[j] == 0) out.accept(where, new String(b, i, j - i, java.nio.charset.StandardCharsets.ISO_8859_1));
                i = j;
            }
            return;
        }
        if (o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof Enum<?>) return;
        if (o.getClass().isArray()) {
            if (o.getClass().getComponentType().isPrimitive()) return;
            int n = Array.getLength(o);
            for (int i = 0; i < n; i++) collect(Array.get(o, i), where, out, seen, depth + 1);
            return;
        }
        if (o instanceof Map<?, ?> m) { for (Object v : m.values()) collect(v, where, out, seen, depth + 1); return; }
        if (o instanceof Collection<?> l) { for (Object v : l) collect(v, where, out, seen, depth + 1); return; }
        Class<?> cl = o.getClass();
        if (!cl.getName().startsWith("mgi.") && depth > 0) {
            // other library objects (fastutil maps, ...): their values only
            if (o instanceof Iterable<?> it) for (Object v : it) collect(v, where, out, seen, depth + 1);
            return;
        }
        for (; cl != null && cl != Object.class; cl = cl.getSuperclass()) {
            for (Field f : cl.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v != null) collect(v, where, out, seen, depth + 1);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // inaccessible field: skip
                }
            }
        }
    }

    /** Every branded string. */
    public static List<Hit> scan(CacheView c) {
        List<Hit> hits = new ArrayList<>();
        strings(c, (where, s) -> {
            if (BRAND.matcher(s).find()) hits.add(new Hit(where, s, "brand"));
            else if (NR.matcher(s).find()) hits.add(new Hit(where, s, "NR"));
        });
        return hits;
    }

    /** Adds check {@code brand/strings} (one bad item per hit) to the findings. */
    public static void run(CacheView c, Findings f, Severity sev) {
        int strings[] = {0};
        List<Hit> hits = new ArrayList<>();
        strings(c, (where, s) -> {
            strings[0]++;
            if (BRAND.matcher(s).find()) hits.add(new Hit(where, s, "brand"));
            else if (NR.matcher(s).find()) hits.add(new Hit(where, s, "NR"));
        });
        for (Hit h : hits) {
            String t = h.text.length() > 120 ? h.text.substring(0, 120) + "..." : h.text;
            f.bad("brand/strings", sev, h.where + ": " + t, h.where + ": \"" + t + "\" (" + h.pattern + ")");
        }
        if (hits.isEmpty()) f.ok("brand/strings", sev);
        f.info("brand.strings.scanned", strings[0]);
        f.info("brand.strings.hits", hits.size());
    }
}

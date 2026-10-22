package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Step;
import mgi.tools.jagcached.CacheFormat;
import mgi.utilities.ByteBuffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * configs-oracle: decodes every config entry of the output that is not BASE's (archive 2, every group but seq, which
 * the anim area checks) with the GadScape server's own build-241 decoders ({@code mgi.types.config.*}, strict 241
 * mode) and requires each decode to consume the entry exactly. These are the decoders the server loads the cache
 * with, written against the 241 client's opcode tables, so this is the client-decode oracle for configs.
 * <ul>
 *   <li>error: an output entry the server decoder rejects (exception, or bytes left over), when the same decoder
 *       reads BASE's entries of that group;</li>
 *   <li>warning: failures in a group where the decoder also fails on BASE entries (a decoder gap, not the
 *       converter's), with examples;</li>
 *   <li>counts: entries decoded per group.</li>
 * </ul>
 * Groups without a server decoder (area, hitsplat, varp, varc, ...) are counted as not checked. The decoders' global
 * revision/strict flags are restored afterwards.
 */
public final class ConfigOracle implements Step {
    public static final String NAME = "configs-oracle";

    @Override public String name() { return NAME; }

    /** Groups the server has a 241 decoder for. */
    static final int[] DECODED = {Arch.CFG_INV, Arch.CFG_LOC, Arch.CFG_ENUM, Arch.CFG_NPC, Arch.CFG_OBJ, Arch.CFG_PARAM, Arch.CFG_SPOT,
            Arch.CFG_VARBIT, Arch.CFG_HEALTHBAR, Arch.CFG_STRUCT, Arch.CFG_DBROW, Arch.CFG_DBTABLE};

    static boolean decoded(int g) { for (int x : DECODED) if (x == g) return true; return false; }

    /**
     * Decodes one entry with the server decoder; null if it decodes completely, else the problem. Callers must have
     * put {@link CacheFormat} in 241 mode.
     */
    public static String decode(int g, int id, byte[] b) {
        ByteBuffer buf = new ByteBuffer(b.clone());
        try {
            switch (g) {
                case Arch.CFG_OBJ -> new mgi.types.config.items.ItemDefinitions(id, buf);
                case Arch.CFG_NPC -> new mgi.types.config.npcs.NPCDefinitions(id, buf);
                case Arch.CFG_LOC -> new mgi.types.config.ObjectDefinitions(id, buf);
                case Arch.CFG_SPOT -> new mgi.types.config.SpotAnimationDefinition(id, buf);
                case Arch.CFG_VARBIT -> new mgi.types.config.VarbitDefinitions(id, buf);
                case Arch.CFG_INV -> new mgi.types.config.InventoryDefinitions(id, buf);
                case Arch.CFG_HEALTHBAR -> new mgi.types.config.HitbarDefinitions(id, buf);
                case Arch.CFG_ENUM -> new mgi.types.config.enums.EnumDefinitions().decode(buf);
                case Arch.CFG_STRUCT -> new mgi.types.config.StructDefinitions(id).decode(buf);
                case Arch.CFG_PARAM -> new mgi.types.config.ParamDefinitions().decode(buf);
                case Arch.CFG_DBROW -> new mgi.types.config.DBRowDefinition(id).decode(buf);
                case Arch.CFG_DBTABLE -> new mgi.types.config.DBTableDefinition().decode(buf);
                default -> { return "no decoder"; }
            }
        } catch (Throwable t) {
            String m = t.getMessage();
            return t.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 120 ? m.substring(0, 120) : m));
        }
        if (buf.getPosition() != b.length) return (b.length - buf.getPosition()) + " bytes not consumed";
        return null;
    }

    @Override public void run(Ctx ctx) {
        int rev = CacheFormat.revision();
        boolean strict = CacheFormat.isStrict();
        CacheFormat.setRevision(CacheFormat.REV_241);
        CacheFormat.setStrict(true);
        java.io.PrintStream so = System.out, se = System.err;
        try {
            // the decoders log unknown opcodes on stdout/stderr; keep the build log readable
            java.io.PrintStream quiet = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
            System.setOut(quiet); System.setErr(quiet);
            check(ctx);
        } finally {
            System.setOut(so); System.setErr(se);
            CacheFormat.setRevision(rev);
            CacheFormat.setStrict(strict);
        }
        ctx.log("%s", summary);
    }

    private String summary = "";

    void check(Ctx ctx) {
        CacheView out = ctx.out;
        TreeMap<String, Integer> ok = new TreeMap<>(), notChecked = new TreeMap<>();
        TreeMap<Integer, List<String>> fails = new TreeMap<>();
        for (int g : out.groupIds(Arch.CONFIGS)) {
            if (g == Arch.CFG_SEQ) continue;
            for (int id : out.configIds(g)) {
                byte[] b = out.configRef(g, id), base = ctx.base.configRef(g, id);
                if (base != null && Arrays.equals(base, b)) continue;
                if (!decoded(g)) { notChecked.merge(Arch.configName(g), 1, Integer::sum); continue; }
                String p = decode(g, id, b);
                if (p == null) ok.merge(Arch.configName(g), 1, Integer::sum);
                else fails.computeIfAbsent(g, x -> new ArrayList<>()).add(id + ": " + p);
            }
            out.release(Arch.CONFIGS);
        }
        int errors = 0, gaps = 0;
        for (var e : fails.entrySet()) {
            int g = e.getKey();
            // baseline: does the decoder read BASE's entries of this group?
            int baseFail = 0, baseN = 0;
            String baseEx = null;
            for (int id : ctx.base.configIds(g)) {
                baseN++;
                String p = decode(g, id, ctx.base.configRef(g, id));
                if (p != null) { baseFail++; if (baseEx == null) baseEx = id + ": " + p; }
            }
            ctx.base.release(Arch.CONFIGS);
            List<String> l = e.getValue();
            String ex = (l.size() > 8 ? l.subList(0, 8) + "... (" + l.size() + ")" : l.toString());
            if (baseFail == 0) {
                errors += l.size();
                for (String f : l.subList(0, Math.min(l.size(), 50))) ctx.report.error(Arch.configName(g), Long.parseLong(f.substring(0, f.indexOf(':'))), "server 241 decoder rejects the entry: " + f.substring(f.indexOf(':') + 2));
                if (l.size() > 50) ctx.report.error(Arch.configName(g) + ": " + (l.size() - 50) + " more entries the server decoder rejects");
            } else {
                gaps += l.size();
                ctx.report.warn(Arch.configName(g), Long.MIN_VALUE, l.size() + " output entries fail the server decoder, which also fails on " + baseFail + "/" + baseN
                        + " BASE entries (decoder gap, e.g. " + baseEx + "): " + ex);
            }
            ctx.report.count("decoder-failed." + Arch.configName(g), l.size());
        }
        for (var e : ok.entrySet()) ctx.report.count("decoded-ok." + e.getKey(), e.getValue());
        for (var e : notChecked.entrySet()) ctx.report.count("no-server-decoder." + e.getKey(), e.getValue());
        int n = ok.values().stream().mapToInt(Integer::intValue).sum();
        summary = n + " non-BASE entries decode with the server's 241 decoders; " + errors + " rejected, " + gaps + " in groups the decoder cannot read in BASE either; not checked (no decoder) " + notChecked;
    }
}

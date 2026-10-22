package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.config.CfgCodec.Entry;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Rev225;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes the rev-225 import table ({@link Rev225}, resource {@code config/rev225.tsv}) from the rev-225 evidence cache
 * (OpenRS2 1943, the cache GadScape's DataMigration ported Jagex content from). Dev tool, run when USER changes:
 * <pre>
 *   dev/run.sh config.Rev225Gen [--rev225=DIR] [--table-out=FILE]
 * </pre>
 * Default evidence: {@code ROOT/gadconv-work/maps/evidence/rev225/1943/x/cache} (the same cache the maps area uses);
 * default output: the module's {@code src/main/resources/config/rev225.tsv} when run from the module, else
 * {@code WORK/config/rev225.tsv}.
 * <p>
 * For every USER entry the user added or changed (USER vs VAN211) that rev 225 also has:
 * <ul>
 *   <li>byte-identical to rev 225's: {@code import};</li>
 *   <li>otherwise (user-added ids only; an edited vanilla 211 entry merges against VAN211), both converted to the 241
 *       layout: if the user's version only lacks fields of rev 225's (GadScape's
 *       NPC encoder cannot write combat stats 74-79, follower ops 122/123, ...) or re-encodes (the multi transform block
 *       106/77 as 118/92), {@code import}; if it adds or changes
 *       fields and is still the same thing (same name and an overlapping model set; spotanims: the same model), an
 *       {@code edit} whose row stores rev 225's bytes; else nothing (the user replaced the entry with own content).</li>
 * </ul>
 * Config groups: underlay 1, identkit 3, overlay 4, loc 6, npc 9, obj 10, spotanim 13, hitsplat 32, healthbar 33 (UI
 * configs are not Jagex imports). Models (archive 7): byte-identical only.
 */
public final class Rev225Gen {
    private Rev225Gen() {}

    static final int[] GROUPS = {Arch.CFG_UNDERLAY, Arch.CFG_IDENTKIT, Arch.CFG_OVERLAY, Arch.CFG_LOC, Arch.CFG_NPC, Arch.CFG_OBJ, Arch.CFG_SPOT, Arch.CFG_HITSPLAT, Arch.CFG_HEALTHBAR};

    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        Path ev = Path.of(o.get("rev225", (o.root == null ? Path.of(".") : o.root).resolve("gadconv-work/maps/evidence/rev225/1943/x/cache").toString()));
        Path module = Path.of(System.getProperty("gadconv.module", "."));
        Path res = module.resolve("src/main/resources/config/rev225.tsv");
        Path outFile = Path.of(o.get("table-out", Files.isDirectory(res.getParent()) ? res.toString() : (o.work != null ? o.work : Path.of("gadconv-work")).resolve("config/rev225.tsv").toString()));
        try (Ctx ctx = Ctx.open(o, false); JagCache r = JagCache.open("REV225", ev)) {
            Rev225 t = classify(ctx, r);
            Files.createDirectories(outFile.getParent());
            Files.writeString(outFile, tsv(t, ev), StandardCharsets.UTF_8);
            System.out.println("wrote " + t.size() + " rows to " + outFile);
        }
    }

    /** Classifies every USER entry against the rev-225 cache. */
    public static Rev225 classify(Ctx ctx, JagCache r225) {
        CfgModel m = new CfgModel(ctx);
        ParamTypeTable rTypes = new ParamTypeTable(r225);
        CfgCodec.Mapper identity = (k, v) -> v;
        Rev225 t = Rev225.builder("generated");
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (int g : GROUPS) {
            if (!m.user.hasArchive(Arch.CONFIGS)) break;
            for (int id : m.user.configIds(g)) {
                Origin o = m.origin(g, id);
                if (!o.isUser()) continue;
                byte[] ub = m.user.configRef(g, id), rb = r225.configRef(g, id);
                if (rb == null) continue;
                long crc = Rev225.crc(ub);
                if (Arrays.equals(ub, rb)) { t.put(new Rev225.Row(Rev225.cfgKind(g), id, Rev225.Status.IMPORT, crc, null)); counts.merge(g + ".import", 1, Integer::sum); continue; }
                if (!CfgCodec.walked(g)) continue;
                Entry u, re;
                try { u = m.userEntry(g, id); re = CfgCodec.parse(g, id, CfgModel.conv(g, id, rb), rTypes); }
                catch (RuntimeException e) { counts.merge(g + ".unreadable", 1, Integer::sum); continue; }
                if (u.opaque || re.opaque) continue;
                Merge3.Result d = Merge3.merge(g, re, u, re, identity, m.lossy(g));
                boolean sameTransforms = java.util.Objects.equals(CfgModel.transforms(u, g), CfgModel.transforms(re, g));
                boolean adds = d.applied.stream().anyMatch(x -> !x.startsWith("-") && !x.endsWith("-") && !(sameTransforms && isTransformKey(g, x)));
                if (!adds && o == Origin.USER_ADDED) { t.put(new Rev225.Row(Rev225.cfgKind(g), id, Rev225.Status.IMPORT, crc, null)); counts.merge(g + ".import-encoder-losses", 1, Integer::sum); continue; }
                if (!adds) { counts.merge(g + ".changed-vanilla-not-classified", 1, Integer::sum); continue; } // a 211 entry the user edited: VAN211 is its merge base
                if (o == Origin.USER_ADDED && sameThing(g, u, re)) {
                    t.put(new Rev225.Row(Rev225.cfgKind(g), id, Rev225.Status.EDIT, crc, rb.clone()));
                    counts.merge(g + ".edit", 1, Integer::sum);
                } else counts.merge(g + ".replaced-by-user", 1, Integer::sum);
            }
            m.user.release(Arch.CONFIGS);
        }
        for (int id : m.user.groupIds(Arch.MODELS)) {
            if (!r225.hasGroup(Arch.MODELS, id)) continue;
            byte[] ub = m.user.single(Arch.MODELS, id);
            byte[] vb = m.van.hasGroup(Arch.MODELS, id) ? m.van.single(Arch.MODELS, id) : null;
            if (ub == null || Arrays.equals(ub, vb)) continue;
            if (Arrays.equals(ub, r225.single(Arch.MODELS, id))) { t.put(new Rev225.Row(Rev225.MODEL, id, Rev225.Status.IMPORT, Rev225.crc(ub), null)); counts.merge("model.import", 1, Integer::sum); }
            if ((id & 1023) == 0) { m.user.release(Arch.MODELS); m.van.release(Arch.MODELS); r225.release(Arch.MODELS); }
        }
        System.out.println("rev-225 classification: " + counts);
        return t;
    }

    /** "+106"/"+118" (npc) or "+77"/"+92" (loc): the multi transform block, re-encoded by GadScape's editor (it writes 118/92 with no default). */
    static boolean isTransformKey(int g, String applied) {
        String k = applied.substring(1);
        return g == Arch.CFG_NPC ? k.equals("106") || k.equals("118") : g == Arch.CFG_LOC && (k.equals("77") || k.equals("92"));
    }

    /**
     * The user's entry is still rev 225's object: same name and an overlapping model set (spotanims: same models). An
     * unnamed rev 225 entry without models is a placeholder, so whatever the user put there is the user's own.
     */
    static boolean sameThing(int g, Entry u, Entry r) {
        String nr = CfgCodec.name(r);
        if ((nr == null || nr.equalsIgnoreCase("null")) && CfgCodec.refsOf(r, Kind.MODEL).stream().allMatch(x -> x < 0)) return false;
        if (CfgModel.same(g, u, r).same()) return true;
        TreeSet<Integer> mu = new TreeSet<>(CfgCodec.refsOf(u, Kind.MODEL)), mr = new TreeSet<>(CfgCodec.refsOf(r, Kind.MODEL));
        mu.remove(-1); mr.remove(-1);
        if (g == Arch.CFG_SPOT) return mu.equals(mr);
        if (!CfgModel.sameName(CfgCodec.name(u), CfgCodec.name(r))) return false;
        if (mu.isEmpty() && mr.isEmpty()) return true;
        mu.retainAll(mr);
        return !mu.isEmpty();
    }

    static String tsv(Rev225 t, Path ev) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Jagex entries GadScape's DataMigration imported from rev 225 (OpenRS2 1943), generated by gadconv config.Rev225Gen\n");
        sb.append("# from the rev-225 evidence cache (not a build input). Columns: kind (config group or 'model'), id,\n");
        sb.append("# import|edit and the CRC-32 of the USER bytes it was classified with (hex). An import row covers a run of up to 32 consecutive\n");
        sb.append("# ids from the given one, one CRC per id (comma-separated); an edit row holds one id and rev 225's bytes (hex, 211 layout).\n");
        sb.append("# import: BASE's newer entry replaces the USER copy (treated as vanilla). edit: the user's edit is re-applied onto\n");
        sb.append("# BASE's entry with rev 225's as the merge base. An id whose CRC no longer matches USER is ignored.\n");
        List<Rev225.Row> rows = t.rows();
        for (int i = 0; i < rows.size(); ) {
            Rev225.Row r = rows.get(i);
            sb.append(r.kind()).append('\t').append(r.id()).append('\t').append(r.status().name().toLowerCase(java.util.Locale.ROOT)).append('\t')
              .append(String.format("%08x", r.userCrc()));
            int j = i + 1;
            if (r.status() == Rev225.Status.IMPORT) {
                while (j - i < 32 && j < rows.size() && rows.get(j).status() == Rev225.Status.IMPORT && rows.get(j).kind().equals(r.kind()) && rows.get(j).id() == r.id() + (j - i)) {
                    sb.append(',').append(String.format("%08x", rows.get(j).userCrc()));
                    j++;
                }
            } else sb.append('\t').append(Bytes.hex(r.r225()));
            sb.append('\n');
            i = j;
        }
        return sb.toString();
    }
}

package com.gadscape.cacheconv.model;

import com.gadscape.cacheconv.convert.ModelConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Archive 7 (models). The output starts with BASE's models; this step layers the user's own models on top.
 * <ul>
 *   <li>vanilla (USER bytes == VAN211 bytes): BASE's model stays. If BASE lacks the id, the 211 copy is converted
 *       and written (so user content that still uses it keeps working).</li>
 *   <li>user-added / user-changed: converted to a 241 encoding ({@link ModelConv#toRev241}: header and trailer,
 *       face-depth tail, decode-equality check), then
 *     <ul>
 *       <li>id free in BASE: written at the same id;</li>
 *       <li>BASE has the same bytes or the same geometry at that id: BASE's model is used ("same");</li>
 *       <li>user-changed, and BASE still has the vanilla 211 model at that id (Jagex never changed it): the user's
 *           edit is written over it in place, as on 211, so the vanilla entries using it show the edit (option
 *           {@code models-changed=auto}, the default; {@code relocate} moves these too, {@code override} always
 *           writes in place);</li>
 *       <li>BASE has a different model there (IC-15): the user model moves. If BASE holds an identical model at
 *           another id, the id is mapped to that model ("twin", option {@code models-dedup}); otherwise it gets a
 *           free id of the model range and is written there.</li>
 *     </ul></li>
 * </ul>
 * Every moved id is recorded in {@link IdMap} (kind {@code model}), so the config and UI steps rewrite references
 * with {@link ModelRefs#remap}. This step must run before them.
 * <p>
 * <b>Model range.</b> The spec reserved 100000-199999, but the build-241 client cannot load a model above 65535: its
 * JS5 requests and replies carry the group id in 16 bits (see {@code package-info}). The step therefore sets the
 * model range to {@code [highest BASE model + 1, 65535]} (option {@code models-range=LO-HI}) and packs moved models at
 * the top of it ({@code models-pack=top}, the default), which leaves the ids right after BASE's models free for the
 * next stock cache for as long as possible. {@code models-prev-idmap=FILE} keeps the targets of an earlier build
 * where they are still free, so ids stay stable across rebuilds.
 * <p>
 * The step also counts which USER entries reference the moved models (obj/npc/loc/spotanim/identkit configs,
 * interface components, model-typed enums and struct params), and warns when an untouched vanilla entry
 * references a model the user edited (that entry comes from BASE and shows BASE's model).
 */
public final class ModelsStep implements Step {
    public static final String NAME = "models";
    /** Highest model id the build-241 client can fetch (16-bit JS5 group ids). */
    public static final int MAX_CLIENT_MODEL = 65535;

    /** One moved user model. {@code twin}: mapped onto an identical model that already exists in the output. */
    public record Move(int from, int to, boolean twin, Origin origin, String why) {}

    /** What the last run did (for the dry-run main and the self-check). */
    public static final class Summary {
        public int lo, hi;
        public final TreeMap<String, Integer> counts = new TreeMap<>();
        public final List<Move> moves = new ArrayList<>();
        public final List<Integer> writtenAtId = new ArrayList<>();
        public RefCensus census;
        void count(String k) { counts.merge(k, 1, Integer::sum); }
        public int count(String k, int def) { return counts.getOrDefault(k, def); }
    }

    private Summary last;

    @Override public String name() { return NAME; }

    public Summary summary() { return last; }

    @Override public void run(Ctx ctx) throws Exception {
        Summary s = new Summary();
        last = s;
        JagCache user = ctx.user(), van = ctx.van211();
        int A = Arch.MODELS;

        // ---------------------------------------------------------------- range
        int[] outIds = ctx.out.groupIds(A);
        int outMax = outIds.length == 0 ? -1 : outIds[outIds.length - 1];
        String changedMode = ctx.opt.get("models-changed", "auto");
        if (!changedMode.equals("auto") && !changedMode.equals("relocate") && !changedMode.equals("override"))
            throw new IllegalArgumentException("--models-changed must be auto, relocate or override, not " + changedMode);
        String rangeOpt = ctx.opt.get("models-range", "auto");
        if (rangeOpt.equals("auto")) { s.lo = outMax + 1; s.hi = MAX_CLIENT_MODEL; }
        else { String[] p = rangeOpt.split("-"); s.lo = Integer.parseInt(p[0].trim()); s.hi = Integer.parseInt(p[1].trim()); }
        if (s.hi > MAX_CLIENT_MODEL) ctx.report.warn(Kind.MODEL.key, Long.MIN_VALUE, "model range " + s.lo + "-" + s.hi + " goes above " + MAX_CLIENT_MODEL + ": the 241 client cannot fetch those models (16-bit JS5 group ids)");
        boolean rangeOk = s.lo <= s.hi;
        if (rangeOk) ctx.ids.setRange(Kind.MODEL, s.lo, s.hi);
        else ctx.report.error(Kind.MODEL.key, Long.MIN_VALUE, "no room for moved models: BASE already uses ids up to " + outMax);
        ctx.report.note("model range " + s.lo + "-" + s.hi + " (BASE models end at " + outMax + "; the 241 client fetches model ids up to " + MAX_CLIENT_MODEL + " only, so the spec's 100000-199999 is not usable)");

        // ---------------------------------------------------------------- classify
        BitSet userIds = new BitSet();
        for (int id : user.groupIds(A)) userIds.set(id);
        for (int id : van.groupIds(A)) if (!userIds.get(id)) { s.count("user-removed"); if (!ctx.out.hasGroup(A, id)) s.count("user-removed.absent-in-base"); }
        TreeMap<Integer, byte[]> clash = new TreeMap<>();
        TreeMap<Integer, String> clashWhy = new TreeMap<>();
        TreeMap<Integer, Origin> origins = new TreeMap<>();
        StringBuilder sameIds = new StringBuilder();
        int[] run = {-2, -2};
        for (int id = userIds.nextSetBit(0); id >= 0; id = userIds.nextSetBit(id + 1)) {
            Origin o = Origin.ofGroup(A, id, user, van);
            s.count("user-models");
            if (o == Origin.VANILLA) {
                s.count("vanilla");
                if (ctx.out.hasGroup(A, id)) continue;
                byte[] conv = convert(ctx, s, id, user.single(A, id));
                if (conv == null) continue;
                put(ctx, id, id, conv);
                s.writtenAtId.add(id);
                s.count("vanilla.missing-in-base.written");
                ctx.report.add(Decision.NOTE, Kind.MODEL, id, "vanilla 211 model missing in BASE: wrote the converted 211 copy");
                continue;
            }
            origins.put(id, o);
            s.count(o == Origin.USER_ADDED ? "user-added" : "user-changed");
            byte[] src = user.single(A, id);
            byte[] conv = convert(ctx, s, id, src);
            if (conv == null) continue;
            if (id > MAX_CLIENT_MODEL) { clash.put(id, conv); clashWhy.put(id, "id above " + MAX_CLIENT_MODEL); continue; }
            byte[] existing = ctx.out.hasGroup(A, id) ? ctx.out.single(A, id) : null;
            if (existing == null) {
                put(ctx, id, id, conv);
                s.writtenAtId.add(id);
                s.count("written.same-id");
                ctx.report.add(o == Origin.USER_ADDED ? Decision.USER_ADDED : Decision.USER_CHANGED, Kind.MODEL, id, "written at its id (free in BASE)");
                continue;
            }
            if (java.util.Arrays.equals(existing, conv)) { s.count("same.bytes"); ranges(sameIds, run, id); continue; }
            String why = ModelConv.sameDecoded(id, conv, existing);
            if (why == null) { s.count("same.geometry"); ranges(sameIds, run, id); continue; }
            if (o == Origin.USER_CHANGED && !changedMode.equals("relocate")) {
                // the user edited a vanilla model: if Jagex never changed it (BASE == VAN211), the edit replaces it in place,
                // as it did on 211 (vanilla entries that use the model show the edit too)
                byte[] v = van.single(A, id);
                boolean baseUnchanged = false;
                try { baseUnchanged = v != null && ModelConv.sameDecoded(id, ModelConv.toRev241(id, v, false), existing) == null; } catch (RuntimeException ignored) { }
                if (changedMode.equals("override") || baseUnchanged) {
                    put(ctx, id, id, conv);
                    s.writtenAtId.add(id);
                    s.count("user-changed.override");
                    ctx.report.add(Decision.USER_CHANGED, Kind.MODEL, id, "user edit of a vanilla model" + (baseUnchanged ? " that BASE still has unchanged since 211" : "")
                            + ": written over BASE's copy (" + why + "), so vanilla entries using it show the edit as on 211");
                    continue;
                }
            }
            clash.put(id, conv);
            clashWhy.put(id, "BASE model " + id + " differs (" + why + ")");
        }
        ranges(sameIds, run, -1);
        user.release(A); van.release(A);
        if (sameIds.length() > 0) ctx.report.add(Decision.SAME, Kind.MODEL.key, Long.MIN_VALUE, null,
                "user models equal to BASE's at the same id (BASE kept): " + sameIds);
        s.counts.put("clash", clash.size());

        // ---------------------------------------------------------------- who references the clashing models
        boolean censusOn = Boolean.parseBoolean(ctx.opt.get("models-census", "true"));
        RefCensus census = censusOn && !clash.isEmpty() ? RefCensus.scan(user, van, clash.keySet()) : null;
        s.census = census;

        // ---------------------------------------------------------------- twins
        boolean dedup = Boolean.parseBoolean(ctx.opt.get("models-dedup", "true"));
        List<Integer> need = new ArrayList<>();
        ModelIndex index = dedup && !clash.isEmpty() ? ModelIndex.build(ctx.out) : null;
        for (var e : clash.entrySet()) {
            int id = e.getKey();
            int t = index == null ? -1 : index.twin(id, e.getValue());
            // a twin must be a model that stays where it is: not itself a moved user id. Several user models may share one
            // twin: the first is recorded, the others are aliases (IdMap.alias: many-to-one onto an existing id)
            if (t >= 0 && (clash.containsKey(t) || ctx.ids.moved(Kind.MODEL, t))) { s.count("twin.skipped"); t = -1; }
            if (t >= 0) {
                String why = clashWhy.get(id) + "; identical to " + (s.writtenAtId.contains(t) ? "user model " : "BASE model ") + t;
                if (ctx.ids.origin(Kind.MODEL, t) != -1) { ctx.ids.alias(Kind.MODEL, id, t, why); s.count("twin.shared"); }
                else ctx.ids.record(Kind.MODEL, id, t, why);
                s.moves.add(new Move(id, t, true, origins.get(id), why));
                s.count("twin");
                ctx.report.relocated(Kind.MODEL, id, t, why + refs(census, id));
            } else need.add(id);
        }

        // ---------------------------------------------------------------- allocation
        TreeMap<Integer, Integer> target = new TreeMap<>();
        String prev = ctx.opt.get("models-prev-idmap", "");
        if (rangeOk && !prev.isEmpty()) {
            IdMap old = IdMap.load(Path.of(prev));
            for (int id : need) {
                int p = old.lookup(Kind.MODEL, id);
                if (p >= s.lo && p <= s.hi && ctx.ids.isFree(Kind.MODEL, p) && !target.containsValue(p)) { target.put(id, p); s.count("sticky"); }
            }
        }
        List<Integer> rest = new ArrayList<>();
        for (int id : need) if (!target.containsKey(id)) rest.add(id);
        if (rangeOk && !rest.isEmpty()) {
            boolean top = !ctx.opt.get("models-pack", "top").equals("bottom");
            List<Integer> free = new ArrayList<>();
            if (top) {
                for (int x = s.hi; x >= s.lo && free.size() < rest.size(); x--) if (ctx.ids.isFree(Kind.MODEL, x) && !target.containsValue(x)) free.add(x);
                java.util.Collections.sort(free);
            } else {
                for (int x = s.lo; x <= s.hi && free.size() < rest.size(); x++) if (ctx.ids.isFree(Kind.MODEL, x) && !target.containsValue(x)) free.add(x);
            }
            for (int i = 0; i < rest.size() && i < free.size(); i++) target.put(rest.get(i), free.get(i));
            if (free.size() < rest.size())
                ctx.report.error(Kind.MODEL.key, Long.MIN_VALUE, "model range " + s.lo + "-" + s.hi + " is full: " + (rest.size() - free.size()) + " user models could not be moved (first " + rest.get(free.size()) + ")");
        }

        // ---------------------------------------------------------------- write moved models
        for (int id : need) {
            Integer to = target.get(id);
            if (to == null) { s.count("unplaced"); continue; }
            String why = clashWhy.get(id);
            ctx.ids.record(Kind.MODEL, id, to, why);
            put(ctx, id, to, clash.get(id));
            s.moves.add(new Move(id, to, false, origins.get(id), why));
            s.count("relocated");
            ctx.report.relocated(Kind.MODEL, id, to, why + refs(census, id));
        }

        // ---------------------------------------------------------------- reference report
        if (census != null) {
            for (var e : census.totals().entrySet()) ctx.report.count("refs." + e.getKey(), e.getValue());
            int unref = 0;
            for (int id : clash.keySet()) if (census.refCount(id) == 0) unref++;
            ctx.report.count("moved.unreferenced-in-user-cache", unref);
            s.counts.put("moved.unreferenced", unref);
            for (int id : clash.keySet()) {
                if (origins.get(id) != Origin.USER_CHANGED) continue;
                List<String> v = census.vanillaUsers(id);
                if (!v.isEmpty())
                    ctx.report.warn(Kind.MODEL.key, id, "the user edited vanilla model " + id + " (moved to " + ctx.ids.map(Kind.MODEL, id) + "), but " + v.size()
                            + " untouched vanilla entries use it and come from BASE, so they show BASE's model: " + String.join(", ", v.subList(0, Math.min(12, v.size()))));
            }
        }
        for (var e : s.counts.entrySet()) ctx.report.count(e.getKey(), e.getValue());
        ctx.log("%d user models: %d vanilla, %d same as BASE, %d written at their id, %d clash -> %d twins + %d relocated into %d-%d",
                s.count("user-models", 0), s.count("vanilla", 0), s.count("same.bytes", 0) + s.count("same.geometry", 0),
                s.count("written.same-id", 0), clash.size(), s.count("twin", 0), s.count("relocated", 0), s.lo, s.hi);
    }

    /** 241 bytes of a 211 model (null and an error if it cannot be converted without changing its geometry). */
    private static byte[] convert(Ctx ctx, Summary s, int id, byte[] src) {
        if (src == null || src.length < 2) { ctx.report.error(Kind.MODEL.key, id, "unreadable USER model"); s.count("convert.failed"); return null; }
        try {
            String kind = ModelConv.kind(src);
            byte[] c = ModelConv.toRev241(id, src, true);
            s.count("encoding." + kind.replace(' ', '_'));
            if (c != src) s.count("converted");
            return c;
        } catch (RuntimeException e) {
            ctx.report.error(Kind.MODEL.key, id, "not converted: " + e.getMessage());
            s.count("convert.failed");
            return null;
        }
    }

    private static void put(Ctx ctx, int from, int to, byte[] data) {
        int name = ctx.out.usesNames(Arch.MODELS) ? ctx.user().groupName(Arch.MODELS, from) : -1;
        ctx.out.putGroup(Arch.MODELS, GroupData.single(to, name, data.clone()));
    }

    private static String refs(RefCensus c, int id) {
        if (c == null) return "";
        String r = c.describe(id);
        return r.isEmpty() ? "; no USER config/interface references it" : "; used by " + r;
    }

    /** Appends id to a compact "a-b,c" range list (id -1 flushes). */
    private static void ranges(StringBuilder sb, int[] run, int id) {
        if (id >= 0 && id == run[1] + 1) { run[1] = id; return; }
        if (run[0] >= 0) { if (sb.length() > 0) sb.append(','); sb.append(run[0]); if (run[1] != run[0]) sb.append('-').append(run[1]); }
        run[0] = id; run[1] = id;
    }

    /** Writes a moves TSV (from, to, kind, origin, why, refs) for people reviewing a run. */
    public static void writeTsv(Summary s, Path file) throws java.io.IOException {
        StringBuilder sb = new StringBuilder("from\tto\tkind\torigin\twhy\tusers\n");
        for (Move m : s.moves)
            sb.append(m.from).append('\t').append(m.to).append('\t').append(m.twin ? "twin" : "relocated").append('\t').append(m.origin)
              .append('\t').append(m.why).append('\t').append(s.census == null ? "" : s.census.describe(m.from)).append('\n');
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb);
    }

    /** Counts per kind of reference, for a quick look. */
    static Map<String, Integer> moveKinds(Summary s) {
        TreeMap<String, Integer> m = new TreeMap<>();
        for (Move x : s.moves) m.merge((x.twin ? "twin." : "relocated.") + x.origin, 1, Integer::sum);
        return m;
    }
}

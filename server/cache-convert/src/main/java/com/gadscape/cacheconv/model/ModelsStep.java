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
 *   <li>vanilla (USER bytes == VAN211 bytes, or a rev-225 import: a Jagex model GadScape's DataMigration copied,
 *       {@link com.gadscape.cacheconv.core.Rev225}): BASE's model stays. If BASE lacks the id, the old copy is converted
 *       and written (so user content that still uses it keeps working). If BASE has a different model there and
 *       GadScape's own content (user-added/changed configs, interfaces, enums, struct params) uses the id, the
 *       vanilla configs that used the model are checked: if none of them still uses it in BASE, Jagex reused the id
 *       for something else (211 NPC torso 292 is the Cat mask in 241) or dropped the model, so the old copy is ported
 *       into the model range and only the GadScape references move to it (a vanilla port, {@link IdMap#markVanilla});
 *       otherwise Jagex updated the model in place and BASE's version is used.</li>
 *   <li>user-added / user-changed: converted to a 241 encoding ({@link ModelConv#toRev241}: header and trailer,
 *       face-depth tail, decode-equality check), then
 *     <ul>
 *       <li>id free in BASE: written at the same id;</li>
 *       <li>BASE has the same bytes or the same geometry at that id: BASE's model is used ("same");</li>
 *       <li>user-changed, and BASE still has the vanilla 211 model at that id (Jagex never changed it): the user's
 *           edit is written over it in place, as on 211, so the vanilla entries using it show the edit (option
 *           {@code models-changed=auto}, the default; {@code relocate} moves these too, {@code override} always
 *           writes in place). When BASE content added after 211 also uses the model (the user's Hooded Figure 38000
 *           is BASE's "No Crewmember" 15349 too), the edit moves instead and only the 211-era users follow it
 *           ({@code configs} rewrites them: {@link #FOLLOW});</li>
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
    /**
     * {@link Ctx#shared} key: {@code TreeMap<Integer, TreeSet<String>>} moved user edit of a vanilla model -> the BASE
     * configs ("group:id") that already used it in 211, which {@code configs} points at the moved edit.
     */
    public static final String FOLLOW = "models.follow";
    /** {@link Ctx#shared} key: {@code TreeSet<Integer>} vanilla models Jagex updated in place (BASE's version kept for GadScape content). */
    public static final String UPDATED = "models.jagex-updated";
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

    @SuppressWarnings("unchecked")
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
        BitSet vanillaLike = new BitSet();                       // vanilla 211 models and rev-225 imports BASE also has
        BitSet importModels = new BitSet();                      // the rev-225 imports among them
        TreeMap<Integer, byte[]> overrideCand = new TreeMap<>(); // user edits of vanilla models BASE never changed
        TreeMap<Integer, String> overrideWhy = new TreeMap<>();
        StringBuilder sameIds = new StringBuilder();
        int[] run = {-2, -2};
        for (int id = userIds.nextSetBit(0); id >= 0; id = userIds.nextSetBit(id + 1)) {
            Origin o = Origin.ofGroup(A, id, user, van);
            s.count("user-models");
            byte[] src = o == Origin.VANILLA ? null : user.single(A, id);
            boolean imported = src != null && ctx.rev225.isModelImport(id, src);
            if (o == Origin.VANILLA || imported) {
                s.count(imported ? "rev225-import" : "vanilla");
                if (ctx.out.hasGroup(A, id)) { vanillaLike.set(id); if (imported) importModels.set(id); continue; }
                byte[] conv = convert(ctx, s, id, src != null ? src : user.single(A, id));
                if (conv == null) continue;
                put(ctx, id, id, conv);
                s.writtenAtId.add(id);
                s.count((imported ? "rev225-import" : "vanilla") + ".missing-in-base.written");
                ctx.report.add(Decision.NOTE, Kind.MODEL, id, (imported ? "rev-225 import" : "vanilla 211 model") + " missing in BASE: wrote the converted copy");
                continue;
            }
            origins.put(id, o);
            s.count(o == Origin.USER_ADDED ? "user-added" : "user-changed");
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
                    // decided after the loop: written in place unless BASE content added after 211 uses the model too
                    overrideCand.put(id, conv);
                    overrideWhy.put(id, "differs from BASE's copy in " + why + (baseUnchanged ? "" : "; forced by --models-changed=override"));
                    continue;
                }
            }
            clash.put(id, conv);
            clashWhy.put(id, "BASE model " + id + " differs (" + why + ")");
        }
        ranges(sameIds, run, -1);
        user.release(A); van.release(A);

        // ---------------------------------------------------------------- vanilla models Jagex reused, edits BASE content shares
        RefCensus all = RefCensus.scan(user, van, null, (g, id) -> ctx.rev225.isImport(g, id, user.configRef(g, id)));
        TreeMap<Integer, byte[]> diverged = new TreeMap<>();
        for (int id : all.userModels()) {
            if (!vanillaLike.get(id)) continue;
            byte[] conv = convert(ctx, s, id, user.single(A, id));
            byte[] existing = ctx.out.single(A, id);
            if (conv == null || existing == null || java.util.Arrays.equals(conv, existing) || ModelConv.sameDecoded(id, conv, existing) == null) continue;
            diverged.put(id, conv);
        }
        user.release(A);
        java.util.TreeSet<Integer> watch = new java.util.TreeSet<>(diverged.keySet());
        watch.addAll(overrideCand.keySet());
        // the old users of a model: VAN211's configs for a vanilla 211 model, the imported configs for a rev-225 import
        java.util.TreeSet<Integer> watch211 = new java.util.TreeSet<>(), watch225 = new java.util.TreeSet<>();
        for (int id : watch) (importModels.get(id) ? watch225 : watch211).add(id);
        TreeMap<Integer, java.util.TreeSet<String>> baseUsers = configUsers(ctx.base, false, watch, null);
        TreeMap<Integer, java.util.TreeSet<String>> vanUsers = configUsers(van, true, watch211, null);
        vanUsers.putAll(configUsers(user, true, watch225, (g, id) -> ctx.rev225.isImport(g, id, user.configRef(g, id))));
        for (var e : diverged.entrySet()) {
            int id = e.getKey();
            java.util.TreeSet<String> vu = vanUsers.getOrDefault(id, new java.util.TreeSet<>()), bu = baseUsers.getOrDefault(id, new java.util.TreeSet<>());
            java.util.TreeSet<String> still = new java.util.TreeSet<>(vu);
            still.retainAll(bu);
            if (!still.isEmpty()) {
                s.count("vanilla-updated-by-jagex");
                ((java.util.TreeSet<Integer>) ctx.shared.computeIfAbsent(UPDATED, x -> new java.util.TreeSet<Integer>())).add(id);
                ctx.report.add(Decision.TAKEN_FROM_BASE, Kind.MODEL, id, "vanilla model Jagex updated in place (BASE still uses it for " + still.size() + " of its " + vu.size()
                        + " old users): GadScape content using it (" + all.describe(id) + ") shows BASE's version");
                continue;
            }
            String why = "vanilla model " + id + (bu.isEmpty() ? ": no BASE config uses it any more" : ": BASE reused the id for other content (" + abbreviate(bu) + ")")
                    + (vu.isEmpty() ? "" : ", none of its " + vu.size() + " old users (" + abbreviate(vu) + ") uses it in BASE") + "; the old copy is ported for GadScape content";
            clash.put(id, e.getValue());
            clashWhy.put(id, why);
            origins.put(id, Origin.VANILLA);
            s.count("vanilla-reused-ported");
        }
        TreeMap<Integer, java.util.TreeSet<String>> follow = new TreeMap<>();
        for (var e : overrideCand.entrySet()) {
            int id = e.getKey();
            java.util.TreeSet<String> baseOnly = new java.util.TreeSet<>(baseUsers.getOrDefault(id, new java.util.TreeSet<>()));
            baseOnly.removeAll(vanUsers.getOrDefault(id, new java.util.TreeSet<>()));
            if (baseOnly.isEmpty() || changedMode.equals("override")) {
                put(ctx, id, id, e.getValue());
                s.writtenAtId.add(id);
                s.count("user-changed.override");
                ctx.report.add(Decision.USER_CHANGED, Kind.MODEL, id, "user edit of a vanilla model that BASE still has unchanged since 211: written over BASE's copy ("
                        + overrideWhy.get(id) + "), so vanilla entries using it show the edit as on 211");
                continue;
            }
            java.util.TreeSet<String> old = new java.util.TreeSet<>(baseUsers.getOrDefault(id, new java.util.TreeSet<>()));
            old.retainAll(vanUsers.getOrDefault(id, new java.util.TreeSet<>()));
            clash.put(id, e.getValue());
            clashWhy.put(id, "user edit of vanilla model " + id + " (" + overrideWhy.get(id) + "), but BASE content added after 211 uses the model too (" + abbreviate(baseOnly)
                    + "): the edit moves, the " + old.size() + " 211-era users follow it (" + abbreviate(old) + ")");
            if (!old.isEmpty()) follow.put(id, old);
            s.count("user-changed.base-only-users.moved");
        }
        if (!follow.isEmpty()) ctx.shared.put(FOLLOW, follow);
        if (sameIds.length() > 0) ctx.report.add(Decision.SAME, Kind.MODEL.key, Long.MIN_VALUE, null,
                "user models equal to BASE's at the same id (BASE kept): " + sameIds);
        s.counts.put("clash", clash.size());

        // ---------------------------------------------------------------- who references the clashing models
        boolean censusOn = Boolean.parseBoolean(ctx.opt.get("models-census", "true"));
        RefCensus census = censusOn && !clash.isEmpty() ? all : null;
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
                if (origins.get(id) == Origin.VANILLA) ctx.ids.markVanilla(Kind.MODEL, id);
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
            if (origins.get(id) == Origin.VANILLA) ctx.ids.markVanilla(Kind.MODEL, id);
            put(ctx, id, to, clash.get(id));
            s.moves.add(new Move(id, to, false, origins.get(id), why));
            s.count("relocated");
            ctx.report.relocated(Kind.MODEL, id, to, why + refs(census, id));
        }

        // ---------------------------------------------------------------- reference report
        if (census != null) {
            int userRefs = 0;
            for (int id : clash.keySet()) userRefs += census.refCount(id);
            ctx.report.count("refs.user-origin-entries-using-moved-models", userRefs);
            int unref = 0;
            for (int id : clash.keySet()) if (census.refCount(id) == 0) unref++;
            ctx.report.count("moved.unreferenced-in-user-cache", unref);
            s.counts.put("moved.unreferenced", unref);
            for (int id : clash.keySet()) {
                if (origins.get(id) != Origin.USER_CHANGED) continue;
                List<String> v = census.vanillaUsers(id);
                if (follow.containsKey(id)) {
                    // the 211-era vanilla users follow the moved edit (configs rewrites them), BASE's newer users keep BASE's model
                    ctx.report.add(Decision.NOTE, Kind.MODEL, id, "user edit of vanilla model " + id + " moved to " + ctx.ids.map(Kind.MODEL, id) + "; its 211-era vanilla users "
                            + abbreviate(follow.get(id)) + " point at the edit (configs), BASE content added after 211 keeps BASE's model");
                    continue;
                }
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

    /**
     * Model id -> configs ("group:id", obj/npc/loc/spotanim/identkit) of {@code c} that reference it, for the watched
     * models; {@code is211} converts entries first; {@code only} (config group, id) limits the entries scanned.
     */
    static TreeMap<Integer, java.util.TreeSet<String>> configUsers(com.gadscape.cacheconv.core.CacheView c, boolean is211, java.util.Set<Integer> watch,
                                                                    java.util.function.BiPredicate<Integer, Integer> only) {
        TreeMap<Integer, java.util.TreeSet<String>> r = new TreeMap<>();
        if (watch.isEmpty()) return r;
        for (int g : new int[]{Arch.CFG_OBJ, Arch.CFG_NPC, Arch.CFG_LOC, Arch.CFG_SPOT, Arch.CFG_IDENTKIT}) {
            com.gadscape.cacheconv.convert.ConfigConv.Fn fn = is211 ? com.gadscape.cacheconv.convert.ConfigConv.forGroup(g) : null;
            for (int id : c.configIds(g)) {
                if (only != null && !only.test(g, id)) continue;
                byte[] b = c.configRef(g, id);
                if (b == null) continue;
                int[] ms;
                try { ms = ModelRefs.models(g, id, fn == null ? b : fn.apply(id, b), false); }
                catch (RuntimeException e) { continue; }
                for (int m : ms) if (watch.contains(m)) r.computeIfAbsent(m, k -> new java.util.TreeSet<>()).add(Arch.configName(g) + ":" + id);
            }
        }
        c.release(Arch.CONFIGS);
        return r;
    }

    static String abbreviate(java.util.Collection<String> l) {
        List<String> x = new ArrayList<>(l);
        return x.size() <= 6 ? String.join(", ", x) : String.join(", ", x.subList(0, 6)) + ", ... (" + x.size() + ")";
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

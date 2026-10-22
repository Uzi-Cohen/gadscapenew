package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.config.CfgCodec.Entry;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Step;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * configs-plan: decides which USER config entries move to a reserved GadScape range and records them in the id map,
 * without writing anything. It runs early (after {@code models}, before {@code anim}, the UI steps and
 * {@link ConfigStep}) so that every later step rewrites its references to these ids (seq hand objs, clientscript
 * operands and constants, interface hooks and var triggers, map files, the server).
 * <p>
 * Per USER entry that the user added or changed (ascending ids, groups in ascending order):
 * <ul>
 *   <li><b>Entity configs</b> (obj, npc, loc, spotanim): moved only when BASE added the id after OLD241 (Raids 4: objs
 *       34646-34731) and the USER entry is not the same thing ({@link CfgModel#same}); every other clash keeps the
 *       USER entry at its id (reported by {@link ConfigStep}). Locs that BASE map squares place are the maps area's
 *       decision ({@link LocWriter#plan}); ids it already moved are left alone.</li>
 *   <li><b>UI configs</b> (enum, struct, param, varbit, varp, varc, dbrow, dbtable), which BASE's scripts and
 *       interfaces read: a user-added entry at an id BASE uses for something else moves (varps and varcs always:
 *       their definitions are near-empty, the id is the identity); a user-changed enum whose key/value types no
 *       longer match BASE's moves; a user-changed param whose type differs from BASE's moves; a user-changed varbit or
 *       dbrow whose BASE version Jagex also changed moves (a different bit layout / table).</li>
 *   <li><b>GadScape variables without a USER definition</b>: varps and varcs that the user's own scripts, the user's
 *       varbits and configs, or the GadScape server ({@code config/server-varps.txt}) use, that vanilla 211 and USER
 *       do not define but BASE does (BASE's scripts read them: for example varps 4204/4205/4507 and varcs 1151,
 *       1155-1159, 1500 of the GadScape scripts 12544-12585 and 32000-32044, varp 4702 under the user varbits
 *       17007/17008), move to the reserved range. Varbits in the same situation cannot move (there is no definition
 *       to port): they are reported.</li>
 *   <li><b>Protected 2746 additions held by vanilla 211 entries</b>: where BASE added an id after OLD241 and the USER
 *       cache holds a different vanilla 211 entry there (the 46 raid structs 1216-1232, 1234, 1746-1767, 1769-1774,
 *       which are 211 settings structs), the 211 copy moves to the reserved range, so the 211 content that refers to
 *       it (enum 2730, which BASE no longer has and {@link ConfigStep} ports) keeps pointing at the data it was made
 *       for.</li>
 *   <li><b>Other vanilla entries Jagex replaced with a different thing</b> (an enum BASE re-typed): the VAN211 copy
 *       moves when user content or a ported vanilla entry references it (and transitively). Vanilla entries Jagex
 *       merely updated stay BASE's.</li>
 * </ul>
 * Reserved ranges: the spec's (struct 13000-13999, enum 20000-20999, param 9000-9999, varbit 30000-34999, varp
 * 9000-9999, dbrow 60000-64999), plus obj 60000-64999 (obj ids are u16 in inventories, cert links and seq hand
 * slots; hand slots allow at most 65023) and varc 2016-2047 (the 241 client sizes its varc table from the config-19
 * file count; {@link ConfigStep} keeps plain entries up to 2047). npc/spotanim/dbtable/loc ranges are set with
 * {@code --configs.range.KIND=LO-HI} when needed; a needed range that is missing is an error.
 */
public final class ConfigPlanStep implements Step {
    public static final String NAME = "configs-plan";

    /** Defaults for kinds without a spec range (obj ids must stay u16-safe, hand objs below 65024; varcs below 2048). */
    public static final Map<Kind, int[]> EXTRA_RANGES = Map.of(Kind.OBJ, new int[]{60000, 64999}, Kind.VARC, new int[]{2016, 2047});

    /** 211 clientscript opcodes whose operand is a variable id: push/pop varp, varbit, varc int, varc string. */
    static Kind varOperand(int op) {
        return switch (op) {
            case 1, 2 -> Kind.VARP;
            case 25, 27 -> Kind.VARBIT;
            case 42, 43, 49, 50 -> Kind.VARC; // 47/48 are the old varcstr ops (config 15, empty in USER)
            default -> null;
        };
    }

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) {
        CfgModel m = new CfgModel(ctx);
        setRanges(ctx);
        Plan p = new Plan(ctx, m);
        p.serverVarps();  // first: reserves the server's varp ids before anything allocates varps
        p.scanScripts();
        p.standIns();
        for (int g : m.groups()) {
            Kind k = CfgModel.kindOf(g);
            if (k == null || !CfgCodec.walked(g) && k != Kind.VARP && k != Kind.VARC) continue;
            p.group(g, k);
        }
        p.portedVanillaRefs();
        p.implicitVars();
        p.protectedVanilla();
        p.vanillaReplaced();
        for (var e : p.counts.entrySet()) ctx.report.count(e.getKey(), e.getValue());
        for (Kind k : new Kind[]{Kind.OBJ, Kind.NPC, Kind.SPOT, Kind.LOC, Kind.ENUM, Kind.STRUCT, Kind.PARAM, Kind.VARBIT, Kind.VARP, Kind.VARC, Kind.DBROW, Kind.DBTABLE})
            if (!ctx.ids.all(k).isEmpty()) ctx.log("%s: %d relocated (total in id map)", k.key, ctx.ids.all(k).size());
    }

    /** Applies the extra default ranges and every --configs.range.KIND=LO-HI option. */
    static void setRanges(Ctx ctx) {
        for (Kind k : new Kind[]{Kind.OBJ, Kind.VARC}) {
            int[] r = EXTRA_RANGES.get(k);
            if (ctx.ids.range(k) == null) ctx.ids.setRange(k, r[0], r[1]);
        }
        for (Kind k : Kind.values()) {
            String r = ctx.opt.get("configs.range." + k.key, null);
            if (r == null) continue;
            String[] lh = r.split("-");
            ctx.ids.setRange(k, Integer.parseInt(lh[0].trim()), Integer.parseInt(lh[1].trim()));
        }
        IdMap.Range or = ctx.ids.range(Kind.OBJ);
        if (or != null && or.hi() > 65023) ctx.report.warn("obj", Long.MIN_VALUE, "obj range " + or.lo() + "-" + or.hi() + " exceeds 65023: such objs cannot be seq hand objs");
    }

    static final class Plan {
        final Ctx ctx;
        final CfgModel m;
        final boolean portRemovedVanilla;
        final TreeMap<String, Long> counts = new TreeMap<>();
        /** References to struct/enum ids from USER-origin entries and ported vanilla entries (for the vanilla passes). */
        final TreeMap<Kind, TreeSet<Integer>> userRefs = new TreeMap<>();
        /** Variable ids (varp, varbit, varc) GadScape content uses, with who uses them (for implicitVars and reports). */
        final TreeMap<Kind, TreeMap<Integer, TreeSet<String>>> varUse = new TreeMap<>();

        Plan(Ctx ctx, CfgModel m) {
            this.ctx = ctx; this.m = m;
            this.portRemovedVanilla = Boolean.parseBoolean(ctx.opt.get("configs.portRemovedVanilla", "true"));
        }

        void count(String k) { counts.merge(k, 1L, Long::sum); }

        void use(Kind k, int id, String by) {
            if (id < 0) return;
            varUse.computeIfAbsent(k, x -> new TreeMap<>()).computeIfAbsent(id, x -> new TreeSet<>()).add(by);
        }

        void group(int g, Kind k) {
            String gn = Arch.configName(g);
            for (int id : m.user.configIds(g)) {
                Origin o = m.origin(g, id);
                if (!o.isUser()) continue;
                if (ctx.rev225.config(g, id, m.user.configRef(g, id)) != null) { count(gn + ".rev225-import-or-edit"); continue; } // Jagex content: BASE's entry (ConfigStep)
                Entry u;
                try { u = m.userEntry(g, id); }
                catch (RuntimeException e) { ctx.report.error(k.key, id, "USER entry does not decode in the 241 layout: " + e.getMessage()); continue; }
                if (ctx.ids.moved(k, id)) { collectRefs(u, gn + " " + id); count(gn + ".already-moved"); continue; }
                Entry b;
                try { b = m.baseEntry(g, id); }
                catch (RuntimeException e) { ctx.report.error(k.key, id, "BASE entry does not decode: " + e.getMessage()); continue; }
                // the references of entries that resolve to BASE's own entry (Jagex content the user imported at its
                // Jagex id) are BASE's, not GadScape usage
                if (b == null || !(CfgModel.isEntity(k) || o == Origin.USER_ADDED) || !CfgModel.same(g, u, CfgCodec.map(u, m.mapper()), b).same())
                    collectRefs(u, gn + " " + id);
                else count(gn + ".same-as-base-refs-ignored");
                if (b == null) continue;
                String why = reason(g, k, id, o, u, b);
                if (why == null) continue;
                relocate(k, id, why);
                count(gn + ".relocated");
            }
            m.user.release(Arch.CONFIGS);
        }

        /** Why the USER entry at a BASE id must move, or null if it stays (same thing / kept clash / merge). */
        String reason(int g, Kind k, int id, Origin o, Entry u, Entry b) {
            Entry um = CfgCodec.map(u, m.mapper());
            if (CfgModel.isEntity(k)) {
                if (!m.newInBase(g, id)) return null;
                if (u.items.isEmpty()) return null; // an empty USER placeholder: BASE's entry stays (ConfigStep)
                CfgModel.Same s = CfgModel.same(g, u, um, b);
                if (s.same()) return null;
                return "BASE added " + Arch.configName(g) + " " + id + " after OLD241 (" + describe(g, b) + "); USER has " + describe(g, u);
            }
            if (!CfgModel.isUiKind(k)) return null;
            if (o == Origin.USER_ADDED) {
                if (k == Kind.VARP || k == Kind.VARC)
                    return "user " + k.key + " " + id + " is a BASE " + k.key + " (BASE scripts read it; plain definitions cannot tell the two apart)";
                CfgModel.Same s = CfgModel.same(g, u, um, b);
                if (s.same()) return null;
                return "user " + Arch.configName(g) + " clashes with BASE's (" + Arch.configName(g) + "s are read by BASE scripts/UI)";
            }
            // USER_CHANGED
            Entry v = m.vanEntry(g, id);
            boolean jagexChanged = !CfgModel.sameEffective(g, v, b);
            switch (k) {
                case ENUM:
                    // a user enum with BASE's key/value types is the same enum, however much of its content the user
                    // replaced (Diango's holiday list 708: vanilla scripts 450/474 and the server read it): ConfigStep
                    // merges it (spec rule 10)
                    if (EnumMerge.incompatible(u, b)) return "user re-typed vanilla enum " + id + " (" + types(u) + " vs BASE " + types(b) + ")";
                    return null;
                case PARAM:
                    if (m.intRetyped.contains(id)) { count("param.int-retype-kept-at-id"); return null; } // param 690: same param, BASE's type
                    if (ParamTypeTable.typeOf(u.encode()) != ParamTypeTable.typeOf(b.encode())) return "user changed the type of vanilla param " + id;
                    return null;
                case VARBIT:
                case DBROW:
                    return jagexChanged ? "user and Jagex both changed vanilla " + Arch.configName(g) + " " + id : null;
                default:
                    return null;
            }
        }

        void relocate(Kind k, int id, String why) {
            int to = ctx.ids.relocate(k, id, why);
            ctx.report.relocated(k, id, to, why);
        }

        void collectRefs(Entry e, String by) {
            for (CfgCodec.Ref r : e.allRefs()) {
                if (r.none() || r.kind() == null) continue;
                if (r.kind() == Kind.STRUCT || r.kind() == Kind.ENUM) userRefs.computeIfAbsent(r.kind(), x -> new TreeSet<>()).add(r.value());
                if (r.kind() == Kind.VARP || r.kind() == Kind.VARBIT) use(r.kind(), r.value(), by);
            }
        }

        // ---------------------------------------------------------------- stand-ins

        /**
         * GadScape stand-ins for content BASE now has natively: a user-added struct whose string params (its name)
         * equal those of a struct Jagex added after 211, and whose param keys BASE's struct also has (GadScape's
         * collection log categories 10500-10503 for Duke Sucellus, Vardorvis, the Tormented Demons and Araxxor, which
         * BASE has as structs 4652, 4653, ... with their own item lists). The stand-in is mapped onto BASE's struct
         * ({@link IdMap#alias}): it is not written, references to it (the server's collection log code) mean BASE's
         * category, and enum merges do not add it again next to BASE's ({@link ConfigStep}). Exactly one BASE match is
         * required; {@code --configs.standIns=false} turns this off.
         */
        void standIns() {
            if (!Boolean.parseBoolean(ctx.opt.get("configs.standIns", "true"))) return;
            int g = Arch.CFG_STRUCT;
            TreeMap<String, List<Integer>> byString = new TreeMap<>();
            TreeMap<Integer, java.util.Map<Integer, String>> baseStrings = new TreeMap<>();
            TreeMap<Integer, java.util.Set<Integer>> baseKeys = new TreeMap<>();
            for (int id : m.base.configIds(g)) {
                byte[] vb = m.van.configRef(g, id);
                if (vb != null && Arrays.equals(vb, m.base.configRef(g, id))) continue; // only content Jagex added (or reused the id for) after 211
                java.util.Map<Integer, String> str = new TreeMap<>();
                java.util.Set<Integer> keys = new TreeSet<>();
                if (!structParams(m.base.configRef(g, id), str, keys) || str.isEmpty()) continue;
                baseStrings.put(id, str);
                baseKeys.put(id, keys);
                for (var e : str.entrySet()) byString.computeIfAbsent(e.getKey() + "=" + e.getValue(), x -> new ArrayList<>()).add(id);
            }
            for (int id : m.user.configIds(g)) {
                if (m.origin(g, id) != Origin.USER_ADDED || ctx.ids.moved(Kind.STRUCT, id)) continue;
                java.util.Map<Integer, String> str = new TreeMap<>();
                java.util.Set<Integer> keys = new TreeSet<>();
                if (!structParams(m.user.configRef(g, id), str, keys) || str.isEmpty()) continue;
                TreeSet<Integer> cand = null;
                for (var e : str.entrySet()) {
                    TreeSet<Integer> c = new TreeSet<>(byString.getOrDefault(e.getKey() + "=" + e.getValue(), List.of()));
                    if (cand == null) cand = c; else cand.retainAll(c);
                }
                if (cand == null) continue;
                cand.removeIf(b -> !baseKeys.get(b).containsAll(keys) || !baseStrings.get(b).equals(str));
                if (cand.size() != 1) { if (cand.size() > 1) ctx.report.note("struct " + id + ": " + cand.size() + " BASE structs share its strings " + str + " (" + cand + "); not treated as a stand-in"); continue; }
                int b = cand.first();
                String why = "GadScape stand-in for BASE struct " + b + " (same " + str.values() + ", BASE has every param key of it): BASE's native struct is used";
                ctx.ids.alias(Kind.STRUCT, id, b, why);
                ctx.report.relocated(Kind.STRUCT, id, b, why);
                count("struct.stand-in-mapped-to-base");
            }
        }

        /** String params and all param keys of a struct entry (either layout); false if it does not parse. */
        static boolean structParams(byte[] b, java.util.Map<Integer, String> strings, java.util.Set<Integer> keys) {
            if (b == null) return false;
            try {
                com.gadscape.cacheconv.core.Buf in = new com.gadscape.cacheconv.core.Buf(b);
                while (true) {
                    int op = in.u8();
                    if (op == 0) return true;
                    if (op != 249) return false;
                    int n = in.u8();
                    for (int i = 0; i < n; i++) {
                        int str = in.u8(), key = in.u24();
                        keys.add(key);
                        if (str == 1) strings.put(key, in.str());
                        else if (str == 2) in.skip(8);
                        else in.skip(4);
                    }
                }
            } catch (RuntimeException e) {
                return false;
            }
        }

        // ---------------------------------------------------------------- GadScape variables

        /** Variable operands of the user's own and user-modified clientscripts (211 opcodes). */
        void scanScripts() {
            if (!m.user.hasArchive(Arch.SCRIPTS)) return;
            int n = 0, bad = 0;
            for (int id : m.user.groupIds(Arch.SCRIPTS)) {
                if (!Origin.ofGroup(Arch.SCRIPTS, id, m.user, m.van).isUser()) continue;
                byte[] b = m.user.single(Arch.SCRIPTS, id);
                if (b == null) continue;
                com.gadscape.cacheconv.convert.Cs2.Script s;
                try { s = com.gadscape.cacheconv.convert.Cs2.parse211(b); }
                catch (RuntimeException e) { bad++; continue; }
                n++;
                for (com.gadscape.cacheconv.convert.Cs2.Ins in : s.code) {
                    Kind k = varOperand(in.op);
                    if (k != null) use(k, in.i, "script " + id);
                }
            }
            m.user.release(Arch.SCRIPTS);
            counts.merge("scripts.scanned-for-vars", (long) n, Long::sum);
            if (bad > 0) ctx.report.warn("script", Long.MIN_VALUE, bad + " user scripts did not parse as 211 scripts; their variables were not checked");
        }

        /**
         * Varps and varcs GadScape uses that neither vanilla 211 nor USER defines: those BASE defines move to the
         * reserved range (BASE's scripts and varbits use the id for their own state); the others stay and get plain
         * definitions from {@link ConfigStep}. Varbits in that situation are reported.
         */
        void implicitVars() {
            for (Kind k : new Kind[]{Kind.VARP, Kind.VARC, Kind.VARBIT}) {
                int g = k.configGroup;
                List<String> notMovable = new ArrayList<>(), imports = new ArrayList<>();
                for (var e : varUse.getOrDefault(k, new TreeMap<>()).entrySet()) {
                    int id = e.getKey();
                    if (m.van.hasConfig(g, id) || m.user.hasConfig(g, id) || ctx.ids.moved(k, id)) continue;
                    String by = abbreviate(e.getValue());
                    if (!m.base.hasConfig(g, id)) { count(k.key + ".gadscape-undefined-kept"); continue; }
                    if (k == Kind.VARBIT) {
                        boolean code = e.getValue().stream().anyMatch(x -> x.startsWith("script") || x.startsWith("server"));
                        (code ? notMovable : imports).add(id + " (" + by + ")");
                        count(code ? "varbit.gadscape-undefined-is-base" : "varbit.import-undefined-is-base");
                        continue;
                    }
                    relocate(k, id, "GadScape " + k.key + " " + id + " has no USER definition (used by " + by + "); BASE defines " + k.key + " " + id
                            + " for its own scripts" + (k == Kind.VARP ? "/varbits" : ""));
                    count(k.key + ".gadscape-undefined-relocated");
                }
                if (!notMovable.isEmpty())
                    ctx.report.warn("varbit", Long.MIN_VALUE, notMovable.size() + " varbits that GadScape scripts or the server use are defined by neither vanilla 211 nor USER but by BASE,"
                            + " so GadScape reads/writes BASE's varbit there (there is no USER definition to relocate; give them GadScape definitions in the user cache and re-run): " + notMovable);
                if (!imports.isEmpty())
                    ctx.report.note(imports.size() + " varbits referenced only by user locs/npcs (Jagex content imported at Jagex ids, kept as clashes) are defined by neither vanilla 211 nor USER but by BASE;"
                            + " those configs now use BASE's varbit, which is most likely the one they were imported for: " + imports);
            }
        }

        /** Varps the GadScape server uses: all reserved (never allocated); their use is checked by {@link #implicitVars}. */
        void serverVarps() {
            for (int id : serverVarpIds(ctx)) {
                ctx.ids.reserve(Kind.VARP, id);
                use(Kind.VARP, id, "server");
            }
        }

        // ---------------------------------------------------------------- vanilla entries

        /** References inside vanilla entries BASE no longer has (ConfigStep ports them at their ids). */
        void portedVanillaRefs() {
            if (!portRemovedVanilla) return;
            for (int g : m.groups()) {
                if (!CfgCodec.walked(g)) continue;
                for (int id : m.van.configIds(g)) {
                    if (m.base.hasConfig(g, id) || m.origin(g, id) != Origin.VANILLA) continue;
                    try { collectRefs(m.vanEntry(g, id), "ported vanilla " + Arch.configName(g) + " " + id); }
                    catch (RuntimeException ignored) { /* ConfigStep reports entries that do not decode */ }
                }
                m.van.release(Arch.CONFIGS);
            }
        }

        /**
         * Spec policy 3, ids 2746 added over 2740: where BASE added an id after OLD241 and USER holds a different
         * vanilla 211 entry there (the 46 raid structs), the 211 copy moves to the reserved range, whatever refers to
         * it (the id map is the record of which 211-era content it was).
         */
        void protectedVanilla() {
            if (m.old241 == null) { ctx.report.warn("protected vanilla ids not checked: no OLD241 given"); return; }
            Deque<long[]> work = new ArrayDeque<>();
            for (int g : m.groups()) {
                Kind k = CfgModel.kindOf(g);
                if (k == null || !CfgModel.isUiKind(k) || !CfgCodec.walked(g)) continue;
                for (int id : m.user.configIds(g)) {
                    if (!m.newInBase(g, id) || m.origin(g, id) != Origin.VANILLA || ctx.ids.moved(k, id)) continue;
                    Entry v = m.vanEntry(g, id), b = m.baseEntry(g, id);
                    if (CfgModel.sameEffective(g, v, b)) continue;
                    relocate(k, id, "protected: BASE added " + Arch.configName(g) + " " + id + " after OLD241 (Raids 4) for different content; the vanilla 211 "
                            + Arch.configName(g) + " the USER cache holds there moves");
                    count(Arch.configName(g) + ".protected-vanilla-relocated");
                    for (CfgCodec.Ref r : v.allRefs())
                        if (!r.none() && (r.kind() == Kind.STRUCT || r.kind() == Kind.ENUM)) work.add(new long[]{r.kind().ordinal(), r.value()});
                }
                m.user.release(Arch.CONFIGS);
            }
            while (!work.isEmpty()) {
                long[] w = work.poll();
                userRefs.computeIfAbsent(Kind.values()[(int) w[0]], x -> new TreeSet<>()).add((int) w[1]);
            }
        }

        /**
         * Vanilla structs/enums that BASE replaced with different content but user content (or a ported vanilla entry)
         * still references: the VAN211 copy moves to the reserved range (and whatever it references in turn).
         */
        void vanillaReplaced() {
            Deque<long[]> work = new ArrayDeque<>();
            for (var e : userRefs.entrySet()) for (int id : e.getValue()) work.add(new long[]{e.getKey().ordinal(), id});
            TreeSet<String> done = new TreeSet<>();
            while (!work.isEmpty()) {
                long[] w = work.poll();
                Kind k = Kind.values()[(int) w[0]];
                int id = (int) w[1];
                if (!done.add(k.key + id)) continue;
                int g = k.configGroup;
                if (m.origin(g, id) != Origin.VANILLA || ctx.ids.moved(k, id)) continue;
                Entry b = m.baseEntry(g, id);
                if (b == null) continue; // deleted from BASE: ConfigStep ports the vanilla copy at its own id
                Entry v = m.vanEntry(g, id);
                if (CfgModel.sameEffective(g, v, b)) continue;
                // only a different thing at the id counts (Jagex deleted the vanilla entry and reused the id, or an
                // enum changed its key/value types); ordinary Jagex updates of the same entry are BASE's to make
                boolean reused = m.old241 != null && !m.old241.hasConfig(g, id);
                boolean retyped = k == Kind.ENUM && EnumMerge.incompatible(v, b);
                if (!reused && !retyped) { count(Arch.configName(g) + ".vanilla-updated-by-base-referenced"); continue; }
                relocate(k, id, "vanilla 211 " + Arch.configName(g) + " " + id + (reused ? " deleted by Jagex and the id reused in BASE" : " re-typed in BASE")
                        + "; referenced by user content, so the 211 copy moves");
                count(Arch.configName(g) + ".vanilla-replaced-ported");
                for (CfgCodec.Ref r : v.allRefs())
                    if (!r.none() && (r.kind() == Kind.STRUCT || r.kind() == Kind.ENUM)) work.add(new long[]{r.kind().ordinal(), r.value()});
            }
        }

        static String types(Entry e) {
            int[] t = CfgCodec.enumTypes(e);
            return (t[0] < 0 ? "-" : String.valueOf((char) t[0])) + "->" + (t[1] < 0 ? "-" : String.valueOf((char) t[1]));
        }
    }

    static String abbreviate(java.util.Collection<String> s) {
        List<String> l = new ArrayList<>(s);
        return l.size() <= 6 ? String.join(", ", l) : String.join(", ", l.subList(0, 6)) + ", ... (" + l.size() + ")";
    }

    /** The server varp list: --configs.serverVarps (ids/ranges, "none") or the bundled config/server-varps.txt. */
    static TreeSet<Integer> serverVarpIds(Ctx ctx) {
        String spec = ctx.opt.get("configs.serverVarps", null);
        if (spec == null) {
            try (var in = ConfigPlanStep.class.getResourceAsStream("/config/server-varps.txt")) {
                if (in == null) throw new IllegalStateException("resource config/server-varps.txt missing");
                StringBuilder sb = new StringBuilder();
                for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                    int h = line.indexOf('#');
                    String t = (h >= 0 ? line.substring(0, h) : line).trim();
                    if (!t.isEmpty()) sb.append(t).append(',');
                }
                spec = sb.toString();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return parseIdList(spec);
    }

    /** "1,5-7, 9" -> {1,5,6,7,9}; "none" or "" -> {}. */
    static TreeSet<Integer> parseIdList(String spec) {
        TreeSet<Integer> ids = new TreeSet<>();
        if (spec == null || spec.equals("none")) return ids;
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int d = part.indexOf('-');
            int lo = Integer.parseInt(d < 0 ? part : part.substring(0, d).trim()), hi = d < 0 ? lo : Integer.parseInt(part.substring(d + 1).trim());
            for (int i = lo; i <= hi; i++) ids.add(i);
        }
        return ids;
    }

    /** Short description of an entity entry for reports: its name and first model. */
    static String describe(int g, Entry e) {
        var models = CfgCodec.refsOf(e, Kind.MODEL);
        if (g == Arch.CFG_SPOT) {
            var seqs = CfgCodec.refsOf(e, Kind.SEQ);
            return "spotanim" + (models.isEmpty() ? "" : " model " + models.get(0)) + (seqs.isEmpty() ? "" : " seq " + seqs.get(0));
        }
        String n = CfgCodec.name(e);
        return "'" + (n == null ? "null" : n) + "'" + (models.isEmpty() ? "" : " model " + models.get(0));
    }
}

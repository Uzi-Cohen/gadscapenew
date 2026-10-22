package com.gadscape.cacheconv.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Everything a {@link Step} works with: the read-only inputs, the output cache, the id map, the report and options.
 * <ul>
 *   <li>{@link #base} stock 241 (2746): the foundation, every archive starts as a copy of it</li>
 *   <li>{@link #user} GadScape's 211 cache; {@link #van211} vanilla 211 (what the user started from)</li>
 *   <li>{@link #old241} older stock 241 (2740, what cacheU was built on; may be null)</li>
 *   <li>{@link #out} the cache being built (BASE copy + pending changes); null for read-only commands</li>
 * </ul>
 * Inputs are opened with mgi {@code Cache.openCache(dir, true)} and never written.
 */
public final class Ctx implements AutoCloseable {
    public final Options opt;
    public final Report report;
    public final IdMap ids;
    public final JagCache base, user, van211, old241;
    public final OutCache out;
    /**
     * Plans one step hands to a later step of the same run that do not belong in the id map (not persisted), keyed by
     * a name the two steps agree on, e.g. {@code "models.follow"}.
     */
    public final java.util.Map<String, Object> shared = new java.util.TreeMap<>();
    /** The rev-225 import table ({@link Rev225}): Jagex entries GadScape imported, treated as vanilla. */
    public final Rev225 rev225;
    private Xteas xteas;
    private JagCache ref;

    private Ctx(Options opt, Report report, IdMap ids, JagCache base, JagCache user, JagCache van211, JagCache old241, OutCache out) {
        this.opt = opt; this.report = report; this.ids = ids;
        this.base = base; this.user = user; this.van211 = van211; this.old241 = old241; this.out = out;
        this.rev225 = Rev225.load(opt);
    }

    /**
     * Opens the inputs that exist (BASE is required) and, with {@code withOut}, creates the output cache as a copy of
     * BASE in {@code opt.out} (or a dry-run view). The model range of the id map is set from BASE
     * ({@link Kind#modelRange}).
     */
    public static Ctx open(Options o, boolean withOut) throws IOException {
        o.require("base", o.base);
        Report report = new Report();
        report.input("options", o.describe());
        JagCache base = JagCache.open("BASE", o.base);
        JagCache user = o.user != null && Files.isDirectory(o.user) ? JagCache.open("USER", o.user) : null;
        JagCache van = o.van211 != null && Files.isDirectory(o.van211) ? JagCache.open("VAN211", o.van211) : null;
        JagCache old = o.old241 != null && Files.isDirectory(o.old241) ? JagCache.open("OLD241", o.old241) : null;
        OutCache out = null;
        if (withOut) {
            if (o.out == null) throw new IllegalArgumentException("--out is required");
            out = OutCache.create(o.base, o.out, o.protectedDirs(), o.force, o.dryRun, report);
        }
        IdMap ids = new IdMap();
        IdMap.Range models = Kind.modelRange(base);
        if (models != null) ids.setRange(Kind.MODEL, models.lo(), models.hi());
        if (user != null && out != null) ids.occupancy(base, user, out);
        else if (user != null) ids.occupancy(base, user);
        else ids.occupancy(base);
        return new Ctx(o, report, ids, base, user, van, old, out);
    }

    /** USER, or an exception saying --user is needed. */
    public JagCache user() { if (user == null) throw new IllegalStateException("this step needs --user"); return user; }
    public JagCache van211() { if (van211 == null) throw new IllegalStateException("this step needs --van211"); return van211; }
    public JagCache old241() { if (old241 == null) throw new IllegalStateException("this step needs --old241"); return old241; }

    /** USER vs VAN211 for one id: vanilla / user-changed / user-added / ... (see {@link Origin}). */
    public Origin origin(Kind k, int id) { return Origin.of(k, id, user(), van211()); }

    /**
     * Whether a user-added/changed config entry is really Jagex content GadScape imported from rev 225 unchanged
     * ({@link Rev225}): such entries take BASE's newer version like vanilla ones.
     */
    public boolean isRev225Import(int cfgGroup, int id) {
        return user != null && rev225.isImport(cfgGroup, id, user.configRef(cfgGroup, id));
    }

    /** The user's map keys (lazy). */
    public Xteas xteas() throws IOException {
        if (xteas == null) { opt.require("xteas", opt.xteas); xteas = Xteas.load(opt.xteas); }
        return xteas;
    }

    /** The hand-built cacheU, for comparisons only (lazy; null if not available). */
    public JagCache ref() {
        if (ref == null && opt.ref != null && Files.isDirectory(opt.ref)) ref = JagCache.open("REF(cacheU)", opt.ref);
        return ref;
    }

    /** Scratch directory for one area (created), e.g. work("maps"). */
    public Path work(String area) throws IOException {
        Path w = (opt.work != null ? opt.work : Path.of("gadconv-work")).resolve(area);
        Files.createDirectories(w);
        return w;
    }

    public void log(String fmt, Object... args) {
        System.out.println("[" + report.step() + "] " + (args.length == 0 ? fmt : String.format(fmt, args)));
    }

    /** Commits and closes the output, then closes the inputs. Does not save report/idmap (the command does). */
    @Override public void close() throws IOException {
        try {
            if (out != null) out.close();
        } finally {
            for (JagCache c : new JagCache[]{base, user, van211, old241, ref}) if (c != null) c.close();
        }
    }
}

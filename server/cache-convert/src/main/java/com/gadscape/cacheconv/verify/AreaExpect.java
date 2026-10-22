package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Step;
import com.gadscape.cacheconv.ui.UiScope;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * area-expect: what the content areas' CACHE_NEEDS ask the shipped cache to hold, as data ({@value #RESOURCE}), checked
 * on every build (one report error per failing row, so a later change that breaks an area's assumption fails the build):
 * <ul>
 *   <li>{@code base KIND IDS}: the output entry equals BASE's (the prayer book, the loot chest, the 241 combat tab, ...);</li>
 *   <li>{@code moved KIND IDS}: USER's entry was relocated (the id map has it) and the id holds BASE's entry again;</li>
 *   <li>{@code exists KIND IDS}: the output has it ({@code =ID}: an output id; otherwise a USER id, mapped);</li>
 *   <li>{@code runelite cs *}: every RuneLite clientscript overlay ({@code verify/runelite-overlays.tsv}) matches the
 *       hash RuneLite checks before it overlays the script.</li>
 * </ul>
 * KIND: a config kind ({@code obj}, {@code enum}, ...), {@code if} (interface groups), {@code cs} (scripts), {@code spr}
 * (sprites), {@code synth} (archive 4).
 */
public final class AreaExpect implements Step {
    public static final String NAME = "area-expect";
    public static final String RESOURCE = "verify/area-expect.tsv";
    public static final String OVERLAYS = "verify/runelite-overlays.tsv";

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) {
        int pass = 0, fail = 0;
        for (String line : lines(RESOURCE)) {
            String[] c = line.split("\t", -1);
            if (c.length < 4) { ctx.report.error("area-expect: bad row: " + line); fail++; continue; }
            String check = c[0].trim(), kind = c[1].trim(), ids = c[2].trim(), area = c[3].trim();
            List<String> bad = new ArrayList<>();
            int n = 0;
            if (check.equals("runelite")) {
                for (String o : lines(OVERLAYS)) {
                    String[] p = o.split("\t");
                    int id = Integer.parseInt(p[0].trim());
                    byte[] b = ctx.out.single(Arch.SCRIPTS, id);
                    n++;
                    if (b == null || !sha256(b).equalsIgnoreCase(p[1].trim())) bad.add("script " + id + (b == null ? " missing" : " differs from RuneLite's hash"));
                }
            } else {
                boolean literal = ids.startsWith("=");
                for (int id : UiScope.ids(literal ? ids.substring(1) : ids)) {
                    n++;
                    String why = one(ctx, check, kind, id, literal);
                    if (why != null) bad.add(why);
                }
            }
            if (bad.isEmpty()) { pass++; ctx.report.count("area-expect.pass " + area, n); }
            else {
                fail++;
                ctx.report.error("area-expect (" + area + "): " + check + " " + kind + " " + ids + ": " + (bad.size() > 12 ? bad.subList(0, 12) + " ... (" + bad.size() + ")" : bad));
            }
        }
        ctx.report.count("area-expect.rows.pass", pass);
        ctx.report.count("area-expect.rows.fail", fail);
        ctx.log("%d rows hold, %d fail", pass, fail);
    }

    /** Null if the expectation holds for the id, else why not. */
    static String one(Ctx ctx, String check, String kind, int id, boolean literal) {
        int arch, cfg = -1;
        Kind k = null;
        switch (kind) {
            case "if" -> { arch = Arch.INTERFACES; k = Kind.INTERFACE; }
            case "cs" -> { arch = Arch.SCRIPTS; k = Kind.SCRIPT; }
            case "spr" -> { arch = Arch.SPRITES; k = Kind.SPRITE; }
            case "synth" -> arch = Arch.SYNTHS;
            default -> { k = Kind.byKey(kind); arch = Arch.CONFIGS; cfg = k.configGroup; }
        }
        String what = kind + " " + id;
        switch (check) {
            case "base" -> { return same(ctx.out, ctx.base, arch, cfg, id) ? null : what + " differs from BASE"; }
            case "moved" -> {
                if (k == null || !ctx.ids.moved(k, id)) return what + " was not relocated";
                int to = ctx.ids.map(k, id);
                if (!present(ctx.out, arch, cfg, to)) return what + " -> " + to + " is missing";
                return same(ctx.out, ctx.base, arch, cfg, id) ? null : what + " does not hold BASE's entry";
            }
            case "exists" -> {
                int t = literal || k == null ? id : ctx.ids.map(k, id);
                return present(ctx.out, arch, cfg, t) ? null : kind + " " + t + " is missing";
            }
            default -> throw new IllegalArgumentException("unknown check " + check);
        }
    }

    static boolean present(CacheView c, int arch, int cfg, int id) { return cfg >= 0 ? c.hasConfig(cfg, id) : c.hasGroup(arch, id); }

    static boolean same(CacheView a, CacheView b, int arch, int cfg, int id) {
        if (cfg >= 0) return Arrays.equals(a.config(cfg, id), b.config(cfg, id));
        boolean ha = a.hasGroup(arch, id), hb = b.hasGroup(arch, id);
        if (!ha || !hb) return ha == hb;
        GroupData x = a.group(arch, id), y = b.group(arch, id);
        return x.sameContent(y);
    }

    static List<String> lines(String resource) {
        try (InputStream in = AreaExpect.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return List.of();
            List<String> r = new ArrayList<>();
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) if (!l.isBlank() && !l.startsWith("#")) r.add(l);
            return r;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    static String sha256(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

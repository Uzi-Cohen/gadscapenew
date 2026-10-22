package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.verify.Findings.Severity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Branding scan: finds every Near-Reality / near-reality / nearreality / "Near Reality" / NR-specific name, "Roat Pkz",
 * "OSNR" and "RuneSpawn" string in a cache. It searches the decoded bytes of every file of the text-carrying archives
 * (configs, interfaces, clientscripts, defaults, world map, dbtable indexes, gamevals) case-insensitively, so it does
 * not depend on any format walker; each hit is reported with the whole string it is in and where it is
 * ({@code obj 1234}, {@code component 548:12}, {@code script 7001}, ...).
 */
public final class BrandScan {
    private BrandScan() {}

    /** The patterns (case-insensitive). */
    public static final Pattern BRAND = Pattern.compile(
            "near[\\s_\\-.]*reality|roat[\\s_\\-]*pkz|(?<![a-z0-9])osnr(?![a-z0-9])|rune[\\s_\\-]*spawn", Pattern.CASE_INSENSITIVE);

    /** Archives scanned (text carriers). */
    public static final int[] ARCHIVES = {Arch.CONFIGS, Arch.INTERFACES, Arch.BINARY, Arch.SCRIPTS, Arch.DEFAULTS, Arch.WORLDMAP_GEOGRAPHY,
            Arch.WORLDMAP, Arch.WORLDMAP_GROUND, Arch.DBTABLE_INDEX, Arch.GAMEVALS};

    public record Hit(int archive, int group, int file, String where, String text) {}

    /** Every hit in one byte array: the NUL/control-delimited string around each match. */
    public static List<String> find(byte[] b) {
        List<String> out = new ArrayList<>();
        String s = new String(b, StandardCharsets.ISO_8859_1);
        Matcher m = BRAND.matcher(s);
        int last = -1;
        while (m.find()) {
            int from = m.start(), to = m.end();
            while (from > 0 && s.charAt(from - 1) >= 32) from--;
            while (to < s.length() && s.charAt(to) >= 32) to++;
            if (from <= last) continue;
            last = to;
            String t = s.substring(from, to);
            out.add(t.length() > 160 ? t.substring(0, 160) + "..." : t);
        }
        return out;
    }

    public static List<Hit> scan(CacheView c) {
        List<Hit> hits = new ArrayList<>();
        for (int a : ARCHIVES) {
            if (!c.hasArchive(a)) continue;
            for (int g : c.groupIds(a)) {
                GroupData d;
                try { d = c.group(a, g); } catch (RuntimeException e) { continue; }
                if (d == null) continue;
                for (int i = 0; i < d.fileCount(); i++) {
                    byte[] b = d.dataAt(i);
                    if (b.length < 4) continue;
                    for (String t : find(b)) hits.add(new Hit(a, g, d.fileIdAt(i), where(a, g, d.fileIdAt(i)), t));
                }
            }
            c.release(a);
        }
        return hits;
    }

    static String where(int a, int g, int f) {
        return switch (a) {
            case Arch.CONFIGS -> Arch.configName(g) + " " + f;
            case Arch.INTERFACES -> "component " + g + ":" + f;
            case Arch.SCRIPTS -> "script " + g;
            default -> Arch.name(a) + " " + g + "/" + f;
        };
    }

    public static void run(CacheView c, Findings f, Severity sev) {
        List<Hit> hits = scan(c);
        int[] perArchive = new int[32];
        for (Hit h : hits) {
            perArchive[h.archive]++;
            f.bad("brand/" + Arch.name(h.archive), sev, h.where + ": " + h.text, h.where + ": \"" + h.text + "\"");
        }
        for (int a : ARCHIVES) if (c.hasArchive(a) && perArchive[a] == 0) f.ok("brand/" + Arch.name(a), sev);
        f.info("brand.hits", hits.size());
    }
}

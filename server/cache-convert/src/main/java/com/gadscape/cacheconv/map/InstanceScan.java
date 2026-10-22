package com.gadscape.cacheconv.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the map squares the server copies into instances (MapBuilder.copyAllPlanesMap / copyPlanesMap / copySquare,
 * DynamicArea(area, chunkX, chunkY), DynamicArea(area, region), PrebuiltDynamicArea(region = ...)) by scanning the
 * server's Java/Kotlin sources. Static only: arguments must be integer literals or literal arithmetic (3522 / 8,
 * 2624 >> 3); every call whose source cannot be resolved is listed as unresolved with file and line. The copied
 * extent comes from the file's single findEmptyChunk(w, h) when there is one, else one square (8 x 8 chunks).
 * An instance built from a square the output takes from BASE while the user's square differs is a candidate for
 * in-game checks (NPC spawn tiles, object handlers by position: audit MAP-1, CRIT-22).
 */
public final class InstanceScan {
    private InstanceScan() {}

    public record Site(String file, int line, String call, int chunkX, int chunkY, int w, int h, TreeSet<Integer> squares) {}

    public record Result(List<Site> resolved, List<String> unresolved) {
        public TreeMap<Integer, List<Site>> bySquare() {
            TreeMap<Integer, List<Site>> m = new TreeMap<>();
            for (Site s : resolved) for (int r : s.squares) m.computeIfAbsent(r, k -> new ArrayList<>()).add(s);
            return m;
        }
    }

    private static final String EXPR = "([^,()]*(?:\\([^()]*\\))?[^,()]*)";
    private static final Pattern SUPER3 = Pattern.compile("super\\(\\s*[\\w.]+\\s*,\\s*" + EXPR + "\\s*,\\s*" + EXPR + "\\s*\\)");
    private static final Pattern SUPER2 = Pattern.compile("super\\(\\s*[\\w.]+\\s*,\\s*(\\d+)\\s*\\)");
    private static final Pattern REGION_ARG = Pattern.compile("(?:PrebuiltDynamicArea|DynamicArea)\\s*\\([^)]*?region\\s*=\\s*(\\d+)");
    private static final Pattern COPY = Pattern.compile("(copyAllPlanesMap|copyPlanesMap|copySquare)\\s*\\(\\s*[\\w.]+\\s*,\\s*" + EXPR + "\\s*,\\s*" + EXPR + "\\s*,\\s*" + EXPR);
    private static final Pattern FIND = Pattern.compile("findEmptyChunk\\(\\s*" + EXPR + "\\s*,\\s*" + EXPR + "\\s*\\)");
    private static final Pattern NUM = Pattern.compile("^\\(?\\s*(\\d+)\\s*(?:(/|\\*|>>|<<)\\s*(\\d+))?\\s*\\)?$");
    private static final Pattern CONST = Pattern.compile("(?:static\\s+final\\s+int|final\\s+static\\s+int|const\\s+val|private\\s+val|val)\\s+([A-Za-z_]\\w*)\\s*(?::\\s*Int)?\\s*=\\s*([^;\\n]+)");

    /** Literal integer expression, or null. */
    static Integer eval(String e) { return eval(e, java.util.Map.of()); }

    /** Integer expression of literals and the file's own int constants, or null. */
    static Integer eval(String e, java.util.Map<String, Integer> consts) {
        String t = e.trim();
        for (var c : consts.entrySet()) t = t.replaceAll("\\b" + c.getKey() + "\\b", String.valueOf(c.getValue()));
        Matcher m = NUM.matcher(t);
        if (!m.matches()) return null;
        long a = Long.parseLong(m.group(1));
        if (m.group(2) == null) return (int) a;
        long b = Long.parseLong(m.group(3));
        return (int) switch (m.group(2)) { case "/" -> a / b; case "*" -> a * b; case ">>" -> a >> b; default -> a << b; };
    }

    public static Result scan(Path serverDir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(serverDir)) {
            files = s.filter(p -> { String n = p.toString(); return (n.endsWith(".java") || n.endsWith(".kt")) && !n.contains("/build/") && !n.contains("/cache-convert/"); })
                    .sorted().toList();
        }
        List<Site> sites = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            if (!text.contains("DynamicArea") && !text.contains("MapBuilder")) continue;
            String rel = serverDir.relativize(f).toString();
            java.util.Map<String, Integer> consts = new java.util.TreeMap<>();
            Matcher cm = CONST.matcher(text);
            while (cm.find()) { Integer v = eval(cm.group(2), consts); if (v != null) consts.put(cm.group(1), v); }
            int[] size = null;
            Matcher fm = FIND.matcher(text);
            int finds = 0;
            while (fm.find()) {
                Integer w = eval(fm.group(1), consts), h = eval(fm.group(2), consts);
                finds++;
                size = w != null && h != null ? new int[]{w, h} : null;
            }
            if (finds != 1) size = null;
            boolean isArea = text.contains("DynamicArea");
            if (isArea) {
                Matcher m = SUPER3.matcher(text);
                while (m.find()) {
                    Integer cx = eval(m.group(1), consts), cy = eval(m.group(2), consts);
                    int line = line(text, m.start());
                    if (cx == null || cy == null) { if (!m.group(1).isBlank()) unresolved.add(rel + ":" + line + "\t" + m.group().replaceAll("\\s+", " ")); continue; }
                    if (cx < 0 || cx > 2047 || cy < 0 || cy > 2047) continue;
                    sites.add(site(rel, line, "DynamicArea(area, " + cx + ", " + cy + ")", cx, cy, size));
                }
                m = SUPER2.matcher(text);
                while (m.find()) {
                    int region = Integer.parseInt(m.group(1));
                    if (region < 4096 || region > 65535) continue;
                    sites.add(site(rel, line(text, m.start()), "DynamicArea(area, region " + region + ")", (region >> 8) << 3, (region & 255) << 3, size));
                }
                m = REGION_ARG.matcher(text);
                while (m.find()) {
                    int region = Integer.parseInt(m.group(1));
                    sites.add(site(rel, line(text, m.start()), "PrebuiltDynamicArea(region = " + region + ")", (region >> 8) << 3, (region & 255) << 3, size));
                }
            }
            Matcher c = COPY.matcher(text);
            while (c.find()) {
                if (rel.endsWith("MapBuilder.java")) continue;
                int line = line(text, c.start());
                boolean sq = c.group(1).equals("copySquare");
                Integer cx = eval(sq ? c.group(3) : c.group(2), consts), cy = eval(sq ? c.group(4) : c.group(3), consts);
                Integer ratio = sq ? eval(c.group(2), consts) : null;
                if (cx == null || cy == null) { unresolved.add(rel + ":" + line + "\t" + c.group().replaceAll("\\s+", " ")); continue; }
                int[] sz = sq && ratio != null ? new int[]{ratio, ratio} : size;
                sites.add(site(rel, line, c.group(1) + "(" + cx + ", " + cy + ")", cx, cy, sz));
            }
        }
        return new Result(sites, unresolved);
    }

    private static Site site(String file, int line, String call, int cx, int cy, int[] size) {
        int w = size == null ? 8 : size[0], h = size == null ? 8 : size[1];
        TreeSet<Integer> sq = new TreeSet<>();
        for (int x = cx >> 3; x <= (cx + Math.max(1, w) - 1) >> 3; x++)
            for (int y = cy >> 3; y <= (cy + Math.max(1, h) - 1) >> 3; y++) sq.add(x << 8 | y);
        return new Site(file, line, call, cx, cy, w, h, sq);
    }

    private static int line(String text, int pos) {
        int n = 1;
        for (int i = 0; i < pos; i++) if (text.charAt(i) == '\n') n++;
        return n;
    }
}

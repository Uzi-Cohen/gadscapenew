package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.core.GroupData;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * The merged UI as data (resources/ui-merge), written by {@link UiMergeGen} and read by {@link UiMergeStep}:
 * <ul>
 *   <li>{@code manifest.tsv}: kind (interface|script), id, decision (merged|user|base), data file (or -), SHA-256 of
 *       the BASE group the merge was made against (the step refuses stale data), note;</li>
 *   <li>{@code interfaces/ID.if}: {@code .mode merge} (the listed components replace or extend BASE's) or
 *       {@code .mode replace} (the listed components are the whole interface); one line per component: id, origin,
 *       241 bytes in hex, USER-sourced relocatable references ("field:tag", "hook#arg:tag", or -);</li>
 *   <li>{@code scripts/ID.cs2}: the merged script in {@link CsText} form ({@code u} marks GadScape's lines).</li>
 * </ul>
 */
public final class MergeData {
    private MergeData() {}

    public static final String RES = "/ui-merge/";

    public record Entry(String kind, int id, String decision, String file, String baseSha, String note) {}

    public record IfLine(int comp, String origin, byte[] bytes, List<String> refs) {}

    public record IfData(int iface, String mode, List<IfLine> comps) {}

    // ------------------------------------------------------------------ digests

    public static String sha(GroupData g) {
        if (g == null) return "-";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int k = 0; k < g.fileCount(); k++) {
                int id = g.fileIdAt(k);
                byte[] d = g.dataAt(k);
                md.update(new byte[]{(byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id,
                        (byte) (d.length >> 24), (byte) (d.length >> 16), (byte) (d.length >> 8), (byte) d.length});
                md.update(d);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ reading (classpath resources, or a directory)

    /** Reads a data file: from {@code dir} when given (the generator's output), else the classpath resource. */
    public static String text(Path dir, String name) {
        try {
            if (dir != null) {
                Path p = dir.resolve(name);
                return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
            }
            try (InputStream in = MergeData.class.getResourceAsStream(RES + name)) {
                return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Entry> manifest(Path dir) {
        String t = text(dir, "manifest.tsv");
        List<Entry> out = new ArrayList<>();
        if (t == null) return out;
        for (String l : t.split("\n")) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] f = l.split("\t", -1);
            out.add(new Entry(f[0], Integer.parseInt(f[1]), f[2], f[3], f[4], f.length > 5 ? f[5] : ""));
        }
        return out;
    }

    public static IfData readIf(String text) {
        int iface = -1;
        String mode = "merge";
        List<IfLine> comps = new ArrayList<>();
        for (String l : text.split("\n")) {
            if (l.isBlank() || l.startsWith("#")) continue;
            if (l.startsWith(".interface")) { iface = Integer.parseInt(l.substring(10).trim()); continue; }
            if (l.startsWith(".mode")) { mode = l.substring(5).trim(); continue; }
            String[] f = l.split("\t", -1);
            List<String> refs = f.length > 3 && !f[3].equals("-") && !f[3].isEmpty() ? List.of(f[3].split(",")) : List.of();
            comps.add(new IfLine(Integer.parseInt(f[0]), f[1], HexFormat.of().parseHex(f[2]), refs));
        }
        return new IfData(iface, mode, comps);
    }

    public static String writeIf(String header, int iface, String mode, TreeMap<Integer, IfMerge.Comp> comps) {
        StringBuilder sb = new StringBuilder();
        for (String h : header.split("\n")) sb.append("# ").append(h).append('\n');
        sb.append(".interface ").append(iface).append('\n');
        sb.append(".mode ").append(mode).append('\n');
        for (IfMerge.Comp c : comps.values()) {
            sb.append(c.id).append('\t').append(c.origin).append('\t').append(HexFormat.of().formatHex(c.c.encode())).append('\t')
                    .append(c.refs.isEmpty() ? "-" : String.join(",", c.refs)).append('\n');
        }
        return sb.toString();
    }
}

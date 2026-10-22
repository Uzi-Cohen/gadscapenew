package com.gadscape.cacheconv.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Jagex entries GadScape's DataMigration imported from rev 225 (OpenRS2 cache 1943) into its 211 cache. They are
 * absent from vanilla 211 (or replace a vanilla 211 entry) but they are Jagex content, not GadScape's: BASE (241) holds
 * Jagex's newer version of the same entry, so the converter treats them like vanilla entries (BASE wins) instead of
 * user content (USER kept over BASE).
 * <p>
 * The rev-225 cache is evidence, not a build input: {@code config.Rev225Gen} classifies every USER entry against it
 * and stores the result in the resource {@code config/rev225.tsv}, so the build is reproducible without it. Each row
 * carries the CRC-32 of the USER bytes it was classified with; a USER entry that changed since then no longer matches
 * and is treated as user content again.
 * <ul>
 *   <li>{@link Status#IMPORT}: the USER entry is rev 225's (byte-identical, or different only where GadScape's encoders
 *       drop fields they cannot write).</li>
 *   <li>{@link Status#EDIT}: the user edited an imported entry and it is still the same thing (an op added: Duke
 *       Sucellus' "Attack", the "Take" option of the Pestle and mortar 47567). The row stores rev 225's bytes, the base
 *       of the three-way merge onto BASE's entry.</li>
 * </ul>
 * Kinds: config groups by number (6 loc, 9 npc, 10 obj, ...) and {@code model} (archive 7). File format: one row per
 * run of consecutive imported ids ({@code kind, first id, import, crc,crc,...}) or per edit
 * ({@code kind, id, edit, crc, rev 225 bytes}).
 */
public final class Rev225 {
    public enum Status { IMPORT, EDIT }

    /** One classified entry. {@code r225} is set for {@link Status#EDIT} only (211-layout bytes of rev 225's entry). */
    public record Row(String kind, int id, Status status, long userCrc, byte[] r225) {}

    public static final String RESOURCE = "/config/rev225.tsv";
    public static final String MODEL = "model";

    private final Map<String, Row> rows = new HashMap<>();
    private final String source;

    private Rev225(String source) { this.source = source; }

    /** No rows: every USER entry is classified against VAN211 only. */
    public static Rev225 empty() { return new Rev225("none"); }

    /** {@code --rev225.table=FILE|none}, else the bundled resource (empty if it is missing). */
    public static Rev225 load(Options o) {
        String f = o == null ? null : o.get("rev225.table", null);
        if ("none".equals(f)) return empty();
        try (InputStream in = f != null ? Files.newInputStream(Path.of(f)) : Rev225.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return empty();
            Rev225 r = new Rev225(f != null ? f : "resource " + RESOURCE.substring(1));
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String l; (l = br.readLine()) != null; ) {
                if (l.isBlank() || l.startsWith("#")) continue;
                String[] c = l.split("\t", -1);
                Status st = Status.valueOf(c[2].trim().toUpperCase(java.util.Locale.ROOT));
                byte[] b = st == Status.EDIT && c.length > 4 ? Bytes.unhex(c[4].trim()) : null;
                String kind = c[0].trim();
                int first = Integer.parseInt(c[1].trim());
                String[] crcs = c[3].trim().split(",");
                if (st == Status.EDIT && crcs.length != 1) throw new IllegalStateException("rev225 table: edit row " + kind + ":" + first + " with " + crcs.length + " CRCs");
                for (int i = 0; i < crcs.length; i++) {
                    Row row = new Row(kind, first + i, st, Long.parseLong(crcs[i].trim(), 16), b);
                    r.rows.put(key(row.kind, row.id), row);
                }
            }
            return r;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String key(String kind, int id) { return kind + ":" + id; }

    public static String cfgKind(int cfgGroup) { return String.valueOf(cfgGroup); }

    public static long crc(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b);
        return c.getValue();
    }

    public String source() { return source; }
    public int size() { return rows.size(); }
    public boolean isEmpty() { return rows.isEmpty(); }

    /** The row for a USER entry, or null when it is not listed or the USER bytes changed since it was classified. */
    public Row row(String kind, int id, byte[] userBytes) {
        Row r = rows.get(key(kind, id));
        if (r == null || userBytes == null || crc(userBytes) != r.userCrc) return null;
        return r;
    }

    /** {@link Status} of a USER config entry (archive 2 group, raw USER bytes), or null. */
    public Status config(int cfgGroup, int id, byte[] userBytes) {
        Row r = row(cfgKind(cfgGroup), id, userBytes);
        return r == null ? null : r.status;
    }

    /** Whether a USER config entry is rev 225's own (imported unchanged). */
    public boolean isImport(int cfgGroup, int id, byte[] userBytes) { return config(cfgGroup, id, userBytes) == Status.IMPORT; }

    /** Rev 225's bytes (211 layout) of an imported entry the user edited, or null. */
    public byte[] editBase(int cfgGroup, int id, byte[] userBytes) {
        Row r = row(cfgKind(cfgGroup), id, userBytes);
        return r == null || r.status != Status.EDIT ? null : r.r225;
    }

    /** Whether a USER model is rev 225's (byte-identical import). */
    public boolean isModelImport(int id, byte[] userBytes) {
        Row r = row(MODEL, id, userBytes);
        return r != null && r.status == Status.IMPORT;
    }

    /** Rows in a stable order (for the generator and tests). */
    public java.util.List<Row> rows() {
        java.util.List<Row> l = new java.util.ArrayList<>(rows.values());
        l.sort(java.util.Comparator.comparing((Row x) -> x.kind.equals(MODEL) ? Integer.MAX_VALUE : Integer.parseInt(x.kind)).thenComparingInt(Row::id));
        return l;
    }

    /** Adds a row (generator). */
    public void put(Row r) { rows.put(key(r.kind, r.id), r); }

    public static Rev225 builder(String source) { return new Rev225(source); }
}

package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.verify.Findings.Severity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Determinism check: compares two caches (two outputs of the same build) group by group. A group differs when it is in
 * one cache only, or its decoded content differs (file ids and bytes; groups whose stored container CRC and version
 * match are taken as equal without decoding). Also hashes the {@code main_file_cache.*} files: two deterministic
 * builds are byte-identical, so the file hashes must match too.
 */
public final class Determinism {
    private Determinism() {}

    public static Map<String, String> fileHashes(Path dir) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        TreeSet<String> names = new TreeSet<>();
        try (Stream<Path> s = Files.list(dir)) { s.forEach(p -> { String n = p.getFileName().toString(); if (n.startsWith("main_file_cache")) names.add(n); }); }
        for (String n : names) m.put(n, sha256(dir.resolve(n)));
        return m;
    }

    static String sha256(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void compare(Path a, Path b, Findings f) throws IOException {
        Map<String, String> ha = fileHashes(a), hb = fileHashes(b);
        f.check("determinism/files", Severity.ERROR, ha.equals(hb), "file hashes", "main_file_cache files differ: " + diffKeys(ha, hb));
        try (JagCache ca = JagCache.open("A", a); JagCache cb = JagCache.open("B", b)) {
            for (int arch = 0; arch < 32; arch++) {
                boolean ia = ca.hasArchive(arch), ib = cb.hasArchive(arch);
                if (!ia && !ib) continue;
                String name = "determinism/" + Arch.name(arch);
                if (ia != ib) { f.bad(name, Severity.ERROR, "archive " + arch, "archive " + arch + " only in " + (ia ? a : b)); continue; }
                TreeSet<Integer> ids = new TreeSet<>();
                for (int g : ca.groupIds(arch)) ids.add(g);
                for (int g : cb.groupIds(arch)) ids.add(g);
                int n = 0;
                for (int g : ids) {
                    boolean ga = ca.hasGroup(arch, g), gb = cb.hasGroup(arch, g);
                    String key = "archive " + arch + " group " + g;
                    if (ga != gb) { f.bad(name, Severity.ERROR, key, key + " only in " + (ga ? "the first" : "the second") + " cache"); continue; }
                    if (ca.groupCrc(arch, g) == cb.groupCrc(arch, g) && ca.groupVersion(arch, g) == cb.groupVersion(arch, g)
                            && ca.groupName(arch, g) == cb.groupName(arch, g)) { f.ok(name, Severity.ERROR); continue; }
                    GroupData da, db;
                    try { da = ca.group(arch, g); db = cb.group(arch, g); }
                    catch (RuntimeException e) { f.bad(name, Severity.ERROR, key, key + ": " + e.getMessage()); continue; }
                    boolean same = da != null && db != null && da.sameContent(db);
                    String why = same ? "same content, different container/version/name" : "content differs";
                    if (same && ca.groupName(arch, g) == cb.groupName(arch, g)) f.check(name + "-container", Severity.WARNING, false, key, key + ": " + why);
                    else f.bad(name, Severity.ERROR, key, key + ": " + why);
                    if (++n % 2000 == 0) { ca.release(arch); cb.release(arch); }
                }
                ca.release(arch); cb.release(arch);
            }
        }
    }

    static String diffKeys(Map<String, String> a, Map<String, String> b) {
        TreeSet<String> d = new TreeSet<>();
        for (String k : a.keySet()) if (!a.get(k).equals(b.get(k))) d.add(k);
        for (String k : b.keySet()) if (!b.get(k).equals(a.get(k))) d.add(k);
        return d.toString();
    }
}

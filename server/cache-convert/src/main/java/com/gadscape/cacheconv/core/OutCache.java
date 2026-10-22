package com.gadscape.cacheconv.core;

import mgi.tools.jagcached.Helper;
import mgi.tools.jagcached.cache.Archive;
import mgi.tools.jagcached.cache.Cache;
import mgi.tools.jagcached.cache.File;
import mgi.tools.jagcached.cache.Group;
import mgi.utilities.ByteBuffer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The cache being built. It starts as a byte copy of BASE in the output directory; steps then put/remove groups and
 * files. Changes are held in memory (and visible to every read through this view) until {@link #commit()}, which writes
 * them in a fixed order (archive id, then group id, ascending) so the same changes always give the same bytes, trims the
 * padding the mgi packer leaves after reference tables, and reopens the view.
 * <p>
 * Naming (archives that use names, e.g. maps/sprites/fonts): a group replacing an existing one keeps the existing name
 * hash unless {@link GroupData.Builder#name} is forced with {@link #forceName}; a new group takes the builder's name.
 * Each file keeps the existing file's name at that id; a file without a name in an archive that uses names gets the
 * hash of "" (0), which is how the client finds such files (same rule as the old Overlay/UiOverlay tools).
 * <p>
 * Inputs are never written: the constructor refuses an output directory that is, contains or lies inside a protected
 * directory, and in dry-run mode nothing at all is written (reads come from BASE plus the pending changes).
 */
public final class OutCache implements CacheView, AutoCloseable {
    public final Path dir;
    private final Path baseDir;
    private final boolean dryRun;
    private final Report report;
    private JagCache view;

    private static final class Pending {
        GroupData.Builder b;
        GroupData snap;
        boolean forceName;
        Pending(GroupData.Builder b) { this.b = b; }
        GroupData get() { if (snap == null) snap = b.build(); return snap; }
    }

    private final TreeMap<Integer, TreeMap<Integer, Pending>> puts = new TreeMap<>();
    private final TreeMap<Integer, TreeSet<Integer>> removed = new TreeMap<>();
    private final Map<Integer, int[]> idsCache = new HashMap<>();
    private int commits;

    private OutCache(Path dir, Path baseDir, boolean dryRun, Report report) {
        this.dir = dir; this.baseDir = baseDir; this.dryRun = dryRun; this.report = report;
    }

    /**
     * Copies BASE's cache files into {@code outDir} (refusing to touch any protected directory) and opens the view.
     * An existing output cache is replaced only with {@code force}; only main_file_cache.* files are ever deleted.
     */
    public static OutCache create(Path baseDir, Path outDir, List<Path> protectedDirs, boolean force, boolean dryRun, Report report) throws IOException {
        Path out = outDir.toAbsolutePath().normalize();
        Path base = baseDir.toAbsolutePath().normalize();
        guard(out, base, protectedDirs);
        if (dryRun) {
            OutCache o = new OutCache(out, base, true, report);
            o.view = JagCache.open("OUT(dry-run)", base);
            return o;
        }
        if (Files.isDirectory(out)) {
            List<Path> old;
            try (Stream<Path> s = Files.list(out)) { old = s.filter(p -> p.getFileName().toString().startsWith("main_file_cache.")).sorted().toList(); }
            if (!old.isEmpty() && !force) throw new IOException("output " + out + " already holds a cache; pass --force to replace it");
            for (Path p : old) Files.delete(p);
        }
        Files.createDirectories(out);
        List<Path> src;
        try (Stream<Path> s = Files.list(base)) { src = s.filter(p -> p.getFileName().toString().startsWith("main_file_cache.")).sorted().toList(); }
        for (Path p : src) Files.copy(p, out.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        OutCache o = new OutCache(out, base, false, report);
        o.view = JagCache.open("OUT", out);
        return o;
    }

    private static void guard(Path out, Path base, List<Path> protectedDirs) throws IOException {
        List<Path> all = new ArrayList<>(protectedDirs);
        all.add(base);
        Path realOut = real(out);
        for (Path p : all) {
            if (p == null) continue;
            Path rp = real(p.toAbsolutePath().normalize());
            if (realOut.equals(rp) || realOut.startsWith(rp) || rp.startsWith(realOut))
                throw new IOException("refusing to write " + out + ": it overlaps protected input " + p);
        }
    }

    /** Real path of the longest existing prefix, plus the rest (works for directories that do not exist yet). */
    private static Path real(Path p) throws IOException {
        Path existing = p, rest = null;
        while (existing != null && !Files.exists(existing)) {
            rest = rest == null ? existing.getFileName() : existing.getFileName().resolve(rest);
            existing = existing.getParent();
        }
        if (existing == null) return p;
        Path r = existing.toRealPath();
        return rest == null ? r : r.resolve(rest);
    }

    public boolean dryRun() { return dryRun; }
    public int commits() { return commits; }

    // ------------------------------------------------------------------ reads

    @Override public String label() { return "OUT"; }
    @Override public boolean hasArchive(int a) { return view.hasArchive(a); }

    private boolean isRemoved(int a, int g) { TreeSet<Integer> r = removed.get(a); return r != null && r.contains(g); }
    private Pending pending(int a, int g) { TreeMap<Integer, Pending> m = puts.get(a); return m == null ? null : m.get(g); }

    @Override public int[] groupIds(int a) {
        return idsCache.computeIfAbsent(a, k -> {
            TreeSet<Integer> s = new TreeSet<>();
            for (int g : view.groupIds(k)) s.add(g);
            TreeMap<Integer, Pending> m = puts.get(k);
            if (m != null) s.addAll(m.keySet());
            TreeSet<Integer> r = removed.get(k);
            if (r != null) s.removeAll(r);
            return s.stream().mapToInt(Integer::intValue).toArray();
        }).clone();
    }

    @Override public boolean hasGroup(int a, int g) {
        if (isRemoved(a, g)) return false;
        return pending(a, g) != null || view.hasGroup(a, g);
    }

    @Override public GroupData group(int a, int g) {
        if (isRemoved(a, g)) return null;
        Pending p = pending(a, g);
        return p != null ? p.get() : view.group(a, g);
    }

    @Override public GroupData group(int a, int g, int[] xtea) {
        if (isRemoved(a, g)) return null;
        Pending p = pending(a, g);
        return p != null ? p.get() : view.group(a, g, xtea);
    }

    @Override public int groupName(int a, int g) {
        if (isRemoved(a, g)) return -1;
        Pending p = pending(a, g);
        int existing = view.hasGroup(a, g) ? view.groupName(a, g) : -1;
        if (p == null) return existing;
        return existing != -1 && !p.forceName ? existing : p.b.name;
    }

    @Override public int groupIdByName(int a, String name) {
        int h = Bytes.nameHash(name);
        TreeMap<Integer, Pending> m = puts.get(a);
        if (m != null) for (int g : m.keySet()) if (groupName(a, g) == h) return g;
        int g = view.groupIdByName(a, name);
        return g == -1 || isRemoved(a, g) || (m != null && m.containsKey(g) && groupName(a, g) != h) ? -1 : g;
    }

    @Override public boolean usesNames(int a) { return view.usesNames(a); }

    @Override public int[] fileIds(int a, int g) {
        if (isRemoved(a, g)) return new int[0];
        Pending p = pending(a, g);
        return p != null ? p.get().fileIds() : view.fileIds(a, g);
    }

    @Override public boolean hasFile(int a, int g, int f) {
        if (isRemoved(a, g)) return false;
        Pending p = pending(a, g);
        return p != null ? p.b.files.containsKey(f) : view.hasFile(a, g, f);
    }

    @Override public void release(int a) { view.release(a); }

    /** The BASE copy as it is on disk, without pending changes. */
    public JagCache committed() { return view; }

    // ------------------------------------------------------------------ writes

    private Pending edit(int a, int g) {
        TreeMap<Integer, Pending> m = puts.computeIfAbsent(a, k -> new TreeMap<>());
        Pending p = m.get(g);
        if (p == null) {
            GroupData cur = isRemoved(a, g) ? null : view.hasGroup(a, g) ? view.group(a, g) : null;
            p = new Pending(cur != null ? cur.toBuilder() : new GroupData.Builder(g));
            m.put(g, p);
            unremove(a, g);
            idsCache.remove(a);
        }
        p.snap = null;
        return p;
    }

    private void unremove(int a, int g) { TreeSet<Integer> r = removed.get(a); if (r != null && r.remove(g)) idsCache.remove(a); }

    /** Replaces (or creates) a whole group with exactly these files. The group data is used as is (not copied). */
    public void putGroup(int a, GroupData g) {
        TreeMap<Integer, Pending> m = puts.computeIfAbsent(a, k -> new TreeMap<>());
        m.put(g.id, new Pending(g.toBuilder()));
        unremove(a, g.id);
        idsCache.remove(a);
    }

    /** Replaces (or creates) a single-file group (file 0). */
    public void putSingle(int a, int g, byte[] data) {
        putGroup(a, new GroupData.Builder(g).put(0, data.clone()).build());
    }

    /** Puts one file into a group (copy-on-write of the current group; creates the group if needed). Data is copied. */
    public void putFile(int a, int g, int f, byte[] data) { edit(a, g).b.put(f, data.clone()); }
    public void putFile(int a, int g, int f, int nameHash, byte[] data) { edit(a, g).b.put(f, nameHash, data.clone()); }

    public void removeFile(int a, int g, int f) {
        if (!hasFile(a, g, f)) return;
        edit(a, g).b.remove(f);
    }

    public void removeGroup(int a, int g) {
        TreeMap<Integer, Pending> m = puts.get(a);
        if (m != null) m.remove(g);
        if (view.hasGroup(a, g)) removed.computeIfAbsent(a, k -> new TreeSet<>()).add(g);
        idsCache.remove(a);
    }

    /** Sets the name hash a pending group is written with, overriding an existing group's name. */
    public void forceName(int a, int g, int nameHash) { Pending p = edit(a, g); p.b.name = nameHash; p.forceName = true; }

    public void putConfig(int cfgGroup, int id, byte[] data) { putFile(Arch.CONFIGS, cfgGroup, id, data); }
    public void removeConfig(int cfgGroup, int id) { removeFile(Arch.CONFIGS, cfgGroup, id); }

    public boolean isPending(int a, int g) { return pending(a, g) != null || isRemoved(a, g); }
    public int pendingCount() { int n = 0; for (var m : puts.values()) n += m.size(); for (var r : removed.values()) n += r.size(); return n; }

    // ------------------------------------------------------------------ commit

    /** Writes every pending change to disk (no-op in dry-run mode) and reopens the view. */
    public void commit() throws IOException {
        if (dryRun || (puts.isEmpty() && removed.isEmpty())) return;
        view.close();
        TreeSet<String> before = listCacheFiles();
        Cache c = Cache.openCache(dir.toString(), false);
        if (c.getArchives() == null) throw new IOException("cannot open " + dir + " for writing");
        try {
            TreeSet<Integer> archives = new TreeSet<>(puts.keySet());
            archives.addAll(removed.keySet());
            for (int a : archives) {
                Archive ar = a < c.getArchiveCount() ? c.getArchive(a) : null;
                if (ar == null || !ar.isLoaded()) throw new IOException("archive " + a + " does not exist in the output cache (creating archives is not supported)");
                Map<Integer, Group> existing = new HashMap<>();
                for (Group g : ar.getGroups()) if (g != null) existing.put(g.getID(), g);
                TreeSet<Integer> rem = removed.get(a);
                if (rem != null) for (int g : rem) { Group ex = existing.get(g); if (ex != null) ar.deleteGroup(ex); }
                TreeMap<Integer, Pending> m = puts.get(a);
                if (m != null) {
                    for (Map.Entry<Integer, Pending> e : m.entrySet()) {
                        int id = e.getKey();
                        Pending p = e.getValue();
                        Group ex = existing.get(id);
                        int name = ex != null && ex.getName() != -1 && !p.forceName ? ex.getName() : p.b.name;
                        if (name == -1 && ex == null && ar.usesNames() && report != null)
                            report.warn("archive " + a + " group " + id + " is new in an archive that uses names but has no name");
                        Map<Integer, Integer> existingNames = new HashMap<>();
                        if (ex != null) for (File f : ex.getFiles()) if (f != null && f.getName() != -1) existingNames.put(f.getID(), f.getName());
                        List<File> files = new ArrayList<>(p.b.files.size());
                        for (Map.Entry<Integer, GroupData.Builder.Entry> fe : p.b.files.entrySet()) {
                            int fid = fe.getKey();
                            int fname = fe.getValue().name();
                            Integer en = existingNames.get(fid);
                            if (en != null) fname = en;
                            else if (fname == -1 && ar.usesNames()) fname = 0;
                            files.add(new File(fid, fname, new ByteBuffer(fe.getValue().data())));
                        }
                        ar.addGroup(new Group(id, name, ex != null ? ex.getVersion() : 0, files.toArray(new File[0])));
                    }
                }
                ar.finish();
                trimReferenceTable(c, ar);
                ar.unloadCachedFiles();
            }
        } finally {
            c.close();
        }
        for (String f : listCacheFiles()) {
            Path p = dir.resolve(f);
            if (!before.contains(f) && Files.size(p) == 0) Files.delete(p); // empty idx files the writable open creates (16, 23)
        }
        puts.clear(); removed.clear(); idsCache.clear();
        view = JagCache.open("OUT", dir);
        commits++;
    }

    /**
     * mgi's Archive.pack allocates 4 bytes per file for file names even when the archive has no names and writes the
     * whole allocation; the client ignores the tail but the strict checker reports it. Rewrite the table without it.
     */
    private void trimReferenceTable(Cache c, Archive ar) {
        byte[] packed = ar.getPacked().getBuffer().clone();
        byte[] table = Helper.decodeFITContainer(packed);
        RefTable t = RefTable.parse(table);
        if (t.end == table.length) return;
        if (!Bytes.isZero(table, t.end, table.length)) throw new IllegalStateException("archive " + ar.getID() + ": reference table has non-zero trailing bytes");
        byte[] trimmed = java.util.Arrays.copyOf(table, t.end);
        ByteBuffer enc = Helper.encodeFITContainer(new ByteBuffer(trimmed), ar.getVersion());
        if (!c.getMasterIndex().put(ar.getID(), enc, enc.getBuffer().length)) throw new IllegalStateException("could not rewrite reference table " + ar.getID());
    }

    private TreeSet<String> listCacheFiles() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            TreeSet<String> r = new TreeSet<>();
            s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("main_file_cache.")).forEach(r::add);
            return r;
        }
    }

    @Override public void close() throws IOException {
        if (view == null) return;
        commit();
        view.close();
        view = null;
    }
}

package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;

import java.util.TreeMap;

/** A small in-memory {@link CacheView} (self-checks and tests): groups put by hand, no names. */
public final class MemView implements CacheView {
    private final TreeMap<Integer, TreeMap<Integer, GroupData>> archives = new TreeMap<>();
    private final String label;

    public MemView(String label) { this.label = label; }

    public MemView put(int archive, GroupData g) { archives.computeIfAbsent(archive, k -> new TreeMap<>()).put(g.id, g); return this; }

    /** Puts one file into a group (creating or extending it). */
    public MemView file(int archive, int group, int file, byte[] data) {
        GroupData old = archives.computeIfAbsent(archive, k -> new TreeMap<>()).get(group);
        GroupData.Builder b = old == null ? new GroupData.Builder(group) : old.toBuilder();
        b.put(file, data);
        return put(archive, b.build());
    }

    public MemView config(int cfgGroup, int id, byte[] data) { return file(Arch.CONFIGS, cfgGroup, id, data); }
    public MemView single(int archive, int group, byte[] data) { return put(archive, GroupData.single(group, -1, data)); }

    @Override public String label() { return label; }
    @Override public boolean hasArchive(int a) { return archives.containsKey(a); }
    @Override public int[] groupIds(int a) { TreeMap<Integer, GroupData> m = archives.get(a); return m == null ? new int[0] : m.keySet().stream().mapToInt(Integer::intValue).toArray(); }
    @Override public boolean hasGroup(int a, int g) { TreeMap<Integer, GroupData> m = archives.get(a); return m != null && m.containsKey(g); }
    @Override public GroupData group(int a, int g) { TreeMap<Integer, GroupData> m = archives.get(a); return m == null ? null : m.get(g); }
    @Override public GroupData group(int a, int g, int[] xtea) { return group(a, g); }
    @Override public int groupName(int a, int g) { return -1; }
    @Override public int groupIdByName(int a, String name) { return -1; }
    @Override public boolean usesNames(int a) { return false; }
    @Override public int[] fileIds(int a, int g) { GroupData d = group(a, g); return d == null ? new int[0] : d.fileIds(); }
    @Override public void release(int a) { }
}

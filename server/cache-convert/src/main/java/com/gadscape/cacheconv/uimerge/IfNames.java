package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Interface and component names of a build-241 cache, from the gamevals archive (24), group 14: one file per
 * interface holding its name, then (u16 component, name) pairs ended by 0xFFFF. 211 caches have no gamevals.
 */
public final class IfNames {
    private final Map<Integer, String> ifName = new HashMap<>();
    private final Map<Integer, TreeMap<Integer, String>> comps = new HashMap<>();
    private final Map<Integer, Map<String, Integer>> byName = new HashMap<>();
    private final Map<String, Integer> ifByName = new HashMap<>();

    public static final int GROUP_INTERFACES = 14;

    public static IfNames of(CacheView c) {
        IfNames n = new IfNames();
        if (!c.hasArchive(Arch.GAMEVALS) || !c.hasGroup(Arch.GAMEVALS, GROUP_INTERFACES)) return n;
        GroupData g = c.group(Arch.GAMEVALS, GROUP_INTERFACES);
        for (int k = 0; k < g.fileCount(); k++) {
            int id = g.fileIdAt(k);
            Buf in = new Buf(g.dataAt(k));
            String name = in.str();
            n.ifName.put(id, name);
            n.ifByName.put(name, id);
            TreeMap<Integer, String> m = new TreeMap<>();
            Map<String, Integer> inv = new HashMap<>();
            while (in.remaining() >= 2) {
                int comp = in.u16();
                if (comp == 0xFFFF) break;
                String cn = in.str();
                m.put(comp, cn);
                inv.put(cn, comp);
            }
            n.comps.put(id, m);
            n.byName.put(id, inv);
        }
        return n;
    }

    public boolean has(int iface) { return ifName.containsKey(iface); }
    public String name(int iface) { return ifName.get(iface); }
    public int ifaceByName(String name) { Integer v = ifByName.get(name); return v == null ? -1 : v; }
    /** Component name, or null. */
    public String comp(int iface, int comp) { TreeMap<Integer, String> m = comps.get(iface); return m == null ? null : m.get(comp); }
    /** Component id by name, or -1. */
    public int compByName(int iface, String name) { Map<String, Integer> m = byName.get(iface); Integer v = m == null ? null : m.get(name); return v == null ? -1 : v; }
    public TreeMap<Integer, String> comps(int iface) { return comps.getOrDefault(iface, new TreeMap<>()); }
}

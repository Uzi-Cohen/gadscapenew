package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.RefTable;

import java.util.ArrayList;
import java.util.List;

/**
 * gadconv inspect: looks inside a cache without changing it.
 * <pre>
 *   inspect CACHE                         every archive: protocol, flags, version, groups, files, padding
 *   inspect CACHE ARCHIVE [--list]        group id ranges (--list: every group with files/version/crc/name)
 *   inspect CACHE ARCHIVE GROUP           files of a group (sizes, names, first bytes)
 *   inspect CACHE ARCHIVE GROUP FILE      hex dump of one file (--hex for all of it)
 *   inspect CACHE cfg KIND ID             hex dump of a config entry (KIND = obj, npc, loc, seq, enum, 10, ...)
 *   inspect CACHE name ARCHIVE NAME       group id of a named group (e.g. name 5 m50_50)
 * </pre>
 * CACHE is base, user, van211, old241, ref, out or a directory (default base).
 */
final class Inspect {
    private Inspect() {}

    static int run(Options o) throws Exception {
        List<String> a = new ArrayList<>(o.args);
        String which = a.isEmpty() ? "base" : a.remove(0);
        boolean all = o.flag("hex"), list = o.flag("list");
        try (JagCache c = JagCache.open(which.toUpperCase(), GadConv.cacheDir(o, which))) {
            if (a.isEmpty()) { archives(c); return 0; }
            if (a.get(0).equals("cfg")) {
                if (a.size() < 3) throw new IllegalArgumentException("inspect CACHE cfg KIND ID");
                int g = configGroup(a.get(1)), id = Integer.parseInt(a.get(2));
                byte[] b = c.config(g, id);
                if (b == null) { System.out.println(Arch.configName(g) + " " + id + ": not in " + c.label()); return 1; }
                System.out.println(Arch.configName(g) + " " + id + " (" + b.length + " bytes)");
                System.out.print(Bytes.dump(b, all ? Integer.MAX_VALUE : 512));
                return 0;
            }
            if (a.get(0).equals("name")) {
                int arch = Integer.parseInt(a.get(1));
                int id = c.groupIdByName(arch, a.get(2));
                System.out.println(id < 0 ? "no group named " + a.get(2) : a.get(2) + " = group " + id);
                return id < 0 ? 1 : 0;
            }
            int arch = Integer.parseInt(a.get(0));
            if (!c.hasArchive(arch)) { System.out.println("archive " + arch + " is not in " + c.label()); return 1; }
            if (a.size() == 1) { groups(c, arch, list); return 0; }
            int gid = Integer.parseInt(a.get(1));
            GroupData g = c.group(arch, gid);
            if (g == null) { System.out.println("archive " + arch + " group " + gid + " is not in " + c.label() + (c.hasGroup(arch, gid) ? " (listed but unreadable/encrypted)" : "")); return 1; }
            if (a.size() == 2) { files(c, arch, g); return 0; }
            int fid = Integer.parseInt(a.get(2));
            byte[] b = g.dataRef(fid);
            if (b == null) { System.out.println("file " + fid + " is not in group " + gid); return 1; }
            System.out.println(Arch.name(arch) + " " + gid + ":" + fid + " (" + b.length + " bytes, name " + g.fileName(fid) + ")");
            System.out.print(Bytes.dump(b, all ? Integer.MAX_VALUE : 512));
            return 0;
        }
    }

    static int configGroup(String s) {
        if (s.chars().allMatch(Character::isDigit)) return Integer.parseInt(s);
        for (int g = 0; g < 64; g++) if (Arch.configName(g).equals(s)) return g;
        return switch (s) {
            case "item", "items" -> Arch.CFG_OBJ;
            case "object", "objects" -> Arch.CFG_LOC;
            case "spot" -> Arch.CFG_SPOT;
            case "kit" -> Arch.CFG_IDENTKIT;
            default -> throw new IllegalArgumentException("unknown config kind " + s);
        };
    }

    static void archives(JagCache c) {
        System.out.println(c + "\n");
        System.out.printf("%4s %-20s %5s %5s %10s %8s %9s %9s %8s%n", "arch", "name", "proto", "flags", "version", "groups", "files", "max id", "padding");
        for (int a = 0; a < 256; a++) {
            if (!c.hasArchive(a)) continue;
            RefTable t = c.refTable(a);
            if (t == null) continue;
            int max = t.groupCount() == 0 ? -1 : t.groupIds[t.groupCount() - 1];
            System.out.printf("%4d %-20s %5d %5s %10d %8d %9d %9d %8s%n", a, Arch.name(a), t.protocol, "0x" + Integer.toHexString(t.flags), t.version,
                    t.groupCount(), t.fileCount(), max, t.length - t.end == 0 ? "-" : (t.length - t.end) + " B");
        }
    }

    static void groups(JagCache c, int arch, boolean list) {
        int[] ids = c.groupIds(arch);
        System.out.println(Arch.name(arch) + " (archive " + arch + ") in " + c.label() + ": " + ids.length + " groups" + (c.usesNames(arch) ? ", named" : ""));
        System.out.println("ids: " + ranges(ids));
        if (!list) return;
        for (int g : ids)
            System.out.printf("  %7d  files %5d  version %8d  crc %08x  name %d%n", g, c.fileIds(arch, g).length, c.groupVersion(arch, g), c.groupCrc(arch, g), c.groupName(arch, g));
    }

    static void files(JagCache c, int arch, GroupData g) {
        System.out.println(Arch.name(arch) + " group " + g.id + " in " + c.label() + ": " + g.fileCount() + " files, name " + g.name + ", version " + g.version
                + (arch == Arch.CONFIGS ? " (" + Arch.configName(g.id) + ")" : ""));
        int shown = 0;
        for (int i = 0; i < g.fileCount(); i++) {
            byte[] b = g.dataAt(i);
            if (shown++ < 200 || g.fileCount() <= 250)
                System.out.printf("  %6d  %7d B  name %11d  %s%n", g.fileIdAt(i), b.length, g.fileNameAt(i), Bytes.hex(b, 0, 24));
        }
        if (g.fileCount() > 250) System.out.println("  ... file ids: " + ranges(g.fileIds()));
    }

    /** "0-12, 15, 20-30" style summary of ascending ids. */
    static String ranges(int[] ids) {
        StringBuilder s = new StringBuilder();
        int n = 0;
        for (int i = 0; i < ids.length; ) {
            int j = i;
            while (j + 1 < ids.length && ids[j + 1] == ids[j] + 1) j++;
            if (s.length() > 0) s.append(", ");
            s.append(ids[i]); if (j > i) s.append('-').append(ids[j]);
            i = j + 1;
            if (++n >= 60 && i < ids.length) { s.append(", ... (").append(ids.length - i).append(" more ids up to ").append(ids[ids.length - 1]).append(')'); break; }
        }
        return s.toString();
    }
}

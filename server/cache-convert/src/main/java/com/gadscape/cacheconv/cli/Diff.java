package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * gadconv diff A B [ARCHIVE...]: compares two caches archive by archive. Groups are compared by the CRC their
 * reference tables hold; with --content, groups whose CRC differs are decoded and compared file by file (the same data
 * compressed differently counts as equal). Config groups (archive 2) are always compared per file. --ids lists ids.
 */
final class Diff {
    private Diff() {}

    static int run(Options o) throws Exception {
        if (o.args.size() < 2) throw new IllegalArgumentException("diff CACHE_A CACHE_B [ARCHIVE...]");
        boolean content = o.flag("content"), showIds = o.flag("ids");
        try (JagCache a = JagCache.open(o.args.get(0).toUpperCase(), GadConv.cacheDir(o, o.args.get(0)));
             JagCache b = JagCache.open(o.args.get(1).toUpperCase(), GadConv.cacheDir(o, o.args.get(1)))) {
            List<Integer> archives = new ArrayList<>();
            for (int i = 2; i < o.args.size(); i++) archives.add(Integer.parseInt(o.args.get(i)));
            if (archives.isEmpty()) for (int i = 0; i < 256; i++) if (a.hasArchive(i) || b.hasArchive(i)) archives.add(i);
            System.out.printf("%-22s %8s %8s %8s %8s%n", "archive", "only " + a.label(), "only " + b.label(), "differ", "same");
            for (int arch : archives) {
                if (arch == Arch.CONFIGS) { configs(a, b, showIds); continue; }
                TreeSet<Integer> onlyA = new TreeSet<>(), onlyB = new TreeSet<>(), differ = new TreeSet<>();
                int same = 0;
                TreeSet<Integer> ids = new TreeSet<>();
                for (int g : a.groupIds(arch)) ids.add(g);
                for (int g : b.groupIds(arch)) ids.add(g);
                int n = 0;
                for (int g : ids) {
                    boolean inA = a.hasGroup(arch, g), inB = b.hasGroup(arch, g);
                    if (!inB) { onlyA.add(g); continue; }
                    if (!inA) { onlyB.add(g); continue; }
                    if (a.groupCrc(arch, g) == b.groupCrc(arch, g)) { same++; continue; }
                    if (content) {
                        GroupData ga = a.group(arch, g), gb = b.group(arch, g);
                        if (ga != null && ga.sameContent(gb)) { same++; continue; }
                        if (++n % 2000 == 0) { a.release(arch); b.release(arch); }
                    }
                    differ.add(g);
                }
                a.release(arch); b.release(arch);
                System.out.printf("%-22s %8d %8d %8d %8d%n", arch + " " + Arch.name(arch), onlyA.size(), onlyB.size(), differ.size(), same);
                if (showIds) ids(onlyA, onlyB, differ, a.label(), b.label());
            }
        }
        return 0;
    }

    static void configs(JagCache a, JagCache b, boolean showIds) {
        TreeSet<Integer> groups = new TreeSet<>();
        for (int g : a.groupIds(Arch.CONFIGS)) groups.add(g);
        for (int g : b.groupIds(Arch.CONFIGS)) groups.add(g);
        for (int g : groups) {
            GroupData ga = a.group(Arch.CONFIGS, g), gb = b.group(Arch.CONFIGS, g);
            TreeSet<Integer> onlyA = new TreeSet<>(), onlyB = new TreeSet<>(), differ = new TreeSet<>();
            int same = 0;
            TreeSet<Integer> ids = new TreeSet<>();
            if (ga != null) for (int f : ga.fileIds()) ids.add(f);
            if (gb != null) for (int f : gb.fileIds()) ids.add(f);
            for (int f : ids) {
                byte[] x = ga == null ? null : ga.dataRef(f), y = gb == null ? null : gb.dataRef(f);
                if (y == null) onlyA.add(f); else if (x == null) onlyB.add(f); else if (java.util.Arrays.equals(x, y)) same++; else differ.add(f);
            }
            System.out.printf("%-22s %8d %8d %8d %8d%n", "2 cfg " + g + " " + Arch.configName(g), onlyA.size(), onlyB.size(), differ.size(), same);
            if (showIds) ids(onlyA, onlyB, differ, a.label(), b.label());
        }
        a.release(Arch.CONFIGS); b.release(Arch.CONFIGS);
    }

    static void ids(TreeSet<Integer> onlyA, TreeSet<Integer> onlyB, TreeSet<Integer> differ, String la, String lb) {
        if (!onlyA.isEmpty()) System.out.println("    only " + la + ": " + Inspect.ranges(onlyA.stream().mapToInt(Integer::intValue).toArray()));
        if (!onlyB.isEmpty()) System.out.println("    only " + lb + ": " + Inspect.ranges(onlyB.stream().mapToInt(Integer::intValue).toArray()));
        if (!differ.isEmpty()) System.out.println("    differ: " + Inspect.ranges(differ.stream().mapToInt(Integer::intValue).toArray()));
    }
}

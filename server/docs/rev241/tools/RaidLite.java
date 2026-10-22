// TEST BUILD ONLY: builds the Raids 4 walk-around preview cache (run: java -cp server241.jar:. RaidLite <copy of cacheU> <openrs2-2746> <openrs2-2740>).
import mgi.tools.jagcached.cache.*;
import mgi.utilities.ByteBuffer;
import java.util.*;

/**
 * RaidLite <target (writable copy of cacheU)> <c2746> <c2740>: adds Raids 4 world content from openrs2 2746 to a
 * GadScape cache for a walk-around preview. Per group/file: absent in target and new in 2746 -> copy; target equal
 * to stock 2740 and 2746 changed it -> copy; anything the user changed is kept and reported. Interfaces (3),
 * scripts (12), the db index (21) and gamevals (24) are not touched. Config special cases: user seqs that sit on
 * raid seq ids 15000-15162 move to 40000+ first; padded empty varp/varc entries take 2746's definition.
 */
public class RaidLite {
    static final Set<Integer> SKIP_ARCHIVES = Set.of(2, 3, 12, 21, 24, 255);
    static final Set<Integer> SKIP_CONFIGS = Set.of(19, 34, 38, 39);
    static final Set<Integer> ADD_ONLY_ARCHIVES = Set.of(6, 8, 10, 15, 17); // title screen, music, sprites, fonts: never replace existing groups
    static final Set<Integer> ADD_ONLY_CONFIGS = Set.of(8); // enums: add raid-only ids, never touch existing ones // enums, structs, dbrows, dbtables: raid UI only, user-customised
    static int copied, updated, kept;

    static boolean same(Group a, Group b) {
        if (a == null || b == null) return a == b;
        File[] fa = a.getFiles(), fb = b.getFiles();
        if (fa.length != fb.length) return false;
        for (int i = 0; i < fa.length; i++) {
            if (fa[i] == null || fb[i] == null) { if (fa[i] != fb[i]) return false; continue; }
            if (fa[i].getID() != fb[i].getID() || !Arrays.equals(fa[i].getData().getBuffer(), fb[i].getData().getBuffer())) return false;
        }
        return true;
    }

    static Map<Integer, Group> stubs(Archive a) {
        Map<Integer, Group> m = new HashMap<>();
        Group[] gs = a.getGroups(); if (gs == null) return m;
        for (Group g : gs) if (g != null) m.put(g.getID(), g);
        return m;
    }

    static byte[] bytes(Group g, int id) { File f = g == null ? null : g.findFileByID(id); return f == null ? null : f.getData().getBuffer(); }

    static void put(Archive out, Group src, Group existing) {
        File[] fs = new File[src.getFiles().length];
        for (int i = 0; i < fs.length; i++) fs[i] = new File(src.getFiles()[i].getID(), new ByteBuffer(src.getFiles()[i].getData().getBuffer().clone()));
        out.addGroup(new Group(src.getID(), existing != null ? existing.getName() : src.getName(), existing != null ? existing.getVersion() + 1 : 1, fs));
    }

    public static void main(String[] a) throws Exception {
        Cache out = Cache.openCache(a[0], false), nw = Cache.openCache(a[1], true), old = Cache.openCache(a[2], true);
        StringBuilder report = new StringBuilder();
        for (int arch = 0; arch < 256; arch++) {
            if (SKIP_ARCHIVES.contains(arch)) continue;
            Archive na = nw.getArchive(arch), oa = out.getArchive(arch), sa = old.getArchive(arch);
            if (na == null || oa == null) continue;
            Map<Integer, Group> os = stubs(oa), ss = sa == null ? Map.of() : stubs(sa);
            int c = 0, u = 0, k = 0;
            if (na.getGroups() == null) continue;
            for (Group nst : na.getGroups()) {
                if (nst == null) continue;
                int id = nst.getID();
                Group ost = os.get(id), sst = ss.get(id);
                if (ost != null && ost.getUncompressedCrc() == nst.getUncompressedCrc() && ost.getUncompressedSize() == nst.getUncompressedSize()) continue;
                Group ng = na.findGroupByID(id);
                if (ost == null) { if (sst == null) { put(oa, ng, null); c++; } else k++; ng.unload(); continue; }
                Group og = oa.findGroupByID(id), sg = sst == null ? null : sa.findGroupByID(id);
                if (same(og, ng)) { ng.unload(); og.unload(); if (sg != null) sg.unload(); continue; }
                if (!ADD_ONLY_ARCHIVES.contains(arch) && ((sg != null && same(og, sg)) || (arch == 7 && id >= 62700 && id <= 62702))) { put(oa, ng, og); u++; }
                else { k++; report.append("kept user arch ").append(arch).append(" group ").append(id).append('\n'); og.unload(); }
                ng.unload(); if (sg != null) sg.unload();
            }
            if (c + u + k > 0) System.out.println("archive " + arch + ": added " + c + ", updated " + u + ", kept user " + k);
            copied += c; updated += u; kept += k;
        }
        // configs (archive 2), per file
        Archive na = nw.getArchive(2), oa = out.getArchive(2), sa = old.getArchive(2);
        for (Group nst : na.getGroups()) {
            if (nst == null || SKIP_CONFIGS.contains(nst.getID())) continue;
            Group ng = na.findGroupByID(nst.getID());
            Group og = oa.findGroupByID(nst.getID()), sg = sa.findGroupByID(nst.getID());
            if (og == null) continue;
            int c = 0, u = 0, k = 0, moved = 0;
            if (ng.getID() == 12) { // move user seqs off the raid ids 15000-15162
                for (int id = 15000; id <= 15162; id++) {
                    byte[] ob = bytes(og, id), nb = bytes(ng, id);
                    if (ob == null || nb == null || Arrays.equals(ob, nb)) continue;
                    og.addFile(new File(40000 + (id - 15000), new ByteBuffer(ob.clone())));
                    og.addFile(new File(id, new ByteBuffer(nb.clone())));
                    moved++;
                    report.append("seq ").append(id).append(" -> ").append(40000 + id - 15000).append('\n');
                }
            }
            for (File nf : ng.getFiles()) {
                if (nf == null) continue;
                int id = nf.getID();
                byte[] nb = nf.getData().getBuffer(), ob = bytes(og, id), sb = bytes(sg, id);
                if (ob != null && Arrays.equals(ob, nb)) continue;
                if (ob == null) { if (sb == null) { og.addFile(new File(id, new ByteBuffer(nb.clone()))); c++; } else k++; continue; }
                boolean pad = (ng.getID() == 16 || ng.getID() == 19) && (ob.length == 0 || (ob.length == 1 && ob[0] == 0));
                if (ADD_ONLY_CONFIGS.contains(ng.getID())) { k++; continue; }
                if ((sb != null && Arrays.equals(ob, sb)) || pad) { og.addFile(new File(id, new ByteBuffer(nb.clone()))); u++; continue; }
                k++;
                if (ng.getID() != 12 || id < 15000 || id > 15162) report.append("kept user config ").append(ng.getID()).append(" file ").append(id).append('\n');
            }
            if (c + u + k + moved > 0) System.out.println("config " + ng.getID() + ": added " + c + ", updated " + u + ", kept user " + k + (moved > 0 ? ", user seqs moved " + moved : ""));
            copied += c; updated += u; kept += k;
        }
        // Skeletal keyframes: 241 reads them from archive 22; GadScape's imports left 509 of them in archive 0 (audit B2/BOSS-X1).
        Archive a0 = out.getArchive(0), a22 = out.getArchive(22);
        Map<Integer, Group> k22 = stubs(a22);
        int moved = 0;
        for (int id = 2923; id <= 4402; id++) {
            if (k22.containsKey(id) || !a0.containsGroup(id)) continue;
            Group g = a0.findGroupByID(id);
            put(a22, g, null);
            g.unload();
            moved++;
        }
        System.out.println("keyframe groups copied from archive 0 to 22: " + moved);
        out.close();
        java.nio.file.Files.writeString(java.nio.file.Path.of(a[0] + ".raidlite-report.txt"), report.toString());
        System.out.println("total added " + copied + ", updated " + updated + ", kept user " + kept);
    }
}

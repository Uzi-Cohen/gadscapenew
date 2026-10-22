package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.convert.InterfaceConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;

import java.util.Arrays;
import java.util.List;

/** Dev tool: prints VAN211 / USER / BASE components of interfaces side by side (what the user changed, what BASE has). */
public final class Explore {
    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        try (JagCache base = JagCache.open("BASE", o.base); JagCache user = JagCache.open("USER", o.user); JagCache van = JagCache.open("VAN211", o.van211)) {
            String cmd = o.args.get(0);
            if (cmd.equals("roundtrip")) { roundtrip(base, false); roundtrip(user, true); roundtrip(van, true); return; }
            IfNames names = IfNames.of(base);
            if (cmd.equals("remap") || cmd.equals("aligncheck")) {
                CompRemap.Table t = CompRemap.loadTable(null);
                for (int k = 1; k < o.args.size(); k++) {
                    int id = Integer.parseInt(o.args.get(k));
                    var vc = CompRemap.comps211(van.group(Arch.INTERFACES, id));
                    var bc = CompRemap.comps241(base.group(Arch.INTERFACES, id));
                    if (cmd.equals("aligncheck")) {
                        var al = CompRemap.align(vc, bc, 0.45);
                        var rows = t.rows.get(id);
                        int agree = 0, dis = 0, tmiss = 0;
                        StringBuilder sb = new StringBuilder();
                        for (int c : vc.keySet()) {
                            String[] f = rows == null ? null : rows.get(c);
                            int tt = f == null ? -9 : Integer.parseInt(f[3]);
                            int aa = al.getOrDefault(c, -1);
                            if (tt == -9) tmiss++; else if (tt == aa) agree++; else { dis++; if (sb.length() < 400) sb.append(c).append(":t").append(tt).append("/a").append(aa).append("(").append(f[4]).append(") "); }
                        }
                        System.out.printf("%d: agree %d disagree %d notInTable %d  %s%n", id, agree, dis, tmiss, sb);
                        continue;
                    }
                    var rows = CompRemap.build(id, t, names, vc, bc);
                    System.out.printf("=== %d (%s) VAN %d BASE %d table %s%n", id, names.name(id), vc.size(), bc.size(), t.covers(id));
                    for (var r : rows.values()) System.out.printf("  %d -> %d  %s  %s  [%s]%n", r.c211(), r.c241(), r.confidence(), r.method(), r.c241() >= 0 ? names.comp(id, r.c241()) : "");
                }
                return;
            }
            for (int k = 1; k < o.args.size(); k++) {
                int id = Integer.parseInt(o.args.get(k));
                GroupData gv = van.group(Arch.INTERFACES, id), gu = user.group(Arch.INTERFACES, id), gb = base.group(Arch.INTERFACES, id);
                System.out.printf("=== interface %d (%s): VAN211 %d, USER %d, BASE %d components%n", id, names.name(id),
                        gv == null ? -1 : gv.fileCount(), gu == null ? -1 : gu.fileCount(), gb == null ? -1 : gb.fileCount());
                if (cmd.equals("status")) {
                    int same = 0, diff = 0, missing = 0;
                    StringBuilder ch = new StringBuilder();
                    for (int c : gv.fileIds()) {
                        byte[] v = IfComp.decode(InterfaceConv.component(gv.dataRef(c))).encode();
                        IfComp vv = IfComp.decode(InterfaceConv.component(gv.dataRef(c)));
                        if (vv.if3 && vv.tail.length > 0 && com.gadscape.cacheconv.core.Bytes.isZero(vv.tail, 0, vv.tail.length)) { vv.tail = new byte[0]; v = vv.encode(); }
                        byte[] b = gb == null ? null : gb.dataRef(c);
                        if (b == null) missing++;
                        else if (Arrays.equals(v, b)) same++;
                        else { diff++; if (ch.length() < 300) ch.append(c).append(IfComp.diff(vv, IfComp.decode(b))).append(' '); }
                    }
                    System.out.printf("   same %d, changed %d, missing %d; BASE extra %d; changed: %s%n", same, diff, missing, gb == null ? 0 : Math.max(0, gb.fileCount() - gv.fileCount()), ch);
                }
                if (cmd.equals("diff")) {
                    for (int c : gu.fileIds()) {
                        IfComp u = IfComp.decode(InterfaceConv.component(gu.dataRef(c)));
                        if (!gv.has(c)) { System.out.printf("  + %d: %s%n", c, u); continue; }
                        IfComp v = IfComp.decode(InterfaceConv.component(gv.dataRef(c)));
                        List<String> d = IfComp.diff(v, u);
                        if (d.isEmpty()) continue;
                        StringBuilder sb = new StringBuilder();
                        for (String f : d) sb.append("\n      ").append(f).append(": ").append(v.if3 ? IfComp.show(v.f.get(f)) : "?").append("  ->  ").append(u.if3 ? IfComp.show(u.f.get(f)) : "?");
                        System.out.printf("  ~ %d:%s%n", c, sb);
                    }
                    for (int c : gv.fileIds()) if (!gu.has(c)) System.out.printf("  - %d removed%n", c);
                } else if (cmd.equals("dump")) {
                    String which = o.get("cache", "base");
                    GroupData g = which.equals("base") ? gb : which.equals("user") ? gu : gv;
                    for (int c : g.fileIds()) {
                        byte[] b = which.equals("base") ? g.dataRef(c) : InterfaceConv.component(g.dataRef(c));
                        String nm = which.equals("base") ? names.comp(id, c) : "";
                        System.out.printf("  %d %s: %s%n", c, nm, IfComp.decode(b));
                    }
                }
            }
        }
    }

    static void roundtrip(JagCache c, boolean is211) {
        int n = 0, bad = 0, tails = 0, if1 = 0;
        for (int g : c.groupIds(Arch.INTERFACES)) {
            GroupData gd = c.group(Arch.INTERFACES, g);
            if (gd == null) continue;
            for (int k = 0; k < gd.fileCount(); k++) {
                byte[] b = gd.dataAt(k);
                if (b.length == 0) continue;
                if (is211) b = InterfaceConv.component(b);
                n++;
                try {
                    IfComp x = IfComp.decode(b);
                    if (!x.if3) if1++;
                    if (x.tail.length > 0) tails++;
                    if (!Arrays.equals(x.encode(), b)) { bad++; if (bad < 5) System.out.println("mismatch " + g + ":" + gd.fileIdAt(k)); }
                } catch (RuntimeException e) { bad++; if (bad < 5) System.out.println("error " + g + ":" + gd.fileIdAt(k) + " " + e); }
            }
            c.release(Arch.INTERFACES);
        }
        System.out.printf("%s: %d components, %d IF1, %d with tail bytes, %d round-trip failures%n", c.label(), n, if1, tails, bad);
    }
}

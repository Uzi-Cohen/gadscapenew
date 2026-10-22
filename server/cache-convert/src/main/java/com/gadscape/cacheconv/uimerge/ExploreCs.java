package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;

import java.util.TreeMap;

/** Dev tool: parse checks and side-by-side disassembly of VAN211 / USER / BASE scripts. */
public final class ExploreCs {
    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        try (JagCache base = JagCache.open("BASE", o.base); JagCache user = JagCache.open("USER", o.user); JagCache van = JagCache.open("VAN211", o.van211)) {
            String cmd = o.args.get(0);
            if (cmd.equals("parse")) {
                TreeMap<Integer, Integer> ops = new TreeMap<>();
                int n = 0, bad = 0;
                for (int id : base.groupIds(Arch.SCRIPTS)) {
                    byte[] b = base.single(Arch.SCRIPTS, id);
                    n++;
                    try {
                        Cs2.Script s = Cs2.parse241(b);
                        for (Cs2.Ins in : s.code) if (in.op < 100) ops.merge(in.op, 1, Integer::sum);
                        if (!java.util.Arrays.equals(Cs2.write241(s), b)) { bad++; System.out.println("rewrite differs " + id); }
                    } catch (RuntimeException e) { bad++; if (bad < 10) System.out.println("parse " + id + ": " + e.getMessage()); }
                }
                System.out.println(n + " BASE scripts, " + bad + " bad; ops<100 used: " + ops);
                return;
            }
            if (cmd.equals("status")) {
                for (int k = 1; k < o.args.size(); k++) {
                    int id = Integer.parseInt(o.args.get(k));
                    byte[] vb = van.single(Arch.SCRIPTS, id), ub = user.single(Arch.SCRIPTS, id), bb = base.single(Arch.SCRIPTS, id);
                    String vs, us;
                    try { vs = java.util.Arrays.equals(com.gadscape.cacheconv.convert.ScriptConv.convertAll(java.util.Map.of(id, vb)).scripts.get(id), bb) ? "BASE==conv(VAN211)" : "BASE changed"; } catch (RuntimeException e) { vs = "conv(VAN) failed: " + e.getMessage(); }
                    try { us = java.util.Arrays.equals(com.gadscape.cacheconv.convert.ScriptConv.convertAll(java.util.Map.of(id, ub)).scripts.get(id), bb) ? "BASE==conv(USER)" : ""; } catch (RuntimeException e) { us = "conv(USER) failed: " + e.getMessage(); }
                    System.out.printf("%5d %-28s names V/U/B %d/%d/%d %s %s%n", id, vs, van.groupName(Arch.SCRIPTS, id), user.groupName(Arch.SCRIPTS, id), base.groupName(Arch.SCRIPTS, id), us,
                            van.groupName(Arch.SCRIPTS, id) == base.groupName(Arch.SCRIPTS, id) ? "" : "NAME CHANGED");
                }
                return;
            }
            if (cmd.equals("diff")) {
                for (int k = 1; k < o.args.size(); k++) {
                    int id = Integer.parseInt(o.args.get(k));
                    byte[] vb = van.single(Arch.SCRIPTS, id), ub = user.single(Arch.SCRIPTS, id), bb = base.single(Arch.SCRIPTS, id);
                    Cs2.Script v = Cs2.parse211(vb), u = Cs2.parse211(ub), b = bb == null ? null : Cs2.parse241(bb);
                    System.out.printf("##### %d: VAN211 %d ins (%d/%d args, %d/%d locals), USER %d ins (%d/%d args, %d/%d locals), BASE %s%n", id,
                            v.code.size(), v.intArgs, v.objArgs, v.localInt, v.localObj, u.code.size(), u.intArgs, u.objArgs, u.localInt, u.localObj,
                            b == null ? "absent" : b.code.size() + " ins (" + b.intArgs + "/" + b.objArgs + "/" + b.longArgs + " args, " + b.localInt + "/" + b.localObj + "/" + b.localLong + " locals)");
                    System.out.print(CsDiff.render(v, u, Integer.parseInt(o.get("ctx", "3"))));
                    if (o.flag("base") && b != null) { System.out.println("  --- VAN211 -> BASE:"); System.out.print(CsDiff.render(v, b, 1)); }
                }
                return;
            }
            for (int k = 1; k < o.args.size(); k++) {
                int id = Integer.parseInt(o.args.get(k));
                for (String which : o.get("caches", "van,user,base").split(",")) {
                    JagCache c = which.equals("base") ? base : which.equals("user") ? user : van;
                    byte[] b = c.single(Arch.SCRIPTS, id);
                    System.out.println("##### " + which + " " + id + (b == null ? " absent" : " " + b.length + "B"));
                    if (b == null) continue;
                    Cs2.Script s = which.equals("base") ? Cs2.parse241(b) : Cs2.parse211(b);
                    System.out.print(CsText.write(s, id, null));
                }
            }
        }
    }
}

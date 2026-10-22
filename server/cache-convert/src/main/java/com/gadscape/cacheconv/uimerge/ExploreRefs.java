package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.convert.InterfaceConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;

import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Dev tool: who references given scripts / interfaces in USER and BASE (hooks, gosubs, hook-setter constants, component constants). */
public final class ExploreRefs {
    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        TreeSet<Integer> scripts = new TreeSet<>(), ifaces = new TreeSet<>();
        for (String s : o.get("scripts", "").split(",")) if (!s.isBlank()) scripts.add(Integer.parseInt(s.trim()));
        for (String s : o.get("ifaces", "").split(",")) if (!s.isBlank()) ifaces.add(Integer.parseInt(s.trim()));
        try (JagCache base = JagCache.open("BASE", o.base); JagCache user = JagCache.open("USER", o.user)) {
            for (JagCache c : new JagCache[]{user, base}) {
                boolean is211 = c == user;
                TreeMap<String, TreeSet<String>> refs = new TreeMap<>();
                for (int g : c.groupIds(Arch.INTERFACES)) {
                    GroupData gd = c.group(Arch.INTERFACES, g);
                    if (gd == null) continue;
                    for (int k = 0; k < gd.fileCount(); k++) {
                        byte[] b = gd.dataAt(k);
                        if (b.length == 0) continue;
                        IfComp x = IfComp.decode(is211 ? InterfaceConv.component(b) : b);
                        if (!x.if3) continue;
                        for (String h : IfComp.HOOKS) {
                            var args = x.list(h);
                            if (args == null || args.isEmpty()) continue;
                            if (args.get(0) instanceof Integer s && scripts.contains(s)) add(refs, "script " + s, "if " + g + ":" + gd.fileIdAt(k) + " " + h);
                            for (Object v : args) if (v instanceof Integer iv && iv > 0 && ifaces.contains(iv >>> 16)) add(refs, "iface " + (iv >>> 16), "if " + g + ":" + gd.fileIdAt(k) + " " + h + " arg");
                        }
                    }
                    c.release(Arch.INTERFACES);
                }
                for (int id : c.groupIds(Arch.SCRIPTS)) {
                    byte[] b = c.single(Arch.SCRIPTS, id);
                    if (b == null || b.length < 14) continue;
                    Cs2.Script s;
                    try { s = is211 ? Cs2.parse211(b) : Cs2.parse241(b); } catch (RuntimeException e) { continue; }
                    for (int k = 0; k < s.code.size(); k++) {
                        Cs2.Ins in = s.code.get(k);
                        if (in.op == 40 && scripts.contains(in.i)) add(refs, "script " + in.i, "cs " + id + " gosub");
                        if (in.op == 0 && scripts.contains(in.i) && k + 1 < s.code.size()) {
                            // hook setter: script id constant followed (somewhere) by a descriptor and a 14xx/24xx op
                            for (int j = k + 1; j < Math.min(s.code.size(), k + 40); j++) { int op = s.code.get(j).op; if ((op >= 1400 && op < 1500) || (op >= 2400 && op < 2500)) { add(refs, "script " + in.i, "cs " + id + " const@" + k + " (op " + op + "?)"); break; } }
                        }
                        if (in.op == 0 && in.i > 0 && ifaces.contains(in.i >>> 16) && (in.i & 0xffff) < 2000) add(refs, "iface " + (in.i >>> 16), "cs " + id);
                    }
                }
                c.release(Arch.SCRIPTS);
                System.out.println("##### " + c.label());
                for (var e : refs.entrySet()) {
                    var w = e.getValue();
                    System.out.println("  " + e.getKey() + " <- " + (w.size() > 25 ? String.join("; ", new java.util.ArrayList<>(w).subList(0, 25)) + " ... (" + w.size() + ")" : String.join("; ", w)));
                }
            }
        }
    }

    static void add(TreeMap<String, TreeSet<String>> m, String k, String v) { m.computeIfAbsent(k, x -> new TreeSet<>()).add(v); }
}

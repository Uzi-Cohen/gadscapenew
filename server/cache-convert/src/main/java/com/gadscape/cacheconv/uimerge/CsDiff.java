package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.convert.Cs2;

import java.util.ArrayList;
import java.util.List;

/**
 * Instruction-level diff of two clientscripts (longest common subsequence). Instructions compare by opcode and
 * operand; branches and switches compare by opcode only (their relative offsets move whenever code is inserted).
 * Used to find what GadScape changed in a vanilla script (VAN211 -> USER) and where BASE's version has the same code.
 */
public final class CsDiff {
    private CsDiff() {}

    /** One aligned pair: a index (or -1 = only in b), b index (or -1 = only in a). */
    public record Pair(int a, int b) {}

    public static String key(Cs2.Ins in) {
        if (Cs2.BRANCH.contains(in.op) || in.op == Cs2.SWITCH) return in.op + ":*";
        if (in.op == Cs2.SCONST) return "3:\"" + in.s;
        if (in.op == Cs2.LCONST) return "61:" + in.l;
        return in.op + ":" + in.i;
    }

    /** The alignment of two instruction lists (in order; pairs with both indices are matches). */
    public static List<Pair> align(List<Cs2.Ins> a, List<Cs2.Ins> b) {
        String[] ka = new String[a.size()], kb = new String[b.size()];
        for (int k = 0; k < ka.length; k++) ka[k] = key(a.get(k));
        for (int k = 0; k < kb.length; k++) kb[k] = key(b.get(k));
        return align(ka, kb);
    }

    public static List<Pair> align(String[] a, String[] b) {
        int n = a.length, m = b.length, pre = 0, suf = 0;
        while (pre < n && pre < m && a[pre].equals(b[pre])) pre++;
        while (suf < n - pre && suf < m - pre && a[n - 1 - suf].equals(b[m - 1 - suf])) suf++;
        int an = n - pre - suf, bn = m - pre - suf;
        List<Pair> out = new ArrayList<>();
        for (int k = 0; k < pre; k++) out.add(new Pair(k, k));
        if ((long) an * bn <= 30_000_000L) {
            int[][] L = new int[an + 1][bn + 1];
            for (int i = an - 1; i >= 0; i--)
                for (int j = bn - 1; j >= 0; j--)
                    L[i][j] = a[pre + i].equals(b[pre + j]) ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
            int i = 0, j = 0;
            while (i < an && j < bn) {
                if (a[pre + i].equals(b[pre + j])) { out.add(new Pair(pre + i, pre + j)); i++; j++; }
                else if (L[i + 1][j] >= L[i][j + 1]) { out.add(new Pair(pre + i, -1)); i++; }
                else { out.add(new Pair(-1, pre + j)); j++; }
            }
            for (; i < an; i++) out.add(new Pair(pre + i, -1));
            for (; j < bn; j++) out.add(new Pair(-1, pre + j));
        } else {
            for (int i = 0; i < an; i++) out.add(new Pair(pre + i, -1));
            for (int j = 0; j < bn; j++) out.add(new Pair(-1, pre + j));
        }
        for (int k = 0; k < suf; k++) out.add(new Pair(n - suf + k, m - suf + k));
        return out;
    }

    static String line(Cs2.Script s, int k) {
        Cs2.Ins in = s.code.get(k);
        String v = in.op == Cs2.SCONST ? CsText.quote(in.s) : in.op == Cs2.LCONST ? in.l + "L" : String.valueOf(in.i);
        String extra = Cs2.BRANCH.contains(in.op) ? " -> " + (k + 1 + in.i) : in.op == Cs2.SWITCH ? " " + s.switches.get(in.i) : "";
        return String.format("%-18s %s%s", CsText.name(in.op), v, extra);
    }

    /** Unified-diff style rendering with {@code ctx} lines of context. */
    public static String render(Cs2.Script a, Cs2.Script b, int ctx) {
        List<Pair> al = align(a.code, b.code);
        StringBuilder sb = new StringBuilder();
        boolean[] show = new boolean[al.size()];
        for (int k = 0; k < al.size(); k++) {
            Pair p = al.get(k);
            if (p.a() < 0 || p.b() < 0) for (int d = Math.max(0, k - ctx); d <= Math.min(al.size() - 1, k + ctx); d++) show[d] = true;
        }
        int last = -2;
        for (int k = 0; k < al.size(); k++) {
            if (!show[k]) continue;
            if (k != last + 1) sb.append("  @@\n");
            last = k;
            Pair p = al.get(k);
            if (p.a() >= 0 && p.b() >= 0) sb.append(String.format("   %5d %5d   %s%n", p.a(), p.b(), line(a, p.a())));
            else if (p.a() >= 0) sb.append(String.format("  -%5d %5s   %s%n", p.a(), "", line(a, p.a())));
            else sb.append(String.format("  +%5s %5d   %s%n", "", p.b(), line(b, p.b())));
        }
        return sb.toString();
    }
}

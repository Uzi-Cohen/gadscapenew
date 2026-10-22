package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.convert.Cs2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Text form of a build-241 clientscript, used to store the merged scripts as reviewable data
 * (resources/ui-merge/scripts/ID.cs2) and assembled back to bytes by the merge step. One instruction per line:
 * <pre>
 * .script 912
 * .name ""
 * .locals 3 0 0          int obj long locals
 * .args 1 0 0            int obj long arguments
 *    0  0 5              ; iconst
 *    1 40 10593 u @script ; gosub          (u = GadScape's code; @TYPE = the operand is an id of that kind)
 *    2  3 "text\x0a"     ; sconst          (strings: ISO-8859-1, \\ \" \xNN escapes)
 *    3 61 12L            ; lconst
 * .switch 0 1=5 2=8      switch table 0: case=relative jump (target = switch index + 1 + jump)
 * </pre>
 * The leading index is checked when reading; opcodes and operands are numbers, names are comments. Only operands of
 * lines marked {@code u} are remapped through the id map at build time: the rest is BASE's own code and BASE ids
 * never move.
 */
public final class CsText {
    private CsText() {}

    /** Per-instruction metadata: GadScape's own instruction, and the kind of id its operand is (null = none). */
    public record Meta(boolean user, String tag) {}

    /** A parsed text script: the instruction model plus metadata per instruction (by identity). */
    public static final class Doc {
        public final int id;
        public final Cs2.Script s;
        public final IdentityHashMap<Cs2.Ins, Meta> meta = new IdentityHashMap<>();
        /** GadScape's switch cases whose key is an id: "table:key" -> kind (written "key@kind=jump"). */
        public final java.util.TreeMap<String, String> switchTags = new java.util.TreeMap<>();
        public Doc(int id, Cs2.Script s) { this.id = id; this.s = s; }
        public Meta meta(Cs2.Ins in) { Meta m = meta.get(in); return m == null ? new Meta(false, null) : m; }
    }

    // ------------------------------------------------------------------ names (comments only)

    private static Map<Integer, String> NAMES;

    static synchronized Map<Integer, String> names() {
        if (NAMES != null) return NAMES;
        Map<Integer, String> m = new HashMap<>();
        String[][] core = {{"0", "iconst"}, {"1", "get_varp"}, {"2", "set_varp"}, {"3", "sconst"}, {"6", "jump"}, {"7", "if_icmpne"},
                {"8", "if_icmpeq"}, {"9", "if_icmplt"}, {"10", "if_icmpgt"}, {"21", "return"}, {"25", "get_varbit"}, {"27", "set_varbit"},
                {"31", "if_icmple"}, {"32", "if_icmpge"}, {"33", "iload"}, {"34", "istore"}, {"35", "oload"}, {"36", "ostore"},
                {"37", "join_string"}, {"38", "pop_int"}, {"39", "pop_obj"}, {"40", "gosub"}, {"42", "get_varc_int"}, {"43", "set_varc_int"},
                {"44", "define_array"}, {"45", "push_array_int"}, {"46", "pop_array_int"}, {"47", "get_varc_str_old"}, {"48", "set_varc_str_old"},
                {"49", "get_varc_str"}, {"50", "set_varc_str"}, {"60", "switch"}, {"61", "lconst"}, {"63", "push_null"}};
        for (String[] c : core) m.put(Integer.parseInt(c[0]), c[1]);
        for (String res : new String[]{"/ui/cs2-commands-211.tsv", "/ui/cs2-commands-211-extra.tsv"}) {
            try (InputStream in = CsText.class.getResourceAsStream(res)) {
                if (in == null) continue;
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String l; (l = r.readLine()) != null; ) {
                    if (l.isBlank() || l.startsWith("#")) continue;
                    String[] f = l.split("\t");
                    m.putIfAbsent(Integer.parseInt(f[0].trim()), f[1].trim());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        NAMES = m;
        return m;
    }

    public static String name(int op) { String n = names().get(op); return n == null ? "op" + op : n; }

    public static boolean isBranch(int op) { return Cs2.BRANCH.contains(op); }

    // ------------------------------------------------------------------ write

    public static String write(Cs2.Script s, int id, Doc doc) {
        StringBuilder sb = new StringBuilder();
        sb.append(".script ").append(id).append('\n');
        sb.append(".name ").append(quote(new String(s.name, 0, s.name.length - 1, StandardCharsets.ISO_8859_1))).append('\n');
        sb.append(".locals ").append(s.localInt).append(' ').append(s.localObj).append(' ').append(s.localLong).append('\n');
        sb.append(".args ").append(s.intArgs).append(' ').append(s.objArgs).append(' ').append(s.longArgs).append('\n');
        for (int k = 0; k < s.code.size(); k++) {
            Cs2.Ins in = s.code.get(k);
            StringBuilder line = new StringBuilder();
            line.append(String.format("%5d %4d ", k, in.op));
            if (in.op == Cs2.SCONST) line.append(quote(in.s));
            else if (in.op == Cs2.LCONST && s.rev241) line.append(in.l).append('L');
            else line.append(in.i);
            Meta m = doc == null ? null : doc.meta.get(in);
            if (m != null && m.user()) line.append(" u");
            if (m != null && m.tag() != null) line.append(" @").append(m.tag());
            while (line.length() < 34) line.append(' ');
            line.append(" ; ").append(name(in.op));
            if (isBranch(in.op)) line.append(" -> ").append(k + 1 + in.i);
            if (in.op == Cs2.SWITCH) line.append(" table ").append(in.i);
            sb.append(line).append('\n');
        }
        for (int t = 0; t < s.switches.size(); t++) {
            sb.append(".switch ").append(t);
            for (var e : s.switches.get(t).entrySet()) {
                String tag = doc == null ? null : doc.switchTags.get(t + ":" + e.getKey());
                sb.append(' ').append(e.getKey()).append(tag == null ? "" : "@" + tag).append('=').append(e.getValue());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String write(Doc d) { return write(d.s, d.id, d); }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 32 || c > 126) sb.append(String.format("\\x%02x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------ read

    /** Parses the text form (241 layout). */
    public static Doc read(String text) {
        Cs2.Script s = new Cs2.Script();
        s.rev241 = true;
        int id = -1;
        Doc[] holder = new Doc[1];
        IdentityHashMap<Cs2.Ins, Meta> meta = new IdentityHashMap<>();
        TreeMapSwitches sw = new TreeMapSwitches();
        java.util.TreeMap<String, String> swTags = new java.util.TreeMap<>();
        String[] lines = text.split("\n", -1);
        for (int ln = 0; ln < lines.length; ln++) {
            String l = lines[ln].strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            try {
                if (l.startsWith(".")) {
                    String[] f = l.split("\\s+", 2);
                    switch (f[0]) {
                        case ".script" -> id = Integer.parseInt(f[1].trim());
                        case ".name" -> { int[] p = {0}; String n = unquote(f[1].trim(), p); byte[] nb = n.getBytes(StandardCharsets.ISO_8859_1); s.name = java.util.Arrays.copyOf(nb, nb.length + 1); }
                        case ".locals" -> { String[] v = f[1].trim().split("\\s+"); s.localInt = Integer.parseInt(v[0]); s.localObj = Integer.parseInt(v[1]); s.localLong = Integer.parseInt(v[2]); }
                        case ".args" -> { String[] v = f[1].trim().split("\\s+"); s.intArgs = Integer.parseInt(v[0]); s.objArgs = Integer.parseInt(v[1]); s.longArgs = Integer.parseInt(v[2]); }
                        case ".switch" -> {
                            String[] v = f[1].trim().split("\\s+");
                            int t = Integer.parseInt(v[0]);
                            LinkedHashMap<Integer, Integer> m = new LinkedHashMap<>();
                            for (int k = 1; k < v.length; k++) {
                                int e = v[k].indexOf('=', 1);
                                String key = v[k].substring(0, e);
                                int at = key.indexOf('@');
                                if (at > 0) { swTags.put(t + ":" + Integer.parseInt(key.substring(0, at)), key.substring(at + 1)); key = key.substring(0, at); }
                                m.put(Integer.parseInt(key), Integer.parseInt(v[k].substring(e + 1)));
                            }
                            sw.put(t, m);
                        }
                        default -> throw new IllegalArgumentException("unknown directive " + f[0]);
                    }
                    continue;
                }
                int[] p = {0};
                int idx = Integer.parseInt(token(l, p));
                if (idx != s.code.size()) throw new IllegalArgumentException("instruction index " + idx + ", expected " + s.code.size());
                int op = Integer.parseInt(token(l, p));
                Cs2.Ins in;
                skipWs(l, p);
                if (op == Cs2.SCONST) in = new Cs2.Ins(op, 0, unquote(l, p));
                else {
                    String t = token(l, p);
                    if (op == Cs2.LCONST) { in = new Cs2.Ins(op, 0, null); in.l = Long.parseLong(t.endsWith("L") ? t.substring(0, t.length() - 1) : t); }
                    else in = new Cs2.Ins(op, Integer.parseInt(t), null);
                }
                boolean user = false;
                String tag = null;
                while (true) {
                    skipWs(l, p);
                    if (p[0] >= l.length() || l.charAt(p[0]) == ';') break;
                    String t = token(l, p);
                    if (t.equals("u")) user = true;
                    else if (t.startsWith("@")) tag = t.substring(1);
                    else throw new IllegalArgumentException("unexpected '" + t + "'");
                }
                s.code.add(in);
                if (user || tag != null) meta.put(in, new Meta(user, tag));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("line " + (ln + 1) + ": " + e.getMessage() + ": " + l, e);
            }
        }
        if (s.name == null) s.name = new byte[]{0};
        for (int t = 0; t < sw.size(); t++) {
            LinkedHashMap<Integer, Integer> m = sw.get(t);
            if (m == null) throw new IllegalArgumentException("switch table " + t + " missing");
            s.switches.add(m);
        }
        Doc d = new Doc(id, s);
        d.meta.putAll(meta);
        d.switchTags.putAll(swTags);
        holder[0] = d;
        return d;
    }

    /** Switch tables by index (kept apart so tables may be listed in any order). */
    private static final class TreeMapSwitches extends java.util.TreeMap<Integer, LinkedHashMap<Integer, Integer>> {}

    private static void skipWs(String l, int[] p) { while (p[0] < l.length() && Character.isWhitespace(l.charAt(p[0]))) p[0]++; }

    private static String token(String l, int[] p) {
        skipWs(l, p);
        int s = p[0];
        while (p[0] < l.length() && !Character.isWhitespace(l.charAt(p[0]))) p[0]++;
        if (s == p[0]) throw new IllegalArgumentException("missing token");
        return l.substring(s, p[0]);
    }

    static String unquote(String l, int[] p) {
        skipWs(l, p);
        if (l.charAt(p[0]) != '"') throw new IllegalArgumentException("expected a string");
        p[0]++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = l.charAt(p[0]++);
            if (c == '"') break;
            if (c == '\\') {
                char e = l.charAt(p[0]++);
                if (e == 'x') { sb.append((char) Integer.parseInt(l.substring(p[0], p[0] + 2), 16)); p[0] += 2; }
                else sb.append(e);
            } else sb.append(c);
        }
        return sb.toString();
    }
}

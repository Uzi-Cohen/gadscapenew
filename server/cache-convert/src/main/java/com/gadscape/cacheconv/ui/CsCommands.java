package com.gadscape.cacheconv.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Stack signatures of the build-211 clientscript commands, as data: {@code ui/cs2-commands-211.tsv} is generated from
 * RuneStar cs2's {@code Command.Basic} table (server/cs2, the decompiler the server build already carries; generator:
 * gadconv-work/ui-convert/gen/gen_cmds.py) and {@code ui/cs2-commands-211-extra.tsv} adds the few commands that table
 * lacks but GadScape's scripts use. Commands with a variable or value-dependent stack effect (gosub, enum, params,
 * hooks, dbtable reads, ...) are handled in {@link CsSim} itself and listed in the file as {@code #special}/{@code #hook}.
 * <p>
 * Each argument/definition is {@code S:type} (string stack) or {@code I:type} (int stack), arguments listed bottom
 * to top as the script pushes them; the type is a cs2 type literal (graphic, model, seq, component, obj, ...).
 */
public final class CsCommands {
    /** One command: arguments (bottom to top) and definitions (pushed results), with stack and type per entry. */
    public record Cmd(int op, String name, boolean[] argString, String[] argType, boolean[] defString, String[] defType) {
        public int intArgs() { int n = 0; for (boolean s : argString) if (!s) n++; return n; }
        public int strArgs() { int n = 0; for (boolean s : argString) if (s) n++; return n; }
    }

    private final Map<Integer, Cmd> basic = new HashMap<>();
    private final Map<Integer, String> special = new HashMap<>();

    private static CsCommands instance;

    /** The table from the module resources (cached). */
    public static synchronized CsCommands get() {
        if (instance == null) {
            CsCommands c = new CsCommands();
            c.load("/ui/cs2-commands-211.tsv");
            c.load("/ui/cs2-commands-211-extra.tsv");
            instance = c;
        }
        return instance;
    }

    private void load(String resource) {
        try (InputStream in = CsCommands.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing resource " + resource);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String l; (l = r.readLine()) != null; ) {
                if (l.isBlank()) continue;
                String[] f = l.split("\t");
                if (l.startsWith("#special") || l.startsWith("#hook")) { special.put(Integer.parseInt(f[1]), f[2]); continue; }
                if (l.startsWith("#")) continue;
                int op = Integer.parseInt(f[0]);
                String[][] a = parse(f[2]), d = parse(f[3]);
                boolean[] as = new boolean[a.length], ds = new boolean[d.length];
                String[] at = new String[a.length], dt = new String[d.length];
                for (int i = 0; i < a.length; i++) { as[i] = a[i][0].equals("S"); at[i] = a[i][1]; }
                for (int i = 0; i < d.length; i++) { ds[i] = d[i][0].equals("S"); dt[i] = d[i][1]; }
                basic.put(op, new Cmd(op, f[1], as, at, ds, dt));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String[][] parse(String s) {
        if (s.equals("-")) return new String[0][];
        String[] parts = s.split(",");
        String[][] out = new String[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            String[] kv = parts[i].split(":");
            if (kv.length != 2 || !(kv[0].equals("I") || kv[0].equals("S"))) throw new IllegalStateException("bad stack entry " + parts[i]);
            out[i] = kv;
        }
        return out;
    }

    /** The fixed-signature command, or null (special or unknown). */
    public Cmd cmd(int op) { return basic.get(op); }
    /** Name of a command handled in code (gosub, enum, hooks, ...), or null. */
    public String special(int op) { return special.get(op); }
    public boolean known(int op) { return basic.containsKey(op) || special.containsKey(op) || CsSim.isHandledInCode(op); }
    public int size() { return basic.size(); }
}

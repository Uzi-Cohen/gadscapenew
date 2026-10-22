package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.verify.Findings.Severity;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The client-decode oracles (ported from {@code rev241/tools/cdec}: ClientDecode/Oracle, ModelOracle, UiOracle): feeds
 * the cache's config entries, models, interface components and clientscripts through the real build-241 client
 * decoder classes, offline, loaded from the RuneLite injected client jar(s) in a library directory. Anything the
 * client's own decoder throws on is a finding ({@code oracle/...}).
 * <p>
 * The decoder classes have obfuscated names that belong to one client build; the defaults are those of
 * injected-client 1.13.3 (the build GadScape's launcher pins) and can be overridden:
 * <pre>
 *   --oracle.lib=DIR            jars to load (default ROOT/rev241/inputs/runelite)
 *   --oracle.buffer=xw          the client's byte buffer class (constructor from byte[])
 *   --oracle.configs=6:or,9:ps,10:ok,12:oh   config group : decoder class (constructor (buffer, ...))
 *   --oracle.model=ee           model class (constructor from byte[])
 *   --oracle.component=ld:aj:af component class : IF1 decode method : IF3 decode method (buffer, int)
 *   --oracle.script=ae:ao       script class : static decode method (byte[])
 * </pre>
 * Errors whose stack mentions RuneLite's {@code getCallbacks} come from injected hooks with no client running and
 * count as decoded (same rule as the old tools). So do null pointers on a static field of the client ("because
 * \"pw.am\" is null": an archive or table the offline harness never set up, e.g. what obj param lookups use): they
 * are counted under {@code oracle.harnessSkips}, not as decode failures. Groups too short to be a script are skipped.
 */
public final class ClientOracle {
    private final URLClassLoader cl;
    private final Constructor<?> buffer;
    private final Map<String, String> opt;

    private ClientOracle(URLClassLoader cl, Constructor<?> buffer, Map<String, String> opt) { this.cl = cl; this.buffer = buffer; this.opt = opt; }

    public static final Map<String, String> DEFAULTS = Map.of(
            "buffer", "xw", "configs", "6:or,9:ps,10:ok,12:oh", "model", "ee", "component", "ld:aj:af", "script", "ae:ao");

    /** Loads the client classes; null (with the reason in {@code why[0]}) if the library is not usable. */
    public static ClientOracle open(Path lib, Map<String, String> overrides, String[] why) {
        try {
            if (lib == null || !Files.isDirectory(lib)) { why[0] = "no client library directory " + lib; return null; }
            List<URL> urls = new ArrayList<>();
            try (var s = Files.list(lib)) {
                for (Path p : (Iterable<Path>) s.sorted()::iterator) if (p.toString().endsWith(".jar")) urls.add(p.toUri().toURL());
            }
            if (urls.isEmpty()) { why[0] = "no jars in " + lib; return null; }
            Map<String, String> o = new TreeMap<>(DEFAULTS);
            if (overrides != null) o.putAll(overrides);
            URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
            Constructor<?> buf = cl.loadClass(o.get("buffer")).getConstructor(byte[].class);
            return new ClientOracle(cl, buf, o);
        } catch (Throwable e) {
            why[0] = "client classes not loadable: " + e;
            return null;
        }
    }

    static String root(Throwable e) {
        Throwable t = e instanceof InvocationTargetException ? e.getCause() : e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        StackTraceElement[] s = t.getStackTrace();
        return t.getClass().getSimpleName() + " at " + (s.length > 0 ? s[0].getClassName() + "." + s[0].getMethodName() : "?")
                + (s.length > 1 ? " <- " + s[1].getClassName() + "." + s[1].getMethodName() : "") + (t instanceof NullPointerException ? " (" + t.getMessage() + ")" : "");
    }

    private static final java.util.regex.Pattern STATIC_NULL = java.util.regex.Pattern.compile("because \"[A-Za-z_$][\\w$]*\\.[A-Za-z_$][\\w$]*\" is null");

    /** A null static field of the client (harness limitation, not a decode failure). */
    static boolean harness(Throwable e) {
        Throwable t = e instanceof InvocationTargetException ? e.getCause() : e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t instanceof NullPointerException && t.getMessage() != null && STATIC_NULL.matcher(t.getMessage()).find();
    }

    private final java.util.TreeMap<String, Integer> skips = new java.util.TreeMap<>();

    /** Counts of harness skips per check (for the findings' info). */
    public java.util.Map<String, Integer> skips() { return skips; }

    static boolean callbacks(Throwable e) {
        Throwable t = e instanceof InvocationTargetException ? e.getCause() : e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        StringBuilder st = new StringBuilder();
        for (StackTraceElement el : t.getStackTrace()) st.append(el.getMethodName()).append(' ');
        return st.toString().contains("getCallbacks") || String.valueOf(t.getMessage()).contains("getCallbacks") || String.valueOf(t.getMessage()).contains("fz.cw");
    }

    /** Every config group named in --oracle.configs. */
    public void configs(CacheView c, Findings f) {
        for (String spec : opt.get("configs").split(",")) {
            String[] gc = spec.split(":");
            int g = Integer.parseInt(gc[0].trim());
            String name = "oracle/config-" + Arch.configName(g);
            Constructor<?> ctor;
            try {
                ctor = null;
                for (Constructor<?> k : cl.loadClass(gc[1].trim()).getDeclaredConstructors())
                    if (k.getParameterCount() > 0 && k.getParameterTypes()[0] == buffer.getDeclaringClass()) ctor = k;
                if (ctor == null) throw new IllegalStateException("no (buffer, ...) constructor in " + gc[1]);
                ctor.setAccessible(true);
            } catch (Throwable e) {
                f.bad(name, Severity.WARNING, "decoder " + gc[1], "decoder class " + gc[1] + " not usable: " + e);
                continue;
            }
            for (int id : c.configIds(g)) {
                byte[] b = c.configRef(g, id);
                if (b == null) continue;
                String err = decodeConfig(ctor, id, b);
                if (err != null && err.isEmpty()) { skips.merge(name, 1, Integer::sum); continue; }
                if (err == null) f.ok(name, Severity.ERROR);
                else f.bad(name, Severity.ERROR, Arch.configName(g) + " " + id, Arch.configName(g) + " " + id + ": " + err);
            }
        }
        c.release(Arch.CONFIGS);
    }

    private String decodeConfig(Constructor<?> ctor, int id, byte[] b) {
        try {
            Object[] args = new Object[ctor.getParameterCount()];
            args[0] = buffer.newInstance((Object) b.clone());
            Class<?>[] pt = ctor.getParameterTypes();
            int ints = 0;
            for (int i = 1; i < args.length; i++)
                args[i] = pt[i] == int.class ? (Object) (ints++ == 0 ? id : 0) : pt[i] == boolean.class ? (Object) Boolean.TRUE : pt[i] == byte.class ? (Object) (byte) 0
                        : pt[i] == short.class ? (Object) (short) 0 : pt[i] == long.class ? (Object) 0L : null;
            ctor.newInstance(args);
            return null;
        } catch (InvocationTargetException e) {
            if (callbacks(e)) return null;
            if (harness(e)) return "";
            return root(e);
        } catch (Throwable e) {
            return "harness: " + e;
        }
    }

    public void models(CacheView c, Findings f) {
        Constructor<?> ctor;
        try {
            ctor = cl.loadClass(opt.get("model")).getDeclaredConstructor(byte[].class);
            ctor.setAccessible(true);
        } catch (Throwable e) {
            f.bad("oracle/models", Severity.WARNING, "model class", "model class not usable: " + e);
            return;
        }
        int n = 0;
        for (int id : c.groupIds(Arch.MODELS)) {
            byte[] b = c.single(Arch.MODELS, id);
            if (b == null) { f.bad("oracle/models", Severity.ERROR, "model " + id, "model " + id + " has no data"); continue; }
            try {
                ctor.newInstance((Object) b);
                f.ok("oracle/models", Severity.ERROR);
            } catch (Throwable e) {
                if (harness(e)) skips.merge("oracle/models", 1, Integer::sum);
                else if (callbacks(e)) f.ok("oracle/models", Severity.ERROR);
                else f.bad("oracle/models", Severity.ERROR, "model " + id, "model " + id + ": " + root(e) + " (tail " + (b.length > 1 ? b[b.length - 2] + "," + b[b.length - 1] : "") + ")");
            }
            if (++n % 3000 == 0) c.release(Arch.MODELS);
        }
        c.release(Arch.MODELS);
    }

    public void interfaces(CacheView c, Findings f) {
        String[] spec = opt.get("component").split(":");
        Object w;
        Method if1, if3;
        try {
            Class<?> ld = cl.loadClass(spec[0]);
            Constructor<?> wc = ld.getConstructor();
            if1 = ld.getDeclaredMethod(spec[1], buffer.getDeclaringClass(), int.class);
            if3 = ld.getDeclaredMethod(spec[2], buffer.getDeclaringClass(), int.class);
            if1.setAccessible(true); if3.setAccessible(true);
            w = wc;
        } catch (Throwable e) {
            f.bad("oracle/interfaces", Severity.WARNING, "component class", "component class not usable: " + e);
            return;
        }
        Constructor<?> wc = (Constructor<?>) w;
        for (int g : c.groupIds(Arch.INTERFACES)) {
            GroupData d = c.group(Arch.INTERFACES, g);
            if (d == null) continue;
            for (int i = 0; i < d.fileCount(); i++) {
                byte[] b = d.dataAt(i);
                if (b.length == 0) continue;
                try {
                    Object comp = wc.newInstance();
                    (b[0] == -1 ? if3 : if1).invoke(comp, buffer.newInstance((Object) b.clone()), 0);
                    f.ok("oracle/interfaces", Severity.ERROR);
                } catch (Throwable e) {
                    if (harness(e)) skips.merge("oracle/interfaces", 1, Integer::sum);
                    else if (callbacks(e)) f.ok("oracle/interfaces", Severity.ERROR);
                    else f.bad("oracle/interfaces", Severity.ERROR, "component " + g + ":" + d.fileIdAt(i), "component " + g + ":" + d.fileIdAt(i) + ": " + root(e));
                }
            }
        }
        c.release(Arch.INTERFACES);
    }

    public void scripts(CacheView c, Findings f) {
        String[] spec = opt.get("script").split(":");
        Method dec;
        try {
            dec = cl.loadClass(spec[0]).getDeclaredMethod(spec[1], byte[].class);
            dec.setAccessible(true);
        } catch (Throwable e) {
            f.bad("oracle/scripts", Severity.WARNING, "script class", "script class not usable: " + e);
            return;
        }
        for (int id : c.groupIds(Arch.SCRIPTS)) {
            byte[] b = c.single(Arch.SCRIPTS, id);
            if (b == null || b.length < CsWorld.MIN_SCRIPT) continue; // stubs (stock script 0) are never run
            try {
                dec.invoke(null, (Object) b);
                f.ok("oracle/scripts", Severity.ERROR);
            } catch (Throwable e) {
                if (harness(e)) skips.merge("oracle/scripts", 1, Integer::sum);
                else if (callbacks(e)) f.ok("oracle/scripts", Severity.ERROR);
                else f.bad("oracle/scripts", Severity.ERROR, "script " + id, "script " + id + ": " + root(e));
            }
        }
        c.release(Arch.SCRIPTS);
    }

    public void close() { try { cl.close(); } catch (Exception ignored) { } }
}

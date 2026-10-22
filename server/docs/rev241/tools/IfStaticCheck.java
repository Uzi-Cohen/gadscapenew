import com.zenyte.CacheManager;
import com.zenyte.game.GameInterface;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.model.ui.InterfacePosition;
import com.zenyte.game.model.ui.PaneType;
import com.zenyte.game.model.ui.UserInterface;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import mgi.tools.jagcached.cache.Archive;
import mgi.tools.jagcached.cache.Cache;
import mgi.tools.jagcached.cache.Group;
import mgi.types.component.ComponentDefinitions;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cache side of the rev241 interface static check (see static_check.py, which runs it).
 *
 * <pre>
 * java -cp app-all.jar:. IfStaticCheck dump    &lt;cacheDir&gt; &lt;out.tsv&gt;   every component of the cache, decoded by the server
 * java -cp app-all.jar:. IfStaticCheck runtime &lt;cacheDir&gt; &lt;out.tsv&gt;   the (interface, component) pairs the server's own
 *                                                                        tables name at run time
 * </pre>
 *
 * dump: {@code iface comp type if3 parent hidden name} (name = the cache's gamevals component name, archive 24 group 14,
 * empty when the cache has none).
 * runtime: {@code kind iface comp where note}; kinds: {@code gameinterface} (comp -1: the interface a GameInterface
 * constant opens, after Gad211.iface), {@code position} (the pane component an InterfacePosition resolves to on each
 * pane, through the pane enums 1129-1132/1745 like InterfaceHandler), {@code put} (every put(component, name) of every
 * Interface plugin after attach(), so names bound with bind() are checked through their put), {@code userif} (comp
 * -1: getInterfaceIds() of every UserInterface plugin), {@code error} (a plugin the tool could not instantiate or whose
 * attach()/build() threw: check it by hand).
 */
public final class IfStaticCheck {

    public static void main(final String[] a) throws Exception {
        if (a.length != 3) {
            System.err.println("usage: IfStaticCheck dump|runtime <cacheDir> <out.tsv>");
            System.exit(2);
        }
        CacheManager.loadCacheFiles(a[1], true);
        if ("dump".equals(a[0])) {
            dump(a[2]);
        } else if ("runtime".equals(a[0])) {
            CacheManager.loadAllDefinitions();
            runtime(a[2]);
        } else {
            throw new IllegalArgumentException(a[0]);
        }
        System.exit(0);
    }

    private static Map<Integer, String> componentNames() {
        final Map<Integer, String> names = new TreeMap<>();
        try {
            final Cache cache = CacheManager.getCache();
            if (cache.getArchiveCount() <= 24) {
                return names;
            }
            final Archive archive = cache.getArchive(24);
            if (archive == null) {
                return names;
            }
            final Group group = archive.findGroupByID(14);
            if (group == null) {
                return names;
            }
            for (final mgi.tools.jagcached.cache.File f : group.getFiles()) {
                if (f == null || f.getData() == null) {
                    continue;
                }
                final byte[] b = f.getData().getBuffer();
                int p = 0;
                final StringBuilder iface = new StringBuilder();
                while (p < b.length && b[p] != 0) iface.append((char) (b[p++] & 0xFF));
                p++;
                names.put(f.getID() << 16 | 0xFFFF, iface.toString());
                while (p + 1 < b.length) {
                    final int comp = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
                    p += 2;
                    if (comp == 0xFFFF) {
                        break;
                    }
                    final StringBuilder cn = new StringBuilder();
                    while (p < b.length && b[p] != 0) cn.append((char) (b[p++] & 0xFF));
                    p++;
                    names.put(f.getID() << 16 | comp, cn.toString());
                }
            }
        } catch (final Throwable t) {
            System.err.println("no gamevals component names: " + t);
        }
        return names;
    }

    private static void dump(final String out) throws Exception {
        new ComponentDefinitions().load();
        final Map<Integer, String> names = componentNames();
        final TreeMap<Integer, ComponentDefinitions> sorted = new TreeMap<>(ComponentDefinitions.definitions);
        try (PrintWriter w = new PrintWriter(new FileWriter(out))) {
            w.println("#iface\tcomp\ttype\tif3\tparent\thidden\tname");
            for (final Map.Entry<Integer, ComponentDefinitions> e : sorted.entrySet()) {
                final int iface = e.getKey() >>> 16;
                final int comp = e.getKey() & 0xFFFF;
                final ComponentDefinitions d = e.getValue();
                final int parent = d.parentId == -1 ? -1 : d.parentId & 0xFFFF;
                String name = names.getOrDefault(e.getKey(), "");
                if (comp == 0 && names.containsKey(iface << 16 | 0xFFFF)) {
                    name = names.get(iface << 16 | 0xFFFF) + (name.isEmpty() ? "" : ":" + name);
                }
                w.println(iface + "\t" + comp + "\t" + d.type + "\t" + (d.isIf3 ? 1 : 0) + "\t" + parent + "\t"
                        + (d.hidden ? 1 : 0) + "\t" + name);
            }
        }
    }

    private static void row(final PrintWriter w, final String kind, final int iface, final int comp, final String where,
                            final String note) {
        w.println(kind + "\t" + iface + "\t" + comp + "\t" + where + "\t" + note.replace('\t', ' ').replace('\n', ' '));
    }

    @SuppressWarnings("unchecked")
    private static void runtime(final String out) throws Exception {
        try (PrintWriter w = new PrintWriter(new FileWriter(out))) {
            w.println("#kind\tiface\tcomp\twhere\tnote");
            for (final GameInterface gi : GameInterface.values) {
                row(w, "gameinterface", gi.getId(), -1, "GameInterface." + gi.name(),
                        gi.getPosition() == null ? "" : String.valueOf(gi.getPosition()));
            }
            final PaneType[] panes = {PaneType.RESIZABLE, PaneType.FIXED, PaneType.SIDE_PANELS, PaneType.FULL_SCREEN,
                    PaneType.MOBILE};
            for (final InterfacePosition position : InterfacePosition.VALUES) {
                final String where = "InterfacePosition." + position.name();
                if (position == InterfacePosition.DIALOGUE) {
                    row(w, "position", PaneType.CHATBOX.getId(), position.getComponent(PaneType.RESIZABLE), where,
                            "CHATBOX");
                    continue;
                }
                if (position == InterfacePosition.TOA_MANAGEMENT) {
                    row(w, "position", PaneType.TOA_MANAGEMENT.getId(), position.getComponent(PaneType.TOA_MANAGEMENT),
                            where, "TOA_MANAGEMENT");
                    continue;
                }
                if (position == InterfacePosition.COLOUR_PICKER) {
                    // opened by SettingsInterface into the advanced settings interface (134), not a toplevel pane
                    row(w, "position", PaneType.ADVANCED_SETTINGS.getId(), position.getResizableComponent(), where,
                            "ADVANCED_SETTINGS");
                    continue;
                }
                for (final PaneType pane : panes) {
                    int comp;
                    try {
                        comp = position.getComponent(pane);
                    } catch (final Throwable t) {
                        row(w, "error", pane.getId(), -1, where, "getComponent(" + pane + "): " + t);
                        continue;
                    }
                    row(w, "position", pane.getId(), comp, where, pane.name()
                            + (position.getGameframeInterfaceId() == -1 ? "" : " tab " + position.getGameframeInterfaceId()));
                }
                if (position.getGameframeInterfaceId() != -1) {
                    row(w, "gameinterface", position.getGameframeInterfaceId(), -1, where, "gameframe interface");
                }
            }
            final Field componentsField = Interface.class.getDeclaredField("components");
            componentsField.setAccessible(true);
            final Method attach = Interface.class.getDeclaredMethod("attach");
            attach.setAccessible(true);
            final Method build = Interface.class.getDeclaredMethod("build");
            build.setAccessible(true);
            try (ScanResult scan = new ClassGraph().enableClassInfo().ignoreClassVisibility()
                    .acceptPackages("com.zenyte", "com.gadscape").scan()) {
                for (final ClassInfo info : scan.getSubclasses(Interface.class.getName())) {
                    if (info.isAbstract() || info.isInterface()) {
                        continue;
                    }
                    final String where = info.getName();
                    final Interface inst;
                    try {
                        final Class<?> cls = info.loadClass();
                        if (Modifier.isAbstract(cls.getModifiers())) {
                            continue;
                        }
                        final Constructor<?> c = cls.getDeclaredConstructor();
                        c.setAccessible(true);
                        inst = (Interface) c.newInstance();
                        attach.invoke(inst);
                    } catch (final Throwable t) {
                        row(w, "error", -1, -1, where, "instantiate/attach: " + root(t));
                        continue;
                    }
                    int iface;
                    try {
                        iface = inst.getInterface().getId();
                    } catch (final Throwable t) {
                        row(w, "error", -1, -1, where, "getInterface: " + root(t));
                        continue;
                    }
                    final Int2ObjectMap<String> comps = (Int2ObjectMap<String>) componentsField.get(inst);
                    for (final Int2ObjectMap.Entry<String> e : comps.int2ObjectEntrySet()) {
                        final int comp = (e.getIntKey() >>> 16) & 0xFFFF;
                        final int slot = e.getIntKey() & 0xFFFF;
                        row(w, "put", iface, comp, where, "\"" + e.getValue() + "\""
                                + (slot == Interface.DEFAULT_SLOT_ID ? "" : " slot " + slot));
                    }
                    if (comps.isEmpty()) {
                        row(w, "gameinterface", iface, -1, where, "Interface plugin without put()");
                    }
                    try {
                        build.invoke(inst);
                    } catch (final Throwable t) {
                        row(w, "error", iface, -1, where, "build: " + root(t));
                    }
                }
                for (final ClassInfo info : scan.getClassesImplementing(UserInterface.class.getName())) {
                    if (info.isAbstract() || info.isInterface()) {
                        continue;
                    }
                    final String where = info.getName();
                    try {
                        final Class<?> cls = info.loadClass();
                        final Constructor<?> c = cls.getDeclaredConstructor();
                        c.setAccessible(true);
                        final UserInterface inst = (UserInterface) c.newInstance();
                        for (final int id : inst.getInterfaceIds()) {
                            row(w, "userif", id, -1, where, "getInterfaceIds");
                        }
                    } catch (final Throwable t) {
                        row(w, "error", -1, -1, where, "UserInterface: " + root(t));
                    }
                }
            }
        }
    }

    private static String root(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.toString();
    }
}

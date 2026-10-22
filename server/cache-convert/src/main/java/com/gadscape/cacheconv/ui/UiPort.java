package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.core.Options;

import java.nio.file.Path;

/**
 * {@code gadconv port-interface}: ports interfaces of a build-211 cache, and the custom clientscripts they need, into a
 * copy of a build-241 cache, with the same rules as the full build (ui-relocate, ui-convert, ui-check):
 * <pre>
 *   gadconv port-interface --from=DIR211 [--into=DIR241] [--van211=DIR] --out=DIR [--force] [--dry-run]
 *                          [--interfaces=1600,1700-1702] [--scripts=10500,10501] [--idmap=IDMAP.json[,...]]
 *                          [--report-dir=DIR] [--interface-range=5200-5299] [--component-remap=TSV]
 * </pre>
 * <ul>
 *   <li>{@code --from}: the 211 cache holding the interfaces (default USER); {@code --into}: the 241 cache the output
 *       starts from (default BASE); {@code --van211}: vanilla 211, to tell custom content from vanilla;</li>
 *   <li>{@code --interfaces}: the interfaces to port (default: every interface --from adds over vanilla 211);
 *       {@code --scripts}: more scripts to port (those only the server runs); every custom script the
 *       selected interfaces and scripts reach is ported too;</li>
 *   <li>{@code --idmap}: relocations already decided for the target (an earlier build's idmap.json), so references
 *       to moved sprites/models/seqs/configs are rewritten;</li>
 *   <li>ids the 241 cache already uses move to the reserved ranges (scripts 47000-47999, interfaces 5200-5299); the
 *       idmap.json and report.json written to --report-dir (default: --out) list every move and decision.</li>
 * </ul>
 * The cli's PortInterfaceCmd delegates here ({@code return UiPort.run(o);}).
 */
public final class UiPort {
    private UiPort() {}

    public static int run(Options o) throws Exception {
        String from = o.get("from", null), into = o.get("into", null), van = o.get("van211", null), out = o.get("out", null), rd = o.get("report-dir", null);
        if (from != null) o.user = Path.of(from);
        if (into != null) o.base = Path.of(into);
        if (van != null) o.van211 = Path.of(van);
        if (out != null) o.out = Path.of(out);
        if (rd != null) o.reportDir = Path.of(rd);
        copy(o, "interfaces", "ui.interfaces");
        copy(o, "scripts", "ui.scripts");
        copy(o, "idmap", "ui.preload");
        copy(o, "interface-range", "ui.interfaceRange");
        copy(o, "component-remap", "ui.componentRemap");
        o.require("user (--from)", o.user);
        o.require("van211", o.van211);
        if (o.out == null) throw new IllegalArgumentException("--out is required");
        // assets the 211 cache adds (sprites, fonts) are copied when missing; models need the full build (ui-check warns)
        o.extra.putIfAbsent("ui.standalone", "true");
        Path reportDir = o.reportDir != null ? o.reportDir : o.out;
        return UiMain.run(o, reportDir);
    }

    private static void copy(Options o, String from, String to) {
        String v = o.extra.get(from);
        if (v != null && !o.extra.containsKey(to)) o.extra.put(to, v);
    }

    public static void main(String[] args) throws Exception { System.exit(run(Options.parse(args, 0))); }
}

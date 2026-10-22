package com.gadscape.cacheconv.uimerge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Interface ids the GadScape server code references (the same scan as server/docs/rev241/tools/build_remap.py):
 * GameInterface enum entries, the toplevel/pane ids, literal first arguments of sendComponent / sendInterface /
 * ComponentDefinitions.get / sendLineSpacing / sendClearItemContainer, sendUpdateItem* interface arguments,
 * getInterfaceIds arrays and literal {@code N << 16} expressions. Build, assets and this module are skipped.
 * <p>
 * Named constants count too (audit: the first scan missed every interface the code names through one): an int
 * constant whose name contains {@code INTERFACE} ({@code PollManager.INTERFACE_ID = 345}, {@code VOTING_INTERFACE_ID
 * = 400}, {@code GrandExchange.INVENTORY_INTERFACE = 467}, Kotlin {@code const val ... = N}), and any int constant of
 * the same file that is the first argument of one of the calls above ({@code sendComponent(ID, ...)}).
 */
public final class ServerScan {
    private ServerScan() {}

    private static final Pattern GI = Pattern.compile("^\\s*[A-Z_0-9]+\\(\\s*(\\d+)", Pattern.MULTILINE);
    private static final Pattern CALLS = Pattern.compile("(?:sendComponent\\w*|sendClearItemContainer|sendInterface|ComponentDefinitions\\.get|sendLineSpacing\\w*)\\(\\s*(\\d+)\\s*,");
    private static final Pattern UPDATE = Pattern.compile("sendUpdateItem\\w*\\(\\s*[^,()]+,\\s*(\\d+)\\s*,\\s*\\d+");
    private static final Pattern IDS = Pattern.compile("getInterfaceIds[\\s\\S]{0,120}?(?:new int\\[\\]\\s*\\{([^}]*)\\}|intArrayOf\\(([^)]*)\\))");
    private static final Pattern SHIFT = Pattern.compile("\\b(\\d{1,4})\\s*<<\\s*16\\b");
    private static final Pattern NUM = Pattern.compile("\\b\\d+\\b");
    /** {@code static final int X_INTERFACE_ID = 345;} / {@code const val INTERFACE: Int = 345} (name contains INTERFACE). */
    private static final Pattern NAMED = Pattern.compile("\\b(?:final\\s+int|val)\\s+[A-Z0-9_]*INTERFACE[A-Z0-9_]*\\s*(?::\\s*Int)?\\s*=\\s*(\\d{1,4})\\b");
    /** Any int constant: name -> value (for constants passed to the calls). */
    private static final Pattern CONST = Pattern.compile("\\b(?:final\\s+int|val)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*Int)?\\s*=\\s*(\\d{1,4})\\s*[;\\n]");
    private static final Pattern CALLS_NAMED = Pattern.compile("(?:sendComponent\\w*|sendClearItemContainer|sendInterface|ComponentDefinitions\\.get|sendLineSpacing\\w*)\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,");

    /** Fixed ids the old scan always added (toplevels, panes and interfaces opened through computed ids). */
    public static final int[] FIXED = {548, 161, 164, 165, 601, 16, 80, 162, 293, 629, 134, 707, 727, 730, 724, 774};

    public static TreeSet<Integer> interfaces(Path server) throws IOException {
        TreeSet<Integer> ids = new TreeSet<>();
        for (int f : FIXED) ids.add(f);
        Path gi = server.resolve("core/src/main/java/com/zenyte/game/GameInterface.java");
        if (Files.isRegularFile(gi)) { Matcher m = GI.matcher(Files.readString(gi)); while (m.find()) ids.add(Integer.parseInt(m.group(1))); }
        List<Path> src;
        try (Stream<Path> s = Files.walk(server)) {
            src = s.filter(p -> {
                String n = p.toString();
                return (n.endsWith(".java") || n.endsWith(".kt")) && !n.contains("/build/") && !n.contains("/assets/") && !n.contains("/.gradle/") && !n.contains("/cache-convert/");
            }).sorted().toList();
        }
        for (Path p : src) {
            String t = Files.readString(p, java.nio.charset.StandardCharsets.ISO_8859_1);
            Matcher m = CALLS.matcher(t); while (m.find()) ids.add(Integer.parseInt(m.group(1)));
            m = UPDATE.matcher(t); while (m.find()) ids.add(Integer.parseInt(m.group(1)));
            m = IDS.matcher(t);
            while (m.find()) { Matcher n = NUM.matcher((m.group(1) == null ? "" : m.group(1)) + " " + (m.group(2) == null ? "" : m.group(2))); while (n.find()) ids.add(Integer.parseInt(n.group())); }
            m = SHIFT.matcher(t); while (m.find()) ids.add(Integer.parseInt(m.group(1)));
            m = NAMED.matcher(t); while (m.find()) ids.add(Integer.parseInt(m.group(1)));
            java.util.Map<String, Integer> consts = new java.util.HashMap<>();
            m = CONST.matcher(t); while (m.find()) consts.put(m.group(1), Integer.parseInt(m.group(2)));
            m = CALLS_NAMED.matcher(t); while (m.find()) { Integer v = consts.get(m.group(1)); if (v != null) ids.add(v); }
        }
        return ids;
    }
}

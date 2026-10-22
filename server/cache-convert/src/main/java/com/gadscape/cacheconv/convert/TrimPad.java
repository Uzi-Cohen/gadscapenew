package com.gadscape.cacheconv.convert;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.OutCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes zero padding the strict cache verifier reports ("X id has N unread bytes"), only when those N bytes are all
 * zero. Ported from rev241/tools/TrimPad (which ran over cacheU after the config conversion).
 */
public final class TrimPad {
    private TrimPad() {}

    /** Config group per verifier type name. */
    public static final Map<String, Integer> GROUPS = Map.of("item", Arch.CFG_OBJ, "spotanim", Arch.CFG_SPOT, "enum", Arch.CFG_ENUM, "struct", Arch.CFG_STRUCT,
            "param", Arch.CFG_PARAM, "varbit", Arch.CFG_VARBIT, "inventory", Arch.CFG_INV, "dbrow", Arch.CFG_DBROW, "npc", Arch.CFG_NPC, "object", Arch.CFG_LOC);

    private static final Pattern LINE = Pattern.compile("(item|spotanim|enum|struct|param|varbit|inventory|dbrow|component|npc|object) (\\d+) has (\\d+) unread bytes");

    /** One verifier finding: type, id (component ids are interface << 16 | component), unread byte count. */
    public record Finding(String type, long id, int n) {}

    /** Findings in a verifier log, first occurrence of each type+id only. */
    public static List<Finding> parse(String log) {
        Matcher m = LINE.matcher(log);
        Set<String> seen = new HashSet<>();
        List<Finding> out = new ArrayList<>();
        while (m.find()) {
            String t = m.group(1); long id = Long.parseLong(m.group(2)); int n = Integer.parseInt(m.group(3));
            if (seen.add(t + id)) out.add(new Finding(t, id, n));
        }
        return out;
    }

    /** b without its last n bytes; IllegalStateException if they are not all zero. */
    public static byte[] trim(String what, byte[] b, int n) {
        if (n > b.length || !Bytes.isZero(b, b.length - n, b.length)) throw new IllegalStateException(what + " tail is not padding");
        return Arrays.copyOf(b, b.length - n);
    }

    /** Applies the findings to the output cache; returns counts per type. */
    public static Map<String, Integer> apply(OutCache out, List<Finding> findings) {
        Map<String, Integer> done = new TreeMap<>();
        for (Finding f : findings) {
            boolean comp = f.type.equals("component");
            int archive = comp ? Arch.INTERFACES : Arch.CONFIGS;
            int group = comp ? (int) (f.id >> 16) : GROUPS.get(f.type);
            int file = comp ? (int) (f.id & 0xffff) : (int) f.id;
            byte[] b = out.file(archive, group, file);
            if (b == null) continue;
            out.putFile(archive, group, file, trim(f.type + " " + f.id, b, f.n));
            done.merge(f.type, 1, Integer::sum);
        }
        return done;
    }
}

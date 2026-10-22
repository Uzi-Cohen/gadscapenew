package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.Kind;

/**
 * Typed references in GadScape's own code and component fields, and how they follow relocations at build time.
 * Tags are cs2 type names (as {@code ui.CsSim} types constants: graphic, obj, component, clientscript, ...) plus
 * "script", "varp", "varbit" and "varc".
 */
public final class RefKinds {
    private RefKinds() {}

    /** The id-map kind of a tag, or null (fonts, colours, coords, stats ... never move). */
    public static Kind kind(String tag) {
        if (tag == null) return null;
        return switch (tag) {
            case "graphic", "sprite" -> Kind.SPRITE;
            case "model" -> Kind.MODEL;
            case "seq" -> Kind.SEQ;
            case "obj", "namedobj" -> Kind.OBJ;
            case "npc" -> Kind.NPC;
            case "loc" -> Kind.LOC;
            case "spotanim", "spot" -> Kind.SPOT;
            case "enum" -> Kind.ENUM;
            case "struct" -> Kind.STRUCT;
            case "param" -> Kind.PARAM;
            case "dbrow" -> Kind.DBROW;
            case "dbtable" -> Kind.DBTABLE;
            case "varp" -> Kind.VARP;
            case "varbit" -> Kind.VARBIT;
            case "varc" -> Kind.VARC;
            case "clientscript", "script" -> Kind.SCRIPT;
            case "interface" -> Kind.INTERFACE;
            default -> null;
        };
    }

    /** Whether a tag names something whose ids can move (so it is worth recording). */
    public static boolean movable(String tag) {
        return kind(tag) != null || "component".equals(tag) || "dbcolumn".equals(tag);
    }

    /** The build-241 value of a GadScape reference: null values (-1, MIN) and event constants never move. */
    public static int map(IdMap ids, String tag, int v) {
        if (v == -1 || v == Integer.MIN_VALUE || (v < 0 && v <= Integer.MIN_VALUE + 64)) return v;
        if (tag == null) return v;
        switch (tag) {
            case "component" -> {
                if (v < 0) return v;
                int g = v >>> 16, ng = ids.map(Kind.INTERFACE, g);
                return ng == g ? v : (ng << 16) | (v & 0xffff);
            }
            case "dbcolumn" -> {
                if (v < 0) return v;
                int t = v >>> 12, nt = ids.map(Kind.DBTABLE, t);
                return nt == t ? v : (nt << 12) | (v & 0xfff);
            }
            default -> {
                Kind k = kind(tag);
                return k == null || v < 0 ? v : ids.map(k, v);
            }
        }
    }

    /** Tag implied by an instruction's opcode for GadScape lines (gosub, var reads/writes), or null. */
    public static String implied(int op) {
        return switch (op) {
            case 40 -> "script";
            case 1, 2 -> "varp";
            case 25, 27 -> "varbit";
            case 42, 43, 49, 50 -> "varc";
            default -> null;
        };
    }
}

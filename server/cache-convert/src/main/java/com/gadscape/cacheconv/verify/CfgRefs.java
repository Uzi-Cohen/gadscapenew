package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Buf;

/**
 * Reads every id reference out of a build-241 config entry, walking exactly the opcodes the 241 client reads (an
 * independent walker: the verifier does not share code with the converters it checks). Throws
 * {@link IllegalStateException} on an unknown opcode, a truncated entry or bytes after the end opcode (zero padding
 * after the end opcode is accepted, as the client ignores it).
 * <p>
 * Targets ({@link Target}) are what the id must exist as: models, seqs, objs, npcs, locs, spotanims, varbits, varps,
 * sprites, params, structs, enums, dbrows, dbtables, interface components, fonts, inventories and identity kits. Typed
 * values (param values, enum keys and values, param defaults, dbrow/dbtable cells) are checked through their script
 * var type.
 */
public final class CfgRefs {
    private CfgRefs() {}

    /** What a reference must exist as. */
    public enum Target {
        MODEL("model"), SEQ("seq"), OBJ("obj"), NPC("npc"), LOC("loc"), SPOT("spotanim"), VARBIT("varbit"), VARP("varp"),
        SPRITE("sprite"), PARAM("param"), STRUCT("struct"), ENUM("enum"), DBROW("dbrow"), DBTABLE("dbtable"),
        COMPONENT("component"), FONT("font"), INV("inv"), IDKIT("idkit"), SCRIPT("script"), FRAME("frame"),
        FRAMEMAP("framemap"), KEYFRAME("keyframe"), INTERFACE("interface");

        public final String key;
        Target(String key) { this.key = key; }
    }

    /** Receives each reference: target, id, and the field it was read from (e.g. "op 24", "param 451"). */
    @FunctionalInterface
    public interface Sink { void ref(Target target, int id, String field); }

    /** The target a value of this script var type char refers to, or null (plain values, or kinds not checked). */
    public static Target targetOfChar(int c) {
        return switch (c) {
            case 'o', 'O' -> Target.OBJ;
            case 'n' -> Target.NPC;
            case 'l' -> Target.LOC;
            case 'A' -> Target.SEQ;
            case 't' -> Target.SPOT;
            case 'J' -> Target.STRUCT;
            case 'g' -> Target.ENUM;
            case 'd' -> Target.SPRITE;
            case 'm' -> Target.MODEL;
            case 0xD0 -> Target.DBROW;
            case 'I' -> Target.COMPONENT;
            case 'f' -> Target.FONT;
            case 'K' -> Target.IDKIT;
            case 'v' -> Target.INV;
            default -> null;
        };
    }

    /** Whether a config group has a walker. */
    public static boolean walked(int group) {
        return switch (group) {
            case Arch.CFG_OBJ, Arch.CFG_NPC, Arch.CFG_LOC, Arch.CFG_SPOT, Arch.CFG_IDENTKIT, Arch.CFG_SEQ, Arch.CFG_ENUM, Arch.CFG_PARAM,
                 Arch.CFG_STRUCT, Arch.CFG_INV, Arch.CFG_VARBIT, Arch.CFG_DBROW, Arch.CFG_DBTABLE, Arch.CFG_HEALTHBAR -> true;
            default -> false;
        };
    }

    public static void walk(int group, int id, byte[] b, CacheTypes types, Sink sink) {
        String what = Arch.configName(group) + " " + id;
        W w = new W(group, id, b, types, sink);
        try {
            if (group == Arch.CFG_ENUM) w.enumTypes();
            while (true) {
                if (w.in.p >= b.length) throw new IllegalStateException(what + ": no end opcode");
                int start = w.in.p, op = w.in.u8();
                if (op == 0) break;
                w.op(op, start);
            }
            if (w.in.p != b.length) {
                for (int i = w.in.p; i < b.length; i++)
                    if (b[i] != 0) throw new IllegalStateException(what + ": " + (b.length - w.in.p) + " bytes after the end opcode");
            }
        } catch (ArrayIndexOutOfBoundsException | NegativeArraySizeException e) {
            throw new IllegalStateException(what + ": truncated (" + b.length + " bytes)");
        }
    }

    private static final class W {
        final int group, id;
        final byte[] b;
        final Buf in;
        final CacheTypes types;
        final Sink sink;
        int keyType = -1, valType = -1;
        String field;

        W(int group, int id, byte[] b, CacheTypes types, Sink sink) {
            this.group = group; this.id = id; this.b = b; this.in = new Buf(b); this.types = types; this.sink = sink;
        }

        void ref(Target t, int v) { if (v >= 0) sink.ref(t, v, field); }
        void u16(Target t) { int v = in.u16(); if (v != 65535) ref(t, v); }
        void i32(Target t) { ref(t, in.i32()); }
        void u16list(Target t, boolean withType) { int n = in.u8(); for (int i = 0; i < n; i++) { u16(t); if (withType) in.skip(1); } }
        void i32list(Target t, boolean withType) { int n = in.u8(); for (int i = 0; i < n; i++) { i32(t); if (withType) in.skip(1); } }

        void typedInt(int typeChar, int v) {
            Target t = targetOfChar(typeChar);
            if (t == null || v == -1) return;
            if (t == Target.COMPONENT) { ref(Target.COMPONENT, v); return; }
            ref(t, v);
        }

        void unknown(int op, int start) {
            throw new IllegalStateException(Arch.configName(group) + " " + id + ": unknown 241 opcode " + op + " at " + start);
        }

        /** npc 106/118, loc 77/92: varbit, varp, [default (118/92)], count, count + 1 targets. */
        void transforms(int op, int withDefault, Target target) {
            u16(Target.VARBIT); u16(Target.VARP);
            if (op == withDefault) u16(target);
            int n = in.u8();
            for (int i = 0; i <= n; i++) u16(target);
        }

        void params() {
            int n = in.u8();
            for (int i = 0; i < n; i++) {
                int t = in.u8();
                int key = in.u24();
                String f = field;
                field = "param " + key;
                ref(Target.PARAM, key);
                if (t == 1) in.skipStr();
                else if (t == 2) in.skip(8);
                else typedInt(types.paramChar(key), in.i32());
                field = f;
            }
        }

        void condOp(boolean sub) {
            in.u8();
            if (sub) in.u16();
            u16(Target.VARP); u16(Target.VARBIT);
            in.skip(8);
            in.skipStr();
        }

        void op(int op, int start) {
            field = "op " + op;
            switch (group) {
                case Arch.CFG_OBJ -> obj(op, start);
                case Arch.CFG_NPC -> npc(op, start);
                case Arch.CFG_LOC -> loc(op, start);
                case Arch.CFG_SPOT -> spot(op, start);
                case Arch.CFG_IDENTKIT -> kit(op, start);
                case Arch.CFG_SEQ -> seq(op, start);
                case Arch.CFG_ENUM -> enm(op, start);
                case Arch.CFG_PARAM -> param(op, start);
                case Arch.CFG_STRUCT -> { if (op == 249) params(); else unknown(op, start); }
                case Arch.CFG_INV -> inv(op, start);
                case Arch.CFG_VARBIT -> { if (op == 1) { u16(Target.VARP); in.skip(2); } else unknown(op, start); }
                case Arch.CFG_DBROW -> dbrow(op, start);
                case Arch.CFG_DBTABLE -> { if (op == 1) dbColumns(true); else unknown(op, start); }
                case Arch.CFG_HEALTHBAR -> healthbar(op, start);
                default -> unknown(op, start);
            }
        }

        void obj(int op, int start) {
            switch (op) {
                case 1, 23, 24, 25, 26, 78, 79, 90, 91, 92, 93 -> { } // 211 model opcodes, read as nothing by the 241 client
                case 2, 3, 9, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39 -> in.skipStr();
                case 4, 5, 6, 7, 8, 75, 94, 95, 110, 111, 112 -> in.skip(2);
                case 97, 98, 139, 140, 148, 149 -> u16(Target.OBJ);
                case 11, 15, 16, 65, 160, 251 -> { }
                case 12 -> in.skip(4);
                case 13, 14, 27, 42, 113, 114, 115 -> in.skip(1);
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                case 43 -> { in.u8(); while (in.u8() != 0) in.skipStr(); }
                case 44, 46, 47, 49, 50, 51, 52, 53, 54 -> i32(Target.MODEL);
                case 45, 48 -> { i32(Target.MODEL); in.skip(1); }
                case 100, 101, 102, 103, 104, 105, 106, 107, 108, 109 -> { u16(Target.OBJ); in.skip(2); }
                case 161 -> { int n = in.u16(); in.skip(n * 2); }
                case 200 -> { in.u8(); in.u8(); in.skipStr(); }
                case 201 -> condOp(false);
                case 202 -> condOp(true);
                case 249 -> params();
                default -> unknown(op, start);
            }
        }

        void npc(int op, int start) {
            switch (op) {
                case 1, 60 -> u16list(Target.MODEL, false);
                case 61, 62 -> i32list(Target.MODEL, false);
                case 2, 30, 31, 32, 33, 34 -> in.skipStr();
                case 12, 100, 101 -> in.skip(1);
                case 13, 14, 15, 16, 114, 116 -> u16(Target.SEQ);
                case 17, 115, 117 -> { u16(Target.SEQ); u16(Target.SEQ); u16(Target.SEQ); u16(Target.SEQ); }
                case 18, 42, 74, 75, 76, 77, 78, 79, 95, 97, 98, 103, 124, 126, 146 -> in.skip(2);
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                case 93, 99, 107, 109, 111, 122, 123, 129, 130, 145, 147 -> { }
                case 102 -> {
                    int mask = in.u8();
                    for (int i = 0; mask >> i != 0; i++) if ((mask & 1 << i) != 0) { int g = in.bigSmart(); if (g >= 0) ref(Target.SPRITE, g); in.skipSmart(); }
                }
                case 106, 118 -> transforms(op, 118, Target.NPC);
                case 249 -> params();
                case 251 -> { in.u8(); in.u8(); in.skipStr(); }
                case 252 -> condOp(false);
                case 253 -> condOp(true);
                default -> unknown(op, start);
            }
        }

        void loc(int op, int start) {
            switch (op) {
                case 1 -> u16list(Target.MODEL, true);
                case 5 -> u16list(Target.MODEL, false);
                case 6 -> i32list(Target.MODEL, true);
                case 7 -> i32list(Target.MODEL, false);
                case 2, 30, 31, 32, 33, 34 -> in.skipStr();
                case 14, 15, 19, 28, 29, 39, 69, 75, 81, 91, 95, 96 -> in.skip(1);
                case 17, 18, 21, 22, 23, 27, 62, 64, 73, 74, 89, 90, 94 -> { }
                case 24 -> u16(Target.SEQ);
                case 42, 61, 65, 66, 67, 68, 70, 71, 72, 82 -> in.skip(2);
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                case 77, 92 -> transforms(op, 92, Target.LOC);
                case 78 -> in.skip(4);
                case 79 -> { in.skip(6); int n = in.u8(); in.skip(n * 2); }
                case 93 -> in.skip(6);
                case 100 -> { in.u8(); in.u8(); in.skipStr(); }
                case 101 -> condOp(false);
                case 102 -> condOp(true);
                case 249 -> params();
                default -> unknown(op, start);
            }
        }

        void spot(int op, int start) {
            switch (op) {
                case 1 -> u16(Target.MODEL);
                case 3 -> i32(Target.MODEL);
                case 2 -> u16(Target.SEQ);
                case 4, 5, 6, 42 -> in.skip(2);
                case 7, 8 -> in.skip(1);
                case 9 -> in.skipStr();
                case 10 -> { }
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                default -> unknown(op, start);
            }
        }

        void kit(int op, int start) {
            if (op >= 60 && op < 70) { u16(Target.MODEL); return; }
            if (op >= 70 && op < 80) { i32(Target.MODEL); return; }
            switch (op) {
                case 1 -> in.skip(1);
                case 2 -> u16list(Target.MODEL, false);
                case 3 -> { }
                case 5 -> i32list(Target.MODEL, false);
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                default -> unknown(op, start);
            }
        }

        /** 241 seq: frames are archive-0 files (group << 16 | file; file 0xffff = no frame), op 13 an archive-22 keyframe. */
        void seq(int op, int start) {
            switch (op) {
                case 1 -> {
                    int n = in.u16();
                    in.skip(n * 2);
                    int[] f = new int[n];
                    for (int i = 0; i < n; i++) f[i] = in.u16();
                    for (int i = 0; i < n; i++) { int g = in.u16(); if (f[i] != 0xffff) ref(Target.FRAME, g << 16 | f[i]); }
                }
                case 12 -> {
                    int n = in.u8();
                    int[] f = new int[n];
                    for (int i = 0; i < n; i++) f[i] = in.u16();
                    for (int i = 0; i < n; i++) { int g = in.u16(); if (f[i] != 0xffff) ref(Target.FRAME, g << 16 | f[i]); }
                }
                case 13 -> { int k = in.i32(); if (k != -1) ref(Target.KEYFRAME, k); }
                case 6, 7 -> { int raw = in.u16(); if (raw > 0) ref(Target.OBJ, raw - 512); }
                case 2, 15 -> in.skip(op == 15 ? 4 : 2);
                case 3, 17 -> { int n = in.u8(); in.skip(n); }
                case 4, 19 -> { }
                case 5, 8, 9, 10, 11, 16 -> in.skip(1);
                case 14 -> { int n = in.u16(); in.skip(n * 8); }
                case 18 -> in.skipStr();
                default -> unknown(op, start);
            }
        }

        /** Enum key/value types are read first so the map entries can be typed. */
        void enumTypes() {
            Buf s = new Buf(b);
            while (s.p < b.length) {
                int op = s.u8();
                switch (op) {
                    case 0: return;
                    case 1: keyType = s.u8(); break;
                    case 2: valType = s.u8(); break;
                    case 3: s.skipStr(); break;
                    case 4: s.skip(4); break;
                    case 5: { int n = s.u16(); for (int i = 0; i < n; i++) { s.skip(4); s.skipStr(); } break; }
                    case 6: { int n = s.u16(); s.skip(n * 8); break; }
                    case 7: { int n = s.u16(); s.skip(n * 12); break; }
                    case 8: s.skip(8); break;
                    default: return; // reported by the main pass
                }
            }
        }

        void enm(int op, int start) {
            switch (op) {
                case 1, 2 -> in.skip(1);
                case 3 -> in.skipStr();
                case 4 -> typedInt(valType, in.i32());
                case 5, 6, 7 -> {
                    int n = in.u16();
                    for (int i = 0; i < n; i++) {
                        int key = in.i32();
                        field = "key " + key;
                        typedInt(keyType, key);
                        if (op == 5) in.skipStr();
                        else if (op == 6) typedInt(valType, in.i32());
                        else in.skip(8);
                    }
                }
                case 8 -> in.skip(8);
                default -> unknown(op, start);
            }
        }

        void param(int op, int start) {
            switch (op) {
                case 1, 8 -> in.skip(1);
                case 2 -> typedInt(types.paramChar(id), in.i32());
                case 4 -> { }
                case 5 -> in.skipStr();
                case 7 -> in.skip(8);
                default -> unknown(op, start);
            }
        }

        void inv(int op, int start) {
            switch (op) {
                case 2 -> in.skip(2);
                case 4 -> { int n = in.u8(); for (int i = 0; i < n; i++) { u16(Target.OBJ); in.skip(2); } }
                case 249 -> params();
                default -> unknown(op, start);
            }
        }

        void healthbar(int op, int start) {
            switch (op) {
                case 1, 5, 11 -> in.skip(2);
                case 2, 3, 6, 14, 15 -> in.skip(1);
                case 4 -> { }
                case 7, 8 -> { int g = in.bigSmart(); if (g >= 0) ref(Target.SPRITE, g); }
                default -> unknown(op, start);
            }
        }

        void dbrow(int op, int start) {
            switch (op) {
                case 3 -> dbColumns(false);
                case 4 -> {
                    int v = 0, bits = 0, r;
                    do { r = in.u8(); v |= (r & 0x7f) << bits; bits += 7; } while (r > 127);
                    ref(Target.DBTABLE, v);
                }
                default -> unknown(op, start);
            }
        }

        /** dbrow (op 3) / dbtable (op 1) columns: count, then {setting, n types, values}, 255. */
        void dbColumns(boolean table) {
            in.u8();
            while (true) {
                int setting = in.u8();
                if (setting == 255) break;
                int col = table ? setting & 127 : setting;
                boolean values = !table || (setting & 128) != 0;
                int nt = in.u8();
                int[] t = new int[nt];
                for (int i = 0; i < nt; i++) t[i] = in.smart();
                if (!values) continue;
                int fields = in.smart();
                for (int f = 0; f < fields; f++) {
                    for (int typeId : t) {
                        int base = CacheTypes.typeIdBase(typeId);
                        if (base == CsTable.OBJ) in.skipStr();
                        else if (base == CsTable.LONG) in.skip(8);
                        else { field = "column " + col; typedInt(CacheTypes.charOfTypeId(typeId), in.i32()); }
                    }
                }
            }
        }
    }
}

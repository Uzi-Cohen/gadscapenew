package com.gadscape.cacheconv.model;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Finds and rewrites the model ids inside build-241 entries: obj, npc, loc, spotanim and identkit configs, and
 * interface components (IF3 and IF1 type 6). This is the helper the config and UI steps call to apply model
 * relocations ({@link ModelsStep}) to the entries they write:
 * <pre>
 *   byte[] b241 = ConfigConv.item(id, ctx.user.configRef(Arch.CFG_OBJ, id));
 *   ctx.out.putConfig(Arch.CFG_OBJ, id, ModelRefs.remap(ctx.ids, Arch.CFG_OBJ, id, b241));
 *   ctx.out.putGroup(Arch.INTERFACES, ModelRefs.remapInterface(ctx.ids, InterfaceConv.group(userGroup)));
 * </pre>
 * The walkers read exactly what the build-241 client reads and throw {@link IllegalStateException} on unknown
 * opcodes or truncated data. Everything except the model ids is copied byte for byte, so rewriting with the identity
 * returns the same bytes. Input must already be in the 241 layout (run {@code ConfigConv} / {@code InterfaceConv}
 * on 211 data first).
 * <p>
 * Model id fields of build 241 (checked with the real 241 client, see {@code package-info}): every field the converter
 * writes is a 4-byte int. The 2-byte opcodes of the 211 layout are still parsed here ({@link Ref#legacy}), but the
 * 241 client drops their value, so they never matter for what the client shows.
 */
public final class ModelRefs {
    private ModelRefs() {}

    /** Type char of model-typed values in enums, params and dbrow columns (ScriptVarType MODEL). */
    public static final char MODEL_TYPE_CHAR = 'm';

    /**
     * One model id field.
     * @param offset  byte offset of the field
     * @param width   2 (unsigned short, 65535 = none) or 4 (int, -1 = none)
     * @param opcode  config opcode, or the component type for interface components
     * @param model   the id, -1 for none
     * @param legacy  a 211-layout field that the 241 client reads and drops
     */
    public record Ref(int offset, int width, int opcode, int model, boolean legacy) {}

    /** Config groups (archive 2) whose entries hold model ids: identkit 3, loc 6, npc 9, obj 10, spotanim 13. */
    public static boolean hasModels(int cfgGroup) {
        return cfgGroup == Arch.CFG_IDENTKIT || cfgGroup == Arch.CFG_LOC || cfgGroup == Arch.CFG_NPC
                || cfgGroup == Arch.CFG_OBJ || cfgGroup == Arch.CFG_SPOT;
    }

    // ------------------------------------------------------------------ public API

    /** Every model id field of a 241 config entry, in byte order (empty for groups without models). */
    public static List<Ref> refs(int cfgGroup, int id, byte[] b) {
        List<Ref> r = new ArrayList<>();
        try {
            switch (cfgGroup) {
                case Arch.CFG_OBJ -> obj(id, b, r);
                case Arch.CFG_NPC -> npc(id, b, r);
                case Arch.CFG_LOC -> loc(id, b, r);
                case Arch.CFG_SPOT -> spot(id, b, r);
                case Arch.CFG_IDENTKIT -> kit(id, b, r);
                default -> { }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalStateException(Arch.configName(cfgGroup) + " " + id + ": truncated entry (" + b.length + " bytes)");
        }
        return r;
    }

    /** Model ids (>= 0) a 241 config entry references, in byte order; legacy fields included only if asked. */
    public static int[] models(int cfgGroup, int id, byte[] b, boolean withLegacy) {
        return values(refs(cfgGroup, id, b), withLegacy);
    }

    /** Every model id field of a 241 interface component (IF3 type 6: one int at offset 19; IF1 type 6: two ints). */
    public static List<Ref> componentRefs(byte[] b) {
        List<Ref> r = new ArrayList<>();
        if (b == null || b.length == 0) return r;
        try {
            if ((b[0] & 255) == 255) {
                if (b.length > 1 && b[1] == 6) r.add(new Ref(19, 4, 6, Bytes.i32(b, 19), false));
                return r;
            }
            Buf in = new Buf(b);
            int type = in.u8(); in.u8(); in.skip(15);
            int n = in.u8(); in.skip(n * 3);
            n = in.u8();
            for (int i = 0; i < n; i++) { int c = in.u16(); in.skip(c * 2); }
            if (type == 6) {
                r.add(new Ref(in.p, 4, 6, Bytes.i32(b, in.p), false));
                r.add(new Ref(in.p + 4, 4, 6, Bytes.i32(b, in.p + 4), false));
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalStateException("interface component: truncated (" + b.length + " bytes)");
        }
        return r;
    }

    public static int[] componentModels(byte[] b) { return values(componentRefs(b), true); }

    /**
     * The entry with every model id {@code m >= 0} replaced by {@code f(m)}. Returns {@code b} itself when nothing
     * changes, else a new array of the same length. Throws if a new id does not fit its field (a legacy 2-byte field
     * holds 0..65534).
     */
    public static byte[] rewrite(int cfgGroup, int id, byte[] b, IntUnaryOperator f) {
        return apply(b, refs(cfgGroup, id, b), f, Arch.configName(cfgGroup) + " " + id);
    }

    public static byte[] rewriteComponent(byte[] b, IntUnaryOperator f) {
        return apply(b, componentRefs(b), f, "interface component");
    }

    /** Every component of an interface group rewritten (file ids, file names, group name and version kept). */
    public static GroupData rewriteInterface(GroupData g, IntUnaryOperator f) {
        boolean changed = false;
        GroupData.Builder nb = new GroupData.Builder(g.id).name(g.name).version(g.version);
        for (int i = 0; i < g.fileCount(); i++) {
            byte[] d = g.dataAt(i);
            byte[] n = rewriteComponent(d, f);
            changed |= n != d;
            nb.put(g.fileIdAt(i), g.fileNameAt(i), n);
        }
        return changed ? nb.build() : g;
    }

    /** {@code m -> ids.map(MODEL, m)}; negative ids (none) stay. */
    public static IntUnaryOperator mapper(IdMap ids) { return m -> m < 0 ? m : ids.map(Kind.MODEL, m); }

    /** A 241 config entry with the model relocations of {@code ids} applied ({@code b} itself if none apply). */
    public static byte[] remap(IdMap ids, int cfgGroup, int id, byte[] b) {
        return hasModels(cfgGroup) ? rewrite(cfgGroup, id, b, mapper(ids)) : b;
    }

    /** A 241 interface component with the model relocations applied. */
    public static byte[] remapComponent(IdMap ids, byte[] b) { return rewriteComponent(b, mapper(ids)); }

    /** A 241 interface group with the model relocations applied (the same instance if none apply). */
    public static GroupData remapInterface(IdMap ids, GroupData g) { return rewriteInterface(g, mapper(ids)); }

    /** A model-typed value (enum value, param value, dbrow column) with the relocations applied. */
    public static int remapModel(IdMap ids, int model) { return model < 0 ? model : ids.map(Kind.MODEL, model); }

    // ------------------------------------------------------------------ internals

    private static int[] values(List<Ref> refs, boolean withLegacy) {
        int n = 0;
        for (Ref r : refs) if (r.model >= 0 && (withLegacy || !r.legacy)) n++;
        int[] out = new int[n];
        int i = 0;
        for (Ref r : refs) if (r.model >= 0 && (withLegacy || !r.legacy)) out[i++] = r.model;
        return out;
    }

    private static byte[] apply(byte[] b, List<Ref> refs, IntUnaryOperator f, String what) {
        byte[] out = b;
        for (Ref r : refs) {
            if (r.model < 0) continue;
            int to = f.applyAsInt(r.model);
            if (to == r.model) continue;
            if (out == b) out = b.clone();
            if (r.width == 4) Bytes.w32(out, r.offset, to);
            else {
                if (to < 0 || to >= 65535)
                    throw new IllegalStateException(what + ": model " + r.model + " -> " + to + " does not fit the 2-byte field of opcode " + r.opcode);
                Bytes.w16(out, r.offset, to);
            }
        }
        return out;
    }

    private static void u16list(Buf in, int op, List<Ref> r, boolean withType) {
        int n = in.u8();
        for (int i = 0; i < n; i++) {
            int p = in.p, v = in.u16();
            r.add(new Ref(p, 2, op, v == 65535 ? -1 : v, true));
            if (withType) in.skip(1);
        }
    }

    private static void i32list(Buf in, int op, List<Ref> r, boolean withType) {
        int n = in.u8();
        for (int i = 0; i < n; i++) {
            int p = in.p;
            r.add(new Ref(p, 4, op, in.i32(), false));
            if (withType) in.skip(1);
        }
    }

    private static void i32(Buf in, int op, List<Ref> r) { int p = in.p; r.add(new Ref(p, 4, op, in.i32(), false)); }

    private static void u16(Buf in, int op, List<Ref> r) { int p = in.p, v = in.u16(); r.add(new Ref(p, 2, op, v == 65535 ? -1 : v, true)); }

    private static IllegalStateException unknown(String kind, int id, int op, int at) {
        return new IllegalStateException(kind + " " + id + ": unknown 241 opcode " + op + " at " + at);
    }

    private static void end(String kind, int id, Buf in) {
        if (in.p != in.b.length) throw new IllegalStateException(kind + " " + id + ": " + (in.b.length - in.p) + " bytes after the end opcode");
    }

    /** Obj (items), build 241: models are ints in 44-54; the 211 short-model opcodes carry no data in 241. */
    static void obj(int id, byte[] b, List<Ref> r) {
        Buf in = new Buf(b);
        while (true) {
            int start = in.p, op = in.op();
            switch (op) {
                case 0 -> { end("obj", id, in); return; }
                case 1, 23, 24, 25, 26, 78, 79, 90, 91, 92, 93 -> { } // 211 model opcodes: no payload in 241
                case 2, 3, 9, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39 -> in.skipStr();
                case 4, 5, 6, 7, 8, 75, 94, 95, 97, 98, 110, 111, 112, 139, 140, 148, 149 -> in.skip(2);
                case 11, 15, 16, 65, 160, 251 -> { }
                case 12 -> in.skip(4);
                case 13, 14, 27, 42, 113, 114, 115 -> in.skip(1);
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                case 43 -> { in.u8(); while (in.u8() != 0) in.skipStr(); }
                case 44, 46, 47, 49, 50, 51, 52, 53, 54 -> i32(in, op, r);
                case 45, 48 -> { i32(in, op, r); in.skip(1); }
                case 100, 101, 102, 103, 104, 105, 106, 107, 108, 109 -> in.skip(4);
                case 161 -> { int n = in.u16(); in.skip(n * 2); }
                case 200 -> { in.skip(2); in.skipStr(); }
                case 201 -> { in.skip(13); in.skipStr(); }
                case 202 -> { in.skip(15); in.skipStr(); }
                case 249 -> in.skipParams();
                default -> throw unknown("obj", id, op, start);
            }
        }
    }

    /** NPCs, build 241: models in 61, chathead models in 62 (ints); 211's 1/60 (shorts) are read and dropped. */
    static void npc(int id, byte[] b, List<Ref> r) {
        Buf in = new Buf(b);
        while (true) {
            int start = in.p, op = in.op();
            switch (op) {
                case 0 -> { end("npc", id, in); return; }
                case 1, 60 -> u16list(in, op, r, false);
                case 61, 62 -> i32list(in, op, r, false);
                case 2, 30, 31, 32, 33, 34 -> in.skipStr();
                case 12, 100, 101 -> in.skip(1);
                case 13, 14, 15, 16, 18, 42, 74, 75, 76, 77, 78, 79, 95, 97, 98, 103, 114, 116, 124, 126, 146 -> in.skip(2);
                case 17, 115, 117 -> in.skip(8);
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                case 93, 99, 107, 109, 111, 122, 123, 129, 130, 145, 147 -> { }
                case 102 -> { int mask = in.u8(); for (int i = 0; mask >> i != 0; i++) if ((mask & 1 << i) != 0) { in.skipBigSmart(); in.skipSmart(); } }
                case 106, 118 -> { in.skip(4); if (op == 118) in.skip(2); int n = in.u8(); in.skip((n + 1) * 2); }
                case 249 -> in.skipParams();
                case 251 -> { in.skip(2); in.skipStr(); }
                case 252 -> { in.skip(13); in.skipStr(); }
                case 253 -> { in.skip(15); in.skipStr(); }
                default -> throw unknown("npc", id, op, start);
            }
        }
    }

    /** Locs (objects), build 241: models in 6 (with shape) and 7 (ints); 211's 1/5 (shorts) are dropped by the 241 client. */
    static void loc(int id, byte[] b, List<Ref> r) {
        Buf in = new Buf(b);
        while (true) {
            int start = in.p, op = in.op();
            switch (op) {
                case 0 -> { end("loc", id, in); return; }
                case 1 -> u16list(in, op, r, true);
                case 5 -> u16list(in, op, r, false);
                case 6 -> i32list(in, op, r, true);
                case 7 -> i32list(in, op, r, false);
                case 2, 30, 31, 32, 33, 34 -> in.skipStr();
                case 14, 15, 19, 28, 29, 39, 69, 75, 81, 91, 95, 96 -> in.skip(1);
                case 17, 18, 21, 22, 23, 27, 62, 64, 73, 74, 89, 90, 94 -> { }
                case 24, 42, 61, 65, 66, 67, 68, 70, 71, 72, 82 -> in.skip(2);
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                case 77, 92 -> { in.skip(4); if (op == 92) in.skip(2); int n = in.u8(); in.skip((n + 1) * 2); }
                case 78 -> in.skip(4);
                case 79 -> { in.skip(6); int n = in.u8(); in.skip(n * 2); }
                case 93 -> in.skip(6);
                case 100 -> { in.skip(2); in.skipStr(); }
                case 101 -> { in.skip(13); in.skipStr(); }
                case 102 -> { in.skip(15); in.skipStr(); }
                case 249 -> in.skipParams();
                default -> throw unknown("loc", id, op, start);
            }
        }
    }

    /** Spot animations, build 241: model in 3 (int); 211's 1 (short) is dropped by the 241 client. */
    static void spot(int id, byte[] b, List<Ref> r) {
        Buf in = new Buf(b);
        while (true) {
            int start = in.p, op = in.op();
            switch (op) {
                case 0 -> { end("spotanim", id, in); return; }
                case 1 -> u16(in, op, r);
                case 3 -> i32(in, op, r);
                case 2, 4, 5, 6, 42 -> in.skip(2);
                case 7, 8 -> in.skip(1);
                case 9 -> in.skipStr();
                case 10 -> { }
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                default -> throw unknown("spotanim", id, op, start);
            }
        }
    }

    /**
     * Identity kits, build 241: models in 5 (ints), chathead models in 70-79 (ints). The 241 client keeps 5
     * chathead slots, so only 70-74 (and 211's 60-64) decode; 211's 2 (short list) breaks the 241 decoder.
     */
    static void kit(int id, byte[] b, List<Ref> r) {
        Buf in = new Buf(b);
        while (true) {
            int start = in.p, op = in.op();
            if (op == 0) { end("identkit", id, in); return; }
            if (op >= 60 && op < 70) { u16(in, op, r); continue; }
            if (op >= 70 && op < 80) { i32(in, op, r); continue; }
            switch (op) {
                case 1 -> in.skip(1);
                case 2 -> u16list(in, op, r, false);
                case 3 -> { }
                case 5 -> i32list(in, op, r, false);
                case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                default -> throw unknown("identkit", id, op, start);
            }
        }
    }
}

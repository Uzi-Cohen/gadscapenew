package com.gadscape.cacheconv.convert;

import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.BufOut;

import java.util.Arrays;
import java.util.TreeMap;
import java.util.function.IntConsumer;

/**
 * Rewrites GadScape (rev 211) config entries into the build-241 layout. Each walker reads exactly what the 211 client
 * reads and fails (IllegalStateException naming the id, opcode and offset) on anything else.
 * Ported unchanged from rev241/tools/Conv.java (which built cacheU); {@link SelfCheck} compares the output with cacheU.
 */
public final class ConfigConv {
    private ConfigConv() {}

    /** Optional hook told every opcode the walkers read (opcode census). */
    public static IntConsumer census = null;

    private static Buf in(byte[] b) { Buf in = new Buf(b); in.census = census; return in; }

    /** Objects (locs): 211 objects up to id 47337 store 78/79 without the retain byte that 241 always reads; 1/5 model lists move to the int-model opcodes 6/7. */
    public static byte[] loc(int id, byte[] b) {
        boolean old = id <= 47337;
        Buf in = in(b); BufOut out = new BufOut(b.length + 16);
        while (true) {
            int start = in.p, op = in.op();
            if (op == 0) { out.u8(0); return out.done(); }
            switch (op) {
                // 241 reads 1/5 model ids as big smarts (4 bytes from 32768 up); the 4-byte fields 6/7 hold any id
                case 1: { int n = in.u8(); out.u8(6); out.u8(n); for (int i = 0; i < n; i++) { out.i32(in.u16()); out.u8(in.u8()); } continue; }
                case 2: in.skipStr(); break;
                case 5: { int n = in.u8(); out.u8(7); out.u8(n); for (int i = 0; i < n; i++) out.i32(in.u16()); continue; }
                case 6: { int n = in.u8(); in.skip(n * 5); break; }
                case 7: { int n = in.u8(); in.skip(n * 4); break; }
                case 14: case 15: case 19: case 28: case 29: case 39: case 69: case 75: case 81: case 91: case 95: case 96: in.skip(1); break;
                case 17: case 18: case 21: case 22: case 23: case 27: case 62: case 64: case 73: case 74: case 89: case 90: case 94: break;
                case 24: case 42: case 61: case 65: case 66: case 67: case 68: case 70: case 71: case 72: case 82: in.skip(2); break;
                case 30: case 31: case 32: case 33: case 34: in.skipStr(); break;
                case 40: case 41: { int n = in.u8(); in.skip(n * 4); break; }
                case 77: case 92: { in.skip(4); if (op == 92) in.skip(2); int n = in.u8(); in.skip((n + 1) * 2); break; }
                case 78: { in.skip(3); out.raw(b, start, in.p); if (old) out.u8(0); else out.u8(in.u8()); continue; }
                case 79: { in.skip(5); out.raw(b, start, in.p); if (old) out.u8(0); else out.u8(in.u8());
                           int s = in.p, n = in.u8(); in.skip(n * 2); out.raw(b, s, in.p); continue; }
                case 93: in.skip(6); break;
                case 100: in.skip(2); in.skipStr(); break;
                case 101: in.skip(13); in.skipStr(); break;
                case 102: in.skip(15); in.skipStr(); break;
                case 249: in.skipParams(); break;
                default: throw new IllegalStateException("object " + id + ": opcode " + op + " at " + start);
            }
            out.raw(b, start, in.p);
        }
    }

    /** NPCs: 211 opcode 111 (follower) is 122 in 241, where 111 means render priority; 1/60 model lists move to the int-model opcodes 61/62. */
    public static byte[] npc(int id, byte[] b) {
        Buf in = in(b); BufOut out = new BufOut(b.length + 16);
        while (true) {
            int start = in.p, op = in.op();
            if (op == 0) { out.u8(0); return out.done(); }
            switch (op) {
                case 1: case 60: { int n = in.u8(); out.u8(op == 1 ? 61 : 62); out.u8(n); for (int i = 0; i < n; i++) out.i32(in.u16()); continue; }
                case 61: case 62: { int n = in.u8(); in.skip(n * 4); break; }
                case 2: case 30: case 31: case 32: case 33: case 34: in.skipStr(); break;
                case 12: case 100: case 101: in.skip(1); break;
                case 13: case 14: case 15: case 16: case 18: case 42: case 74: case 75: case 76: case 77: case 78: case 79:
                case 95: case 97: case 98: case 103: case 114: case 116: case 124: case 126: case 146: in.skip(2); break;
                case 17: case 115: case 117: in.skip(8); break;
                case 40: case 41: { int n = in.u8(); in.skip(n * 4); break; }
                case 93: case 99: case 107: case 109: case 123: case 129: case 130: case 145: case 147: break;
                case 102: { int mask = in.u8(); for (int i = 0; mask >> i != 0; i++) if ((mask & 1 << i) != 0) { in.skipBigSmart(); in.skipSmart(); } break; }
                case 106: case 118: { in.skip(4); if (op == 118) in.skip(2); int n = in.u8(); in.skip((n + 1) * 2); break; }
                case 111: out.u8(122); continue;
                case 122: break;
                case 251: in.skip(2); in.skipStr(); break;
                case 252: in.skip(13); in.skipStr(); break;
                case 253: in.skip(15); in.skipStr(); break;
                case 249: in.skipParams(); break;
                default: throw new IllegalStateException("npc " + id + ": opcode " + op + " at " + start);
            }
            out.raw(b, start, in.p);
        }
    }

    /** Weight written for every converted seq sound: what 4,183 of 4,500 build-241 sound entries use. */
    public static int seqWeight = 100;

    /**
     * Sequences: 211 sounds are 3-byte packed per frame (opcode 13) and skeletal fields sit on 14-17; 241 renumbers them
     * (13 skeletal id, 14 sounds by frame with weight, 15 range, 17 masks). The skeletal/sound/range/mask fields are
     * re-emitted after the others, in that order.
     */
    public static byte[] seq(int id, byte[] b) {
        Buf in = in(b); BufOut out = new BufOut(b.length + 16);
        TreeMap<Integer, int[]> sounds = new TreeMap<>();
        int maya = -1; int[] range = null; byte[] masks = null;
        while (true) {
            int start = in.p, op = in.op();
            if (op == 0) break;
            switch (op) {
                case 1: { int n = in.u16(); in.skip(n * 6); out.raw(b, start, in.p); break; }
                case 2: case 6: case 7: in.skip(2); out.raw(b, start, in.p); break;
                case 3: { int n = in.u8(); in.skip(n); out.raw(b, start, in.p); break; }
                case 4: out.raw(b, start, in.p); break;
                case 5: case 8: case 9: case 10: case 11: in.skip(1); out.raw(b, start, in.p); break;
                case 12: { int n = in.u8(); in.skip(n * 4); out.raw(b, start, in.p); break; }
                case 13: { int n = in.u8(); for (int i = 0; i < n; i++) { int v = in.u24(); if (v >> 8 >= 1) sounds.put(i, new int[]{v >> 8, v >> 4 & 7, v & 15}); } break; }
                case 14: maya = in.i32(); break;
                case 15: { int n = in.u16(); for (int i = 0; i < n; i++) { int f = in.u16(), v = in.u24(); if (v >> 8 >= 1) sounds.put(f, new int[]{v >> 8, v >> 4 & 7, v & 15}); } break; }
                case 16: range = new int[]{in.u16(), in.u16()}; break;
                case 17: { int s = in.p, n = in.u8(); in.skip(n); masks = Arrays.copyOfRange(b, s, in.p); break; }
                default: throw new IllegalStateException("sequence " + id + ": opcode " + op + " at " + start);
            }
        }
        if (maya != -1) { out.u8(13); out.i32(maya); }
        if (!sounds.isEmpty()) {
            out.u8(14); out.u16(sounds.size());
            for (var e : sounds.entrySet()) { int[] s = e.getValue(); out.u16(e.getKey()); out.u16(s[0]); out.u8(seqWeight); out.u8(s[1]); out.u8(s[2]); out.u8(0); }
        }
        if (range != null) { out.u8(15); out.u16(range[0]); out.u16(range[1]); }
        if (masks != null) { out.u8(17); out.raw(masks, 0, masks.length); }
        out.u8(0);
        return out.done();
    }

    /** Items: 241 stores every model id as an int (44-54); the 2-byte 211 fields move to their int twins. */
    public static byte[] item(int id, byte[] b) {
        Buf in = in(b); BufOut out = new BufOut(b.length + 32);
        while (true) {
            int start = in.p, op = in.op();
            if (op == 0) { out.u8(0); return out.done(); }
            switch (op) {
                case 1: out.u8(44); out.i32(in.u16()); continue;
                case 23: out.u8(45); out.i32(in.u16()); out.u8(in.u8()); continue;
                case 24: out.u8(46); out.i32(in.u16()); continue;
                case 25: out.u8(48); out.i32(in.u16()); out.u8(in.u8()); continue;
                case 26: out.u8(49); out.i32(in.u16()); continue;
                case 78: out.u8(47); out.i32(in.u16()); continue;
                case 79: out.u8(50); out.i32(in.u16()); continue;
                case 90: out.u8(51); out.i32(in.u16()); continue;
                case 91: out.u8(53); out.i32(in.u16()); continue;
                case 92: out.u8(52); out.i32(in.u16()); continue;
                case 93: out.u8(54); out.i32(in.u16()); continue;
                case 2: case 3: case 9: case 30: case 31: case 32: case 33: case 34: case 35: case 36: case 37: case 38: case 39: in.skipStr(); break;
                case 4: case 5: case 6: case 7: case 8: case 75: case 94: case 95: case 97: case 98: case 110: case 111: case 112:
                case 139: case 140: case 148: case 149: in.skip(2); break;
                case 11: case 15: case 16: case 65: case 160: case 251: break;
                case 12: case 44: case 46: case 47: case 49: case 50: case 51: case 52: case 53: case 54: in.skip(4); break;
                case 45: case 48: in.skip(5); break;
                case 13: case 14: case 27: case 42: case 113: case 114: case 115: in.skip(1); break;
                case 40: case 41: { int n = in.u8(); in.skip(n * 4); break; }
                case 43: { in.u8(); while (in.u8() != 0) in.skipStr(); break; }
                case 100: case 101: case 102: case 103: case 104: case 105: case 106: case 107: case 108: case 109: in.skip(4); break;
                case 161: { int n = in.u16(); in.skip(n * 2); break; }
                case 200: in.skip(2); in.skipStr(); break;
                case 201: in.skip(13); in.skipStr(); break;
                case 202: in.skip(15); in.skipStr(); break;
                case 249: in.skipParams(); break;
                default: throw new IllegalStateException("item " + id + ": opcode " + op + " at " + start);
            }
            out.raw(b, start, in.p);
        }
    }

    /** Identity kits: 2 -> 5 (int models), 60-69 -> 70-79 (int chathead models). The 241 client reads 2 as ints and runs off the end. */
    public static byte[] kit(int id, byte[] b) {
        Buf in = in(b); BufOut out = new BufOut(b.length + 16);
        while (true) {
            int start = in.p, op = in.op();
            if (op == 0) { out.u8(0); return out.done(); }
            if (op == 2) { int n = in.u8(); out.u8(5); out.u8(n); for (int i = 0; i < n; i++) out.i32(in.u16()); continue; }
            if (op >= 60 && op < 70) { out.u8(op + 10); out.i32(in.u16()); continue; }
            switch (op) {
                case 1: in.skip(1); break;
                case 3: break;
                case 5: { int n = in.u8(); in.skip(n * 4); break; }
                case 40: case 41: { int n = in.u8(); in.skip(n * 4); break; }
                default: if (op >= 70 && op < 80) { in.skip(4); break; } throw new IllegalStateException("identkit " + id + ": opcode " + op + " at " + start);
            }
            out.raw(b, start, in.p);
        }
    }

    /** Spot animations: 1 -> 3 (int model). */
    public static byte[] spot(int id, byte[] b) {
        Buf in = in(b); BufOut out = new BufOut(b.length + 8);
        while (true) {
            int start = in.p, op = in.op();
            if (op == 0) { out.u8(0); return out.done(); }
            switch (op) {
                case 1: out.u8(3); out.i32(in.u16()); continue;
                case 2: case 4: case 5: case 6: case 42: in.skip(2); break;
                case 3: in.skip(4); break;
                case 7: case 8: in.skip(1); break;
                case 9: in.skipStr(); break;
                case 10: break;
                case 40: case 41: { int n = in.u8(); in.skip(n * 4); break; }
                default: throw new IllegalStateException("spotanim " + id + ": opcode " + op + " at " + start);
            }
            out.raw(b, start, in.p);
        }
    }

    /** The converter for a config group (loc, npc, seq, obj, identkit, spotanim), or null if the group needs none. */
    public static Fn forGroup(int cfgGroup) {
        return switch (cfgGroup) {
            case 6 -> ConfigConv::loc;
            case 9 -> ConfigConv::npc;
            case 10 -> ConfigConv::item;
            case 12 -> ConfigConv::seq;
            case 3 -> ConfigConv::kit;
            case 13 -> ConfigConv::spot;
            default -> null;
        };
    }

    /** A 211 -> 241 config converter. */
    @FunctionalInterface
    public interface Fn { byte[] apply(int id, byte[] b); }
}

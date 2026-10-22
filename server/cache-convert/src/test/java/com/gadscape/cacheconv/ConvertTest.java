package com.gadscape.cacheconv;

import com.gadscape.cacheconv.convert.ConfigConv;
import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.convert.InterfaceConv;
import com.gadscape.cacheconv.convert.ScriptConv;
import com.gadscape.cacheconv.convert.ScriptTrailer;
import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.Options;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConvertTest {
    @Test void itemModelsBecomeInts() {
        byte[] in = new BufOut().u8(1).u16(1234).u8(2).str("Test").u8(23).u16(40000).u8(5).u8(0).done();
        byte[] out = ConfigConv.item(1, in);
        byte[] want = new BufOut().u8(44).i32(1234).u8(2).str("Test").u8(45).i32(40000).u8(5).u8(0).done();
        assertArrayEquals(want, out);
        assertThrows(IllegalStateException.class, () -> ConfigConv.item(1, new byte[]{(byte) 250, 0}));
    }

    @Test void npcFollowerOpcode() {
        byte[] out = ConfigConv.npc(1, new BufOut().u8(1).u8(1).u16(7).u8(111).u8(0).done());
        assertArrayEquals(new BufOut().u8(61).u8(1).i32(7).u8(122).u8(0).done(), out);
    }

    @Test void locRetainByte() {
        byte[] old = ConfigConv.loc(100, new BufOut().u8(78).u16(5).u8(3).u8(0).done());
        assertArrayEquals(new BufOut().u8(78).u16(5).u8(3).u8(0).u8(0).done(), old);
        byte[] recent = ConfigConv.loc(50000, new BufOut().u8(78).u16(5).u8(3).u8(9).u8(0).done());
        assertArrayEquals(new BufOut().u8(78).u16(5).u8(3).u8(9).u8(0).done(), recent);
    }

    @Test void seqSounds() {
        // 211 opcode 13: 1 frame sound, id 300, loops 2, range 5 (packed id<<8 | loops<<4 | range)
        byte[] out = ConfigConv.seq(1, new BufOut().u8(13).u8(1).u24(300 << 8 | 2 << 4 | 5).u8(0).done());
        assertArrayEquals(new BufOut().u8(14).u16(1).u16(0).u16(300).u8(100).u8(2).u8(5).u8(0).u8(0).done(), out);
    }

    @Test void modelComponentWidened() {
        byte[] c = new byte[30];
        c[0] = (byte) 255; c[1] = 6; c[19] = 0x12; c[20] = 0x34;
        byte[] w = InterfaceConv.component(c);
        assertEquals(32, w.length);
        assertEquals(0x1234, Bytes.i32(w, 19));
    }

    /** iconst 1; branch_not -> label; iconst 2; label: return — then splice two instructions in front of the target. */
    @Test void spliceKeepsJumps() {
        byte[] raw = script211(new int[][]{{0, 1}, {7, 1}, {0, 2}, {21, 0}});
        assertArrayEquals(ScriptTrailer.to241(raw), Cs2.write241(Cs2.parse211(raw)));
        Cs2.Script s = Cs2.parse211(raw);
        Cs2.splice(s, 2, Cs2.L(new Cs2.Ins(0, 5, null), new Cs2.Ins(38, 0, null), new Cs2.Ins(0, 2, null)));
        assertEquals(6, s.code.size());
        assertEquals(3, s.code.get(1).i); // still lands on the return
        byte[] b = Cs2.write241(s);
        ScriptTrailer.verify241(1, b);
    }

    @Test void ccCreateAndDescriptor() {
        byte[] raw = script211WithString();
        ScriptConv.Result r = ScriptConv.convertAll(Map.of(9, raw));
        Cs2.Script s = Cs2.parse241(r.scripts.get(9));
        assertEquals("ii", s.code.get(1).s);
        assertEquals(1, r.descChanged);
    }

    @Test void adapterReadsBack() {
        ScriptTrailer.verify241(46000, ScriptTrailer.adapter(98, 11, new int[]{1}, 1));
    }

    /** Golden check against cacheU, only when the cache layout is available (-Dgadconv.root or GADCONV_ROOT). */
    @Test void goldenAgainstCacheU() throws Exception {
        Options o = Options.defaults();
        Assumptions.assumeTrue(o.root != null && Files.isDirectory(o.user) && Files.isDirectory(o.ref), "cache layout not available");
        assertEquals(0, com.gadscape.cacheconv.convert.SelfCheck.run(new String[0]));
    }

    static byte[] script211(int[][] code) {
        BufOut o = new BufOut().u8(0);
        for (int[] in : code) { o.u16(in[0]); if (in[0] < 100 && in[0] != 21 && in[0] != 38 && in[0] != 39) o.i32(in[1]); else o.u8(in[1]); }
        o.i32(code.length).u16(0).u16(0).u16(0).u16(0); // ops, localInt, localObj, intArgs, objArgs
        o.u8(0).u16(1);                                  // no switch tables
        return o.done();
    }

    /** sconst "ab"; iconst 1; if_setonclick (2400 range); return */
    static byte[] script211WithString() {
        BufOut o = new BufOut().u8(0);
        o.u16(0).i32(7);
        o.u16(3).str("ab");
        o.u16(0).i32(1);
        o.u16(2400).u8(0);
        o.u16(21).u8(0);
        o.i32(5).u16(0).u16(0).u16(0).u16(0);
        o.u8(0).u16(1);
        return o.done();
    }
}

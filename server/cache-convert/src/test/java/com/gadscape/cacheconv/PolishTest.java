package com.gadscape.cacheconv;

import com.gadscape.cacheconv.config.CfgCodec;
import com.gadscape.cacheconv.config.ConfigEdit;
import com.gadscape.cacheconv.config.EnumMerge;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.rebrand.If1Text;
import com.gadscape.cacheconv.rebrand.RebrandTable;
import com.gadscape.cacheconv.verify.StringScan;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The polish stage: config edits, IF1 texts, the string brand scan, new ids at fixed places and the data files. */
class PolishTest {

    static CfgCodec.Entry obj(byte[] b) { return CfgCodec.parse(Arch.CFG_OBJ, 1, b, CfgCodec.UNTYPED); }

    @Test void configEdits() {
        // name, wearpos1 3, inventory op 2 'Wield', recolour 1 pair, end
        byte[] b = new BufOut().u8(2).str("Malovelent kiteshield").u8(13).u8(3).u8(36).str("Wield").u8(40).u8(1).u16(926).u16(5).u8(0).done();
        CfgCodec.Entry e = obj(b);
        e = ConfigEdit.set(e, ConfigEdit.payload(2, "str:Malevolent kiteshield"));
        e = ConfigEdit.set(e, ConfigEdit.payload(13, "u8:5"));
        e = ConfigEdit.set(e, ConfigEdit.payload(97, "u16:32079"));      // new opcode goes before the end
        e = ConfigEdit.unset(e, 36);
        e = ConfigEdit.recolour(e, 0, 7870);
        e = ConfigEdit.recolour(e, 926, 6);                                // replaces the existing pair
        byte[] want = new BufOut().u8(2).str("Malevolent kiteshield").u8(13).u8(5).u8(40).u8(2).u16(926).u16(6).u16(0).u16(7870).u8(97).u16(32079).u8(0).done();
        assertArrayEquals(want, e.encode());
        assertArrayEquals(want, obj(want).encode());
        // copy: present opcodes taken, missing ones removed
        CfgCodec.Entry src = obj(new BufOut().u8(13).u8(4).u8(14).u8(6).u8(0).done());
        CfgCodec.Entry c = ConfigEdit.copy(obj(new BufOut().u8(13).u8(10).u8(27).u8(11).u8(0).done()), src, 13, 14, 27);
        assertArrayEquals(new BufOut().u8(13).u8(4).u8(14).u8(6).u8(0).done(), c.encode());
        assertArrayEquals(new byte[]{13, 1, 0x28, 40}, ConfigEdit.payload(13, "u16:296 u8:40"));
        assertThrows(IllegalArgumentException.class, () -> ConfigEdit.payload(2, "x:1"));
    }

    @Test void params() {
        // params: 249, count 2, (type 0 int, key u24, value i32) x2
        byte[] b = new BufOut().u8(2).str("Varrock Teleport").u8(249).u8(2).u8(0).u24(365).i32(563).u8(0).u24(601).i32(7).u8(0).done();
        CfgCodec.Entry e = ConfigEdit.unsetParams(obj(b), 365, 366);
        assertArrayEquals(new BufOut().u8(2).str("Varrock Teleport").u8(249).u8(1).u8(0).u24(601).i32(7).u8(0).done(), e.encode());
        assertArrayEquals(new BufOut().u8(2).str("Varrock Teleport").u8(0).done(), ConfigEdit.unsetParams(e, 601).encode());
    }

    @Test void enumPairs() {
        // enum: key type 'o', value type 'i', default int, int map 2 pairs
        byte[] b = new BufOut().u8(1).u8('o').u8(2).u8('i').u8(4).i32(-1).u8(6).u16(2).i32(4151).i32(500).i32(28338).i32(1).u8(0).done();
        CfgCodec.Entry e = CfgCodec.parse(Arch.CFG_ENUM, 906, b, CfgCodec.UNTYPED);
        byte[] v = ConfigEdit.enumGet(e, 4151);
        CfgCodec.Entry n = ConfigEdit.enumPut(e, 33338, v);
        assertEquals(500, Bytes.i32(EnumMerge.pairsOf(n).get(33338), 0));
        assertEquals(3, EnumMerge.pairsOf(n).size());
        assertEquals(3, EnumMerge.pairsOf(CfgCodec.parse(Arch.CFG_ENUM, 906, n.encode(), CfgCodec.UNTYPED)).size());
    }

    @Test void if1Text() {
        // IF1 type 4 (text), menu type 0: header 17 bytes, no comparators, no cs1, then xAlign yAlign lineHeight font u16 shadow,
        // text, active text, text colour, active colour, hover colour, active hover colour
        BufOut o = new BufOut().u8(4).u8(0).u16(0).u16(5).u16(6).u16(100).u16(20).u8(0).u16(65535).u16(65535);
        o.u8(0).u8(0).u8(1).u8(1).u8(0).u16(495).u8(1).str("RuneSpawn Credit Packages").str("").i32(0xff981f).i32(0).i32(0).i32(0);
        byte[] b = o.done();
        assertEquals("RuneSpawn Credit Packages", new String(b, 25, 25, StandardCharsets.ISO_8859_1));  // the text starts at offset 25
        RebrandTable t = RebrandTable.parse(List.of("RuneSpawn Credit Packages\tGadScape Credit Packages"), "test");
        List<String[]> ch = new ArrayList<>();
        byte[] nb = If1Text.rewrite(b, t::apply, ch);
        assertEquals(1, ch.size());
        assertEquals("GadScape Credit Packages", new String(nb, 25, "GadScape Credit Packages".length(), StandardCharsets.ISO_8859_1));
        assertEquals(b.length - 1, nb.length);
        List<String[]> none = new ArrayList<>();
        assertSame(nb, If1Text.rewrite(nb, t::apply, none));
        assertNull(If1Text.rewrite(new byte[]{6, 0}, t::apply, none));      // models are not walked
    }

    @Test void brandPatterns() {
        for (String s : new String[]{"Near-Reality Store", "near reality", "OSNR Mystery Box", "Roat Pkz Blackjack", "RoatPkz", "RuneSpawn", "Rune Spawn"})
            assertTrue(StringScan.BRAND.matcher(s).find(), s);
        for (String s : new String[]{"Throat", "Groats", "Gnome restaurant", "nearby reality", "osnrx"})
            assertFalse(StringScan.BRAND.matcher(s).find(), s);
        assertTrue(StringScan.NR.matcher("the NR coin").find());
        assertFalse(StringScan.NR.matcher("NRG / INR / nr").find());
    }

    @Test void claimNew() {
        IdMap m = new IdMap();
        m.setRange(Kind.OBJ, 60000, 64999);
        assertEquals(60000, m.claimNew(Kind.OBJ, "kit:a", 60000, "test"));
        assertEquals(60000, m.claimNew(Kind.OBJ, "kit:a", 60000, "test"));
        assertThrows(IllegalStateException.class, () -> m.claimNew(Kind.OBJ, "kit:b", 60000, "test"));
        assertEquals(60001, m.allocate(Kind.OBJ));
        assertEquals(60000, m.newId(Kind.OBJ, "kit:a"));
    }

    @Test void dataFiles() throws Exception {
        RebrandTable t = RebrandTable.load(com.gadscape.cacheconv.core.Options.parse(new String[0], 0));
        assertFalse(t.isEmpty());
        assertEquals("GadScape Mystery Box", t.apply("OSNR Mystery Box"));
        assertEquals("GadScape Blackjack", t.apply("Roat Pkz Blackjack"));
        for (String r : new String[]{"config/fixes.tsv", "config/relocate.tsv", "ui/fixes.tsv", "verify/area-expect.tsv", "verify/runelite-overlays.tsv",
                "sprite/replace.tsv", "sprite/5265-0-gadscape-coin.png", "ui/scripts/10719-hide_roofs.cs2"}) {
            try (InputStream in = PolishTest.class.getClassLoader().getResourceAsStream(r)) { assertNotNull(in, r); }
        }
        // every config fix row names a kind and an action the step knows
        try (InputStream in = PolishTest.class.getClassLoader().getResourceAsStream("config/fixes.tsv")) {
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (l.isBlank() || l.startsWith("#")) continue;
                String[] c = l.split("\t", -1);
                assertTrue(c.length >= 5, l);
                assertNotNull(Kind.byKey(c[0]), l);
                assertTrue(List.of("set", "unset", "copy", "recolour", "new", "enum-copy", "enum-component", "unset-param").contains(c[2]), l);
            }
        }
    }
}

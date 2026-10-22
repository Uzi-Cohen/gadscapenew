package com.gadscape.cacheconv.rebrand;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.BufOut;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RebrandTest {
    static final RebrandTable T = RebrandTable.parse(List.of(
            "# test table",
            "Near-Reality\tGadScape\ti",
            "OSNR\tGadScape\tw",
            "Roat Pkz\tGadScape"), "test");

    @Test void table() {
        assertEquals(3, T.rules.size());
        assertEquals("Welcome to GadScape!", T.apply("Welcome to near-reality!"));
        assertEquals("GadScape store", T.apply("OSNR store"));
        String s = "OSNRX and roat pkz";                       // whole word only; case-sensitive rule
        assertSame(s, T.apply(s));
        assertTrue(RebrandTable.empty().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RebrandTable.parse(List.of("no tab here"), "bad"));
        assertThrows(IllegalArgumentException.class, () -> RebrandTable.parse(List.of("a\tb\tx"), "bad"));
    }

    @Test void configStrings() {
        // obj: name (op 2), ground op 1 (op 30), inventory op 1 (op 35, opcode byte '#'), end
        byte[] b = new BufOut().u8(2).str("Near-Reality cape").u8(30).str("Take").u8(35).str("Visit OSNR").u8(0).done();
        Rebrander r = new Rebrander(T, null, null);
        List<String[]> changes = new ArrayList<>();
        byte[] nb = r.strings(b, changes);
        assertEquals(2, changes.size());
        byte[] want = new BufOut().u8(2).str("GadScape cape").u8(30).str("Take").u8(35).str("Visit GadScape").u8(0).done();
        assertArrayEquals(want, nb);
        assertNull(Rebrander.sameShape(Arch.CFG_OBJ, 1, b, nb));
    }

    @Test void scriptStrings() {
        Cs2.Script s = new Cs2.Script();
        s.rev241 = true;
        s.name = new byte[]{0};
        s.code.add(new Cs2.Ins(Cs2.SCONST, 0, "Near-Reality"));
        s.code.add(new Cs2.Ins(Cs2.RETURN, 0, null));
        byte[] b = Cs2.write241(s);
        Cs2.Script back = Cs2.parse241(b);
        assertEquals("Near-Reality", back.code.get(0).s);
        assertEquals("GadScape", T.apply(back.code.get(0).s));
        assertTrue(T.mayMatch(b));
        assertTrue(!T.mayMatch("nothing here".getBytes(StandardCharsets.ISO_8859_1)));
    }
}

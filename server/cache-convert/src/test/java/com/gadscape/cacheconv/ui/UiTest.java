package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiTest {
    @Test void syntheticSelfChecks() {
        List<String> f = UiSelfCheck.synthetic();
        assertTrue(f.isEmpty(), f.toString());
    }

    @Test void commandTableLoads() {
        CsCommands c = CsCommands.get();
        assertTrue(c.size() > 650, "table rows " + c.size());
        CsCommands.Cmd g = c.cmd(1105);
        assertNotNull(g);
        assertEquals("graphic", g.argType()[0]);
        assertEquals("component", c.cmd(2105).argType()[1]);
        assertNotNull(c.cmd(1928), "extra table");
        assertTrue(c.known(40) && c.known(2400) && c.known(3408));
    }

    @Test void underflowStopsTyping() {
        byte[] s = UiSelfCheck.script211(0, 0, 0, 0, 1105, 0, 21, 0);   // cc_setgraphic with nothing pushed
        CsSim.Result r = CsSim.run(CsCommands.get(), new UiSelfCheck.MemWorld(), 1, Cs2.parse211(s));
        assertFalse(r.complete);
        assertTrue(r.problem.contains("underflow"), r.problem);
    }

    @Test void unknownCommandStopsTyping() {
        byte[] s = UiSelfCheck.script211(0, 0, 0, 0, 0, 1, 9999, 0, 21, 0);
        CsSim.Result r = CsSim.run(CsCommands.get(), new UiSelfCheck.MemWorld(), 1, Cs2.parse211(s));
        assertFalse(r.complete);
        assertTrue(r.problem.contains("unknown command 9999"), r.problem);
    }

    @Test void signatureRulesAndAdapters() {
        var rules = UiData.signatures();
        assertEquals(List.of(98, 2064, 4218, 6156), List.copyOf(rules.keySet()));
        for (UiData.SigRule r : rules.values()) {
            assertEquals(null, r.shapeProblem(), "rule " + r.target());
            Cs2.Script s = Cs2.parse241(UiAdapters.build(r));
            assertEquals(r.int211(), s.intArgs, "adapter " + r.adapter() + " int args");
            assertEquals(r.obj211(), s.objArgs, "adapter " + r.adapter() + " obj args");
            assertEquals(1, s.code.stream().filter(i -> i.op == Cs2.GOSUB && i.i == r.target()).count(), "adapter " + r.adapter() + " calls its target once");
        }
        // 2064: the 211 constant 9 is passed as 241's new middle argument
        assertEquals("[33 0, 0 9, 33 1, 35 0, 40 2064, 21 0]", Cs2.parse241(UiAdapters.build(rules.get(2064))).code.toString());
        // 4218: same arguments, the int 241 returns is dropped
        assertEquals("[33 0, 33 1, 33 2, 33 3, 33 4, 33 5, 33 6, 33 7, 33 8, 33 9, 35 0, 40 4218, 38 0, 21 0]", Cs2.parse241(UiAdapters.build(rules.get(4218))).code.toString());
    }

    @Test void componentShapeIgnoresTextButNotLayout() {
        byte[] a = UiSelfCheck.if3Graphic(1, new Object[]{10500, 0, "a"}, new Object[]{38, 0}, 1);
        byte[] b = UiSelfCheck.if3Graphic(2, new Object[]{10501, 0, "other text"}, new Object[]{38, 0}, 2);
        assertArrayEquals(IfRefs.shape(a), IfRefs.shape(b));
        byte[] c = a.clone();
        c[4] = 1;                                                     // x position
        assertFalse(java.util.Arrays.equals(IfRefs.shape(a), IfRefs.shape(c)));
    }

    @Test void scopeIdLists() {
        assertEquals("[866, 1700, 1701, 1702, 5100]", UiScope.ids("866, 1700-1702,5100").toString());
        assertTrue(UiScope.ids("").isEmpty());
    }

    @Test void goldenAgainstCacheU() throws Exception {
        Options o = Options.defaults();
        Assumptions.assumeTrue(o.root != null && Files.isDirectory(o.user) && Files.isDirectory(o.van211) && Files.isDirectory(o.ref), "cache layout not available");
        try (JagCache user = JagCache.open("USER", o.user); JagCache van = JagCache.open("VAN211", o.van211); JagCache ref = JagCache.open("REF", o.ref)) {
            StringBuilder log = new StringBuilder();
            List<String> f = UiSelfCheck.golden(user, van, ref, log);
            assertTrue(f.isEmpty(), log + " " + f);
        }
    }
}

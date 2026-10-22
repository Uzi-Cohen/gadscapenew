package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.core.Options;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyTest {
    @Test void selfChecks() {
        assertEquals(List.of(), VerifySelfCheck.run());
    }

    @Test void shippedTableCoversTheStockCommands() {
        CsTable t = CsTable.load();
        assertTrue(t.all().size() > 900, "table size " + t.all().size());
        assertEquals("iiii", t.get(100).pops(), "cc_create takes 4 ints in 241");
        assertTrue(t.get(40).special());
        for (CsTable.Effect e : t.all().values()) if (!e.special()) { e.pop(); e.push(); } // every row parses
    }

    @Test void gosubMismatchIsReportedAtTheCall() {
        Map<Integer, Cs2.Script> s = new TreeMap<>();
        s.put(98, VerifySelfCheck.script(11, 0, 11, 0, 21, 0));
        Object[] code = new Object[2 * 12 + 4];
        for (int i = 0; i < 12; i++) { code[2 * i] = 0; code[2 * i + 1] = i; }
        code[24] = 40; code[25] = 98; code[26] = 21; code[27] = 0;
        s.put(1, VerifySelfCheck.script(0, 0, 0, 0, code));
        CsWorld w = new CsWorld(new MemView("T"), s);
        CsStack.Result r = w.analyseAll(CsTable.load()::counts).get(1);
        assertEquals(1, r.count("gosub-leftover"), r.toString());
        assertEquals(98, r.calls.get(0).callee());
        assertEquals(12, r.calls.get(0).before()[0]);
    }

    @Test void baseIsClean() throws Exception {
        Options o = Options.defaults();
        Assumptions.assumeTrue(o.base != null && Files.isDirectory(o.base), "set -Dgadconv.root for the golden checks");
        assertEquals(List.of(), VerifySelfCheck.full(o.base));
    }
}

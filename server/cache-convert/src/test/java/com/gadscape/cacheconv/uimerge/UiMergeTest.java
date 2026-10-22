package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.core.Options;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiMergeTest {
    @Test void syntheticSelfChecks() {
        List<String> f = UiMergeSelfCheck.synthetic();
        assertTrue(f.isEmpty(), f.toString());
    }

    @Test void storedDataIsComplete() {
        List<MergeData.Entry> m = MergeData.manifest(null);
        assertEquals(UiMergeSpecs.INTERFACES.length + UiMergeSpecs.SCRIPTS.length, m.size(), "one manifest entry per user-modified interface and script");
        for (MergeData.Entry e : m) {
            assertEquals(64, e.baseSha().length(), e.kind() + " " + e.id() + " base sha");
            if (e.decision().equals("base")) { assertFalse(e.note().isBlank(), e.kind() + " " + e.id() + " needs a reason"); continue; }
            String t = MergeData.text(null, e.file());
            assertNotNull(t, e.file());
            if (e.kind().equals("script")) {
                CsText.Doc d = CsText.read(t);
                assertEquals(e.id(), d.id);
                com.gadscape.cacheconv.convert.ScriptTrailer.verify241(e.id(), Cs2.write241(d.s));
            } else {
                MergeData.IfData d = MergeData.readIf(t);
                assertEquals(e.id(), d.iface());
                assertFalse(d.comps().isEmpty());
            }
        }
        assertNotNull(MergeData.text(null, "component-remap.tsv"));
        assertNotNull(MergeData.text(null, "NOTES.md"));
    }

    /** ScriptEdit.remove drops a dead range, keeps outside jumps on their targets and refuses a jump into the range. */
    @Test void scriptEditRemove() {
        Cs2.Script s = new Cs2.Script();
        s.rev241 = true;
        s.name = new byte[]{0};
        // 0 iconst 1; 1 jump -> 5; 2 iconst 2; 3 pop_int; 4 iconst 3; 5 return
        s.code = new java.util.ArrayList<>(List.of(new Cs2.Ins(0, 1, null), new Cs2.Ins(6, 3, null), new Cs2.Ins(0, 2, null), new Cs2.Ins(38, 0, null),
                new Cs2.Ins(0, 3, null), new Cs2.Ins(21, 0, null)));
        ScriptEdit e = new ScriptEdit(1, s);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> e.remove(4, 6, "jump target inside"));
        e.remove(2, 5, "dead");
        assertEquals(3, e.s().code.size());
        assertEquals(6, e.s().code.get(1).op);
        assertEquals(21, e.s().code.get(1 + 1 + e.s().code.get(1).i).op, "the jump still lands on the return");
    }

    @Test void specsCoverEveryId() {
        assertEquals(UiMergeSpecs.INTERFACES.length, UiMergeSpecs.interfaces().size());
        assertEquals(UiMergeSpecs.SCRIPTS.length, ScriptRecipes.specs().size());
    }

    /** Against the real caches (only with -Dgadconv.root): codecs, stored data vs BASE, regeneration identical. */
    @Test void golden() throws Exception {
        Options o = Options.defaults();
        Assumptions.assumeTrue(o.root != null && o.base != null && Files.isDirectory(o.base) && Files.isDirectory(o.user) && Files.isDirectory(o.van211),
                "set -Dgadconv.root to run the golden UI merge checks");
        List<String> f = UiMergeSelfCheck.golden(o);
        assertTrue(f.isEmpty(), String.join("\n", f));
    }
}

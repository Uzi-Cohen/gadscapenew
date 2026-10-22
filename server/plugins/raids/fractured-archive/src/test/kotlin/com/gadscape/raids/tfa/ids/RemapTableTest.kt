package com.gadscape.raids.tfa.ids

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RemapTableTest {

    @Test
    fun absentFileIsIdentity() {
        val table = RemapTable.loadDefault(Path.of("does", "not", "exist", "remap.tsv"))
        assertSame(RemapTable.IDENTITY, table)
        assertEquals(15000, table.resolve(IdKind.SEQ, 15000))
        assertTrue(table.isIdentity)
    }

    @Test
    fun theSpecsProposedRemapParses() {
        val table = RemapTable.parse(listOf(
            "kind\told\tnew",
            "# spec 0.2 proposal",
            "seq\t15000-15162\t15300-15462",
            "clientscript\t10034-10048\t11000",
            "clientscript\t10102\t11016\t# stats open",
            "struct 1216 13000",
            "",
        ), "test")
        assertTrue(table.problems.isEmpty(), table.problems.joinToString())
        assertEquals(15324, table.resolve(IdKind.SEQ, 15024), "Guardian battery idle")
        assertEquals(15462, table.resolve(IdKind.SEQ, 15162), "jelly spawn")
        assertEquals(14999, table.resolve(IdKind.SEQ, 14999), "outside the range")
        assertEquals(11002, table.resolve(IdKind.CLIENTSCRIPT, 10036), "timer")
        assertEquals(11008, table.resolve(IdKind.CLIENTSCRIPT, 10042), "orb names")
        assertEquals(11016, table.resolve(IdKind.CLIENTSCRIPT, 10102), "stats open")
        assertEquals(13000, table.resolve(IdKind.STRUCT, 1216))
        assertEquals(10010, table.resolve(IdKind.CLIENTSCRIPT, 10010), "prefetch scripts do not clash")
        assertEquals(15000, table.resolve(IdKind.NPC, 15000), "kinds are separate")
        assertEquals(163 + 15 + 1 + 1, table.size)
    }

    @Test
    fun badLinesAreReportedAndSkipped() {
        val table = RemapTable.parse(listOf(
            "seq\t15000-15010\t15300-15305",   // length mismatch
            "monster\t1\t2",                   // unknown kind
            "npc\tabc\t5",                     // bad id
            "npc\t16631",                      // too few columns
            "npc\t16631\t20000",
            "npc\t16631\t20001",               // conflicting second mapping
            "npc\t16632\t20000",               // two olds on one new
        ), "bad.tsv")
        assertEquals(20000, table.resolve(IdKind.NPC, 16631), "the first mapping wins")
        assertEquals(15000, table.resolve(IdKind.SEQ, 15000), "a rejected range maps nothing")
        assertEquals(6, table.problems.size, table.problems.joinToString("\n"))
        assertTrue(table.problems.all { it.startsWith("bad.tsv:") })
        assertTrue(table.problems.any { "bad.tsv:2: unknown kind" in it })
    }

    @Test
    fun loadsAFile() {
        val dir = createTempDirectory("tfa-remap")
        val file = dir.resolve("remap.tsv")
        file.toFile().writeText("spotanim\t4023\t9023\n")
        try {
            val table = RemapTable.loadDefault(file)
            assertEquals(9023, table.resolve(IdKind.SPOTANIM, 4023))
            assertEquals(file.toString(), table.source)
        } finally {
            file.deleteIfExists()
            dir.deleteIfExists()
        }
    }

    @Test
    fun kindAliases() {
        assertEquals(IdKind.SPOTANIM, IdKind.parse("spot"))
        assertEquals(IdKind.CLIENTSCRIPT, IdKind.parse("Script"))
        assertEquals(IdKind.OBJ, IdKind.parse("item"))
        assertEquals(null, IdKind.parse("monster"))
    }
}

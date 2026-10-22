package com.gadscape.raids.tfa.stats

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.TfaRoomId
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec §3.14 / WU-19: categories, rows, the 10125 lines, records storage and formatting. */
class StatsTest {

    @Test
    fun categoriesFollowEnum6066AndTheirDbrows() {
        assertEquals(9, StatsCategory.entries.size)
        assertEquals((0..8).toList(), StatsCategory.entries.map { it.tab })
        assertEquals(TfaIds.Dbrow.STATS_OVERALL, StatsCategory.OVERALL.dbrow)
        assertEquals(17134, StatsCategory.GUARDIAN.dbrow)
        assertEquals(17140, StatsCategory.DREADNOTH.dbrow)
        assertEquals(17139, StatsCategory.OCELLUS.dbrow)
        for (room in TfaRoomId.entries) {
            val c = StatsCategory.of(room)
            assertEquals(room.statsTab, c.tab, "$room tab")
            assertEquals(c, StatsCategory.byDbrow(room.statsDbrow))
        }
        assertEquals(8360, StatsCategory.OVERALL.icon, "icon_boss_25x25,72")
        assertEquals(8575, StatsCategory.OCELLUS.icon, "icon_boss_25x25,80")
    }

    @Test
    fun rowsPerCategoryMatchDbtable264() {
        assertEquals(listOf(StatsRow.ATTEMPTS, StatsRow.COMPLETIONS, StatsRow.DEATHS, StatsRow.WIPES, StatsRow.FAILURES, StatsRow.TIME),
            StatsCategory.OVERALL.rows)
        assertEquals(listOf(StatsRow.ATTEMPTS, StatsRow.COMPLETIONS, StatsRow.COMPLETIONS_PRACTICE, StatsRow.DEATHS, StatsRow.TIME),
            StatsCategory.XARTH.rows)
        assertEquals((17142..17148).toList(), StatsRow.entries.map { it.dbrow })
    }

    @Test
    fun linesSendTimeRowsForEveryPartySize() {
        val personal = StatsTable()
        val all = StatsTable()
        val today = StatsTable()
        personal.add(StatsCategory.OVERALL, StatsRow.ATTEMPTS, 3)
        all.add(StatsCategory.OVERALL, StatsRow.ATTEMPTS, 40)
        today.add(StatsCategory.OVERALL, StatsRow.ATTEMPTS, 5)
        personal.recordTime(StatsCategory.OVERALL, 4, 1500)
        val lines = StatsRows.lines(StatsCategory.OVERALL, personal, all, today) { _, _ -> null }
        assertEquals(5 + 8, lines.size)
        val attempts = lines.first()
        assertEquals(StatsRow.ATTEMPTS, attempts.row)
        assertEquals(0, attempts.partySize)
        assertEquals(StatsRows.BLANK, attempts.current, "no Current column outside a raid")
        assertEquals(3, attempts.personal)
        assertEquals(40, attempts.global)
        assertEquals(5, attempts.globalToday)
        assertEquals(5, attempts.worldToday, "one world: World Today = Global Today")
        val times = lines.filter { it.row == StatsRow.TIME }
        assertEquals((1..8).toList(), times.map { it.partySize })
        assertEquals(1500, times[3].personal)
        assertEquals(0, times[0].personal, "0 prints \"-\"")
        assertEquals(12, StatsRows.lines(StatsCategory.MOIA, personal, all, today) { _, _ -> null }.size)
    }

    @Test
    fun currentTimeRowCountsUpWhenNotFrozen() {
        val lines = StatsRows.lines(StatsCategory.GUARDIAN, StatsTable(), StatsTable(), StatsTable()) { row, size ->
            if (row == StatsRow.TIME && size == 3) CurrentValue(120, frozen = false) else null
        }
        val t3 = lines.first { it.row == StatsRow.TIME && it.partySize == 3 }
        assertEquals(120, t3.current)
        assertEquals(0, t3.currentFrozen)
        val t2 = lines.first { it.row == StatsRow.TIME && it.partySize == 2 }
        assertEquals(StatsRows.BLANK, t2.current)
    }

    @Test
    fun personalBestKeepsTheLowestTimePerSize() {
        val t = StatsTable()
        assertTrue(t.recordTime(StatsCategory.XARTH, 4, 500))
        assertFalse(t.recordTime(StatsCategory.XARTH, 4, 600))
        assertTrue(t.recordTime(StatsCategory.XARTH, 4, 450))
        assertEquals(450, t.bestTime(StatsCategory.XARTH, 4))
        assertEquals(0, t.bestTime(StatsCategory.XARTH, 5))
        assertTrue(t.recordTime(StatsCategory.XARTH, 12, 1), "sizes clamp to 8")
        assertEquals(1, t.bestTime(StatsCategory.XARTH, 8))
        assertFalse(t.recordTime(StatsCategory.XARTH, 2, 0))
    }

    @Test
    fun todayResetsAtTheDateChange() {
        val r = GlobalRecords()
        assertTrue(r.rollTo("2026-10-22"))
        r.today.add(StatsCategory.OVERALL, StatsRow.WIPES)
        r.allTime.add(StatsCategory.OVERALL, StatsRow.WIPES)
        assertFalse(r.rollTo("2026-10-22"))
        assertEquals(1, r.today.get(StatsCategory.OVERALL, StatsRow.WIPES))
        assertTrue(r.rollTo("2026-10-23"))
        assertEquals(0, r.today.get(StatsCategory.OVERALL, StatsRow.WIPES))
        assertEquals(1, r.allTime.get(StatsCategory.OVERALL, StatsRow.WIPES))
    }

    @Test
    fun recordsFileRoundTripsAndSurvivesCorruption() {
        val dir = createTempDirectory("tfa-records-test")
        try {
            val file = RecordsFile(dir)
            assertTrue(file.load().allTime.isEmpty(), "a missing file starts empty")
            val r = GlobalRecords()
            r.rollTo("2026-10-22")
            r.allTime.add(StatsCategory.BORK, StatsRow.COMPLETIONS, 7)
            r.allTime.recordTime(StatsCategory.BORK, 3, 345)
            assertTrue(file.write(RecordsFile.encode(r)))
            val back = file.load()
            assertEquals(7, back.allTime.get(StatsCategory.BORK, StatsRow.COMPLETIONS))
            assertEquals(345, back.allTime.bestTime(StatsCategory.BORK, 3))
            assertEquals("2026-10-22", back.todayDate)

            dir.resolve(RecordsFile.FILE_NAME).writeText("{ not json")
            val fresh = file.load()
            assertTrue(fresh.allTime.isEmpty())
            assertFalse(dir.resolve(RecordsFile.FILE_NAME).exists(), "the bad file is moved aside")
            assertTrue(dir.listDirectoryEntries().any { it.fileName.toString().startsWith("global.json.corrupt-") })
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    @Test
    fun durationsUseHundredthsOfTicksAt0Point6Seconds() {
        assertEquals("0:00.60", StatsText.duration(1))
        assertEquals("3:27.60", StatsText.duration(346), "wiki Guardian 1p record 3:27.60")
        assertEquals("19:01.20", StatsText.duration(1902))
        assertEquals("1:00:00.00", StatsText.duration(6000))
    }
}

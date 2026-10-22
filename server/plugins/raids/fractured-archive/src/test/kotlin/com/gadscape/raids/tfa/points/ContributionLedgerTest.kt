package com.gadscape.raids.tfa.points

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.raid.TfaRoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContributionLedgerTest {

    private val cfg = PointsConfig.DEFAULT

    @Test
    fun bundledPointsJsonMatchesDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(PointsConfig)

    @Test
    fun poolsSumTo35000AndPairsAreEqual() {
        // spec §3.10: 4000 + 6000 + 6000 + 7000 + 12000 = 35000; paired rooms award the same total [DOC].
        assertEquals(35_000, cfg.max)
        assertEquals(16_000, cfg.firstThreeRooms)
        for (section in 1..3) {
            val pair = TfaRoomId.entries.filter { it.section == section }
            assertEquals(1, pair.map { cfg.pool(it) }.distinct().size, "section $section")
        }
    }

    @Test
    fun sharesSumExactlyToThePool() {
        val l = ContributionLedger(cfg)
        l.record(TfaRoomId.GUARDIAN, "A", 333)
        l.record(TfaRoomId.GUARDIAN, "b", 333)
        l.record(TfaRoomId.GUARDIAN, "C", 334)
        val shares = l.bank(TfaRoomId.GUARDIAN)
        assertEquals(4000, shares.values.sum())
        assertEquals(setOf("a", "b", "c"), shares.keys)
        // largest remainder: c dealt the most
        assertEquals(1336, shares.getValue("c"))
    }

    @Test
    fun cappedDamageIsWhatCounts() {
        // TfaNpc caps a hit at the HP the target had (overkill capped); the ledger sums what it is given.
        val l = ContributionLedger(cfg)
        l.record(TfaRoomId.BORK, "a", 50) // e.g. a 99 on a 50-HP smork
        l.record(TfaRoomId.BORK, "b", 50)
        assertEquals(mapOf("a" to 3000, "b" to 3000), l.bank(TfaRoomId.BORK))
    }

    @Test
    fun bankingIsOnceAndLateDamageIsIgnored() {
        val l = ContributionLedger(cfg)
        l.record(TfaRoomId.MOIA, "a", 100)
        l.bank(TfaRoomId.MOIA)
        l.record(TfaRoomId.MOIA, "a", 100_000)
        l.bank(TfaRoomId.MOIA)
        assertEquals(6000, l.banked("a"))
        assertEquals(6000, l.total())
    }

    @Test
    fun deathRemovesTwentyPercentOfBankedPoints() {
        val l = ContributionLedger(cfg)
        l.record(TfaRoomId.GUARDIAN, "a", 10)
        l.bank(TfaRoomId.GUARDIAN)
        assertEquals(800, l.applyDeathPenalty("A"))
        assertEquals(3200, l.banked("a"))
        assertEquals(640, l.applyDeathPenalty("a"))
        assertEquals(2560, l.banked("a"))
        assertEquals(1440, l.penalties("a"))
    }

    @Test
    fun aRoomWithoutDamageBanksNothing() {
        val l = ContributionLedger(cfg)
        assertTrue(l.bank(TfaRoomId.XARTH).isEmpty())
        assertEquals(0, l.total())
    }

    @Test
    fun poorPerformanceThreshold() {
        // < 10% of MAX / size: for 4 players 35000 / 4 = 8750, 10% = 875.
        val l = ContributionLedger(cfg)
        assertTrue(ContributionLedger.isBelow(874, 10, 35_000, 4))
        assertFalse(ContributionLedger.isBelow(875, 10, 35_000, 4))
        l.record(TfaRoomId.GUARDIAN, "a", 1)
        l.record(TfaRoomId.GUARDIAN, "b", 99)
        l.bank(TfaRoomId.GUARDIAN)
        assertTrue(l.isPoorPerformance("a", 4))   // 40 points
        assertFalse(l.isPoorPerformance("b", 4))  // 3960 points
    }

    @Test
    fun splitWithOnePlayerGivesTheWholePool() {
        assertEquals(mapOf("solo" to 12_000), ContributionLedger.split(12_000, mapOf("solo" to 5L)))
    }
}

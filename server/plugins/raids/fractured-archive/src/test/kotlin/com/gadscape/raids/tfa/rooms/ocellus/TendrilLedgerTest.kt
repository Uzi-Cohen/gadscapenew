package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TendrilLedgerTest {

    @Test
    fun blockedTendrilsAddNoMeterAndBlockersAreHealedAtTheEnd() {
        val ledger = TendrilLedger<String>(blockDmg = 5, meterPerUnblocked = 8, meterMax = 100)
        var meter = 0
        val a = ledger.resolve(listOf("alice"), meter)
        assertEquals(TendrilLedger.Outcome.Blocked(5, 1), a)
        val b = ledger.resolve(emptyList(), meter) as TendrilLedger.Outcome.Unblocked
        meter = b.meterAfter
        assertEquals(8, meter)
        ledger.resolve(listOf("alice", "bob"), meter)
        val heals = ledger.settle()
        assertEquals(mapOf("alice" to 10, "bob" to 5), heals)
        assertTrue(ledger.settle().isEmpty())
        assertEquals(3, ledger.resolved)
        assertEquals(2, ledger.blocked)
    }

    @Test
    fun meterCapsAtMax() {
        val ledger = TendrilLedger<String>(5, 8, 100)
        var meter = 96
        val o = ledger.resolve(emptyList(), meter) as TendrilLedger.Outcome.Unblocked
        meter = o.meterAfter
        assertEquals(100, meter)
        assertEquals(4, o.gained)
        // 7 unblocked tendrils on an empty meter reach 56 ≥ 50 (annihilation); 6 reach 48 (death awaits)
        var m = 0
        repeat(7) { m = (ledger.resolve(emptyList(), m) as TendrilLedger.Outcome.Unblocked).meterAfter }
        assertEquals(56, m)
    }

    @Test
    fun deadPlayersAreNotHealed() {
        val ledger = TendrilLedger<String>(5, 8, 100)
        ledger.resolve(listOf("alice", "bob"), 0)
        ledger.forget("bob")
        assertEquals(mapOf("alice" to 5), ledger.settle())
    }

    @Test
    fun anchorOrderSkipsTheWallDirection() {
        val geo = ArenaGeometry()
        val sw = StaticTile(4119, 5665, 1)
        val lines = ArenaGeometry.CLOCKWISE.associateWith { geo.tendrilLine(sw.x, sw.y, 3, it) }
        val order = TendrilPlan.anchorOrder(lines, 7) { 0 }
        assertEquals(7, order.size)
        assertFalse(Compass.SW in order)
        assertEquals(listOf(Compass.W, Compass.NW, Compass.N, Compass.NE, Compass.E, Compass.SE, Compass.S), order)
    }

    @Test
    fun anchorOrderSkipsARandomDirectionWhenAllHaveTiles() {
        val geo = ArenaGeometry()
        val c = StaticTile(4126, 5672, 1)
        val lines = ArenaGeometry.CLOCKWISE.associateWith { geo.tendrilLine(c.x, c.y, 3, it) }
        val order = TendrilPlan.anchorOrder(lines, 7) { 2 } // skip E
        assertEquals(listOf(Compass.SE, Compass.S, Compass.SW, Compass.W, Compass.NW, Compass.N, Compass.NE), order)
    }

    @Test
    fun everyAnchorHasSevenTendrils() {
        val geo = ArenaGeometry()
        for (a in listOf(StaticTile(4119, 5665, 1), StaticTile(4119, 5679, 1), StaticTile(4133, 5679, 1), StaticTile(4133, 5665, 1))) {
            val lines = ArenaGeometry.CLOCKWISE.associateWith { geo.tendrilLine(a.x, a.y, 3, it) }
            assertEquals(7, TendrilPlan.anchorOrder(lines, 7) { 0 }.size, "$a")
        }
    }

    @Test
    fun centreRoundsAreClockwisePairsFromNorth() {
        assertEquals(
            listOf(Compass.N to Compass.NE, Compass.E to Compass.SE, Compass.S to Compass.SW, Compass.W to Compass.NW),
            TendrilPlan.centreRounds(),
        )
    }

    @Test
    fun thresholdsCrossedByOneHitQueueInOrder() {
        val t = ThresholdTracker(listOf(25, 66))
        assertEquals(emptyList(), t.crossed(2500, 1700, 2500))
        assertEquals(listOf(66, 25), t.crossed(1700, 600, 2500))
        assertEquals(listOf(66, 25), t.pendingList())
        assertEquals(66, t.poll())
        assertEquals(emptyList(), t.crossed(600, 100, 2500)) // never twice
        assertEquals(25, t.poll())
        assertFalse(t.hasPending())
        // exactly at the mark counts as crossed (Scaling.crossed)
        val u = ThresholdTracker(listOf(66))
        assertEquals(listOf(66), u.crossed(1651, 1650, 2500))
    }
}

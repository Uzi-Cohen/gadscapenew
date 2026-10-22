package com.gadscape.raids.tfa.encounter

import com.gadscape.game.world.entity.player.status.BurnStacks
import com.gadscape.game.world.entity.player.status.PlayerBurn
import com.gadscape.game.world.entity.player.status.PrayerDrainOnAttack
import com.gadscape.game.world.entity.player.status.VirusHost
import com.gadscape.game.world.entity.player.status.VirusOutbreak
import com.gadscape.game.world.entity.player.status.VirusSettings
import com.gadscape.game.world.entity.player.status.Weary
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WU-05 (spec §6.2) acceptance tests for the player statuses (E10, E11), with a seeded RNG and a fake virus host. */
class PlayerStatusesTest {

    /** 3 stacks deal 3 every 4 t and expire after 40 t. */
    @Test
    fun burnStacksDealStackCountEveryFourTicks() {
        val burn = BurnStacks(PlayerBurn.MAX_STACKS, PlayerBurn.INTERVAL, PlayerBurn.HITS_PER_STACK)
        assertEquals(3, burn.add(3))
        assertEquals(30, burn.remainingDamage)
        val hits = mutableMapOf<Int, Int>()
        for (tick in 1..60) {
            val dmg = burn.tick()
            if (dmg > 0) hits[tick] = dmg
        }
        assertEquals((1..10).associate { it * 4 to 3 }, hits, "3 damage on ticks 4, 8, ..., 40")
        assertTrue(burn.isEmpty, "expired after 40 t")
        assertEquals(0, burn.remainingDamage)
    }

    /** A 6th stack is refused; a stack added mid-burn joins the shared clock. */
    @Test
    fun sixthStackIsRefused() {
        val burn = BurnStacks()
        assertEquals(5, burn.add(7))
        assertFalse(burn.add())
        assertEquals(5, burn.stacks)

        val late = BurnStacks()
        late.add()
        repeat(2) { late.tick() }
        late.add() // joins at tick 2; next beat at tick 4 hits for 2
        assertEquals(0, late.tick())
        assertEquals(2, late.tick())
    }

    /** Burn and virus clear on death. */
    @Test
    fun burnAndVirusClearOnDeath() {
        val burn = BurnStacks()
        burn.add(2)
        burn.clear() // PlayerBurn.clear / the burn task on a dead player
        assertTrue(burn.isEmpty)
        assertEquals(0, burn.tick())

        val a = Carrier(0, 0)
        val outbreak = outbreak()
        outbreak.infect(a)
        assertTrue(outbreak.isInfected(a))
        a.dead = true
        outbreak.tick(listOf(a))
        assertFalse(outbreak.isInfected(a), "a dead carrier is cured")
        assertEquals(listOf(a), host.cleared)
    }

    /** Weary adds 1 tick to a whip's delay and blocks running for 25 t. */
    @Test
    fun wearySlowsAttacksForTwentyFiveTicks() {
        val whip = 4
        assertEquals(5, Weary.adjustAttackSpeed(whip, weary = true))
        assertEquals(4, Weary.adjustAttackSpeed(whip, weary = false))
        assertEquals(25, Weary.DEFAULT_TICKS)
        val applied = 1000L
        val until = applied + Weary.DEFAULT_TICKS
        for (now in applied until until) assertTrue(Weary.remaining(until, now) > 0, "weary at +${now - applied}")
        assertEquals(0, Weary.remaining(until, until), "run allowed again after 25 t")
    }

    /** A virus carrier infects an adjacent player within 1 tick; a serpentine helm cuts its damage to 25%. */
    @Test
    fun virusSpreadsToAdjacentPlayersAndSerpentineHelmCutsIt() {
        val carrier = Carrier(10, 10)
        val adjacent = Carrier(11, 11)
        val far = Carrier(13, 10)
        val outbreak = outbreak()
        outbreak.infect(carrier)
        outbreak.tick(listOf(carrier, adjacent, far))
        assertTrue(outbreak.isInfected(adjacent), "Chebyshev 1")
        assertFalse(outbreak.isInfected(far))
        assertEquals(settings.duration, outbreak.ticksLeft(adjacent))

        assertEquals(2, VirusOutbreak.playerCoughDamage(8, serpentine = true, settings))
        assertEquals(1, VirusOutbreak.playerCoughDamage(4, serpentine = true, settings))
        assertEquals(8, VirusOutbreak.playerCoughDamage(8, serpentine = false, settings))

        // coughs every 4 t for 20 t: 5 coughs, 4-8 each, then cured
        val solo = Carrier(50, 50)
        val o2 = outbreak()
        o2.infect(solo)
        repeat(25) { o2.tick(listOf(solo)) }
        assertEquals(5, solo.coughs.size)
        assertTrue(solo.coughs.all { it in 4..8 })
        assertFalse(o2.isInfected(solo))
    }

    /** Prayer drain on every attack: 2 per jelly attack; Ward of Arceuus against a demon leaves 1. */
    @Test
    fun prayerDrainWithWardOfArceuus() {
        assertEquals(2, PrayerDrainOnAttack.wardReduced(2, ward = false))
        assertEquals(1, PrayerDrainOnAttack.wardReduced(2, ward = true))
    }

    // ---------------------------------------------------------------------------------------------------- fakes

    private val settings = VirusSettings(duration = 20, coughEvery = 4, damageMin = 4, damageMax = 8, serpentineMultiplier = 0.25)

    private class Carrier(var x: Int, var y: Int) {
        var dead = false
        val coughs = mutableListOf<Int>()
    }

    private class FakeHost : VirusHost<Carrier> {
        val cleared = mutableListOf<Carrier>()
        override fun x(carrier: Carrier) = carrier.x
        override fun y(carrier: Carrier) = carrier.y
        override fun plane(carrier: Carrier) = 0
        override fun isGone(carrier: Carrier) = carrier.dead
        override fun coughDamage(carrier: Carrier, settings: VirusSettings, random: Random) =
            settings.damageMin + random.nextInt(settings.damageMax - settings.damageMin + 1)
        override fun applyCough(carrier: Carrier, damage: Int, settings: VirusSettings) {
            carrier.coughs += damage
        }
        override fun onCleared(carrier: Carrier) {
            cleared += carrier
        }
    }

    private val host = FakeHost()

    private fun outbreak() = VirusOutbreak(settings, Random(7), host)
}

package com.gadscape.raids.tfa.rooms.guardian

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.Scaling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Archive Guardian state machine against a recording host (no world): every transition, clock and threshold of
 * spec §4.1 with the default `guardian.json`.
 */
class GuardianFightTest {

    private val cfg: GuardianConfig = TfaConfigTestKit.load(listOf(GuardianConfig), bundled = emptyMap())[GuardianConfig]

    /** Records what the fight asks for; carries the npc pool HP like the real npc. */
    private class Host : GuardianHost {
        lateinit var fight: GuardianFight
        val events = ArrayList<String>()
        var hp = 0
        var max = 0
        var pool = "none"
        var enraged = false

        fun at(name: String): List<Int> = events.filter { it.startsWith("$name@") }.map { it.substringAfter('@').substringBefore(' ').toInt() }

        private fun rec(name: String, extra: String = "") {
            events += "$name@${fight.now}" + (if (extra.isEmpty()) "" else " $extra")
        }

        /** A processed hit on the npc: what ArchiveGuardianNpc.processHit does. */
        fun damage(amount: Int) {
            val before = hp
            hp = (hp - amount).coerceAtLeast(0)
            fight.onPoolHpChanged(before, hp)
        }

        override fun playWake() = rec("wake")
        override fun showShieldPool(shield: Int, max: Int) {
            pool = "shield"; hp = shield; this.max = max; rec("shield", "$shield/$max")
        }
        override fun showMainPool(hp: Int, max: Int, enraged: Boolean) {
            pool = "main"; this.hp = hp; this.max = max; this.enraged = enraged; rec("main", "$hp/$max${if (enraged) " enraged" else ""}")
        }
        override fun poolHp(): Int = hp
        override fun setPoolHp(hp: Int) {
            this.hp = hp
        }
        override fun playLanding() = rec("land")
        override fun playGroundIdle() = rec("groundIdle")
        override fun playLifting() = rec("lift")
        override fun playEnrage() = rec("enrage")
        override fun playDeath() = rec("death")
        override fun showDead() = rec("dead")
        override fun followTarget() {}
        override fun attack(kind: GuardianAttack) = rec(kind.name.lowercase())
        override fun tendrilWave() = rec("tendrils")
        override fun dischargeOnShield(amount: Int) {
            rec("dischargeShield", "$amount")
            damage(amount)
        }
        override fun dischargeOnMain(amount: Int, floorHp: Int) {
            rec("dischargeMain", "$amount floor $floorHp")
            damage(GuardianRules.clampToFloor(hp, amount, floorHp))
        }
        override fun clearHazards() = rec("clear")
    }

    private fun newFight(scale: Int = 2, c: GuardianConfig = cfg): Pair<GuardianFight, Host> {
        val host = Host()
        val f = GuardianFight(c, Scaling.DEFAULT.hp(c.shieldHp, scale), Scaling.DEFAULT.hp(c.mainHp, scale), host)
        host.fight = f
        return f to host
    }

    private fun GuardianFight.run(ticks: Int) = repeat(ticks) { tick() }

    @Test
    fun wakeThenFlyingClock() {
        val (f, h) = newFight()
        assertEquals(GuardianState.DORMANT, f.state)
        assertTrue(f.start())
        assertFalse(f.start(), "a second Touch does nothing")
        assertEquals(GuardianState.WAKING, f.state)
        assertTrue(f.isInvulnerable)
        f.run(5)
        assertEquals(GuardianState.FLYING, f.state, "wake seq 14755 is 5 t")
        assertEquals(listOf(5), h.at("shield"))
        assertEquals(550, h.hp)
        f.run(20)
        // firstAttackDelay 2, attack speed 5; every 3rd attack is a mushroom cast.
        assertEquals(listOf(7, 12, 22), h.at("auto"))
        assertEquals(listOf(17), h.at("mushrooms"))
        assertTrue(f.isFlying)
        assertTrue(f.isTargetable)
    }

    /** Spec WU-20: shield break → 4 t land → grounded; tendrils first at grounded + 6 t then every 3 t. */
    @Test
    fun shieldBreakLandsAndGrounds() {
        val (f, h) = newFight()
        f.start()
        f.run(6)
        h.damage(549)
        assertEquals(GuardianState.FLYING, f.state)
        h.damage(10) // overflow discarded
        assertEquals(GuardianState.LANDING, f.state)
        assertEquals("main", h.pool)
        assertEquals(1100, h.hp, "the main pool is untouched by the shield phase")
        assertTrue(f.isInvulnerable)
        assertFalse(f.isTargetable)
        val landed = f.now
        assertEquals(listOf(landed), h.at("land"))
        f.run(4)
        assertEquals(GuardianState.GROUNDED, f.state, "land seq 14747 is 4 t")
        val grounded = f.now
        assertEquals(65, f.liftBoundaryPct)
        f.run(12)
        assertEquals(listOf(grounded + 6, grounded + 9, grounded + 12), h.at("tendrils"))
        assertEquals(listOf(grounded, grounded + 4, grounded + 8, grounded + 12), h.at("groundIdle"))
        assertTrue(h.at("auto").all { it < landed }, "no ranged auto while grounded")
        assertFalse(f.isFlying)
    }

    @Test
    fun liftsAfter24TicksWithAFullShield() {
        val (f, h) = newFight()
        f.start()
        f.run(5)
        h.damage(550)
        f.run(4)
        val grounded = f.now
        h.damage(100) // 1000 / 1100: no boundary crossed
        f.run(24)
        assertEquals(listOf(grounded + 24), h.at("lift"))
        assertEquals(GuardianState.LIFTING, f.state)
        assertEquals("shield", h.pool)
        assertEquals(550, h.hp)
        assertEquals(1000, f.storedMainHp)
        assertEquals(listOf(grounded + 6, grounded + 9, grounded + 12, grounded + 15, grounded + 18, grounded + 21), h.at("tendrils"), "no wave after the lift")
        f.run(3)
        assertEquals(GuardianState.FLYING, f.state, "lift seq 14748 is 3 t")
        // Second landing keeps the main HP.
        h.damage(550)
        assertEquals(1000, h.hp)
        assertEquals(2, f.landings)
    }

    @Test
    fun liftsWhenMainCrosses65Percent() {
        val (f, h) = newFight()
        f.start()
        f.run(5)
        h.damage(550)
        f.run(4)
        h.damage(300) // 800 > 715
        assertEquals(GuardianState.GROUNDED, f.state)
        h.damage(100) // 700 <= 715 (65% of 1100)
        assertEquals(GuardianState.LIFTING, f.state)
        f.run(3)
        h.damage(550)
        f.run(4)
        assertEquals(GuardianState.GROUNDED, f.state)
        assertEquals(null, f.liftBoundaryPct, "below 65% only the enrage or the timer ends it")
    }

    /** Spec WU-20: 30% → 14 t enrage, invulnerable; one hit through 65% and 30% enrages. */
    @Test
    fun enrageAtThirtyPercent() {
        val (f, h) = newFight()
        f.start()
        f.run(5)
        h.damage(550)
        f.run(4)
        h.damage(800) // 300 <= 330: crosses 65% and 30% at once
        assertEquals(GuardianState.ENRAGING, f.state)
        assertTrue(h.enraged)
        assertTrue(f.isInvulnerable)
        assertFalse(f.isTargetable)
        assertTrue(f.isFlying)
        assertEquals(0, h.at("lift").size)
        val enraging = f.now
        f.run(14)
        assertEquals(GuardianState.ENRAGED, f.state, "enrage seq 14754 is 14 t")
        assertFalse(f.isInvulnerable)
        f.run(20)
        val first = enraging + 14 + cfg.firstAttackDelay
        assertEquals(listOf(first, first + 10), h.at("mushrooms"))
        assertEquals(listOf(first + 5, first + 15), h.at("laser"))
        assertEquals(0, h.at("tendrils").count { it > enraging })
    }

    @Test
    fun deathAndCompletion() {
        val (f, h) = newFight()
        f.debugSkipTo("enraged")
        assertEquals(GuardianState.ENRAGED, f.state)
        h.damage(h.hp)
        assertEquals(GuardianState.DYING, f.state)
        assertEquals(1, h.at("clear").size)
        val dying = f.now
        f.run(8)
        assertFalse(f.isComplete)
        f.run(1)
        assertTrue(f.isComplete, "death seq 14749 is 9 t")
        assertEquals(listOf(dying + 9), h.at("dead"))
        val events = h.events.size
        f.run(30)
        assertEquals(events, h.events.size, "nothing happens after death")
        h.damage(10)
        assertEquals(GuardianState.DEAD, f.state)
    }

    /** Spec WU-20: a battery kill removes 40% of the max shield (and the 6-scale data point 596 / 1490). */
    @Test
    fun batteriesOnTheShield() {
        val (f, h) = newFight(scale = 6)
        f.start()
        f.run(5)
        assertEquals(1490, h.hp)
        f.onBatteryDestroyed(byLaser = false)
        assertEquals(894, h.hp)
        assertTrue(h.events.any { it.startsWith("dischargeShield@") && it.endsWith(" 596") })
        f.onBatteryDestroyed(byLaser = false)
        f.onBatteryDestroyed(byLaser = false) // breaks the shield
        assertEquals(GuardianState.LANDING, f.state)
    }

    @Test
    fun batteryDischargeWhileGroundedIsBanked() {
        val (f, h) = newFight()
        f.start()
        f.run(5)
        h.damage(550)
        f.run(4)
        f.onBatteryDestroyed(byLaser = false)
        assertEquals(220, f.bankedDischarge)
        assertEquals(1100, h.hp, "main HP is not hit while grounded")
        f.run(24 + 3)
        assertEquals(GuardianState.FLYING, f.state)
        assertEquals(0, f.bankedDischarge)
        assertEquals(330, h.hp, "the next shield starts 220 down")
    }

    @Test
    fun batteryDuringEnrageStopsAtKillRange() {
        val (f, h) = newFight()
        f.debugSkipTo("enraged")
        h.setPoolHp(200)
        f.onBatteryDestroyed(byLaser = true)
        assertEquals(110, h.hp, "clamped at 10% of max main")
        f.onBatteryDestroyed(byLaser = true)
        assertEquals(110, h.hp, "in kill range batteries stop hurting it")
    }

    @Test
    fun stopCancelsEverything() {
        val (f, h) = newFight()
        f.start()
        f.run(8)
        val n = h.events.size
        f.stop()
        f.run(50)
        assertEquals(n, h.events.size)
    }

    @Test
    fun debugPhases() {
        for (phase in GuardianFight.PHASES) {
            val (f, _) = newFight()
            assertTrue(f.debugSkipTo(phase), phase)
            val expected = when (phase) {
                "flying" -> GuardianState.FLYING
                "grounded" -> GuardianState.LANDING
                "lifting" -> GuardianState.LIFTING
                "enrage" -> GuardianState.ENRAGING
                "enraged" -> GuardianState.ENRAGED
                "dying" -> GuardianState.DYING
                else -> error(phase)
            }
            assertEquals(expected, f.state, phase)
        }
        val (f, _) = newFight()
        assertFalse(f.debugSkipTo("nonsense"))
    }

    @Test
    fun scaledPools() {
        for ((n, shield, main) in listOf(Triple(2, 550, 1100), Triple(6, 1490, 2980), Triple(8, 1960, 3920))) {
            val (f, h) = newFight(scale = n)
            f.start()
            f.run(5)
            assertEquals(shield, h.hp)
            assertEquals(shield, f.shieldMax)
            assertEquals(main, f.mainMax)
        }
    }
}

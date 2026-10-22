package com.gadscape.raids.tfa.rooms.ezekiel

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.Scaling
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ezekiel's state machine against a recording host (no world): the attack cycle, the special and its cross bursts, the
 * sword volleys with stun / heal, the finale at 33.3%, the death and completion (spec §4.2, WU-21 acceptance tests).
 */
class EzekielFightTest {

    private fun config(override: String? = null): EzekielConfig =
        TfaConfigTestKit.load(listOf(EzekielConfig), overrides = override?.let { mapOf("ezekiel.json" to it) } ?: emptyMap(),
            bundled = emptyMap())[EzekielConfig]

    /** One cast of three swords that all charge 12 t, so the volley timing is fixed. */
    private val fixedCast = """{ "swordCasts": ["h_center+v_flush"] }"""

    /** Records what the fight asks for; carries his HP like the real npc. */
    private class Host(var hp: Int) : EzekielHost {
        lateinit var fight: EzekielFight
        val events = ArrayList<String>()
        var reach = true
        var follows = 0
        /** What each launched sword does. */
        var outcome: (SwordSpec) -> SwordOutcome = { SwordOutcome(hitBoss = false, empower = 0.0, impactTicks = 1, despawnTicks = 2) }
        val spawned = ArrayList<SwordSpec>()

        fun at(name: String): List<Int> = events.filter { it.startsWith("$name@") }.map { it.substringAfter('@').substringBefore(' ').toInt() }
        fun arg(name: String): List<String> = events.filter { it.startsWith("$name@") }.map { it.substringAfter(' ', "") }

        private fun rec(name: String, extra: String = "") {
            events += "$name@${fight.now}" + (if (extra.isEmpty()) "" else " $extra")
        }

        /** A processed hit on the npc: what EzekielNpc.processHit and sendDeath do. */
        fun damage(amount: Int) {
            val before = hp
            hp = (hp - amount).coerceAtLeast(0)
            if (hp == 0) fight.onBossDeath() else fight.onBossHpChanged(before, hp)
        }

        override fun tankInReach() = reach
        override fun bossHp() = hp
        override fun setBossHp(hp: Int) {
            this.hp = hp
            if (hp == 0) fight.onBossDeath()
        }
        override fun follow() {
            follows++
        }
        override fun playCleave() = rec("cleave")
        override fun resolveCleave() = rec("cleaveHit")
        override fun playLightning() = rec("lightning")
        override fun resolveLightning() = rec("lightningHit")
        override fun playSpecial(): Set<Tile> {
            rec("special")
            return Star.tiles(Tile(4062, 5534), 3, 3)
        }
        override fun starTelegraph(tiles: Set<Tile>) = rec("starSpawn", "${tiles.size}")
        override fun starMarker(tiles: Set<Tile>) = rec("starMarker")
        override fun starExplosion(tiles: Set<Tile>) = rec("starExplosion")
        override fun starBurst(tiles: Set<Tile>, index: Int) = rec("burst", "$index")
        override fun spawnSword(volley: Int, sword: SwordSpec) {
            spawned += sword
            rec("swordSpawn", "$volley/${sword.index}")
        }
        override fun launchSword(volley: Int, sword: SwordSpec): SwordOutcome {
            rec("launch", "$volley/${sword.index}")
            return outcome(sword)
        }
        override fun swordGone(volley: Int, sword: SwordSpec) = rec("swordGone", "$volley/${sword.index}")
        override fun damageBoss(damage: Int) {
            rec("bossDamage", "$damage")
            this.damage(damage)
        }
        override fun argh() = rec("argh")
        override fun playStunStart() = rec("stunIn")
        override fun playStunLoop() = rec("stunLoop")
        override fun playStunEnd() = rec("stunOut")
        override fun playHeal() = rec("heal")
        override fun healBoss(amount: Int) {
            hp += amount
            rec("healBoss", "$amount")
        }
        override fun clearSwords() = rec("clearSwords")
        override fun beginFinale() = rec("finale")
        override fun finaleSwordsDrop() = rec("finaleSwords")
        override fun finaleUpkeep(volleyActive: Boolean, castPlaying: Boolean) = rec("upkeep", "$volleyActive $castPlaying")
        override fun finaleTelegraph(attack: FinaleAttack) = rec("telegraph", attack.key)
        override fun finaleStrike(attack: FinaleAttack) = rec("strike", attack.key)
        override fun playDeath() = rec("death")
        override fun finishDeath() = rec("deathDone")
    }

    private fun newFight(c: EzekielConfig = config(fixedCast), scale: Int = 2, seed: Long = 1): Pair<EzekielFight, Host> {
        val max = Scaling.DEFAULT.hp(c.baseHp, scale)
        val host = Host(max)
        val f = EzekielFight(c, max, host, Random(seed))
        host.fight = f
        return f to host
    }

    private fun EzekielFight.run(ticks: Int) = repeat(ticks) { tick() }

    @Test
    fun meleeMeleeSpecialOnTheFiveTickClock() {
        val (f, h) = newFight()
        assertEquals(EzekielState.WAITING, f.state)
        f.start()
        assertEquals(EzekielState.CYCLE, f.state)
        f.run(16)
        assertEquals(listOf(2, 7), h.at("cleave"), "first attack 2 t after the start, then every 5 t")
        assertEquals(listOf(3, 8), h.at("cleaveHit"), "the cleave lands 1 t after its seq")
        assertEquals(listOf(12), h.at("special"), "the third attack is the special")
        f.run(1)
        assertEquals(listOf(2, 7, 17), h.at("cleave"), "the cycle restarts")
        assertTrue(h.follows > 0)
    }

    @Test
    fun anOutOfReachTankGetsLightningOnEveryone() {
        val (f, h) = newFight()
        h.reach = false
        f.start()
        f.run(8)
        assertEquals(listOf(2, 7), h.at("lightning"))
        assertEquals(listOf(4), h.at("lightningHit"), "lightning lands 2 t after the cast")
        assertTrue(h.at("cleave").isEmpty())
    }

    @Test
    fun theCrossBurstsAtT3AndT5AndSummonsSwordsAtT1() {
        val (f, h) = newFight()
        f.start()
        f.run(18)
        val t0 = h.at("special").single()
        assertEquals(listOf(t0), h.at("starSpawn"))
        assertEquals("45", h.arg("starSpawn").single(), "his 3x3 plus four 3x3 arms")
        assertEquals(listOf(t0 + 2), h.at("starMarker"))
        assertEquals(listOf(t0 + 3), h.at("starExplosion"))
        assertEquals(listOf(t0 + 3, t0 + 5), h.at("burst"))
        assertEquals(List(3) { t0 + 1 }, h.at("swordSpawn"), "three swords spawn 1 t after the special starts")
        assertTrue(f.rootedUntil >= t0 + 5)
    }

    @Test
    fun aMissedVolleyHealsFivePercent() {
        val (f, h) = newFight()
        f.start()
        f.run(30)
        val spawn = h.at("swordSpawn").first()
        assertEquals(List(3) { spawn + 12 }, h.at("launch"), "swords launch after a 12 t charge")
        assertEquals(List(3) { spawn + 14 }, h.at("swordGone"), "and are gone after the 2 t flight")
        assertEquals(listOf(spawn + 14), h.at("heal"), "no sword hit him: he heals when the last is gone")
        assertEquals(listOf(spawn + 15), h.at("healBoss"))
        assertEquals("150", h.arg("healBoss").single(), "5% of 3000")
        assertEquals(1, f.heals)
        assertEquals(0, f.stuns)
        // The heal paused the clock 3 t: the next special comes 3 t later than t27.
        assertEquals(30, h.at("special").last())
    }

    @Test
    fun aSwordOnHisLaneStunsHimEightTicksAndPreventsTheHeal() {
        val (f, h) = newFight()
        h.outcome = { s -> if (s.index == 0) SwordOutcome(true, 0.5, 1, 1) else SwordOutcome(false, 0.0, 1, 2) }
        f.start()
        f.run(40)
        val launch = h.at("launch").first()
        val hit = launch + 1
        assertEquals(listOf(hit), h.at("bossDamage"))
        assertEquals("225", h.arg("bossDamage").single(), "5% x 3000 x (1 + 0.5 empower)")
        assertEquals(3000 - 225, h.hp + 0, "no heal: HP stays down")
        assertEquals(listOf(hit), h.at("argh"))
        assertEquals(listOf(hit), h.at("stunIn"))
        assertEquals(listOf(hit + 2), h.at("stunLoop"))
        assertEquals(listOf(hit + 7), h.at("stunOut"))
        assertTrue(h.at("heal").isEmpty(), "one hit per volley cancels the heal")
        assertEquals(1, f.stuns)
        // The clock was paused for the 8 t stun: the special due at t27 comes at t35.
        assertEquals(35, h.at("special").last())
        assertTrue(h.at("cleave").none { it in hit until hit + 8 }, "no attacks while stunned")
    }

    @Test
    fun perMissingSwordHealRule() {
        val (f, h) = newFight(config("""{ "swordCasts": ["h_center+v_flush"], "healRule": "per_missing_sword" }"""))
        h.outcome = { s -> if (s.index == 0) SwordOutcome(true, 0.0, 1, 1) else SwordOutcome(false, 0.0, 1, 2) }
        f.start()
        f.run(45)
        assertEquals(1, f.stuns)
        assertEquals("300", h.arg("healBoss").single(), "two swords missed: 2 x 150")
    }

    @Test
    fun fullGridsStaggerTheirLaunches() {
        val (f, h) = newFight(config("""{ "swordCasts": ["v_full"] }"""))
        f.start()
        f.run(32)
        val spawn = h.at("swordSpawn").first()
        assertEquals(listOf(spawn + 10, spawn + 13, spawn + 16), h.at("launch").sorted())
    }

    @Test
    fun aStunDuringTheSpecialWaitsForItsEnd() {
        val (f, h) = newFight()
        f.start()
        f.run(12)
        assertEquals(listOf(12), h.at("special"))
        f.stun() // e.g. a full-grid sword from the previous volley
        assertTrue(h.at("stunIn").isEmpty())
        f.run(5)
        assertEquals(listOf(17), h.at("stunIn"), "after the 5 t special")
        assertEquals(EzekielState.STUNNED, f.state)
    }

    @Test
    fun finaleAtOneThirdThenVolleysOfThree() {
        val (f, h) = newFight()
        f.start()
        f.run(3)
        h.damage(1999) // 3000 -> 1001 (33.37%): no finale yet
        f.run(1)
        assertEquals(EzekielState.CYCLE, f.state)
        h.damage(2) // 999 <= 999.0
        assertTrue(f.finalePending)
        f.run(1)
        val t0 = h.at("finale").single()
        assertEquals(EzekielState.FINALE_TRANSITION, f.state)
        assertEquals(listOf(t0), h.at("clearSwords"))
        f.run(5)
        assertEquals(listOf(t0 + 3), h.at("finaleSwords"), "swords drop after the 3 t imbue")
        assertEquals(EzekielState.FINALE, f.state)
        f.run(40)
        val telegraphs = h.at("telegraph")
        val strikes = h.at("strike")
        assertEquals(listOf(t0 + 5, t0 + 9, t0 + 13, t0 + 20, t0 + 24, t0 + 28, t0 + 35, t0 + 39), telegraphs.take(8))
        assertEquals(telegraphs.take(6).map { it + 2 }, strikes.take(6), "each strike lands 2 t after its telegraph")
        // The attacks follow the rotation's combos.
        val schedule = f.finaleSchedule!!
        val rotation = Finale.DEFAULT_ROTATIONS[schedule.rotationIndex]
        val expected = (0 until 2).flatMap { Finale.DEFAULT_COMBOS[rotation[it] - 1] }.map { it.key }
        assertEquals(expected, h.arg("telegraph").take(6))
        // No cleave, lightning or special in the finale.
        assertTrue(h.at("cleave").none { it > t0 } && h.at("special").none { it > t0 } && h.at("lightning").none { it > t0 })
        assertTrue(h.at("upkeep").first() == t0 + 3)
    }

    @Test
    fun theFinaleCancelsPendingCrossBurstsAndSwords() {
        val (f, h) = newFight()
        f.start()
        f.run(13) // special at t12, swords at t13
        h.hp = 1000
        h.damage(1)
        f.run(10)
        val t0 = h.at("finale").single()
        assertTrue(h.at("burst").none { it > t0 }, "the bursts after the finale started were cancelled")
        assertTrue(h.at("launch").isEmpty(), "the swords were cleared")
        assertTrue(f.activeVolleys.isEmpty())
    }

    @Test
    fun deathPlaysSixTicksThenCompletesOnce() {
        val (f, h) = newFight()
        f.start()
        assertTrue(f.debugSkipTo("finale"))
        f.run(12)
        assertEquals(EzekielState.FINALE, f.state)
        h.damage(h.hp)
        val d = h.at("death").single()
        assertEquals(EzekielState.DYING, f.state)
        assertFalse(f.isComplete)
        f.run(6)
        assertEquals(listOf(d + 6), h.at("deathDone"))
        assertTrue(f.isComplete)
        assertTrue(h.at("strike").none { it > d }, "no strikes after the death")
        f.onBossDeath()
        f.run(10)
        assertEquals(1, h.at("death").size, "the death runs once")
        assertEquals(1, h.at("deathDone").size)
    }

    @Test
    fun stopCancelsEverything() {
        val (f, h) = newFight()
        f.start()
        f.run(14)
        f.stop()
        val before = h.events.size
        f.timeline.advance()
        f.timeline.advance()
        assertEquals(0, f.timeline.size)
        assertEquals(before, h.events.size)
    }

    @Test
    fun scaledHealAndThreshold() {
        val (f, _) = newFight(scale = 6)
        assertEquals(8127, f.maxHp)
        assertEquals(406, f.healAmount())
    }

    @Test
    fun debugPhases() {
        for (phase in EzekielFight.PHASES) {
            val (f, h) = newFight()
            assertTrue(f.debugSkipTo(phase), phase)
            f.run(8)
            when (phase) {
                "special" -> assertEquals(1, h.at("special").size)
                "swords" -> assertEquals(3, h.at("swordSpawn").size)
                "stun" -> assertEquals(1, h.at("stunIn").size)
                "heal" -> assertEquals(1, h.at("heal").size)
                "finale" -> assertEquals(1, h.at("finale").size)
                "dying" -> assertEquals(1, h.at("death").size)
            }
        }
        val (f, _) = newFight()
        assertFalse(f.debugSkipTo("nonsense"))
    }
}

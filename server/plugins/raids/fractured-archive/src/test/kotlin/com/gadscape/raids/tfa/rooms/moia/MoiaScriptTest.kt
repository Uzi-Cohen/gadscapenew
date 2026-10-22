package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.game.world.entity.player.status.VirusHost
import com.gadscape.game.world.entity.player.status.VirusOutbreak
import com.gadscape.game.world.entity.player.status.VirusSettings
import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.Scaling
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The attack grid, the enrage loop, the timeline and the virus rules of the Moia room. */
class MoiaScriptTest {

    private val cfg: MoiaConfig = TfaConfigTestKit.load(listOf(MoiaConfig))[MoiaConfig]

    // ------------------------------------------------------------------------------------------- attack grid

    @Test
    fun autosEveryFiveTicksWithTheSpecialOnEveryFourth() {
        val plan = MoiaAttackPlan(5, 4, 30)
        plan.start(10)
        val attacks = (10 until 50).mapNotNull { t -> plan.poll(t, canInfect = false)?.let { t to it } }
        assertEquals((10 until 50 step 5).toList(), attacks.map { it.first })
        assertEquals(listOf(25, 45), attacks.filter { it.second == MoiaAttackPlan.Attack.DAGGER_THEN_SPECIAL }.map { it.first })
        assertTrue(attacks.none { it.second == MoiaAttackPlan.Attack.INFECT })
    }

    @Test
    fun infectReplacesOneAutoEveryThirtyTicks() {
        val plan = MoiaAttackPlan(5, 4, 30)
        plan.start(0)
        val attacks = (0 until 130).mapNotNull { t -> plan.poll(t, canInfect = true)?.let { t to it } }
        val infects = attacks.filter { it.second == MoiaAttackPlan.Attack.INFECT }.map { it.first }
        // due at 30; slot 35 is the 8th auto (special), so the infect takes slot 30, then 60, 90, 120
        assertEquals(listOf(30, 60, 90, 120), infects)
        // a special slot is never turned into an infect
        val specials = attacks.filter { it.second == MoiaAttackPlan.Attack.DAGGER_THEN_SPECIAL }.map { it.first }
        assertEquals(listOf(15, 35, 55, 75, 95, 115), specials)
    }

    @Test
    fun infectWaitsForSomeoneToInfect() {
        val plan = MoiaAttackPlan(5, 4, 30)
        plan.start(0)
        var result: Pair<Int, MoiaAttackPlan.Attack>? = null
        for (t in 0 until 80) {
            val a = plan.poll(t, canInfect = t >= 50) ?: continue
            if (a == MoiaAttackPlan.Attack.INFECT) { result = t to a; break }
        }
        assertEquals(50, result?.first)
    }

    @Test
    fun forcedSpecialAndInfect() {
        val plan = MoiaAttackPlan(5, 4, 30)
        plan.start(0)
        assertEquals(MoiaAttackPlan.Attack.DAGGER, plan.poll(0, false))
        plan.forceSpecialNext(1)
        assertEquals(MoiaAttackPlan.Attack.DAGGER_THEN_SPECIAL, plan.poll(1, false))
        plan.forceInfectDue(2)
        assertEquals(MoiaAttackPlan.Attack.INFECT, plan.poll(2, true))
    }

    // ------------------------------------------------------------------------------------------------- enrage

    @Test
    fun enrageAtOneThirdOfTheScaledMax() {
        assertEquals(633, MoiaTiming.enrageMark(1900, cfg.enrageFraction))
        val scaled = Scaling.DEFAULT.hp(cfg.baseHp, 6)
        assertEquals(5147, scaled)
        assertEquals(1715, MoiaTiming.enrageMark(scaled, cfg.enrageFraction))
    }

    /** WU-23: "charge 25 t, explosion min(99, 25 × survivors); all binders dead → new set, no explosion". */
    @Test
    fun chargeLoop() {
        val enrage = MoiaEnrage(cfg.enrage)
        val end = enrage.begin(200)
        assertEquals(225, end)
        assertFalse(enrage.isDue(224))
        assertEquals(1, enrage.remaining(224))
        assertTrue(enrage.isDue(225))
        val explosions = (0..8).map { cfg.enrage.explosion(it) }
        assertEquals(listOf(0, 25, 50, 75, 99, 99, 99, 99, 99), explosions)
        val boom = enrage.resolve(3)
        assertTrue(boom.exploded)
        assertEquals(75, boom.damage)
        assertEquals(5, boom.refill)
        enrage.begin(225)
        val clean = enrage.resolve(0)
        assertFalse(clean.exploded)
        assertEquals(0, clean.damage)
        assertEquals(8, clean.refill, "all dead: a whole new set")
        assertEquals(2, enrage.completed)
    }

    // ----------------------------------------------------------------------------------------------- timeline

    @Test
    fun timelineRunsInOrderAndCancelsByTag() {
        val tl = Timeline()
        val log = ArrayList<String>()
        tl.schedule(2, Timeline.Tag.PHASE1) { log += "special@${tl.now}" }
        tl.schedule(2, Timeline.Tag.HIT) { log += "hit@${tl.now}" }
        tl.schedule(1, Timeline.Tag.OTHER) {
            log += "a@${tl.now}"
            tl.schedule(0, Timeline.Tag.OTHER) { log += "same-tick@${tl.now}" }
        }
        tl.advance()
        tl.cancel(Timeline.Tag.PHASE1)
        tl.advance()
        tl.advance()
        assertEquals(listOf("a@1", "same-tick@1", "hit@2"), log)
        var ran = false
        tl.after(0, Timeline.Tag.HIT) { ran = true }
        assertTrue(ran)
        tl.schedule(5, Timeline.Tag.DEATH) { log += "never" }
        tl.cancelAll()
        repeat(10) { tl.advance() }
        assertEquals(0, tl.pending())
        assertFalse("never" in log)
    }

    // -------------------------------------------------------------------------------------------------- virus

    private class Body(var x: Int, var y: Int, val npc: Boolean = false) {
        var hp = 99
        var coughs = 0
    }

    private object Host : VirusHost<Body> {
        override fun x(carrier: Body) = carrier.x
        override fun y(carrier: Body) = carrier.y
        override fun plane(carrier: Body) = 0
        override fun isGone(carrier: Body) = carrier.hp <= 0
        override fun coughDamage(carrier: Body, settings: VirusSettings, random: Random): Int =
            if (carrier.npc) settings.npcDamage else settings.damageMin + random.nextInt(settings.damageMax - settings.damageMin + 1)
        override fun applyCough(carrier: Body, damage: Int, settings: VirusSettings) {
            carrier.coughs++
            carrier.hp -= damage
        }
    }

    /** WU-23: "virus spreads to an adjacent player within 1 t" (and to binders, who take 20 a cough). */
    @Test
    fun virusSpreadsToAnAdjacentPlayerWithinOneTick() {
        val settings = Bridge.virusSettings(cfg.infect)
        assertEquals(VirusSettings(20, 4, 4, 8, 0.25, 20, "*Cough*"), settings)
        val outbreak = VirusOutbreak(settings, Random(3), Host)
        val carrier = Body(4063, 5663)
        val neighbour = Body(4064, 5664)
        val far = Body(4065, 5663)
        val binder = Body(4062, 5662, npc = true)
        val everyone = listOf(carrier, neighbour, far, binder)
        outbreak.infect(carrier)
        outbreak.tick(everyone)
        assertTrue(outbreak.isInfected(neighbour), "adjacent player infected within 1 tick")
        assertTrue(outbreak.isInfected(binder), "adjacent binder infected")
        assertFalse(outbreak.isInfected(far), "two tiles away is safe this tick")
        // coughs every 4 ticks for 4-8 (players) / 20 (binders); a carrier is cured after 20 ticks without contact
        far.x = 4080
        neighbour.x = 4090; binder.x = 4100; binder.y = 5600
        repeat(4) { outbreak.tick(everyone) } // ticks 2-5: the carrier coughs on tick 4, the binder on tick 5
        assertEquals(1, carrier.coughs)
        assertTrue(99 - carrier.hp in 4..8)
        assertEquals(79, binder.hp)
        repeat(30) { outbreak.tick(everyone) }
        assertFalse(outbreak.isInfected(carrier))
        // serpentine helm: × 0.25
        assertEquals(2, VirusOutbreak.playerCoughDamage(8, true, settings))
        assertEquals(8, VirusOutbreak.playerCoughDamage(8, false, settings))
    }
}

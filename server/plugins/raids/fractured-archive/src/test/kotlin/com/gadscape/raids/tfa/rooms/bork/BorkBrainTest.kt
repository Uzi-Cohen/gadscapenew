package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.rooms.bork.BorkSmorksBrain.BorkState
import com.gadscape.raids.tfa.rooms.bork.BorkSmorksBrain.Special
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WU-22 acceptance (spec §6.4): tempo 7 / 6 / 4 per NPC; a heavy ball on Bork dazes him for 2 × tempo, sets flat armour
 * 0 and skips melee accuracy; 4 stomps after the daze, the cart shadow protects; oil deals 6-7 + burn per tick; Bork
 * dead → the Smorks at speed 3 on everyone; the Smorks dead → no shadow anywhere. Bork's spawn SW is (4194,5597), so
 * his middle tile is (4196,5599) and the tile (4186,5599) straight behind the cart is in its shadow.
 */
class BorkBrainTest {

    private val gongNever: (BorkSmorksConfig) -> BorkSmorksConfig = { it.copy(bork = it.bork.copy(gongChance = 0.0)) }
    private val gongAlways: (BorkSmorksConfig) -> BorkSmorksConfig = { it.copy(bork = it.bork.copy(gongChance = 1.0)) }

    private fun Harness.ticksOf(kind: String, who: Fighter? = null, text: String? = null) =
        host.of(kind).filter { (who == null || it.target === who) && (text == null || it.text == text) }.map { it.tick }

    private fun Harness.animTicks(seq: Int) = host.of("borkAnim").filter { it.a == seq }.map { it.tick }

    // -------------------------------------------------------------------------------------------------- beats

    @Test
    fun beatsFollowBorksOwnTempo() {
        val h = Harness()
        val tank = h.fighter("tank", 4193, 5598)
        h.brain.start(tank)
        h.run(4)
        assertEquals(listOf(4), h.animTicks(BorkIds.SEQ_BEAT))
        assertEquals(BorkIds.SPOT_BEAT, h.host.of("borkSpot").single().a)
        // the clock is set from his HP at each swing: 7 at 4, then 60% → 6, then 26.7% → 4
        h.host.bork = 900
        h.run(7)
        assertEquals(listOf(4, 11), h.animTicks(BorkIds.SEQ_BEAT))
        h.run(5)
        h.host.bork = 400
        h.run(1)
        assertEquals(listOf(4, 11, 17), h.animTicks(BorkIds.SEQ_BEAT))
        h.run(8)
        assertEquals(listOf(4, 11, 17, 21, 25), h.animTicks(BorkIds.SEQ_BEAT))
        // every beat lands 2 t after the swing on the tank in the 6x6: crush max 34 with the accuracy roll
        val hits = h.host.of("borkHit").filter { it.target === tank }
        assertEquals(listOf(6, 13, 19, 23), hits.map { it.tick })
        assertTrue(hits.all { it.a == 34 && it.b == 1 && it.text == "BEAT" })
    }

    @Test
    fun smorksKeepTheirOwnTempo() {
        val h = Harness()
        val tank = h.fighter("tank", 4193, 5598)
        h.fighter("p2", 4186, 5605)
        h.brain.start(tank)
        h.host.bork = 300 // Bork's HP does not speed the Smorks up (independent per NPC [COMM])
        h.run(20)
        assertEquals(listOf(7, 14), h.host.of("smorksAnim").map { it.tick })
        h.host.smorksHpNow = 300 // 50%: the attack at 21 sets the clock to 6
        h.run(12)
        assertEquals(listOf(7, 14, 21, 27), h.host.of("smorksAnim").map { it.tick })
        h.host.smorksHpNow = 100 // 16.7%: the attack at 33 sets it to 4
        h.run(9)
        assertEquals(listOf(7, 14, 21, 27, 33, 37, 41), h.host.of("smorksAnim").map { it.tick })
        assertEquals(4, h.brain.borkSpeed())
        assertEquals(4, h.brain.smorksSpeed())
    }

    @Test
    fun largeBeatWhenTheTargetIsOutOfReach() {
        val h = Harness()
        val tank = h.fighter("tank", 4186, 5604)      // 8 tiles from his 4x4
        val ring5 = h.fighter("ring5", 4189, 5605)    // exactly 5, in the open
        val ring6 = h.fighter("ring6", 4188, 5606)    // 6: outside the large beat
        val hidden = h.fighter("hidden", 4189, 5599)  // 5, but behind the cart
        h.brain.start(tank)
        h.run(6)
        assertEquals(1, h.brain.largeBeats)
        assertEquals(listOf(6), h.ticksOf("borkHit", ring5))
        assertTrue(h.ticksOf("borkHit", ring6).isEmpty())
        assertTrue(h.ticksOf("borkHit", hidden).isEmpty())
        assertTrue(h.ticksOf("borkHit", tank).isEmpty())
        // long dust over the hit tiles, delayed by ring
        val dust = h.host.of("graphic").filter { it.tick == 6 }
        assertTrue(dust.isNotEmpty())
        assertTrue(dust.all { it.a in 4394..4405 && it.b in 4..20 })
        // the second attack is small (the first was large), the third large again
        h.run(14)
        assertEquals(listOf(4, 11, 18), h.animTicks(BorkIds.SEQ_BEAT))
        assertEquals(2, h.brain.largeBeats)
        assertEquals(listOf(6, 20), h.ticksOf("borkHit", ring5))
    }

    @Test
    fun pendingBeatIsAreaLockedAtTheSwing() {
        val h = Harness()
        val tank = h.fighter("tank", 4193, 5598)
        h.brain.start(tank)
        h.run(5) // swing at 4
        tank.tile = StaticTile(4186, 5605, 0) // runs out before the impact at 6
        h.run(1)
        assertTrue(h.ticksOf("borkHit", tank).isEmpty())
    }

    // ------------------------------------------------------------------------------------- daze and stomps

    @Test
    fun heavyBallOnBorkDazesForTwiceHisTempo() {
        for ((hp, ticks, seq) in listOf(
            Triple(1500, 14, BorkIds.SEQ_DAZE_14), Triple(750, 12, BorkIds.SEQ_DAZE_12), Triple(300, 8, BorkIds.SEQ_DAZE_8),
        )) {
            val h = Harness(gongNever)
            h.host.bork = hp
            val tank = h.fighter("tank", 4193, 5598)
            h.brain.start(tank)
            h.brain.fireSpecial(Special.HEAVY, tank)
            assertEquals(BorkIds.SPOT_HEAVY_SHADOW, h.host.of("graphic").single().a)
            assertEquals(4, h.host.of("lob").single().b)
            h.run(4)
            assertEquals(BorkState.DAZED, h.brain.borkState)
            assertEquals(listOf(4), h.animTicks(seq))
            assertEquals(1, h.host.of("dazed").single().a)
            assertTrue(h.brain.isDazed)
            assertEquals(0, h.brain.borkFlatArmour())
            assertEquals(0, h.brain.borkMagicResist())
            assertEquals(listOf(35), h.host.of("damage").filter { it.target === tank }.map { it.a })
            assertTrue(h.host.of("borkSpotCentre").any { it.a == BorkIds.SPOT_STUN_STARS })
            h.run(ticks - 1)
            assertTrue(h.brain.isDazed, "still dazed at ${h.timeline.now}")
            h.run(1)
            assertFalse(h.brain.isDazed)
            assertEquals(BorkState.STOMP, h.brain.borkState)
            assertEquals(listOf(4 + ticks), h.animTicks(BorkIds.SEQ_STOMP_IN_FROM_DAZE))
        }
    }

    /** The traits Bork hands the engine (E8): 12 / 2, 10 at or below 66%, 0 / 0 + guaranteed melee while dazed. */
    @Test
    fun borkArmourFollowsHpAndDaze() {
        val h = Harness()
        val tank = h.fighter("tank", 4193, 5598)
        h.brain.start(tank)
        assertEquals(12, h.brain.borkFlatArmour())
        h.host.bork = 1000
        assertEquals(12, h.brain.borkFlatArmour())
        h.host.bork = 990
        assertEquals(10, h.brain.borkFlatArmour())
        assertEquals(2, h.brain.borkMagicResist())
        h.brain.daze()
        assertEquals(0, h.brain.borkFlatArmour())
        assertEquals(0, h.brain.borkMagicResist())
        assertTrue(h.brain.isDazed)
    }

    @Test
    fun fourStompsAfterTheDazeAndTheShadowProtects() {
        val h = Harness(gongNever)
        val tank = h.fighter("tank", 4193, 5598)
        val open = h.fighter("open", 4186, 5605)
        val hidden = h.fighter("hidden", 4186, 5599)
        h.brain.start(tank)
        h.brain.fireSpecial(Special.HEAVY, tank)
        h.run(4 + 14) // dazed at 4 for 14 t
        assertEquals(listOf("Bork very angry!"), h.host.of("borkSay").map { it.text })
        h.run(15)
        assertEquals(listOf(18), h.animTicks(BorkIds.SEQ_STOMP_IN_FROM_DAZE))
        assertEquals(listOf(20), h.animTicks(BorkIds.SEQ_STOMP))
        assertEquals(listOf(26), h.animTicks(BorkIds.SEQ_STOMP_CYCLE_OUT))
        assertEquals(listOf(20, 26), h.host.of("borkSpot").filter { it.a == BorkIds.SPOT_STOMP }.map { it.tick })
        assertEquals(listOf(21, 24, 27, 30), h.ticksOf("borkHit", tank, "STOMP"))
        assertEquals(listOf(21, 24, 27, 30), h.ticksOf("borkHit", open, "STOMP"))
        assertTrue(h.ticksOf("borkHit", hidden).isEmpty())
        assertTrue(h.host.of("borkHit").filter { it.text == "STOMP" }.all { it.a == 40 })
        assertEquals(4, h.brain.stomps)
        // the sequence ends at 18 + 2 + 6 + 7 = 33 with a new target, never the old tank while others live
        assertEquals(BorkState.CHASE, h.brain.borkState)
        val said = h.host.of("borkSay").last()
        assertEquals(33, said.tick)
        assertTrue(said.text == "Bork smash open now." || said.text == "Bork smash hidden now.", said.text)
        assertTrue(h.brain.target !== tank)
        assertEquals(h.brain.target, h.host.primary)
    }

    @Test
    fun gongBarrageAfterTheDaze() {
        val h = Harness(gongAlways)
        val tank = h.fighter("tank", 4193, 5598)
        val hidden = h.fighter("hidden", 4186, 5599)
        h.brain.start(tank)
        h.brain.fireSpecial(Special.HEAVY, tank)
        h.run(18 + 11)
        assertEquals(listOf(18), h.animTicks(BorkIds.SEQ_GONG_IN_FROM_DAZE))
        assertEquals(listOf(19), h.animTicks(BorkIds.SEQ_GONG))
        assertEquals(listOf(27), h.animTicks(BorkIds.SEQ_GONG_OUT))
        assertEquals("Bork crush now.", h.host.of("borkSay").first().text)
        assertEquals((20..27).toList(), h.ticksOf("borkHit", tank, "GONG"))
        assertTrue(h.host.of("borkHit").filter { it.text == "GONG" }.all { it.a == 12 })
        assertTrue(h.ticksOf("borkHit", hidden).isEmpty())
        assertEquals(BorkState.CHASE, h.brain.borkState)
        assertEquals(28, h.host.of("borkSay").last().tick)
    }

    @Test
    fun missedHeavyBallStartsStompsFromIdle() {
        val h = Harness(gongNever)
        val tank = h.fighter("tank", 4193, 5598)
        val far = h.fighter("far", 4186, 5605)
        h.brain.start(tank)
        h.brain.fireSpecial(Special.HEAVY, far)
        h.run(4)
        assertEquals(listOf(35), h.host.of("damage").filter { it.target === far }.map { it.a })
        assertFalse(h.brain.isDazed)
        assertEquals(BorkState.STOMP, h.brain.borkState)
        assertEquals("Bork think it stomp time now!", h.host.of("borkSay").single().text)
        h.run(14)
        assertEquals(listOf(4), h.animTicks(BorkIds.SEQ_STOMP_IN_FROM_IDLE))
        assertEquals(listOf(5), h.animTicks(BorkIds.SEQ_STOMP))
        assertEquals(listOf(11), h.animTicks(BorkIds.SEQ_STOMP_CYCLE_OUT))
        assertEquals(listOf(6, 9, 12, 15), h.ticksOf("borkHit", tank, "STOMP"))
        assertEquals(BorkState.CHASE, h.brain.borkState)
        assertEquals(18, h.host.of("borkSay").last().tick)
    }

    @Test
    fun missedHeavyBallCanStartTheGong() {
        val h = Harness(gongAlways)
        val tank = h.fighter("tank", 4193, 5598)
        val far = h.fighter("far", 4186, 5605)
        h.brain.start(tank)
        h.brain.fireSpecial(Special.HEAVY, far)
        h.run(14)
        assertEquals(listOf(4), h.animTicks(BorkIds.SEQ_GONG_IN_FROM_IDLE))
        assertEquals((6..13).toList(), h.ticksOf("borkHit", tank, "GONG"))
    }

    // ---------------------------------------------------------------------------------------- the Smorks

    @Test
    fun smorksShootEveryoneButBorksTarget() {
        val h = Harness()
        val tank = h.fighter("tank", 4193, 5598)
        val p2 = h.fighter("p2", 4186, 5605)
        val p3 = h.fighter("p3", 4184, 5599)
        h.brain.start(tank)
        h.run(7)
        val shots = h.host.of("projectile")
        assertEquals(setOf<Fighter>(p2, p3), shots.map { it.target!! }.toSet())
        assertTrue(shots.all { it.a == BorkIds.SPOT_BONE_PROJ || it.a == BorkIds.SPOT_POTION_PROJ })
        assertEquals(1, shots.map { it.a }.toSet().size) // one style per volley
        assertEquals(BorkIds.SPOT_VOLLEY, h.host.of("smorksSpot").single().a)
        // p3 is 7 tiles from the cart's centre: 30 + 5 × 7 = 65 cycles → lands 3 t later, prayer checked then
        h.run(3)
        assertEquals(listOf(10), h.ticksOf("volleyHit", p3))
        assertTrue(h.host.of("volleyHit").all { it.a == 28 })
    }

    @Test
    fun soloPlayerIsShotToo() {
        val h = Harness()
        val tank = h.fighter("tank", 4193, 5598)
        h.brain.start(tank)
        h.run(7)
        assertEquals(listOf<Fighter>(tank), h.host.of("projectile").map { it.target!! })
    }

    @Test
    fun specialsEveryFourthAttackIncendiaryFirst() {
        val h = Harness(gongNever)
        h.fighter("tank", 4193, 5598)
        h.fighter("p2", 4186, 5605)
        h.brain.start(h.host.fighters[0])
        h.run(56)
        val anims = h.host.of("smorksAnim").map { it.a }
        val v = BorkIds.SEQ_VOLLEY
        val c = BorkIds.SEQ_BIG_CANNON
        assertEquals(listOf(v, v, v, c, v, v, v, c), anims)
        assertEquals(listOf("Burny gloop!", "Big bomb. Bork watch out!"), h.host.of("smorksSay").map { it.text })
        assertEquals(listOf(28, 56), h.host.of("smorksSpotCentre").filter { it.a == BorkIds.SPOT_BIG_CANNON_FLASH }.map { it.tick })
    }

    @Test
    fun incendiaryHurtsBorkAndLeavesBurningOil() {
        val h = Harness({ it.copy(smorks = it.smorks.copy(specialEvery = 100)) }) // no other cannonball meanwhile
        val tank = h.fighter("tank", 4193, 5598)
        val walker = h.fighter("walker", 4186, 5605)
        h.brain.start(tank)
        h.brain.fireSpecial(Special.INCENDIARY, tank)
        assertEquals(BorkIds.SPOT_INCENDIARY_MARKER, h.host.of("graphic").single().a)
        assertEquals(4, h.host.of("graphic").single().b) // 60 - 56 cycles: the marker ends as the ball lands
        assertEquals(2, h.host.of("lob").single().b)
        h.run(2)
        assertTrue(h.host.of("graphic").any { it.tick == 2 && it.a == BorkIds.SPOT_INCENDIARY_IMPACT })
        assertEquals(listOf(30), h.host.of("damage").filter { it.target === tank }.map { it.a })
        assertEquals(75, h.host.of("borkDamage").single().a) // 5% of 1500
        val oiled = h.host.of("oilSpawn").map { it.tile!! }
        assertEquals(7, oiled.size)
        assertEquals(7, oiled.toSet().size)
        assertTrue(oiled.all { BorkArena.isWalkable(it) && BorkArena.inSquare(it, StaticTile(4193, 5598, 0), 5) })
        // standing on oil: 6-7 typeless and a burn stack every tick from the next one
        walker.tile = oiled.first()
        h.host.clear()
        h.run(3)
        val burns = h.host.of("damage").filter { it.target === walker }
        assertEquals(3, burns.size)
        assertTrue(burns.all { it.a in 6..7 })
        assertEquals(3, h.host.of("burn").count { it.target === walker && it.a == 1 })
        // gone after 30 t: fade (despawn spot + loc anim) then removal
        h.run(30)
        assertEquals(7, h.host.of("oilFade").size)
        assertEquals(7, h.host.of("oilRemove").size)
    }

    // ------------------------------------------------------------------------------------------- enrage

    @Test
    fun borkDeadEnragesTheSmorks() {
        val h = Harness()
        val tank = h.fighter("tank", 4193, 5598)
        val p2 = h.fighter("p2", 4186, 5605)
        val p3 = h.fighter("p3", 4184, 5599)
        h.brain.start(tank)
        h.brain.onBorkDeath()
        assertEquals(listOf(BorkIds.SEQ_DEATH), h.host.of("borkAnim").map { it.a })
        assertEquals(BorkIds.SPOT_DEATH, h.host.of("borkSpot").single().a)
        assertEquals(0, h.host.of("borkTargetable").single().a)
        assertTrue(h.brain.smorksEnraged)
        h.run(24)
        assertEquals((3..24 step 3).toList(), h.host.of("smorksAnim").map { it.tick })
        assertEquals(listOf(7), h.host.of("borkDespawn").map { it.tick })
        // every player, the old tank included
        val firstVolley = h.host.of("projectile").filter { it.tick == 3 }.map { it.target!! }.toSet()
        assertEquals(setOf<Fighter>(tank, p2, p3), firstVolley)
        assertEquals(3, h.brain.smorksSpeed())
        // specials are incendiary only, autos hit up to 35
        assertEquals(listOf("Burny gloop!", "Burny gloop!"), h.host.of("smorksSay").map { it.text })
        assertTrue(h.host.of("volleyHit").all { it.a == 35 })
    }

    @Test
    fun smorksDeadMeansNoShadowAndContinuousStomps() {
        val h = Harness(gongNever)
        val tank = h.fighter("tank", 4193, 5598)
        val hidden = h.fighter("hidden", 4186, 5599)
        h.brain.start(tank)
        h.brain.onSmorksDeath()
        assertEquals(BorkIds.SPOT_SMORKS_DEATH, h.host.of("smorksSpotCentre").map { it.a })
        assertTrue(h.brain.borkEnraged)
        h.run(6)
        assertEquals(listOf(6), h.host.of("smorksDespawn").map { it.tick })
        assertEquals(listOf(4), h.animTicks(BorkIds.SEQ_BEAT))
        h.run(26)
        // two large beats (4, 11), then the stomp sequence from idle at 18: stomps at 20 / 23 / 26 / 29
        assertEquals(listOf(4, 11), h.animTicks(BorkIds.SEQ_BEAT))
        assertEquals(2, h.brain.largeBeats)
        assertEquals(listOf(18), h.animTicks(BorkIds.SEQ_STOMP_IN_FROM_IDLE))
        assertEquals(listOf(20, 23, 26, 29), h.ticksOf("borkHit", hidden, "STOMP")) // no shadow any more
        assertEquals(listOf(20, 23, 26, 29), h.ticksOf("borkHit", tank, "STOMP"))
        assertTrue(h.host.of("smorksAnim").isEmpty())
    }

    // -------------------------------------------------------------------------------- deaths and targets

    @Test
    fun bothDeadAfterTheirDeathSequences() {
        val h = Harness()
        h.brain.start(h.fighter("tank", 4193, 5598))
        h.brain.onSmorksDeath()
        h.run(3)
        h.brain.onBorkDeath()
        assertFalse(h.brain.bothDone)
        h.run(6)
        assertFalse(h.brain.bothDone)
        h.run(1)
        assertTrue(h.brain.bothDone) // Bork despawns 7 t after his death at 3
        assertEquals(BorkState.DEAD, h.brain.borkState)
        val immediate = Harness({ it.copy(completeAfterDeathAnims = false) })
        immediate.brain.start(immediate.fighter("tank", 4193, 5598))
        immediate.brain.onBorkDeath()
        immediate.brain.onSmorksDeath()
        assertTrue(immediate.brain.bothDone)
    }

    @Test
    fun deathCancelsBorksPendingHits() {
        val h = Harness(gongNever)
        val tank = h.fighter("tank", 4193, 5598)
        h.brain.start(tank)
        h.run(5) // beat swung at 4, lands at 6
        h.brain.onBorkDeath()
        h.run(3)
        assertTrue(h.ticksOf("borkHit", tank).isEmpty())
        assertEquals(0, h.timeline.pending(Timeline.Tag.BORK))
    }

    @Test
    fun deadTankIsReplacedWithAnOverhead() {
        val h = Harness()
        val tank = h.fighter("tank", 4193, 5598)
        val p2 = h.fighter("p2", 4186, 5605)
        h.brain.start(tank)
        assertEquals(tank, h.host.primary)
        h.run(2)
        tank.alive = false
        h.brain.onFighterLost(tank)
        h.run(1)
        assertEquals(p2, h.brain.target)
        assertEquals("Bork smash p2 now.", h.host.of("borkSay").single().text)
        assertEquals(p2, h.host.primary)
    }

    @Test
    fun nobodyLeftMeansNoAttacks() {
        val h = Harness()
        val tank = h.fighter("tank", 4193, 5598)
        h.brain.start(tank)
        tank.alive = false
        h.run(20)
        assertTrue(h.animTicks(BorkIds.SEQ_BEAT).isEmpty())
        assertTrue(h.host.of("smorksAnim").isEmpty())
    }

    @Test
    fun sameSeedSameFight() {
        fun play(): List<Ev> {
            val h = Harness(seed = 42L)
            h.host.chaseMoves = true
            val tank = h.fighter("tank", 4186, 5604)
            h.fighter("p2", 4185, 5594)
            h.fighter("p3", 4197, 5606)
            h.brain.start(tank)
            h.run(120)
            return h.host.events.toList()
        }
        val a = play()
        assertEquals(a.size, play().size)
        assertEquals(a.map { "${it.tick} ${it.kind} ${it.a} ${it.b} ${it.text} ${it.tile}" }, play().map { "${it.tick} ${it.kind} ${it.a} ${it.b} ${it.text} ${it.tile}" })
        assertNotNull(a.firstOrNull { it.kind == "chase" })
    }
}

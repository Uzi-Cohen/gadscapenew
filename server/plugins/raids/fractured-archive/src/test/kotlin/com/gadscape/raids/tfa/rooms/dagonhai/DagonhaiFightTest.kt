package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fight script against a recording host (spec §4.6 state machine and attack table; WU-25 acceptance tests:
 * shared pool, matching-element interrupts, 4 channels → empowered, tome interception, breaks, explosion, the last
 * two kills, completion exactly once).
 */
class DagonhaiFightTest {

    private val noChannels = """{"channel": {"first": 5000, "afterProtect": 5000}}"""

    /** No channels and no natural tome throws (only the forced ones). */
    private val quiet = """{"channel": {"first": 5000, "afterProtect": 5000}, "monk": {"searchTicks": [500, 500]}}"""

    // ============================================================================================ intro, Elder

    @Test
    fun introLastsThreeTicksThenTheElderAttacksEverySix() {
        val f = FightFixture(overrides = noChannels)
        f.player(4060, 5600)
        f.fight.start()
        assertEquals(DagonhaiFight.Phase.INTRO, f.fight.phase)
        assertEquals(640, f.host.pool)
        assertEquals(listOf(1000, 1000, 0, 4), f.host.hud.toList())
        // the monks scatter to their first desks (NW, SW)
        assertTrue(f.host.has("walk STAFF ${f.cfg.monk.stands[Compass.NW]}"))
        assertTrue(f.host.has("walk BOOK ${f.cfg.monk.stands[Compass.SW]}"))
        f.run(2)
        assertEquals(DagonhaiFight.Phase.INTRO, f.fight.phase)
        f.tick()
        assertEquals(DagonhaiFight.Phase.SHIELDED, f.fight.phase)
        val attackTicks = ArrayList<Int>()
        repeat(30) {
            val before = f.host.log.size
            f.tick()
            val new = f.host.log.subList(before, f.host.log.size)
            if (new.any { it == "anim ELDER ${DagonhaiIds.SEQ_ELDER_RANGED}" || it == "anim ELDER ${DagonhaiIds.SEQ_ELDER_MAGIC}" }) attackTicks += f.timeline.now
        }
        assertEquals(listOf(5, 11, 17, 23, 29), attackTicks.take(5))
    }

    @Test
    fun elderAttacksUseTheCachedIdsAndSplashOnNeighbours() {
        val f = FightFixture(seed = 11, overrides = noChannels)
        val a = f.player(4060, 5600, "a")
        val b = f.player(4061, 5601, "b") // within 1 tile of a
        f.fight.start()
        f.run(60)
        val anims = f.host.log.filter { it.startsWith("anim ELDER") }
        assertTrue(anims.isNotEmpty())
        assertTrue(anims.all { it == "anim ELDER 14593" || it == "anim ELDER 14597" }, anims.toString())
        assertTrue(anims.contains("anim ELDER 14593") && anims.contains("anim ELDER 14597"), "both styles in 60 t")
        val ranged = f.host.log.count { it == "proj ELDER 4071 fighter" }
        val surges = f.host.log.count { it.startsWith("proj ELDER 14") && it.endsWith("fighter") }
        assertEquals(anims.size, ranged + surges)
        assertTrue(f.host.log.filter { it.startsWith("gfx ELDER") }.all { it.removePrefix("gfx ELDER ").toInt() in listOf(4070, 4103, 4104, 4105, 4106) })
        // every landed auto hits the focus and the neighbour (independent rolls)
        val elderAttacks = f.host.attacks.filter { it.first == Role.ELDER }
        assertEquals(elderAttacks.count { it.second === a }, elderAttacks.count { it.second === b })
        assertTrue(elderAttacks.size >= 2 * 8)
    }

    @Test
    fun elderFocusIsKeptForFourAttacks() {
        val f = FightFixture(seed = 5, overrides = noChannels)
        f.player(4058, 5600, "a")
        f.player(4070, 5605, "b")
        f.fight.start()
        f.run(5) // first attack at 5
        val first = f.fight.focus
        assertNotNull(first)
        f.run(18) // attacks at 11, 17, 23
        assertTrue(first === f.fight.focus)
    }

    @Test
    fun surgeHitsLandOnImpactNotAtCast() {
        val spec = FightFixture().cfg.projectiles.surge
        // distance 8: 1 + (1 + 8) / 3 = 4 t; flight 51 - 5 + 80 = 126 c -> 5 t: the hit waits for the projectile
        assertEquals(5, spec.landingTicks(8))
        assertEquals(2, spec.landingTicks(1))
        val ranged = FightFixture().cfg.projectiles.elderRanged
        assertEquals(3, ranged.landingTicks(5)) // 1 + (3 + 5) / 6 = 2, but the arrow flies 41 + 25 = 66 c -> 3 t
    }

    // ============================================================================================ shared pool

    @Test
    fun theSharedPoolSyncsAndBreaksIntoTheProtection() {
        val f = FightFixture(overrides = noChannels)
        val p = f.player(4072, 5601)
        f.fight.start()
        f.run(3)
        f.fight.onPoolHit(500)
        assertEquals(500, f.host.pool) // the room sets all three NPCs to it
        f.depletePool()
        assertTrue(f.fight.breakPending)
        assertFalse(f.fight.poolOpen())
        f.fight.onPoolHit(400) // a hit in the same tick does nothing
        assertEquals(0, f.fight.pool)
        f.tick()
        assertEquals(DagonhaiFight.Phase.PROTECTED, f.fight.phase)
        assertEquals(1, f.fight.breaks)
        assertEquals(800, f.host.hud[0])
        assertEquals(ShieldState.PROTECTED, f.host.shield)
        assertEquals(0, f.host.pool)
        assertTrue(f.host.has("cauldronGfx ${DagonhaiIds.SPOT_CAULDRON_SIZZLE}"))
        assertEquals(3, f.host.riftTiles.size)
        assertTrue(f.host.riftTiles.all { DagonhaiArena.chebyshev(it, p.tile) >= 3 })
        assertEquals(6, f.fight.impsTotal)
        f.tick()
        assertTrue(f.host.has("cauldronGfx ${DagonhaiIds.SPOT_CAULDRON_DOME}"))
        assertEquals(3, f.host.impsSpawned) // first imp of each rift
        f.run(2)
        assertEquals(6, f.host.impsSpawned) // second imps 2 t later
        assertTrue(f.host.impTargets.all { it === p })
        f.tick()
        assertTrue(f.host.openRifts.isEmpty()) // rifts close after their imps
        f.killImps(p)
        f.tick()
        assertEquals(DagonhaiFight.Phase.SHIELDED, f.fight.phase)
        assertEquals(640, f.host.pool)
        assertEquals(ShieldState.SHIELDED, f.host.shield)
    }

    @Test
    fun theProtectionEndsAfterThirtyTicksEvenIfImpsLive() {
        val f = FightFixture(overrides = noChannels)
        f.player(4072, 5601)
        f.fight.start()
        f.run(3)
        f.depletePool()
        f.tick()
        val start = f.timeline.now
        f.runUntil { f.fight.phase == DagonhaiFight.Phase.SHIELDED }
        assertEquals(start + 30, f.timeline.now)
        assertEquals(6, f.host.impsAlive) // they keep fighting
        // the dome was re-sent every 6 t while it lasted
        assertEquals(5, f.host.count("cauldronGfx ${DagonhaiIds.SPOT_CAULDRON_DOME}"))
    }

    @Test
    fun anImpKillGivesDemonicStrengthForOneHit() {
        val f = FightFixture(overrides = noChannels)
        val p = f.player(4072, 5601)
        f.fight.start()
        f.run(3)
        f.depletePool()
        f.run(4)
        f.fight.onImpDeath(p)
        assertTrue(f.host.has("pgfx ${DagonhaiIds.SPOT_DEMON_BUFF}"))
        assertTrue(f.fight.hasStrength(p))
        f.run(4)
        assertTrue(f.host.has("pgfx ${DagonhaiIds.SPOT_DEMONIC_GLOW}"))
        assertEquals(2.0, f.fight.consumeStrength(p))
        assertEquals(1.0, f.fight.consumeStrength(p))
        f.fight.onImpDeath(p)
        f.run(51)
        assertFalse(f.fight.hasStrength(p))
        assertEquals(1.0, f.fight.consumeStrength(p))
    }

    // ================================================================================================ breaks

    private fun breakAndRecover(f: FightFixture) {
        f.depletePool()
        f.tick()
        if (f.fight.phase == DagonhaiFight.Phase.PROTECTED) {
            f.run(4)
            f.killImps()
            f.tick()
            assertEquals(DagonhaiFight.Phase.SHIELDED, f.fight.phase)
        }
    }

    @Test
    fun fiveBreaksExplodeTheCauldronAndTheLastTwoKillsComplete() {
        val f = FightFixture(overrides = noChannels)
        f.player(4072, 5601)
        f.fight.start()
        f.run(3)
        repeat(4) { breakAndRecover(f) }
        assertEquals(4, f.fight.breaks)
        assertEquals(200, f.host.hud[0])
        assertEquals(24, f.host.impsSpawned)
        f.depletePool()
        f.tick()
        val atExplosion = f.host.log.size
        assertEquals(DagonhaiFight.Phase.FINALE, f.fight.phase)
        assertEquals(0, f.host.hud[0])
        // the explosion seq plays only after the model swap (14679 is on 62612's rig)
        val model = f.host.log.indexOf("cauldronModel")
        val seq = f.host.log.indexOf("cauldronAnim ${DagonhaiIds.SEQ_CAULDRON_EXPLODE}")
        assertTrue(model in 0 until seq)
        assertTrue(f.host.has("cauldronGfx ${DagonhaiIds.SPOT_CAULDRON_EXPLODE}"))
        assertTrue(f.host.has("map ${DagonhaiIds.SPOT_CAULDRON_DUST} ${DagonhaiArena.CAULDRON_CENTRE}"))
        // the book monk dies in the blast; the others become 7-HP npcs
        assertEquals(listOf(Role.BOOK), f.host.killed)
        assertTrue(f.host.has("anim BOOK 14654"))
        assertTrue(f.host.has("gfx BOOK 4163"))
        assertEquals(mapOf(Role.ELDER to 7, Role.STAFF to 7), f.host.unshielded)
        assertEquals(ShieldState.UNSHIELDED, f.host.shield)
        f.run(2)
        assertTrue(f.host.cauldronGone)
        f.run(3)
        assertEquals(listOf(Role.BOOK), f.host.despawned)
        // the Elder recovers after 3 t and attacks again; nothing completes yet
        assertTrue(f.host.log.drop(atExplosion).any { it == "anim ELDER 14593" || it == "anim ELDER 14597" })
        assertFalse(f.fight.complete)
        f.fight.onFinalDeath(Role.ELDER)
        assertTrue(f.host.has("anim ELDER 14652"))
        f.run(5)
        assertFalse(f.fight.complete)
        f.fight.onFinalDeath(Role.STAFF)
        f.fight.onFinalDeath(Role.STAFF) // reported twice: once only
        assertEquals(1, f.host.count("anim STAFF 14653"))
        f.run(4)
        assertFalse(f.fight.complete)
        f.tick()
        assertTrue(f.fight.complete)
        assertEquals(DagonhaiFight.Phase.DONE, f.fight.phase)
        assertEquals(listOf(Role.BOOK, Role.ELDER, Role.STAFF), f.host.despawned)
    }

    @Test
    fun theStaffMonkCanBeTheOneThatDies() {
        val f = FightFixture(overrides = """{"channel": {"first": 5000}, "explosion": {"dies": "staff"}}""")
        f.player(4072, 5601)
        f.fight.start()
        f.run(3)
        assertTrue(f.fight.debugBreak(toExplosion = true))
        f.tick()
        assertEquals(listOf(Role.STAFF), f.host.killed)
        assertTrue(f.host.has("anim STAFF 14653"))
        assertFalse(f.host.has("gfx STAFF 4163")) // only death_03 has a vfx
        f.fight.onFinalDeath(Role.BOOK)
        assertTrue(f.host.has("anim BOOK 14654"))
    }

    // ============================================================================================== channels

    @Test
    fun onlyAMatchingElementInterruptsTheChannel() {
        val f = FightFixture(overrides = """{"channel": {"casters": ["elder"]}, "tome": {"addsProgress": false}}""")
        f.player(4072, 5601)
        f.fight.start()
        f.runUntil { f.fight.channel != null }
        assertEquals(15, f.timeline.now)
        val c = f.fight.channel!!
        assertEquals(Role.ELDER, c.role)
        assertTrue(f.host.has("say ELDER ${DagonhaiFight.channelLine(c.element)}"))
        assertTrue(f.host.has("anim ELDER ${DagonhaiIds.seqChannel(Role.ELDER)}"))
        assertTrue(f.host.has("gfx ELDER ${DagonhaiIds.spotChannel(Role.ELDER, c.element, false)}"))
        assertTrue(f.host.has("charge ELDER 12"))
        val wrong = Element.entries.first { it != c.element }
        assertFalse(f.fight.onSpellLanded(Role.ELDER, wrong, false))
        assertFalse(f.fight.onSpellLanded(Role.STAFF, c.element, false)) // another member
        assertFalse(f.fight.onSpellLanded(Role.ELDER, null, false))      // non-standard spell
        assertNotNull(f.fight.channel)
        assertTrue(f.fight.onSpellLanded(Role.ELDER, c.element, splash = true)) // splashes count
        assertNull(f.fight.channel)
        assertTrue(f.host.has("anim ELDER 14611"))
        assertTrue(f.host.has("charge ELDER 0"))
        assertTrue(f.host.has("gfx ELDER -1"))
        f.run(20)
        assertEquals(0, f.fight.ritual)
        assertEquals(1, f.fight.channelsInterrupted)
    }

    @Test
    fun fourSuccessfulChannelsEmpowerTheTrio() {
        val f = FightFixture(overrides = """{"channel": {"casters": ["elder"], "first": 2, "interval": [13, 13]}, "tome": {"addsProgress": false}}""")
        val p = f.player(4072, 5601)
        f.fight.start()
        f.runUntil { f.fight.channelsCompleted == 1 }
        assertEquals(1, f.fight.ritual)
        assertEquals(1, f.host.hud[2])
        assertTrue(f.host.has("anim ELDER 14609"))
        assertTrue(f.host.log.any { it.startsWith("gfx ELDER ") && it.removePrefix("gfx ELDER ").toInt() in listOf(4080, 4082, 4084, 4086) })
        f.runUntil { f.fight.empowered }
        assertEquals(4, f.fight.ritual)
        assertTrue(f.host.has("cauldronAnim ${DagonhaiIds.SEQ_CAULDRON_EMPOWERED}"))
        assertTrue(f.host.has("cauldronGfx ${DagonhaiIds.SPOT_CAULDRON_UNLOCK}"))
        assertTrue(f.host.has("book ${DagonhaiIds.SEQ_BOOK_OPEN}"))
        assertTrue(f.host.empowered)
        val before = f.host.attacks.size
        f.run(11)
        // every Dagon'hai attacks every 5 t; the Elder fires two 4078 surges per attack
        assertEquals(2, f.host.count("anim ELDER 14604"))
        assertEquals(2, f.host.count("anim STAFF 14599"))
        assertEquals(2, f.host.count("anim BOOK 14601"))
        assertEquals(4, f.host.count("proj ELDER 4078"))
        assertTrue(f.host.attacks.size - before >= 6)
        assertTrue(f.host.attacks.drop(before).all { it.second === p && it.third == Style.MAGIC })
        assertNull(f.fight.channel)
    }

    // ================================================================================================== tomes

    @Test
    fun aPlayerOnTheLaneInterceptsTheTome() {
        val f = FightFixture(overrides = quiet)
        val blocker = f.player(4065, 5603, "blocker") // on the N lane
        val near = f.player(4067, 5603, "near")       // 2 tiles from the blocker: first Bandos ring
        f.fight.start()
        f.run(3)
        assertTrue(f.fight.debugTome(God.BANDOS, Compass.N, Role.STAFF))
        assertTrue(f.host.has("say STAFF What have we here?"))
        assertTrue(f.host.has("anim STAFF 14623"))
        f.tick()
        assertTrue(f.host.has("say STAFF ${God.BANDOS.line}"))
        assertTrue(f.host.has("anim STAFF 14625"))
        assertTrue(f.host.has("gfx STAFF 4109"))
        // Bandos gusts (4135-4142) on the N lane tiles: 4135
        val lane = DagonhaiArena.laneTiles(Compass.N, f.cfg.monk.stands.getValue(Compass.N))
        for (t in lane) assertTrue(f.host.has("map 4135 $t"), "gust on $t")
        f.run(3)
        assertTrue(f.host.has("proj STAFF 4115 fighter"))
        f.run(3)
        assertTrue(f.host.has("pgfx 4118"))
        assertEquals(1, f.fight.tomesIntercepted)
        val blocked = f.host.damage.filter { it.first === blocker }
        assertEquals(1, blocked.size)
        assertTrue(blocked.single().second in 0..55)
        f.run(4)
        assertTrue((0..3).all { f.host.has("map ${DagonhaiIds.spotBandosRing(it)} ${blocker.tile}") })
        assertEquals(listOf(20), f.host.damage.filter { it.first === near }.map { it.second })
        assertEquals(1, f.host.damage.count { it.first === blocker }) // never hit by its own wave
        assertEquals(0, f.fight.ritual) // an intercepted tome adds nothing
    }

    @Test
    fun anUnblockedTomeFiresTheCauldronAndAddsAQuarter() {
        val f = FightFixture(overrides = quiet)
        val p = f.player(4068, 5599, "p") // E lane, not on N; 2 tiles from the cauldron
        f.fight.start()
        f.run(3)
        assertTrue(f.fight.debugTome(God.BANDOS, Compass.N, Role.STAFF))
        f.run(4)
        assertTrue(f.host.has("proj STAFF 4115 ${DagonhaiArena.CAULDRON_CENTRE}"))
        f.run(3)
        assertEquals(1, f.fight.tomesToCauldron)
        assertTrue(f.host.has("cauldronAnim 14661"))
        assertTrue(f.host.has("cauldronGfx 4155"))
        assertEquals(1, f.fight.ritual)
        assertEquals(1, f.host.hud[2])
        f.tick()
        assertEquals(listOf(20), f.host.damage.filter { it.first === p }.map { it.second }) // ring 1 from the footprint
    }

    @Test
    fun zamorakInterceptBurnsAndDropsFireballs() {
        val f = FightFixture(overrides = quiet)
        val blocker = f.player(4065, 5602, "blocker")
        val other = f.player(4063, 5603, "other")
        f.fight.start()
        f.run(3)
        f.fight.debugTome(God.ZAMORAK, Compass.N, Role.BOOK)
        f.run(7)
        assertTrue(f.host.has("anim BOOK 14632"))
        assertTrue(f.host.has("gfx BOOK 4110"))
        assertEquals(listOf(25), f.host.damage.filter { it.first === blocker }.map { it.second })
        val fuses = f.host.log.count { it.startsWith("map 414") && it.split(" ")[1].toInt() in 4145..4148 }
        assertEquals(GodEffects.zamorakField(blocker.tile, 3, "random", 4, java.util.Random()).size, fuses)
        f.run(30)
        assertEquals(listOf(3, 3, 2, 2, 1, 1), f.host.burns.filter { it.first === blocker }.map { it.second })
        assertTrue(f.host.count("map 4149") == fuses)
        assertTrue(f.host.damage.any { it.first === other && it.second == 15 }) // standing on a fireball tile
    }

    @Test
    fun saradominInterceptHealsAndCallsTheStorm() {
        val f = FightFixture(overrides = quiet)
        val blocker = f.player(4065, 5602, "blocker")
        f.fight.start()
        f.run(3)
        f.fight.debugTome(God.SARADOMIN, Compass.N, Role.STAFF)
        f.run(7)
        assertTrue(f.host.has("anim STAFF 14626"))
        assertTrue(f.host.heals.any { it.first === blocker && it.second == 20 })
        assertTrue(f.host.has("pgfx 4066"))
        val healTiles = f.host.count("map 4064") + f.host.count("map 4065")
        assertEquals(10, healTiles)
        assertEquals(10, f.host.count("map 4063"))
        assertEquals(10, f.host.count("map 4151"))
        assertEquals(10, f.host.count("map 4153"))
        f.run(6)
        assertEquals(10, f.host.count("map 4069"))
        assertEquals(10, f.host.count("map 4068"))
        assertEquals(10, f.host.count("map 4152"))
        assertEquals(10, f.host.count("map 4154"))
    }

    @Test
    fun saradominAtTheCauldronHealsEveryoneAndThePool() {
        val f = FightFixture(overrides = quiet)
        val p = f.player(4072, 5603)
        f.fight.start()
        f.run(3)
        f.fight.onPoolHit(300)
        f.fight.debugTome(God.SARADOMIN, Compass.N, Role.STAFF)
        f.run(7)
        assertTrue(f.host.has("cauldronAnim 14659"))
        assertTrue(f.host.has("cauldronGfx 4150"))
        assertTrue(f.host.heals.any { it.first === p && it.second == 20 })
        assertEquals(364, f.fight.pool) // + 10% of 640
        assertEquals(364, f.host.pool)
    }

    @Test
    fun zamorakAtTheCauldronSpewsTwentyFireballs() {
        val f = FightFixture(overrides = quiet)
        f.player(4072, 5603)
        f.fight.start()
        f.run(3)
        f.fight.debugTome(God.ZAMORAK, Compass.N, Role.STAFF)
        f.run(7)
        assertTrue(f.host.has("cauldronAnim 14657"))
        assertTrue(f.host.has("cauldronGfx 4143"))
        assertEquals(20, f.host.log.count { it.startsWith("proj CAULDRON 4144 ") })
        f.run(12)
        assertEquals(20, f.host.count("map 4149"))
    }

    @Test
    fun onlyOneTomeIsInTheAirAtATime() {
        val f = FightFixture(seed = 9, overrides = noChannels)
        f.player(4072, 5603)
        f.fight.start()
        val throwStarts = ArrayList<Int>()
        val throwSeqs = (14624..14626) + (14632..14634)
        repeat(400) {
            val before = f.host.log.size
            f.tick()
            if (f.host.log.subList(before, f.host.log.size).any { e -> throwSeqs.any { e.endsWith(" $it") && e.startsWith("anim") } }) {
                throwStarts += f.timeline.now
            }
        }
        assertTrue(throwStarts.size >= 10, "monks keep throwing: $throwStarts")
        assertTrue(throwStarts.zipWithNext().all { (a, b) -> b - a >= 7 }, throwStarts.toString())
    }

    // ===================================================================================== lifecycle, misc

    @Test
    fun stopCancelsEverything() {
        val f = FightFixture()
        f.player(4072, 5603)
        f.fight.start()
        f.run(20)
        f.timeline.cancelAll()
        f.fight.stop()
        val size = f.host.log.size
        f.run(100)
        assertEquals(size, f.host.log.size)
        assertEquals(0, f.timeline.pending())
    }

    @Test
    fun aSeedReplaysTheSameFight() {
        fun runOnce(): List<String> {
            val f = FightFixture(seed = 42)
            f.player(4060, 5600)
            f.player(4070, 5596)
            f.fight.start()
            f.run(200)
            return f.host.log
        }
        assertEquals(runOnce(), runOnce())
    }

    @Test
    fun aLostFighterIsNoLongerTheFocus() {
        val f = FightFixture(overrides = noChannels)
        val a = f.player(4060, 5600)
        f.fight.start()
        f.run(5)
        assertTrue(f.fight.focus === a)
        a.isAlive = false
        f.fight.onFighterLost(a)
        assertNull(f.fight.focus)
        f.run(10)
        assertNull(f.fight.focus)
    }
}

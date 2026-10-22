package com.gadscape.raids.tfa.lobby

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WU-10: lobby config, Ascension rock calibration and the lobby timeline. */
class LobbyTest {

    private val cfg = TfaConfigTestKit.load(listOf(LobbyConfig), bundled = emptyMap())[LobbyConfig]

    @Test
    fun bundledLobbyJsonMatchesDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(LobbyConfig)

    @Test
    fun specNumbers() {
        assertEquals(3, cfg.rocks.activeCount)
        assertEquals(295, cfg.rocks.cycleTicks)
        assertEquals(80, cfg.rocks.levelRequired)
        assertEquals(15, cfg.rocks.xpPerShard)
        assertEquals(10, cfg.rocks.tiles.size, "the cache map places ten rocks")
        assertEquals(50, cfg.sprite.despawnTicks, "30 seconds")
        assertEquals(1..2, cfg.sprite.shards)
        assertEquals(50..89, cfg.sprite.xp)
        assertEquals(45, cfg.party.maxParties)
        assertEquals(7, cfg.party.maxApplicants)
    }

    @Test
    fun noChanceBelowLevel80AndMoreWithBetterTools() {
        val r = cfg.rocks
        assertEquals(0.0, AscensionMining.successChance(79, 61, r))
        assertEquals(r.chanceAt80, AscensionMining.successChance(80, 61, r), 1e-9)
        assertEquals(r.chanceAt99, AscensionMining.successChance(99, 61, r), 1e-9)
        assertEquals(r.chanceAt99, AscensionMining.successChance(105, 61, r), 1e-9, "boosts above 99 add nothing")
        assertTrue(AscensionMining.successChance(99, 41, r) < AscensionMining.successChance(99, 61, r))
        assertTrue(AscensionMining.successChance(90, 61, r) in r.chanceAt80..r.chanceAt99)
    }

    @Test
    fun anHourAt99WithADragonPickaxeGives130Shards() {
        // Spec §3.2 / WU-10: "A 1-hour bot run at 99 Mining with a dragon pickaxe yields 130 ± 15 shards". One run has
        // a standard deviation of about 10 shards, so the check is on the mean and on most runs.
        val runs = (1L..200L).map { AscensionMining.simulateHour(99, 61, cfg.rocks, cfg.sprite, Random(it)) }
        val mean = runs.map { it.shards }.average()
        assertTrue(mean in 125.0..135.0, "mean $mean")
        val inside = runs.count { it.shards in 115..145 }
        assertTrue(inside >= 160, "$inside of 200 runs within 130 ± 15")
        for (sim in runs) {
            assertTrue(sim.shardsFromSprites in 21..42, "one sprite per 295-tick cycle (21 an hour), 1-2 shards each: ${sim.shardsFromSprites}")
            assertTrue(spriteXp(sim) in 21 * 50..21 * 89, "21 sprites of 50-89 xp besides 15 xp per mined shard: ${spriteXp(sim)}")
        }
    }

    /** Sprite xp is 50-89 each; the remainder of [Simulation.xp] after the mined shards. */
    private fun spriteXp(sim: AscensionMining.Simulation): Int = sim.xp - sim.shardsFromRocks * cfg.rocks.xpPerShard

    @Test
    fun nextActiveSetNeverRepeatsTheCurrentRocks() {
        val random = Random(7)
        var current = setOf(0, 1, 2)
        repeat(500) {
            val next = AscensionMining.nextActive(10, current, 3, random)
            assertEquals(3, next.size)
            assertTrue(next.none { it in current }, "$next overlaps $current")
            assertTrue(next.all { it in 0 until 10 })
            current = next
        }
        assertEquals(3, AscensionMining.nextActive(4, setOf(0, 1, 2), 3, random).size, "falls back to old rocks if needed")
    }

    @Test
    fun spriteTimeIsInsideTheCycle() {
        val random = Random(3)
        repeat(1000) {
            val t = AscensionMining.spriteTick(100, 395, random)
            assertTrue(t in 100 until 395)
        }
        assertEquals(10, AscensionMining.spriteTick(10, 10, random), "an exhausted cycle spawns at once")
    }

    @Test
    fun gemPreRollIsRare() {
        val random = Random(11)
        val gems = (1..100_000).count { AscensionMining.rollReward(random, cfg.rocks) == AscensionMining.Reward.GEM }
        assertTrue(gems in 300..500, "about 1 in 256: $gems")
    }

    @Test
    fun tickQueueRunsInOrderAndCancels() {
        val q = TickQueue()
        val log = ArrayList<String>()
        q.schedule(2, "b") { log += "b" }
        q.schedule(1, "a") { log += "a" }
        q.schedule(2, "c") { log += "c" }
        q.schedule(3, "x") { log += "x" }
        assertEquals(1, q.cancel("x"))
        q.schedule(1, "boom") { error("ignored") }
        repeat(4) { q.advance() }
        assertEquals(listOf("a", "b", "c"), log)
        assertEquals(0, q.size)
        assertEquals(4, q.now)
    }
}

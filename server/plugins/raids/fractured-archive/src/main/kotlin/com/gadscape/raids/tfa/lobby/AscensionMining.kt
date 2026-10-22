package com.gadscape.raids.tfa.lobby

import java.util.Random

/**
 * The Ascension rock numbers (spec §3.2) [DOC wiki Ascension rocks / Sprite / money making guide], free of engine
 * types:
 * - level 80, 15 xp per shard, three rocks active, all three sink every 295 ticks and three others rise; depletion is
 *   global (not affected by other players), tick manipulation and mining enhancers do nothing;
 * - one sprite per depletion cycle at a random time, 1-2 shards and 50-89 xp, gone after 30 s;
 * - uncut gems are a rare pre-roll;
 * - calibration target [DOC]: about 130 shards per hour at 99 Mining with a dragon pickaxe, sprites included.
 *
 * [HOUSE] model: one roll every `rollIntervalTicks`; success chance rises linearly from `chanceAt80` to `chanceAt99`,
 * times a pickaxe-tier multiplier. With the defaults a 99 / dragon miner gets ≈ 100 shards an hour from the rock and
 * ≈ 30 from the sprites (`AscensionMiningTest` simulates the hour).
 */
object AscensionMining {

    /** Pickaxe tier from its level requirement: 1 (bronze/iron), 21 (mithril), 31 (adamant), 41 (rune), 61 (dragon+). */
    @JvmStatic
    fun toolTier(toolLevel: Int): Int = when {
        toolLevel >= 61 -> 4
        toolLevel >= 41 -> 3
        toolLevel >= 31 -> 2
        toolLevel >= 21 -> 1
        else -> 0
    }

    /** Chance that one roll gives a shard. 0 below the level requirement. */
    @JvmStatic
    fun successChance(level: Int, toolLevel: Int, cfg: LobbyConfig.Rocks): Double {
        if (level < cfg.levelRequired) return 0.0
        val span = (99 - cfg.levelRequired).coerceAtLeast(1)
        val t = ((level.coerceAtMost(99) - cfg.levelRequired).toDouble() / span).coerceIn(0.0, 1.0)
        val base = cfg.chanceAt80 + (cfg.chanceAt99 - cfg.chanceAt80) * t
        val mult = cfg.toolMultipliers.getOrElse(toolTier(toolLevel)) { 1.0 }
        return (base * mult).coerceIn(0.0, 1.0)
    }

    /** What one successful roll gives. */
    enum class Reward { SHARD, GEM }

    /** Gem pre-roll, then the shard. */
    @JvmStatic
    fun rollReward(random: Random, cfg: LobbyConfig.Rocks): Reward =
        if (cfg.gemOneIn > 0 && random.nextInt(cfg.gemOneIn) == 0) Reward.GEM else Reward.SHARD

    /** The next active set: [count] rock indexes out of [total], none of them in [current] when enough exist. */
    @JvmStatic
    fun nextActive(total: Int, current: Set<Int>, count: Int, random: Random): Set<Int> {
        val pool = (0 until total).filter { it !in current }.toMutableList()
        val fallback = (0 until total).filter { it in current }.toMutableList()
        val out = LinkedHashSet<Int>()
        while (out.size < count.coerceAtMost(total)) {
            val source = if (pool.isNotEmpty()) pool else fallback
            if (source.isEmpty()) break
            out += source.removeAt(random.nextInt(source.size))
        }
        return out
    }

    /** A random tick in [now, cycleEnd) for this cycle's sprite. */
    @JvmStatic
    fun spriteTick(now: Long, cycleEnd: Long, random: Random): Long {
        val span = (cycleEnd - now).coerceAtLeast(1)
        return now + (random.nextDouble() * span).toLong().coerceIn(0, span - 1)
    }

    /** Result of [simulateHour]. */
    data class Simulation(val shardsFromRocks: Int, val shardsFromSprites: Int, val gems: Int, val xp: Int) {
        val shards: Int get() = shardsFromRocks + shardsFromSprites
    }

    /**
     * One hour (6000 ticks) of a bot that mines non-stop, walks [moveTicks] to a new rock after each depletion and
     * never misses a sprite: the calibration check of the spec (130 ± 15 shards at 99 with a dragon pickaxe).
     */
    @JvmStatic
    fun simulateHour(
        level: Int, toolLevel: Int, rocks: LobbyConfig.Rocks, sprite: LobbyConfig.Sprite, random: Random,
        moveTicks: Int = 4, ticks: Int = 6000,
    ): Simulation {
        val chance = successChance(level, toolLevel, rocks)
        var fromRocks = 0
        var fromSprites = 0
        var gems = 0
        var xp = 0
        var tick = 0
        var cycleStart = 0
        while (tick < ticks) {
            val cycleEnd = minOf(cycleStart + rocks.cycleTicks, ticks)
            if (sprite.enabled) {
                val at = spriteTick(cycleStart.toLong(), cycleEnd.toLong(), random)
                if (at < ticks) {
                    fromSprites += sprite.shards.first + random.nextInt(sprite.shards.last - sprite.shards.first + 1)
                    xp += sprite.xp.first + random.nextInt(sprite.xp.last - sprite.xp.first + 1)
                }
            }
            tick = cycleStart + moveTicks + rocks.rollIntervalTicks
            while (tick <= cycleEnd) {
                if (random.nextDouble() < chance) {
                    if (rollReward(random, rocks) == Reward.GEM) gems++ else {
                        fromRocks++
                        xp += rocks.xpPerShard
                    }
                }
                tick += rocks.rollIntervalTicks
            }
            cycleStart = cycleEnd
            tick = cycleStart
        }
        return Simulation(fromRocks, fromSprites, gems, xp)
    }
}

package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.Evidence
import com.gadscape.raids.tfa.config.TfaConfigSpec
import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.raid.StaticTile

/**
 * `moia.json` (spec §4.4 "House defaults", §7 U35-U37). Every [Evidence.HOUSE] / [Evidence.COMM] / [Evidence.INF]
 * number of the Moia room is a key here, with the spec value as the default; [Evidence.CACHE] / [Evidence.DOC]
 * keys hold the cache 2746 and wiki facts the room needs at runtime (the server has no combat-definition file for
 * npcs 16667 / 16670, so their stats come from here).
 */
data class MoiaConfig(
    /** 2-player HP (cache 16667 hitpoints 1900; the wiki's 785 is a mid-fight Monster Inspect, U35). */
    val baseHp: Int,
    /** Enrage when HP ≤ floor(scaled max × fraction) ("~33%", COMM). */
    val enrageFraction: Double,
    /** Ticks between autos (cache param 14). */
    val attackSpeed: Int,
    val rangedMax: Int,
    val magicMax: Int,
    /** Every Nth auto slot is "dagger, then teleport + blue special". */
    val specialEveryNthAuto: Int,
    /** SPAWNING length before the first auto (spec state machine: 2 t). */
    val spawnTicks: Int,
    /** DYING length: death seq 14784 is 150 cycles = 5 t (CACHE). */
    val deathTicks: Int,
    val special: Special,
    val projectiles: Projectiles,
    val infect: Infect,
    val binders: Binders,
    val enrage: Enrage,
    val startArm: MoiaArm,
    val arms: Map<MoiaArm, StaticTile>,
    /** Displayed level when `scaling.json combatLevelOverride.moia` is absent (DOC wiki 1064; cache 1004). */
    val combatLevel: Int,
    val traits: CombatTraits,
    val binderTraits: CombatTraits,
    val stats: NpcProfile,
    val binderStats: NpcProfile,
) {

    /** The teleport special after the dagger on tick T (spec §4.4 attack table, COMM rule, HOUSE cadence). */
    data class Special(
        /** T + this: seq 14788 + spot 4219 (teleport out). */
        val teleportOut: Int,
        /** T + this: move to another arm, spot 4220. */
        val arrive: Int,
        /** T + this: cast 14785 + projectile 4213. */
        val cast: Int,
        /**
         * Ticks from the cast to the magic hit. 3 lands it on T+6: one tick after the next dagger is thrown (T+5) and
         * between the dagger landings (grid + 2..3), so it is always "off-sync" (COMM) and off the 5-t grid (WU-23 test).
         */
        val hitDelay: Int,
        /** Height of the impact spot 4216 on the player. */
        val impactHeight: Int,
    )

    /** One classic projectile: end cycle = delay + perTile × distance; hit after `1 + floor((hitOffset + d) / hitDivisor)` t. */
    data class ProjectileSpec(
        val startHeight: Int,
        val endHeight: Int,
        /** Start delay in client cycles. */
        val delay: Int,
        /** Client slope units (OSRS "slope", 0-64 = 0-90 degrees). */
        val slope: Int,
        val perTile: Int,
        val hitOffset: Int,
        val hitDivisor: Int,
    ) {
        /** Ticks from the throw to the hit at Chebyshev distance [distance]. */
        fun hitDelay(distance: Int): Int = 1 + (hitOffset + distance) / hitDivisor

        /** Client cycles until the projectile reaches a target at [distance]. */
        fun flightCycles(distance: Int): Int = delay + perTile * distance
    }

    data class Projectiles(val dagger: ProjectileSpec, val magic: ProjectileSpec, val infect: ProjectileSpec)

    data class Infect(
        /** One auto is replaced by the infect cast every [interval] ticks. */
        val interval: Int,
        val duration: Int,
        val coughEvery: Int,
        val dmg: IntRange,
        val serpMult: Double,
        val binderDmg: Int,
        /** Play the stock cough seq 11268 on a coughing player (spec: optional; Nex, the template, has none). */
        val coughAnim: Boolean,
    )

    data class Binders(
        val setBase: Int,
        val setPerScale: Int,
        val setCap: Int,
        /** One binder respawns every [respawn] ticks while below the set size. */
        val respawn: Int,
        /** The formation steps every [shuffle] ticks. */
        val shuffle: Int,
        /** Stab max hit (cache str 170 + 15: floor((179 × 79 + 320) / 640) = 22). */
        val max: Int,
        /** Chebyshev reach of the binder melee. */
        val reach: Int,
        /** Ticks a dead binder stays (death seq 14792 is 12.3 t; removed at 6 t, INF). */
        val despawn: Int,
        /** Binder HP (cache 95, not scaled). */
        val hp: Int,
        val attackSpeed: Int,
        /** Ticks from the attack seq 14791 to the hit (impact sounds at frames 14-19 = 1.4-1.9 t). */
        val hitDelay: Int,
        /** A (re)spawn point is at least this Chebyshev distance from every player. */
        val minPlayerDistance: Int,
    ) {
        /** `min(setCap, setBase + setPerScale × scale)`: 6 at 2-scale, 12 at 5+. */
        fun setSize(scale: Int): Int = minOf(setCap, setBase + setPerScale * scale)
    }

    data class Enrage(
        val set: Int,
        val chargeTicks: Int,
        val explosionPerSurvivor: Int,
        val explosionCap: Int,
        /** Ticks from the enrage threshold to the first charge (spec ENRAGE_TRANSITION: 2 t). */
        val transitionTicks: Int,
        /** The diamond around the centre (spec "Enrage diamond"). */
        val diamond: List<StaticTile>,
        /** Mirror the charge on the HP HUD extra bar (spec option b, INF). */
        val hudExtraBar: Boolean,
    ) {
        /** Explosion damage for [survivors] binders left when the charge completes: `min(cap, perSurvivor × n)`. */
        fun explosion(survivors: Int): Int = if (survivors <= 0) 0 else minOf(explosionCap, explosionPerSurvivor * survivors)
    }

    /** Combat levels and bonuses of an NPC the server has no combat definition file for. */
    data class NpcProfile(
        /** attack, strength, defence, magic, ranged. */
        val levels: List<Int>,
        /** stab, slash, crush, magic, ranged attack bonus. */
        val attack: List<Int>,
        /** stab, slash, crush, magic, ranged (standard) defence bonus. */
        val defence: List<Int>,
        /** melee strength, ranged strength, magic damage %. */
        val strength: List<Int>,
    )

    companion object SPEC : TfaConfigSpec<MoiaConfig>("moia.json") {

        /** spec §4.4 arena table [INF]: SW tile of Moia's 3x3 on each arm and in the centre. */
        val DEFAULT_ARMS: Map<MoiaArm, StaticTile> = linkedMapOf(
            MoiaArm.W to StaticTile(4054, 5662),
            MoiaArm.E to StaticTile(4070, 5662),
            MoiaArm.S to StaticTile(4062, 5654),
            MoiaArm.N to StaticTile(4062, 5670),
            MoiaArm.CENTRE to StaticTile(4062, 5662),
        )

        /** spec §4.4 "Enrage diamond" [HOUSE]. */
        val DEFAULT_DIAMOND: List<StaticTile> = listOf(
            StaticTile(4063, 5660), StaticTile(4065, 5661), StaticTile(4066, 5663), StaticTile(4065, 5665),
            StaticTile(4063, 5666), StaticTile(4061, 5665), StaticTile(4060, 5663), StaticTile(4061, 5661),
        )

        /** §3.11 boss default + Moia's DOC rows: 60% floor (117), air 80%, ranged 60 / 120 / 140. */
        val DEFAULT_TRAITS: CombatTraits = CombatTraits.BOSS_DEFAULT.copy(
            weakness = Element.AIR, weaknessPct = 80, rangedDef = RangedDefence(60, 120, 140),
        )

        /** Adds can be poisoned (§3.11 HOUSE); binder ranged defence 0 / 0 / 0 [HOUSE]. */
        val DEFAULT_BINDER_TRAITS: CombatTraits = CombatTraits.ADD_DEFAULT.copy(rangedDef = RangedDefence(0, 0, 0))

        override fun read(r: ConfigReader): MoiaConfig = MoiaConfig(
            baseHp = r.int("baseHp", 1900, Evidence.INF, min = 1),
            enrageFraction = r.double("enrageFraction", 0.3333, Evidence.COMM, min = 0.0, max = 1.0),
            attackSpeed = r.int("attackSpeed", 5, Evidence.DOC, min = 1, max = 20),
            rangedMax = r.int("rangedMax", 32, Evidence.INF, min = 0),
            magicMax = r.int("magicMax", 34, Evidence.HOUSE, min = 0),
            specialEveryNthAuto = r.int("specialEveryNthAuto", 4, Evidence.HOUSE, min = 1),
            spawnTicks = r.int("spawnTicks", 2, Evidence.HOUSE, min = 0, max = 50),
            deathTicks = r.int("deathTicks", 5, Evidence.CACHE, min = 0, max = 50),
            special = r.obj("special") {
                val out = int("teleportOut", 1, Evidence.HOUSE, min = 1, max = 4)
                val arrive = int("arrive", 2, Evidence.HOUSE, min = 1, max = 4)
                val cast = int("cast", 3, Evidence.HOUSE, min = 1, max = 4)
                check("arrive", out < arrive && arrive < cast) { "teleportOut < arrive < cast is required (got $out, $arrive, $cast)" }
                Special(
                    teleportOut = out, arrive = arrive, cast = cast,
                    hitDelay = int("hitDelay", 3, Evidence.HOUSE, min = 1, max = 10),
                    impactHeight = int("impactHeight", 92, Evidence.INF, min = 0, max = 255),
                )
            },
            projectiles = r.obj("projectiles") {
                Projectiles(
                    dagger = obj("dagger") { projectile(this, 70, 31, 30, 15, 5, 3, 6) },
                    magic = obj("magic") { projectile(this, 70, 31, 30, 15, 5, 1, 3) },
                    infect = obj("infect") { projectile(this, 70, 31, 30, 15, 10, 1, 3) },
                )
            },
            infect = r.obj("infect") {
                Infect(
                    interval = int("interval", 30, Evidence.HOUSE, min = 1),
                    duration = int("duration", 20, Evidence.HOUSE, min = 1),
                    coughEvery = int("coughEvery", 4, Evidence.HOUSE, min = 1),
                    dmg = intRange("dmg", 4..8, Evidence.HOUSE, min = 0),
                    serpMult = double("serpMult", 0.25, Evidence.COMM, min = 0.0, max = 1.0),
                    binderDmg = int("binderDmg", 20, Evidence.COMM, min = 0),
                    coughAnim = bool("coughAnim", false, Evidence.HOUSE),
                )
            },
            binders = r.obj("binders") {
                Binders(
                    setBase = int("setBase", 2, Evidence.HOUSE, min = 0),
                    setPerScale = int("setPerScale", 2, Evidence.HOUSE, min = 0),
                    setCap = int("setCap", 12, Evidence.HOUSE, min = 0, max = 16),
                    respawn = int("respawn", 12, Evidence.HOUSE, min = 1),
                    shuffle = int("shuffle", 8, Evidence.HOUSE, min = 1),
                    max = int("max", 22, Evidence.INF, min = 0),
                    reach = int("reach", 2, Evidence.COMM, min = 1, max = 8),
                    despawn = int("despawn", 6, Evidence.INF, min = 1, max = 30),
                    hp = int("hp", 95, Evidence.DOC, min = 1),
                    attackSpeed = int("attackSpeed", 5, Evidence.DOC, min = 1, max = 20),
                    hitDelay = int("hitDelay", 1, Evidence.INF, min = 0, max = 5),
                    minPlayerDistance = int("minPlayerDistance", 2, Evidence.HOUSE, min = 0, max = 8),
                )
            },
            enrage = r.obj("enrage") {
                val set = int("set", 8, Evidence.HOUSE, min = 1)
                val diamond = tileList("diamond", DEFAULT_DIAMOND, Evidence.HOUSE, size = 1..32)
                check("diamond", diamond.size >= set) { "diamond has ${diamond.size} tiles, fewer than set = $set" }
                Enrage(
                    set = minOf(set, diamond.size),
                    chargeTicks = int("chargeTicks", 25, Evidence.HOUSE, min = 1),
                    explosionPerSurvivor = int("explosionPerSurvivor", 25, Evidence.HOUSE, min = 0),
                    explosionCap = int("explosionCap", 99, Evidence.HOUSE, min = 0),
                    transitionTicks = int("transitionTicks", 2, Evidence.HOUSE, min = 1, max = 10),
                    diamond = diamond,
                    hudExtraBar = bool("hudExtraBar", true, Evidence.INF),
                )
            },
            startArm = r.enum("startArm", MoiaArm.W, Evidence.HOUSE).let { if (it == MoiaArm.CENTRE) MoiaArm.W else it },
            arms = r.obj("arms") {
                MoiaArm.entries.associateWith { arm -> tile(arm.key, DEFAULT_ARMS.getValue(arm), Evidence.INF) }
            },
            combatLevel = r.int("combatLevel", 1064, Evidence.DOC, min = 1),
            traits = CombatTraits.read(r, DEFAULT_TRAITS),
            binderTraits = CombatTraits.read(r, DEFAULT_BINDER_TRAITS, key = "binderTraits", evidence = Evidence.HOUSE),
            stats = r.obj("stats") {
                profile(this, listOf(1, 1, 195, 350, 200), listOf(0, 0, 0, 300, 350), listOf(20, 110, 15, 30, 120), listOf(1, 35, 15))
            },
            binderStats = r.obj("binderStats") {
                profile(this, listOf(1, 170, 225, 50, 1), listOf(0, 0, 0, 0, 0), listOf(12, 15, 10, -20, 0), listOf(15, 0, 0))
            },
        )

        private fun projectile(
            r: ConfigReader, start: Int, end: Int, delay: Int, slope: Int, perTile: Int, hitOffset: Int, hitDivisor: Int,
        ) = ProjectileSpec(
            startHeight = r.int("startHeight", start, Evidence.INF, min = 0, max = 255),
            endHeight = r.int("endHeight", end, Evidence.INF, min = 0, max = 255),
            delay = r.int("delay", delay, Evidence.INF, min = 0, max = 300),
            slope = r.int("slope", slope, Evidence.INF, min = 0, max = 64),
            perTile = r.int("perTile", perTile, Evidence.INF, min = 0, max = 60),
            hitOffset = r.int("hitOffset", hitOffset, Evidence.INF, min = 0, max = 30),
            hitDivisor = r.int("hitDivisor", hitDivisor, Evidence.INF, min = 1, max = 30),
        )

        private fun profile(r: ConfigReader, levels: List<Int>, attack: List<Int>, defence: List<Int>, strength: List<Int>) =
            NpcProfile(
                levels = r.intList("levels", levels, Evidence.CACHE, size = 5..5, min = 0),
                attack = r.intList("attack", attack, Evidence.CACHE, size = 5..5),
                defence = r.intList("defence", defence, Evidence.CACHE, size = 5..5),
                strength = r.intList("strength", strength, Evidence.CACHE, size = 3..3),
            )
    }
}

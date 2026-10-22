package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.Evidence
import com.gadscape.raids.tfa.config.Evidence.CACHE
import com.gadscape.raids.tfa.config.Evidence.COMM
import com.gadscape.raids.tfa.config.Evidence.DOC
import com.gadscape.raids.tfa.config.Evidence.HOUSE
import com.gadscape.raids.tfa.config.Evidence.INF
import com.gadscape.raids.tfa.config.TfaConfigSpec
import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.raid.StaticTile

/**
 * `dreadnoth.json` (spec §4.5 "House defaults", plus the keys the implementation needs that the spec block leaves
 * implicit; each extra key names its source). The top-level keys `moveEveryTicks` .. `blood` are the spec block
 * verbatim; `melee`, `slam`, `volley`, `projectiles`, `death`, `arena`, `stats`, `traits` and the extra `jump` /
 * `jelly` keys are documented in `raids4-impl/ENC-dreadnoth.md`.
 *
 * Every tick count is in server ticks (1 t = 30 client cycles); seq lengths are from `raids4/dreadnoth/NOTES.md` §5.
 */
data class DreadnothConfig(
    /** CACHE npc 16665 hitpoints=2100, the 2-player base for `Scaling.hp` (§2.6: 2100 / 2997 / ... / 7484). */
    val baseHp: Int,
    /** DOC "around one tile every two ticks"; walk seq 14778 is a 2-tick loop [CACHE]. */
    val moveEveryTicks: Int,
    /** CACHE param 14 = 4. */
    val attackSpeed: Int,
    /** HOUSE: NPC formula floor(((285+9)×(35+64)+320)/640) = 45. */
    val meleeMax: Int,
    val slamMax: Int,
    /** HOUSE: floor(((250+9)×(20+64)+320)/640) = 34. */
    val rangedMax: Int,
    /** DOC 85 / 65 / 45; the k-th threshold runs k cycles. */
    val thresholds: List<Int>,
    val thresholdMode: ThresholdMode,
    /** Cycle ticks of the three ranged volleys (HOUSE 3 / 7 / 11). */
    val volleyTicks: List<Int>,
    val cycleLength: Int,
    /** DOC 25. */
    val enragePct: Int,
    val jump: Jump,
    val jelly: Jelly,
    val blood: Blood,
    val melee: Melee,
    val slam: Slam,
    val volley: Volley,
    val projectiles: Projectiles,
    val death: Death,
    val arena: Arena,
    val stats: Stats,
    /** DOC wiki infobox, "empirical": +77.5% combat xp. */
    val xpModifier: Double,
    val traits: CombatTraits,
) {

    /** U38: reading A ("cycles": k × [slam + 3 volleys]) or reading B ("multihit": k slams, then 3 volleys). */
    enum class ThresholdMode { CYCLES, MULTIHIT }

    data class Jump(
        /** HOUSE 8: a jump-slam every 8 ticks in enrage. */
        val period: Int,
        /** HOUSE 3: landing hit 3 t after take-off (seq 14780 landing sound at 2.93 t, shadow 4209 lasts 3 t). */
        val landTick: Int,
        /** HOUSE [40, 70]: typeless hit on players inside the landing area. */
        val landingDmg: IntRange,
        /** HOUSE 0.5: Protect from Melee halves the landing hit. */
        val pfmMult: Double,
        /** DOC "~30-40" healed per player hit by the landing. */
        val heal: IntRange,
        /** DOC "take damage regardless of location" without Protect from Melee: a melee hit, max 45. */
        val noPrayerMax: Int,
        /** HOUSE 1: ticks between entering enrage and the first take-off. */
        val firstDelay: Int,
        /** DOC follow-up "following the slam": ranged + splatter 4 t after take-off (spec t4). */
        val followUpTick: Int,
        /**
         * Client cycles of the leap's exact move: lift-off at 2 (synth 12358 at 0.07 t) and touch-down at 88 (synth
         * 12355 at 2.93 t) of seq 14780 [CACHE seq sounds]; the boss is moved onto the landing area at take-off.
         */
        val moveStartCycle: Int,
        val moveEndCycle: Int,
        /** HOUSE: only the jumped player gets the follow-up (latest wiki text); false = every player (earlier text). */
        val followUpOnlyTarget: Boolean,
    )

    data class Jelly(
        /** CACHE 75 (Monster Inspect 61 read as a damaged jelly); not scaled. */
        val hp: Int,
        /** HOUSE count per threshold sequence: min(cap, perThresholdBase + perScale × n). */
        val perThresholdBase: Int,
        val perScale: Int,
        val cap: Int,
        /** DOC "around ten" jellies on death. */
        val deathWave: Int,
        /** HOUSE formula floor(((160+9)×(0+64)+320)/640) = 17. */
        val max: Int,
        /** HOUSE 2 points per attack, blocked or not [DOC "even if blocked"]. */
        val prayerDrain: Int,
        /** HOUSE 3: the forming-jelly graphic 4210 (2.8 t) shows this long before the NPC rises. */
        val spawnTicks: Int,
        /** INF U74: a jelly that cannot reach its target spits (seq 15161). */
        val rangedWhenBlocked: Boolean,
        /** CACHE param 14 = 5. */
        val attackSpeed: Int,
        /** INF 3: passive and untargetable while seq 15162 (2.73 t) plays. */
        val riseTicks: Int,
        /** Spec: blood tile on the jelly's tile 2 t after its death. */
        val bloodDelay: Int,
        /** CACHE: death seq 1587 is 68 cycles, so the corpse is removed after 3 t. */
        val despawnTicks: Int,
        /** Spec: jellies form on tiles at least this far from Dreadnoth's footprint. */
        val minBossDistance: Int,
        /** INF: death-wave jellies hop 1..hopMaxTiles out of the footprint (14774 for 1 tile, 14773 beyond). */
        val hopMaxTiles: Int,
        /** HOUSE: the spit reaches this far (Chebyshev) and needs this many ticks without a melee path. */
        val rangedRange: Int,
        val rangedStuckTicks: Int,
        /** INF: the stock jelly sounds of GadScape npc 437 (attack 547, block 550, death 549). */
        val stockSounds: Boolean,
        val stats: Stats,
        val traits: CombatTraits,
    )

    data class Blood(
        /** DOC 6-8 per tick. */
        val dmg: IntRange,
        /** HOUSE 30 t (ToB's BloodTrail lasts 31). */
        val lifetime: Int,
        /** HOUSE: a tile under a random player on every 3rd boss melee ("periodically"). */
        val periodicEveryNthMelee: Int,
    )

    data class Melee(
        /** INF: the AoE melee lands 1 t after seq 14779 starts (impact synth 12381 at 0.80 t). */
        val hitDelay: Int,
        /** INF: Chebyshev distance from the 5x5 footprint that the AoE covers (1 = the 7x7, under him included). */
        val reach: Int,
        /** UNK, HOUSE crush. */
        val style: String,
    )

    data class Slam(
        /** INF: the arena-wide hit lands 1 t after hop 14777 starts (landing synth 12373 at 0.90 t). */
        val hitDelay: Int,
    )

    data class Volley(
        /** NOTES §5 house: no ranged seq exists in the cache, so a volley plays melee01 14779; false = no NPC seq. */
        val seq: Boolean,
        /** HOUSE: every player in the arena (the wiki tells everyone to pray Missiles); false = the chase target only. */
        val allPlayers: Boolean,
    )

    /** A classic projectile; arrival cycle = delay + duration + perTile × distance (Zenyte `Projectile`). */
    data class ProjectileSpec(
        val startHeight: Int,
        val endHeight: Int,
        val delay: Int,
        /** Client slope units (0-64). */
        val slope: Int,
        val duration: Int,
        val perTile: Int,
    ) {
        /** Arrival in client cycles over [distance] tiles. */
        fun cycles(distance: Int): Int = delay + duration + perTile * distance

        /** Ticks until the hit lands: the first tick boundary at or after the arrival (at least 1). */
        fun flightTicks(distance: Int): Int = maxOf(1, (cycles(distance) + 29) / 30)
    }

    data class Projectiles(
        /** Spec §4.5: 4211 starts at 30 cycles, height 60 → 31, arrives at 30 + 5d; slope HOUSE 16. */
        val ranged: ProjectileSpec,
        /** NOTES §8: 4212 a blitz-style arc (slope 16) onto a tile; 60 cycles, so it lands at t6 (spec) and leaves blood. */
        val splatter: ProjectileSpec,
    )

    data class Death(
        /** CACHE seq 14782 is 4.33 t; spec: the death wave at +2 t, despawn at +5 t. */
        val waveTick: Int,
        val despawnTick: Int,
    )

    data class Arena(
        /** INF: boss SW tile, centred on the octagram decal (4191,5663). */
        val spawn: StaticTile,
        /** INF: he faces the transporter side. */
        val spawnFacing: String,
    )

    /** NPC combat stats (Zenyte combat definitions), from cache params and Monster Inspect. */
    data class Stats(
        /** attack, strength, defence, magic, ranged. */
        val levels: List<Int>,
        /** stab, slash, crush, magic, ranged attack bonus. */
        val attackBonuses: List<Int>,
        /** stab, slash, crush, magic, ranged (standard) defence. */
        val defenceBonuses: List<Int>,
        /** melee strength, ranged strength, magic damage %. */
        val otherBonuses: List<Int>,
    )

    companion object SPEC : TfaConfigSpec<DreadnothConfig>("dreadnoth.json") {

        /** §4.5 combat profile: Def floor none, fire 85%, ranged 100/80/80, freeze res 40, demonbane 25%. */
        @JvmField
        val BOSS_TRAITS: CombatTraits = CombatTraits.BOSS_DEFAULT.copy(
            defenceFloorPct = null, weakness = Element.FIRE, weaknessPct = 85,
            rangedDef = RangedDefence(100, 80, 80), freezeResistPct = 40, demonbanePct = 25,
        )

        /** §4.5 jelly: no floor, fire 60%, ranged −45/18/−50, freeze res 20, demon (normal demonbane), cannon-immune. */
        @JvmField
        val JELLY_TRAITS: CombatTraits = CombatTraits.ADD_DEFAULT.copy(
            weakness = Element.FIRE, weaknessPct = 60, rangedDef = RangedDefence(-45, 18, -50),
            freezeResistPct = 20, demonbanePct = 100,
        )

        private val THRESHOLD_MODES = listOf("cycles", "multihit")

        override fun read(r: ConfigReader): DreadnothConfig = DreadnothConfig(
            baseHp = r.int("baseHp", 2100, CACHE, 1, 100_000),
            moveEveryTicks = r.int("moveEveryTicks", 2, DOC, 1, 10),
            attackSpeed = r.int("attackSpeed", 4, CACHE, 1, 20),
            meleeMax = r.int("meleeMax", 45, HOUSE, 0, 200),
            slamMax = r.int("slamMax", 45, HOUSE, 0, 200),
            rangedMax = r.int("rangedMax", 34, HOUSE, 0, 200),
            thresholds = r.intList("thresholds", listOf(85, 65, 45), DOC, size = 0..10, min = 1, max = 99,
                check = { list -> if (list.zipWithNext().any { (a, b) -> a <= b }) "must be strictly decreasing" else null }),
            thresholdMode = when (r.string("thresholdMode", "cycles", HOUSE, allowed = THRESHOLD_MODES)) {
                "multihit" -> ThresholdMode.MULTIHIT
                else -> ThresholdMode.CYCLES
            },
            volleyTicks = r.intList("volleyTicks", listOf(3, 7, 11), HOUSE, size = 0..10, min = 1, max = 100),
            cycleLength = r.int("cycleLength", 14, HOUSE, 2, 100),
            enragePct = r.int("enragePct", 25, DOC, 0, 99),
            jump = r.obj("jump") {
                Jump(
                    period = int("period", 8, HOUSE, 4, 60),
                    landTick = int("landTick", 3, HOUSE, 1, 10),
                    landingDmg = intRange("landingDmg", 40..70, HOUSE, 0, 200),
                    pfmMult = double("pfmMult", 0.5, HOUSE, 0.0, 1.0),
                    heal = intRange("heal", 30..40, DOC, 0, 500),
                    noPrayerMax = int("noPrayerMax", 45, DOC, 0, 200),
                    firstDelay = int("firstDelay", 1, HOUSE, 0, 30),
                    followUpTick = int("followUpTick", 4, INF, 1, 30),
                    moveStartCycle = int("moveStartCycle", 2, CACHE, 0, 300),
                    moveEndCycle = int("moveEndCycle", 88, CACHE, 1, 300),
                    followUpOnlyTarget = bool("followUpOnlyTarget", true, COMM),
                )
            },
            jelly = r.obj("jelly") {
                Jelly(
                    hp = int("hp", 75, CACHE, 1, 1000),
                    perThresholdBase = int("perThresholdBase", 4, HOUSE, 0, 30),
                    perScale = int("perScale", 1, HOUSE, 0, 10),
                    cap = int("cap", 12, HOUSE, 0, 50),
                    deathWave = int("deathWave", 10, DOC, 0, 50),
                    max = int("max", 17, HOUSE, 0, 100),
                    prayerDrain = int("prayerDrain", 2, HOUSE, 0, 99),
                    spawnTicks = int("spawnTicks", 3, HOUSE, 0, 20),
                    rangedWhenBlocked = bool("rangedWhenBlocked", true, INF),
                    attackSpeed = int("attackSpeed", 5, CACHE, 1, 20),
                    riseTicks = int("riseTicks", 3, INF, 0, 20),
                    bloodDelay = int("bloodDelay", 2, HOUSE, 0, 20),
                    despawnTicks = int("despawnTicks", 3, CACHE, 1, 20),
                    minBossDistance = int("minBossDistance", 2, HOUSE, 0, 7),
                    hopMaxTiles = int("hopMaxTiles", 3, INF, 1, 7),
                    rangedRange = int("rangedRange", 4, HOUSE, 1, 15),
                    rangedStuckTicks = int("rangedStuckTicks", 2, HOUSE, 1, 20),
                    stockSounds = bool("stockSounds", true, INF),
                    stats = readStats(this, JELLY_STATS, CACHE),
                    traits = CombatTraits.read(this, JELLY_TRAITS),
                )
            },
            blood = r.obj("blood") {
                Blood(
                    dmg = intRange("dmg", 6..8, DOC, 0, 99),
                    lifetime = int("lifetime", 30, HOUSE, 1, 1000),
                    periodicEveryNthMelee = int("periodicEveryNthMelee", 3, HOUSE, 0, 100),
                )
            },
            melee = r.obj("melee") {
                Melee(
                    hitDelay = int("hitDelay", 1, INF, 0, 5),
                    reach = int("reach", 1, INF, 0, 5),
                    style = string("style", "crush", HOUSE, allowed = listOf("stab", "slash", "crush")),
                )
            },
            slam = r.obj("slam") {
                Slam(
                    hitDelay = int("hitDelay", 1, INF, 0, 5),
                )
            },
            volley = r.obj("volley") {
                Volley(
                    seq = bool("seq", true, INF),
                    allPlayers = bool("allPlayers", true, HOUSE),
                )
            },
            projectiles = r.obj("projectiles") {
                Projectiles(
                    ranged = obj("ranged") { readProjectile(this, ProjectileSpec(60, 31, 30, 16, 0, 5)) },
                    splatter = obj("splatter") { readProjectile(this, ProjectileSpec(60, 0, 30, 16, 30, 0)) },
                )
            },
            death = r.obj("death") {
                Death(
                    waveTick = int("waveTick", 2, INF, 0, 20),
                    despawnTick = int("despawnTick", 5, CACHE, 1, 30),
                )
            },
            arena = r.obj("arena") {
                Arena(
                    spawn = tile("spawn", StaticTile(4189, 5661, 0), INF),
                    spawnFacing = string("spawnFacing", "west", HOUSE, allowed = listOf("north", "east", "south", "west")),
                )
            },
            stats = readStats(r, BOSS_STATS, CACHE),
            xpModifier = r.double("xpModifier", 1.775, DOC, 0.0, 10.0),
            traits = CombatTraits.read(r, BOSS_TRAITS),
        )

        /** CACHE npc 16665 + DOC Monster Inspect (melee attack +185 is server-only; used for every melee style). */
        @JvmField
        val BOSS_STATS = Stats(
            levels = listOf(280, 285, 125, 220, 250),
            attackBonuses = listOf(185, 185, 185, 0, 180),
            defenceBonuses = listOf(15, 20, 5, 20, 80),
            otherBonuses = listOf(35, 20, 0),
        )

        /** CACHE npc 16666 (no attack bonuses), standard ranged defence 18 [DOC]. */
        @JvmField
        val JELLY_STATS = Stats(
            levels = listOf(200, 160, 100, 100, 1),
            attackBonuses = listOf(0, 0, 0, 0, 0),
            defenceBonuses = listOf(4, 5, 0, 15, 18),
            otherBonuses = listOf(0, 0, 0),
        )

        private fun readStats(r: ConfigReader, default: Stats, evidence: Evidence): Stats =
            r.obj("stats") {
                Stats(
                    levels = intList("levels", default.levels, evidence, size = 5..5, min = 0, max = 5000),
                    attackBonuses = intList("attackBonuses", default.attackBonuses, evidence, size = 5..5, min = -1000, max = 1000),
                    defenceBonuses = intList("defenceBonuses", default.defenceBonuses, evidence, size = 5..5, min = -1000, max = 1000),
                    otherBonuses = intList("otherBonuses", default.otherBonuses, evidence, size = 3..3, min = -1000, max = 1000),
                )
            }

        private fun readProjectile(r: ConfigReader, default: ProjectileSpec): ProjectileSpec = ProjectileSpec(
            startHeight = r.int("startHeight", default.startHeight, HOUSE, 0, 255),
            endHeight = r.int("endHeight", default.endHeight, HOUSE, 0, 255),
            delay = r.int("delay", default.delay, HOUSE, 0, 300),
            slope = r.int("slope", default.slope, HOUSE, 0, 64),
            duration = r.int("duration", default.duration, HOUSE, 0, 300),
            perTile = r.int("perTile", default.perTile, HOUSE, 0, 60),
        )
    }
}

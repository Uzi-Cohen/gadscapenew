package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.Evidence
import com.gadscape.raids.tfa.config.Evidence.CACHE
import com.gadscape.raids.tfa.config.Evidence.DOC
import com.gadscape.raids.tfa.config.Evidence.HOUSE
import com.gadscape.raids.tfa.config.Evidence.INF
import com.gadscape.raids.tfa.config.TfaConfigSpec
import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.raid.StaticTile

/**
 * `xarth.json` (spec §4.7 "House defaults" plus the keys the implementation needs; `raids4-impl/ENC-xarth.md` §4
 * lists them with their sources). The keys `attackSpeed` .. `healBlast` of the spec block are kept verbatim (their
 * objects gain extra keys); every other key names its evidence below. All tick counts are server ticks
 * (1 t = 30 client cycles); seq lengths are from `raids4/xarth/seq_xarth.txt`.
 */
data class XarthConfig(
    /** CACHE npc 16684 hitpoints=2600 = the 2-player base (§2.6: 2600 / 3711 / ... / 9265). */
    val baseHp: Int,
    /** CACHE param 14 = 4. */
    val attackSpeed: Int,
    /** HOUSE (NOTES §7.1): first auto this many ticks after the start, or 1 t after the first player hit. */
    val firstAttackDelay: Int,
    /** DOC ~80 / ~65 / ~50; the last one starts phase 2 after its blast. */
    val thresholds: List<Int>,
    /** HOUSE (TD auto max 31 [DOC]). */
    val magicMax: Int,
    /** HOUSE: NPC formula floor(((225+9)×(30+64)+320)/640) = 34. */
    val meleeMax: Int,
    /** HOUSE: floor(((220+9)×(30+64)+320)/640) = 34. */
    val rangedMax: Int,
    /** DOC "his magic attacks will inflict a burn which can stack"; HOUSE: stacks per accurate roll, even when prayed. */
    val magicBurn: Int,
    val blast: Blast,
    val transition: Transition,
    val flames: Flames,
    val corpses: Corpses,
    val healBlast: HealBlast,
    val melee: Melee,
    val projectiles: Projectiles,
    val turn: Turn,
    val death: Death,
    val arena: Arena,
    val stats: Stats,
    /** DOC wiki infobox "approximate ... empirical": +180% combat xp. */
    val xpModifier: Double,
    val traits: CombatTraits,
) {

    /** Who the threshold blast can hit (spec §4.7: `coverRule = "los" | "facing"`). */
    enum class CoverRule { LOS, FACING }

    /** How cover is tested: the pillar ray (Bresenham from his centre, standing pillars only) or the engine's map LOS. */
    enum class LosTest { RAY, ENGINE }

    /** When a corpse pile can reanimate again: once its corpse has appeared, or once that corpse is gone. */
    enum class PileBusy { SPAWNED, DEAD }

    data class Blast(
        /** HOUSE 3 (TD bind; spec t+0 → t+3). */
        val bindTicks: Int,
        /** HOUSE [35, 45] typeless per bomb tile stood on (TD 40-45 [DOC]). */
        val bombDmg: IntRange,
        /** HOUSE [45, 65] typeless, no prayer, for a player with clear line of sight. */
        val blastDmg: IntRange,
        /** HOUSE 2 burn stacks with the blast. */
        val blastBurn: Int,
        /** HOUSE t+7: the blast resolves (14989 starts at t+4; 4380 bursts after 60 cycles). */
        val resolveTick: Int,
        val coverRule: CoverRule,
        /** HOUSE 1 burn stack from a bomb. */
        val bombBurn: Int,
        /** HOUSE t+3: aoe throw 14992 + 4376, the bombs 4378 leave (once the 3-tick root 14991 has played). */
        val aoeTick: Int,
        /** HOUSE t+4: flame blast 14989 + 4380 (the 2-tick charge). */
        val chargeTick: Int,
        /** HOUSE t+9: run back on, autos resume (14989 is 5 t from t+4). */
        val endTick: Int,
        /** DOC "two searing projectiles": per player, one on their tile and one in their 3x3 (TD rule). */
        val bombsPerPlayer: Int,
        val losTest: LosTest,
        /** INF (NOTES §8.11): run is switched back on at the end if the player had it on. */
        val restoreRun: Boolean,
    )

    data class Transition(
        /** HOUSE t+3: flames leave Xarth as 4378 projectiles (they land 2 t later with the bomb projectile). */
        val flamesLaunchTick: Int,
        /** HOUSE t+8: autos resume (15017 is 8 t). */
        val autosTick: Int,
        /** HOUSE t+10: the first corpse summon. */
        val firstSummonTick: Int,
        /** DOC "once all three pillars are destroyed" (first wiki text): standing pillars collapse at P2. */
        val collapseStanding: Boolean,
        /** [C+] optional: also play the TD shield-deplete spot 4033 (Xarth has no shield [DOC]); off by default. */
        val depleteSpot: Boolean,
    )

    data class Flames(
        /** HOUSE count at P2 = base + perTwoScale × floor(n / 2), capped. */
        val base: Int,
        val perTwoScale: Int,
        /** HOUSE: +add flames every addEvery ticks [DOC "targeting various tiles with flames"]. */
        val addEvery: Int,
        val add: Int,
        val cap: Int,
        /** HOUSE [4, 8] typeless per tick on a flame. */
        val dmg: IntRange,
        /** HOUSE: at most one burn stack per player per this many ticks from flames. */
        val burnEvery: Int,
        /** HOUSE: flames land at least this far (Chebyshev) from his 3x3. */
        val minDistance: Int,
        /** CACHE 12003 = 150 cyc: the idle 12005 is sent back after this many ticks. */
        val spawnAnimTicks: Int,
    )

    data class Interval(val abovePct: Int, val ticks: Int)

    data class Corpses(
        /** INF `20 + 7 × (n − 2)` (cache 62 = 8-scale, Monster Inspect 20); `scaling.json overrides.xarth_corpse_hp` wins. */
        val hp: List<Int>,
        /** DOC "greater rate" as he weakens; HOUSE 15 / 12 / 9 t above 35 / 20 / 0 %. */
        val intervals: List<Interval>,
        /** HOUSE k = 1 + floor((n − 1) / perSummonDiv). */
        val perSummonDiv: Int,
        val cap: Int,
        /** HOUSE: a pile burns (fire variant) this long before its corpse appears. */
        val pileFireTicks: Int,
        /** HOUSE: one extra corpse per fire-spell hit, at most once per this many ticks. */
        val fireExtraCooldown: Int,
        /** HOUSE: untargetable this long after appearing. */
        val untargetableTicks: Int,
        /** HOUSE: Chebyshev distance from his 3x3 at which a corpse is absorbed. */
        val arrivalDistance: Int,
        /** CACHE 15005 = 60 cyc: despawn after the absorb seq. */
        val absorbTicks: Int,
        /** CACHE 15003 = 120 cyc: despawn after the death seq. */
        val deathTicks: Int,
        val pileBusy: PileBusy,
        /** HOUSE: a splashed water spell does not douse (it did not hit). */
        val douseOnSplash: Boolean,
        /** HOUSE: a splashed fire spell still summons ("avoid using fire spells" [DOC]). */
        val fireOnSplash: Boolean,
        val stats: Stats,
        val dousedStats: Stats,
        val traits: CombatTraits,
        val dousedTraits: CombatTraits,
    )

    data class HealBlast(
        /** DOC 200 healed by corpses; the counter keeps the excess. */
        val threshold: Int,
        /** HOUSE [40, 55] typeless for the first player on a line from his centre. */
        val dmg: IntRange,
        /** HOUSE 3 stacks for the first player. */
        val burn: Int,
        /** DOC "lowered if standing behind the first player"; HOUSE × 0.5 and 1 stack. */
        val shieldedMult: Double,
        val shieldedBurn: Int,
        /** HOUSE t+2: the burst (4380 holds its first frame 60 cycles). */
        val burstTick: Int,
        /** HOUSE t+4: autos resume. */
        val resumeTick: Int,
    )

    data class Melee(
        /** INF: lands 1 t after 14983 starts (impact synth 12686 at 22 cycles). */
        val hitDelay: Int,
        /** UNK, HOUSE crush (Xarth's own weakness is crush; the wiki gives no melee style). */
        val style: String,
    )

    /** A classic projectile; arrival cycle = delay + duration + perTile × distance (Zenyte `Projectile`). */
    data class ProjectileSpec(
        /** Server height units (×4 on the rev 241 wire). */
        val startHeight: Int,
        val endHeight: Int,
        /** Client cycles before it leaves (the release frame of the casting seq). */
        val delay: Int,
        /** Client slope units (0-64). */
        val slope: Int,
        val duration: Int,
        val perTile: Int,
    ) {
        fun cycles(distance: Int): Int = delay + duration + perTile * distance

        /** Ticks until the hit lands: the first tick boundary at or after the arrival (at least 1). */
        fun flightTicks(distance: Int): Int = maxOf(1, (cycles(distance) + 29) / 30)
    }

    data class Projectiles(
        /** 4034: release at 32 cycles (14984 release synth 12681 [CACHE]); heights TD 64/32 × 165% [INF]. */
        val magic: ProjectileSpec,
        /** 4374: release at 52 cycles (14985 release synth 12689 [CACHE]). */
        val ranged: ProjectileSpec,
        /** 4378 bombs and flames: a high ground-targeted arc, 60 cycles = 2 t whatever the distance (spec). */
        val bomb: ProjectileSpec,
    )

    data class Turn(
        /** Spec [C+]: turn seqs 14981 / 14982 on face changes; HOUSE: he faces his next target before the attack. */
        val enabled: Boolean,
        /** HOUSE: smallest face change (2048 = full turn) that plays a turn seq. */
        val minAngle: Int,
        /** HOUSE: how many ticks before an auto he turns to its target (only while no other seq plays). */
        val leadTicks: Int,
    )

    data class Death(
        /** CACHE 14995 = 150 cyc: the room completes after the death seq. */
        val completeTick: Int,
    )

    data class Arena(
        /** HOUSE (NOTES §4): SW tile of his 3x3 on the scorch decal, centre (4191,5535); static all fight. */
        val spawn: StaticTile,
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

    companion object SPEC : TfaConfigSpec<XarthConfig>("xarth.json") {

        /** §4.7: floor 102 (60%), water 85%, ranged 125/230/155, demonbane 80%, freeze-immune [HOUSE: static]. */
        @JvmField
        val BOSS_TRAITS: CombatTraits = CombatTraits.BOSS_DEFAULT.copy(
            defenceFloorPct = 60, weakness = Element.WATER, weaknessPct = 85,
            rangedDef = RangedDefence(125, 230, 155), freezeResistPct = 100, demonbanePct = 80,
        )

        /** §4.7 corpse: water 40%, ranged −20/80/80, freeze resistance 0, poisonable, no cannon (raid rule). */
        @JvmField
        val CORPSE_TRAITS: CombatTraits = CombatTraits.ADD_DEFAULT.copy(
            weakness = Element.WATER, weaknessPct = 40, rangedDef = RangedDefence(-20, 80, 80), freezeResistPct = 0,
        )

        /** Doused corpse: the wiki gives no separate profile [UNK]; HOUSE same as burning. */
        @JvmField
        val DOUSED_TRAITS: CombatTraits = CORPSE_TRAITS

        /** CACHE npc 16684 + Monster Inspect: melee +150 [DOC, weak, all three styles], magic +250, ranged +250. */
        @JvmField
        val BOSS_STATS = Stats(
            levels = listOf(250, 225, 170, 350, 220),
            attackBonuses = listOf(150, 150, 150, 250, 250),
            defenceBonuses = listOf(115, 105, 20, 205, 230),
            otherBonuses = listOf(30, 30, 0),
        )

        /** CACHE npc 16687 params 5-8 (100/100/100/0), standard ranged defence 80 [DOC]. */
        @JvmField
        val CORPSE_STATS = Stats(
            levels = listOf(50, 40, 100, 100, 1),
            attackBonuses = listOf(0, 0, 0, 0, 0),
            defenceBonuses = listOf(100, 100, 100, 0, 80),
            otherBonuses = listOf(0, 0, 0),
        )

        /** CACHE npc 16688 params 5-8 (−20/−20/−20/0); ranged defence not published [UNK, HOUSE 80]. */
        @JvmField
        val DOUSED_STATS = CORPSE_STATS.copy(defenceBonuses = listOf(-20, -20, -20, 0, 80))

        @JvmField
        val DEFAULT_INTERVALS = listOf(Interval(35, 15), Interval(20, 12), Interval(0, 9))

        override fun read(r: ConfigReader): XarthConfig = XarthConfig(
            baseHp = r.int("baseHp", 2600, CACHE, 1, 100_000),
            attackSpeed = r.int("attackSpeed", 4, CACHE, 1, 20),
            firstAttackDelay = r.int("firstAttackDelay", 5, HOUSE, 0, 60),
            thresholds = r.intList("thresholds", listOf(80, 65, 50), DOC, size = 1..10, min = 1, max = 99,
                check = { list -> if (list.zipWithNext().any { (a, b) -> a <= b }) "must be strictly decreasing" else null }),
            magicMax = r.int("magicMax", 30, HOUSE, 0, 200),
            meleeMax = r.int("meleeMax", 34, HOUSE, 0, 200),
            rangedMax = r.int("rangedMax", 34, HOUSE, 0, 200),
            magicBurn = r.int("magicBurn", 1, HOUSE, 0, 5),
            blast = r.obj("blast") {
                Blast(
                    bindTicks = int("bindTicks", 3, HOUSE, 0, 20),
                    bombDmg = intRange("bombDmg", 35..45, HOUSE, 0, 200),
                    blastDmg = intRange("blastDmg", 45..65, HOUSE, 0, 200),
                    blastBurn = int("blastBurn", 2, HOUSE, 0, 5),
                    resolveTick = int("resolveTick", 7, HOUSE, 1, 30),
                    coverRule = when (string("coverRule", "los", HOUSE, allowed = listOf("los", "facing"))) {
                        "facing" -> CoverRule.FACING
                        else -> CoverRule.LOS
                    },
                    bombBurn = int("bombBurn", 1, HOUSE, 0, 5),
                    aoeTick = int("aoeTick", 3, HOUSE, 0, 30),
                    chargeTick = int("chargeTick", 4, HOUSE, 0, 30),
                    endTick = int("endTick", 9, HOUSE, 1, 40),
                    bombsPerPlayer = int("bombsPerPlayer", 2, DOC, 0, 2),
                    losTest = when (string("losTest", "ray", HOUSE, allowed = listOf("ray", "engine"))) {
                        "engine" -> LosTest.ENGINE
                        else -> LosTest.RAY
                    },
                    restoreRun = bool("restoreRun", true, INF),
                )
            },
            transition = r.obj("transition") {
                Transition(
                    flamesLaunchTick = int("flamesLaunchTick", 3, HOUSE, 0, 30),
                    autosTick = int("autosTick", 8, HOUSE, 1, 30),
                    firstSummonTick = int("firstSummonTick", 10, HOUSE, 1, 60),
                    collapseStanding = bool("collapseStanding", true, DOC),
                    depleteSpot = bool("depleteSpot", false, INF),
                )
            },
            flames = r.obj("flames") {
                Flames(
                    base = int("base", 6, HOUSE, 0, 50),
                    perTwoScale = int("perTwoScale", 1, HOUSE, 0, 10),
                    addEvery = int("addEvery", 25, HOUSE, 1, 600),
                    add = int("add", 2, HOUSE, 0, 20),
                    cap = int("cap", 16, HOUSE, 0, 100),
                    dmg = intRange("dmg", 4..8, HOUSE, 0, 99),
                    burnEvery = int("burnEvery", 4, HOUSE, 1, 100),
                    minDistance = int("minDistance", 2, HOUSE, 0, 8),
                    spawnAnimTicks = int("spawnAnimTicks", 5, CACHE, 0, 30),
                )
            },
            corpses = r.obj("corpses") {
                Corpses(
                    hp = intList("hp", listOf(20, 27, 34, 41, 48, 55, 62), INF, size = 1..7, min = 1, max = 10_000),
                    intervals = objList("intervals", DEFAULT_INTERVALS, HOUSE, size = 1..10) {
                        Interval(int("abovePct", 0, HOUSE, 0, 100), int("ticks", 15, HOUSE, 1, 600))
                    },
                    perSummonDiv = int("perSummonDiv", 3, HOUSE, 1, 8),
                    cap = int("cap", 12, HOUSE, 0, 50),
                    pileFireTicks = int("pileFireTicks", 6, HOUSE, 0, 30),
                    fireExtraCooldown = int("fireExtraCooldown", 3, HOUSE, 0, 60),
                    untargetableTicks = int("untargetableTicks", 1, HOUSE, 0, 10),
                    arrivalDistance = int("arrivalDistance", 1, HOUSE, 1, 5),
                    absorbTicks = int("absorbTicks", 2, CACHE, 1, 10),
                    deathTicks = int("deathTicks", 4, CACHE, 1, 10),
                    pileBusy = when (string("pileBusy", "spawned", HOUSE, allowed = listOf("spawned", "dead"))) {
                        "dead" -> PileBusy.DEAD
                        else -> PileBusy.SPAWNED
                    },
                    douseOnSplash = bool("douseOnSplash", false, HOUSE),
                    fireOnSplash = bool("fireOnSplash", true, HOUSE),
                    stats = readStats(this, "stats", CORPSE_STATS, CACHE),
                    dousedStats = readStats(this, "dousedStats", DOUSED_STATS, CACHE),
                    traits = CombatTraits.read(this, CORPSE_TRAITS),
                    dousedTraits = CombatTraits.read(this, DOUSED_TRAITS, key = "dousedTraits", evidence = HOUSE),
                )
            },
            healBlast = r.obj("healBlast") {
                HealBlast(
                    threshold = int("threshold", 200, DOC, 1, 10_000),
                    dmg = intRange("dmg", 40..55, HOUSE, 0, 200),
                    burn = int("burn", 3, HOUSE, 0, 5),
                    shieldedMult = double("shieldedMult", 0.5, HOUSE, 0.0, 1.0),
                    shieldedBurn = int("shieldedBurn", 1, HOUSE, 0, 5),
                    burstTick = int("burstTick", 2, HOUSE, 0, 10),
                    resumeTick = int("resumeTick", 4, HOUSE, 1, 20),
                )
            },
            melee = r.obj("melee") {
                Melee(
                    hitDelay = int("hitDelay", 1, INF, 0, 5),
                    style = string("style", "crush", HOUSE, allowed = listOf("stab", "slash", "crush")),
                )
            },
            projectiles = r.obj("projectiles") {
                Projectiles(
                    magic = obj("magic") { readProjectile(this, ProjectileSpec(106, 32, 32, 16, 10, 5), CACHE) },
                    ranged = obj("ranged") { readProjectile(this, ProjectileSpec(106, 32, 52, 16, 10, 5), CACHE) },
                    bomb = obj("bomb") { readProjectile(this, ProjectileSpec(106, 0, 0, 40, 60, 0), HOUSE) },
                )
            },
            turn = r.obj("turn") {
                Turn(
                    enabled = bool("enabled", true, INF),
                    minAngle = int("minAngle", 256, HOUSE, 1, 1024),
                    leadTicks = int("leadTicks", 2, HOUSE, 1, 10),
                )
            },
            death = r.obj("death") {
                Death(completeTick = int("completeTick", 5, CACHE, 1, 30))
            },
            arena = r.obj("arena") {
                Arena(
                    spawn = tile("spawn", StaticTile(4190, 5534, 0), INF),
                    spawnFacing = string("spawnFacing", "west", HOUSE, allowed = listOf("north", "east", "south", "west")),
                )
            },
            stats = readStats(r, "stats", BOSS_STATS, CACHE),
            xpModifier = r.double("xpModifier", 2.8, DOC, 0.0, 10.0),
            traits = CombatTraits.read(r, BOSS_TRAITS),
        )

        private fun readStats(r: ConfigReader, key: String, default: Stats, evidence: Evidence): Stats =
            r.obj(key) {
                Stats(
                    levels = intList("levels", default.levels, evidence, size = 5..5, min = 0, max = 5000),
                    attackBonuses = intList("attackBonuses", default.attackBonuses, evidence, size = 5..5, min = -1000, max = 1000),
                    defenceBonuses = intList("defenceBonuses", default.defenceBonuses, evidence, size = 5..5, min = -1000, max = 1000),
                    otherBonuses = intList("otherBonuses", default.otherBonuses, evidence, size = 3..3, min = -1000, max = 1000),
                )
            }

        /** [delayEvidence]: CACHE when the delay is the release frame of the casting seq (its release synth). */
        private fun readProjectile(r: ConfigReader, default: ProjectileSpec, delayEvidence: Evidence): ProjectileSpec = ProjectileSpec(
            startHeight = r.int("startHeight", default.startHeight, HOUSE, 0, 255),
            endHeight = r.int("endHeight", default.endHeight, HOUSE, 0, 255),
            delay = r.int("delay", default.delay, delayEvidence, 0, 300),
            slope = r.int("slope", default.slope, HOUSE, 0, 64),
            duration = r.int("duration", default.duration, HOUSE, 0, 300),
            perTile = r.int("perTile", default.perTile, HOUSE, 0, 60),
        )
    }
}

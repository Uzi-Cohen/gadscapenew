package com.gadscape.raids.tfa.rooms.ezekiel

import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.Evidence
import com.gadscape.raids.tfa.config.TfaConfigSpec
import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.raid.StaticTile

/**
 * `ezekiel.json` (spec §4.2 "House defaults", plus the facts and implementation keys the fight needs at run time).
 *
 * Every [Evidence.HOUSE]/[Evidence.COMM]/[Evidence.INF] key is listed at load as "still holding a house value" until a
 * file marks it verified. HP, levels and bonuses are [Evidence.DOC]/[Evidence.CACHE] facts (wiki Monster Inspect,
 * cache 2746 npc params), kept here so an operator can correct them without a rebuild. Seq lengths (cleave 2 t,
 * special 5 t, heal 3 t, finale imbue 3 t, death 6 t ...) are cache facts and live in [EzekielIds], not here.
 */
data class EzekielConfig(
    /** 2-player HP [CACHE npc 16652 hitpoints = DOC wiki 3000]. */
    val baseHp: Int,
    /** Attack clock [CACHE param 14, DOC wiki attack speed 5]. */
    val attackSpeed: Int,
    /** First attack after the start [HOUSE]. */
    val firstAttackDelay: Int,
    /** Cleave max, NPC formula with Str 150 and +80 strength [INF]. */
    val meleeMax: Int,
    /** Ticks from seq 14685 to the cleave hit (impact synths at keyframe 11 of 60) [INF]. */
    val cleaveHitDelay: Int,
    /** Melee style of the cleave: no param 26 in the cache; he swings a sword [HOUSE]. */
    val cleaveStyle: String,
    /** Cleave hits roll accuracy (slash vs each player) [HOUSE]. */
    val cleaveAccuracyRoll: Boolean,
    /** Lightning on all, max [HOUSE]. */
    val magicMax: Int,
    /** Protect from Magic multiplier on the lightning [HOUSE]. */
    val magicPfmMult: Double,
    /** Ticks from seq 14686 to the lightning hit (spot 4167's strike lands ~cycle 61) [HOUSE]. */
    val lightningHitDelay: Int,
    /** Lightning rolls magic accuracy against each player [HOUSE]. */
    val lightningAccuracyRoll: Boolean,
    /** Cross bursts: damage per burst, typeless [HOUSE]. */
    val starBurstDamage: Int,
    val starArmWidth: Int,
    val starArmLength: Int,
    /** Burst ticks after the special starts [HOUSE; spot 4172's two bursts are 59 cycles apart]. */
    val starBurstTicks: List<Int>,
    /** Tick of the marker spot 4171 (after the 2-t spawn spot 4170) [INF]. */
    val starMarkerTick: Int,
    /** Ezekiel does not move during the 5-t special [HOUSE]. */
    val specialRoots: Boolean,
    /** Swords appear this long after the special starts ("after starting the lightning attack") [HOUSE]. */
    val swordSpawnDelay: Int,
    val swordChargeTicks: Int,
    /** Charge ticks of the three swords of a `full` grid, assigned in a random order [COMM "different rates"]. */
    val swordFullStagger: List<Int>,
    /** Ticks of a full 6-tile run [HOUSE]. */
    val swordFlightTicks: Int,
    val swordPlayerDmgBase: Int,
    val swordPlayerDmgEmpower: Int,
    /** A sword hit on Ezekiel deals swordBossPct% of his max HP × (1 + empower) [HOUSE]. */
    val swordBossPct: Int,
    /** Air weakness of the swords [COMM "very weak to air magic"; HOUSE number]. */
    val swordAirWeaknessPct: Int,
    /** Light / standard / heavy ranged defence of the swords [COMM "resistant to range"; HOUSE number]. */
    val swordRangedDef: Int,
    /** Sword HP = empower capacity [CACHE npc 16653 hitpoints 80]. */
    val swordHp: Int,
    /** Player magic always hits the swords [COMM rev 15377868 "appear to always be hit by magic"]. */
    val swordMagicAlwaysHits: Boolean,
    /** The casts a special picks from (uniform) [HOUSE, the 7 of spec §4.2]. */
    val swordCasts: List<SwordCast>,
    /** Players moving on the launch tick roll out of the lane [COMM]. */
    val swordDodge: Boolean,
    /** Ticks from the launch to the player hits [HOUSE]. */
    val swordPlayerHitDelay: Int,
    val stunTicks: Int,
    val healPct: Int,
    val healRule: HealRule,
    /** Ticks from the heal seq to the HP gain (heal synths at 40-49 cycles) [HOUSE]. */
    val healDelay: Int,
    /** The heal (3 t) pauses the attack clock like the stun does [HOUSE]. */
    val healPausesClock: Boolean,
    /** Finale threshold, % of scaled max HP [COMM wiki Strategies]. */
    val enragePct: Double,
    val finale: FinaleSettings,
    val bleed: BleedMode,
    val bleedOnTank: Boolean,
    /** Message to a new tank [HOUSE]. */
    val tankMessage: String,
    /** Ezekiel's SW at the start [INF: Guthix emblem 62861 at 4063,5535 = centre]. */
    val spawn: StaticTile,
    val spawnFacing: Facing,
    val stats: Stats,
    /** Ezekiel's combat traits (the `traits` block) [DOC]. */
    val traits: CombatTraits,
    /** `::tfa ezekiel` debug run: where players go when it stops (walkable corridor by the passage) [HOUSE]. */
    val debugExitTile: StaticTile,
) {

    enum class HealRule { NO_SWORD_HIT, PER_MISSING_SWORD }

    enum class BleedMode { VARDORVIS, NONE }

    enum class SwordVisual { NPC_SEQ, SPOTANIM }

    enum class Facing(val angle: Int) {
        SOUTH(0), WEST(512), NORTH(1024), EAST(1536);

        val direction: com.zenyte.game.util.Direction
            get() = when (this) {
                SOUTH -> com.zenyte.game.util.Direction.SOUTH
                WEST -> com.zenyte.game.util.Direction.WEST
                NORTH -> com.zenyte.game.util.Direction.NORTH
                EAST -> com.zenyte.game.util.Direction.EAST
            }
    }

    data class FinaleSettings(
        /** Ticks from the telegraph to the strike (`hit_delay_ticks`) [HOUSE]. */
        val hitDelay: Int,
        /** Ticks between attacks (`next_attack_delay_ticks`) [HOUSE]. */
        val nextAttack: Int,
        /** Attacks per volley [COMM "repeats three times in a row"]. */
        val perVolley: Int,
        val volleyPause: Int,
        /** Damage of a strike, magic [HOUSE]; Protect from Magic multiplier (`prayer_protection`) [HOUSE]. */
        val damage: Int,
        val pfmMult: Double,
        /** Transition length: teleport + fences + imbue (14694, 3 t) + swords drop [HOUSE]. */
        val transitionTicks: Int,
        /** The three finale swords drop this long into the transition (after the 3-t imbue) [INF]. */
        val swordDropDelay: Int,
        /** Ezekiel's finale SW, cell (2,1) [INF "central eastern section"]. */
        val bossTile: StaticTile,
        val bossFacing: Facing,
        /** The player pen [x1, y1, x2, y2], cell (1,1) [INF]. */
        val pen: List<Int>,
        /** SW tiles of the 7 sword fences (npc 16654), in drop/idle variant order 1..7 [HOUSE order]. */
        val fenceTiles: List<StaticTile>,
        val fenceFacing: Facing,
        /** Fence cells block movement [INF: npcs do not block players]. */
        val fenceCollision: Boolean,
        /** Finale sword tiles (npc 16655, size 1) [HOUSE placement of "left (N), right (S), front (E)"]. */
        val swordTiles: Map<FinaleSword, StaticTile>,
        val swordFacing: Facing,
        /** Finale sword visuals: npc seqs of each sword's set, or the set's spotanims on the npc [INF]. */
        val swordVisual: SwordVisual,
        /** dbtable 139 combos [HOUSE]. */
        val combos: List<List<FinaleAttack>>,
        /** dbtable 138 rotations, 1-based combo numbers [HOUSE]. */
        val rotations: Map<String, List<Int>>,
        /** Safe tiles per attack [COMM wiki tables, INF mapping]. */
        val safeTiles: Map<FinaleAttack, List<StaticTile>>,
    ) {
        val penRect: Rect get() = Rect(pen[0], pen[1], pen[2], pen[3])
        fun safe(attack: FinaleAttack): List<Tile> = safeTiles[attack].orEmpty().map { Tile(it.x, it.y) }
    }

    /** Levels and bonuses (StatDefinitions order) [DOC wiki Monster Inspect, CACHE params 3-8, 10, 12, 65]. */
    data class Profile(
        /** attack, strength, defence, magic, ranged. */
        val levels: List<Int>,
        /** stab, slash, crush, magic, ranged attack bonuses. */
        val attackBonuses: List<Int>,
        /** stab, slash, crush, magic, ranged (standard) defence bonuses. */
        val defenceBonuses: List<Int>,
        /** melee strength, ranged strength, magic damage %. */
        val otherBonuses: List<Int>,
    )

    data class Stats(val ezekiel: Profile, val sword: Profile)

    /**
     * The Animated Swords' traits: [SWORD_TRAITS] with `swordAirWeaknessPct` and `swordRangedDef`, and magic that
     * always hits with `swordMagicAlwaysHits` (spec §4.2; built here so the two spec keys stay the only knobs).
     */
    val swordTraits: CombatTraits
        get() = SWORD_TRAITS.copy(
            weaknessPct = swordAirWeaknessPct,
            rangedDef = RangedDefence(swordRangedDef, swordRangedDef, swordRangedDef),
            guaranteedMagic = swordMagicAlwaysHits,
        )

    companion object SPEC : TfaConfigSpec<EzekielConfig>("ezekiel.json") {

        /** Ezekiel 16652 [DOC wiki Ezekiel rev 15378798; hotfix 60% cap]: earth 25%, ranged 445/370/320. */
        @JvmField
        val EZEKIEL_TRAITS = CombatTraits.BOSS_DEFAULT.copy(
            weakness = Element.EARTH, weaknessPct = 25, rangedDef = RangedDefence(445, 370, 320),
        )

        /**
         * Animated Sword 16653 [COMM + HOUSE]: air 200%, ranged 300 each, poison/venom immune (poison would fill
         * empower), no defence floor. Magic accuracy is set by `swordMagicAlwaysHits` (code-only trait).
         */
        @JvmField
        val SWORD_TRAITS = CombatTraits.ADD_DEFAULT.copy(
            weakness = Element.AIR, weaknessPct = 200, rangedDef = RangedDefence(300, 300, 300),
            immunePoison = true, immuneVenom = true,
        )

        /** 16652: Att 250 Str 150 Def 200 Mag 200 Rng 80; +250 magic attack; def 150/90/35/200/370; str +80, mdmg 30%. */
        @JvmField
        val EZEKIEL_PROFILE = Profile(
            levels = listOf(250, 150, 200, 200, 80),
            attackBonuses = listOf(0, 0, 0, 250, 0),
            defenceBonuses = listOf(150, 90, 35, 200, 370),
            otherBonuses = listOf(80, 0, 30),
        )

        /** 16653: Def 115, Mag 90, +100 stab/slash/crush/magic [CACHE]; ranged 300 [HOUSE]. */
        @JvmField
        val SWORD_PROFILE = Profile(
            levels = listOf(1, 1, 115, 90, 1),
            attackBonuses = listOf(0, 0, 0, 0, 0),
            defenceBonuses = listOf(100, 100, 100, 100, 300),
            otherBonuses = listOf(0, 0, 0),
        )

        private val ATTACK_KEYS = FinaleAttack.entries.map { it.key }
        private val CLEAVE_STYLES = listOf("stab", "slash", "crush")

        override fun read(r: ConfigReader): EzekielConfig {
            return EzekielConfig(
                baseHp = r.int("baseHp", 3000, Evidence.CACHE, min = 1),
                attackSpeed = r.int("attackSpeed", 5, Evidence.DOC, min = 1),
                firstAttackDelay = r.int("firstAttackDelay", 2, min = 1),
                meleeMax = r.int("meleeMax", 36, Evidence.INF, min = 0),
                cleaveHitDelay = r.int("cleaveHitDelay", 1, Evidence.INF, min = 0, max = 2),
                cleaveStyle = r.string("cleaveStyle", "slash", allowed = CLEAVE_STYLES),
                cleaveAccuracyRoll = r.bool("cleaveAccuracyRoll", true),
                magicMax = r.int("magicMax", 30, min = 0),
                magicPfmMult = r.double("magicPfmMult", 0.5, min = 0.0, max = 1.0),
                lightningHitDelay = r.int("lightningHitDelay", 2, min = 0, max = 3),
                lightningAccuracyRoll = r.bool("lightningAccuracyRoll", true),
                starBurstDamage = r.int("starBurstDamage", 20, min = 0),
                starArmWidth = r.int("starArmWidth", 3, min = 0, max = 9),
                starArmLength = r.int("starArmLength", 3, min = 0, max = 9),
                starBurstTicks = r.intList("starBurstTicks", listOf(3, 5), Evidence.COMM, size = 1..4, min = 0, max = 5),
                starMarkerTick = r.int("starMarkerTick", 2, Evidence.INF, min = 0, max = 5),
                specialRoots = r.bool("specialRoots", true),
                swordSpawnDelay = r.int("swordSpawnDelay", 1, min = 0, max = 5),
                swordChargeTicks = r.int("swordChargeTicks", 12, min = 1),
                swordFullStagger = r.intList("swordFullStagger", listOf(10, 13, 16), Evidence.COMM, size = 3..3, min = 1),
                swordFlightTicks = r.int("swordFlightTicks", 2, min = 1, max = 10),
                swordPlayerDmgBase = r.int("swordPlayerDmgBase", 15, min = 0),
                swordPlayerDmgEmpower = r.int("swordPlayerDmgEmpower", 25, min = 0),
                swordBossPct = r.int("swordBossPct", 5, min = 0, max = 100),
                swordAirWeaknessPct = r.int("swordAirWeaknessPct", 200, min = 0, max = 1000),
                swordRangedDef = r.int("swordRangedDef", 300),
                swordHp = r.int("swordHp", 80, Evidence.CACHE, min = 1),
                swordMagicAlwaysHits = r.bool("swordMagicAlwaysHits", true, Evidence.COMM),
                swordCasts = r.stringList("swordCasts", SwordCast.DEFAULT_KEYS, size = 1..32).let { keys ->
                    val parsed = keys.mapNotNull { SwordCast.parse(it) }
                    if (r.check("swordCasts", parsed.size == keys.size) { "unknown cast in ${keys.filter { SwordCast.parse(it) == null }}" }) parsed
                    else SwordCast.DEFAULT_KEYS.map { SwordCast.parse(it)!! }
                },
                swordDodge = r.bool("swordDodge", true, Evidence.COMM),
                swordPlayerHitDelay = r.int("swordPlayerHitDelay", 1, min = 0, max = 3),
                stunTicks = r.int("stunTicks", 8, min = 3),
                healPct = r.int("healPct", 5, Evidence.COMM, min = 0, max = 100),
                healRule = r.enum("healRule", HealRule.NO_SWORD_HIT, Evidence.COMM),
                healDelay = r.int("healDelay", 1, min = 0, max = 3),
                healPausesClock = r.bool("healPausesClock", true),
                enragePct = r.double("enragePct", 33.3, Evidence.COMM, min = 0.0, max = 99.0),
                finale = r.obj("finale") { readFinale(this) },
                bleed = r.enum("bleed", BleedMode.VARDORVIS, Evidence.DOC),
                bleedOnTank = r.bool("bleedOnTank", false),
                tankMessage = r.string("tankMessage", "Ezekiel turns his attention to you."),
                spawn = r.tile("spawn", StaticTile(4062, 5534, 0), Evidence.INF),
                spawnFacing = r.enum("spawnFacing", Facing.SOUTH),
                stats = r.obj("stats") {
                    Stats(
                        ezekiel = profile(this, "ezekiel", EZEKIEL_PROFILE, Evidence.DOC),
                        sword = profile(this, "sword", SWORD_PROFILE, Evidence.CACHE),
                    )
                },
                traits = CombatTraits.read(r, EZEKIEL_TRAITS),
                debugExitTile = r.tile("debugExitTile", StaticTile(4078, 5535, 0)),
            )
        }

        private fun readFinale(r: ConfigReader): FinaleSettings {
            val defaultSafe = Finale.DEFAULT_SAFE
            val combos = r.stringList("combos", Finale.DEFAULT_COMBOS.map { c -> c.joinToString(" ") { it.key } }, size = 1..16)
                .let { list ->
                    val words = list.map { line -> line.trim().split(Regex("\\s+")) }
                    val parsed = words.map { w -> w.mapNotNull { FinaleAttack.byKey(it) } }
                    val ok = words.indices.all { i -> parsed[i].isNotEmpty() && parsed[i].size == words[i].size }
                    if (r.check("combos", ok) { "each combo is a space-separated list of ${ATTACK_KEYS.joinToString()}" }) parsed
                    else Finale.DEFAULT_COMBOS
                }
            val rotations = r.intListMap("rotations",
                mapOf("r1" to Finale.DEFAULT_ROTATIONS[0], "r2" to Finale.DEFAULT_ROTATIONS[1], "r3" to Finale.DEFAULT_ROTATIONS[2]),
                listSize = 1..16, min = 1, max = 16)
            r.check("rotations", rotations.isNotEmpty() && rotations.values.all { l -> l.all { it <= combos.size } }) {
                "rotations must be non-empty and name combos 1..${combos.size}"
            }
            return FinaleSettings(
                hitDelay = r.int("hitDelay", 2, min = 1),
                nextAttack = r.int("nextAttack", 4, min = 1),
                perVolley = r.int("perVolley", 3, Evidence.COMM, min = 1),
                volleyPause = r.int("volleyPause", 3, min = 0),
                damage = r.int("damage", 25, min = 0),
                pfmMult = r.double("pfmMult", 0.5, min = 0.0, max = 1.0),
                transitionTicks = r.int("transitionTicks", 5, min = 1),
                swordDropDelay = r.int("swordDropDelay", 3, Evidence.INF, min = 0),
                bossTile = r.tile("bossTile", StaticTile(Finale.DEFAULT_BOSS_TILE.x, Finale.DEFAULT_BOSS_TILE.y, 0), Evidence.INF),
                bossFacing = r.enum("bossFacing", Facing.WEST, Evidence.INF),
                pen = r.intList("pen", listOf(Finale.DEFAULT_PEN.minX, Finale.DEFAULT_PEN.minY, Finale.DEFAULT_PEN.maxX, Finale.DEFAULT_PEN.maxY),
                    Evidence.INF, size = 4..4, check = { p -> if (p[0] > p[2] || p[1] > p[3]) "pen must be [x1, y1, x2, y2] with x1 <= x2, y1 <= y2" else null }),
                fenceTiles = r.tileList("fenceTiles", Finale.DEFAULT_FENCE_TILES.map { StaticTile(it.x, it.y, 0) }, size = 0..7),
                fenceFacing = r.enum("fenceFacing", Facing.SOUTH),
                fenceCollision = r.bool("fenceCollision", true, Evidence.INF),
                swordTiles = r.obj("swordTiles") {
                    mapOf(
                        FinaleSword.LEFT to tile("left", StaticTile(4063, 5538, 0)),
                        FinaleSword.RIGHT to tile("right", StaticTile(4063, 5532, 0)),
                        FinaleSword.FRONT to tile("front", StaticTile(4066, 5535, 0)),
                    )
                },
                swordFacing = r.enum("swordFacing", Facing.WEST),
                swordVisual = r.enum("swordVisual", SwordVisual.NPC_SEQ, Evidence.INF),
                combos = combos,
                rotations = rotations,
                safeTiles = r.obj("safeTiles") {
                    FinaleAttack.entries.associateWith { a ->
                        tileList(a.key, defaultSafe.getValue(a).map { StaticTile(it.x, it.y, 0) }, Evidence.COMM, size = 0..9)
                    }
                },
            )
        }

        private fun profile(r: ConfigReader, key: String, default: Profile, evidence: Evidence): Profile = r.obj(key) {
            Profile(
                levels = intList("levels", default.levels, evidence, size = 5..5, min = 1),
                attackBonuses = intList("attackBonuses", default.attackBonuses, evidence, size = 5..5),
                defenceBonuses = intList("defenceBonuses", default.defenceBonuses, evidence, size = 5..5),
                otherBonuses = intList("otherBonuses", default.otherBonuses, evidence, size = 3..3),
            )
        }
    }
}

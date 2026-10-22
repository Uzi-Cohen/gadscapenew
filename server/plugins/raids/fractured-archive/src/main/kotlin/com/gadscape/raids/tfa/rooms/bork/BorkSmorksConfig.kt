package com.gadscape.raids.tfa.rooms.bork

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
 * `bork_smorks.json` (spec §4.3 "House defaults"). The keys of the spec block (`tempo`, `bork.flatArmour` ..
 * `bork.enragedLargeBeats`, `smorks.flatArmour` .. `smorks.enragedDmgMult`) are verbatim; every other key is one the
 * implementation needs and names its evidence here (see also `raids4-impl/ENC-bork.md` §4).
 *
 * Tick counts are server ticks (1 t = 30 client cycles); seq lengths and impact frames are from
 * `raids4/bork-smorks/seqlen.txt` and the seq configs (sound frames) in `audit/raids4/dump/cfg.diff`.
 */
data class BorkSmorksConfig(
    /** HOUSE (DOC "faster at several thresholds", COMM "independent per NPC"): each NPC on its own HP. */
    val tempo: List<TempoTier>,
    val bork: Bork,
    val smorks: Smorks,
    val projectiles: Projectiles,
    val wave: Wave,
    val shadow: Shadow,
    /**
     * HOUSE: the room completes once both are dead **and** their death sequences have played (Bork despawns 7 t after
     * his death, the Smorks 6 t); false = the moment the second one dies.
     */
    val completeAfterDeathAnims: Boolean,
) {

    /** One drum tier: an NPC whose HP is above [abovePct]% of its max attacks every [speed] ticks. */
    data class TempoTier(val abovePct: Int, val speed: Int)

    data class Bork(
        /** CACHE npc 16640 hitpoints = 1500 (the 2-player base; §2.6: 1500 / 2141 / ... / 5345). */
        val baseHp: Int,
        /** DOC 12 flat armour; HOUSE 10 at or below [armourStepPct] (Xara: "seems to go down along with his hp"). */
        val flatArmour: Int,
        val flatArmourBelow66: Int,
        /** HOUSE: the HP % at or below which [flatArmourBelow66] applies (the first drum threshold). */
        val armourStepPct: Int,
        /** DOC 2 magic resistance (0 while dazed, like the flat armour). */
        val magicResist: Int,
        /** HOUSE: NPC formula floor(((200+9)×(40+64)+320)/640) = 34, crush with an accuracy roll. */
        val beatMax: Int,
        /** HOUSE R_max: the large beat grows to min(distance to the target, this). */
        val largeBeatRadius: Int,
        /** HOUSE 0.5: Protect from Melee halves every Bork hit (beats, stomps, gong). */
        val pfmMult: Double,
        val stompMax: Int,
        /** INF 4 stomps per sequence (COMM "bork will do 4 stomp attacks"). */
        val stomps: Int,
        /**
         * INF from the synth frames: stomp impacts in ticks after the start of the 2 t daze entry 14549 (impacts at
         * +2.83 / +5.87 / +8.83 / +11.87). After the 1 t idle entry 14556 every impact is 1 t earlier.
         */
        val stompTicks: List<Int>,
        /** HOUSE: the gong barrage hits once per tick this many times (14547 loops, one impact synth per loop). */
        val gongHits: Int,
        val gongMax: Int,
        /** HOUSE 50/50 stomp or gong after a missed heavy ball (and after a daze when [gongAfterDaze]). */
        val gongChance: Double,
        /** Spec [C+] U73: the gong can also follow a daze (seq 14546 exists). */
        val gongAfterDaze: Boolean,
        /** HOUSE: Smorks dead → 2 large beats (radius [largeBeatRadius]) then a stomp sequence, repeat. */
        val enragedLargeBeats: Int,
        /** HOUSE: ticks from the fight start to Bork's first beat. */
        val firstAttackDelay: Int,
        /** CACHE seq 14542: impact synth 12768 at cycle 58 → the beat lands 2 t after the swing starts. */
        val beatHitDelay: Int,
        /** INF: daze length = this × Bork's current attack speed (7 → 14 t, 6 → 12 t, 4 → 8 t = the 3 daze seqs). */
        val dazeTicksPerSpeed: Int,
        /** CACHE: 14549 = 2 t, 14556 = 1 t, 14550 = 6 t (2 stomps), 14551 = 7 t (2 stomps and out). */
        val stompInFromDazeTicks: Int,
        val stompInFromIdleTicks: Int,
        val stompLoopTicks: Int,
        val stompOutTicks: Int,
        /** CACHE: 14546 / 14557 = 1 t; 14547 impact synth at cycle 22 of each 1 t loop; 14548 = 1 t. */
        val gongInTicks: Int,
        val gongOutTicks: Int,
        /** HOUSE: which Bork hits roll accuracy (the spec gives the roll for beats; stomps and gong follow it). */
        val accuracy: Accuracy,
        /** Spec: despawn 7 t after the death seq 14541 (6.5 t) starts. */
        val deathDespawnTicks: Int,
        /** HOUSE: SW tile of his 4x4 and his facing at build time (east of the cart, facing the transporter side). */
        val spawn: StaticTile,
        val spawnFacing: String,
        val texts: BorkTexts,
        /** DOC wiki infobox "+60% (empirical)". */
        val xpModifier: Double,
        val stats: Stats,
        /** DOC Monster Inspect: earth 40%, ranged 80/50/5, freeze/poison/venom/cannon immune, 60% floor (180). */
        val traits: CombatTraits,
    )

    data class Accuracy(val beat: Boolean, val stomp: Boolean, val gong: Boolean)

    /** Overheads (DOC wiki Strategies, exact strings). `smash` takes the target's name for `%s`. */
    data class BorkTexts(
        val smash: String,
        val veryAngry: String,
        val crushNow: String,
        val stompTime: String,
        /** HOUSE: the enraged (Smorks dead) stomp has no known line; the from-idle stomp line is used. */
        val enragedStomp: String,
    )

    data class Smorks(
        /** CACHE npc 16641 hitpoints = 600 (§2.6: 600 / 856 / ... / 2138). */
        val baseHp: Int,
        /** DOC 2 flat armour. */
        val flatArmour: Int,
        /** HOUSE: ranged floor(((185+9)×(30+64)+320)/640) = 28; magic 21 × 1.35 = 28. */
        val autoMax: Int,
        /** COMM "3 auto attacks then a special". */
        val specialEvery: Int,
        val incendiaryDmg: Int,
        /** HOUSE "moderate damage": % of Bork's scaled max HP when his 4x4 is in the impact square. */
        val incendiaryBorkPct: Int,
        /** HOUSE: 7 oil tiles inside the [oilArea]×[oilArea] square around the impact, 30 t each. */
        val oilTiles: Int,
        val oilArea: Int,
        val oilLifetime: Int,
        /** DOC "6-7 damage per tick" + a burn. */
        val oilDmg: IntRange,
        val heavyDmg: Int,
        /** INF: the heavy ball lands 4 t after firing (= its landing shadow 4442, seq 7817 = 120 cycles). */
        val heavyFlight: Int,
        /** HOUSE: Bork dead → attack speed 3, every player targeted, max hits × 1.25, incendiary only. */
        val enragedSpeed: Int,
        val enragedDmgMult: Double,
        /** HOUSE: ticks from the fight start to the first volley. */
        val firstAttackDelay: Int,
        /** HOUSE: side of the cannonball impact square (3 = 3x3). */
        val impactArea: Int,
        /** INF weak: the incendiary lands 2 t after firing (its marker 4443 lasts 56 cycles = 1.87 t). */
        val incendiaryFlight: Int,
        /** INF: burn stacks per tick on oil (wiki Burn: "starts at 2" = 2 stacks by the first burn hit). */
        val burnStacks: Int,
        /** HOUSE: loc type of the oil puddle 62544 (10 = centrepiece; the arena's ground decor stays). */
        val oilLocType: Int,
        /** COMM order "burny gloop" first, then "big bomb". */
        val firstSpecial: String,
        /** HOUSE (spec): the Smorks despawn 6 t after dying; the cart locs 26209 stay. */
        val deathDespawnTicks: Int,
        /** CACHE: the NPC stands on the cart (the 9 × 26209 at 4190..4192, 5598..5600). */
        val spawn: StaticTile,
        val spawnFacing: String,
        val texts: SmorksTexts,
        /** HOUSE: +60% like Bork. */
        val xpModifier: Double,
        val stats: Stats,
        /** DOC Monster Inspect: earth 85%, ranged 80/150/80, no defence floor, freeze/poison/venom/cannon immune. */
        val traits: CombatTraits,
    )

    /** COMM osrsguide ("*burny gloop*", "*big bomb. Bork watch out*"); exact casing unknown. */
    data class SmorksTexts(val incendiary: String, val heavy: String)

    /** NPC combat stats for the engine's combat definitions (CACHE params + DOC Monster Inspect). */
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

    /**
     * A classic projectile that follows its target: arrival cycle = delay + perTile × distance (Zenyte `Projectile`;
     * heights in the server's units, slope in client units 0-64).
     */
    data class ProjectileSpec(val startHeight: Int, val endHeight: Int, val delay: Int, val slope: Int, val perTile: Int) {
        fun cycles(distance: Int): Int = delay + perTile * distance

        /** Ticks until the hit lands: the first tick boundary at or after the arrival (at least 1). */
        fun flightTicks(distance: Int): Int = maxOf(1, (cycles(distance) + 29) / 30)
    }

    /** A lobbed cannonball onto a tile: it always lands a fixed number of ticks after firing (see the flights). */
    data class ArcSpec(val startHeight: Int, val endHeight: Int, val delay: Int, val slope: Int)

    /** HOUSE arcs (no live numbers): bones and potions low, cannonballs high. */
    data class Projectiles(val bone: ProjectileSpec, val potion: ProjectileSpec, val incendiary: ArcSpec, val heavy: ArcSpec)

    /**
     * The shockwave dust (NOTES §6/§8): one graphic per hit tile, delayed by [cyclesPerTile] per ring from Bork's 4x4.
     * [angleOffset] turns the whole mapping (the model's base direction is not known): 0, 90, 180 or 270.
     */
    data class Wave(val dust: Boolean, val cyclesPerTile: Int, val angleOffset: Int)

    /**
     * INF cart shadow: `"bresenham"` (spec: the Bresenham line from Bork's middle tile to the tile crosses the cart's
     * 3x3) or `"segment"` (the exact segment between the two centres crosses it).
     */
    data class Shadow(val mode: String)

    companion object SPEC : TfaConfigSpec<BorkSmorksConfig>("bork_smorks.json") {

        @JvmField
        val DEFAULT_TEMPO = listOf(TempoTier(66, 7), TempoTier(33, 6), TempoTier(0, 4))

        /** §4.3 combat profile: earth 40%, ranged 80/50/5, flat armour 12, magic resistance 2, freeze-immune, floor 60%. */
        @JvmField
        val BORK_TRAITS: CombatTraits = CombatTraits.BOSS_DEFAULT.copy(
            weakness = Element.EARTH, weaknessPct = 40, rangedDef = RangedDefence(80, 50, 5),
            freezeResistPct = 100, flatArmour = 12, magicResist = 2,
        )

        /** §4.3 the Smorks: earth 85%, ranged 80/150/80, flat armour 2, no defence floor (DOC hotfix), freeze-immune. */
        @JvmField
        val SMORKS_TRAITS: CombatTraits = CombatTraits.BOSS_DEFAULT.copy(
            defenceFloorPct = null, weakness = Element.EARTH, weaknessPct = 85, rangedDef = RangedDefence(80, 150, 80),
            freezeResistPct = 100, flatArmour = 2, magicResist = 0,
        )

        /** CACHE npc 16640 + DOC Monster Inspect (melee attack +100 is server-only; used for every melee style). */
        @JvmField
        val BORK_STATS = Stats(
            levels = listOf(200, 200, 300, 90, 200),
            attackBonuses = listOf(100, 100, 100, 0, 0),
            defenceBonuses = listOf(140, 130, 10, 300, 50),
            otherBonuses = listOf(40, 0, 0),
        )

        /** CACHE npc 16641 (magic +185 / +35%, ranged +250 / str +30) + DOC standard ranged defence 150. */
        @JvmField
        val SMORKS_STATS = Stats(
            levels = listOf(1, 1, 220, 185, 185),
            attackBonuses = listOf(0, 0, 0, 185, 250),
            defenceBonuses = listOf(220, 170, 160, 400, 150),
            otherBonuses = listOf(0, 30, 35),
        )

        private val FACINGS = listOf("north", "east", "south", "west")

        override fun read(r: ConfigReader): BorkSmorksConfig {
            var tempo = r.objList("tempo", DEFAULT_TEMPO, HOUSE, size = 1..10) {
                TempoTier(int("abovePct", 0, HOUSE, 0, 99), int("speed", 7, HOUSE, 1, 30))
            }
            val ordered = tempo.zipWithNext().all { (a, b) -> a.abovePct > b.abovePct } && tempo.last().abovePct == 0
            if (!r.check("tempo", ordered) { "tiers must have strictly decreasing abovePct ending at 0; using the default table" }) {
                tempo = DEFAULT_TEMPO
            }
            val bork = r.obj("bork") { readBork(this) }
            val smorks = r.obj("smorks") { readSmorks(this) }
            return BorkSmorksConfig(
                tempo = tempo,
                bork = bork,
                smorks = smorks,
                projectiles = r.obj("projectiles") {
                    Projectiles(
                        bone = obj("bone") { readProjectile(this, ProjectileSpec(70, 31, 30, 15, 5)) },
                        potion = obj("potion") { readProjectile(this, ProjectileSpec(70, 31, 30, 15, 5)) },
                        incendiary = obj("incendiary") { readArc(this, ArcSpec(90, 0, 15, 40)) },
                        heavy = obj("heavy") { readArc(this, ArcSpec(90, 0, 15, 50)) },
                    )
                },
                wave = r.obj("wave") {
                    Wave(
                        dust = bool("dust", true, INF),
                        cyclesPerTile = int("cyclesPerTile", 4, HOUSE, 0, 60),
                        angleOffset = int("angleOffset", 0, INF, 0, 270, check = { if (it % 90 != 0) "must be 0, 90, 180 or 270" else null }),
                    )
                },
                shadow = r.obj("shadow") {
                    Shadow(mode = string("mode", "bresenham", INF, allowed = listOf("bresenham", "segment")))
                },
                completeAfterDeathAnims = r.bool("completeAfterDeathAnims", true, HOUSE),
            )
        }

        private fun readBork(r: ConfigReader): Bork {
            val flatArmour = r.int("flatArmour", 12, DOC, 0, 1000)
            val magicResist = r.int("magicResist", 2, DOC, 0, 1000)
            val stomps = r.int("stomps", 4, INF, 1, 20)
            var stompTicks = r.intList("stompTicks", listOf(3, 6, 9, 12), INF, size = 1..20, min = 1, max = 100,
                check = { list -> if (list.zipWithNext().any { (a, b) -> a >= b }) "must be strictly increasing" else null })
            if (!r.check("stompTicks", stompTicks.size == stomps) { "has ${stompTicks.size} entries but stomps = $stomps; using every 3 t" }) {
                stompTicks = List(stomps) { 3 * (it + 1) }
            }
            val traits = CombatTraits.read(r, BORK_TRAITS)
            r.check("traits.flatArmour", traits.flatArmour == flatArmour && traits.magicResist == magicResist) {
                "traits.flatArmour/magicResist differ from bork.flatArmour/magicResist; the bork.* keys are used"
            }
            return Bork(
                baseHp = r.int("baseHp", 1500, CACHE, 1, 100_000),
                flatArmour = flatArmour,
                flatArmourBelow66 = r.int("flatArmourBelow66", 10, HOUSE, 0, 1000),
                armourStepPct = r.int("armourStepPct", 66, HOUSE, 0, 100),
                magicResist = magicResist,
                beatMax = r.int("beatMax", 34, HOUSE, 0, 200),
                largeBeatRadius = r.int("largeBeatRadius", 5, HOUSE, 1, 15),
                pfmMult = r.double("pfmMult", 0.5, HOUSE, 0.0, 1.0),
                stompMax = r.int("stompMax", 40, HOUSE, 0, 200),
                stomps = stomps,
                stompTicks = stompTicks,
                gongHits = r.int("gongHits", 8, HOUSE, 1, 30),
                gongMax = r.int("gongMax", 12, HOUSE, 0, 200),
                gongChance = r.double("gongChance", 0.5, HOUSE, 0.0, 1.0),
                gongAfterDaze = r.bool("gongAfterDaze", true, INF),
                enragedLargeBeats = r.int("enragedLargeBeats", 2, HOUSE, 0, 20),
                firstAttackDelay = r.int("firstAttackDelay", 4, HOUSE, 1, 30),
                beatHitDelay = r.int("beatHitDelay", 2, CACHE, 0, 5),
                dazeTicksPerSpeed = r.int("dazeTicksPerSpeed", 2, INF, 1, 10),
                stompInFromDazeTicks = r.int("stompInFromDazeTicks", 2, CACHE, 0, 10),
                stompInFromIdleTicks = r.int("stompInFromIdleTicks", 1, CACHE, 0, 10),
                stompLoopTicks = r.int("stompLoopTicks", 6, CACHE, 1, 30),
                stompOutTicks = r.int("stompOutTicks", 7, CACHE, 1, 30),
                gongInTicks = r.int("gongInTicks", 1, CACHE, 0, 10),
                gongOutTicks = r.int("gongOutTicks", 1, CACHE, 0, 10),
                accuracy = r.obj("accuracy") {
                    Accuracy(beat = bool("beat", true, DOC), stomp = bool("stomp", true, HOUSE), gong = bool("gong", true, HOUSE))
                },
                deathDespawnTicks = r.int("deathDespawnTicks", 7, CACHE, 1, 30),
                spawn = r.tile("spawn", StaticTile(4194, 5597, 0), HOUSE),
                spawnFacing = r.string("spawnFacing", "west", HOUSE, allowed = FACINGS),
                texts = r.obj("texts") {
                    BorkTexts(
                        smash = string("smash", "Bork smash %s now.", DOC, check = { if ("%s" !in it) "must contain %s for the name" else null }),
                        veryAngry = string("veryAngry", "Bork very angry!", DOC),
                        crushNow = string("crushNow", "Bork crush now.", DOC),
                        stompTime = string("stompTime", "Bork think it stomp time now!", DOC),
                        enragedStomp = string("enragedStomp", "Bork think it stomp time now!", HOUSE),
                    )
                },
                xpModifier = r.double("xpModifier", 1.6, DOC, 0.0, 10.0),
                stats = readStats(r, BORK_STATS),
                traits = traits.copy(flatArmour = flatArmour, magicResist = magicResist),
            )
        }

        private fun readSmorks(r: ConfigReader): Smorks {
            val flatArmour = r.int("flatArmour", 2, DOC, 0, 1000)
            val traits = CombatTraits.read(r, SMORKS_TRAITS)
            r.check("traits.flatArmour", traits.flatArmour == flatArmour) {
                "traits.flatArmour differs from smorks.flatArmour; smorks.flatArmour is used"
            }
            return Smorks(
                baseHp = r.int("baseHp", 600, CACHE, 1, 100_000),
                flatArmour = flatArmour,
                autoMax = r.int("autoMax", 28, HOUSE, 0, 200),
                specialEvery = r.int("specialEvery", 4, COMM, 1, 20),
                incendiaryDmg = r.int("incendiaryDmg", 30, HOUSE, 0, 200),
                incendiaryBorkPct = r.int("incendiaryBorkPct", 5, HOUSE, 0, 100),
                oilTiles = r.int("oilTiles", 7, HOUSE, 0, 25),
                oilArea = r.int("oilArea", 5, HOUSE, 1, 9, check = { if (it % 2 == 0) "must be odd" else null }),
                oilLifetime = r.int("oilLifetime", 30, HOUSE, 1, 500),
                oilDmg = r.intRange("oilDmg", 6..7, DOC, 0, 99),
                heavyDmg = r.int("heavyDmg", 35, HOUSE, 0, 200),
                heavyFlight = r.int("heavyFlight", 4, INF, 1, 20),
                enragedSpeed = r.int("enragedSpeed", 3, HOUSE, 1, 30),
                enragedDmgMult = r.double("enragedDmgMult", 1.25, HOUSE, 0.0, 10.0),
                firstAttackDelay = r.int("firstAttackDelay", 7, HOUSE, 1, 30),
                impactArea = r.int("impactArea", 3, HOUSE, 1, 9, check = { if (it % 2 == 0) "must be odd" else null }),
                incendiaryFlight = r.int("incendiaryFlight", 2, INF, 1, 20),
                burnStacks = r.int("burnStacks", 1, INF, 0, 5),
                oilLocType = r.int("oilLocType", 10, HOUSE, 0, 22),
                firstSpecial = r.string("firstSpecial", "incendiary", COMM, allowed = listOf("incendiary", "heavy")),
                deathDespawnTicks = r.int("deathDespawnTicks", 6, HOUSE, 1, 30),
                spawn = r.tile("spawn", StaticTile(4190, 5598, 0), CACHE),
                spawnFacing = r.string("spawnFacing", "west", HOUSE, allowed = FACINGS),
                texts = r.obj("texts") {
                    SmorksTexts(
                        incendiary = string("incendiary", "Burny gloop!", COMM),
                        heavy = string("heavy", "Big bomb. Bork watch out!", COMM),
                    )
                },
                xpModifier = r.double("xpModifier", 1.6, HOUSE, 0.0, 10.0),
                stats = readStats(r, SMORKS_STATS),
                traits = traits.copy(flatArmour = flatArmour),
            )
        }

        private fun readStats(r: ConfigReader, default: Stats): Stats =
            r.obj("stats") {
                Stats(
                    levels = intList("levels", default.levels, CACHE, size = 5..5, min = 0, max = 5000),
                    attackBonuses = intList("attackBonuses", default.attackBonuses, DOC, size = 5..5, min = -1000, max = 1000),
                    defenceBonuses = intList("defenceBonuses", default.defenceBonuses, DOC, size = 5..5, min = -1000, max = 1000),
                    otherBonuses = intList("otherBonuses", default.otherBonuses, CACHE, size = 3..3, min = -1000, max = 1000),
                )
            }

        private fun readProjectile(r: ConfigReader, default: ProjectileSpec): ProjectileSpec = ProjectileSpec(
            startHeight = r.int("startHeight", default.startHeight, HOUSE, 0, 255),
            endHeight = r.int("endHeight", default.endHeight, HOUSE, 0, 255),
            delay = r.int("delay", default.delay, HOUSE, 0, 300),
            slope = r.int("slope", default.slope, HOUSE, 0, 64),
            perTile = r.int("perTile", default.perTile, HOUSE, 0, 60),
        )

        private fun readArc(r: ConfigReader, default: ArcSpec): ArcSpec = ArcSpec(
            startHeight = r.int("startHeight", default.startHeight, HOUSE, 0, 255),
            endHeight = r.int("endHeight", default.endHeight, HOUSE, 0, 255),
            delay = r.int("delay", default.delay, Evidence.INF, 0, 300),
            slope = r.int("slope", default.slope, HOUSE, 0, 64),
        )
    }
}

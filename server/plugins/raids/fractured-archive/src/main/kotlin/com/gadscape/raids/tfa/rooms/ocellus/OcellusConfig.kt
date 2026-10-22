package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.Evidence.CACHE
import com.gadscape.raids.tfa.config.Evidence.COMM
import com.gadscape.raids.tfa.config.Evidence.DOC
import com.gadscape.raids.tfa.config.Evidence.HOUSE
import com.gadscape.raids.tfa.config.Evidence.INF
import com.gadscape.raids.tfa.config.TfaConfigSpec
import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.raid.StaticTile

/**
 * `ocellus.json` (spec §4.8 "House defaults"): every number of the Ocellus room. The spec's JSON block is the
 * core of the file; the keys the spec does not list (tiles, timings taken from seq lengths, projectile shapes,
 * crack rotations, the P2 lane plumbing) are additions of WU-27, documented in `raids4-impl/ENC-ocellus.md`.
 *
 * Evidence: CACHE = cache 2746 (npc config, seq lengths in `raids4/ocellus/seq_ocellus.txt`); DOC = OSRS wiki
 * Ocellus / Strategies (rev 15379038 / 15379033); COMM = first-48h guides; INF/HOUSE = our choice.
 */
data class OcellusConfig(
    val hp: Hp,
    val traits: Traits,
    val arena: Arena,
    val p1: P1,
    val p2: P2,
    val transitions: Transitions,
    val projectiles: Projectiles,
    val p3: P3,
    val p4: P4,
    val p3InheritP1Pfm: Boolean,
    val p3PfmWhileMoving: Boolean,
    val planeShift: Int,
    val combatLevel: CombatLevel,
    val death: Death,
) {

    /** 2-player base HP of each form [CACHE npc 16674 / 16676 / 16677]; scaled with `Scaling` (§2.6). */
    data class Hp(val p1: Int, val blade: Int, val p3: Int)

    data class Traits(val p1: CombatTraits, val blade: CombatTraits, val p3: CombatTraits)

    /** Static tiles on cache plane 1 (before [planeShift]): SW tiles of his 3x3. */
    data class Arena(
        val introTile: StaticTile,
        val centreTile: StaticTile,
        /** In quadrant order SW, NW, NE, SE. */
        val anchorTiles: List<StaticTile>,
    )

    data class P1(
        val speed: Int,
        val autoMax: Int,
        val anchors: List<String>,
        val tendrilInterval: Int,
        val tendrilLife: Int,
        val tendrilsPerAnchor: Int,
        val blockDmg: Int,
        val meterPerUnblocked: Int,
        val slamRadius: Int,
        val slamMax: Int,
        val thresholds: List<Int>,
        val annihilationAt: Int,
        val annihilation: IntRange,
        val deathAwaits: IntRange,
        val bindable: Boolean,
        // ---- WU-27 additions -----------------------------------------------------------------------------------
        val meterMax: Int,
        val firstAutoDelay: Int,
        val castTicks: Int,
        val autosWhileChannelling: Boolean,
        val arrivalPause: Int,
        val slamDelay: Int,
        val postSlamPause: Int,
        val walkTimeout: Int,
        val centrePairGap: Int,
        val chargeTicks: Int,
        val releaseTicks: Int,
        val teleportTicks: Int,
        val crackSegment: Int,
        /** Direction (n, ne, e, se, s, sw, w, nw) → index 0..3 into the 0/90/180/270 crack spot family. */
        val crackRotations: Map<String, Int>,
        /** N, E, S, W → index 0..3 into the slam floor spots 4262-4265. */
        val slamFloorRotations: Map<String, Int>,
        val slamFloorOffset: Int,
        val chargeSpotEvery: Int,
    )

    data class Barrage(val speed: Int, val col: Int, val row: Int)

    data class P2(
        val barrages: List<Barrage>,
        val randomize: Boolean,
        val volleys: Int,
        val laneDmg: IntRange,
        val pools: Int,
        val poolDmg: IntRange,
        val portalDelay: Int,
        val quadrantAttackDelay: Int,
        val quadrantAttackDmg: Int,
        val bladeSkipsBarrages: Boolean,
        // ---- WU-27 additions -----------------------------------------------------------------------------------
        /** Quadrant of barrage 1, 2, 3; the party ends in the quadrant after the last one (P3 quadrant 1). */
        val quadrants: List<String>,
        val firstVolleyDelay: Int,
        val shotTicks: Int,
        val poolTicks: Int,
        val barrageGap: Int,
        val gatherIntoQuadrant: Boolean,
        val floorPortalSize: Int,
    )

    /** Transition lengths in ticks, from the seq lengths (cycles / 30) [CACHE]. */
    data class Transitions(
        val p1ToP2: Int,
        val p2Form: Int,
        val bladeDrop: Int,
        val p2ToP3Part1: Int,
        val p2ToP3Part2: Int,
        val armSpotDelay: Int,
    )

    /**
     * A classic projectile: flight = `delay + base + perTile × distance` client cycles (30 per tick); heights in
     * the server's units (×4 on the wire).
     */
    data class ProjectileSpec(val startHeight: Int, val endHeight: Int, val delay: Int, val angle: Int, val base: Int, val perTile: Int) {
        fun cycles(distance: Int): Int = delay + base + perTile * distance

        /** Ticks until the projectile lands (rounded up). */
        fun ticks(distance: Int): Int = (cycles(distance) + 29) / 30
    }

    data class Projectiles(val p1Magic: ProjectileSpec, val p1Ranged: ProjectileSpec, val p1Release: ProjectileSpec, val p2Shot: ProjectileSpec)

    data class P3(
        val speed: Int, val meleeMax: Int, val waveDmg: IntRange, val waveWidth: Int, val voidShot: IntRange,
        val spinCheckDmg: Int, val sawMax: Int, val sawPfmMult: Double, val untetherEvery: Int, val vineTicks: Int,
        val vineDmg: IntRange, val addCharge: Int, val riftDetonate: IntRange, val siphonHealPct: Int,
        val augmenterBuffPct: Int, val insolenceDmg: IntRange, val crackDmg: Int, val crackLife: Int,
        val untetherBombs: IntRange, val untether: Int, val obliterationTicks: Int, val thresholds: List<Int>,
        val pools: Int, val poolDmg: Int, val poolLife: Int,
    )

    data class P4(
        val chainCount: Int, val chainInterval: Int, val chainDmg: IntRange, val damageTakenMult: Double,
        val orbStages: List<Int>, val orbHeal: List<Double>, val orbLife: Int, val blast: Int, val blastPrayed: IntRange,
        val soulSplitPct: Int, val voidFloorDmg: Int, val deflectReflectPct: Int,
    )

    data class CombatLevel(val p1: Int?, val p3: Int?)

    /** Death seq 14872 is 440 cycles = 14.67 t, spec uses 15 t [CACHE]. */
    data class Death(val ticks: Int)

    companion object SPEC : TfaConfigSpec<OcellusConfig>("ocellus.json") {

        val QUADRANT_NAMES = listOf("SW", "NW", "NE", "SE")
        private val DIRECTIONS = listOf("n", "ne", "e", "se", "s", "sw", "w", "nw")

        /** §4.8 combat profile: P1 floor 129 = ceil(0.6 × 215), demonbane 80%, ranged 50 / 135 / 50 [DOC hotfix + wiki]. */
        val P1_TRAITS: CombatTraits = CombatTraits.BOSS_DEFAULT.copy(
            rangedDef = RangedDefence(50, 135, 50), demonbanePct = 80, freezeResistPct = 0,
        )

        /** The Blade (P2 hitbox): no defence floor, ranged 100 / 110 / 80 [DOC]; not a demon. */
        val BLADE_TRAITS: CombatTraits = CombatTraits.ADD_DEFAULT.copy(rangedDef = RangedDefence(100, 110, 80))

        /** P3/P4: floor 150 = ceil(0.6 × 250), demonbane 80%, ranged 250 / 250 / 250 [DOC]. */
        val P3_TRAITS: CombatTraits = CombatTraits.BOSS_DEFAULT.copy(
            rangedDef = RangedDefence(250, 250, 250), demonbanePct = 80, freezeResistPct = 100,
        )

        private val BARRAGES = listOf(Barrage(4, 2, 2), Barrage(3, 1, 3), Barrage(2, 3, 1))

        override fun read(r: ConfigReader): OcellusConfig = OcellusConfig(
            hp = r.obj("hp") {
                Hp(
                    p1 = int("p1", 2500, CACHE, min = 1),
                    blade = int("blade", 1000, CACHE, min = 1),
                    p3 = int("p3", 5250, CACHE, min = 1),
                )
            },
            traits = r.obj("traits") {
                Traits(
                    p1 = CombatTraits.read(this, P1_TRAITS, "p1"),
                    blade = CombatTraits.read(this, BLADE_TRAITS, "blade"),
                    p3 = CombatTraits.read(this, P3_TRAITS, "p3"),
                )
            },
            arena = r.obj("arena") {
                Arena(
                    introTile = tile("introTile", StaticTile(4126, 5668, 1), HOUSE),
                    centreTile = tile("centreTile", StaticTile(4126, 5672, 1), HOUSE),
                    anchorTiles = tileList(
                        "anchorTiles",
                        listOf(StaticTile(4119, 5665, 1), StaticTile(4119, 5679, 1), StaticTile(4133, 5679, 1), StaticTile(4133, 5665, 1)),
                        HOUSE, size = 4..4,
                    ),
                )
            },
            p1 = r.obj("p1") {
                P1(
                    speed = int("speed", 5, CACHE, min = 1, max = 20),
                    autoMax = int("autoMax", 30, HOUSE, min = 0, max = 200),
                    anchors = stringList("anchors", QUADRANT_NAMES, HOUSE, size = 1..8, allowed = QUADRANT_NAMES),
                    tendrilInterval = int("tendrilInterval", 3, HOUSE, min = 1, max = 20),
                    tendrilLife = int("tendrilLife", 4, INF, min = 1, max = 20),
                    tendrilsPerAnchor = int("tendrilsPerAnchor", 7, DOC, min = 1, max = 8),
                    blockDmg = int("blockDmg", 5, HOUSE, min = 0, max = 99),
                    meterPerUnblocked = int("meterPerUnblocked", 8, HOUSE, min = 0, max = 100),
                    slamRadius = int("slamRadius", 2, DOC, min = 0, max = 10),
                    slamMax = int("slamMax", 35, DOC, min = 0, max = 200),
                    thresholds = intList("thresholds", listOf(66, 25), DOC, size = 0..4, min = 1, max = 99,
                        check = { if (it != it.sortedDescending()) "must be in descending order" else null }),
                    annihilationAt = int("annihilationAt", 50, HOUSE, min = 0, max = 100),
                    annihilation = intRange("annihilation", 80..90, COMM, min = 0, max = 200),
                    deathAwaits = intRange("deathAwaits", 17..18, DOC, min = 0, max = 200),
                    bindable = bool("bindable", true, COMM),
                    meterMax = int("meterMax", 100, HOUSE, min = 1, max = 65535),
                    firstAutoDelay = int("firstAutoDelay", 5, HOUSE, min = 1, max = 50),
                    castTicks = int("castTicks", 2, INF, min = 0, max = 10),
                    autosWhileChannelling = bool("autosWhileChannelling", true, HOUSE),
                    arrivalPause = int("arrivalPause", 1, HOUSE, min = 0, max = 20),
                    slamDelay = int("slamDelay", 1, DOC, min = 0, max = 10),
                    postSlamPause = int("postSlamPause", 2, HOUSE, min = 0, max = 20),
                    walkTimeout = int("walkTimeout", 30, HOUSE, min = 1, max = 200),
                    centrePairGap = int("centrePairGap", 1, HOUSE, min = 0, max = 10),
                    chargeTicks = int("chargeTicks", 4, CACHE, min = 1, max = 20),
                    releaseTicks = int("releaseTicks", 3, CACHE, min = 1, max = 20),
                    teleportTicks = int("teleportTicks", 2, CACHE, min = 1, max = 10),
                    crackSegment = int("crackSegment", 5, INF, min = 1, max = 25),
                    crackRotations = intMap(
                        "crackRotations",
                        mapOf("n" to 0, "ne" to 0, "e" to 1, "se" to 1, "s" to 2, "sw" to 2, "w" to 3, "nw" to 3),
                        INF, min = 0, max = 3,
                    ).also { m -> check("crackRotations", DIRECTIONS.all { it in m }) { "needs all of $DIRECTIONS" } },
                    slamFloorRotations = intMap("slamFloorRotations", mapOf("n" to 0, "e" to 1, "s" to 2, "w" to 3), INF, min = 0, max = 3)
                        .also { m -> check("slamFloorRotations", listOf("n", "e", "s", "w").all { it in m }) { "needs n, e, s and w" } },
                    slamFloorOffset = int("slamFloorOffset", 3, INF, min = 0, max = 10),
                    chargeSpotEvery = int("chargeSpotEvery", 2, CACHE, min = 1, max = 10),
                )
            },
            p2 = r.obj("p2") {
                P2(
                    barrages = objList("barrages", BARRAGES, HOUSE, size = 1..8) {
                        Barrage(
                            speed = int("speed", 4, min = 1, max = 20),
                            col = int("col", 2, min = 0, max = 4),
                            row = int("row", 2, min = 0, max = 4),
                        )
                    },
                    randomize = bool("randomize", true, HOUSE),
                    volleys = int("volleys", 5, HOUSE, min = 1, max = 20),
                    laneDmg = intRange("laneDmg", 25..35, HOUSE, min = 0, max = 200),
                    pools = int("pools", 8, HOUSE, min = 0, max = 40),
                    poolDmg = intRange("poolDmg", 15..25, HOUSE, min = 0, max = 200),
                    portalDelay = int("portalDelay", 6, HOUSE, min = 1, max = 30),
                    quadrantAttackDelay = int("quadrantAttackDelay", 8, HOUSE, min = 1, max = 40),
                    quadrantAttackDmg = int("quadrantAttackDmg", 60, HOUSE, min = 0, max = 200),
                    bladeSkipsBarrages = bool("bladeSkipsBarrages", true, HOUSE),
                    quadrants = stringList("quadrants", listOf("SW", "NW", "NE"), COMM, size = 1..8, allowed = QUADRANT_NAMES),
                    firstVolleyDelay = int("firstVolleyDelay", 2, HOUSE, min = 1, max = 20),
                    shotTicks = int("shotTicks", 4, CACHE, min = 1, max = 10),
                    poolTicks = int("poolTicks", 6, CACHE, min = 1, max = 20),
                    barrageGap = int("barrageGap", 2, HOUSE, min = 0, max = 20),
                    gatherIntoQuadrant = bool("gatherIntoQuadrant", true, HOUSE),
                    floorPortalSize = int("floorPortalSize", 1, INF, min = 1, max = 3),
                ).also { p2 ->
                    check("barrages", p2.barrages.size >= p2.quadrants.size) {
                        "needs one barrage per quadrant (${p2.quadrants.size}), has ${p2.barrages.size}"
                    }
                }
            },
            transitions = r.obj("transitions") {
                Transitions(
                    p1ToP2 = int("p1ToP2", 7, CACHE, min = 1, max = 40),          // 14814, 210 cycles
                    p2Form = int("p2Form", 6, CACHE, min = 1, max = 40),          // 14837, 180 cycles
                    bladeDrop = int("bladeDrop", 8, CACHE, min = 1, max = 40),    // spot 4280 anim 14876, 240 cycles
                    p2ToP3Part1 = int("p2ToP3Part1", 8, CACHE, min = 1, max = 40), // 14878, 240 cycles
                    p2ToP3Part2 = int("p2ToP3Part2", 5, CACHE, min = 1, max = 40), // 14881, 150 cycles
                    armSpotDelay = int("armSpotDelay", 5, INF, min = 0, max = 20), // 14878 sounds at 4.90 / 5.53 t
                )
            },
            projectiles = r.obj("projectiles") {
                Projectiles(
                    p1Magic = projectile(this, "p1Magic", ProjectileSpec(70, 31, 0, 15, 10, 5)),
                    p1Ranged = projectile(this, "p1Ranged", ProjectileSpec(70, 31, 0, 15, 10, 5)),
                    p1Release = projectile(this, "p1Release", ProjectileSpec(80, 31, 0, 15, 15, 4)),
                    p2Shot = projectile(this, "p2Shot", ProjectileSpec(10, 10, 0, 0, 120, 0)),
                )
            },
            p3 = r.obj("p3") {
                P3(
                    speed = int("speed", 6, CACHE, min = 1, max = 20),
                    meleeMax = int("meleeMax", 30, HOUSE, min = 0, max = 200),
                    waveDmg = intRange("waveDmg", 25..35, HOUSE, min = 0, max = 200),
                    waveWidth = int("waveWidth", 3, HOUSE, min = 1, max = 11),
                    voidShot = intRange("voidShot", 20..28, DOC, min = 0, max = 200),
                    spinCheckDmg = int("spinCheckDmg", 25, HOUSE, min = 0, max = 200),
                    sawMax = int("sawMax", 50, HOUSE, min = 0, max = 200),
                    sawPfmMult = double("sawPfmMult", 0.2, HOUSE, min = 0.0, max = 1.0),
                    untetherEvery = int("untetherEvery", 3, HOUSE, min = 1, max = 20),
                    vineTicks = int("vineTicks", 12, HOUSE, min = 1, max = 100),
                    vineDmg = intRange("vineDmg", 50..55, DOC, min = 0, max = 200),
                    addCharge = int("addCharge", 15, HOUSE, min = 1, max = 100),
                    riftDetonate = intRange("riftDetonate", 20..25, HOUSE, min = 0, max = 200),
                    siphonHealPct = int("siphonHealPct", 5, HOUSE, min = 0, max = 100),
                    augmenterBuffPct = int("augmenterBuffPct", 10, HOUSE, min = 0, max = 100),
                    insolenceDmg = intRange("insolenceDmg", 30..40, HOUSE, min = 0, max = 200),
                    crackDmg = int("crackDmg", 4, HOUSE, min = 0, max = 200),
                    crackLife = int("crackLife", 10, HOUSE, min = 1, max = 100),
                    untetherBombs = intRange("untetherBombs", 15..20, HOUSE, min = 0, max = 200),
                    untether = int("untether", 45, DOC, min = 0, max = 200),
                    obliterationTicks = int("obliterationTicks", 10, HOUSE, min = 1, max = 100),
                    thresholds = intList("thresholds", listOf(75, 50, 25), DOC, size = 0..8, min = 1, max = 99),
                    pools = int("pools", 2, HOUSE, min = 0, max = 20),
                    poolDmg = int("poolDmg", 3, HOUSE, min = 0, max = 200),
                    poolLife = int("poolLife", 15, HOUSE, min = 1, max = 100),
                )
            },
            p4 = r.obj("p4") {
                P4(
                    chainCount = int("chainCount", 6, DOC, min = 1, max = 20),
                    chainInterval = int("chainInterval", 2, DOC, min = 1, max = 10),
                    chainDmg = intRange("chainDmg", 15..20, HOUSE, min = 0, max = 200),
                    damageTakenMult = double("damageTakenMult", 0.5, DOC, min = 0.0, max = 1.0),
                    orbStages = intList("orbStages", listOf(6, 12), HOUSE, size = 2..2, min = 1, max = 100),
                    orbHeal = doubleList("orbHeal", listOf(0.5, 0.75, 1.0), HOUSE, size = 3..3, min = 0.0, max = 1.0),
                    orbLife = int("orbLife", 20, HOUSE, min = 1, max = 100),
                    blast = int("blast", 45, DOC, min = 0, max = 200),
                    blastPrayed = intRange("blastPrayed", 6..7, DOC, min = 0, max = 200),
                    soulSplitPct = int("soulSplitPct", 25, HOUSE, min = 0, max = 100),
                    voidFloorDmg = int("voidFloorDmg", 10, HOUSE, min = 0, max = 200),
                    deflectReflectPct = int("deflectReflectPct", 10, HOUSE, min = 0, max = 100),
                )
            },
            p3InheritP1Pfm = r.bool("p3InheritP1Pfm", false, INF),
            p3PfmWhileMoving = r.bool("p3PfmWhileMoving", true, INF),
            planeShift = r.int("planeShift", 0, INF, min = -1, max = 0),
            combatLevel = r.obj("combatLevel") {
                CombatLevel(
                    p1 = intOrNull("p1", null, DOC, min = 1, max = 9999),
                    p3 = intOrNull("p3", 1601, DOC, min = 1, max = 9999),
                )
            },
            death = r.obj("death") { Death(ticks = int("ticks", 15, CACHE, min = 1, max = 60)) },
        )

        private fun projectile(r: ConfigReader, key: String, d: ProjectileSpec): ProjectileSpec = r.obj(key) {
            ProjectileSpec(
                startHeight = int("startHeight", d.startHeight, HOUSE, min = 0, max = 255),
                endHeight = int("endHeight", d.endHeight, HOUSE, min = 0, max = 255),
                delay = int("delay", d.delay, HOUSE, min = 0, max = 300),
                angle = int("angle", d.angle, HOUSE, min = -90, max = 90),
                base = int("base", d.base, HOUSE, min = 0, max = 600),
                perTile = int("perTile", d.perTile, HOUSE, min = 0, max = 60),
            )
        }
    }
}

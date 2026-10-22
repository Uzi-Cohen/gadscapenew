package com.gadscape.raids.tfa.rooms.guardian

import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.Evidence
import com.gadscape.raids.tfa.config.TfaConfigSpec
import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.raid.StaticTile

/**
 * `guardian.json` (spec §4.1 "House defaults", plus the facts the fight needs at run time).
 *
 * Every [Evidence.HOUSE]/[Evidence.COMM]/[Evidence.INF] key is listed at load as "still holding a house value" until
 * a file marks it verified. Combat profiles and HP bases are [Evidence.DOC]/[Evidence.CACHE] facts (wiki Monster
 * Inspect, cache 2746 npc params), kept here so an operator can correct them without a rebuild.
 *
 * Seq lengths (wake 5 t, land 4 t, lift 3 t, enrage 14 t, death 9 t ...) are cache facts and live in [GuardianAnims],
 * not here.
 */
data class GuardianConfig(
    /** 2-player shield (flying 16659 HP) and main HP (grounded 16660 HP) [DOC wiki S3, CACHE npc hitpoints]. */
    val shieldHp: Int,
    val mainHp: Int,
    /** Enrage threshold, % of scaled main HP [COMM wiki Strategies; 33% in older text]. */
    val enragePct: Int,
    val lift: Lift,
    /** Step-under slam max (crush) [HOUSE]. */
    val slamMax: Int,
    /** Ticks from the slam seq start to the hit (seq 14752 hit frame 63 of 90) [INF]. */
    val slamHitDelay: Int,
    /** Ranged auto max [DOC wiki max hit 25]. */
    val rangedMax: Int,
    /** Attack clock [DOC wiki attack speed 5, CACHE param 14]. */
    val attackSpeed: Int,
    /** First attack after FLYING / ENRAGED begins [HOUSE]. */
    val firstAttackDelay: Int,
    /** FLYING: every Nth attack on the clock is a mushroom cast [HOUSE]. */
    val mushroomEveryNthAttack: Int,
    /** ENRAGED alternates mushrooms and the laser; which comes first [HOUSE]. */
    val enragedFirst: EnragedFirst,
    val mushrooms: Mushrooms,
    val tendril: Tendril,
    val laser: Laser,
    val rangedProjectile: RangedProjectile,
    /** [COMM S2, low reliability] ranged auto also hits players in front of the boss near the target's line. */
    val rangedCone: Boolean,
    /** Half-width of the cone strip, tiles [HOUSE]. */
    val coneWidth: Int,
    /** [COMM S13, low reliability] melee tail swipe on the 7x2 strip behind the boss with the ranged auto. */
    val tailSwipe: Boolean,
    val tailMax: Int,
    val movement: Movement,
    /** Shared aggro rule window (§4 shared rules) [HOUSE]. */
    val aggroWindowTicks: Int,
    /** The boss cannot be attacked while it lands (4 t) [HOUSE]. */
    val landingUntargetable: Boolean,
    /** ENRAGING (14 t) is invulnerable [INF]. */
    val enragingInvulnerable: Boolean,
    /** Death pose NPC 16658 stays this long after the encounter completes (the nexus re-copy shows corpse loc 62800). */
    val corpseLingerTicks: Int,
    /** Size-5 boss south-west tile [INF from corpse loc 62800 at SW 4125,5597; centre 4127,5599]. */
    val bossSpawn: StaticTile,
    val battery: Battery,
    val traits: Traits,
    val stats: Stats,
) {

    data class Lift(
        /** GROUNDED ends when main HP crosses the next multiple of this % below 100 (100 → 65) [HOUSE]. */
        val mainStepPct: Int,
        /** ... or after this many grounded ticks [HOUSE]. */
        val maxGroundedTicks: Int,
    )

    enum class EnragedFirst { MUSHROOMS, LASER }

    data class Mushrooms(
        /** Count per cast = base + perTwoAlive × floor(alive / 2) [HOUSE]. */
        val base: Int,
        val perTwoAlive: Int,
        /** Chebyshev distance range from the boss centre [HOUSE]. */
        val minDist: Int,
        val maxDist: Int,
        /** Ticks a mushroom lives untriggered, from its spawn [HOUSE]. */
        val lifetime: Int,
        /** Most mushrooms alive at once [HOUSE]. */
        val cap: Int,
        /** Burst damage roll [COMM wiki 3-10]. */
        val dmgMin: Int,
        val dmgMax: Int,
        /** Poison damage started by a burst [COMM osrsguide "+10 poison"]. */
        val poison: Int,
        /** Weary length [HOUSE, Nightmare drowsy copy]. */
        val wearyTicks: Int,
        /** Ticks from the cast seq (14750) to the mushrooms appearing [HOUSE]. */
        val spawnDelay: Int,
    )

    data class Tendril(
        /** First tendril wave, ticks after GROUNDED begins [COMM wiki "initial 6 tick delay"]. */
        val firstDelay: Int,
        /** Wave and swipe clock [COMM wiki "every 3 ticks"]. */
        val interval: Int,
        /** Swipes per tendril [COMM wiki "attacking 3 times"]. */
        val swipes: Int,
        /** Unprayed max [HOUSE]; Protect from Melee max [COMM wiki 11, hotfix 21 Oct]. */
        val max: Int,
        val maxPrayed: Int,
    )

    data class Laser(
        /** Total damage T = perAlive × alive players, shared by the players on the line [HOUSE]. */
        val perAlive: Int,
        /** Damage factor under Protect from Magic [HOUSE; COMM "reduces damage slightly"]. */
        val pfmMult: Double,
        /** Ray length in tiles from the boss centre [HOUSE; beam model 4205 is ~16 tiles × resize 1.22]. */
        val length: Int,
        /** A battery hit by the laser cannot take main HP below this % of max ("kill range") [HOUSE]. */
        val killRangePct: Int,
        /** Wind-up from the seq (14753, 3 t) to the hit [INF: one hit after the 3 t seq]. */
        val windup: Int,
    )

    /**
     * Ranged auto projectile 4201. End cycle = startDelay + flight + perTile × distance; the hit lands on the tick the
     * projectile arrives (rounded up). [CACHE seq 14761 = 82 cycles; spec §4.1 "start delay 30 cycles, 82-cycle
     * flight"]; heights and slope are [HOUSE] (no live capture). Heights are in the server unit (× 4 on the wire).
     */
    data class RangedProjectile(
        val startDelay: Int,
        val flight: Int,
        val perTile: Int,
        val startHeight: Int,
        val endHeight: Int,
        val slope: Int,
        /** Impact spot 4200 height on the target, client units [HOUSE: projectile end height × 4]. */
        val impactHeight: Int,
    )

    data class Movement(
        /** While flying it walks to keep its target within this Chebyshev distance of its centre [HOUSE]. */
        val followRange: Int,
        /** Floor box its 5x5 never leaves: [minX, minY, maxX, maxY] static [HOUSE]. */
        val box: List<Int>,
    )

    data class Battery(
        /** Battery HP, not scaled [CACHE npc 16662 hitpoints]. */
        val hp: Int,
        /** Discharge = round(shieldPct% × scaled max shield) [COMM wiki: 596 of 1490 at 6-scale]. */
        val shieldPct: Int,
        /** Earth weakness [HOUSE; COMM "earth spells weakness"]. */
        val earthWeaknessPct: Int,
        /** "active" (16662) or "inactive" (16661) per alcove [HOUSE from the fence layout]. */
        val startStates: Map<Alcove, Boolean>,
        /** Battery NPC SW tiles [INF: centre of the 2x4 base 62581]. */
        val tiles: Map<Alcove, StaticTile>,
        /** Batteries can be attacked before the Touch [HOUSE]. */
        val attackableBeforeStart: Boolean,
    )

    data class Traits(
        val flying: CombatTraits,
        val grounded: CombatTraits,
        /** Enraged form: it flies again; the flying profile is used [INF]. */
        val enraged: CombatTraits,
        /** Batteries: the add default with an earth weakness of `battery.earthWeaknessPct`. */
        val battery: CombatTraits,
    )

    /** Levels and bonuses (StatDefinitions order) of one form [DOC wiki Monster Inspect, CACHE params 3-8, 10, 12, 65]. */
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

    data class Stats(
        val flying: Profile,
        val grounded: Profile,
        val battery: Profile,
    )

    companion object SPEC : TfaConfigSpec<GuardianConfig>("guardian.json") {

        /** Flying 16659 [DOC wiki Archive_Guardian rev 15378797, CACHE params]. */
        @JvmField
        val FLYING_TRAITS = CombatTraits.BOSS_DEFAULT.copy(
            weakness = Element.FIRE, weaknessPct = 80, rangedDef = RangedDefence(75, 120, 50),
            freezeResistPct = 100, immuneAncients = true, magicDrainInverted = true, flying = true,
        )

        /** Grounded 16660: fire 40%, ranged 50/115/50, melee allowed [DOC wiki]. */
        @JvmField
        val GROUNDED_TRAITS = FLYING_TRAITS.copy(weaknessPct = 40, rangedDef = RangedDefence(50, 115, 50), flying = false)

        /** Inert Guardian battery 16662 [CACHE stats; HOUSE earth 50%]. Adds: no floor, poisonable (§3.11). */
        @JvmField
        val BATTERY_TRAITS = CombatTraits.ADD_DEFAULT.copy(weakness = Element.EARTH, weaknessPct = 50)

        private val ALLOWED_STATES = listOf("active", "inactive")

        override fun read(r: ConfigReader): GuardianConfig = readAll(r)

        private fun readAll(r: ConfigReader): GuardianConfig {
            val battery = readBattery(r)
            return GuardianConfig(
                shieldHp = r.int("shieldHp", 550, Evidence.DOC, min = 1),
                mainHp = r.int("mainHp", 1100, Evidence.DOC, min = 1),
                enragePct = r.int("enragePct", 30, Evidence.COMM, min = 1, max = 99),
                lift = r.obj("lift") {
                    Lift(
                        mainStepPct = int("mainStepPct", 35, min = 1, max = 100),
                        maxGroundedTicks = int("maxGroundedTicks", 24, min = 1),
                    )
                },
                slamMax = r.int("slamMax", 30, min = 0),
                slamHitDelay = r.int("slamHitDelay", 2, Evidence.INF, min = 0, max = 3),
                rangedMax = r.int("rangedMax", 25, Evidence.DOC, min = 0),
                attackSpeed = r.int("attackSpeed", 5, Evidence.DOC, min = 1),
                firstAttackDelay = r.int("firstAttackDelay", 2, min = 1),
                mushroomEveryNthAttack = r.int("mushroomEveryNthAttack", 3, min = 0),
                enragedFirst = r.enum("enragedFirst", EnragedFirst.MUSHROOMS),
                mushrooms = r.obj("mushrooms") {
                    Mushrooms(
                        base = int("base", 3, min = 0),
                        perTwoAlive = int("perTwoAlive", 1, min = 0),
                        minDist = int("minDist", 2, min = 0),
                        maxDist = int("maxDist", 6, min = 1),
                        lifetime = int("lifetime", 30, min = 4),
                        cap = int("cap", 12, min = 0),
                        dmgMin = int("dmgMin", 3, Evidence.COMM, min = 0),
                        dmgMax = int("dmgMax", 10, Evidence.COMM, min = 0),
                        poison = int("poison", 10, Evidence.COMM, min = 0),
                        wearyTicks = int("wearyTicks", 25, min = 0),
                        spawnDelay = int("spawnDelay", 1, min = 0, max = 3),
                    ).also { m ->
                        check("dmgMax", m.dmgMin <= m.dmgMax) { "dmgMin ${m.dmgMin} is above dmgMax ${m.dmgMax}" }
                        check("maxDist", m.minDist <= m.maxDist) { "minDist ${m.minDist} is above maxDist ${m.maxDist}" }
                    }
                },
                tendril = r.obj("tendril") {
                    Tendril(
                        firstDelay = int("firstDelay", 6, Evidence.COMM, min = 1),
                        interval = int("interval", 3, Evidence.COMM, min = 1),
                        swipes = int("swipes", 3, Evidence.COMM, min = 1),
                        max = int("max", 25, min = 0),
                        maxPrayed = int("maxPrayed", 11, Evidence.COMM, min = 0),
                    )
                },
                laser = r.obj("laser") {
                    Laser(
                        perAlive = int("perAlive", 45, min = 0),
                        pfmMult = double("pfmMult", 0.8, min = 0.0, max = 1.0),
                        length = int("length", 18, min = 1, max = 64),
                        killRangePct = int("killRangePct", 10, min = 0, max = 100),
                        windup = int("windup", 3, Evidence.INF, min = 0),
                    )
                },
                rangedProjectile = r.obj("rangedProjectile") {
                    RangedProjectile(
                        startDelay = int("startDelay", 30, Evidence.INF, min = 0),
                        flight = int("flight", 82, Evidence.INF, min = 0),
                        perTile = int("perTile", 0, min = 0),
                        startHeight = int("startHeight", 100, min = 0, max = 255),
                        endHeight = int("endHeight", 31, min = 0, max = 255),
                        slope = int("slope", 15, min = -90, max = 90),
                        impactHeight = int("impactHeight", 124, min = 0),
                    )
                },
                rangedCone = r.bool("rangedCone", false, Evidence.COMM),
                coneWidth = r.int("coneWidth", 2, min = 0),
                tailSwipe = r.bool("tailSwipe", false, Evidence.COMM),
                tailMax = r.int("tailMax", 15, min = 0),
                movement = r.obj("movement") {
                    Movement(
                        followRange = int("followRange", 6, min = 1),
                        box = intList("box", listOf(4116, 5585, 4138, 5613), size = 4..4, check = { b ->
                            if (b[0] + 4 > b[2] || b[1] + 4 > b[3]) "the box must fit the 5x5 boss" else null
                        }),
                    )
                },
                aggroWindowTicks = r.int("aggroWindowTicks", 10, min = 1),
                landingUntargetable = r.bool("landingUntargetable", true),
                enragingInvulnerable = r.bool("enragingInvulnerable", true, Evidence.INF),
                corpseLingerTicks = r.int("corpseLingerTicks", 10, min = 0),
                bossSpawn = r.tile("bossSpawn", StaticTile(4125, 5597, 0), Evidence.INF),
                battery = battery,
                traits = r.obj("traits") {
                    Traits(
                        flying = CombatTraits.read(this, FLYING_TRAITS, key = "flying"),
                        grounded = CombatTraits.read(this, GROUNDED_TRAITS, key = "grounded"),
                        enraged = CombatTraits.read(this, FLYING_TRAITS, key = "enraged", evidence = Evidence.INF),
                        battery = BATTERY_TRAITS.copy(weaknessPct = battery.earthWeaknessPct),
                    )
                },
                stats = r.obj("stats") {
                    Stats(
                        flying = profile(this, "flying", FLYING_PROFILE),
                        grounded = profile(this, "grounded", GROUNDED_PROFILE),
                        battery = profile(this, "battery", BATTERY_PROFILE),
                    )
                },
            )
        }

        private fun readBattery(r: ConfigReader): Battery = r.obj("battery") {
            Battery(
                hp = int("hp", 180, Evidence.CACHE, min = 1),
                shieldPct = int("shieldPct", 40, Evidence.COMM, min = 0, max = 100),
                earthWeaknessPct = int("earthWeaknessPct", 50, min = 0, max = 1000),
                startStates = obj("startStates") {
                    Alcove.entries.associateWith { a ->
                        string(a.name, if (a == Alcove.SE) "inactive" else "active", allowed = ALLOWED_STATES) == "active"
                    }
                },
                tiles = obj("tiles") {
                    Alcove.entries.associateWith { a -> tile(a.name, a.defaultTile, Evidence.INF) }
                },
                attackableBeforeStart = bool("attackableBeforeStart", false),
            )
        }

        /** Flying 16659: levels 250/240/260/250/280; +350 magic and ranged attack; def 120/110/20/130/120; str 0/55/55%. */
        @JvmField
        val FLYING_PROFILE = Profile(
            levels = listOf(250, 240, 260, 250, 280),
            attackBonuses = listOf(0, 0, 0, 350, 350),
            defenceBonuses = listOf(120, 110, 20, 130, 120),
            otherBonuses = listOf(0, 55, 55),
        )

        /** Grounded 16660: slash defence 90, standard ranged 115 [DOC wiki dslash2, dstandard2; CACHE param 6]. */
        @JvmField
        val GROUNDED_PROFILE = FLYING_PROFILE.copy(defenceBonuses = listOf(120, 90, 20, 130, 115))

        /** Battery 16662: Def 50, Mag 50; stab 0, slash +20, crush −20, magic −20 [CACHE]; ranged not in the cache. */
        @JvmField
        val BATTERY_PROFILE = Profile(
            levels = listOf(1, 1, 50, 50, 1),
            attackBonuses = listOf(0, 0, 0, 0, 0),
            defenceBonuses = listOf(0, 20, -20, -20, 0),
            otherBonuses = listOf(0, 0, 0),
        )

        private fun profile(r: ConfigReader, key: String, default: Profile): Profile = r.obj(key) {
            Profile(
                levels = intList("levels", default.levels, Evidence.DOC, size = 5..5, min = 1),
                attackBonuses = intList("attackBonuses", default.attackBonuses, Evidence.DOC, size = 5..5),
                defenceBonuses = intList("defenceBonuses", default.defenceBonuses, Evidence.DOC, size = 5..5),
                otherBonuses = intList("otherBonuses", default.otherBonuses, Evidence.DOC, size = 3..3),
            )
        }
    }
}

/**
 * The four battery alcoves of the atrium (spec §4.1 table; fences from the region 16471 map, `arena_map.txt`):
 * NW behind fence03 (ranged and magic from the floor), SW and NE with a one-tile gap (melee), SE closed (beam only).
 */
enum class Alcove(val defaultTile: StaticTile) {
    NW(StaticTile(4114, 5605, 0)),
    SW(StaticTile(4114, 5592, 0)),
    NE(StaticTile(4139, 5605, 0)),
    SE(StaticTile(4139, 5592, 0));

    companion object {
        @JvmStatic
        fun byName(text: String): Alcove? = entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
    }
}

package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.Evidence
import com.gadscape.raids.tfa.config.TfaConfigSpec
import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.RangedDefence
import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile

/**
 * `dagonhai.json` (spec §4.6 "House defaults", §7 U40-U43, U71, U72). The spec's JSON block is reproduced key for key;
 * the extra keys are the implementation's timing, geometry and presentation choices, each tagged with its evidence.
 * Every [Evidence.HOUSE] / [Evidence.COMM] / [Evidence.INF] value is listed by `::tfa dump config` until verified.
 */
data class DagonhaiConfig(
    /** Shared pool per break at 2-player scale (cache hitpoints 640 = Monster Inspect); scaled per spec §2.6. */
    val pool2: Int,
    /** Pool depletions until the cauldron explodes (COMM "five times"). */
    val breaks: Int,
    /** Cauldron 16649 hitpoints = ritual stability (CACHE 1000, shown in % by param 2289). */
    val stability: Int,
    /** Stability lost per break (COMM "drop 20%" of 1000). */
    val stabilityPerBreak: Int,
    /** Successful charges that fill the ritual (COMM "25%" each). */
    val ritualQuarters: Int,
    /** Does a break reset the ritual progress? (U: unknown; no). */
    val ritualResetOnBreak: Boolean,
    /** Hitpoints of the unshielded survivors (CACHE 16644/16646/16648 = 7). */
    val finalHp: Int,
    /** INTRO length before the shield phase (spec state machine: 3 t). */
    val introTicks: Int,
    val elder: Elder,
    val channel: Channel,
    val monk: Monk,
    /** Headbar of the shared pool on each Dagon'hai: 89 `standard_shield_30` (U72) or 86 `standard_shield_60`. */
    val poolHeadbar: Int,
    val tome: Tome,
    val protect: Protect,
    val empowered: Empowered,
    val explosion: Explosion,
    val finale: Finale,
    val projectiles: Projectiles,
    val heights: Heights,
    val arena: Arena,
    val hud: Hud,
    /** The three shielded NPCs (identical, spec combat profile). */
    val traits: CombatTraits,
    val impTraits: CombatTraits,
    val stats: NpcProfile,
    val impStats: NpcProfile,
) {

    /** Shared pool for party scale [n] (spec §2.6 table, `Scaling.hp`). */
    fun scaledPool(scaling: com.gadscape.raids.tfa.encounter.Scaling, n: Int): Int = scaling.hp(pool2, n)

    /** The Elder's autos (spec §4.6 attack table, NOTES §7.1). */
    data class Elder(
        /** Ticks between autos (cache param 14 = 6). */
        val speed: Int,
        val rangedMax: Int,
        /** Surge max by element (stock surge bases: fire 24, water 22, air 21, earth 23). */
        val surgeMax: Map<Element, Int>,
        /** Attacks on one focus target before a new one is picked (COMM "focus on one player"). */
        val focusAttacks: Int,
        /** Every other player within this Chebyshev distance of the target takes its own roll. */
        val splash: Int,
        /** Chance an auto is the surge (COMM "50/50"). */
        val magicChance: Double,
        /** Ticks from the shield phase's start to the first auto. */
        val firstAttack: Int,
    )

    /** Elemental channels (spec attack table, U40). */
    data class Channel(
        /** Ticks from the fight start to the first channel. */
        val first: Int,
        /** Ticks between channel starts. */
        val interval: IntRange,
        /** Charge length (headbar 85 fills over it). */
        val length: Int,
        /** Ticks after a protection phase before the next channel. */
        val afterProtect: Int,
        /** Who may channel: "elder", "staff", "book" (spec "one at a time across the trio"). */
        val casters: List<Role>,
        /** A splashed matching spell interrupts too (spec "splashes count"). */
        val splashCounts: Boolean,
    )

    /** The monks' desk loop (spec attack table "Tome throw", NOTES §7.4). */
    data class Monk(
        val searchTicks: IntRange,
        /** Throw tick the tome leaves at (5-t throw, release ~3.7 t) [INF, spec]. */
        val throwRelease: Int,
        /** Tome flight in ticks. */
        val flight: Int,
        /** Client cycle of the release inside the throw seq (synth at frame 29 = cycle 112) [CACHE]. */
        val releaseCycle: Int,
        /** A walk that has not arrived after this many ticks ends with the monk placed on its desk. */
        val walkTimeout: Int,
        /** Only one tome in the air at a time (NOTES [PH]). */
        val oneTomeAtATime: Boolean,
        /** Desks the monks use, as lane directions from the cauldron. */
        val desks: List<Compass>,
        /** First desk of each monk ("the two monks will quickly scatter to the sides"). */
        val firstDesks: Map<Role, Compass>,
        /** South-west tile of a monk (2x2) searching each desk, on the outer end of the desk's lane [INF map]. */
        val stands: Map<Compass, StaticTile>,
    )

    /** Tome effects (spec "Tome effects" table and [C+] note). */
    data class Tome(
        /** An un-intercepted tome adds a ritual quarter (U71). */
        val addsProgress: Boolean,
        val bandosBlocker: Int,
        val bandosRing: Int,
        /** Rings of the shockwave and their width: rings 1-2, 3-4, 5-6, 7-8 on ticks +1..+4. */
        val bandosRings: Int,
        val bandosRingWidth: Int,
        val zamBlocker: Int,
        /** Burn hitsplats on the blocker, one every [zamBurnEvery] ticks. */
        val zamBurn: List<Int>,
        val zamBurnEvery: Int,
        val zamBlast: Int,
        /** Half-width of the fireball square around the blocker (7x7). */
        val zamRadius: Int,
        val zamCauldronTiles: Int,
        /** Fuse lengths of spots 4145-4148 [CACHE 14664-14667]. */
        val zamFuses: List<Int>,
        /** "random" fuse per tile, or by Chebyshev ring: "rings_out" (centre first) / "rings_in". */
        val zamFuseLayout: String,
        val saraHeal: Int,
        val saraTiles: Int,
        val saraTileHeal: Int,
        val saraTilePrayer: Int,
        val saraTileDmg: Int,
        /** Un-intercepted Saradomin: the trio's pool heals this % of its max. */
        val saraPoolHealPct: Int,
        /** Un-intercepted Saradomin: every player in the arena heals this much. */
        val saraCauldronHeal: Int,
        /** Storm tiles are picked within this distance of the blocker (COMM "shadows appear around the arena": 20 = all). */
        val saraRadius: Int,
        /** Strike delays of the heal tiles: telegraph 4064 = 5 t, 4065 = 6 t [CACHE 7818 / 7819]. */
        val saraHealStrikes: List<Int>,
        /** Strike delay of the damage tiles: telegraph 4063 = 5 t [CACHE 7818]. */
        val saraDamageStrike: Int,
    )

    /** Breaks 1-4: Zamorak protection, rifts and imps (spec PROTECTED, U41). */
    data class Protect(
        val rifts: Int,
        val impsPerRift: Int,
        val impHp: Int,
        val impMax: Int,
        /** Protection ends after this many ticks even if imps live. */
        val maxTicks: Int,
        /** Demonic strength multiplier on the killer's next damaging hit on a Dagon'hai. */
        val strengthMult: Double,
        /** Demonic strength is lost after this many ticks. */
        val strengthTicks: Int,
        /** Imp attack rate (cache param 14 = 4). */
        val impSpeed: Int,
        /** Damage multiplier of an imp hit through Protect from Melee (COMM "reduced", spec "halves"). */
        val impPfmMult: Double,
        /** "stab" / "slash" / "crush": the imp's melee style for the accuracy roll. */
        val impStyle: String,
        /** Ticks from the rift opening to its first imp, and between its imps ("2 t apart"). */
        val firstImp: Int,
        val impInterval: Int,
        /** A rift opens at least this Chebyshev distance from every player. */
        val riftMinPlayerDistance: Int,
        /** Imp despawn after death (seq 14530 = 60 c). */
        val impDespawn: Int,
        /** Re-send the dome 4165 every this many ticks (seq 9601 = 6 t). */
        val domeEvery: Int,
    )

    /** The ritual completed (spec EMPOWERED, U42). */
    data class Empowered(
        val speed: Int,
        val elderHits: Int,
        val elderMax: Int,
        val monkMax: Int,
        /** Damage multiplier through the correct protection prayer (prayer −25%). */
        val prayerMult: Double,
        /** Ticks from the ritual completing to the first empowered attacks. */
        val firstDelay: Int,
        /** Client cycles between the Elder's two 4078 projectiles. */
        val secondProjectileCycles: Int,
        /** The monks' cast sends the stock water surge 1459 (their cast spots carry the water-surge recolour) [INF]. */
        val monkProjectile: Boolean,
    )

    /** The fifth break (spec EXPLOSION). */
    data class Explosion(
        val monkStun: Int,
        val elderStun: Int,
        /** The monk the blast kills: "book" (only death_03 has a vfx, 4163) or "staff". */
        val dies: Role,
        /** The exploded monk is removed after its death seq (125 c). */
        val deathTicks: Int,
        /** The cauldron is removed after the explosion seq (60 c). */
        val cauldronTicks: Int,
    )

    /** The 7-HP finish. */
    data class Finale(
        /** The Elder "quickly recovers" and keeps attacking. */
        val elderAttacks: Boolean,
        /** Death seq 14652 / 14653 (125 c) before the NPC is removed. */
        val deathTicks: Int,
    )

    /**
     * One classic projectile. Flight = delay + duration + perTile × distance client cycles; the hit lands
     * `max(1 + floor((hitOffset + d) / hitDivisor), flight ticks rounded up)` ticks after the cast (OSRS NPC formulas:
     * ranged 3/6, magic 1/3). Heights in the server's old units, slope in client units (0-64).
     */
    data class ProjectileSpec(
        val startHeight: Int,
        val endHeight: Int,
        val delay: Int,
        val slope: Int,
        val perTile: Int,
        val duration: Int,
        val hitOffset: Int,
        val hitDivisor: Int,
    ) {
        fun flightCycles(distance: Int): Int = maxOf(0, delay + duration + perTile * distance)

        fun formulaTicks(distance: Int): Int = 1 + (hitOffset + distance) / hitDivisor

        /** Ticks from the cast to the hit: the formula, but never before the projectile visibly lands. */
        fun landingTicks(distance: Int): Int =
            maxOf(formulaTicks(distance), (flightCycles(distance) + DagonhaiIds.CYCLES_PER_TICK - 1) / DagonhaiIds.CYCLES_PER_TICK)
    }

    data class Projectiles(
        val elderRanged: ProjectileSpec,
        val surge: ProjectileSpec,
        val empoweredElder: ProjectileSpec,
        val monkSurge: ProjectileSpec,
        /** Heights and slope of the tome; its timing comes from `monk.releaseCycle` and `monk.flight`. */
        val tome: ProjectileSpec,
        val zamFireball: ProjectileSpec,
    )

    /** Spotanim heights on players (server units). */
    data class Heights(
        val surgeImpact: Int,
        val rangedImpact: Int,
        val tomeImpact: Int,
        val healImpact: Int,
        val buff: Int,
    )

    /** Spawn tiles (south-west tiles of the footprints) [HOUSE / INF map]. */
    data class Arena(
        val elder: StaticTile,
        val staff: StaticTile,
        val book: StaticTile,
        val cauldron: StaticTile,
    ) {
        fun spawn(role: Role): StaticTile = when (role) {
            Role.ELDER -> elder
            Role.STAFF -> staff
            Role.BOOK -> book
        }
    }

    /** HP HUD presentation (spec: recolour the extra bar yellow). */
    data class Hud(
        val recolourExtraBar: Boolean,
        /** RGB of the extra bar's back, sliding and remaining parts (303:24 / 25 / 26). */
        val extraBarColours: List<Int>,
    )

    /** Combat levels and bonuses (the server has no combat definition file for these npcs). */
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

    companion object SPEC : TfaConfigSpec<DagonhaiConfig>("dagonhai.json") {

        /** spec §4.6 combat profile: no defence floor (hotfix), poison/venom/cannon immune, ranged 80 / 80 / 80, no weakness. */
        val DEFAULT_TRAITS: CombatTraits = CombatTraits.BOSS_DEFAULT.copy(
            defenceFloorPct = null, rangedDef = RangedDefence(80, 80, 80),
        )

        /** Adds can be poisoned (§3.11 HOUSE); the imp's ranged defence 0 [CACHE: all defence bonuses 0]. */
        val DEFAULT_IMP_TRAITS: CombatTraits = CombatTraits.ADD_DEFAULT.copy(rangedDef = RangedDefence(0, 0, 0))

        /** Monk stand tiles per desk (SW of the 2x2, adjacent to the desk, on the lane's outer end) [INF arena map]. */
        val DEFAULT_STANDS: Map<Compass, StaticTile> = linkedMapOf(
            Compass.N to StaticTile(4065, 5605),
            Compass.NE to StaticTile(4070, 5603),
            Compass.E to StaticTile(4072, 5599),
            Compass.SE to StaticTile(4070, 5594),
            Compass.S to StaticTile(4065, 5592),
            Compass.SW to StaticTile(4059, 5593),
            Compass.W to StaticTile(4055, 5599),
            Compass.NW to StaticTile(4060, 5604),
        )

        override fun read(r: ConfigReader): DagonhaiConfig = DagonhaiConfig(
            pool2 = r.int("pool2", 640, Evidence.CACHE, min = 1),
            breaks = r.int("breaks", 5, Evidence.COMM, min = 1, max = 20),
            stability = r.int("stability", 1000, Evidence.CACHE, min = 1),
            stabilityPerBreak = r.int("stabilityPerBreak", 200, Evidence.COMM, min = 0),
            ritualQuarters = r.int("ritualQuarters", 4, Evidence.COMM, min = 1, max = 20),
            ritualResetOnBreak = r.bool("ritualResetOnBreak", false, Evidence.HOUSE),
            finalHp = r.int("finalHp", 7, Evidence.CACHE, min = 1),
            introTicks = r.int("introTicks", 3, Evidence.HOUSE, min = 0, max = 30),
            elder = r.obj("elder") {
                Elder(
                    speed = int("speed", 6, Evidence.CACHE, min = 1, max = 20),
                    rangedMax = int("rangedMax", 27, Evidence.INF, min = 0),
                    surgeMax = obj("surgeMax") {
                        linkedMapOf(
                            Element.FIRE to int("fire", 24, Evidence.INF, min = 0),
                            Element.WATER to int("water", 22, Evidence.INF, min = 0),
                            Element.AIR to int("air", 21, Evidence.INF, min = 0),
                            Element.EARTH to int("earth", 23, Evidence.INF, min = 0),
                        )
                    },
                    focusAttacks = int("focusAttacks", 4, Evidence.HOUSE, min = 1),
                    splash = int("splash", 1, Evidence.HOUSE, min = 0, max = 5),
                    magicChance = double("magicChance", 0.5, Evidence.COMM, min = 0.0, max = 1.0),
                    firstAttack = int("firstAttack", 2, Evidence.HOUSE, min = 0, max = 20),
                )
            },
            channel = r.obj("channel") {
                Channel(
                    first = int("first", 15, Evidence.HOUSE, min = 1),
                    interval = intRange("interval", 20..25, Evidence.HOUSE, min = 1),
                    length = int("length", 12, Evidence.HOUSE, min = 1, max = 60),
                    afterProtect = int("afterProtect", 15, Evidence.HOUSE, min = 1),
                    casters = stringList("casters", listOf("elder", "staff", "book"), Evidence.HOUSE, size = 1..3,
                        allowed = Role.entries.map { it.key }).mapNotNull { Role.byKey(it) },
                    splashCounts = bool("splashCounts", true, Evidence.HOUSE),
                )
            },
            monk = r.obj("monk") {
                val desks = stringList("desks", Compass.entries.map { it.name.lowercase() }, Evidence.INF, size = 2..8,
                    allowed = Compass.entries.map { it.name.lowercase() }).map { Compass.valueOf(it.uppercase()) }.distinct()
                Monk(
                    searchTicks = intRange("searchTicks", 4..8, Evidence.HOUSE, min = 1),
                    throwRelease = int("throwRelease", 4, Evidence.INF, min = 1, max = 5),
                    flight = int("flight", 2, Evidence.HOUSE, min = 1, max = 10),
                    releaseCycle = int("releaseCycle", 112, Evidence.CACHE, min = 0, max = 149),
                    walkTimeout = int("walkTimeout", 12, Evidence.HOUSE, min = 1),
                    oneTomeAtATime = bool("oneTomeAtATime", true, Evidence.HOUSE),
                    desks = desks,
                    firstDesks = obj("firstDesks") {
                        linkedMapOf(
                            Role.STAFF to enum("staff", Compass.NW, Evidence.HOUSE),
                            Role.BOOK to enum("book", Compass.SW, Evidence.HOUSE),
                        )
                    },
                    stands = obj("stands") {
                        Compass.entries.associateWith { dir -> tile(dir.name.lowercase(), DEFAULT_STANDS.getValue(dir), Evidence.INF) }
                    },
                )
            },
            poolHeadbar = r.int("poolHeadbar", 89, Evidence.INF, check = { if (it != 86 && it != 89) "must be 86 or 89" else null }),
            tome = r.obj("tome") {
                Tome(
                    addsProgress = bool("addsProgress", true, Evidence.INF),
                    bandosBlocker = int("bandosBlocker", 55, Evidence.COMM, min = 0),
                    bandosRing = int("bandosRing", 20, Evidence.HOUSE, min = 0),
                    bandosRings = int("bandosRings", 4, Evidence.INF, min = 1, max = 4),
                    bandosRingWidth = int("bandosRingWidth", 2, Evidence.INF, min = 1, max = 4),
                    zamBlocker = int("zamBlocker", 25, Evidence.COMM, min = 0),
                    zamBurn = intList("zamBurn", listOf(3, 3, 2, 2, 1, 1), Evidence.HOUSE, size = 0..20, min = 0),
                    zamBurnEvery = int("zamBurnEvery", 4, Evidence.HOUSE, min = 1),
                    zamBlast = int("zamBlast", 15, Evidence.HOUSE, min = 0),
                    zamRadius = int("zamRadius", 3, Evidence.COMM, min = 0, max = 6),
                    zamCauldronTiles = int("zamCauldronTiles", 20, Evidence.HOUSE, min = 0, max = 200),
                    zamFuses = intList("zamFuses", listOf(3, 4, 5, 6), Evidence.CACHE, size = 4..4, min = 1),
                    zamFuseLayout = string("zamFuseLayout", "random", Evidence.HOUSE, allowed = listOf("random", "rings_out", "rings_in")),
                    saraHeal = int("saraHeal", 20, Evidence.HOUSE, min = 0),
                    saraTiles = int("saraTiles", 10, Evidence.HOUSE, min = 0, max = 100),
                    saraTileHeal = int("saraTileHeal", 10, Evidence.HOUSE, min = 0),
                    saraTilePrayer = int("saraTilePrayer", 5, Evidence.HOUSE, min = 0),
                    saraTileDmg = int("saraTileDmg", 20, Evidence.HOUSE, min = 0),
                    saraPoolHealPct = int("saraPoolHealPct", 10, Evidence.HOUSE, min = 0, max = 100),
                    saraCauldronHeal = int("saraCauldronHeal", 20, Evidence.HOUSE, min = 0),
                    saraRadius = int("saraRadius", 20, Evidence.COMM, min = 1, max = 30),
                    saraHealStrikes = intList("saraHealStrikes", listOf(5, 6), Evidence.CACHE, size = 1..2, min = 1),
                    saraDamageStrike = int("saraDamageStrike", 5, Evidence.CACHE, min = 1),
                )
            },
            protect = r.obj("protect") {
                Protect(
                    rifts = int("rifts", 3, Evidence.COMM, min = 0, max = 10),
                    impsPerRift = int("impsPerRift", 2, Evidence.COMM, min = 0, max = 10),
                    impHp = int("impHp", 44, Evidence.CACHE, min = 1),
                    impMax = int("impMax", 20, Evidence.INF, min = 0),
                    maxTicks = int("maxTicks", 30, Evidence.HOUSE, min = 1),
                    strengthMult = double("strengthMult", 2.0, Evidence.HOUSE, min = 1.0, max = 10.0),
                    strengthTicks = int("strengthTicks", 50, Evidence.HOUSE, min = 1),
                    impSpeed = int("impSpeed", 4, Evidence.CACHE, min = 1, max = 20),
                    impPfmMult = double("impPfmMult", 0.5, Evidence.COMM, min = 0.0, max = 1.0),
                    impStyle = string("impStyle", "stab", Evidence.HOUSE, allowed = listOf("stab", "slash", "crush")),
                    firstImp = int("firstImp", 1, Evidence.HOUSE, min = 0, max = 10),
                    impInterval = int("impInterval", 2, Evidence.HOUSE, min = 0, max = 10),
                    riftMinPlayerDistance = int("riftMinPlayerDistance", 3, Evidence.HOUSE, min = 0, max = 10),
                    impDespawn = int("impDespawn", 2, Evidence.CACHE, min = 1, max = 10),
                    domeEvery = int("domeEvery", 6, Evidence.CACHE, min = 1, max = 30),
                )
            },
            empowered = r.obj("empowered") {
                Empowered(
                    speed = int("speed", 5, Evidence.HOUSE, min = 1),
                    elderHits = int("elderHits", 2, Evidence.COMM, min = 1, max = 4),
                    elderMax = int("elderMax", 45, Evidence.HOUSE, min = 0),
                    monkMax = int("monkMax", 30, Evidence.HOUSE, min = 0),
                    prayerMult = double("prayerMult", 0.75, Evidence.HOUSE, min = 0.0, max = 1.0),
                    firstDelay = int("firstDelay", 2, Evidence.HOUSE, min = 0, max = 20),
                    secondProjectileCycles = int("secondProjectileCycles", 15, Evidence.HOUSE, min = 0, max = 60),
                    monkProjectile = bool("monkProjectile", true, Evidence.INF),
                )
            },
            explosion = r.obj("explosion") {
                Explosion(
                    monkStun = int("monkStun", 6, Evidence.HOUSE, min = 0),
                    elderStun = int("elderStun", 3, Evidence.HOUSE, min = 0),
                    dies = Role.byKey(string("dies", "book", Evidence.INF, allowed = listOf("book", "staff"))) ?: Role.BOOK,
                    deathTicks = int("deathTicks", 5, Evidence.CACHE, min = 1, max = 20),
                    cauldronTicks = int("cauldronTicks", 2, Evidence.CACHE, min = 1, max = 20),
                )
            },
            finale = r.obj("finale") {
                Finale(
                    elderAttacks = bool("elderAttacks", true, Evidence.COMM),
                    deathTicks = int("deathTicks", 5, Evidence.CACHE, min = 1, max = 20),
                )
            },
            projectiles = r.obj("projectiles") {
                Projectiles(
                    elderRanged = obj("elderRanged") { projectile(this, 60, 31, 41, 15, 5, 0, 3, 6) },
                    surge = obj("surge") { projectile(this, 60, 31, 51, 16, 10, -5, 1, 3) },
                    empoweredElder = obj("empoweredElder") { projectile(this, 60, 31, 51, 16, 10, -5, 1, 3) },
                    monkSurge = obj("monkSurge") { projectile(this, 50, 31, 51, 16, 10, -5, 1, 3) },
                    tome = obj("tome") { projectile(this, 70, 31, 0, 10, 0, 0, 1, 1) },
                    zamFireball = obj("zamFireball") { projectile(this, 90, 0, 30, 30, 5, 30, 1, 1) },
                )
            },
            heights = r.obj("heights") {
                Heights(
                    surgeImpact = int("surgeImpact", 124, Evidence.INF, min = 0, max = 255),
                    rangedImpact = int("rangedImpact", 92, Evidence.INF, min = 0, max = 255),
                    tomeImpact = int("tomeImpact", 92, Evidence.INF, min = 0, max = 255),
                    healImpact = int("healImpact", 0, Evidence.INF, min = 0, max = 255),
                    buff = int("buff", 0, Evidence.INF, min = 0, max = 255),
                )
            },
            arena = r.obj("arena") {
                Arena(
                    elder = tile("elder", StaticTile(4069, 5599), Evidence.HOUSE),
                    staff = tile("staff", StaticTile(4062, 5602), Evidence.HOUSE),
                    book = tile("book", StaticTile(4062, 5596), Evidence.HOUSE),
                    cauldron = tile("cauldron", StaticTile(4064, 5598), Evidence.INF),
                )
            },
            hud = r.obj("hud") {
                Hud(
                    recolourExtraBar = bool("recolourExtraBar", true, Evidence.INF),
                    extraBarColours = intList("extraBarColours", listOf(0x2b2727, 0x9a9500, 0xd8d300), Evidence.INF, size = 3..3,
                        min = 0, max = 0xffffff),
                )
            },
            traits = CombatTraits.read(r, DEFAULT_TRAITS),
            impTraits = CombatTraits.read(r, DEFAULT_IMP_TRAITS, key = "impTraits", evidence = Evidence.HOUSE),
            stats = r.obj("stats") {
                profile(this, listOf(1, 1, 100, 290, 260), listOf(0, 0, 0, 250, 180), listOf(50, 50, 50, 100, 80), listOf(1, 0, 0))
            },
            impStats = r.obj("impStats") {
                profile(this, listOf(250, 195, 50, 125, 66), listOf(0, 0, 0, 1, 6), listOf(0, 0, 0, 0, 0), listOf(0, 0, 0))
            },
        )

        private fun projectile(
            r: ConfigReader, start: Int, end: Int, delay: Int, slope: Int, perTile: Int, duration: Int, hitOffset: Int, hitDivisor: Int,
        ) = ProjectileSpec(
            startHeight = r.int("startHeight", start, Evidence.INF, min = 0, max = 255),
            endHeight = r.int("endHeight", end, Evidence.INF, min = 0, max = 255),
            delay = r.int("delay", delay, Evidence.INF, min = 0, max = 300),
            slope = r.int("slope", slope, Evidence.INF, min = 0, max = 64),
            perTile = r.int("perTile", perTile, Evidence.INF, min = 0, max = 60),
            duration = r.int("duration", duration, Evidence.INF, min = -60, max = 600),
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

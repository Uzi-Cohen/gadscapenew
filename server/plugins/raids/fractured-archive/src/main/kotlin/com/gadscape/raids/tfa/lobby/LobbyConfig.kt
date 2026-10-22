package com.gadscape.raids.tfa.lobby

import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.Evidence.CACHE
import com.gadscape.raids.tfa.config.Evidence.DOC
import com.gadscape.raids.tfa.config.Evidence.HOUSE
import com.gadscape.raids.tfa.config.Evidence.INF
import com.gadscape.raids.tfa.config.TfaConfigSpec
import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile

/**
 * `lobby.json`: the keys of WU-09 (entry), WU-10 (lobby), WU-11 (party) and WU-19 (stats) that `tfa.json`
 * ([com.gadscape.raids.tfa.config.RaidConfig]) does not already hold. Raid-wide keys this unit also reads from
 * `tfa.json`: `entryGate.*`, `folio.*`, `introScene.*`, `party.refreshRateLimitTicks`, `stats.inRaidScoreboard`,
 * `completionJingle`, `recordsKey.*`, `tiles.lobbyArrival / templeExit`.
 *
 * Every coordinate is a static cache tile.
 */
data class LobbyConfig(
    val npcs: List<NpcSpawn>,
    val rocks: Rocks,
    val sprite: Sprite,
    val intro: Intro,
    val messenger: Messenger,
    val party: Party,
    val stats: Stats,
) {

    /** A lobby NPC spawn by role ([LobbyNpcRole]); the npc id comes from `TfaIds`. */
    data class NpcSpawn(val role: LobbyNpcRole, val tile: StaticTile, val facing: Compass)

    /**
     * Ascension rocks (§3.2) [DOC wiki Ascension_rocks]: 3 active at a time, all three sink every [cycleTicks] and
     * three others rise; level 80, 15 xp per shard. The success roll is a [HOUSE] calibration to the wiki's
     * "about 130 shards per hour at 99 Mining with a dragon pickaxe, sprites included" (see [AscensionMining]).
     */
    data class Rocks(
        /** Rock tiles; the cache map places 10 inactive rocks (63269-63271) here [CACHE r16470 locs]. */
        val tiles: List<StaticTile>,
        val activeCount: Int,
        val cycleTicks: Int,
        val levelRequired: Int,
        val xpPerShard: Int,
        /** Ticks between rolls; fixed, so tick manipulation does not speed it up [DOC]. */
        val rollIntervalTicks: Int,
        /** Success chance per roll at level 80 and at level 99 with a top-tier (level 61) pickaxe. */
        val chanceAt80: Double,
        val chanceAt99: Double,
        /** Multipliers by pickaxe tier (pickaxe level requirement 1/21/31/41/61). */
        val toolMultipliers: List<Double>,
        /** Uncut gem pre-roll: 1 in [gemOneIn] successful rolls gives a random uncut gem instead of a shard. */
        val gemOneIn: Int,
        /** Ticks the lower / raise loc animation needs (seqs 15064-15069 are 61 cycles, so 3 ticks). */
        val animTicks: Int,
    )

    /** The sprite 16691 (§3.2) [DOC wiki Sprite]: one per depletion cycle at a random time, 30 s life. */
    data class Sprite(
        val enabled: Boolean,
        val despawnTicks: Int,
        val shards: IntRange,
        val xp: IntRange,
        /** Max distance from the miner where it appears. */
        val spawnRadius: Int,
    )

    /** First-entry scene (§3.1): where the three Alliance leaders stand, by the entrance [HOUSE]. */
    data class Intro(
        val ivy: StaticTile,
        val armacus: StaticTile,
        val eva: StaticTile,
        /** NPCs vanish after this many ticks even if the dialogue is left open (the scene never traps anyone). */
        val maxTicks: Int,
    )

    /** "Guardian of Armadyl" messenger 16699 (§3.1): hands over the Alliance's message once the gate is met. */
    data class Messenger(val enabled: Boolean, val despawnTicks: Int, val checkIntervalTicks: Int)

    /** §3.3 Grouping Board limits [CACHE enum 6058 / UI applicant rows]. */
    data class Party(
        val maxParties: Int,
        val maxMembers: Int,
        val maxApplicants: Int,
        val notifyLeaderOnApply: Boolean,
    )

    /** §3.14 storage. */
    data class Stats(
        /** How often the global records file is flushed when it changed. */
        val saveIntervalTicks: Int,
        /** Personal-best varbits 16449-16569 are not used; personal stats live in the player save. */
        val announcePersonalBest: Boolean,
    )

    companion object SPEC : TfaConfigSpec<LobbyConfig>("lobby.json") {

        /** Spec §3.2 positions [DOC wiki maps]. */
        val DEFAULT_NPCS: List<NpcSpawn> = listOf(
            NpcSpawn(LobbyNpcRole.GENERAL_MONTOYA, StaticTile(4117, 5550), Compass.S),
            NpcSpawn(LobbyNpcRole.RECORD_KEEPER, StaticTile(4136, 5543), Compass.S),
            NpcSpawn(LobbyNpcRole.WIZARD_APPRENTICE, StaticTile(4114, 5539), Compass.S),
            NpcSpawn(LobbyNpcRole.BANKER, StaticTile(4135, 5555), Compass.W),
        )

        /** The ten inactive rocks of the cache map [CACHE audit/raids4/walk/raid_locs.txt, region 16470]. */
        val DEFAULT_ROCK_TILES: List<StaticTile> = listOf(
            StaticTile(4111, 5538), StaticTile(4113, 5533), StaticTile(4116, 5535),
            StaticTile(4113, 5538), StaticTile(4115, 5532), StaticTile(4116, 5537),
            StaticTile(4110, 5536), StaticTile(4111, 5535), StaticTile(4114, 5539), StaticTile(4117, 5534),
        )

        override fun read(r: ConfigReader): LobbyConfig = LobbyConfig(
            npcs = r.objList("npcs", DEFAULT_NPCS, DOC) {
                NpcSpawn(
                    role = enum("role", LobbyNpcRole.GENERAL_MONTOYA),
                    tile = tile("tile", StaticTile(4117, 5550)),
                    facing = enum("facing", Compass.S),
                )
            },
            rocks = r.obj("rocks") {
                Rocks(
                    tiles = tileList("tiles", DEFAULT_ROCK_TILES, CACHE, size = 4..32),
                    activeCount = int("activeCount", 3, DOC, min = 1, max = 8),
                    cycleTicks = int("cycleTicks", 295, DOC, min = 10),
                    levelRequired = int("levelRequired", 80, DOC, min = 1, max = 99),
                    xpPerShard = int("xpPerShard", 15, DOC, min = 0),
                    rollIntervalTicks = int("rollIntervalTicks", 4, HOUSE, min = 1, max = 10),
                    chanceAt80 = double("chanceAt80", 0.045, HOUSE, min = 0.0, max = 1.0),
                    chanceAt99 = double("chanceAt99", 0.0675, HOUSE, min = 0.0, max = 1.0),
                    toolMultipliers = doubleList("toolMultipliers", listOf(0.5, 0.6, 0.7, 0.85, 1.0), HOUSE, size = 5..5, min = 0.0, max = 2.0),
                    gemOneIn = int("gemOneIn", 256, HOUSE, min = 0),
                    animTicks = int("animTicks", 3, CACHE, min = 0, max = 10),
                )
            },
            sprite = r.obj("sprite") {
                Sprite(
                    enabled = bool("enabled", true, DOC),
                    despawnTicks = int("despawnTicks", 50, DOC, min = 1),
                    shards = intRange("shards", 1..2, DOC, min = 0),
                    xp = intRange("xp", 50..89, DOC, min = 0),
                    spawnRadius = int("spawnRadius", 3, HOUSE, min = 1, max = 8),
                )
            },
            intro = r.obj("intro") {
                Intro(
                    ivy = tile("ivy", StaticTile(4052, 4563), HOUSE),
                    armacus = tile("armacus", StaticTile(4050, 4563), HOUSE),
                    eva = tile("eva", StaticTile(4054, 4563), HOUSE),
                    maxTicks = int("maxTicks", 500, HOUSE, min = 10),
                )
            },
            messenger = r.obj("messenger") {
                Messenger(
                    enabled = bool("enabled", true, DOC),
                    despawnTicks = int("despawnTicks", 100, HOUSE, min = 1),
                    checkIntervalTicks = int("checkIntervalTicks", 50, HOUSE, min = 1),
                )
            },
            party = r.obj("party") {
                Party(
                    maxParties = int("maxParties", 45, CACHE, min = 1, max = 45),
                    maxMembers = int("maxMembers", 8, DOC, min = 1, max = 8),
                    maxApplicants = int("maxApplicants", 7, CACHE, min = 0, max = 7),
                    notifyLeaderOnApply = bool("notifyLeaderOnApply", true, HOUSE),
                )
            },
            stats = r.obj("stats") {
                Stats(
                    saveIntervalTicks = int("saveIntervalTicks", 100, HOUSE, min = 1),
                    announcePersonalBest = bool("announcePersonalBest", true, INF),
                )
            },
        )
    }
}

/** The lobby NPCs this unit spawns, by role (ids through `TfaIds.Npc`). */
enum class LobbyNpcRole { GENERAL_MONTOYA, RECORD_KEEPER, WIZARD_APPRENTICE, BANKER }

package com.gadscape.raids.tfa.config

import com.gadscape.raids.tfa.config.Evidence.DOC
import com.gadscape.raids.tfa.config.Evidence.HOUSE
import com.gadscape.raids.tfa.config.Evidence.INF
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.raid.TfaRoomId

/**
 * Raid-wide settings, `tfa.json` (spec §2-§3 and the "Key" column of §7.1). Owned by WU-08; read by the
 * framework units. Every coordinate is static (translate with `TfaInstance`).
 *
 * Units that need a raid-wide key that is not here should add their own file and spec (e.g. `spectate.json`)
 * rather than editing this class; a spec rooted at a new top-level key of `tfa.json` also works.
 */
data class RaidConfig(
    val entryGate: EntryGate,
    /** U78: teleporting out mid-raid. */
    val teleportOut: TeleportOutRule,
    /** U14: logout / disconnect. */
    val logout: Logout,
    /** U9: who may still enter after the raid is created. */
    val lateJoin: LateJoinRule,
    /** U10: where Passageway Exit works. */
    val passagewayExit: PassagewayExitRule,
    /** §2.3: an inactive raid ends as FAILED after this many minutes. */
    val watchdogMinutes: Int,
    /** §2.3: the instance is released this many ticks after the last member leaves. */
    val releaseDelayTicks: Int,
    /** §2.4: after completion, players in the arena move to the antechamber after this many ticks. */
    val roomExitDelayTicks: Int,
    /** §3.7 / U66: jingle played to each member at Ocellus's death (a resolved jingle id). */
    val completionJingle: Int,
    val recordsKey: RecordsKey,
    val folio: Folio,
    val supplies: Supplies,
    val nexus: Nexus,
    val hud: Hud,
    val practice: Practice,
    val introScene: IntroScene,
    val stats: Stats,
    val party: Party,
    val tiles: Tiles,
    val rooms: Rooms,
) {

    /** §3.1 entry gate (`TfaEntryGate`, WU-09). */
    data class EntryGate(
        val mode: GateMode,
        val requirements: Requirements,
        val refusalMessage: String,
    )

    enum class GateMode { WAIVED, ATTUNEMENT }

    /** "Attunement" requirements, mirroring what While Guthix Sleeps proves (§3.1). */
    data class Requirements(
        val thieving: Int,
        val magic: Int,
        val agility: Int,
        val farming: Int,
        val herblore: Int,
        val hunter: Int,
        val firemaking: Int,
        val attackPlusStrength: Int,
        val tormentedDemonKills: Int,
        val combatLevel: Int,
    )

    enum class TeleportOutRule { LEAVE_RAID, BLOCKED }

    data class Logout(val allowRejoin: Boolean)

    enum class LateJoinRule { UNTIL_GUARDIAN_TOUCH, NEVER }

    enum class PassagewayExitRule { ANTECHAMBER_ONLY, ANYWHERE }

    /** §3.17 / U19, U69: the Archive key for the records room. */
    data class RecordsKey(val source: KeySource, val kc: Int)

    enum class KeySource { RECORD_KEEPER, NEXUS_PICKUP }

    /** §3.1: Archivist's folio teleport charges. */
    data class Folio(val chargesPerShard: Int, val chargeCap: Int)

    /**
     * §3.12 / U6, U64: Quartermaster credits
     * `clamp(baseCredits + round(shareCredits × min(1, share / (fairSharePoints / raidSize))) − deathPenalty × deaths, 0, maxCredits)`.
     */
    data class Supplies(
        val baseCredits: Int,
        val shareCredits: Int,
        val deathPenalty: Int,
        val maxCredits: Int,
        val fairSharePoints: Int,
        val perPlayerStock: Boolean,
        val useOmnishop: Boolean,
        val slotMap: SlotMap,
    )

    /** How omnishop inv 1030 slots map to stock rows (U64). */
    enum class SlotMap { BUYABLE_ROWS, ALL_ROWS }

    /** §2.4, §3.5 / U8, U11. */
    data class Nexus(
        /** Cache planes copied for the pre-Guardian and camp variants of region 16471. */
        val variantPlanes: List<Int>,
        /** Planes to use when the records-room planes are not wanted. */
        val fallbackPlanes: List<Int>,
        /** Navigator spawns this many ticks after a completion if the party has not returned to the nexus. */
        val navigatorDelayTicks: Int,
        /** Guardian death → camp variant re-copy delay. */
        val campRecopyDelayTicks: Int,
        /** Section doors stay closed until the Navigator's "Yes". */
        val doorsLockedUntilNavigator: Boolean,
        /** "Enter" asks for confirmation once per section ("Quick-enter" never does). */
        val confirmSideOnce: Boolean,
        /** Tiles inside the Passageway where a section door Enter lands. */
        val antechamberInset: Int,
    )

    /** §3.7 / U12. */
    data class Hud(val timerMode: TimerMode, val orbHpCap: Int)

    enum class TimerMode { TOTAL, ROOM }

    /** §3.18 / U17. */
    data class Practice(val enabled: Boolean)

    /** §3.1 / U62. */
    data class IntroScene(val enabled: Boolean, val skippable: Boolean)

    /** §3.14 / U63. */
    data class Stats(val inRaidScoreboard: Boolean)

    /** §3.3. */
    data class Party(val refreshRateLimitTicks: Int)

    /** Fixed tiles of the framework (all [HOUSE] or [INF] in the spec). */
    data class Tiles(
        val lobbyArrival: StaticTile,
        val templeExit: StaticTile,
        val nexusArrival: StaticTile,
        val lobbyGravestone: StaticTile,
        val navigatorSpawn: StaticTile,
        val quartermaster: StaticTile,
        val ivy: StaticTile,
        val campMeritScroll: StaticTile,
        val ocellusCorridor: StaticTile,
        val ocellusGateSpill: StaticTile,
        val vaultArrival: StaticTile,
        val vaultMeritScroll: StaticTile,
        val uniqueChest: StaticTile,
    )

    /** Transporter landing per paired room (§2.4 / U7). */
    data class RoomEntry(val entry: StaticTile, val facing: Compass, val spread: Int)

    data class Rooms(val entries: Map<TfaRoomId, RoomEntry>) {
        operator fun get(room: TfaRoomId): RoomEntry? = entries[room]
    }

    companion object SPEC : TfaConfigSpec<RaidConfig>("tfa.json") {

        /** Spec defaults for the transporter landings (§2.4 table). */
        val DEFAULT_ROOM_ENTRIES: Map<TfaRoomId, RoomEntry> = mapOf(
            TfaRoomId.EZEKIEL to RoomEntry(StaticTile(4063, 5532), Compass.N, 1),
            TfaRoomId.BORK to RoomEntry(StaticTile(4186, 5599), Compass.E, 1),
            TfaRoomId.MOIA to RoomEntry(StaticTile(4066, 5663), Compass.W, 1),
            TfaRoomId.DREADNOTH to RoomEntry(StaticTile(4185, 5663), Compass.E, 1),
            TfaRoomId.DAGONHAI to RoomEntry(StaticTile(4072, 5601), Compass.W, 1),
            TfaRoomId.XARTH to RoomEntry(StaticTile(4184, 5535), Compass.E, 1),
        )

        override fun read(r: ConfigReader): RaidConfig = RaidConfig(
            entryGate = r.obj("entryGate") {
                EntryGate(
                    mode = enum("mode", GateMode.ATTUNEMENT, HOUSE),
                    requirements = obj("requirements") {
                        Requirements(
                            thieving = int("thieving", 72, HOUSE, 1, 99),
                            magic = int("magic", 67, HOUSE, 1, 99),
                            agility = int("agility", 66, HOUSE, 1, 99),
                            farming = int("farming", 65, HOUSE, 1, 99),
                            herblore = int("herblore", 65, HOUSE, 1, 99),
                            hunter = int("hunter", 62, HOUSE, 1, 99),
                            firemaking = int("firemaking", 49, HOUSE, 1, 99),
                            attackPlusStrength = int("attackPlusStrength", 130, HOUSE, 2, 198),
                            tormentedDemonKills = int("tormentedDemonKills", 1, HOUSE, 0),
                            combatLevel = int("combatLevel", 100, HOUSE, 3, 126),
                        )
                    },
                    refusalMessage = string("refusalMessage",
                        "You need to have proven yourself to the Guthixian Alliance to enter the Archive.", HOUSE),
                )
            },
            teleportOut = r.enum("teleportOut", TeleportOutRule.LEAVE_RAID, INF),
            logout = r.obj("logout") { Logout(allowRejoin = bool("allowRejoin", false, HOUSE)) },
            lateJoin = r.enum("lateJoin", LateJoinRule.UNTIL_GUARDIAN_TOUCH, INF),
            passagewayExit = r.enum("passagewayExit", PassagewayExitRule.ANTECHAMBER_ONLY, HOUSE),
            watchdogMinutes = r.int("watchdogMinutes", 30, HOUSE, min = 1, max = 24 * 60),
            releaseDelayTicks = r.int("releaseDelayTicks", 2, HOUSE, min = 0, max = 100),
            roomExitDelayTicks = r.int("roomExitDelayTicks", 3, HOUSE, min = 0, max = 100),
            completionJingle = r.int("completionJingle", TfaIds.Jingle.COMPLETION, INF, min = -1),
            recordsKey = r.obj("recordsKey") {
                RecordsKey(source = enum("source", KeySource.RECORD_KEEPER, HOUSE), kc = int("kc", 100, HOUSE, min = 0))
            },
            folio = r.obj("folio") {
                Folio(chargesPerShard = int("chargesPerShard", 5, DOC, min = 1), chargeCap = int("chargeCap", 1000, HOUSE, min = 1))
            },
            supplies = r.obj("supplies") {
                Supplies(
                    baseCredits = int("baseCredits", 40, HOUSE, 0, 127),
                    shareCredits = int("shareCredits", 43, HOUSE, 0, 127),
                    deathPenalty = int("deathPenalty", 10, HOUSE, 0, 127),
                    maxCredits = int("maxCredits", 83, HOUSE, 0, 127), // varbit 16381 holds 0-127
                    fairSharePoints = int("fairSharePoints", 16000, HOUSE, min = 1),
                    perPlayerStock = bool("perPlayerStock", true, HOUSE),
                    useOmnishop = bool("useOmnishop", true, HOUSE),
                    slotMap = enum("slotMap", SlotMap.BUYABLE_ROWS, INF),
                )
            },
            nexus = r.obj("nexus") {
                Nexus(
                    variantPlanes = intList("variantPlanes", listOf(1, 3), INF, size = 2..2, min = 0, max = 3),
                    fallbackPlanes = intList("fallbackPlanes", listOf(0, 2), INF, size = 2..2, min = 0, max = 3),
                    navigatorDelayTicks = int("navigatorDelayTicks", 10, HOUSE, min = 0),
                    campRecopyDelayTicks = int("campRecopyDelayTicks", 9, HOUSE, min = 0),
                    doorsLockedUntilNavigator = bool("doorsLockedUntilNavigator", true, INF),
                    confirmSideOnce = bool("confirmSideOnce", true, HOUSE),
                    antechamberInset = int("antechamberInset", 2, HOUSE, min = 0, max = 10),
                )
            },
            hud = r.obj("hud") {
                Hud(timerMode = enum("timerMode", TimerMode.TOTAL, HOUSE), orbHpCap = int("orbHpCap", 99, INF, min = 1))
            },
            practice = r.obj("practice") { Practice(enabled = bool("enabled", false, DOC)) },
            introScene = r.obj("introScene") {
                IntroScene(enabled = bool("enabled", true, DOC), skippable = bool("skippable", true, HOUSE))
            },
            stats = r.obj("stats") { Stats(inRaidScoreboard = bool("inRaidScoreboard", true, HOUSE)) },
            party = r.obj("party") { Party(refreshRateLimitTicks = int("refreshRateLimitTicks", 8, HOUSE, min = 0)) },
            tiles = r.obj("tiles") {
                Tiles(
                    lobbyArrival = tile("lobbyArrival", StaticTile(4126, 5508, 0), HOUSE),
                    templeExit = tile("templeExit", StaticTile(4051, 4564, 0), HOUSE),
                    nexusArrival = tile("nexusArrival", StaticTile(4127, 5582, 0), HOUSE),
                    lobbyGravestone = tile("lobbyGravestone", StaticTile(4123, 5558, 0), HOUSE),
                    navigatorSpawn = tile("navigatorSpawn", StaticTile(4127, 5590, 0), HOUSE),
                    quartermaster = tile("quartermaster", StaticTile(4121, 5581, 0), HOUSE),
                    ivy = tile("ivy", StaticTile(4127, 5618, 0), HOUSE),
                    campMeritScroll = tile("campMeritScroll", StaticTile(4131, 5579, 0), HOUSE),
                    ocellusCorridor = tile("ocellusCorridor", StaticTile(4127, 5634, 1), HOUSE),
                    ocellusGateSpill = tile("ocellusGateSpill", StaticTile(4127, 5661, 1), HOUSE),
                    vaultArrival = tile("vaultArrival", StaticTile(4127, 5775, 2), INF),
                    vaultMeritScroll = tile("vaultMeritScroll", StaticTile(4127, 5790, 2), HOUSE),
                    uniqueChest = tile("uniqueChest", StaticTile(4126, 5807, 2), INF),
                )
            },
            rooms = r.obj("rooms") {
                Rooms(DEFAULT_ROOM_ENTRIES.mapValues { (room, def) ->
                    obj(room.configKey) {
                        RoomEntry(
                            entry = tile("entry", def.entry, HOUSE),
                            facing = enum("facing", def.facing, HOUSE),
                            spread = int("spread", def.spread, HOUSE, min = 0, max = 5),
                        )
                    }
                })
            },
        )
    }
}

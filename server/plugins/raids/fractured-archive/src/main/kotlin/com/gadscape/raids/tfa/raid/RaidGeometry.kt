package com.gadscape.raids.tfa.raid

import com.gadscape.raids.tfa.ids.TfaIds

/**
 * Static geometry of the framework parts of the map (spec §2.4, §3.5, §3.6). Every value is a cache fact: the loc
 * placements of `audit/raids4/walk/raid_locs.txt` (format region|loc|x|y|plane|type|rot). Tiles the spec marks
 * [HOUSE] live in `tfa.json tiles.*` / `rooms.*` instead ([com.gadscape.raids.tfa.config.RaidConfig]).
 */
object RaidGeometry {

    /** A section door slot in the nexus: type-10 loc, 5 wide, SW tile [tile], rotation [rotation]. */
    data class DoorSpot(val section: Int, val side: Side, val tile: StaticTile, val rotation: Int)

    /** Loc ids of one section door [CACHE locs 63088-63107]. */
    data class DoorIds(val enter: Int, val slightly: Int, val opened: Int)

    /** West doors at x 4113 rot 3, east doors at x 4141 rot 1; sections at y 5584 / 5597 / 5610 [CACHE r16471]. */
    private val DOOR_Y = intArrayOf(5584, 5597, 5610)
    const val WEST_DOOR_X = 4113
    const val EAST_DOOR_X = 4141
    const val DOOR_WIDTH = 5

    fun door(section: Int, side: Side): DoorSpot {
        require(section in 1..3) { "section $section" }
        val y = DOOR_Y[section - 1]
        return if (side == Side.WEST) DoorSpot(section, side, StaticTile(WEST_DOOR_X, y, 0), 3)
        else DoorSpot(section, side, StaticTile(EAST_DOOR_X, y, 0), 1)
    }

    fun doorIds(section: Int, side: Side): DoorIds {
        val l = TfaIds.Loc
        return when (section to side) {
            1 to Side.WEST -> DoorIds(l.SECTION1_WEST_DOOR, l.SECTION1_WEST_DOOR_SLIGHTLY, l.SECTION1_WEST_DOOR_OPENED)
            1 to Side.EAST -> DoorIds(l.SECTION1_EAST_DOOR, l.SECTION1_EAST_DOOR_SLIGHTLY, l.SECTION1_EAST_DOOR_OPENED)
            2 to Side.WEST -> DoorIds(l.SECTION2_WEST_DOOR, l.SECTION2_WEST_DOOR_SLIGHTLY, l.SECTION2_WEST_DOOR_OPENED)
            2 to Side.EAST -> DoorIds(l.SECTION2_EAST_DOOR, l.SECTION2_EAST_DOOR_SLIGHTLY, l.SECTION2_EAST_DOOR_OPENED)
            3 to Side.WEST -> DoorIds(l.SECTION3_WEST_DOOR, l.SECTION3_WEST_DOOR_SLIGHTLY, l.SECTION3_WEST_DOOR_OPENED)
            3 to Side.EAST -> DoorIds(l.SECTION3_EAST_DOOR, l.SECTION3_EAST_DOOR_SLIGHTLY, l.SECTION3_EAST_DOOR_OPENED)
            else -> error("section $section")
        }
    }

    /** Every door loc id with Enter ops, to the (section, side) it belongs to. */
    fun doorOf(locId: Int): Pair<Int, Side>? {
        for (section in 1..3) for (side in Side.entries) {
            val ids = doorIds(section, side)
            if (locId == ids.enter || locId == ids.slightly || locId == ids.opened) return section to side
        }
        return null
    }

    /**
     * The room side of the Passageway 62765 (5x2, op Exit) and the Archive transporter of each paired room
     * [CACHE raid_locs]. Rotation 1/3 makes the passageway 2 wide (x) and 5 long (y).
     */
    data class RoomExit(val passageway: StaticTile, val transporter: StaticTile, val transporterLoc: Int)

    fun exitOf(room: TfaRoomId): RoomExit {
        val l = TfaIds.Loc
        return when (room) {
            TfaRoomId.EZEKIEL -> RoomExit(StaticTile(4085, 5533), StaticTile(4073, 5535), l.TRANSPORTER)
            TfaRoomId.DAGONHAI -> RoomExit(StaticTile(4088, 5597), StaticTile(4079, 5599), l.TRANSPORTER)
            TfaRoomId.MOIA -> RoomExit(StaticTile(4086, 5661), StaticTile(4074, 5663), l.TRANSPORTER_MOIA)
            TfaRoomId.XARTH -> RoomExit(StaticTile(4169, 5533), StaticTile(4178, 5535), l.TRANSPORTER)
            TfaRoomId.BORK -> RoomExit(StaticTile(4164, 5597), StaticTile(4180, 5599), l.TRANSPORTER)
            TfaRoomId.DREADNOTH -> RoomExit(StaticTile(4169, 5661), StaticTile(4177, 5663), l.TRANSPORTER_DREADNOTH)
            else -> error("$room has no passageway")
        }
    }

    /**
     * The antechamber tile of a paired room: on the passageway's middle row, [inset] tiles inside its room-side edge
     * (spec §3.5 "2 tiles inside the Passageway" [HOUSE]; `tfa.json nexus.antechamberInset`).
     * West rooms lie west of their passageway (x 4085-4088), east rooms east of it (x 4164-4170).
     */
    fun antechamber(room: TfaRoomId, inset: Int): StaticTile {
        val p = exitOf(room).passageway
        val midY = p.y + DOOR_WIDTH / 2
        return if (room.side == Side.WEST) StaticTile(p.x - inset, midY, 0) else StaticTile(p.x + 1 + inset, midY, 0)
    }

    /** True if [tile] is on the passageway footprint (2 x 5) of [room]. */
    fun onPassageway(room: TfaRoomId, tile: StaticTile): Boolean {
        val p = exitOf(room).passageway
        return tile.x in p.x..p.x + 1 && tile.y in p.y until p.y + DOOR_WIDTH
    }

    /** A static rectangle (both ends included). */
    data class Box(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
        operator fun contains(t: StaticTile): Boolean = t.x in minX..maxX && t.y in minY..maxY
        val centre: StaticTile get() = StaticTile((minX + maxX) / 2, (minY + maxY) / 2, 0)
    }

    /**
     * The fight area of each room, for "who is in the arena" and spectating (spec §2.4 arena column [CACHE];
     * Moia's box includes the moat ring around the 9x9 graveyard; the Guardian's is the atrium of the nexus;
     * Ocellus is the octagon x 4115-4139, y 5661-5685 of `rooms/ocellus/ArenaGeometry`). Only used for spectator
     * placement and dumps: who is "in the arena" is tracked per member ([TfaRaidMember.zone]), since every paired
     * arena is sealed and entered only through the transporter.
     */
    fun arena(room: TfaRoomId): Box = when (room) {
        TfaRoomId.GUARDIAN -> Box(4104, 5585, 4150, 5615)
        TfaRoomId.EZEKIEL -> Box(4059, 5531, 4067, 5539)
        TfaRoomId.BORK -> Box(4184, 5591, 4198, 5607)
        TfaRoomId.MOIA -> Box(4052, 5652, 4074, 5674)
        TfaRoomId.DREADNOTH -> Box(4184, 5656, 4198, 5670)
        TfaRoomId.DAGONHAI -> Box(4055, 5590, 4074, 5608)
        TfaRoomId.XARTH -> Box(4183, 5527, 4199, 5543)
        TfaRoomId.OCELLUS -> Box(4115, 5661, 4139, 5685)
    }

    /** Centre tile spectators are placed on (spec §3.13 "the current room's centre tile" [HOUSE]). */
    fun spectateCentre(room: TfaRoomId): StaticTile = when (room) {
        TfaRoomId.GUARDIAN -> StaticTile(4127, 5590, 0)        // south of the plinth (4127,5599)
        TfaRoomId.OCELLUS -> StaticTile(4127, 5664, 1)         // inside the vine entrance
        else -> arena(room).centre
    }

    /** Ocellus door footprint (7x2, rot 0) = `tfa_exit_large` 62766 at (4124,5625) [CACHE]. */
    val OCELLUS_DOOR = StaticTile(4124, 5625, 0)

    /** Guardian plinth centre (3x3) [CACHE]. */
    val GUARDIAN_PLINTH = StaticTile(4127, 5599, 0)

    /** The static nexus region box (map square 64,87) used for "in the nexus". */
    val NEXUS = Box(4096, 5568, 4159, 5631)

    /** The vault floor (map square 64,90). */
    val VAULT = Box(4096, 5760, 4159, 5823)

    /** The Ocellus corridor and arena (map square 64,88). */
    val OCELLUS_REGION = Box(4096, 5632, 4159, 5695)

    /** Records-room interior on the nexus variant planes 1/3 [CACHE]. */
    val RECORDS_ROOM = Box(4099, 5571, 4109, 5578)
}

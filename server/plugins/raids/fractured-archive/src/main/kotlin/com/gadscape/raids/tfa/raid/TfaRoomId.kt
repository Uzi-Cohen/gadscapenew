package com.gadscape.raids.tfa.raid

import com.gadscape.raids.tfa.ids.TfaIds

/** The side of the nexus a paired room is on (§3.5). Path varbit 16417 stores WEST as 0 and EAST as 1. */
enum class Side(val pathBit: Int) { WEST(0), EAST(1) }

/**
 * The eight encounters (spec §2.2). [id] is the cache room id (1..8); every other id is read through [TfaIds],
 * so the asset port's remap applies.
 *
 * Route: GUARDIAN → section 1 {EZEKIEL | BORK} → section 2 {MOIA | DREADNOTH} → Quartermaster →
 * section 3 {DAGONHAI | XARTH} → OCELLUS → vault.
 *
 * @property id room id used by the client and the cache tables (dbtable 259 rows, stats categories).
 * @property section nexus section (1..3) for the paired rooms, null for the Guardian and Ocellus.
 * @property side west or east door of [section], null for the Guardian and Ocellus.
 * @property configKey the room's key in config (`<configKey>.json`, `tfa.json rooms.<configKey>`, `::tfa skipto`).
 * @property statsTab tab index in the Alliance Log (enum 6066 order; 0 is Overall).
 * @property musicUnlockBit bit in music varp index [TfaIds.MusicUnlock.VARP_INDEX].
 */
enum class TfaRoomId(
    val id: Int,
    val section: Int?,
    val side: Side?,
    val configKey: String,
    val displayName: String,
    val statsTab: Int,
    val musicUnlockBit: Int,
    vararg aliases: String,
) {
    GUARDIAN(1, null, null, "guardian", "Archive Guardian", 1, TfaIds.MusicUnlock.GUARDIAN_BIT, "archive_guardian", "ag"),
    EZEKIEL(2, 1, Side.WEST, "ezekiel", "Ezekiel", 5, TfaIds.MusicUnlock.EZEKIEL_BIT, "zek"),
    XARTH(3, 3, Side.EAST, "xarth", "Xarth", 3, TfaIds.MusicUnlock.XARTH_BIT),
    DAGONHAI(4, 3, Side.WEST, "dagonhai", "Dagon'hai trio", 4, TfaIds.MusicUnlock.DAGONHAI_BIT, "trio", "dagon'hai"),
    BORK(5, 1, Side.EAST, "bork_smorks", "Bork and the Smorks", 7, TfaIds.MusicUnlock.BORK_BIT, "bork", "smorks"),
    MOIA(6, 2, Side.WEST, "moia", "Moia", 6, TfaIds.MusicUnlock.MOIA_BIT),
    DREADNOTH(7, 2, Side.EAST, "dreadnoth", "Dreadnoth the Jellied", 2, TfaIds.MusicUnlock.DREADNOTH_BIT, "jelly"),
    OCELLUS(8, null, null, "ocellus", "Ocellus", 8, TfaIds.MusicUnlock.OCELLUS_BIT, "blade"),
    ;

    /** Lower-case names accepted by [byName] (besides [name] and [configKey]). */
    val aliases: List<String> = aliases.toList()

    /** Static map square of the room (§2.4). */
    val staticRegion: Int
        get() = when (this) {
            GUARDIAN -> TfaIds.Region.NEXUS
            EZEKIEL -> TfaIds.Region.EZEKIEL
            XARTH -> TfaIds.Region.XARTH
            DAGONHAI -> TfaIds.Region.DAGONHAI
            BORK -> TfaIds.Region.BORK
            MOIA -> TfaIds.Region.MOIA
            DREADNOTH -> TfaIds.Region.DREADNOTH
            OCELLUS -> TfaIds.Region.OCELLUS
        }

    /** Stats category dbrow (dbtable 264). */
    val statsDbrow: Int
        get() = when (this) {
            GUARDIAN -> TfaIds.Dbrow.STATS_GUARDIAN
            EZEKIEL -> TfaIds.Dbrow.STATS_EZEKIEL
            XARTH -> TfaIds.Dbrow.STATS_XARTH
            DAGONHAI -> TfaIds.Dbrow.STATS_DAGONHAI
            BORK -> TfaIds.Dbrow.STATS_BORK
            MOIA -> TfaIds.Dbrow.STATS_MOIA
            DREADNOTH -> TfaIds.Dbrow.STATS_DREADNOTH
            OCELLUS -> TfaIds.Dbrow.STATS_OCELLUS
        }

    /** Encounter entry config dbrow (dbtable 259, values stripped). */
    val entryDbrow: Int
        get() = when (this) {
            GUARDIAN -> TfaIds.Dbrow.ENTRY_GUARDIAN
            EZEKIEL -> TfaIds.Dbrow.ENTRY_EZEKIEL
            XARTH -> TfaIds.Dbrow.ENTRY_XARTH
            DAGONHAI -> TfaIds.Dbrow.ENTRY_DAGONHAI
            BORK -> TfaIds.Dbrow.ENTRY_BORK
            MOIA -> TfaIds.Dbrow.ENTRY_MOIA
            DREADNOTH -> TfaIds.Dbrow.ENTRY_DREADNOTH
            OCELLUS -> TfaIds.Dbrow.ENTRY_OCELLUS
        }

    /** Seq prefetch clientscript, run on arena entry (Ocellus: corridor entry). */
    val prefetchScript: Int
        get() = when (this) {
            GUARDIAN -> TfaIds.Script.PREFETCH_GUARDIAN
            EZEKIEL -> TfaIds.Script.PREFETCH_EZEKIEL
            XARTH -> TfaIds.Script.PREFETCH_XARTH
            DAGONHAI -> TfaIds.Script.PREFETCH_DAGONHAI
            BORK -> TfaIds.Script.PREFETCH_BORK
            MOIA -> TfaIds.Script.PREFETCH_MOIA
            DREADNOTH -> TfaIds.Script.PREFETCH_DREADNOTH
            OCELLUS -> TfaIds.Script.PREFETCH_OCELLUS
        }

    /** Music dbrow played and unlocked at encounter start (Ocellus P3/P4: [TfaIds.Dbrow.MUSIC_OCELLUS_P3]). */
    val musicDbrow: Int
        get() = when (this) {
            GUARDIAN -> TfaIds.Dbrow.MUSIC_GUARDIAN
            EZEKIEL -> TfaIds.Dbrow.MUSIC_EZEKIEL
            XARTH -> TfaIds.Dbrow.MUSIC_XARTH
            DAGONHAI -> TfaIds.Dbrow.MUSIC_DAGONHAI
            BORK -> TfaIds.Dbrow.MUSIC_BORK
            MOIA -> TfaIds.Dbrow.MUSIC_MOIA
            DREADNOTH -> TfaIds.Dbrow.MUSIC_DREADNOTH
            OCELLUS -> TfaIds.Dbrow.MUSIC_OCELLUS
        }

    /** Midi of [musicDbrow] (Ocellus P3/P4: [TfaIds.Midi.OCELLUS_P3]). */
    val midi: Int
        get() = when (this) {
            GUARDIAN -> TfaIds.Midi.GUARDIAN
            EZEKIEL -> TfaIds.Midi.EZEKIEL
            XARTH -> TfaIds.Midi.XARTH
            DAGONHAI -> TfaIds.Midi.DAGONHAI
            BORK -> TfaIds.Midi.BORK
            MOIA -> TfaIds.Midi.MOIA
            DREADNOTH -> TfaIds.Midi.DREADNOTH
            OCELLUS -> TfaIds.Midi.OCELLUS
        }

    /** True for the six rooms behind section doors. */
    val isPaired: Boolean get() = section != null

    /** The other room of the same section, or null for the Guardian and Ocellus. */
    val pair: TfaRoomId?
        get() = if (section == null) null else entries.first { it.section == section && it.side != side }

    companion object {

        /** The rooms in route order: Guardian, sections 1-3 (west, east), Ocellus. */
        @JvmStatic
        val ROUTE_ORDER: List<TfaRoomId> = listOf(GUARDIAN, EZEKIEL, BORK, MOIA, DREADNOTH, DAGONHAI, XARTH, OCELLUS)

        @JvmStatic
        fun byId(id: Int): TfaRoomId? = entries.firstOrNull { it.id == id }

        /** The room of [section] (1..3) on [side]. */
        @JvmStatic
        fun of(section: Int, side: Side): TfaRoomId =
            entries.first { it.section == section && it.side == side }

        /** Case-insensitive lookup by enum name, [configKey], [displayName] or alias (`::tfa skipto`). */
        @JvmStatic
        fun byName(text: String): TfaRoomId? {
            val key = text.trim().lowercase()
            return entries.firstOrNull { room ->
                room.name.lowercase() == key || room.configKey == key || room.displayName.lowercase() == key ||
                    key in room.aliases
            }
        }
    }
}

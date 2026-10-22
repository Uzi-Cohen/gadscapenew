package com.gadscape.raids.tfa.raid

import com.gadscape.raids.tfa.TfaRaidDirectory
import com.gadscape.raids.tfa.config.RaidConfig
import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.encounter.TfaEncounter
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.world.entity.player.Player
import org.slf4j.LoggerFactory
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The raid registry (WU-12) and the framework's public entry points:
 * - [create] a raid from a Grouping Board party (the lobby's Archive Door, `::tfa start`);
 * - [archiveDoor] the whole spec §3.4 decision for the Archive Door 62768 "Enter";
 * - [raidOf] / [memberOf] / [contextOf] lookups;
 * - [requestStart] for encounters whose trigger is in their own code (Guardian Touch, Ocellus intro "Yes").
 *
 * Installed as `TfaServices.raids` ([TfaRaidDirectory]) at launch by `TfaModule`.
 */
object TfaRaids : TfaRaidDirectory {

    private val log = LoggerFactory.getLogger(TfaRaids::class.java)
    private val raids = ConcurrentHashMap<Int, TfaRaid>()
    private val ids = AtomicInteger()

    /** Seed source of the per-raid random (tests and `::tfa` can fix it). */
    @JvmStatic
    @Volatile
    var seedSource: () -> Long = { System.nanoTime() xor 0x5DEECE66DL }

    /** Live raids. */
    @JvmStatic
    fun all(): Collection<TfaRaid> = raids.values

    /** The most recently created raid that is still running (spectating "recent party", §3.13). */
    @JvmStatic
    fun mostRecent(): TfaRaid? = raids.values.filter { it.phase != RaidPhase.ENDED }.maxByOrNull { it.id }

    /**
     * Creates a raid for [party] (spec §2.3 CREATED): allocates the 24 x 40-chunk block, copies the nexus, builds the
     * Guardian, and puts the leader in the nexus. Members still in the lobby get "Step Inside Now!" (16386 = 2) and
     * enter with [TfaRaid.enter]. With `tfa.json lateJoin = never` every member in the lobby is taken in at once.
     * Returns null (with a message to the leader) if the map has no room or the raid cap is reached.
     */
    @JvmStatic
    fun create(party: TfaPartyView): TfaRaid? = create(party, party.members, party.leader)

    /** [create] for a roster without a Grouping Board party (`::tfa start` with no party). */
    @JvmStatic
    fun create(party: TfaPartyView?, roster: List<Player>, leader: Player): TfaRaid? {
        val config = TfaConfig.current
        val framework = config[FrameworkConfig]
        if (raids.size >= framework.raid.maxConcurrentRaids) {
            leader.sendMessage("The Archive is too crowded right now. Please try again in a moment.")
            return null
        }
        val members = roster.filter { !it.isFinished }.distinctBy { it.username.lowercase() }.take(TfaIds.Misc.MAX_PARTY_SIZE)
        if (members.isEmpty()) return null
        for (p in members) {
            raidOf(p)?.let {
                leader.sendMessage("${p.name} is already in a raid.")
                return null
            }
        }
        val instance = try {
            TfaBlockInstance.allocate()
        } catch (e: Exception) {
            log.error("Fractured Archive: no map space for a raid", e)
            leader.sendMessage("The Archive cannot be entered right now. Please try again later.")
            return null
        }
        val ordered = listOf(leader) + members.filter { it !== leader && it.username != leader.username }
        val raid = TfaRaid(ids.incrementAndGet(), party, config, Random(seedSource()), ordered, instance)
        raids[raid.id] = raid
        try {
            raid.init()
        } catch (e: Exception) {
            log.error("Fractured Archive: creating raid {} failed", raid.id, e)
            raids.remove(raid.id)
            instance.release()
            leader.sendMessage("The Archive cannot be entered right now. Please try again later.")
            return null
        }
        try {
            party?.onRaidCreated(raid)
        } catch (e: Exception) {
            log.error("Fractured Archive: party.onRaidCreated failed", e)
        }
        raid.memberOf(leader)?.let { raid.admit(it, leader) }
        if (config.raid.lateJoin == RaidConfig.LateJoinRule.NEVER) {
            for (m in raid.members) if (!m.entered && inLobby(m.player)) raid.admit(m)
        }
        return raid
    }

    /**
     * A member of [party] enters its existing raid (the lobby's Archive Door, §3.4). Silent: returns false when the
     * raid refuses (joins closed after the Guardian touch, `tfa.json lateJoin`) and the caller says "Your party has
     * already started."
     */
    @JvmStatic
    fun join(player: Player, party: TfaPartyView): Boolean {
        val raid = raids.values.firstOrNull { it.party === party && it.phase != RaidPhase.ENDED }
            ?: party.members.firstNotNullOfOrNull { raidOf(it) }
            ?: return false
        return raid.enter(player, quiet = true)
    }

    internal fun unregister(raid: TfaRaid) {
        raids.remove(raid.id)
    }

    /** True if [player] stands in the lobby (static region 16470). */
    @JvmStatic
    fun inLobby(player: Player): Boolean = player.location.regionId == TfaIds.Region.LOBBY

    /** Result of [archiveDoor]. */
    enum class DoorResult { CREATED, ENTERED, REFUSED, NOT_IN_PARTY }

    /**
     * Archive Door 62768 "Enter" (spec §3.4) for the lobby unit's loc handler:
     * - the leader of a party with no raid: every member must be in the lobby, nobody may hold unclaimed vault loot
     *   (varbit 16416), at most 8 members; then [create];
     * - a member while joins are open: [TfaRaid.enter];
     * - a member after the Guardian touch: "Your party has already started.";
     * - no party: [DoorResult.NOT_IN_PARTY] (the lobby opens 977).
     */
    @JvmStatic
    fun archiveDoor(player: Player, party: TfaPartyView?): DoorResult {
        if (party == null || party.members.none { it.username.equals(player.username, true) }) {
            player.sendMessage("You are not in a party.")
            return DoorResult.NOT_IN_PARTY
        }
        val existing = party.members.firstNotNullOfOrNull { raidOf(it) }
        if (existing != null) return if (existing.enter(player)) DoorResult.ENTERED else DoorResult.REFUSED
        if (!party.leader.username.equals(player.username, true)) {
            player.sendMessage("Your party leader must enter the Archive first.")
            return DoorResult.REFUSED
        }
        if (party.members.size > TfaIds.Misc.MAX_PARTY_SIZE) {
            player.sendMessage("Your party is too large to enter the Archive.")
            return DoorResult.REFUSED
        }
        val away = party.members.filter { !inLobby(it) || it.isFinished }
        if (away.isNotEmpty()) {
            player.sendMessage("Everyone in your party must be in the lobby before you start: ${away.joinToString { it.name }}.")
            return DoorResult.REFUSED
        }
        val loot = party.members.filter { it.varManager.getBitValue(TfaIds.Varbit.HAS_CLAIMABLE_LOOT) == 1 }
        if (loot.isNotEmpty()) {
            player.sendMessage("${loot.joinToString { it.name }} must claim their loot from the Loot Wheelbarrow first.")
            return DoorResult.REFUSED
        }
        return if (create(party) != null) DoorResult.CREATED else DoorResult.REFUSED
    }

    // ======================================================================================================== lookups

    /** The raid [player] is a member of (not left), or null. */
    @JvmStatic
    fun raidOf(player: Player): TfaRaid? {
        val name = player.username.lowercase()
        return raids.values.firstOrNull { r -> r.phase != RaidPhase.ENDED && r.members.any { it.username == name && !it.left } }
    }

    @JvmStatic
    fun memberOf(player: Player): TfaRaidMember? = raidOf(player)?.memberOf(player)

    /** The raid an encounter context belongs to (the context an encounter was created with), or null. */
    @JvmStatic
    fun raidOf(context: TfaEncounterContext): TfaRaid? = (context as? RoomRuntime)?.raid

    /**
     * An encounter asks its raid to start it (its trigger is in its own code: the Guardian's Touch, the Ocellus intro's
     * "Yes"). The raid fixes the scale, locks joins (Guardian), starts the timer, music and stats, then calls the
     * encounter's `start()`. Returns false when [context] is not a raid context (a standalone `::tfa <room>` debug run:
     * the caller starts itself) or the room is not ready.
     */
    @JvmStatic
    fun requestStart(context: TfaEncounterContext, by: Player?): Boolean {
        val rt = context as? RoomRuntime ?: return false
        return rt.raid.startEncounter(rt.room, by)
    }

    // ================================================================================================ TfaRaidDirectory

    override fun contextOf(player: Player): TfaEncounterContext? {
        val raid = raidOf(player) ?: return null
        return raid.activeRoom ?: raid.runtimes().lastOrNull()
    }

    override fun encounterOf(player: Player): TfaEncounter? {
        val raid = raidOf(player) ?: return null
        val member = raid.memberOf(player)
        val room = member?.let { raid.currentRoomOf(it) } ?: raid.activeRoom?.room
        return room?.let { raid.runtime(it)?.encounter } ?: raid.activeRoom?.encounter
    }

    override fun activeRaidCount(): Int = raids.size

    override fun describe(player: Player): List<String> {
        val raid = raidOf(player) ?: return listOf("You are not in a raid (${raids.size} running).")
        return raid.describe()
    }

    override fun toString(): String = "TfaRaids(${raids.size} raids)"
}

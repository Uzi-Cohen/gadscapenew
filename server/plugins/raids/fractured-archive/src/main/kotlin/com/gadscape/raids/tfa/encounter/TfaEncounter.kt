package com.gadscape.raids.tfa.encounter

import com.gadscape.raids.tfa.config.TfaConfigSnapshot
import com.gadscape.raids.tfa.raid.TfaInstance
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.world.entity.player.Player
import java.util.Random

/**
 * One boss room (spec §2.2). The raid (WU-12) creates it through the [TfaEncounterFactory] registered for its
 * [room], calls [build] when the room's region has been copied, [onArenaEntry] for each player who enters the
 * arena, [start] on the documented trigger (Touch, first transporter use, intro), then [tick] once per server
 * tick while it is active (§2.5 order: encounter, HUD, hazards, spectators), and finally [onComplete] or [onWipe],
 * followed by [teardown].
 *
 * Rules for implementations:
 * - Keep a tick counter and an action queue; never chain `WorldTasksManager.schedule` (a wipe must cancel all).
 * - Use [TfaEncounterContext.random] for every roll (seeded in tests).
 * - Read numbers from the room's config spec in [TfaEncounterContext.config], never from literals.
 * - Read ids through `TfaIds`.
 */
interface TfaEncounter {

    val room: TfaRoomId

    /** The raid-side context this encounter was created with. */
    val context: TfaEncounterContext

    /** Contribution points this room awards in total (§3.10, `points.json`, WU-16). */
    val contributionPool: Int

    /** Phase names in order, for `::tfa skipto <phase>` and Practice Mode skip-phase (e.g. `["p1", "p2"]`). */
    val phases: List<String>

    /** Spawns NPCs and locs before the fight (room region already copied). */
    fun build()

    /** A player arrives in the arena (transporter, door or intro). */
    fun onArenaEntry(player: Player)

    /** The fight begins; the room timer starts. */
    fun start()

    /** Once per server tick while the encounter is active. */
    fun tick()

    fun onPlayerDeath(player: Player)

    /** e.g. Dreadnoth: boss and every jelly dead. Polled by the raid after [tick]. */
    fun isComplete(): Boolean

    /** Stats, contribution banking, cleanup and the room exit hand-off. Called exactly once. */
    fun onComplete()

    /** Every member who entered the active encounter is dead. */
    fun onWipe()

    /** Despawns everything the encounter created and cancels its timeline. Always called last. */
    fun teardown()

    // ---- debug hooks (`::tfa`); the defaults report "not supported" -------------------------------------------

    /** `::tfa skipto <phase>`: jump to [phase] (one of [phases]). Return false if not supported. */
    fun debugSkipToPhase(phase: String): Boolean = false

    /** `::tfa hp <pct>`: set the current boss to [percent]% of its scaled max HP. Return false if not supported. */
    fun debugSetHpPercent(percent: Int): Boolean = false

    /** Extra lines for `::tfa dump` (state machine state, timers...). */
    fun debugState(): List<String> = emptyList()
}

/**
 * What the raid gives an encounter. Implemented by the raid (WU-12); tests and debug tools implement it with
 * fixed values (e.g. [com.gadscape.raids.tfa.raid.StaticTfaInstance]).
 */
interface TfaEncounterContext {

    val room: TfaRoomId

    /** The config snapshot the raid started with (§2.6: running raids keep their snapshot). */
    val config: TfaConfigSnapshot

    /** Scaling over [config]'s `scaling.json`. */
    val scaling: Scaling

    /** The party scale `n` fixed when this encounter started (2..8; see [Scaling.partyScale]). */
    val scale: Int

    /** Coordinate mapping of the raid's map space. */
    val instance: TfaInstance

    /** The raid's random source; seeded in tests. */
    val random: Random

    /** Raid members in slot order (the order sets HUD orb slots). Includes dead members. */
    val members: List<Player>

    /** Living members currently inside this encounter's arena. */
    fun playersInArena(): List<Player>

    /** Server ticks since [TfaEncounter.start] (0 before the start). */
    val encounterTicks: Int
}

/** Creates the encounter for a room of one raid. Registered with [TfaEncounters.register]. */
fun interface TfaEncounterFactory {
    fun create(context: TfaEncounterContext): TfaEncounter
}

/**
 * Where encounters plug in. Each encounter unit registers its factory once at server launch, from its own
 * `@Subscribe` `ServerLaunchEvent` handler (order does not matter):
 * ```
 * object ArchiveGuardianModule {
 *     @Subscribe @JvmStatic fun onServerLaunch(event: ServerLaunchEvent) {
 *         TfaConfig.register(GuardianConfig)
 *         TfaEncounters.register(TfaRoomId.GUARDIAN) { ctx -> ArchiveGuardianRoom(ctx) }
 *     }
 * }
 * ```
 * The raid asks [factory] when it builds a room; a room without a factory cannot be entered (and `::tfa dump`
 * lists it as missing).
 */
object TfaEncounters {

    private val factories = java.util.concurrent.ConcurrentHashMap<TfaRoomId, TfaEncounterFactory>()

    /** Registers (or replaces, with a warning in the log) the factory for [room]. */
    @JvmStatic
    fun register(room: TfaRoomId, factory: TfaEncounterFactory) {
        val previous = factories.put(room, factory)
        if (previous != null && previous !== factory) {
            org.slf4j.LoggerFactory.getLogger(TfaEncounters::class.java)
                .warn("Fractured Archive: encounter factory for {} replaced", room)
        }
    }

    @JvmStatic
    fun factory(room: TfaRoomId): TfaEncounterFactory? = factories[room]

    @JvmStatic
    fun isRegistered(room: TfaRoomId): Boolean = factories.containsKey(room)

    /** Rooms with a registered factory, in route order. */
    @JvmStatic
    fun registered(): List<TfaRoomId> = TfaRoomId.ROUTE_ORDER.filter { factories.containsKey(it) }

    /** Rooms still missing a factory, in route order. */
    @JvmStatic
    fun missing(): List<TfaRoomId> = TfaRoomId.ROUTE_ORDER.filterNot { factories.containsKey(it) }
}

package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.raid.StaticTile

/**
 * The three Dagon'hai (spec §4.6 identity table). The order is the cache suffix order `_01` / `_02` / `_03` used by
 * the TfaIds families (channel seqs and vfx), so `ordinal` indexes them.
 */
enum class Role(val key: String) {
    /** Dagon'hai Elder 16643 (unshielded 16644). Autos every 6 t; channels; never throws tomes. */
    ELDER("elder"),

    /** Dagon'hai Monk, staff 16645 (unshielded 16646). Searches desks, throws tomes or channels. */
    STAFF("staff"),

    /** Dagon'hai Monk, book 16647 (unshielded 16648). Searches desks, throws tomes or channels. */
    BOOK("book");

    val isMonk: Boolean get() = this != ELDER

    companion object {
        fun byKey(key: String): Role? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** The tome gods (spec "Tome effects"), in the TfaIds family order: Zamorak, Saradomin, Bandos. */
enum class God(val key: String, val line: String) {
    ZAMORAK("zamorak", "Zamorak empowers us!"),
    SARADOMIN("saradomin", "Saradominist zeal, feed the flame!"),
    BANDOS("bandos", "Bandosian strength, feed the flame!");

    companion object {
        fun byKey(key: String): God? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) || it.name.equals(key, true) }
    }
}

/** The protection prayers the room checks. */
enum class Protect { MELEE, MISSILES, MAGIC }

/** The trio's attack styles; the world rolls accuracy and the protection prayer is checked when the hit lands. */
enum class Style { RANGED, MAGIC }

/** What the trio NPCs accept from players (the room's view of the shield, spec state machine). */
enum class ShieldState {
    /** Shared pool, attackable. */
    SHIELDED,

    /** Zamorak protection after a break: invulnerable, not attackable. */
    PROTECTED,

    /** After the explosion: separate 7-HP NPCs, attackable, normal health bars. */
    UNSHIELDED,
}

/**
 * A player as the encounter logic sees it. The room wraps each `Player` once (stable identity: the Elder keeps a
 * focus target, demonic strength and burns are tracked per fighter); tests use plain fakes.
 */
interface Fighter {
    /** The player's static tile (instance coordinates translated back). */
    val tile: StaticTile

    /** Alive and inside this arena. */
    val alive: Boolean

    fun praying(protect: Protect): Boolean
}

/**
 * Everything [DagonhaiFight] does to the world. [DagonhaiRoom] implements it over the engine; unit tests record the
 * calls. Coordinates are static (the room translates them through the raid's `TfaInstance`). Trio positions are the
 * south-west tiles of their 2x2 footprints.
 */
interface DagonhaiHost {

    // ------------------------------------------------------------------------------------------------- players

    /** Players in the arena (alive ones; a dead or departed one may still be listed with `alive == false`). */
    fun fighters(): List<Fighter>

    // ------------------------------------------------------------------------------------------------- the trio

    /** South-west tile of [role]'s 2x2 footprint, or null if it is not spawned. */
    fun trioTile(role: Role): StaticTile?

    fun trioAnimate(role: Role, seq: Int)

    /** A spotanim on the NPC; a negative [spot] clears the slot (stops a looping channel vfx). */
    fun trioGraphic(role: Role, spot: Int)

    fun trioSay(role: Role, text: String)

    fun trioFace(role: Role, target: Fighter)

    fun trioFaceTile(role: Role, tile: StaticTile)

    /** Walks [role] to [tile] (south-west tile) on the engine's route finder. */
    fun trioWalk(role: Role, tile: StaticTile)

    /** True while [role] still has walk steps queued. */
    fun trioMoving(role: Role): Boolean

    /** Stops any walk in progress. */
    fun trioStop(role: Role)

    /** Puts [role] on [tile] at once (a stuck monk). */
    fun trioPlace(role: Role, tile: StaticTile)

    /**
     * The shared pool changed: every shielded NPC's HP mirrors [pool] (or stays full while [pool] is 0 during the
     * protection, when nothing can hit them) and the pool headbar shows [pool] / [max] on all three.
     */
    fun trioPool(pool: Int, max: Int)

    /** What the trio NPCs accept from players. */
    fun trioShield(state: ShieldState)

    /** Empowered trio: protection prayers only reduce their magic by `1 - empowered.prayerMult`. */
    fun trioEmpowered(empowered: Boolean)

    /** Headbar 85 filling over [ticks] ticks on [role]; 0 removes it. */
    fun chargeBar(role: Role, ticks: Int)

    /** Turns [role] into its unshielded NPC (16644 / 16646 / 16648) with [hp] hitpoints and a normal health bar. */
    fun trioUnshield(role: Role, hp: Int)

    /** [role] dies in the explosion: no longer attackable; the death seq and vfx are played by the fight. */
    fun trioKill(role: Role)

    /** Removes [role]'s NPC (after its death animation). */
    fun trioDespawn(role: Role)

    /** An attack from [role] landing on [target] now: accuracy roll, max [max]; the protection prayer applies now. */
    fun trioAttack(role: Role, target: Fighter, style: Style, max: Int)

    // ------------------------------------------------------------------------------------------------ the cauldron

    fun cauldronAnimate(seq: Int)

    fun cauldronGraphic(spot: Int)

    /** Swaps the cauldron's model to the exploding one (62612) so seq 14679 animates it. */
    fun cauldronExplodeModel()

    fun cauldronDespawn()

    /** HP HUD on the cauldron 16649: main bar stability / max (shown in %), extra bar ritual progress. */
    fun ritualHud(stability: Int, stabilityMax: Int, ritual: Int, ritualMax: Int)

    /** Loc anim on the Book of Life and Death (62552). */
    fun bookAnimate(seq: Int)

    // ------------------------------------------------------------------------------------------- effects on players

    /** Unprayable damage of exactly [amount] now (typeless, or the BURN hitsplat); nothing for godmode players. */
    fun damage(target: Fighter, amount: Int, burn: Boolean = false)

    /** Restores [hp] hitpoints (up to the maximum) and [prayer] prayer points. */
    fun heal(target: Fighter, hp: Int, prayer: Int)

    fun playerGraphic(target: Fighter, spot: Int, height: Int = 0)

    /** A map spotanim on [tile], shown after [delayCycles] client cycles. */
    fun graphic(spot: Int, tile: StaticTile, height: Int = 0, delayCycles: Int = 0)

    /** A projectile from [from] (null = the cauldron) that follows [target]. */
    fun projectile(from: Role?, spot: Int, spec: DagonhaiConfig.ProjectileSpec, target: Fighter)

    /** A projectile from [from] (null = the cauldron) to [tile]. */
    fun projectile(from: Role?, spot: Int, spec: DagonhaiConfig.ProjectileSpec, tile: StaticTile)

    // ------------------------------------------------------------------------------------------------ the adds

    /** A Demonic rift (16651) on [tile]; returns a handle for [despawnRift]. */
    fun spawnRift(tile: StaticTile): Int

    fun despawnRift(handle: Int)

    /** An Imp (16650) dropping out of a rift on [tile], aggressive to [target]. */
    fun spawnImp(tile: StaticTile, target: Fighter?)

    /** Imps alive (dying ones excluded). */
    fun impsAlive(): Int

    /** Removes every imp and rift at once (explosion, completion, teardown). */
    fun despawnAdds()
}

package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.raid.StaticTile

/** The protection prayers the room checks. */
enum class Protect { MELEE, MISSILES, MAGIC }

/** The attack styles of the boss and the jellies; the world rolls accuracy and checks prayer when the hit lands. */
enum class AttackStyle { MELEE, RANGED }

/**
 * A player as the encounter logic sees it. The room wraps each `Player` once (stable identity, so the boss can keep a
 * target between ticks); tests use plain fakes.
 */
interface Fighter {
    /** The player's static tile (instance coordinates translated back). */
    val tile: StaticTile

    /** Alive and inside this arena. */
    val alive: Boolean

    fun praying(protect: Protect): Boolean
}

/**
 * Everything [DreadnothBrain] does to the world. [DreadnothRoom] implements it over the engine; unit tests record the
 * calls. Coordinates are static; the room translates them.
 */
interface DreadnothHost {

    // ----------------------------------------------------------------------------------------------- the boss

    /** South-west tile of the boss's 5x5 footprint. */
    val bossSw: StaticTile
    val bossHp: Int
    val bossMaxHp: Int

    fun bossAnimate(seq: Int)
    fun bossFace(target: Fighter?)

    /** One walk step (engine collision applies; the diagonal falls back to one axis). False if he could not move. */
    fun bossStep(dx: Int, dy: Int): Boolean

    /** Stops any queued walk step. */
    fun bossHalt()

    /** Moves the boss onto [to] now and shows a flight from [from] between client cycles [startCycle]..[endCycle]. */
    fun bossLeap(from: StaticTile, to: StaticTile, startCycle: Int, endCycle: Int)

    /** False while airborne or dying: players cannot attack him. */
    fun bossTargetable(targetable: Boolean)

    fun bossHeal(amount: Int)
    fun bossDespawn()

    // ---------------------------------------------------------------------------------------------- players

    /** Players in the arena (alive ones; a dead one may still be listed with `alive == false`). */
    fun fighters(): List<Fighter>

    /** An NPC attack of [style] landing now: accuracy roll, max [max], protection prayer checked now. */
    fun attack(target: Fighter, style: AttackStyle, max: Int)

    /** A typeless hit of exactly [amount] landing now; returns the damage dealt (0 for a godmode player). */
    fun damage(target: Fighter, amount: Int): Int

    // -------------------------------------------------------------------------------------------- the world

    /** A map graphic (telegraph) on [tile]. */
    fun graphic(spot: Int, tile: StaticTile)

    /** Projectile [spot] from the boss to a player (it follows the player). */
    fun projectile(spot: Int, spec: DreadnothConfig.ProjectileSpec, target: Fighter)

    /** Projectile [spot] from the boss to a tile. */
    fun projectile(spot: Int, spec: DreadnothConfig.ProjectileSpec, tile: StaticTile)

    /** A blood tile (62609) on [tile]: 6-8 per tick to players standing on it, for its lifetime. */
    fun blood(tile: StaticTile)

    /** A threshold batch of [count] jellies forming on free tiles (§4.5 "Jellies"). */
    fun spawnJellies(count: Int)

    /** The death wave: [count] jellies hop out of the boss's footprint. */
    fun spawnDeathWave(count: Int)

    /** Entering the 25% enrage (the wiki's voice line is not known; nothing is said). */
    fun onEnrage() {}
}

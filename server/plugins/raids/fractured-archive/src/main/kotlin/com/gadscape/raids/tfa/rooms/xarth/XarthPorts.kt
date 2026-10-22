package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.raids.tfa.raid.StaticTile

/**
 * The seam between the engine-free boss script ([XarthBrain]) and the world ([XarthRoom]): the brain decides what
 * happens and when; the host performs it on NPCs, players, locs and the client. Unit tests drive the brain with a
 * recording host.
 */

/** A player in the arena, as the brain sees it. */
interface Fighter {
    /** Static tile (spec coordinates). */
    val tile: StaticTile

    /** Alive and inside the arena. */
    val alive: Boolean
}

/** Xarth's attack styles; the host maps them to engine hit types (and the protection prayer that blocks them). */
enum class AttackStyle { MAGIC, MELEE, RANGED }

/** The three projectile kinds of the room (config `projectiles.*`). */
enum class Proj { MAGIC, RANGED, BOMB }

interface XarthHost {

    // ------------------------------------------------------------------------------------------------------ boss

    val bossHp: Int
    val bossMaxHp: Int

    fun bossAnimate(seq: Int)

    fun bossGraphic(spot: Int)

    /** Turns him toward [target] (face entity). */
    fun bossFace(target: Fighter)

    /** Turns him toward a static [tile]. */
    fun bossFaceTile(tile: StaticTile)

    /** Heals him by up to [amount] (capped at his max), shown as a HEALED splat. Returns what was healed. */
    fun bossHeal(amount: Int): Int

    /** False while dying: no clicks, no area attacks. */
    fun bossTargetable(targetable: Boolean)

    fun bossDespawn()

    // --------------------------------------------------------------------------------------------------- players

    fun fighters(): List<Fighter>

    /**
     * Xarth's accuracy roll and damage roll for [style] (max [max]); the hit lands now and the matching protection
     * prayer blocks it as it lands. Returns true when the accuracy roll succeeded (the magic burn applies then, even if
     * the prayer zeroes the damage [spec §4.7]).
     */
    fun attack(target: Fighter, style: AttackStyle, max: Int): Boolean

    /** A typeless hit (no prayer) landing now. Returns the damage dealt (0 for godmode). */
    fun damage(target: Fighter, amount: Int): Int

    /** Burn stacks (E10): 1 per 4 t per stack, BURN splat, not reduced by prayer. */
    fun burn(target: Fighter, stacks: Int)

    /** The TD bind: [ticks] frozen, run switched off. */
    fun bind(target: Fighter, ticks: Int)

    /** Called every tick while bound: keeps run off. */
    fun holdBind(target: Fighter)

    /** The bind is over: run back on if it was on (config `blast.restoreRun`). */
    fun release(target: Fighter)

    fun playerGraphic(target: Fighter, spot: Int)

    /** A map graphic on a static tile. */
    fun tileGraphic(spot: Int, tile: StaticTile)

    fun projectile(kind: Proj, target: Fighter)

    fun projectile(kind: Proj, tile: StaticTile)

    /** The engine's map line of sight from his centre to [target] (`losTest = "engine"`): true when blocked. */
    fun losBlocked(target: Fighter): Boolean

    // ---------------------------------------------------------------------------------------- arena objects

    /** Removes the pillar's Rubble loc and turns its helper 16685 into the debris 16686 (14997 + 4382). */
    fun collapsePillar(pillar: Pillar)

    /** Swaps a corpse pile to its fire variant (true) or back. */
    fun pileFire(pile: Pile, burning: Boolean)

    /** A corpse appears on the pile's arena entry tile. */
    fun spawnCorpse(pile: Pile)

    /** Corpses still walking or spawning (dying and absorbed ones excluded). */
    fun corpsesAlive(): Int

    /** Every corpse dies (his death). */
    fun killCorpses()

    /** A flame loc 62713 lands on [tile]. */
    fun flameSpawn(tile: StaticTile)

    fun flameRemove(tile: StaticTile)
}

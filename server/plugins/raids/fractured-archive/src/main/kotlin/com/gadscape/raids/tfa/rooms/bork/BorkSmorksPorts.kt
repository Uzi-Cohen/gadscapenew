package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.raid.StaticTile

/**
 * A player as the encounter logic sees it. The room wraps each `Player` once (stable identity, so Bork can keep a
 * target between ticks); tests use plain fakes.
 */
interface Fighter {
    /** The player's static tile (instance coordinates translated back). */
    val tile: StaticTile

    /** Alive and inside this arena. */
    val alive: Boolean

    /** Display name (for "Bork smash <name> now."). */
    val name: String
}

/** Style of one Smorks auto volley: bones (ranged) or a cluster of potions (magic) [DOC Strategies]. */
enum class VolleyStyle { RANGED, MAGIC }

/** Which of Bork's hits: decides the max hit and whether the accuracy roll applies. */
enum class BorkHit { BEAT, STOMP, GONG }

/**
 * Everything [BorkSmorksBrain] does to the world. [BorkSmorksRoom] implements it over the engine; unit tests record
 * the calls. Coordinates are static; the room translates them.
 */
interface BorkHost {

    // ------------------------------------------------------------------------------------------------- Bork

    /** South-west tile of Bork's 4x4, or null once he is gone. */
    val borkSw: StaticTile?
    val borkHp: Int
    val borkMaxHp: Int

    fun borkAnimate(seq: Int)

    /** A spotanim on Bork (the engine has one NPC spotanim slot: a later one replaces it). */
    fun borkSpot(spot: Int)

    /** A map graphic centred on Bork (for a second simultaneous graphic, e.g. the stun stars 4035 over a daze spot). */
    fun borkSpotAtCentre(spot: Int)

    fun borkSay(text: String)
    fun borkFace(target: Fighter?)

    /** One chase step toward [target] (the engine's dumb follow: no path around the cart, as NPCs chase on live). */
    fun borkChase(target: Fighter)

    /** Stops any queued walk step. */
    fun borkHalt()

    /** Dazed on/off: flat armour and magic resistance 0, player melee skips the accuracy roll (his traits read it). */
    fun borkDazed(dazed: Boolean)

    /**
     * One of Bork's hits on [target] landing now: crush, accuracy roll when [rollAccuracy], 0..[max]; Protect from Melee
     * applies as it lands (× `pfmMult` through his prayer multiplier).
     */
    fun borkHit(target: Fighter, kind: BorkHit, max: Int, rollAccuracy: Boolean)

    /** Typeless damage to Bork that no player dealt (an incendiary cannonball landing on him). */
    fun borkDamage(amount: Int)

    fun borkTargetable(targetable: Boolean)
    fun borkDespawn()

    /** `tfa_client_primary<slot>` = 1 for Bork's target (null clears every slot). */
    fun primaryTarget(target: Fighter?)

    // ----------------------------------------------------------------------------------------------- Smorks

    val smorksHp: Int
    val smorksMaxHp: Int

    fun smorksAnimate(seq: Int)
    fun smorksSpot(spot: Int)

    /** A map graphic on the cart's centre tile (the Smorks' own centre: exact for a size-3 NPC). */
    fun smorksSpotAtCentre(spot: Int, delayCycles: Int = 0)

    fun smorksSay(text: String)
    fun smorksFace(target: Fighter?)

    /** Volley projectile [spot] to [target] (follows the player); returns the ticks until it lands. */
    fun smorksProjectile(spot: Int, spec: BorkSmorksConfig.ProjectileSpec, target: Fighter): Int

    /** Cannonball [spot] lobbed onto [tile], landing exactly [flightTicks] ticks after this one. */
    fun smorksLob(spot: Int, spec: BorkSmorksConfig.ArcSpec, tile: StaticTile, flightTicks: Int)

    /**
     * A volley hit landing now on [target]: [style] accuracy roll, 0..[max]; the protection prayer is checked as it
     * lands (blocks 100%); then its impact graphics (4055, or 4058 at the tile + 4059).
     */
    fun smorksVolleyHit(target: Fighter, style: VolleyStyle, max: Int)

    fun smorksTargetable(targetable: Boolean)
    fun smorksDespawn()

    // -------------------------------------------------------------------------------------------- the world

    /** All players in the arena; ones that died or left have `alive == false`. Bork's target is chosen from these. */
    fun fighters(): List<Fighter>

    /** A map graphic on [tile], [delayCycles] client cycles late. */
    fun graphic(spot: Int, tile: StaticTile, delayCycles: Int = 0)

    /** Typeless damage of exactly [amount] on [target] now (cannonballs, oil). */
    fun damage(target: Fighter, amount: Int)

    /** Adds [stacks] burn stacks (E10) to [target]. */
    fun burn(target: Fighter, stacks: Int)

    /** A new oil tile (puddle loc + splat) / its fade / its removal. */
    fun oilSpawn(tile: StaticTile, rotation: Int)
    fun oilFade(tile: StaticTile, rotation: Int)
    fun oilRemove(tile: StaticTile)
}

package com.gadscape.raids.tfa.rooms.ezekiel

/**
 * Who tanks Ezekiel (spec §4.2 Tank rules [COMM + HOUSE]): "The first player to enter the room is marked as the tank
 * until they die" (wiki Strategies); the first transporter user is that player (§2.4 room flow). After the tank dies
 * (or leaves the arena) the next player in transporter order who is alive on the floor takes over.
 *
 * Generic over the player type so it is unit-tested without a world.
 */
class TankOrder<P> {

    private val order = ArrayList<P>()

    /** The current tank, or null before anyone entered or when nobody eligible is left. */
    var tank: P? = null
        private set

    /** Entry order (transporter uses), first entry first; re-entries keep the first position. */
    val entries: List<P> get() = order

    /** Records an arena entry; returns true if it was the player's first. */
    fun entered(p: P): Boolean {
        if (p in order) return false
        order += p
        return true
    }

    /**
     * Makes sure the tank is eligible ([eligible]: alive and on the floor); otherwise picks the first eligible player
     * in entry order. Returns the new tank when it changed (null when it did not change or nobody is eligible).
     */
    fun refresh(eligible: (P) -> Boolean): P? {
        val current = tank
        if (current != null && eligible(current)) return null
        val next = order.firstOrNull(eligible)
        tank = next
        return if (next != null && next != current) next else null
    }

    /** The tank is gone (death, logout): the next eligible player in entry order takes over (returned if any). */
    fun lost(p: P, eligible: (P) -> Boolean): P? {
        if (tank != p) return null
        tank = null
        return refresh { it != p && eligible(it) }
    }

    /** Forces [p] as the tank (debug). */
    fun force(p: P) {
        if (p !in order) order += p
        tank = p
    }

    fun clear() {
        order.clear()
        tank = null
    }
}

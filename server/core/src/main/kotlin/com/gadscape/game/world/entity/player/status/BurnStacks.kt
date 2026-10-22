package com.gadscape.game.world.entity.player.status

/**
 * Burn stacks on one entity, free of world state (spec §2.7 E10, OSRS wiki "Burn"):
 * - up to [maxStacks] stacks at once; a further stack is discarded, the oldest is not replaced;
 * - one shared clock: every [interval] ticks the entity takes damage equal to the number of stacks;
 * - each stack deals [hitsPerStack] hits (10 × 1 damage over 40 ticks), then expires.
 *
 * The clock starts when the first stack lands (first damage [interval] ticks later). A stack added to a running burn
 * joins the clock and takes its first hit on the next beat.
 */
class BurnStacks @JvmOverloads constructor(
    val maxStacks: Int = 5,
    val interval: Int = 4,
    val hitsPerStack: Int = 10,
) {
    private val remaining = IntArray(maxStacks)
    private var size = 0
    private var clock = 0

    /** Stacks currently burning. */
    val stacks: Int get() = size

    val isEmpty: Boolean get() = size == 0

    /** Damage still to come if nothing is added (each remaining hit deals 1 per stack). */
    val remainingDamage: Int get() = (0 until size).sumOf { remaining[it] }

    /** Ticks until the next damage beat (0 when not burning). */
    val ticksToNextHit: Int get() = if (size == 0) 0 else clock

    /** Adds one stack; false (and nothing changes) when [maxStacks] are already burning. */
    fun add(): Boolean {
        if (size >= maxStacks) return false
        if (size == 0) clock = interval
        remaining[size++] = hitsPerStack
        return true
    }

    /** Adds up to [count] stacks; returns how many were added. */
    fun add(count: Int): Int {
        var added = 0
        while (added < count && add()) added++
        return added
    }

    /**
     * Advances one tick. Returns the damage dealt on this tick: the stack count on a beat, else 0. Stacks that have
     * dealt all their hits are removed after the beat.
     */
    fun tick(): Int {
        if (size == 0) return 0
        if (--clock > 0) return 0
        clock = interval
        val damage = size
        var write = 0
        for (read in 0 until size) {
            val left = remaining[read] - 1
            if (left > 0) remaining[write++] = left
        }
        size = write
        return damage
    }

    fun clear() {
        size = 0
        clock = 0
    }
}

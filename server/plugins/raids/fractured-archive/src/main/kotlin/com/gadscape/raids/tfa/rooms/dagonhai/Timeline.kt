package com.gadscape.raids.tfa.rooms.dagonhai

import java.util.PriorityQueue

/**
 * The room's scripted timeline (spec §2.5): a tick counter and a queue of cancellable actions. Nothing in the room
 * uses `WorldTasksManager.schedule`, so a break, the explosion, a wipe or [cancelAll] in teardown stops everything.
 *
 * - [advance] moves to the next tick and runs every action due on it, in the order they were scheduled. An action
 *   scheduled for the current tick from inside another action runs in the same pass.
 * - Actions carry a [Tag], so a phase change can cancel one family (the trio's wind-ups) and keep another (a tome or
 *   a projectile already in the air, hazards already on the floor).
 */
class Timeline {

    enum class Tag {
        /** The trio's own sequences: attack wind-ups, monk search and throw steps, channel completion. */
        TRIO,
        /** A hit or effect of something already released (projectile in flight, tome in the air). */
        HIT,
        /** Floor hazards already telegraphed (rings, fireballs, storm strikes) and burns. */
        HAZARD,
        /** Protection phase steps (imp drops, dome). */
        PROTECT,
        /** Explosion and final deaths. */
        FINALE,
        /** Demonic strength glows. */
        BUFF,
        OTHER,
    }

    class Handle internal constructor(val tick: Int, val tag: Tag, internal val seq: Long, internal val action: () -> Unit) {
        var cancelled: Boolean = false
            private set

        fun cancel() {
            cancelled = true
        }
    }

    /** The current tick (0 before the first [advance]). */
    var now: Int = 0
        private set

    private var seq = 0L
    private val queue = PriorityQueue<Handle>(compareBy<Handle>({ it.tick }, { it.seq }))

    /** Runs [action] [delay] ticks from now (0 = later in the current tick's pass, or at the next [runDue]). */
    fun schedule(delay: Int, tag: Tag = Tag.OTHER, action: () -> Unit): Handle {
        require(delay >= 0) { "delay must be >= 0, got $delay" }
        val handle = Handle(now + delay, tag, seq++, action)
        queue.add(handle)
        return handle
    }

    /** Moves to the next tick and runs everything due. */
    fun advance() {
        step()
        runDue()
    }

    /** Moves to the next tick without running anything (call [runDue] after the pending checks, §2.5). */
    fun step() {
        now++
    }

    /** Runs every action due at or before [now] (also those added while running). */
    fun runDue() {
        while (true) {
            val head = queue.peek() ?: return
            if (head.tick > now) return
            queue.poll()
            if (!head.cancelled) head.action()
        }
    }

    fun cancel(tag: Tag) {
        queue.forEach { if (it.tag == tag) it.cancel() }
        queue.removeIf { it.cancelled }
    }

    fun cancelAll() {
        queue.forEach { it.cancel() }
        queue.clear()
    }

    /** Number of live (not cancelled) actions with [tag], or of all tags when null. */
    fun pending(tag: Tag? = null): Int = queue.count { !it.cancelled && (tag == null || it.tag == tag) }
}

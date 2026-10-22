package com.gadscape.raids.tfa.rooms.bork

import java.util.PriorityQueue

/**
 * The room's scripted timeline (spec §2.5): a tick counter and a queue of tagged, cancellable actions. Nothing in the
 * room chains `WorldTasksManager.schedule`, so a death, a wipe or [cancelAll] in teardown stops everything at once.
 *
 * - [advance] moves to the next tick and runs every action due on it, in scheduling order; an action queued for the
 *   current tick while the queue runs is run in the same pass.
 * - [after] with 0 called while the queue runs is run in the same pass; called outside it (from the brain's tick
 *   step, which runs after the queue) it runs on the next [advance], i.e. one tick later. The brain therefore does
 *   "now" work directly and only queues the future.
 * - Tags let a death cancel one NPC's script ([Tag.BORK] / [Tag.SMORKS]) and keep the death sequence, the cannonballs
 *   already in flight and the oil.
 */
class Timeline {

    enum class Tag {
        /** Bork's sequences: daze end, stomp/gong steps and their impacts, pending beat impacts. */
        BORK,
        /** The Smorks' attack steps that are not yet fired. */
        SMORKS,
        /** Projectiles already fired (volley hits, cannonball impacts): they land even if the Smorks die meanwhile. */
        FLIGHT,
        /** Death sequences and despawns. */
        DEATH,
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
    private var running = false

    /** Runs [action] [delay] ticks from now (0 = later in the current pass when called from inside the queue). */
    fun after(delay: Int, tag: Tag, action: () -> Unit): Handle {
        require(delay >= 0) { "delay must be >= 0, got $delay" }
        val handle = Handle(now + delay, tag, seq++, action)
        queue.add(handle)
        return handle
    }

    /** Moves to the next tick and runs everything due. */
    fun advance() {
        now++
        running = true
        try {
            while (true) {
                val head = queue.peek() ?: return
                if (head.tick > now) return
                queue.poll()
                if (!head.cancelled) head.action()
            }
        } finally {
            running = false
        }
    }

    /** Cancels every pending action of [tag]; returns how many. */
    fun cancel(tag: Tag): Int {
        var n = 0
        for (h in queue) {
            if (h.tag == tag && !h.cancelled) {
                h.cancel()
                n++
            }
        }
        queue.removeIf { it.cancelled }
        return n
    }

    fun cancelAll() {
        queue.forEach { it.cancel() }
        queue.clear()
    }

    /** Live (not cancelled) actions of [tag], or of every tag when null. */
    fun pending(tag: Tag? = null): Int = queue.count { !it.cancelled && (tag == null || it.tag == tag) }

    /** The earliest tick a live action of [tag] is due, or null. */
    fun nextDue(tag: Tag): Int? = queue.filter { !it.cancelled && it.tag == tag }.minOfOrNull { it.tick }

    /** Whether the queue is running right now (an [after] with 0 runs in this pass). */
    val isRunning: Boolean get() = running
}

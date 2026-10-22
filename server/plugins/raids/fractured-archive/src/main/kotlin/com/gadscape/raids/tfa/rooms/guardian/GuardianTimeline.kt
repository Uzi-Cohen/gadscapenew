package com.gadscape.raids.tfa.rooms.guardian

import java.util.PriorityQueue

/**
 * The room's scripted timeline (spec §2.5): a tick counter and a queue of cancellable scheduled actions. Nothing in
 * the room chains `WorldTasksManager.schedule`, so [cancel] / [cancelAll] stop everything a wipe or teardown must stop.
 *
 * - [advance] moves to the next tick and runs every action due on it, in schedule order. An action scheduled with
 *   delay 0 while the tick runs still runs on this tick (after the ones already queued).
 * - Every action carries a [tag]; [cancel] drops all actions with that tag (e.g. `"attack"` when the shield breaks).
 * - [every] repeats an action until its tag is cancelled or the action returns false.
 *
 * Not thread-safe: it lives on the world thread like the rest of the encounter.
 */
class GuardianTimeline {

    /** The current tick (0 before the first [advance]). */
    var now: Int = 0
        private set

    private var sequence = 0L
    private var running = false

    /** One queued action (internal state; use [Handle]). */
    class Entry internal constructor(
        internal val at: Int,
        internal val seq: Long,
        internal val tag: String,
        internal val action: () -> Unit,
    ) {
        internal var cancelled = false
    }

    private val queue = PriorityQueue<Entry>(compareBy<Entry>({ it.at }, { it.seq }))

    /** Handle of one scheduled action. */
    inner class Handle internal constructor(internal val entry: Entry) {
        val tick: Int get() = entry.at
        val isPending: Boolean get() = !entry.cancelled && entry.at >= now && queue.contains(entry)
        fun cancel() {
            entry.cancelled = true
        }
    }

    /** Runs [action] [delay] ticks from now (0 = this tick if the timeline is running it, else the next [advance]). */
    fun schedule(delay: Int, tag: String, action: () -> Unit): Handle {
        require(delay >= 0) { "negative delay $delay" }
        val at = if (delay == 0 && !running) now + 1 else now + delay
        val entry = Entry(at, sequence++, tag, action)
        queue += entry
        return Handle(entry)
    }

    /**
     * Runs [action] after [first] ticks and then every [interval] ticks while it returns true and [tag] is not
     * cancelled.
     */
    fun every(first: Int, interval: Int, tag: String, action: () -> Boolean): Handle {
        require(interval >= 1) { "interval must be at least 1" }
        lateinit var self: () -> Unit
        self = {
            if (action()) schedule(interval, tag, self)
        }
        return schedule(first, tag, self)
    }

    /** Drops every pending action tagged [tag]. Returns how many were dropped. */
    fun cancel(tag: String): Int {
        var n = 0
        for (e in queue) if (!e.cancelled && e.tag == tag) {
            e.cancelled = true
            n++
        }
        return n
    }

    /** Drops every pending action whose tag is in [tags]. */
    fun cancel(vararg tags: String): Int = tags.sumOf { cancel(it) }

    /** Drops everything. */
    fun cancelAll() {
        for (e in queue) e.cancelled = true
        queue.clear()
    }

    /** True if an action tagged [tag] is still pending. */
    fun hasPending(tag: String): Boolean = queue.any { !it.cancelled && it.tag == tag }

    /** Number of pending actions (for `::tfa dump`). */
    val size: Int get() = queue.count { !it.cancelled }

    /** The tick of the next pending action tagged [tag], or null. */
    fun nextTick(tag: String): Int? = queue.filter { !it.cancelled && it.tag == tag }.minOfOrNull { it.at }

    /** Moves to the next tick and runs the actions due on it. */
    fun advance() {
        now++
        running = true
        try {
            while (true) {
                val head = queue.peek() ?: break
                if (head.at > now) break
                queue.poll()
                if (!head.cancelled) head.action()
            }
        } finally {
            running = false
        }
    }
}

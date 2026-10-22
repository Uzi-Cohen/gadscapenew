package com.gadscape.raids.tfa.rooms.ezekiel

import java.util.PriorityQueue

/**
 * The room's scripted timeline (spec §2.5): a tick counter and a queue of cancellable scheduled actions. Nothing in the
 * room chains `WorldTasksManager.schedule`, so [cancel] / [cancelAll] stop everything a wipe, the finale, the death or
 * a teardown must stop.
 *
 * - [advance] moves to the next tick and runs every action due on it, in schedule order. An action scheduled with
 *   delay 0 while the tick runs still runs on this tick (after the ones already queued).
 * - Every action carries a [tag]; [cancel] drops all actions with that tag.
 *
 * Not thread-safe: it lives on the world thread like the rest of the encounter.
 */
class EzekielTimeline {

    /** The current tick (0 before the first [advance]). */
    var now: Int = 0
        private set

    private var sequence = 0L
    private var running = false

    private class Entry(val at: Int, val seq: Long, val tag: String, val action: () -> Unit) {
        var cancelled = false
    }

    private val queue = PriorityQueue<Entry>(compareBy<Entry>({ it.at }, { it.seq }))

    /** Runs [action] [delay] ticks from now (0 = this tick if the timeline is running it, else the next [advance]). */
    fun schedule(delay: Int, tag: String, action: () -> Unit) {
        require(delay >= 0) { "negative delay $delay" }
        val at = if (delay == 0 && !running) now + 1 else now + delay
        queue += Entry(at, sequence++, tag, action)
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

    /** The tick of the next pending action tagged [tag], or null. */
    fun nextTick(tag: String): Int? = queue.filter { !it.cancelled && it.tag == tag }.minOfOrNull { it.at }

    /** Number of pending actions (for `::tfa dump`). */
    val size: Int get() = queue.count { !it.cancelled }

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

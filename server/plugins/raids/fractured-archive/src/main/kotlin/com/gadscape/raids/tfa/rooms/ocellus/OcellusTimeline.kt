package com.gadscape.raids.tfa.rooms.ocellus

import org.slf4j.LoggerFactory
import java.util.PriorityQueue

/**
 * The room's scripted timeline (spec §2.5): a tick counter and a queue of cancellable actions. Every delayed
 * thing the encounter does (a projectile landing, a tendril resolving, the next barrage) is an entry here, never a
 * chained `WorldTasksManager.schedule`, so a wipe, a phase change or the teardown can cancel all of it.
 *
 * - [after] schedules an action [delay] ticks from now (0 = later in the current tick if the timeline is running,
 *   else on the next [tick]). Actions due on the same tick run in scheduling order.
 * - Each action has a dotted [tag] (`p1`, `p1.cycle`, `p2.barrage`, `hit`...). [cancel] removes every pending
 *   action whose tag is the given tag or starts with it plus a dot.
 * - An action that throws is logged and dropped; the others still run.
 *
 * Pure (no world access); unit tested in `OcellusTimelineTest`.
 */
class OcellusTimeline {

    /** Ticks since the timeline was created. */
    var now: Int = 0
        private set

    private var seq = 0L
    private var running = false

    private val queue = PriorityQueue<Entry>(compareBy<Entry>({ it.at }, { it.seq }))

    inner class Entry internal constructor(val at: Int, val seq: Long, val tag: String, val action: () -> Unit) {
        var cancelled: Boolean = false
            private set

        fun cancel() {
            cancelled = true
        }

        val pending: Boolean get() = !cancelled && at >= now && queue.contains(this)
    }

    /** Runs [action] [delay] ticks from now under [tag]. */
    fun after(delay: Int, tag: String, action: () -> Unit): Entry {
        require(delay >= 0) { "negative delay $delay" }
        val at = if (delay == 0 && !running) now + 1 else now + delay
        val entry = Entry(at, seq++, tag, action)
        queue += entry
        return entry
    }

    /** Runs [action] every [interval] ticks, first after [firstDelay], while [whileTrue] holds (checked before each run). */
    fun every(interval: Int, firstDelay: Int, tag: String, whileTrue: () -> Boolean = { true }, action: () -> Unit) {
        require(interval >= 1) { "interval $interval" }
        fun schedule(delay: Int) {
            after(delay, tag) {
                if (!whileTrue()) return@after
                action()
                schedule(interval)
            }
        }
        schedule(firstDelay)
    }

    /** Cancels every pending action tagged [tag] or `tag.*`. Returns how many were cancelled. */
    fun cancel(tag: String): Int {
        var n = 0
        val prefix = "$tag."
        for (e in queue) {
            if (!e.cancelled && (e.tag == tag || e.tag.startsWith(prefix))) {
                e.cancel()
                n++
            }
        }
        queue.removeIf { it.cancelled }
        return n
    }

    /** Cancels everything. */
    fun clear() {
        queue.forEach { it.cancel() }
        queue.clear()
    }

    /** Pending actions (optionally only under [tag]). */
    fun pending(tag: String? = null): Int =
        if (tag == null) queue.count { !it.cancelled } else queue.count { !it.cancelled && (it.tag == tag || it.tag.startsWith("$tag.")) }

    /** Advances one tick and runs every action now due. */
    fun tick() {
        now++
        running = true
        try {
            while (true) {
                val head = queue.peek() ?: break
                if (head.at > now) break
                queue.poll()
                if (head.cancelled) continue
                try {
                    head.action()
                } catch (t: Throwable) {
                    log.error("Ocellus timeline action '{}' at tick {} failed", head.tag, now, t)
                }
            }
        } finally {
            running = false
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OcellusTimeline::class.java)
    }
}

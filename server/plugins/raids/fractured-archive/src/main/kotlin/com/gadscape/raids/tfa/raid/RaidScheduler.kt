package com.gadscape.raids.tfa.raid

import org.slf4j.LoggerFactory
import java.util.PriorityQueue

/**
 * The raid's own timeline (spec §2.5): a tick counter and a queue of tagged, cancellable actions. Everything the raid
 * does later (room exit after 3 t, camp re-copy after 9 t, Navigator after 10 t, door animations, release) is queued
 * here instead of being chained through `WorldTasksManager`, so a wipe or a release cancels it all in one call.
 *
 * Pure (no engine types): unit-tested in `RaidSchedulerTest`.
 */
class RaidScheduler {

    private class Entry(val due: Int, val seq: Long, val tag: String, val action: () -> Unit)

    private val queue = PriorityQueue<Entry>(compareBy<Entry>({ it.due }, { it.seq }))
    private var nextSeq = 0L

    /** Ticks advanced so far. */
    var now: Int = 0
        private set

    /** Number of queued actions. */
    val size: Int get() = queue.size

    /**
     * Runs [action] [delay] ticks from now (0 = later in this same tick if the queue is being run, else on the next
     * [advance]). [tag] groups actions for [cancel] / [cancelPrefix].
     */
    fun schedule(delay: Int, tag: String = "", action: () -> Unit) {
        queue += Entry(now + delay.coerceAtLeast(0), nextSeq++, tag, action)
    }

    /** True if an action with exactly [tag] is queued. */
    fun has(tag: String): Boolean = queue.any { it.tag == tag }

    /** Ticks until the first action with [tag] runs, or -1. */
    fun remaining(tag: String): Int = queue.filter { it.tag == tag }.minOfOrNull { it.due - now } ?: -1

    fun cancel(tag: String): Int {
        val before = queue.size
        queue.removeIf { it.tag == tag }
        return before - queue.size
    }

    fun cancelPrefix(prefix: String): Int {
        val before = queue.size
        queue.removeIf { it.tag.startsWith(prefix) }
        return before - queue.size
    }

    fun cancelAll() {
        queue.clear()
    }

    /** Advances one tick and runs every action that is due, in (due, insertion) order. */
    fun advance() {
        now++
        runDue()
    }

    /** Runs the due actions without advancing (actions queued with delay 0 while running also run). */
    fun runDue() {
        var guard = 0
        while (true) {
            val head = queue.peek() ?: return
            if (head.due > now) return
            queue.poll()
            try {
                head.action()
            } catch (e: Exception) {
                log.error("Fractured Archive: raid action '{}' failed", head.tag, e)
            }
            if (++guard > 10_000) {
                log.error("Fractured Archive: raid scheduler ran 10000 actions in one tick; stopping")
                return
            }
        }
    }

    /** Lines for `::tfa dump`. */
    fun describe(): List<String> =
        queue.sortedWith(compareBy<Entry>({ it.due }, { it.seq })).map { "  +${it.due - now} t: ${it.tag.ifEmpty { "(untagged)" }}" }

    private companion object {
        private val log = LoggerFactory.getLogger(RaidScheduler::class.java)
    }
}

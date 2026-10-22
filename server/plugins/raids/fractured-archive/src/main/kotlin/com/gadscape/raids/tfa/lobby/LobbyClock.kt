package com.gadscape.raids.tfa.lobby

import org.slf4j.LoggerFactory
import java.util.PriorityQueue

/**
 * A tick counter with a queue of tagged, cancellable actions (the lobby's version of the spec §2.5 timeline): rock
 * rotation, loc animation follow-ups, sprite / messenger / intro despawns. Driven once per world tick by
 * [LobbyModule]; pure, so tests drive it with [advance].
 */
class TickQueue {

    private class Entry(val due: Long, val seq: Long, val tag: String, val action: () -> Unit)

    private val queue = PriorityQueue(compareBy<Entry>({ it.due }, { it.seq }))
    private var nextSeq = 0L

    var now: Long = 0
        private set

    val size: Int get() = queue.size

    /** Runs [action] [delay] ticks from now; [tag] groups actions for [cancel]. */
    fun schedule(delay: Int, tag: String = "", action: () -> Unit) {
        queue += Entry(now + delay.coerceAtLeast(0), nextSeq++, tag, action)
    }

    fun has(tag: String): Boolean = queue.any { it.tag == tag }

    /** Cancels every queued action with [tag]; returns how many. */
    fun cancel(tag: String): Int {
        val before = queue.size
        queue.removeIf { it.tag == tag }
        return before - queue.size
    }

    /** Advances one tick and runs everything due. An action that throws is logged; the others still run. */
    fun advance() {
        now++
        while (true) {
            val head = queue.peek() ?: break
            if (head.due > now) break
            queue.poll()
            try {
                head.action()
            } catch (e: Exception) {
                log.error("Fractured Archive lobby action '{}' failed", head.tag, e)
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(TickQueue::class.java)
    }
}

/** The world's lobby timeline (world thread only). */
object LobbyClock {
    @JvmField
    val queue = TickQueue()
}

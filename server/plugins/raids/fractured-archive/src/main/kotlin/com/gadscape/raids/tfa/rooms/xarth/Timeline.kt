package com.gadscape.raids.tfa.rooms.xarth

import java.util.PriorityQueue

/**
 * The Xarth room's scripted timeline (spec §2.5): a tick counter and a queue of cancellable, tagged actions. Nothing in
 * the room chains `WorldTasksManager.schedule`, so his death, a wipe or [cancelAll] in teardown stops everything.
 *
 * - [advance] moves to the next tick and runs every action due on it, in scheduling order. An action scheduled for the
 *   current tick while the queue runs is run in the same pass.
 * - [at] with a tick that is not in the future runs the action immediately (no queue round trip), so a sequence that
 *   starts "now" can be written with the same absolute ticks as the rest of its script.
 * - Tags let his death cancel the boss script ([Tag.BOSS]), pending pile reanimations ([Tag.CORPSE]) and flames in
 *   flight ([Tag.FLAME]) while despawns ([Tag.NPC]), loc-anim refreshes ([Tag.LOC]) and the death itself
 *   ([Tag.DEATH]) still run.
 */
class Timeline {

    enum class Tag {
        /** Boss attacks and their impacts, threshold blasts, the P2 transition, heal blasts. */
        BOSS,
        /** Corpse piles burning before their corpse appears. */
        CORPSE,
        /** Flames in flight (projectile launched, loc not yet placed). */
        FLAME,
        /** NPC despawns after a death or absorb seq. */
        NPC,
        /** Loc animation follow-ups (flame spawn → idle). */
        LOC,
        /** Death sequence: room completion. */
        DEATH,
        OTHER,
    }

    class Handle internal constructor(
        val tick: Int,
        val tag: Tag,
        internal val seq: Long,
        internal val action: () -> Unit,
    ) {
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

    /** Runs [action] [delay] ticks from now (0 = still in the current pass when called from inside the queue). */
    fun after(delay: Int, tag: Tag, action: () -> Unit): Handle {
        require(delay >= 0) { "delay must be >= 0, got $delay" }
        return enqueue(now + delay, tag, action)
    }

    /** Runs [action] at the absolute [tick]: queued if it is in the future, run at once otherwise (returns null then). */
    fun at(tick: Int, tag: Tag, action: () -> Unit): Handle? {
        if (tick > now) return enqueue(tick, tag, action)
        if (running) return enqueue(now, tag, action)
        action()
        return null
    }

    private fun enqueue(tick: Int, tag: Tag, action: () -> Unit): Handle {
        val handle = Handle(tick, tag, seq++, action)
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
        queue.forEach {
            if (it.tag == tag && !it.cancelled) {
                it.cancel()
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
}

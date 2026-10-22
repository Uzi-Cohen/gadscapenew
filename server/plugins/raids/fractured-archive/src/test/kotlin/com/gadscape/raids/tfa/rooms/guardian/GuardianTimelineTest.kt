package com.gadscape.raids.tfa.rooms.guardian

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardianTimelineTest {

    @Test
    fun runsInTickAndScheduleOrder() {
        val t = GuardianTimeline()
        val log = ArrayList<String>()
        t.schedule(2, "a") { log += "a@${t.now}" }
        t.schedule(1, "b") { log += "b@${t.now}" }
        t.schedule(2, "c") { log += "c@${t.now}" }
        repeat(3) { t.advance() }
        assertEquals(listOf("b@1", "a@2", "c@2"), log)
    }

    @Test
    fun zeroDelayInsideATickRunsThatTick() {
        val t = GuardianTimeline()
        val log = ArrayList<String>()
        t.schedule(1, "a") {
            log += "a@${t.now}"
            t.schedule(0, "b") { log += "b@${t.now}" }
        }
        t.schedule(0, "c") { log += "c@${t.now}" } // outside a tick: the next one
        t.advance()
        assertEquals(listOf("a@1", "c@1", "b@1"), log)
    }

    @Test
    fun cancelByTagAndAll() {
        val t = GuardianTimeline()
        val log = ArrayList<String>()
        t.schedule(1, "x") { log += "x" }
        t.schedule(1, "y") { log += "y" }
        val h = t.schedule(1, "z") { log += "z" }
        assertEquals(1, t.cancel("x"))
        h.cancel()
        assertTrue(t.hasPending("y"))
        assertFalse(t.hasPending("x"))
        t.advance()
        assertEquals(listOf("y"), log)
        t.schedule(3, "w") { log += "w" }
        t.cancelAll()
        repeat(5) { t.advance() }
        assertEquals(listOf("y"), log)
        assertEquals(0, t.size)
    }

    @Test
    fun everyRepeatsUntilFalseOrCancelled() {
        val t = GuardianTimeline()
        val ticks = ArrayList<Int>()
        t.every(2, 3, "r") {
            ticks += t.now
            ticks.size < 4
        }
        repeat(20) { t.advance() }
        assertEquals(listOf(2, 5, 8, 11), ticks)

        val other = ArrayList<Int>()
        t.every(1, 1, "s") {
            other += t.now
            true
        }
        repeat(3) { t.advance() }
        t.cancel("s")
        repeat(3) { t.advance() }
        assertEquals(3, other.size)
        assertEquals(26, t.now)
        assertEquals(t.now - 6 + 1, other.first())
    }
}

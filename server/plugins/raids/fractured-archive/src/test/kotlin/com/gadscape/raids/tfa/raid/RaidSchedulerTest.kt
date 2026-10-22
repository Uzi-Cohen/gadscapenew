package com.gadscape.raids.tfa.raid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaidSchedulerTest {

    @Test
    fun runsActionsInDueThenInsertionOrder() {
        val s = RaidScheduler()
        val log = ArrayList<String>()
        s.schedule(2, "b") { log += "b2" }
        s.schedule(1, "a") { log += "a1" }
        s.schedule(2, "c") { log += "c2" }
        s.advance()
        assertEquals(listOf("a1"), log)
        s.advance()
        assertEquals(listOf("a1", "b2", "c2"), log)
        assertEquals(0, s.size)
    }

    @Test
    fun zeroDelayDuringRunExecutesInTheSameTick() {
        val s = RaidScheduler()
        val log = ArrayList<Int>()
        s.schedule(1) {
            log += s.now
            s.schedule(0) { log += s.now }
        }
        s.advance()
        assertEquals(listOf(1, 1), log)
    }

    @Test
    fun cancelByTagAndPrefix() {
        val s = RaidScheduler()
        var ran = 0
        s.schedule(1, "door:1:WEST") { ran++ }
        s.schedule(1, "door:1:EAST") { ran++ }
        s.schedule(1, "room:exit") { ran++ }
        assertTrue(s.has("room:exit"))
        assertEquals(1, s.cancel("room:exit"))
        assertEquals(2, s.cancelPrefix("door:"))
        s.advance()
        assertEquals(0, ran)
        assertFalse(s.has("room:exit"))
    }

    @Test
    fun cancelAllStopsEverythingAndAFailingActionDoesNotStopOthers() {
        val s = RaidScheduler()
        var ran = 0
        s.schedule(1) { error("boom") }
        s.schedule(1) { ran++ }
        s.advance()
        assertEquals(1, ran)
        s.schedule(3, "x") { ran++ }
        assertEquals(3, s.remaining("x"))
        s.cancelAll()
        repeat(5) { s.advance() }
        assertEquals(1, ran)
    }
}

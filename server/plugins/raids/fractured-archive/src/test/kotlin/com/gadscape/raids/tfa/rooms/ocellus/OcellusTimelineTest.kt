package com.gadscape.raids.tfa.rooms.ocellus

import kotlin.test.Test
import kotlin.test.assertEquals

class OcellusTimelineTest {

    @Test
    fun runsInTickThenSchedulingOrder() {
        val tl = OcellusTimeline()
        val log = ArrayList<String>()
        tl.after(2, "a") { log += "a2@${tl.now}" }
        tl.after(1, "b") { log += "b1@${tl.now}" }
        tl.after(2, "c") { log += "c2@${tl.now}" }
        repeat(3) { tl.tick() }
        assertEquals(listOf("b1@1", "a2@2", "c2@2"), log)
    }

    @Test
    fun zeroDelayOutsideATickRunsNextTickInsideRunsNow() {
        val tl = OcellusTimeline()
        val log = ArrayList<String>()
        tl.after(0, "x") {
            log += "outer@${tl.now}"
            tl.after(0, "x") { log += "inner@${tl.now}" }
        }
        tl.tick()
        assertEquals(listOf("outer@1", "inner@1"), log)
    }

    @Test
    fun cancelByTagPrefix() {
        val tl = OcellusTimeline()
        val log = ArrayList<String>()
        tl.after(1, "p1") { log += "p1" }
        tl.after(1, "p1.cycle") { log += "p1.cycle" }
        tl.after(1, "p1x") { log += "p1x" }
        tl.after(1, "hit") { log += "hit" }
        assertEquals(2, tl.cancel("p1"))
        tl.tick()
        assertEquals(listOf("p1x", "hit"), log)
    }

    @Test
    fun clearCancelsEverythingAndActionsCanFail() {
        val tl = OcellusTimeline()
        var ran = 0
        tl.after(1, "a") { error("boom") }
        tl.after(1, "a") { ran++ }
        tl.tick()
        assertEquals(1, ran)
        tl.after(1, "a") { ran++ }
        tl.clear()
        tl.tick()
        assertEquals(1, ran)
        assertEquals(0, tl.pending())
    }

    @Test
    fun everyRepeatsWhileTheConditionHolds() {
        val tl = OcellusTimeline()
        val at = ArrayList<Int>()
        var on = true
        tl.every(5, 5, "auto", { on }) { at += tl.now }
        repeat(16) { tl.tick() }
        on = false
        repeat(10) { tl.tick() }
        assertEquals(listOf(5, 10, 15), at)
    }
}

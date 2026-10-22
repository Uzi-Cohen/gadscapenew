package com.gadscape.raids.tfa.hud

import kotlin.test.Test
import kotlin.test.assertEquals

class OrbCodecTest {

    @Test
    fun healthFill() {
        // WU-14 test: a member at 50/99 HP shows orb 14 to the others.
        assertEquals(14, OrbCodec.health(50, 99))
        assertEquals(27, OrbCodec.health(99, 99))
        assertEquals(1, OrbCodec.health(0, 99))
        // HP capped at 99 (ToB getStatus): 120/121 counts as 99/99.
        assertEquals(27, OrbCodec.health(120, 121))
    }

    @Test
    fun states() {
        assertEquals(0, OrbCodec.orb(occupied = false, dead = false, inActiveRoom = true, hp = 99, maxHp = 99))
        assertEquals(30, OrbCodec.orb(occupied = true, dead = true, inActiveRoom = true, hp = 0, maxHp = 99))
        assertEquals(31, OrbCodec.orb(occupied = true, dead = false, inActiveRoom = false, hp = 99, maxHp = 99))
        assertEquals(14, OrbCodec.orb(occupied = true, dead = false, inActiveRoom = true, hp = 50, maxHp = 99))
    }

    @Test
    fun timerArgs() {
        assertEquals(OrbCodec.TimerArgs(-1, 1), OrbCodec.TIMER_NOT_STARTED)
        assertEquals(OrbCodec.TimerArgs(120, 0), OrbCodec.running(120))
        assertEquals(OrbCodec.TimerArgs(3000, 1), OrbCodec.frozen(3000))
    }
}

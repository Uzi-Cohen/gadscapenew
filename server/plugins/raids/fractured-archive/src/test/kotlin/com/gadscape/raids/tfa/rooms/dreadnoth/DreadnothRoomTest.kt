package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.config.TfaConfigSnapshot
import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.raid.StaticTfaInstance
import com.gadscape.raids.tfa.raid.TfaInstance
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.world.entity.player.Player
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The encounter contract parts that need no world (no NPC is spawned here). */
class DreadnothRoomTest {

    private class Ctx(override val scale: Int = 2) : TfaEncounterContext {
        override val room = TfaRoomId.DREADNOTH
        override val config: TfaConfigSnapshot = TfaConfigTestKit.load(listOf(DreadnothConfig))
        override val scaling = Scaling(config.scaling)
        override val instance: TfaInstance = StaticTfaInstance
        override val random = Random(1)
        override val members: List<Player> = emptyList()
        override fun playersInArena(): List<Player> = emptyList()
        override val encounterTicks: Int = 0
    }

    @Test
    fun registeredThroughTheEncounterRegistry() {
        DreadnothModule.register()
        val factory = assertNotNull(TfaEncounters.factory(TfaRoomId.DREADNOTH))
        val room = factory.create(Ctx())
        assertTrue(room is DreadnothRoom)
        assertEquals(TfaRoomId.DREADNOTH, room.room)
    }

    @Test
    fun phasesForSkipTo() {
        val room = DreadnothRoom(Ctx())
        assertEquals(listOf("chase", "slam85", "slam65", "slam45", "enrage", "death"), room.phases)
        assertEquals(6000, room.contributionPool, "§3.10 Moia or Dreadnoth pool")
    }

    @Test
    fun nothingCompletesOrRunsBeforeTheStart() {
        val room = DreadnothRoom(Ctx())
        room.tick()
        assertFalse(room.isComplete())
        assertFalse(room.debugSetHpPercent(50), "no boss before build")
        assertFalse(room.debugSkipToPhase("enrage"))
        assertTrue(room.debugState().isNotEmpty())
    }

    @Test
    fun teardownIsIdempotentAndFinal() {
        val room = DreadnothRoom(Ctx())
        room.teardown()
        room.teardown()
        room.start() // refused after teardown
        room.tick()
        assertFalse(room.isComplete())
        room.onWipe()
    }
}

package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.config.TfaConfigSnapshot
import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.encounter.Scaling
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.ids.TfaIds
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
class BorkRoomTest {

    private class Ctx(override val scale: Int = 2) : TfaEncounterContext {
        override val room = TfaRoomId.BORK
        override val config: TfaConfigSnapshot = TfaConfigTestKit.load(listOf(BorkSmorksConfig))
        override val scaling = Scaling(config.scaling)
        override val instance: TfaInstance = StaticTfaInstance
        override val random = Random(1)
        override val members: List<Player> = emptyList()
        override fun playersInArena(): List<Player> = emptyList()
        override val encounterTicks: Int = 0
    }

    @Test
    fun registeredThroughTheEncounterRegistry() {
        BorkSmorksModule.register()
        val factory = assertNotNull(TfaEncounters.factory(TfaRoomId.BORK))
        val room = factory.create(Ctx())
        assertTrue(room is BorkSmorksRoom)
        assertEquals(TfaRoomId.BORK, room.room)
        assertTrue(TfaRoomId.BORK in TfaEncounters.registered())
    }

    @Test
    fun phasesAndPool() {
        val room = BorkSmorksRoom(Ctx())
        assertEquals(
            listOf("fight", "tempo66", "tempo33", "daze", "stomp", "gong", "incendiary", "heavy", "smorks_enraged", "bork_enraged", "death"),
            room.phases,
        )
        assertEquals(6000, room.contributionPool, "§3.10 Ezekiel or Bork pool")
        assertEquals(TfaIds.Region.BORK, TfaRoomId.BORK.staticRegion)
        assertEquals(16727, TfaRoomId.BORK.staticRegion)
        assertEquals(10010, TfaRoomId.BORK.prefetchScript)
        assertEquals(888, TfaRoomId.BORK.midi)
    }

    @Test
    fun nothingCompletesOrRunsBeforeTheStart() {
        val room = BorkSmorksRoom(Ctx())
        room.tick()
        assertFalse(room.isComplete())
        assertFalse(room.debugSetHpPercent(50), "no NPC before build")
        assertFalse(room.debugSkipToPhase("daze"))
        assertTrue(room.debugState().isNotEmpty())
        assertEquals(0, room.timeline.now)
    }

    @Test
    fun teardownIsIdempotentAndFinal() {
        val room = BorkSmorksRoom(Ctx())
        room.teardown()
        room.teardown()
        room.start() // refused after teardown
        room.tick()
        assertFalse(room.isComplete())
        room.onWipe()
        assertEquals(0, room.timeline.pending())
    }

    /** The arena entry tile and spread come from tfa.json (WU-13 lands players there). */
    @Test
    fun entryTileIsOnTheFloorWestOfTheCart() {
        val entry = assertNotNull(Ctx().config.raid.rooms[TfaRoomId.BORK])
        assertTrue(BorkArena.isWalkable(entry.entry))
        assertTrue(entry.entry.x < BorkArena.CART_SW.x)
    }
}

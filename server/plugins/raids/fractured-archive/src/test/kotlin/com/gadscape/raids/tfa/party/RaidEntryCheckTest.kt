package com.gadscape.raids.tfa.party

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec §3.4: the leader's Archive Door checks. */
class RaidEntryCheckTest {

    private data class M(val name: String, val lobby: Boolean = true, val loot: Boolean = false, val gate: Boolean = true)

    private fun check(members: List<M>, mode: Int = 0, practice: Boolean = false) = RaidEntry.check(
        members, { it.name }, { it.lobby }, { it.loot }, { it.gate }, mode, practice,
    )

    @Test
    fun everyoneReadyStarts() {
        assertEquals(RaidEntry.StartCheck.Ok, check(listOf(M("A"), M("B"))))
    }

    @Test
    fun membersOutsideTheLobbyAreNamed() {
        val r = check(listOf(M("A"), M("B", lobby = false), M("C", lobby = false)))
        assertEquals(RaidEntry.StartCheck.NotInLobby(listOf("B", "C")), r)
        assertTrue(r.message!!.contains("B, C"))
    }

    @Test
    fun unclaimedLootRefusesTheStart() {
        assertEquals(RaidEntry.StartCheck.UnclaimedLoot(listOf("B")), check(listOf(M("A"), M("B", loot = true))))
    }

    @Test
    fun gateMustBePassed() {
        assertEquals(RaidEntry.StartCheck.GateNotPassed(listOf("A")), check(listOf(M("A", gate = false))))
    }

    @Test
    fun practiceNeedsTheFlagAndSizeIsCapped() {
        assertEquals(RaidEntry.StartCheck.PracticeDisabled, check(listOf(M("A")), mode = 1))
        assertEquals(RaidEntry.StartCheck.Ok, check(listOf(M("A")), mode = 1, practice = true))
        assertEquals(RaidEntry.StartCheck.TooMany, check((1..9).map { M("P$it") }))
    }
}

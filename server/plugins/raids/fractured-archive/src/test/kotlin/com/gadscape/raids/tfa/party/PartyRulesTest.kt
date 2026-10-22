package com.gadscape.raids.tfa.party

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Spec §3.3 / WU-11: the Grouping Board rules, with names standing in for players. */
class PartyRulesTest {

    private fun book(limits: Limits = Limits()) = PartyBook<String>(limits) { it }

    private fun party(book: PartyBook<String>, leader: String) = book.create(leader, 0).second!!

    @Test
    fun applyAcceptMovesTheApplicantToTheMembers() {
        val b = book()
        val p = party(b, "Leader")
        assertEquals(PartyResult.OK, b.apply("Bob", p))
        assertEquals(listOf("Bob"), p.applicants)
        assertSame(p.state(), b.applicationOf("Bob"))
        val (result, member) = b.accept(p, 0)
        assertEquals(PartyResult.OK, result)
        assertEquals("Bob", member)
        assertEquals(listOf("Leader", "Bob"), p.members)
        assertTrue(p.applicants.isEmpty())
        assertSame(p, b.partyOf("Bob"))
        assertNull(b.applicationOf("Bob"))
    }

    @Test
    fun rejectBlocksUntilUnblock() {
        val b = book()
        val p = party(b, "Leader")
        b.apply("Bob", p)
        assertEquals(PartyResult.OK, b.reject(p, 0).first)
        assertEquals(PartyResult.BLOCKED, b.apply("Bob", p))
        assertEquals(PartyResult.BLOCKED, b.apply("BOB", p), "the block list ignores case")
        assertTrue(p.unblockAll())
        assertEquals(PartyResult.OK, b.apply("Bob", p))
    }

    @Test
    fun ninthMemberAndEighthApplicantAreRefused() {
        val b = book()
        val p = party(b, "L")
        for (i in 1..7) {
            assertEquals(PartyResult.OK, b.apply("M$i", p))
            assertEquals(PartyResult.OK, b.accept(p, 0).first)
        }
        assertEquals(8, p.members.size)
        assertEquals(PartyResult.PARTY_FULL, b.apply("Ninth", p))

        val q = party(b, "Q")
        for (i in 1..7) assertEquals(PartyResult.OK, b.apply("A$i", q))
        assertEquals(PartyResult.TOO_MANY_APPLICANTS, b.apply("A8", q))
    }

    @Test
    fun acceptRefusedWhenFull() {
        val b = book(Limits(maxMembers = 2))
        val p = party(b, "L")
        b.apply("A", p)
        b.accept(p, 0)
        // A third applicant cannot even apply to a full party.
        assertEquals(PartyResult.PARTY_FULL, b.apply("B", p))
    }

    @Test
    fun onePartyPerPlayerAndOneApplicationAtATime() {
        val b = book()
        val p = party(b, "L1")
        val q = party(b, "L2")
        assertEquals(PartyResult.ALREADY_IN_PARTY, b.create("L1", 0).first)
        assertEquals(PartyResult.ALREADY_IN_PARTY, b.apply("L1", q))
        assertEquals(PartyResult.OK, b.apply("X", p))
        assertEquals(PartyResult.ALREADY_APPLIED, b.apply("X", p))
        assertEquals(PartyResult.OK, b.apply("X", q))
        assertTrue(p.applicants.isEmpty(), "the first application is withdrawn")
        assertEquals(listOf("X"), q.applicants)
        // Making a party withdraws the pending application.
        b.create("X", 0)
        assertTrue(q.applicants.isEmpty())
    }

    @Test
    fun applicantWhoJoinedElsewhereIsDroppedOnAccept() {
        val b = book()
        val p = party(b, "L1")
        b.apply("X", p)
        b.create("Y", 0)
        // A stale row for Y (e.g. a click racing Y's own "Make Party").
        p.state().applicants.add("Y")
        assertEquals(PartyResult.ALREADY_IN_PARTY, b.accept(p, 1).first)
        assertEquals(listOf("X"), p.applicants)
    }

    @Test
    fun kickMoveLeaveAndLeaderHandOver() {
        val b = book()
        val p = party(b, "L")
        for (n in listOf("A", "B", "C")) {
            b.apply(n, p)
            b.accept(p, 0)
        }
        assertEquals(listOf("L", "A", "B", "C"), p.members)
        assertEquals(PartyResult.OK, p.state().moveUp(3))
        assertEquals(listOf("L", "A", "C", "B"), p.members)
        assertEquals(PartyResult.OK, p.state().moveDown(1))
        assertEquals(listOf("L", "C", "A", "B"), p.members)
        assertEquals(PartyResult.LEADER_STAYS_FIRST, p.state().moveUp(1))
        assertEquals(PartyResult.LEADER_STAYS_FIRST, p.state().moveDown(0))
        assertEquals(PartyResult.NO_SUCH_SLOT, p.state().moveDown(3))

        assertEquals("A", b.kick(p, 2).second)
        assertNull(b.partyOf("A"))
        assertEquals(PartyResult.NO_SUCH_SLOT, b.kick(p, 0).first, "slot 0 is Quit Party (leave)")

        p.preferredSize = 5
        val leave = b.leave("L")!!
        assertEquals("C", leave.newLeader)
        assertFalse(leave.disbanded)
        assertEquals(listOf("C", "B"), p.members)
        assertEquals(5, p.preferredSize, "settings carry over to the new leader")
    }

    @Test
    fun emptyPartyIsRemovedAndApplicantsReleased() {
        val b = book()
        val p = party(b, "L")
        b.apply("X", p)
        val leave = b.leave("L")!!
        assertTrue(leave.disbanded)
        assertEquals(listOf("X"), leave.releasedApplicants)
        assertNull(b[p.id])
        assertNull(b.applicationOf("X"))
    }

    @Test
    fun disbandReleasesEveryone() {
        val b = book()
        val p = party(b, "L")
        b.apply("A", p)
        b.accept(p, 0)
        b.apply("X", p)
        val (members, applicants) = b.disband(p)
        assertEquals(listOf("L", "A"), members)
        assertEquals(listOf("X"), applicants)
        assertNull(b.partyOf("A"))
        assertNull(b.applicationOf("X"))
        assertEquals(0, b.size)
    }

    @Test
    fun boardHoldsAtMost45Parties() {
        val b = book()
        for (i in 1..45) assertEquals(PartyResult.OK, b.create("L$i", 0).first)
        assertEquals(PartyResult.BOARD_FULL, b.create("L46", 0).first)
    }

    @Test
    fun lockedPartyCannotChange() {
        val b = book()
        val p = party(b, "L")
        b.apply("A", p)
        b.accept(p, 0)
        p.locked = true
        assertEquals(PartyResult.LOCKED, b.apply("X", p))
        assertEquals(PartyResult.LOCKED, b.kick(p, 1).first)
        assertEquals(PartyResult.LOCKED, p.state().moveDown(1))
    }

    @Test
    fun viewerRoles() {
        val b = book()
        val p = party(b, "L")
        b.apply("A", p)
        b.accept(p, 0)
        b.apply("X", p)
        val q = party(b, "Q")
        assertEquals(ViewerRole.LEADER, ViewerRole.of(b, p, "L"))
        assertEquals(ViewerRole.MEMBER, ViewerRole.of(b, p, "A"))
        assertEquals(ViewerRole.APPLICANT, ViewerRole.of(b, p, "X"))
        assertEquals(ViewerRole.APPLY, ViewerRole.of(b, p, "Stranger"))
        assertEquals(ViewerRole.APPLY_DISABLED, ViewerRole.of(b, p, "Q"), "in another party")
        b.reject(p, 0)
        assertEquals(ViewerRole.APPLY_DISABLED, ViewerRole.of(b, p, "X"), "blocked")
        p.locked = true
        assertEquals(ViewerRole.APPLY_DISABLED, ViewerRole.of(b, p, "Stranger"), "inside a raid")
        assertNotNull(q)
    }

    @Test
    fun detailsPauseSubsMatchEnum6057() {
        assertEquals(DetailsButton.Back, DetailsButton.of(0))
        assertEquals(DetailsButton.Refresh, DetailsButton.of(1))
        assertEquals(DetailsButton.Action, DetailsButton.of(6))
        assertEquals(DetailsButton.SpectateToggle, DetailsButton.of(9))
        assertEquals(DetailsButton.Kick(0), DetailsButton.of(10))
        assertEquals(DetailsButton.Kick(7), DetailsButton.of(17))
        assertEquals(DetailsButton.MoveUp(0), DetailsButton.of(18))
        assertEquals(DetailsButton.MoveDown(7), DetailsButton.of(33))
        assertEquals(DetailsButton.Accept(0), DetailsButton.of(34))
        assertEquals(DetailsButton.Accept(6), DetailsButton.of(40))
        assertEquals(DetailsButton.Reject(0), DetailsButton.of(41))
        assertEquals(DetailsButton.Reject(6), DetailsButton.of(47))
        assertNull(DetailsButton.of(48))
        assertEquals(ListButton.REFRESH, ListButton.of(0))
        assertEquals(ListButton.MY_PARTY, ListButton.of(1))
        assertEquals(ListButton.FILTER, ListButton.of(2))
    }

    @Test
    fun refreshIsRateLimitedTo1Per8Ticks() {
        val limiter = RefreshLimiter { 8 }
        assertTrue(limiter.tryAcquire("p", 100))
        for (t in 101L..107L) assertFalse(limiter.tryAcquire("p", t))
        assertTrue(limiter.tryAcquire("p", 108))
        assertTrue(limiter.tryAcquire("other", 101))
        assertTrue(RefreshLimiter { 0 }.tryAcquire("p", 1))
    }

    private fun PartyState<String>.state() = this
}

package com.gadscape.raids.tfa.entry

import com.gadscape.raids.tfa.config.RaidConfig
import com.gadscape.raids.tfa.config.TfaConfigTestKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec §3.1 / WU-09: the attunement gate and the folio charge maths. */
class EntryGateTest {

    private val defaults = TfaConfigTestKit.load(listOf(RaidConfig), bundled = emptyMap())[RaidConfig]
    private val req = defaults.entryGate.requirements

    private val qualified = TfaEntryGate.GateStats(
        thieving = 72, magic = 67, agility = 66, farming = 65, herblore = 65, hunter = 62, firemaking = 49,
        attack = 65, strength = 65, tormentedDemonKills = 1, combatLevel = 100,
    )

    @Test
    fun theSpecRequirementsPass() {
        assertTrue(TfaEntryGate.meets(req, qualified))
        assertTrue(TfaEntryGate.missing(req, qualified).isEmpty())
    }

    @Test
    fun missingThievingIsListed() {
        val missing = TfaEntryGate.missing(req, qualified.copy(thieving = 71))
        assertEquals(listOf("72 Thieving"), missing)
        assertFalse(TfaEntryGate.meets(req, qualified.copy(thieving = 71)))
    }

    @Test
    fun attackPlusStrengthKillsAndCombat() {
        val m = TfaEntryGate.missing(req, qualified.copy(attack = 60, strength = 69, tormentedDemonKills = 0, combatLevel = 99))
        assertEquals(listOf("a combined 130 Attack and Strength", "1 Tormented demon kill", "combat level 100"), m)
    }

    @Test
    fun defaultGateIsAttunement() {
        assertEquals(RaidConfig.GateMode.ATTUNEMENT, defaults.entryGate.mode)
        assertEquals("You need to have proven yourself to the Guthixian Alliance to enter the Archive.", defaults.entryGate.refusalMessage)
    }

    @Test
    fun folioTakesFiveChargesPerShardUpToTheCap() {
        val folio = defaults.folio
        assertEquals(5, folio.chargesPerShard)
        assertEquals(10, Folio.shardsAccepted(0, 10, folio))
        assertEquals(200, Folio.shardsAccepted(0, 5000, folio), "1000 cap / 5")
        assertEquals(1, Folio.shardsAccepted(995, 10, folio))
        assertEquals(0, Folio.shardsAccepted(1000, 10, folio))
        assertEquals(0, Folio.shardsAccepted(997, 10, folio), "never over the cap")
        assertEquals(0, Folio.shardsAccepted(0, 0, folio))
    }

    @Test
    fun miniquestStagesMatchTheSpec() {
        assertEquals(listOf(0, 1, 2, 3, 6, 8), listOf(Miniquest.NOT_STARTED, Miniquest.STARTED, Miniquest.ENTERED_LOBBY,
            Miniquest.FIRST_RAID, Miniquest.OCELLUS_KILLED, Miniquest.COMPLETE))
    }
}

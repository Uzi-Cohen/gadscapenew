package com.gadscape.raids.tfa.ids

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs with no `data/raids/tfa/remap.tsv` in the module directory, so every id resolves to itself. */
class TfaIdsTest {

    @Test
    fun everyGroupResolvesAndIsIdentityWithoutARemap() {
        val all = TfaIds.all()
        assertTrue(all.size > 900, "expected the spec's ~1000 ids, got ${all.size}")
        assertTrue(all.none { it.remapped }, "no remap.tsv in tests")
        assertEquals(TfaIds.groups.map { it.groupName }.toSet(), all.map { it.group }.toSet(), "every group registered ids")
    }

    @Test
    fun namesAreUniquePerGroup() {
        val dupes = TfaIds.all().groupBy { it.group + "." + it.name }.filter { it.value.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate names: $dupes")
    }

    @Test
    fun spotChecksAgainstTheSpec() {
        assertEquals(16657, TfaIds.Npc.GUARDIAN_INERT)
        assertEquals(16683, TfaIds.Npc.THE_BLADE)
        assertEquals(63087, TfaIds.Loc.TRANSPORTER)
        assertEquals(15162, TfaIds.Seq.JELLY_SPAWN)
        assertEquals(4406, TfaIds.Spot.VAULT_CHEST_SPARKLES)
        assertEquals(974, TfaIds.Interface.HUD)
        assertEquals(10036, TfaIds.Script.HUD_TIMER)
        assertEquals(5913, TfaIds.Varp.MY_CONTROLLER)
        assertEquals(16386, TfaIds.Varbit.PARTY_STATUS)
        assertEquals(17150, TfaIds.Dbrow.SHOP_NORMAL)
        assertEquals(1216, TfaIds.Struct.CA_BOSS)
        assertEquals(886, TfaIds.Jingle.COMPLETION)
        assertEquals(16471, TfaIds.Region.NEXUS)
    }

    @Test
    fun familiesFollowTheSpecFormulas() {
        for (k in 0 until 8) {
            assertEquals(16736 + 2 * k, TfaIds.Npc.VAULT_CHEST_CLOSED[k], "closed chest $k")
            assertEquals(16737 + 2 * k, TfaIds.Npc.VAULT_CHEST_OPEN[k], "open chest $k")
            assertEquals(16397 + k, TfaIds.Varbit.VAULT_CHEST_OPENED[k])
            assertEquals(16387 + k, TfaIds.Varbit.ORBS[k])
            assertEquals(TfaIds.Loc.XARTH_PILES[k] + 1, TfaIds.Loc.XARTH_PILES_FIRE[k], "fire variant = pile + 1")
        }
        for (i in 0 until 7) {
            assertEquals(14731 + 2 * i, TfaIds.Seq.EZEKIEL_FENCE_DROP[i])
            assertEquals(14732 + 2 * i, TfaIds.Seq.EZEKIEL_FENCE_IDLE[i])
        }
        assertEquals(17, TfaIds.Obj.COLLECTION_LOG.size, "enum 6017 has 17 items")
        assertEquals(listOf(14931, 14930, 14929, 14928, 14927, 14926, 14925, 14924, 14923), TfaIds.Seq.INSOLENCE_DEGEN_FRAMES.toList())
        assertEquals(12, TfaIds.Spot.WAVE_DUST.size)
        assertTrue(16420 !in TfaIds.Varbit.CAPE_STAND_VISIBLE)
    }

    @Test
    fun crcsAreTheSignedPacketValues() {
        assertEquals(2481737999L.toInt(), TfaIds.Crc.SPECTATE_SLOT)
        assertEquals(2359217665L.toInt(), TfaIds.Crc.STATS_CATEGORY)
        assertEquals(3642382788L.toInt(), TfaIds.Crc.STATS_DEBUG_SETTER)
        assertEquals(1373305897, TfaIds.Crc.HUD_ACTION)
        assertEquals(407137374, TfaIds.Crc.OMNISHOP_BUY)
    }

    @Test
    fun packedComponents() {
        assertEquals(63963137, TfaIds.packed(TfaIds.Interface.PARTY_DETAILS, TfaIds.Component.PARTY_DETAILS_BUTTONS))
        assertEquals(64028673, TfaIds.packed(TfaIds.Interface.PARTY_LIST, TfaIds.Component.PARTY_LIST_BUTTONS))
    }
}

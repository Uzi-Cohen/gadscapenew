package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.ids.IdKind
import com.gadscape.raids.tfa.ids.RemapTable

/**
 * Ocellus ids the shared `TfaIds` table does not list yet (spec §0.2: never a raw id in code; every id goes through
 * the remap). Resolved lazily through [RemapTable.active] like `TfaIds` (they are kept out of `TfaIdRegistry`, so
 * `TfaIdsTest`'s group check is unaffected). Listed in `raids4-impl/ENC-ocellus.md` for a later move into `TfaIds`.
 */
internal object OcellusIds {

    private fun seq(id: Int) = lazy { RemapTable.active.resolve(IdKind.SEQ, id) }
    private fun loc(id: Int) = lazy { RemapTable.active.resolve(IdKind.LOC, id) }

    /** `vfx_ocellus_p2_portal_despawn` (15 frames, 30 cycles = 1 t, framemap 1928 like spawn 14829 / idle 14830) [CACHE seq]. */
    val PORTAL_DESPAWN: Int by seq(14831)

    /**
     * Hanging Blade states `tfa_the_blade1_idle0/1/2` [CACHE loc_ocellus.txt]: same model 62699 as 62706, `active=no`,
     * anims 14873 static / 14874 `sword_idle01` / 14875 `sword_idle02` (4.93 t loops). [INF] Swapped in as the
     * fight escalates: idle1 from P2, idle2 for the last barrage.
     */
    val BLADE_IDLE0: Int by loc(62707)
    val BLADE_IDLE1: Int by loc(62708)
    val BLADE_IDLE2: Int by loc(62709)
}

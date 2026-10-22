package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.ids.IdKind
import com.gadscape.raids.tfa.ids.RemapTable
import com.gadscape.raids.tfa.ids.TfaIds

/**
 * Every cache id the Dreadnoth room uses, in one place, with its evidence. Raid ids come from [TfaIds] (resolved
 * through `remap.tsv`); the few stock ids `TfaIds` does not name are declared here and resolved through the same
 * [RemapTable] (they are outside every remapped range, so they stay as written). They are not registered in the
 * shared id registry on purpose (that registry must hold exactly the `TfaIds` groups).
 *
 * Skeleton check (`raids4/dreadnoth/tools/FrameBase.java` on the 2746 cache, rerun for this unit):
 * - Dreadnoth 16665 stand 14776 / walk 14778 are framemap 2725; 14777, 14779, 14780, 14782 are framemap 2725 too.
 * - Jelly 16666 stand 1583 / walk 1584 are framemap 807; 1585, 1586, 1587, 14773, 14774 (group 14927), 15161,
 *   15162 (group 10013) are framemap 807 too.
 * - 14781 (framemap 2726) is the projectile model's own loop inside spotanim 4211; the server never plays it.
 */
internal object DreadnothIds {

    // NPCs (§4.5 identity, CACHE)
    val BOSS: Int get() = TfaIds.Npc.DREADNOTH                 // 16665 tfa_jelly_boss, size 5
    val JELLY: Int get() = TfaIds.Npc.DREADNOTH_JELLY          // 16666 tfa_jelly_minion

    // Dreadnoth seqs (§4.5 / NOTES §5; cycles/30 = ticks)
    val SEQ_MELEE: Int get() = TfaIds.Seq.DREADNOTH_MELEE      // 14779 dreadnoth_melee01, 53 cyc = 1.77 t, walkmerge
    val SEQ_HOP: Int get() = TfaIds.Seq.DREADNOTH_HOP          // 14777 dreadnoth_jump01, 54 cyc = 1.8 t (threshold slam)
    val SEQ_JUMP: Int get() = TfaIds.Seq.DREADNOTH_JUMP        // 14780 dreadnoth_jump02, 117 cyc = 3.9 t (enrage leap)
    val SEQ_DEATH: Int get() = TfaIds.Seq.DREADNOTH_DEATH      // 14782 dreadnoth_death01, 130 cyc = 4.33 t

    // Jelly seqs (§4.5; stock jelly skeleton 807)
    val SEQ_JELLY_ATTACK: Int get() = TfaIds.Seq.JELLY_ATTACK  // 1586 jelly_attack, 29 cyc ~ 1 t
    val SEQ_JELLY_DEATH: Int get() = TfaIds.Seq.JELLY_DEATH    // 1587 jelly_death, 68 cyc
    val SEQ_JELLY_SPAWN: Int get() = TfaIds.Seq.JELLY_SPAWN    // 15162 jelly_spawn, 82 cyc = 2.73 t (1587 reversed)
    val SEQ_JELLY_HOP_1: Int get() = TfaIds.Seq.JELLY_HOP      // 14774 npc_jelly_jump_1, 30 cyc = 1 t
    val SEQ_JELLY_HOP_3: Int get() = TfaIds.Seq.JELLY_JUMP_3   // 14773 npc_jelly_jump_3, 92 cyc = 3.07 t
    val SEQ_JELLY_RANGED: Int get() = TfaIds.Seq.JELLY_RANGED  // 15161 jelly_range, 27 cyc = 0.9 t (U74)

    /** Stock jelly defend seq: GadScape combat def of npc 437 "Jelly" (block 1585, 50 cyc); framemap 807. */
    val SEQ_JELLY_BLOCK: Int by lazy { RemapTable.active.resolve(IdKind.SEQ, 1585) }

    // Spotanims (§4.5, CACHE config 13)
    val SPOT_JUMP_SHADOW: Int get() = TfaIds.Spot.DREADNOTH_JUMP_SHADOW      // 4209, 5x5 disc, seq 7816 (90 cyc = 3 t)
    val SPOT_JELLY_FORMING: Int get() = TfaIds.Spot.JELLY_FORMING            // 4210, jelly model at 50%, seq 14775 (2.8 t)
    val SPOT_RANGED_PROJ: Int get() = TfaIds.Spot.DREADNOTH_RANGED_PROJ      // 4211 projectile (volleys, enrage follow-up)
    val SPOT_SPLATTER_PROJ: Int get() = TfaIds.Spot.DREADNOTH_SPLATTER_PROJ  // 4212 "jelly splatter" projectile

    // Loc (§4.5): blood tile, same art as ToB Maiden's blood trail 32984; blockwalk=no
    val LOC_BLOOD: Int get() = TfaIds.Loc.DREADNOTH_BLOOD                    // 62609

    // Stock jelly sounds: GadScape combat def of npc 437 "Jelly" (attackSound 547, blockSound 550, deathSound 549)
    val SYNTH_JELLY_ATTACK: Int by lazy { RemapTable.active.resolve(IdKind.SYNTH, 547) }
    val SYNTH_JELLY_BLOCK: Int by lazy { RemapTable.active.resolve(IdKind.SYNTH, 550) }
    val SYNTH_JELLY_DEATH: Int by lazy { RemapTable.active.resolve(IdKind.SYNTH, 549) }
}

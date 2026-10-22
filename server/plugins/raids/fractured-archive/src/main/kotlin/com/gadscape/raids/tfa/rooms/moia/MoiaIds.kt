package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.ids.IdKind
import com.gadscape.raids.tfa.ids.RemapTable
import com.gadscape.raids.tfa.ids.TfaIds

/**
 * The ids the Moia room uses, in one place. Raid ids come from [TfaIds] (live 2746 numbers resolved through
 * `remap.tsv`); the few the room needs that `TfaIds` lacks are resolved here the same way, with their evidence
 * (listed in `raids4-impl/ENC-moia.md` for a later `TfaIds` addition).
 *
 * Skeleton check (`raids4-impl/moia-tools/SeqSkel.java` on cache 2746 and cacheV): every Moia seq 14783-14788 is a
 * maya seq on skeleton 2734 (her stand/walk 14783 is on 2734); every binder seq 14789-14792 is on framemap 1288 (its
 * stand 14789 / walk 14790 are on 1288); the cough seqs 11267 / 11268 are on framemap 0 (players).
 */
internal object MoiaIds {

    // NPCs (spec §4.4 identity table, cache 2746 config 9)
    val MOIA: Int get() = TfaIds.Npc.MOIA                       // 16667 tfa_moia, size 3
    val BINDER: Int get() = TfaIds.Npc.MOIA_BINDER              // 16670 tfa_moia_minion "Undead binder"

    // Moia seqs, skeleton 2734 (cycles / 30 = ticks, SeqSkel)
    val SEQ_DEATH: Int get() = TfaIds.Seq.MOIA_DEATH            // 14784 moia_death01, 150 c = 5 t
    val SEQ_CAST: Int get() = TfaIds.Seq.MOIA_CAST              // 14785 moia_projectile_attack01, 60 c = 2 t (blue special, infect)
    val SEQ_DAGGER: Int get() = TfaIds.Seq.MOIA_DAGGER          // 14786 moia_dagger_attack01, 60 c = 2 t (ranged auto)
    val SEQ_CHANT: Int get() = TfaIds.Seq.MOIA_CHANT            // 14787 moia_chant01, 90 c = 3 t loop (enrage charge)
    val SEQ_TELEPORT: Int get() = TfaIds.Seq.MOIA_TELEPORT      // 14788 moia_teleport01, 34 c = 1.1 t

    // Binder seqs, framemap 1288 (stock zombie_update weapon rig)
    val SEQ_BINDER_ATTACK: Int get() = TfaIds.Seq.MOIA_BINDER_ATTACK // 14791 moia_minion_attack, 90 c = 3 t, walkmerge
    val SEQ_BINDER_DEATH: Int get() = TfaIds.Seq.MOIA_BINDER_DEATH   // 14792 moia_minion_death, 370 c = 12.3 t

    // Spotanims (spec §4.4 / notes §7; dump.spot 2746)
    val SPOT_MAGIC_PROJ: Int get() = TfaIds.Spot.MOIA_MAGIC_PROJ            // 4213 moia_dagger_magic (blue dagger, anim 14794)
    val SPOT_DAGGER_PROJ: Int get() = TfaIds.Spot.MOIA_DAGGER_PROJ          // 4214 moia_dagger (anim 14793)
    val SPOT_MAGIC_IMPACT: Int get() = TfaIds.Spot.MOIA_MAGIC_IMPACT        // 4216 moia_dagger_magic_impact
    val SPOT_INFECT_GROUND: Int get() = TfaIds.Spot.MOIA_INFECT_GROUND      // 4217 moia_infect_spotanim (anim 14795, 2 t)
    val SPOT_INFECT_PROJ: Int get() = TfaIds.Spot.MOIA_INFECT_PROJ          // 4218 moia_infect_attack_projanim (anim 14796)
    val SPOT_TELEPORT_OUT: Int get() = TfaIds.Spot.MOIA_TELEPORT_OUT        // 4219 moia_teleport_spotanim (anim 14797, 3 t)
    val SPOT_TELEPORT_IN: Int get() = TfaIds.Spot.MOIA_TELEPORT_IN          // 4220 moia_teleport_in_spotanim (anim 14798, 2.6 t)
    val SPOT_DEATH: Int get() = TfaIds.Spot.MOIA_DEATH                      // 4221 moia_teleport_death_spotanim (anim 14799, 5 t)
    val SPOT_BINDER_RELEASE: Int get() = TfaIds.Spot.MOIA_BINDER_EXPLODE    // 4222 moia_minion_empower_spotanim_out_01 (anim 14802, 2.67 t)
    val SPOT_BINDER_EMPOWER: Int get() = TfaIds.Spot.MOIA_BINDER_EMPOWER_LOOP // 4223 moia_minion_empower_spotanim_01 (anim 14801, 3 t loop)
    val SPOT_BINDER_EMPOWER_IN: Int get() = TfaIds.Spot.MOIA_BINDER_EMPOWER_IN // 4224 moia_minion_empower_in_spotanim_01 (anim 14800, 3 t)
    val SPOT_PLAYER_EXPLODE: Int get() = TfaIds.Spot.MOIA_PLAYER_EXPLODE    // 4225 moia_chant_player_explode (anim 14803, 1 t)

    /** Length in ticks of the 4223 / 4224 loops (14800 / 14801 are 90 c). */
    const val EMPOWER_LOOP_TICKS = 3

    /** Length in ticks of the chant 14787 (90 c), re-sent to loop it. */
    const val CHANT_TICKS = 3

    // Headbar (spec §4.4 enrage: "charge bar is headbar 85 (yellow, 70)"; dump.headbar 85 segments=70)
    val HEADBAR_CHARGE: Int get() = TfaIds.Headbar.CHARGE_70    // 85 standard_charge_70
    const val HEADBAR_CHARGE_SEGMENTS = 70

    // Client script (spec §3.7 prefetch table)
    val SCRIPT_PREFETCH: Int get() = TfaIds.Script.PREFETCH_MOIA // 10015, no args

    // Music (spec §3.7 music table: "Daughter of Darkness", dbrow 17007, unlock 27,28)
    val MIDI: Int get() = TfaIds.Midi.MOIA                      // 892 tfa_moia_combat

    /**
     * Stock seq 11268 `wgs_player_coughs_loop` (spec §4.4 Virus: "optional player seq 11268"; framemap 0; 54 c).
     * Not in `TfaIds`; played only with `moia.json infect.coughAnim`.
     */
    val SEQ_PLAYER_COUGH: Int by lazy { RemapTable.active.resolve(IdKind.SEQ, 11268) }
}

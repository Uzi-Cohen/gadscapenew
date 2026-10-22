package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.ids.TfaIds

/**
 * Every cache id the Bork and the Smorks room uses, with its evidence. All of them are `TfaIds` constants (resolved
 * through `remap.tsv`); nothing here is a raw id.
 *
 * Skeleton check, rerun for this unit (`raids4-impl/guardian-tools/SkelCheck.java` on `audit/raids4/c2746/cache`):
 * - Bork 16640: stand 14540 / walk 14539 are maya skeleton **2703**; 14541-14552, 14556 and 14557 are skeleton 2703 too.
 * - The Smorks 16641: stand = walk 14553 is maya skeleton **2771**; 14554 and 14555 are skeleton 2771 too.
 * - **14538 `bork_stunned` is NOT a Bork seq**: it is a frame seq on framemap 905, the stars model 3070 of spotanim
 *   4035 (`npc_stunned_4x4`). The server never plays it on Bork (the spec's "use 14538 if a stun interrupts him" would
 *   put another skeleton's seq on him; he is freeze-immune anyway).
 * - 14558-14579 are the spotanims' own seqs (skeletons 2704-2786); the client plays them, the server never does.
 *
 * Lengths are from `raids4/bork-smorks/seqlen.txt` (cycles / 30 = ticks) and the seq configs in
 * `audit/raids4/dump/cfg.diff` (sound frames give the impact moments).
 */
internal object BorkIds {

    // ------------------------------------------------------------------------------------- NPCs (§4.3, CACHE)
    val BORK: Int get() = TfaIds.Npc.BORK                       // 16640 tfa_bork, size 4, cb 871
    val SMORKS: Int get() = TfaIds.Npc.SMORKS                   // 16641 tfa_smork, size 3, cb 640, turnspeed 12
    /** 16642 `tfa_bork_smork_hp_ui_handler`: HUD title "Bork and the Smorks", params 2762/2769 true, 2767/2768 = 16. */
    val HUD_NPC: Int get() = TfaIds.Npc.BORK_SMORKS_HUD

    // ---------------------------------------------------------------- Bork seqs (skeleton 2703, NOTES §5)
    /** 14542 npc_bork_gong_melee01, 90 cyc = 3 t, keyframewalkmerge; impact synth 12768 at cycle 58 → hit at +2 t. */
    val SEQ_BEAT: Int get() = TfaIds.Seq.BORK_BEAT
    /** Daze seqs by length: 14543 (240 cyc = 8 t), 14544 (360 = 12 t), 14545 (420 = 14 t); preanim_move=merge. */
    val SEQ_DAZE_8: Int get() = TfaIds.Seq.BORK_DAZE_8
    val SEQ_DAZE_12: Int get() = TfaIds.Seq.BORK_DAZE_12
    val SEQ_DAZE_14: Int get() = TfaIds.Seq.BORK_DAZE_14
    /** 14549 npc_bork_enraged_stomp_attack_in01, 60 cyc = 2 t (from the daze pose), priority 5. */
    val SEQ_STOMP_IN_FROM_DAZE: Int get() = TfaIds.Seq.BORK_STOMP_IN_FROM_DAZE
    /** 14556 npc_bork_idle_to_enraged_stomp_attack_in01, 30 cyc = 1 t (from idle), priority 5. */
    val SEQ_STOMP_IN_FROM_IDLE: Int get() = TfaIds.Seq.BORK_STOMP_IN_FROM_IDLE
    /** 14550 npc_bork_enraged_stomp_attack01, 180 cyc = 6 t, 2 stomps (synths 12276 @25, 12277 @116), priority 6. */
    val SEQ_STOMP: Int get() = TfaIds.Seq.BORK_STOMP
    /** 14551 npc_bork_enraged_stomp_attack_cycle_and_out01, 210 cyc = 7 t, the last 2 stomps then idle, priority 7. */
    val SEQ_STOMP_CYCLE_OUT: Int get() = TfaIds.Seq.BORK_STOMP_CYCLE_OUT
    /** 14552 npc_bork_enraged_stomp_attack_out01, 30 cyc = 1 t, priority 8: the early exit (spec [C+]). */
    val SEQ_STOMP_OUT: Int get() = TfaIds.Seq.BORK_STOMP_OUT
    /** 14546 npc_bork_enraged_gong_attack_in01, 30 cyc = 1 t (from the daze pose, spec [C+] U73). */
    val SEQ_GONG_IN_FROM_DAZE: Int get() = TfaIds.Seq.BORK_GONG_IN_FROM_DAZE
    /** 14557 npc_bork_idle_to_enraged_gong_attack_in01, 30 cyc = 1 t (from idle). */
    val SEQ_GONG_IN_FROM_IDLE: Int get() = TfaIds.Seq.BORK_GONG_IN_FROM_IDLE
    /** 14547 npc_bork_enraged_gong_attack01, 30 cyc = 1 t, loops=30, impact synth 12768 @22: one beat per tick. */
    val SEQ_GONG: Int get() = TfaIds.Seq.BORK_GONG
    /** 14548 npc_bork_enraged_gong_attack_out01, 30 cyc = 1 t, priority 7 (ends the 14547 loop). */
    val SEQ_GONG_OUT: Int get() = TfaIds.Seq.BORK_GONG_OUT
    /** 14541 npc_bork_death01, 195 cyc = 6.5 t, priority 10 (body hits the floor at cycles 74-81). */
    val SEQ_DEATH: Int get() = TfaIds.Seq.BORK_DEATH

    // ------------------------------------------------------------ Smorks seqs (skeleton 2771, NOTES §5)
    /** 14554 npc_smork_cannon_attack01, 90 cyc = 3 t, duplicatebehaviour=reset: the auto volley. */
    val SEQ_VOLLEY: Int get() = TfaIds.Seq.SMORKS_VOLLEY
    /** 14555 npc_smork_big_cannon_attack01, 90 cyc = 3 t, synths at cycles 7-19 (fires about 0.5 t in): specials. */
    val SEQ_BIG_CANNON: Int get() = TfaIds.Seq.SMORKS_BIG_CANNON

    // -------------------------------------------------------------- spotanims (CACHE config 13, cfg.diff)
    /** 4035 npc_stunned_4x4 (stars, model 3070, seq 14538 = 8.53 t). */
    val SPOT_STUN_STARS: Int get() = TfaIds.Spot.BORK_STUN_STARS
    /** 4036 vfx_bork_death_spotanim01 (seq 14558, 6.5 t) with death 14541. */
    val SPOT_DEATH: Int get() = TfaIds.Spot.BORK_DEATH
    /** 4037 vfx_bork_gong_melee_spotanim01 (seq 14559, 3 t) with every beat 14542. */
    val SPOT_BEAT: Int get() = TfaIds.Spot.BORK_BEAT
    /** 4038 / 4040 / 4041 vfx_bork_(12|14)_cycle_stun (model 62590; 8 / 12 / 14 t) pair with 14543 / 14544 / 14545. */
    val SPOT_DAZE_8: Int get() = TfaIds.Spot.BORK_DAZE_8
    val SPOT_DAZE_12: Int get() = TfaIds.Spot.BORK_DAZE_12
    val SPOT_DAZE_14: Int get() = TfaIds.Spot.BORK_DAZE_14
    /** 4039 vfx_bork_enraged_stomp_spotanim01 (seq 14561, 6 t) pairs with 14550 (sent again with 14551). */
    val SPOT_STOMP: Int get() = TfaIds.Spot.BORK_STOMP

    /** 4042 vfx_smork_cannon_attack_spotanim01 (muzzle smoke, 1.8 t) with every volley 14554. */
    val SPOT_VOLLEY: Int get() = TfaIds.Spot.SMORKS_VOLLEY
    /** 4043 vfx_smork_big_cannon_attack_spotanim01 (smoke, 3 t) and 4044 ..._flash (3 t) with 14555. */
    val SPOT_BIG_CANNON: Int get() = TfaIds.Spot.SMORKS_BIG_CANNON
    val SPOT_BIG_CANNON_FLASH: Int get() = TfaIds.Spot.SMORKS_BIG_CANNON_FLASH
    /** 4045 vfx_smork_napalm_fire_projanim01: the incendiary cannonball projectile. */
    val SPOT_INCENDIARY_PROJ: Int get() = TfaIds.Spot.SMORKS_INCENDIARY_PROJ
    /** 4046 vfx_smork_napalm_fire_spotanim01 (fire column, 2 t): incendiary impact. */
    val SPOT_INCENDIARY_IMPACT: Int get() = TfaIds.Spot.SMORKS_INCENDIARY_IMPACT
    /** 4443 garboss_debris_shadow_red_60 (seq 9470, 56 cyc = 1.87 t): incendiary landing marker [INF weak]. */
    val SPOT_INCENDIARY_MARKER: Int get() = TfaIds.Spot.SMORKS_INCENDIARY_MARKER
    /** 4047-4050 vfx_smork_napalm_splat_spotanim01 (_90/_180/_270): oil appears (1.43 t). */
    fun spotOilSplat(rotation: Int): Int = TfaIds.Spot.SMORKS_OIL_SPLAT[rotation and 3]
    /** 4051-4054 vfx_smork_napalm_despawn_spotanim01 (_90/_180/_270): oil disappears (1.43 t). */
    fun spotOilDespawn(rotation: Int): Int = TfaIds.Spot.SMORKS_OIL_DESPAWN[rotation and 3]
    /** 4056 vfx_smork_range_attack_projanim01 (bone, 150%): ranged volley projectile; 4055 bone impact on the player. */
    val SPOT_BONE_PROJ: Int get() = TfaIds.Spot.SMORKS_BONE_PROJ
    val SPOT_BONE_IMPACT: Int get() = TfaIds.Spot.SMORKS_BONE_IMPACT
    /** 4057 vfx_smork_mage_attack_projanim01 (potions, 150%): magic projectile; 4058 splash at the tile; 4059 hit on the player. */
    val SPOT_POTION_PROJ: Int get() = TfaIds.Spot.SMORKS_POTION_PROJ
    val SPOT_POTION_SPLASH: Int get() = TfaIds.Spot.SMORKS_POTION_SPLASH
    val SPOT_POTION_HIT: Int get() = TfaIds.Spot.SMORKS_POTION_HIT
    /** 4060 tower / 4061 goblins / 4062 explosion: the Smorks' death (no death seq exists). */
    val SPOT_SMORKS_DEATH: List<Int> get() = TfaIds.Spot.SMORKS_DEATH.toList()
    /** 4440 cannonball_travel_big (model 2413 at 226%): heavy cannonball projectile [INF strong]. */
    val SPOT_HEAVY_PROJ: Int get() = TfaIds.Spot.SMORKS_HEAVY_PROJ
    /** 4442 gargboss_debris_shadow_120_small (seq 7817, 120 cyc = 4 t): heavy ball landing shadow. */
    val SPOT_HEAVY_SHADOW: Int get() = TfaIds.Spot.SMORKS_HEAVY_SHADOW

    /**
     * 4394-4405 tfa_wave_dust (model 46395 = ToA Zebak's floor roar): long 0/90/180/270 = 4394/4396/4398/4400 (seq 9647,
     * 2 t), short 0/90/180/270 = 4395/4397/4399/4401 (seq 11596, 1.53 t), diagonal shorts 45/135/225/315 = 4402-4405
     * (seq 10099, 1 t). NOTES §6 [INF strong]: the beat and stomp shockwave tiles.
     */
    fun spotDustLong(quarter: Int): Int = TfaIds.Spot.WAVE_DUST[2 * (quarter and 3)]
    fun spotDustShort(quarter: Int): Int = TfaIds.Spot.WAVE_DUST[2 * (quarter and 3) + 1]
    fun spotDustDiagonal(quarter: Int): Int = TfaIds.Spot.WAVE_DUST[8 + (quarter and 3)]

    // ------------------------------------------------------------------------------- locs (§4.3, CACHE)
    /** 62544 tfa_bork_ragged_tile (model 62729, blockwalk=no, not placed in the map): the oil puddle. */
    val LOC_OIL: Int get() = TfaIds.Loc.BORK_OIL
    /** 14576 npc_smork_napalm_splat_fade_spotanim01 (frames, framemap 2785, 1 t): the oil loc's fade. */
    val SEQ_OIL_FADE: Int get() = TfaIds.Seq.BORK_OIL_DESPAWN

    // ------------------------------------------------------------------------------ client (§3.7, §5)
    val PREFETCH: Int get() = TfaIds.Script.PREFETCH_BORK      // RUNCLIENTSCRIPT 10010
    val MIDI: Int get() = TfaIds.Midi.BORK                     // 888 tfa_bork_combat "Bork's Beats"
    /** tfa_client_primary0..7 (16408-16415): Bork's current target, for RuneLite (§3.7 [HOUSE]). */
    fun varbitPrimary(slot: Int): Int = TfaIds.Varbit.PRIMARY[slot]
    val PRIMARY_SLOTS: Int get() = TfaIds.Varbit.PRIMARY.size
}

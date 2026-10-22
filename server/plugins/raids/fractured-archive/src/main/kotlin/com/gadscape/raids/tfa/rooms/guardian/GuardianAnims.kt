package com.gadscape.raids.tfa.rooms.guardian

import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics

/**
 * Every seq and spotanim the Archive Guardian room plays, with its evidence, and the durations the timeline is built
 * from. Ids come from [TfaIds] (live 2746 numbers, remapped at boot); nothing here is a raw id.
 *
 * Evidence: cache 2746 seq configs (`raids4/archive-guardian/seq_cfg.txt`, osrs-dumps names), lengths and skeletons
 * decoded with `raids4-impl/guardian-tools/SkelCheck.java` (maya keyframe range or frame delays; 30 cycles = 1 tick).
 * - Boss seqs 14745-14755, 14757, 14758 are all maya seqs on skeleton **2694**, the skeleton of the stand/walk seqs
 *   of npcs 16657 (14757), 16658 (14758), 16659/16660 (14745).
 * - Vine seqs 14768-14771 are on skeleton **2693**, the skeleton of npc 16664's stand 14770.
 * - Battery seqs 15024-15026 are frame seqs on framemap **2680**, the framemap of npc 16662's stand 15024.
 * - Spotanims 4199-4208 (`vfx_guardian_*`) carry their own seqs (14759-14767, 14772); the client plays them.
 */
internal object GuardianAnims {

    // ---- Boss seqs (npc_archive_guardian_*, skeleton 2694) -------------------------------------------------------
    /** 14745 idle01: flying stand/walk of 16659/16660, 3 t loop. */
    val IDLE_FLYING: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_IDLE_FLYING) }
    /** 14746 ground_idle01: grounded idle, 4 t loop (not the cache readyanim, so the room replays it). */
    val IDLE_GROUNDED: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_IDLE_GROUNDED) }
    /** 14747 idle_to_ground01: landing, 4 t (120 cycles). */
    val LAND: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_LAND) }
    /** 14748 ground_to_flying01: lift-off, 3 t (90 cycles). */
    val LIFT: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_LIFT) }
    /** 14749 death01: death, 9 t (270 cycles). */
    val DEATH: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_DEATH) }
    /** 14750 swoosh_attack01: mushroom cast [INF: the only unassigned attack seq], 3 t. */
    val MUSHROOM_CAST: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_MUSHROOM_CAST) }
    /** 14751 range_attack01: ranged auto, 3 t. */
    val RANGED: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_RANGED) }
    /** 14752 melee_attack01: step-under slam, 3 t; sound at frame 63 = the impact (tick 2). */
    val SLAM: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_SLAM) }
    /** 14753 special_attack01: laser wind-up, 3 t. */
    val LASER: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_LASER) }
    /** 14754 enraged_phase01: enrage transition, 14 t (420 cycles). */
    val ENRAGE: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_ENRAGE) }
    /** 14755 spawn01: waking after the Touch, 5 t (150 cycles). */
    val WAKE: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_WAKE) }
    /** 14757 spawn_idle01: dormant stand of 16657 (its cache readyanim), 3 t loop. */
    val INERT_IDLE: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_INERT_IDLE) }

    // ---- Boss spotanims (vfx_guardian_*, resize 156 like the npc) -----------------------------------------------
    /** 4199 ranged_spotanim01 on the boss with the ranged seq (its seq 14759, 3 t). */
    val RANGED_CAST: Graphics by lazy { Graphics(TfaIds.Spot.GUARDIAN_RANGED_CAST) }
    /** 4202 idletoground_spotanim01 (flat ground decal, seq 14762, 4 t) with the landing. */
    val LAND_GFX: Graphics by lazy { Graphics(TfaIds.Spot.GUARDIAN_LAND) }
    /** 4203 groundtoidle_spotanim01 (seq 14763, 3 t) with the lift. */
    val LIFT_GFX: Graphics by lazy { Graphics(TfaIds.Spot.GUARDIAN_LIFT) }
    /** 4204 enraged_phase_spotanim01 (room-wide model, seq 14764, 14 t) with the enrage. */
    val ENRAGE_GFX: Graphics by lazy { Graphics(TfaIds.Spot.GUARDIAN_ENRAGE) }
    /** 4205 special_attack_spotanim01: the beam (model 62579 along +x, merged into the npc model so it turns with it; seq 14765, 4 t). */
    val LASER_GFX: Graphics by lazy { Graphics(TfaIds.Spot.GUARDIAN_LASER) }
    /** 4206 spawn_spotanim01 (seq 14766, 5 t) with the wake. */
    val WAKE_GFX: Graphics by lazy { Graphics(TfaIds.Spot.GUARDIAN_WAKE) }
    /** 4207 spawn_idle_spotanim01 (seq 14767, 6 t, loops=1) on the dormant guardian; replayed every [INERT_SPOT_LOOP]. */
    val INERT_IDLE_GFX: Graphics by lazy { Graphics(TfaIds.Spot.GUARDIAN_INERT_IDLE) }

    /** 4201 ranged_projanim01: the ranged projectile (seq 14761, 82 cycles). */
    val RANGED_PROJ_ID: Int get() = TfaIds.Spot.GUARDIAN_RANGED_PROJ
    /** 4200 ranged_hitanim01: impact on the target (seq 14760, 2 t). */
    val RANGED_IMPACT_ID: Int get() = TfaIds.Spot.GUARDIAN_RANGED_IMPACT
    /** 4208 tentacle_intro_spotanim01: tendril telegraph decal at the spawn tile (seq 14772, 4 t). */
    val TENDRIL_TELEGRAPH: Graphics by lazy { Graphics(TfaIds.Spot.GUARDIAN_TENDRIL_TELEGRAPH) }

    // ---- Vine 16664 (npc_guardian_archive_tentacle_*, skeleton 2693) ---------------------------------------------
    /** 14771 tentacle_spawn01: emerging, 4 t (120 cycles). */
    val VINE_SPAWN: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_TENDRIL_SPAWN) }
    /** 14768 tentacle_attack01: the 3x3 swipe, 2 t; sound at frame 13 = the hit. */
    val VINE_SWIPE: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_TENDRIL_SWIPE) }
    /** 14769 tentacle_despawn01: 38 cycles (removed 2 t after it starts). */
    val VINE_DESPAWN: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_TENDRIL_DESPAWN) }

    // ---- Battery 16662 (guthixian_battery_*, framemap 2680) -----------------------------------------------------
    /** 15025 guthixian_battery_despawn: the orb goes out, 30 cycles = 1 t; then the npc becomes 16663. */
    val BATTERY_DESPAWN: Animation by lazy { Animation(TfaIds.Seq.GUARDIAN_BATTERY_DESPAWN) }

    // ---- Durations in ticks (seq lengths above) ----------------------------------------------------------------
    const val WAKE_TICKS = 5         // 14755: 150 cycles
    const val LAND_TICKS = 4         // 14747: 120 cycles
    const val GROUND_IDLE_LOOP = 4   // 14746: 120 cycles
    const val LIFT_TICKS = 3         // 14748: 90 cycles
    const val ENRAGE_TICKS = 14      // 14754: 420 cycles
    const val DEATH_TICKS = 9        // 14749: 270 cycles
    const val INERT_SPOT_LOOP = 6    // 4207 / 14767: 180 cycles, plays once
    const val TELEGRAPH_TICKS = 4    // 4208 / 14772: 120 cycles, then the vine spawns
    const val VINE_EMERGE_TICKS = 4  // 14771: 120 cycles
    const val VINE_SWIPE_TICKS = 2   // 14768: 60 cycles
    const val VINE_DESPAWN_TICKS = 2 // 14769: 38 cycles, rounded up
    const val BATTERY_DESPAWN_TICKS = 1 // 15025: 30 cycles
    const val MUSHROOM_SPAWN_TICKS = 3  // loc 62578 anim 14756: 30 frames × 3 cycles
    const val MUSHROOM_BURST_TICKS = 2  // loc 62580 anim 8632 (nightmare_infection_spotanim_despawn): 60 cycles
}

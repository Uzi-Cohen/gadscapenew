package com.gadscape.raids.tfa.rooms.ezekiel

import com.gadscape.raids.tfa.ids.IdKind
import com.gadscape.raids.tfa.ids.RemapTable
import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics

/**
 * Every npc, seq, spotanim and headbar the Ezekiel room uses, with its evidence, and the durations the timeline is
 * built from. Raid ids come from [TfaIds] (live 2746 numbers, resolved through `remap.tsv`); the finale-sword seqs of
 * sets 01-03 that `TfaIds` does not name are declared here and resolved through the same [RemapTable] (they are below
 * the remapped 15000-15162 range, so they stay as written). They are not registered in the shared id registry on
 * purpose (it holds exactly the `TfaIds` groups).
 *
 * Evidence:
 * - Names and configs: cache 2746 (`audit/raids4/dump/cfg.diff`, osrs-dumps 6e5033af), `raids4/ezekiel/NOTES.md` §8.
 * - Lengths: `raids4/ezekiel/seqlen.txt` (maya keyframe range or frame delays; 30 cycles = 1 tick).
 * - Skeletons, decoded for this unit from cache 2746 with `raids4-impl/guardian-tools/SkelCheck.java`:
 *   - Ezekiel 16652 stand 14683 / walk 14684 are on skeleton **2727**; so are 14685-14697 (every boss seq used here)
 *     and 14690, the readyanim of the death sword 16656 (whose model 62635 is the sword piece of Ezekiel's model).
 *   - Animated Sword 16653 stand/walk 14707 is on skeleton **2733** (the only seq it plays).
 *   - Sword fence 16654 stand 14729 is on framemap **2729**; the drop/idle seqs 14731-14744 are on 2729 too.
 *   - Finale sword 16655 stand 14713 is on framemap **2730**; 14710-14725 (drop/telegraph/despawn/idle of sets 01-04)
 *     are all on 2730, so the npc can play the seqs of any set directly.
 *   - Player dodge seqs 14705 / 14706 are on framemap **0**, the human skeleton of stock player seqs 808 / 819 / 422.
 *   - Spotanim seqs (14698-14704, 14708-14728) are played by the client with their spotanim; the server never sends
 *     them as npc seqs.
 */
internal object EzekielIds {

    // ---- NPCs (§4.2 identity, CACHE npc configs) ------------------------------------------------------------------
    val BOSS: Int get() = TfaIds.Npc.EZEKIEL                       // 16652 tfa_ezekiel, size 3, op2 Attack
    val SWORD: Int get() = TfaIds.Npc.EZEKIEL_ANIMATED_SWORD       // 16653 tfa_ezekiel_sword, size 3, HP 80, turnspeed 0
    val FENCE: Int get() = TfaIds.Npc.EZEKIEL_SWORD_FENCE          // 16654 tfa_ezekiel_sword_noop, size 3, active=no, turnspeed 0
    val FINALE_SWORD: Int get() = TfaIds.Npc.EZEKIEL_FINALE_SWORD  // 16655 tfa_ezekiel_finale_vfx, size 1, model 62639
    val DEATH_SWORD: Int get() = TfaIds.Npc.EZEKIEL_DEATH_SWORD    // 16656 tfa_ezekiel_death_sword, size 3, "Sword"

    // ---- Ezekiel seqs (npc_ezekiel_*, skeleton 2727; ticks = cycles / 30) -------------------------------------------
    /** 14685 npc_ezekiel_melee01: cleave, 60 cyc = 2 t, keyframewalkmerge; swing synth at kf 2, impact synths at kf 11. */
    val CLEAVE: Animation by lazy { Animation(TfaIds.Seq.EZEKIEL_CLEAVE) }
    /** 14686 npc_ezekiel_magic01: lightning on all, 90 cyc = 3 t, keyframewalkmerge; release synths at kf 22-24. */
    val LIGHTNING: Animation by lazy { Animation(TfaIds.Seq.EZEKIEL_LIGHTNING) }
    /** 14687 npc_ezekiel_special01: the cross + sword summon, 150 cyc = 5 t, priority 5, preanim_move=merge. */
    val SPECIAL: Animation by lazy { Animation(TfaIds.Seq.EZEKIEL_STAR) }
    /** 14688 npc_ezekiel_heal01: heal, 90 cyc = 3 t (also the seq of spot 4173). */
    val HEAL: Animation by lazy { Animation(TfaIds.Seq.EZEKIEL_HEAL) }
    /** 14691 npc_ezekiel_idle_to_weaken01: stun start, 60 cyc = 2 t. */
    val STUN_IN: Animation by lazy { Animation(TfaIds.Seq.EZEKIEL_STUN_IN) }
    /** 14692 npc_ezekiel_weaken01: stun loop, 30 cyc, loops=30. */
    val STUN_LOOP: Animation by lazy { Animation(TfaIds.Seq.EZEKIEL_STUN_LOOP) }
    /** 14693 npc_ezekiel_weaken_to_idle01: stun end, 30 cyc = 1 t. */
    val STUN_OUT: Animation by lazy { Animation(TfaIds.Seq.EZEKIEL_STUN_OUT) }
    /** 14694 npc_ezekiel_finale01: imbue at the finale start, 90 cyc = 3 t, priority 8. */
    val FINALE: Animation by lazy { Animation(TfaIds.Seq.EZEKIEL_FINALE) }
    /** 14696 npc_ezekiel_finale_loop01: finale idle, 30 cyc = 1 t (re-sent every tick: no npc base-anim override). */
    val FINALE_LOOP: Animation by lazy { Animation(TfaIds.Seq.EZEKIEL_FINALE_LOOP) }
    /** 14697 npc_ezekiel_finale_death01: death, 180 cyc = 6 t, loops=1, priority 10 (script 10012 prefetches it). */
    val DEATH: Animation by lazy { Animation(TfaIds.Seq.EZEKIEL_DEATH) }

    // ---- Player seqs (framemap 0, the human skeleton) -----------------------------------------------------------
    /** 14705 tfa_ezekiel_roll (copy of human_myq6_wyrd_roll), 33 cyc = 1.1 t, hides held items: sword dodge. */
    val PLAYER_ROLL: Animation by lazy { Animation(TfaIds.Seq.PLAYER_EZEKIEL_ROLL) }
    /** 14706 tfa_ezekiel_strafe (copy of myq6_strafe), 25 cyc = 0.83 t, walkmerge: sideways sword dodge. */
    val PLAYER_STRAFE: Animation by lazy { Animation(TfaIds.Seq.PLAYER_EZEKIEL_STRAFE) }

    // ---- Sword fence 16654 seqs (framemap 2729): drop 0N 42 cyc = 1.4 t (loops 1), idle 0N 1 frame (30 cyc) ----------
    fun fenceDrop(i: Int): Animation = Animation(TfaIds.Seq.EZEKIEL_FENCE_DROP[i])
    fun fenceIdle(i: Int): Animation = Animation(TfaIds.Seq.EZEKIEL_FENCE_IDLE[i])
    val FENCE_VARIANTS: Int get() = TfaIds.Seq.EZEKIEL_FENCE_DROP.size

    // ---- Finale sword 16655 seqs (framemap 2730), per set: drop (60 cyc), telegraph, despawn (30 cyc), idle (1 frame)
    // Set 01 = spots 4180-4183 (seqs 14710 drop, 14711 telegraph 20 cyc, 14712 despawn, 14713 idle = npc readyanim).
    // Set 02 = spots 4184-4187 (14714 drop, 14715 telegraph 25 cyc, 14716 despawn, 14717 idle).
    // Set 03 = spots 4188-4191 (14718 drop, 14719 telegraph 25 cyc, 14720 despawn, 14721 idle).
    // Set 04 = spots 4192-4195 (14722-14725) is unassigned in the spec and kept spare.
    private fun seq(id: Int): Int = RemapTable.active.resolve(IdKind.SEQ, id)

    /** One finale sword's visuals: npc seqs of its set and the matching spotanims (spec §4.2 [C+] spot sets). */
    class FinaleSet(val drop: Int, val idle: Int, val telegraph: Int, val despawn: Int, spots: com.gadscape.raids.tfa.ids.IdList) {
        val dropSpot: Int = spots[0]
        val idleSpot: Int = spots[1]
        val telegraphSpot: Int = spots[2]
        val despawnSpot: Int = spots[3]
    }

    val FINALE_SET_LEFT: FinaleSet by lazy { FinaleSet(seq(14710), TfaIds.Seq.EZEKIEL_FINALE_SWORD_IDLE, seq(14711), seq(14712), TfaIds.Spot.EZEKIEL_FINALE_SWORD_LEFT) }
    val FINALE_SET_RIGHT: FinaleSet by lazy { FinaleSet(seq(14714), seq(14717), seq(14715), seq(14716), TfaIds.Spot.EZEKIEL_FINALE_SWORD_RIGHT) }
    val FINALE_SET_FRONT: FinaleSet by lazy { FinaleSet(seq(14718), seq(14721), seq(14719), seq(14720), TfaIds.Spot.EZEKIEL_FINALE_SWORD_FRONT) }

    // ---- Spotanims (config 13, CACHE) ---------------------------------------------------------------------------
    /** 4167 ezekiel_magic_attack_vfx01 (model 41538 lightning, seq 14698 90 cyc): on every player at the cast. */
    val LIGHTNING_CAST: Graphics by lazy { Graphics(TfaIds.Spot.EZEKIEL_LIGHTNING_CAST) }
    /** 4168 ..._impact_vfx01 (seq 14699, 60 cyc): on every player when the lightning lands. */
    val LIGHTNING_IMPACT: Graphics by lazy { Graphics(TfaIds.Spot.EZEKIEL_LIGHTNING_IMPACT) }
    /** 4169 ezekiel_special_crack_vfx01 (model 62636 ~4x4 tiles, seq 14700 150 cyc): under him with the special. */
    val STAR_CRACK: Graphics by lazy { Graphics(TfaIds.Spot.EZEKIEL_STAR_CRACK) }
    /** 4170 star explosion spawn (seq 14701, 60 cyc): every cross tile at t0. */
    val STAR_SPAWN_ID: Int get() = TfaIds.Spot.EZEKIEL_STAR_SPAWN
    /** 4171 star explosion marker (seq 14702, 30 cyc): every cross tile at t2. */
    val STAR_MARKER_ID: Int get() = TfaIds.Spot.EZEKIEL_STAR_MARKER
    /** 4172 star explosion (seq 14703, 145 cyc: first burst from cycle 0, second from cycle 59 = 2 t later). */
    val STAR_EXPLOSION_ID: Int get() = TfaIds.Spot.EZEKIEL_STAR_EXPLOSION
    /** 4173 ezekiel_heal_glow_vfx01 (model 62649, seq 14688 = the heal seq, so it follows his skeleton): on him. */
    val HEAL_GLOW: Graphics by lazy { Graphics(TfaIds.Spot.EZEKIEL_HEAL_GLOW) }
    /** 4174 ezekiel_heal_spotanim_vfx01 (model 62643 +-625 units = arena-wide, seq 14704 90 cyc): at his centre tile. */
    val HEAL_WAVE_ID: Int get() = TfaIds.Spot.EZEKIEL_HEAL
    /** 4175 vfx_ezekiel_finale_cast01 (model 41538, seq 14708 90 cyc): lightning on his sword at each finale attack. */
    val FINALE_CAST: Graphics by lazy { Graphics(TfaIds.Spot.EZEKIEL_FINALE_CAST) }
    /** 4176 vfx_ezekiel_finale_cast_loop01 (seq 14709 30 cyc): between casts while a volley runs (re-sent per tick). */
    val FINALE_CAST_LOOP: Graphics by lazy { Graphics(TfaIds.Spot.EZEKIEL_FINALE_CAST_LOOP) }
    /** 4177 vfx_ezekiel_finale_cast_glow01 (model 62650, seq 14695 = finale01's keyframeset): with seq 14694. */
    val FINALE_GLOW: Graphics by lazy { Graphics(TfaIds.Spot.EZEKIEL_FINALE_GLOW) }
    /** 4178 ..._glow_loop01 (seq 14696 = the finale loop): with seq 14696 between volleys (re-sent per tick). */
    val FINALE_GLOW_LOOP: Graphics by lazy { Graphics(TfaIds.Spot.EZEKIEL_FINALE_GLOW_LOOP) }
    /** 4179 vfx_ezekiel_finale_death_glow_01 (seq 14697 = the death seq): with the death. */
    val DEATH_GLOW: Graphics by lazy { Graphics(TfaIds.Spot.EZEKIEL_DEATH_GLOW) }
    /** 4196 / 4197 / 4198 finale impact large / medium / small (model 62638 ~2 tiles, seqs 14726-14728, 30 cyc). */
    val IMPACT_LARGE_ID: Int get() = TfaIds.Spot.EZEKIEL_FINALE_IMPACT_LARGE
    val IMPACT_MEDIUM_ID: Int get() = TfaIds.Spot.EZEKIEL_FINALE_IMPACT_MEDIUM
    val IMPACT_SMALL_ID: Int get() = TfaIds.Spot.EZEKIEL_FINALE_IMPACT_SMALL

    // ---- Headbars (E14, cache 2746 headbar configs) -----------------------------------------------------------------
    /** 85 standard_charge_70 (yellow, 70 segments): sword empower. */
    val HEADBAR_EMPOWER: Int get() = TfaIds.Headbar.CHARGE_70
    /** 88 headbar_heat_60 (orange, 60 segments): sword launch timer. */
    val HEADBAR_LAUNCH: Int get() = TfaIds.Headbar.HEAT_60

    // ---- Durations in ticks (seq lengths above) ---------------------------------------------------------------------
    const val CLEAVE_TICKS = 2          // 14685: 60 cycles
    const val LIGHTNING_TICKS = 3       // 14686: 90 cycles
    const val SPECIAL_TICKS = 5         // 14687: 150 cycles
    const val HEAL_TICKS = 3            // 14688: 90 cycles
    const val STUN_IN_TICKS = 2         // 14691: 60 cycles
    const val STUN_OUT_TICKS = 1        // 14693: 30 cycles
    const val FINALE_IMBUE_TICKS = 3    // 14694: 90 cycles
    const val DEATH_TICKS = 6           // 14697: 180 cycles
    const val FINALE_CAST_TICKS = 3     // 4175 / 14708: 90 cycles
    const val FENCE_DROP_TICKS = 2      // 14731..14743: 42 cycles, rounded up
    const val FINALE_SWORD_DROP_TICKS = 2   // 14710 / 14714 / 14718: 60 cycles
    const val FINALE_SWORD_TELEGRAPH_TICKS = 1 // 14711 20 cyc / 14715, 14719 25 cyc, rounded up
    const val FINALE_SWORD_DESPAWN_TICKS = 1   // 14712 / 14716 / 14720: 30 cycles
    const val ROLL_CYCLES = 33          // 14705
    const val STRAFE_CYCLES = 25        // 14706
    const val CYCLES_PER_TICK = 30
}

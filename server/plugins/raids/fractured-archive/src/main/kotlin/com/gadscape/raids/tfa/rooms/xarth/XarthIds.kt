package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.raids.tfa.ids.TfaIds

/**
 * Every cache id the Xarth room uses, with its evidence (spec §4.7 identity table and [C+] additions,
 * `raids4/xarth/NOTES.md` §2, §5, §6, `seq_xarth.txt`, `spot_xarth.txt`, `loc_xarth.txt`, `npc_xarth.txt`).
 * All of them are `TfaIds` names (live 2746 numbers resolved through `remap.tsv`), so the corpse/pile seqs in the
 * clashing 15000-15016 range follow the asset port without code changes.
 *
 * Skeleton check, rerun for this unit on `audit/raids4/c2746/cache` (`raids4-impl/moia-tools/SeqSkel`,
 * `raids4-impl/dagonhai-tools/FmLabels` + `ModelSkins`):
 * - Xarth 16684: stand 14979 / walk 14980 are framemap 2721 (162 labels = the 162 vertex labels of model 62620);
 *   14981, 14982, 14983, 14984, 14985, 14989, 14991, 14992, 14995, 15017, 15018 are framemap 2721 too.
 * - Corpse 16687 stand 14999 / walk 15000 and doused 16688 stand 808 `human_ready` are framemap 0 (the stock
 *   humanoid, 218 labels; model 62515 uses labels <= 86); 15003 and 15005 are framemap 0 too.
 * - Pillar debris 16686 (model 62467, vertex labels 0-23): 14997 `loc_xarth_rubble_collapse_01` is framemap 2691
 *   (28 labels). The standing helper 16685 is the invisible 8-vertex box 32709 and is never animated.
 * - Flame loc 62713: its own config anim is 12005; the spawn anim 12003 is the same framemap 2277.
 * - Pile fire locs 62748-62762 carry their config anims 15008-15015 (framemap 2689): spawning the loc plays them.
 *
 * Lengths below are `cycles / 30` from `seq_xarth.txt` (rounded up to whole ticks).
 */
internal object XarthIds {

    // ---------------------------------------------------------------------------------- NPCs (CACHE npc config)
    val XARTH: Int get() = TfaIds.Npc.XARTH                              // 16684 tfa_xarth, size 3, resize 165%
    val PILLAR: Int get() = TfaIds.Npc.XARTH_PILLAR                      // 16685 tfa_xarth_rubble_npc (invisible)
    val PILLAR_DESTROYED: Int get() = TfaIds.Npc.XARTH_PILLAR_DESTROYED  // 16686 debris model 62467
    val CORPSE: Int get() = TfaIds.Npc.XARTH_CORPSE                      // 16687 tfa_xarth_corpse (burning)
    val CORPSE_DOUSED: Int get() = TfaIds.Npc.XARTH_CORPSE_DOUSED        // 16688 tfa_xarth_corpse_doused

    // ------------------------------------------------------------- Xarth seqs (framemap 2721, seq_xarth.txt)
    val SEQ_TURN_LEFT: Int get() = TfaIds.Seq.XARTH_TURN_LEFT        // 14981, 90 cyc = 3 t, prio 5 (spec [C+])
    val SEQ_TURN_RIGHT: Int get() = TfaIds.Seq.XARTH_TURN_RIGHT      // 14982, 90 cyc = 3 t, prio 5 (spec [C+])
    val SEQ_MELEE: Int get() = TfaIds.Seq.XARTH_MELEE                // 14983, 60 cyc = 2 t, impact synth 12686 @ 22 cyc
    val SEQ_MAGIC: Int get() = TfaIds.Seq.XARTH_MAGIC                // 14984, 60 cyc = 2 t, release synth 12681 @ 32 cyc; also the corpse summon [DOC]
    val SEQ_RANGED: Int get() = TfaIds.Seq.XARTH_RANGED              // 14985, 90 cyc = 3 t, release synth 12689 @ 52 cyc
    val SEQ_FLAME_BLAST: Int get() = TfaIds.Seq.XARTH_FLAME_BLAST    // 14989, 150 cyc = 5 t (blast charge; heal blast)
    val SEQ_ROOT: Int get() = TfaIds.Seq.XARTH_ROOT                  // 14991, 90 cyc = 3 t (the bind)
    val SEQ_AOE: Int get() = TfaIds.Seq.XARTH_AOE                    // 14992, 90 cyc = 3 t (throws the two searing projectiles)
    val SEQ_DEATH: Int get() = TfaIds.Seq.XARTH_DEATH                // 14995, 150 cyc = 5 t, prio 10
    val SEQ_SUMMON: Int get() = TfaIds.Seq.XARTH_P2_TRANSITION       // 15017 xarth_attack_summon_01, 240 cyc = 8 t (P2 transition)
    val SEQ_DEFEND: Int get() = TfaIds.Seq.XARTH_DEFEND              // 15018, 90 cyc = 3 t, prio 4 (spec [C+])

    // -------------------------------------------------- pillar debris 16686 (framemap 2691 ⊇ model 62467 labels)
    val SEQ_RUBBLE_COLLAPSE: Int get() = TfaIds.Seq.XARTH_RUBBLE_COLLAPSE // 14997, 72 cyc = 2.4 t, loops 1

    // ------------------------------------------------------------------ corpse seqs (framemap 0, humanoid)
    val SEQ_CORPSE_DEATH: Int get() = TfaIds.Seq.XARTH_CORPSE_DEATH   // 15003, 120 cyc = 4 t, prio 10
    val SEQ_CORPSE_ABSORB: Int get() = TfaIds.Seq.XARTH_CORPSE_ABSORB // 15005, 60 cyc = 2 t, prio 7

    // --------------------------------------------------------- loc anims of the flame loc 62713 (framemap 2277)
    val SEQ_FLAME_SPAWN: Int get() = TfaIds.Seq.XARTH_FLAME_SPAWN     // 12003 vfx_branda_queen_loc01_spawn01, 150 cyc = 5 t
    val SEQ_FLAME_IDLE: Int get() = TfaIds.Seq.XARTH_FLAME_IDLE       // 12005 vfx_branda_queen_loc01_idle01 (= 62713's config anim)

    // ---------------------------------------------------------------------------- spotanims (spot_xarth.txt)
    val SPOT_P2_RESTORE: Int get() = TfaIds.Spot.XARTH_P2_RESTORE     // 4032 TD shield-restore aura at 165% (= Xarth's resize), seq 11401 4 t
    val SPOT_P2_DEPLETE: Int get() = TfaIds.Spot.XARTH_P2_DEPLETE     // 4033 TD shield-deplete aura, normal size [C+ optional]
    val SPOT_MAGIC_PROJ: Int get() = TfaIds.Spot.XARTH_MAGIC_PROJ     // 4034 TD fireball model 50027 at 256% [INF strong]
    val SPOT_MAGIC_IMPACT: Int get() = TfaIds.Spot.XARTH_MAGIC_IMPACT // 2854 stock TD magic impact [INF, house]
    val SPOT_RANGED_CAST: Int get() = TfaIds.Spot.XARTH_RANGED_CAST   // 4373 on Xarth with 14985
    val SPOT_RANGED_PROJ: Int get() = TfaIds.Spot.XARTH_RANGED_PROJ   // 4374 P2 ranged projectile
    val SPOT_RANGED_IMPACT: Int get() = TfaIds.Spot.XARTH_RANGED_IMPACT // 4375 on the target
    val SPOT_AOE: Int get() = TfaIds.Spot.XARTH_AOE                   // 4376 fire column on Xarth with 14992
    val SPOT_ROOT: Int get() = TfaIds.Spot.XARTH_ROOT                 // 4377 bind visual on every bound player (39 frames, 3 t)
    val SPOT_BOMB_PROJ: Int get() = TfaIds.Spot.XARTH_BOMB_PROJ       // 4378 TD fire bomb 54031 at 160%: bombs and P2 flames
    val SPOT_BOMB_IMPACT: Int get() = TfaIds.Spot.XARTH_BOMB_IMPACT   // 4379 bomb impact on the tile
    val SPOT_FLAME_BLAST: Int get() = TfaIds.Spot.XARTH_FLAME_BLAST   // 4380 the blast on Xarth (first frame held 60 cyc, then the burst)
    val SPOT_DEATH: Int get() = TfaIds.Spot.XARTH_DEATH               // 4381 death VFX, 5 t
    val SPOT_RUBBLE_COLLAPSE: Int get() = TfaIds.Spot.XARTH_RUBBLE_COLLAPSE // 4382 pillar collapse fire/dust
    val SPOT_CORPSE_WALK: Int get() = TfaIds.Spot.XARTH_CORPSE_WALK_FIRE    // 4383 per step of a burning corpse
    val SPOT_CORPSE_WALK_DOUSED: Int get() = TfaIds.Spot.XARTH_CORPSE_WALK_DOUSED // 4384 per step of a doused corpse
    val SPOT_CORPSE_DEATH: Int get() = TfaIds.Spot.XARTH_CORPSE_DEATH       // 4385 ash, 4 t
    val SPOT_CORPSE_ABSORBED: Int get() = TfaIds.Spot.XARTH_CORPSE_ABSORBED // 4386 on the corpse while absorbed
    val SPOT_ABSORB: Int get() = TfaIds.Spot.XARTH_ABSORB                   // 4387 on Xarth when a corpse heals him, 6 t

    // ------------------------------------------------------------------------------------- locs (loc_xarth.txt)
    val LOC_RUBBLE: Int get() = TfaIds.Loc.XARTH_RUBBLE           // 62712 tfa_xarth_rubble_1: pillars A (SE) and B (NE)
    val LOC_RUBBLE_EAST: Int get() = TfaIds.Loc.XARTH_RUBBLE_EAST // 62711 tfa_xarth_rubble_2: pillar C (E)
    val LOC_FLAME: Int get() = TfaIds.Loc.XARTH_FLAME             // 62713 tfa_xarth_arenafire, blockwalk=no
    fun locPile(index: Int): Int = TfaIds.Loc.XARTH_PILES[index]          // 62747..62761 odd, N E S W NW NE SE SW
    fun locPileFire(index: Int): Int = TfaIds.Loc.XARTH_PILES_FIRE[index] // 62748..62762 even, config anims 15008-15015

    /** Seq lengths in ticks (cycles / 30, rounded up) [CACHE seq_xarth.txt]. */
    object Ticks {
        const val MELEE = 2        // 14983, 60 cyc
        const val MAGIC = 2        // 14984, 60 cyc
        const val RANGED = 3       // 14985, 90 cyc
        const val ROOT = 3         // 14991, 90 cyc
        const val AOE = 3          // 14992, 90 cyc
        const val FLAME_BLAST = 5  // 14989, 150 cyc
        const val SUMMON = 8       // 15017, 240 cyc
        const val DEATH = 5        // 14995, 150 cyc
        const val TURN = 3         // 14981 / 14982, 90 cyc
        const val DEFEND = 3       // 15018, 90 cyc
        const val CORPSE_DEATH = 4 // 15003, 120 cyc
        const val CORPSE_ABSORB = 2 // 15005, 60 cyc
        const val FLAME_SPAWN = 5  // 12003, 150 cyc
    }
}

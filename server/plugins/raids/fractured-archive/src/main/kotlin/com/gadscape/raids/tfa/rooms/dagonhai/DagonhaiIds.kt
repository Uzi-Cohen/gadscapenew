package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.ids.IdKind
import com.gadscape.raids.tfa.ids.RemapTable
import com.gadscape.raids.tfa.ids.TfaIds

/**
 * Every id the Dagon'hai room uses, in one place (spec §4.6, research `raids4/dagonhai/NOTES.md` §2 and §8). Raid ids
 * come from [TfaIds] (live 2746 numbers resolved through `remap.tsv`); the few the room needs that `TfaIds` lacks are
 * resolved here the same way, with their evidence, and listed in `raids4-impl/ENC-dagonhai.md` for a later `TfaIds`
 * addition.
 *
 * Skeleton check (`raids4-impl/moia-tools/SeqSkel.java` on cache 2746 and on cacheV, identical; output in
 * `raids4-impl/dagonhai-seqskel-2746.txt`):
 * - every trio seq 14581-14654 (idle, walk, attacks, charge, search, throw, death) is on framemap **2708**, the
 *   framemap of the stand/walk seqs 14581-14592 in the npc configs of 16643-16648;
 * - cauldron 16649: stand 14656 and the attacks 14657 / 14659 / 14661 / 14678 are on framemap **2709**; the explosion
 *   14679 is on framemap **2717**, which is not the cauldron model's rig: framemap 2709 has 122 labels (max 121) = model
 *   62603 (the cauldron, max label 121) and framemap 2717 has 126 labels (max 125) = model **62612** (1653 vertices,
 *   labels 0..125, referenced by no config). So the server swaps the cauldron's model to 62612 before playing 14679
 *   (`raids4-impl/dagonhai-tools/FmLabels.java`, `ModelSkins.java`) [INF, strong];
 * - imp 16650: stand 13841 / walk 13844 / attack 14529 / despawn 14530 all on framemap **1240**;
 * - book loc 62552: 14681 / 14682 on framemap 2686 (loc anims).
 * Lengths: `raids4/dagonhai/seqlen_named.txt` (cycles / 30 = ticks).
 */
internal object DagonhaiIds {

    /** Client cycles per server tick. */
    const val CYCLES_PER_TICK = 30

    // ------------------------------------------------------------------------------------------------ NPCs
    // spec §4.6 identity table; cache 2746 config 9 (NOTES §2)

    val ELDER: Int get() = TfaIds.Npc.DAGONHAI_ELDER                          // 16643 tfa_dagonhai_leader, size 2
    val ELDER_UNSHIELDED: Int get() = TfaIds.Npc.DAGONHAI_ELDER_UNSHIELDED    // 16644, HP 7
    val MONK_STAFF: Int get() = TfaIds.Npc.DAGONHAI_MONK_STAFF                // 16645 tfa_dagonhai_minion_1
    val MONK_STAFF_UNSHIELDED: Int get() = TfaIds.Npc.DAGONHAI_MONK_STAFF_UNSHIELDED // 16646, HP 7
    val MONK_BOOK: Int get() = TfaIds.Npc.DAGONHAI_MONK_BOOK                  // 16647 tfa_dagonhai_minion_2
    val MONK_BOOK_UNSHIELDED: Int get() = TfaIds.Npc.DAGONHAI_MONK_BOOK_UNSHIELDED // 16648, HP 7
    val CAULDRON: Int get() = TfaIds.Npc.DAGONHAI_CAULDRON                    // 16649 "Ritual Stability", size 3, HP 1000
    val IMP: Int get() = TfaIds.Npc.DAGONHAI_IMP                              // 16650 tfa_dagonhai_demon, HP 44
    val RIFT: Int get() = TfaIds.Npc.DAGONHAI_RIFT                            // 16651 tfa_dagonhai_rift (stand 9355)

    fun shieldedNpc(role: Role): Int = when (role) {
        Role.ELDER -> ELDER
        Role.STAFF -> MONK_STAFF
        Role.BOOK -> MONK_BOOK
    }

    fun unshieldedNpc(role: Role): Int = when (role) {
        Role.ELDER -> ELDER_UNSHIELDED
        Role.STAFF -> MONK_STAFF_UNSHIELDED
        Role.BOOK -> MONK_BOOK_UNSHIELDED
    }

    // ------------------------------------------------------------------------------- trio seqs (framemap 2708)
    // Suffix rule [CACHE spotanim resizes, NOTES §2]: _01 Elder (160), _02 staff monk (136), _03 book monk (128);
    // search/throw seqs have only _01 (staff) and _02 (book).

    /** npc_dagonhai_attack_ranged_01, 90 c = 3 t: Elder ranged auto (spec §4.6 attack table). */
    val SEQ_ELDER_RANGED: Int get() = TfaIds.Seq.DAGONHAI_ELDER_RANGED        // 14593
    /** npc_dagonhai_attack_magic_01, 90 c: Elder surge auto. */
    val SEQ_ELDER_MAGIC: Int get() = TfaIds.Seq.DAGONHAI_ELDER_MAGIC          // 14597
    /** npc_dagonhai_attack_magic_guthix_04, 130 c = 4.33 t, synths 12325/12324/12326: empowered Elder (spec). */
    val SEQ_EMPOWERED_ELDER: Int get() = TfaIds.Seq.DAGONHAI_EMPOWERED_ELDER  // 14604
    /** npc_dagonhai_attack_magic_02 / _03, 90 c, synths 12344-12346: empowered monks (spec attack table). */
    val SEQ_EMPOWERED_STAFF: Int get() = TfaIds.Seq.DAGONHAI_EMPOWERED_STAFF  // 14599
    val SEQ_EMPOWERED_BOOK: Int get() = TfaIds.Seq.DAGONHAI_EMPOWERED_BOOK    // 14601

    fun seqEmpowered(role: Role): Int = when (role) {
        Role.ELDER -> SEQ_EMPOWERED_ELDER
        Role.STAFF -> SEQ_EMPOWERED_STAFF
        Role.BOOK -> SEQ_EMPOWERED_BOOK
    }

    /** Channel loop npc_dagonhai_charge_cauldron_idle_0X (90 c, loop step 6/8/6, max loops 6/8/6 = 13.8 t). */
    fun seqChannel(role: Role): Int = TfaIds.Seq.DAGONHAI_CHANNEL_IDLE[role.ordinal]          // 14607 / 14612 / 14617
    /** Channel success ..._end_0X, 30 c = 1 t. */
    fun seqChannelEnd(role: Role): Int = TfaIds.Seq.DAGONHAI_CHANNEL_END[role.ordinal]        // 14609 / 14614 / 14619
    /** Channel interrupted ..._interrupted_0X, 60 c = 2 t, priority 8. */
    fun seqChannelInterrupted(role: Role): Int = TfaIds.Seq.DAGONHAI_CHANNEL_INTERRUPTED[role.ordinal] // 14611 / 14616 / 14621

    /** npc_dagonhai_search_books_idle_01 / _02 (90 c + 12 loops of the last frames; preanim_move=delayanim). */
    fun seqSearch(role: Role): Int = if (role == Role.BOOK) TfaIds.Seq.DAGONHAI_BOOK_SEARCH else TfaIds.Seq.DAGONHAI_STAFF_SEARCH // 14630 / 14622
    /** npc_dagonhai_search_books_end_01 / _02, 30 c. */
    fun seqSearchEnd(role: Role): Int = if (role == Role.BOOK) TfaIds.Seq.DAGONHAI_BOOK_SEARCH_END else TfaIds.Seq.DAGONHAI_STAFF_SEARCH_END // 14631 / 14623

    /** npc_dagonhai_throw_book_{zamorak,bandos,saradomin}_01 / _02: 150 c = 5 t, release sound at frame 29 = cycle 112. */
    fun seqThrow(role: Role, god: God): Int = if (role == Role.BOOK) when (god) {
        God.ZAMORAK -> TfaIds.Seq.DAGONHAI_BOOK_THROW_ZAMORAK      // 14632
        God.SARADOMIN -> TfaIds.Seq.DAGONHAI_BOOK_THROW_SARADOMIN  // 14634
        God.BANDOS -> TfaIds.Seq.DAGONHAI_BOOK_THROW_BANDOS        // 14633
    } else when (god) {
        God.ZAMORAK -> TfaIds.Seq.DAGONHAI_STAFF_THROW_ZAMORAK     // 14624
        God.SARADOMIN -> TfaIds.Seq.DAGONHAI_STAFF_THROW_SARADOMIN // 14626
        God.BANDOS -> TfaIds.Seq.DAGONHAI_STAFF_THROW_BANDOS       // 14625
    }

    /** npc_dagonhai_death_01 / _02 / _03, 125 c = 4.17 t (spec [C+] "Final deaths"; WU-25 "the last two kills"). */
    fun seqDeath(role: Role): Int = when (role) {
        Role.ELDER -> TfaIds.Seq.DAGONHAI_ELDER_DEATH              // 14652
        Role.STAFF -> TfaIds.Seq.DAGONHAI_STAFF_DEATH              // 14653
        Role.BOOK -> TfaIds.Seq.DAGONHAI_BOOK_EXPLODE_DEATH        // 14654 (+ spot 4163 in the explosion)
    }

    // --------------------------------------------------------------------------- cauldron seqs (framemap 2709)

    /** npc_dagonhai_cauldron_attack_01_zamorak / 02_saradomin / 03_bandos, 60 c: un-intercepted tome (spec). */
    fun seqCauldron(god: God): Int = when (god) {
        God.ZAMORAK -> TfaIds.Seq.DAGONHAI_CAULDRON_ZAMORAK        // 14657
        God.SARADOMIN -> TfaIds.Seq.DAGONHAI_CAULDRON_SARADOMIN    // 14659
        God.BANDOS -> TfaIds.Seq.DAGONHAI_CAULDRON_BANDOS          // 14661
    }

    /** npc_dagonhai_cauldron_attack_04_guthix_01, 60 c: the ritual completes (spec EMPOWERED). */
    val SEQ_CAULDRON_EMPOWERED: Int get() = TfaIds.Seq.DAGONHAI_CAULDRON_EMPOWERED // 14678

    /**
     * npc_dagonhai_cauldron_attack_04_guthix_02, 60 c, synth 12311: the explosion (spec EXPLOSION). Framemap 2717, the
     * rig of model [MODEL_CAULDRON_BROKEN]; played only after the model swap (see the class comment).
     */
    val SEQ_CAULDRON_EXPLODE: Int get() = TfaIds.Seq.DAGONHAI_CAULDRON_EXPLODE // 14679

    /** loc_dagonhai_spellbook_guthix_open, 120 c, synth 12315: the Book of Life and Death opens (spec EMPOWERED). */
    val SEQ_BOOK_OPEN: Int get() = TfaIds.Seq.DAGONHAI_BOOK_LOC_OPEN          // 14682 (loc anim)

    // ------------------------------------------------------------------------------- imp seqs (framemap 1240)

    /** leagues_6_thrall_imp_melee_attack_silent, 60 c, new in 2746, prefetched by script 10011. */
    val SEQ_IMP_ATTACK: Int get() = TfaIds.Seq.DAGONHAI_IMP_ATTACK            // 14529
    /** leagues_6_thrall_imp_melee_despawn_silent, 60 c (the silent twin of 13856), the imp's death [INF, NOTES §7.5]. */
    val SEQ_IMP_DEATH: Int get() = TfaIds.Seq.DAGONHAI_IMP_DEATH              // 14530

    // ------------------------------------------------------------------------------------------- spotanims

    // Elder auto (spec §4.6 attack table; NOTES §7.1)
    val SPOT_RANGED_CAST: Int get() = TfaIds.Spot.DAGONHAI_ELDER_RANGED_CAST     // 4070 (anim 14594, 3 t)
    val SPOT_RANGED_PROJ: Int get() = TfaIds.Spot.DAGONHAI_ELDER_RANGED_PROJ     // 4071 (grey projectile)
    val SPOT_RANGED_IMPACT: Int get() = TfaIds.Spot.DAGONHAI_ELDER_RANGED_IMPACT // 4072 (1 t)

    /** Elder surge cast spot by element (4103-4106 are fire, water, air, earth). */
    fun spotSurgeCast(element: Element): Int = TfaIds.Spot.DAGONHAI_SURGE_CAST[elementIndex(element)]
    /** Stock surge projectile by element (1465 fire, 1459 water, 1456 wind, 1462 earth) [INF, NOTES §7.1]. */
    fun spotSurgeProj(element: Element): Int = TfaIds.Spot.SURGE_PROJ[elementIndex(element)]
    /** Stock surge impact by element (1466, 1460, 1457, 1463). */
    fun spotSurgeImpact(element: Element): Int = TfaIds.Spot.SURGE_IMPACT[elementIndex(element)]

    // Empowered (spec attack table): Elder 4077 guthix energy + 2 x 4078; monks 4074 / 4075
    val SPOT_EMPOWERED_ELDER: Int get() = TfaIds.Spot.DAGONHAI_EMPOWERED_ELDER         // 4077 (anim 14606, 4.33 t)
    val SPOT_EMPOWERED_ELDER_PROJ: Int get() = TfaIds.Spot.DAGONHAI_EMPOWERED_ELDER_PROJ // 4078 (Surok's surge anim)

    fun spotEmpoweredCast(role: Role): Int = when (role) {
        Role.ELDER -> SPOT_EMPOWERED_ELDER
        Role.STAFF -> TfaIds.Spot.DAGONHAI_EMPOWERED_STAFF  // 4074 vfx_dagonhai_attack_magic_02 (water recolour)
        Role.BOOK -> TfaIds.Spot.DAGONHAI_EMPOWERED_BOOK    // 4075 vfx_dagonhai_attack_magic_03 (water recolour)
    }

    /**
     * Channel vfx per caster and element, idle loop or end (4079-4102: Elder / staff / book x fire, water, air, earth
     * x idle, end; spec "Element vfx per NPC").
     */
    fun spotChannel(role: Role, element: Element, end: Boolean): Int {
        val list = when (role) {
            Role.ELDER -> TfaIds.Spot.DAGONHAI_CHANNEL_ELDER
            Role.STAFF -> TfaIds.Spot.DAGONHAI_CHANNEL_STAFF
            Role.BOOK -> TfaIds.Spot.DAGONHAI_CHANNEL_BOOK
        }
        return list[elementIndex(element) * 2 + if (end) 1 else 0]
    }

    // Tomes (spec [C+] "Missing ids"): hand spots on the monk, projectile, impact, lane gusts
    fun spotHand(role: Role, god: God): Int =
        (if (role == Role.BOOK) TfaIds.Spot.DAGONHAI_HAND_BOOK else TfaIds.Spot.DAGONHAI_HAND_STAFF)[god.ordinal] // 4107-4112

    fun spotTomeProj(god: God): Int = TfaIds.Spot.DAGONHAI_TOME_PROJ[god.ordinal]       // 4113 / 4114 / 4115
    fun spotTomeImpact(god: God): Int = TfaIds.Spot.DAGONHAI_TOME_IMPACT[god.ordinal]   // 4116 / 4117 / 4118

    /** Floor gust for [god] on the lane of [direction] (N, NE, E, SE, S, SW, W, NW = the `Compass` order). */
    fun spotGust(god: God, direction: com.gadscape.raids.tfa.raid.Compass): Int = when (god) {
        God.ZAMORAK -> TfaIds.Spot.DAGONHAI_GUSTS_ZAMORAK         // 4119-4126
        God.SARADOMIN -> TfaIds.Spot.DAGONHAI_GUSTS_SARADOMIN     // 4127-4134
        God.BANDOS -> TfaIds.Spot.DAGONHAI_GUSTS_BANDOS           // 4135-4142
    }[direction.ordinal]

    /** Cauldron spot of an un-intercepted tome: 4143 Zamorak, 4150 Saradomin, 4155 Bandos (spec tome effects). */
    fun spotCauldron(god: God): Int = when (god) {
        God.ZAMORAK -> TfaIds.Spot.DAGONHAI_CAULDRON_ZAMORAK      // 4143
        God.SARADOMIN -> TfaIds.Spot.DAGONHAI_CAULDRON_SARADOMIN  // 4150
        God.BANDOS -> TfaIds.Spot.DAGONHAI_CAULDRON_BANDOS        // 4155
    }

    // Zamorak (spec tome effects; fuse anims 14664-14667 are 3 / 4 / 5 / 6 t, blast 14668 2 t)
    val SPOT_ZAMORAK_FIREBALL_PROJ: Int get() = TfaIds.Spot.DAGONHAI_ZAMORAK_FIREBALL_PROJ // 4144 (anim 14663, 3 t)
    fun spotZamorakFuse(index: Int): Int = TfaIds.Spot.DAGONHAI_ZAMORAK_FUSE[index]          // 4145-4148
    val SPOT_ZAMORAK_BLAST: Int get() = TfaIds.Spot.DAGONHAI_ZAMORAK_BLAST                   // 4149

    // Saradomin (spec tome effects; telegraph anims 7818 = 5 t, 7819 = 6 t; lightning 14726 / 14728 = 1 t)
    val SPOT_SARA_DAMAGE_TELEGRAPH: Int get() = TfaIds.Spot.DAGONHAI_SARA_DAMAGE_TELEGRAPH   // 4063 (5 t)
    val SPOT_SARA_HEAL_TELEGRAPH_5: Int get() = TfaIds.Spot.DAGONHAI_SARA_HEAL_TELEGRAPH_150 // 4064 (5 t)
    val SPOT_SARA_HEAL_TELEGRAPH_6: Int get() = TfaIds.Spot.DAGONHAI_SARA_HEAL_TELEGRAPH_180 // 4065 (6 t)
    val SPOT_SARA_HEAL_IMPACT: Int get() = TfaIds.Spot.DAGONHAI_SARA_HEAL_IMPACT             // 4066 (anim 9029)
    val SPOT_SARA_DAMAGE_LIGHTNING: Int get() = TfaIds.Spot.DAGONHAI_SARA_DAMAGE_LIGHTNING   // 4068
    val SPOT_SARA_HEAL_LIGHTNING: Int get() = TfaIds.Spot.DAGONHAI_SARA_HEAL_LIGHTNING       // 4069
    val SPOT_STORMCLOUD_HEAL: Int get() = TfaIds.Spot.DAGONHAI_STORMCLOUD_HEAL               // 4151 (anim 14669, 4 t loop)
    val SPOT_STORMCLOUD_HEAL_DESPAWN: Int get() = TfaIds.Spot.DAGONHAI_STORMCLOUD_HEAL_DESPAWN // 4152
    val SPOT_STORMCLOUD_DAMAGE: Int get() = TfaIds.Spot.DAGONHAI_STORMCLOUD_DAMAGE           // 4153
    val SPOT_STORMCLOUD_DAMAGE_DESPAWN: Int get() = TfaIds.Spot.DAGONHAI_STORMCLOUD_DAMAGE_DESPAWN // 4154

    /** Bandos shockwave rings 1-2, 3-4, 5-6, 7-8 (4156-4159, anims 14673-14676, 1 t each). */
    fun spotBandosRing(index: Int): Int = TfaIds.Spot.DAGONHAI_BANDOS_RINGS[index]

    // Breaks, ritual, explosion (spec state machine)
    val SPOT_CAULDRON_SIZZLE: Int get() = TfaIds.Spot.DAGONHAI_CAULDRON_SIZZLE   // 4164 (anim 5401, 1.1 t): stability loss
    val SPOT_CAULDRON_DOME: Int get() = TfaIds.Spot.DAGONHAI_CAULDRON_DOME       // 4165 (anim 9601, 6 t): Zamorak protection
    val SPOT_CAULDRON_UNLOCK: Int get() = TfaIds.Spot.DAGONHAI_CAULDRON_UNLOCK   // 4166: ritual complete beam
    val SPOT_CAULDRON_EXPLODE: Int get() = TfaIds.Spot.DAGONHAI_CAULDRON_EXPLODE // 4160 (anim 14680, 2 t)
    val SPOT_CAULDRON_DUST: Int get() = TfaIds.Spot.DAGONHAI_CAULDRON_DUST       // 4161 (anim 14680, 2 t)
    val SPOT_BOOK_DEATH: Int get() = TfaIds.Spot.DAGONHAI_BOOK_DEATH             // 4163 vfx_dagonhai_death_03 (anim 14655, 4 t)

    // Demonic strength (spec): 4162 on the imp's killer (anim 14677, 4.07 t), then the 4067 glow (anim 14580, 5.4 t)
    val SPOT_DEMON_BUFF: Int get() = TfaIds.Spot.DAGONHAI_DEMON_BUFF             // 4162
    val SPOT_DEMONIC_GLOW: Int get() = TfaIds.Spot.DAGONHAI_DEMONIC_GLOW         // 4067

    /**
     * Stock spot 3736 `leagues_6_thrall_imp_melee_despawn_vfx_spotanim`, the vfx of the despawn seq the imp dies with
     * (NOTES §7.5 [INF]). Not in `TfaIds`.
     */
    val SPOT_IMP_DESPAWN: Int by lazy { RemapTable.active.resolve(IdKind.SPOTANIM, 3736) }

    // ---------------------------------------------------------------------------------------------- models

    /** Cauldron model (npc 16649 model1), rig of framemap 2709. Not in `TfaIds`. */
    val MODEL_CAULDRON: Int by lazy { RemapTable.active.resolve(IdKind.MODEL, 62603) }

    /** The exploding cauldron (framemap 2717, labels 0..125; no config uses it): swapped in for seq 14679 [INF]. */
    val MODEL_CAULDRON_BROKEN: Int by lazy { RemapTable.active.resolve(IdKind.MODEL, 62612) }

    // ------------------------------------------------------------------------------------------------ misc

    /** Headbar 85 `standard_charge_70` (yellow, 70 segments, sticktime 1): channel charge (spec). */
    val HEADBAR_CHARGE: Int get() = TfaIds.Headbar.CHARGE_70
    const val HEADBAR_CHARGE_SEGMENTS = 70

    /** Book of Life and Death loc 62552 at (4049,5597), type 10 (r16215_locs). */
    val LOC_BOOK: Int get() = TfaIds.Loc.DAGONHAI_BOOK

    /** Seq prefetch 10011 (spec §3.7). */
    val SCRIPT_PREFETCH: Int get() = TfaIds.Script.PREFETCH_DAGONHAI
    /** Music 889 tfa_dagonhai_combat "Triple Threat" (spec §3.7). */
    val MIDI: Int get() = TfaIds.Midi.DAGONHAI

    /** Index of [element] in the cache's fire, water, air, earth spotanim order. */
    fun elementIndex(element: Element): Int = when (element) {
        Element.FIRE -> 0
        Element.WATER -> 1
        Element.AIR -> 2
        Element.EARTH -> 3
    }

    /** Segments of the 2746 headbars the room may use for the pool [CACHE dump.headbar]. */
    fun headbarSegments(headbar: Int): Int = when (headbar) {
        TfaIds.Headbar.CHARGE_70 -> 70
        TfaIds.Headbar.SHIELD_30 -> 30
        else -> 60 // 86, 87, 88
    }

    /** Headbars with sticktime 0 (87, 88, 89) vanish when their interpolation ends: they need a hold duration. */
    fun headbarNeedsHold(headbar: Int): Boolean =
        headbar == TfaIds.Headbar.SHIELD_30 || headbar == TfaIds.Headbar.BLOOD_45 || headbar == TfaIds.Headbar.HEAT_60
}

@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.gadscape.raids.tfa.ids

/**
 * Every cache id named in the implementation spec (`raids4/SPEC.md`), as named constants.
 *
 * Rules (spec §0.2):
 * - Server code never writes a raw raid id. It uses `TfaIds.<Group>.<NAME>`.
 * - The numbers below are **live cache 2746 ids**. Each one is resolved through `data/raids/tfa/remap.tsv`
 *   ([RemapTable]) the first time its group is used, so ids the asset port moves (seqs 15000-15162,
 *   clientscripts 10034-10121, structs 1216-1774, objs 34721-34726) follow the port without code changes.
 * - Values are fixed for the life of the server once resolved (a remap change needs a restart). They are `val`s,
 *   not `const val`s, so they cannot be used in annotations; `when (id) { TfaIds.Npc.MOIA -> ... }` works.
 * - Stock (pre-raid) ids that the raid uses are listed too and marked "stock"; the remap leaves them alone unless
 *   `remap.tsv` names them.
 * - Families are [IdList]s in the order the spec gives (e.g. `Loc.XARTH_PILES` is N, E, S, W, NW, NE, SE, SW).
 *
 * Names follow the role in the spec; the cache symbol name is in the comment where it helps. Add new ids here
 * only when the spec (or a cache lookup) gives them. An id may appear under two names when it has two roles
 * (e.g. inside a family list and as a single name, or stock seq 808 as Navigator stand and doused-corpse idle).
 */
object TfaIds {

    /** Every group, in a fixed order. Touching a group resolves its ids. */
    @JvmStatic
    val groups: List<IdGroup>
        get() = listOf(
            Region, Npc, Loc, Obj, StockObj, Seq, Spot, Interface, Inv, Script, Varp, Varbit, Varc,
            Dbrow, Dbtable, Enums, Struct, Param, Headbar, Hitsplat, Sprite, Midi, Jingle, Synth, Ambience, Model,
        )

    /** Resolves every group and returns every id. */
    @JvmStatic
    fun all(): List<TfaId> {
        groups // initialise every group
        return TfaIdRegistry.snapshot()
    }

    /** True once any group has resolved its ids (after that, [RemapTable.install] is refused). */
    @JvmStatic
    fun anyGroupInitialised(): Boolean = !TfaIdRegistry.isEmpty

    /** Packs an interface id and a component index the way pause buttons and IF events carry them. */
    @JvmStatic
    fun packed(interfaceId: Int, component: Int): Int = (interfaceId shl 16) or component

    // ------------------------------------------------------------------------------------------------------------
    /** Static map squares (§2.4). Region id = `(x >> 6) << 8 | (y >> 6)`. */
    object Region : IdGroup(IdKind.REGION) {
        val TEMPLE by id(16199)        // temple and raid entrance, static
        val LOBBY by id(16470)         // static, not instanced
        val NEXUS by id(16471)         // nexus / atrium, Archive Guardian
        val EZEKIEL by id(16214)
        val BORK by id(16727)
        val MOIA by id(16216)
        val DREADNOTH by id(16728)
        val DAGONHAI by id(16215)
        val XARTH by id(16726)
        val OCELLUS by id(16472)       // corridor and arena, locs on cache plane 1
        val UNUSED_16473 by id(16473)  // terrain only
        val VAULT by id(16474)         // walkable floor on cache plane 2
    }

    // ------------------------------------------------------------------------------------------------------------
    object Npc : IdGroup(IdKind.NPC) {
        // Pets and collection (§3.17)
        val ORION by id(16631)                     // follower, stand 14882 / walk 14883
        val ORION_ROBED by id(16632)
        /** Boss-form pets, behind a flag: xarth, guardian, jelly, dagonhai, ezekiel, moia, bork. */
        val PET_METAMORPHS by ids(16633..16639)
        val ORION_POH by id(16763)
        val ORION_ROBED_POH by id(16764)
        /** POH boss-form pets: xarth, guardian, jelly, dagonhai, ezekiel, moia, bork. */
        val POH_PET_METAMORPHS by ids(16765..16771)

        // Bork and the Smorks (§4.3)
        val BORK by id(16640)
        val SMORKS by id(16641)
        val BORK_SMORKS_HUD by id(16642)           // invisible HP HUD handler, "Bork and the Smorks"

        // Dagon'hai trio (§4.6)
        val DAGONHAI_ELDER by id(16643)
        val DAGONHAI_ELDER_UNSHIELDED by id(16644)
        val DAGONHAI_MONK_STAFF by id(16645)
        val DAGONHAI_MONK_STAFF_UNSHIELDED by id(16646)
        val DAGONHAI_MONK_BOOK by id(16647)
        val DAGONHAI_MONK_BOOK_UNSHIELDED by id(16648)
        val DAGONHAI_CAULDRON by id(16649)         // "Ritual Stability", HP HUD NPC
        val DAGONHAI_IMP by id(16650)
        val DAGONHAI_RIFT by id(16651)

        // Ezekiel (§4.2)
        val EZEKIEL by id(16652)
        val EZEKIEL_ANIMATED_SWORD by id(16653)
        val EZEKIEL_SWORD_FENCE by id(16654)
        val EZEKIEL_FINALE_SWORD by id(16655)
        val EZEKIEL_DEATH_SWORD by id(16656)

        // Archive Guardian (§4.1)
        val GUARDIAN_INERT by id(16657)            // op1 Touch
        val GUARDIAN_DEAD by id(16658)
        val GUARDIAN_FLYING by id(16659)           // shielded; HUD bar = shield
        val GUARDIAN_GROUNDED by id(16660)         // unshielded; HUD bar = main HP
        val GUARDIAN_BATTERY_INACTIVE by id(16661)
        val GUARDIAN_BATTERY_ACTIVE by id(16662)
        val GUARDIAN_BATTERY_DESTROYED by id(16663)
        val GUARDIAN_VINE by id(16664)             // tendril

        // Dreadnoth (§4.5)
        val DREADNOTH by id(16665)
        val DREADNOTH_JELLY by id(16666)

        // Moia (§4.4)
        val MOIA by id(16667)
        val INVISIBLE_1X1 by id(16668)             // tfa_invisible_npc: anchor for tile spots / sounds
        val INVISIBLE_2X2 by id(16669)
        val MOIA_BINDER by id(16670)

        // Ocellus and The Blade (§4.8)
        val OCELLUS_INTRO by id(16671)             // multi on varbit 16384
        val OCELLUS_INTRO_NOT_MET by id(16672)     // "Hooded Figure"
        val OCELLUS_INTRO_MET by id(16673)
        val OCELLUS_P1 by id(16674)
        val OCELLUS_P2 by id(16675)                // not clickable; HUD NPC in P2
        val OCELLUS_BLADE_HITBOX by id(16676)      // The Blade (P2)
        val OCELLUS_P3 by id(16677)                // P3 and P4
        val OCELLUS_VOID_RIFT by id(16678)
        val OCELLUS_VOID_SIPHON by id(16679)
        val OCELLUS_VOID_AUGMENTER by id(16680)
        val OCELLUS_VOID_VINE by id(16681)
        val OCELLUS_DEBUG by id(16682)             // never spawn
        val THE_BLADE by id(16683)                 // after the kill: Commune / Travel

        // Xarth (§4.7)
        val XARTH by id(16684)
        val XARTH_PILLAR by id(16685)
        val XARTH_PILLAR_DESTROYED by id(16686)
        val XARTH_CORPSE by id(16687)
        val XARTH_CORPSE_DOUSED by id(16688)

        val UNUSED_HELPER_16689 by id(16689)       // tfa_butterfly01: no use found (§5.9)
        val VAULT_RIFT by id(16690)                // Leave / Quick-leave

        // Lobby (§3.2)
        val SPRITE by id(16691)
        val RECORD_KEEPER by id(16692)
        val WIZARD_APPRENTICE by id(16693)
        val LOBBY_DRUID_1 by id(16694)
        val LOBBY_DRUID_2 by id(16695)
        val ARMACUS_TALK by id(16696)              // Talk-to variant (camp, intro dialogue)
        val EVA_TALK by id(16697)
        val IVY by id(16698)                       // Talk-to; NEXUS_FINAL brief
        val MESSENGER by id(16699)                 // "Guardian of Armadyl", hands over the Alliance's message
        val NAVIGATOR by id(16700)
        val ENDING_ARMACUS by id(16701)            // miniquest value 6
        val ENDING_IVY by id(16702)
        val ENDING_EVA by id(16703)
        val BANKER by id(16704)
        val GENERAL_MONTOYA by id(16705)
        /** Builders, knights, druids and Guardians of Armadyl (decoration). */
        val LOBBY_DECORATION by ids(16706..16723)

        // Nexus camp allies (§3.5)
        val ARMACUS_CAMP by id(16724)              // no ops
        val EVA_CAMP by id(16725)                  // no ops

        // First-entry intro scene (§3.1)
        val IVY_CUTSCENE_NOOP by id(16726)
        val ARMACUS_CUTSCENE by id(16727)
        val EVA_CUTSCENE by id(16728)
        val IVY_CUTSCENE by id(16729)

        // Vault (§3.15)
        val UNIQUE_CHEST_CLOSED by id(16730)
        val UNIQUE_CHEST_OPEN by id(16731)
        val VAULT_CHEST_NOT_MINE by id(16732)
        val VAULT_CHEST_MINE by id(16733)
        val VAULT_RIFT_NOT_MINE by id(16734)
        val VAULT_RIFT_MINE by id(16735)
        /** Closed personal chest multinpc of slot k (k = 0..7): `16736 + 2k`. */
        val VAULT_CHEST_CLOSED by ids(16736, 16738, 16740, 16742, 16744, 16746, 16748, 16750)
        /** Open personal chest multinpc of slot k (k = 0..7): `16737 + 2k`. */
        val VAULT_CHEST_OPEN by ids(16737, 16739, 16741, 16743, 16745, 16747, 16749, 16751)

        /** Horn of Bran Camelot cutscene: Lancelot, Gawain, Kay, Bedivere, Tristram, Pelleas, Lucan, Palomedes, Arthur. */
        val CAMELOT_CUTSCENE by ids(16752..16760)

        // Quartermaster (§3.12)
        val QUARTERMASTER by id(16761)
        val QUARTERMASTER_INACTIVE by id(16762)
    }

    // ------------------------------------------------------------------------------------------------------------
    object Loc : IdGroup(IdKind.LOC) {
        // Entrance (§3.1)
        val ENTRANCE_MULTI by id(62542)            // multiloc on varbit 16354
        val ENTRANCE_OPEN by id(62543)             // "Opening", Crawl-through
        val ENTRANCE_BLOCKED by id(53638)          // stock "Blocked cavity"

        // Lobby (§3.2)
        val GROUPING_BOARD by id(62767)
        val ARCHIVE_DOOR by id(62768)
        val ALLIANCE_RECORDS by id(62773)
        val LOBBY_EXIT by id(63009)
        val STRATEGY_BOARD by id(63083)
        val LOBBY_BANK_CHEST by id(63275)
        val ASCENSION_ROCKS_ACTIVE by ids(63266..63268)
        val ASCENSION_ROCKS_INACTIVE by ids(63269..63271)
        val LOOT_WHEELBARROW by id(63299)          // multiloc on varbit 16416
        val LOOT_WHEELBARROW_EMPTY by id(63300)
        val LOOT_WHEELBARROW_FULL by id(63301)     // Search

        // Nexus (§3.5, §3.6, §3.17)
        val PASSAGEWAY by id(62765)                // room Exit, 5x2
        val EXIT_LARGE by id(62766)                // tfa_exit_large, covered by the Ocellus door
        val MERIT_SCROLL_CAMP by id(62774)         // in-raid scoreboard, not placed in any map
        val MERIT_SCROLL_VAULT by id(62775)
        val ARCHIVE_TABLE by id(62776)             // "Fractured archive table" (Repair), unspawned
        val SUPPLY_WHEELBARROW by id(62787)        // decor
        val SUPPLY_CHEST by id(62788)              // decor
        val SECTION1_WEST_DOOR by id(63088)
        val SECTION1_WEST_DOOR_SLIGHTLY by id(63089)
        val SECTION1_WEST_DOOR_OPENED by id(63090)
        val SECTION1_EAST_DOOR by id(63091)
        val SECTION1_EAST_DOOR_SLIGHTLY by id(63092)
        val SECTION1_EAST_DOOR_OPENED by id(63093)
        val SECTION_DOOR_CLOSED by id(63094)       // no ops
        val SECTION_DOOR_CLOSING by id(63095)
        val SECTION2_WEST_DOOR by id(63096)
        val SECTION2_WEST_DOOR_SLIGHTLY by id(63097)
        val SECTION2_WEST_DOOR_OPENED by id(63098)
        val SECTION2_EAST_DOOR by id(63099)
        val SECTION2_EAST_DOOR_SLIGHTLY by id(63100)
        val SECTION2_EAST_DOOR_OPENED by id(63101)
        val SECTION3_WEST_DOOR by id(63102)
        val SECTION3_WEST_DOOR_SLIGHTLY by id(63103)
        val SECTION3_WEST_DOOR_OPENED by id(63104)
        val SECTION3_EAST_DOOR by id(63105)
        val SECTION3_EAST_DOOR_SLIGHTLY by id(63106)
        val SECTION3_EAST_DOOR_OPENED by id(63107)
        val OCELLUS_DOOR_OPENING by id(63109)
        val OCELLUS_DOOR_CLOSED by id(63110)
        val OCELLUS_DOOR_OPEN by id(63111)         // "Archive Door", Enter / Quick-enter
        val RECORDS_DOOR by id(63279)              // multiloc on varbit 16383
        val RECORDS_DOOR_LOCKED by id(63280)       // "Strange door"
        val RECORDS_DOOR_UNLOCKED by id(63281)     // "Records room", Enter
        val RECORDS_EXIT by id(63282)
        /** Living record claim stands, tiers 1..5, hidden variant. */
        val CAPE_STANDS_HIDDEN by ids(63287, 63289, 63291, 63293, 63295)
        /** Living record claim stands, tiers 1..5, visible variant (Claim / Inspect). */
        val CAPE_STANDS_VISIBLE by ids(63288, 63290, 63292, 63294, 63296)

        // Transporters (§3.5)
        val TRANSPORTER by id(63087)               // Ezekiel, Bork, Dagon'hai, Xarth
        val TRANSPORTER_MOIA by id(62610)
        val TRANSPORTER_DREADNOTH by id(63134)

        // Archive Guardian (§4.1)
        val GUARDIAN_MUSHROOM_SPAWN by id(62578)
        val GUARDIAN_MUSHROOM_ACTIVE by id(62579)
        val GUARDIAN_MUSHROOM_BURST by id(62580)
        val GUARDIAN_BATTERY_BASE by id(62581)     // 2x4
        val GUARDIAN_FENCE_03 by id(62585)
        val GUARDIAN_FENCE_03_2X2 by id(62586)
        val GUARDIAN_PEDESTAL by id(62595)
        val GUARDIAN_RAISE_PLAYER by id(63306)     // invisible_raise_player_20

        // Bork and the Smorks (§4.3)
        val BORK_OIL by id(62544)
        val BORK_CART_BLOCK by id(26209)           // stock; blocks walking, not projectiles
        val INVISIBLE_WALL by id(85)               // stock ring around the Bork arena

        // Dreadnoth (§4.5)
        val DREADNOTH_BLOOD by id(62609)           // same art as Maiden blood 32984
        val MAIDEN_BLOOD by id(32984)              // stock

        // Dagon'hai (§4.6)
        val DAGONHAI_BOOK by id(62552)             // Book of Life and Death
        val DAGONHAI_DESK_N by id(62563)
        val DAGONHAI_DESK_NE by id(62564)
        val DAGONHAI_DESK_E by id(62561)
        val DAGONHAI_DESK_SE by id(62560)
        val DAGONHAI_DESK_S by id(62559)
        val DAGONHAI_DESK_SW by id(62558)
        val DAGONHAI_DESK_NW by id(62562)

        // Xarth (§4.7)
        val XARTH_RUBBLE by id(62712)              // pillars A (SE) and B (NE)
        val XARTH_RUBBLE_EAST by id(62711)         // pillar C (E)
        val XARTH_FLAME by id(62713)
        /** Corpse piles N, E, S, W, NW, NE, SE, SW. */
        val XARTH_PILES by ids(62747, 62749, 62751, 62753, 62755, 62757, 62759, 62761)
        /** Fire variants of [XARTH_PILES] (pile id + 1), same order. */
        val XARTH_PILES_FIRE by ids(62748, 62750, 62752, 62754, 62756, 62758, 62760, 62762)

        // Ocellus (§3.6, §4.8)
        val OCELLUS_WALL_ORANGE by id(62684)       // quadrant wall "inactive"
        val OCELLUS_WALL_PURPLE by id(62685)       // quadrant wall "active"
        val OCELLUS_STONE_MIDDLE by id(62686)      // "Gap", Jump
        val OCELLUS_STONE_NORTH by id(62687)
        val OCELLUS_STONE_EAST by id(62688)
        val OCELLUS_STONE_SOUTH by id(62689)
        val OCELLUS_STONE_WEST by id(62690)
        val OCELLUS_VOID_FLOOR by id(62692)
        val CENSER_BANDOSIAN by id(62693)
        val CENSER_BANDOSIAN_INACTIVE by id(62694)
        val CENSER_ARMADYLEAN by id(62695)
        val CENSER_ARMADYLEAN_INACTIVE by id(62696)
        val CENSER_ZAROSIAN by id(62697)
        val CENSER_ZAROSIAN_INACTIVE by id(62698)
        val CENSER_SARADOMIN by id(62699)
        val CENSER_SARADOMIN_INACTIVE by id(62700)
        val OCELLUS_VOID_POOL by id(62701)
        val OCELLUS_DEBRIS by id(62702)
        val OCELLUS_CRACKS by ids(62703, 62704, 62705)
        val OCELLUS_HANGING_BLADE by id(62706)
        /** Arm crack locs N.. (straight_collapse_01..04). */
        val OCELLUS_ARM_CRACK by ids(62929..62932)
        /** Arm void locs after the crack (block walking, not projectiles). */
        val OCELLUS_ARM_VOID by ids(62933..62936)
        val OCELLUS_SLICES_SW by ids(62937..62939)
        val OCELLUS_SLICES_NW by ids(62940..62942)
        val OCELLUS_SLICES_NE by ids(62943..62945)
        val OCELLUS_SLICES_SE by ids(62946..62948)
        val OCELLUS_VINE_GATE by id(62949)
        val OCELLUS_VINE_ENTRANCE_OPEN by id(62951)
        val OCELLUS_VINE_ENTRANCE_CLOSED by id(62952)
        val OCELLUS_VINE_ENTRANCE_CLOSING by id(62953)
        /** Untether border segments (default anim 14970). */
        val OCELLUS_BARRIER by ids(63000..63002)
        val OCELLUS_BARRIER_SPAWN by ids(63003..63005)
        val OCELLUS_BARRIER_DESPAWN by ids(63006..63008)

        // Vault (§3.15)
        val VAULT_BRAZIER_1 by id(62972)
        val VAULT_BRAZIER_2 by id(62973)
        val VAULT_EXIT by id(63297)

        // Horn of Bran (§3.16)
        val ROUND_TABLE by id(26074)               // stock multiloc
    }

    // ------------------------------------------------------------------------------------------------------------
    object Obj : IdGroup(IdKind.OBJ) {
        val ORION by id(34646)
        val ORION_ROBED by id(34648)
        /** Placeholder boss-form pet items: xarth, guardian, jelly, dagonhai, ezekiel, moia, bork. */
        val PET_FORMS by ids(34649..34655)
        val ALLIANCES_MESSAGE by id(34657)
        val ARCHIVE_KEY by id(34658)
        /** Living record capes, tiers 1..5. */
        val CAPES by ids(34659, 34661, 34663, 34665, 34667)
        val ASCENSION_SHARDS by id(34669)
        val FRAGMENT_EARTH by id(34671)            // Fragment of living earth
        val FRAGMENT_WATER by id(34673)            // Fragment of deep water
        val FRAGMENT_AIR by id(34675)              // Fragment of whispering wind
        val FRAGMENT_FIRE by id(34677)             // Fragment of burning fire
        val HORN_OF_BRAN by id(34679)
        val ASCENSION_CROSSBOWS by id(34681)
        val OBLIGATOR_UNCHARGED by id(34717)
        val RONDACHE by id(34719)
        val PLACEHOLDER_RONDACHE by id(34721)      // clashes with a GadScape item until the port moves it
        val FOLIO_INERT by id(34722)               // Archivist's folio (inert)
        val FOLIO by id(34723)
        val PLACEHOLDER_ZORYAS_TOME by id(34726)
        val ZORYAS_TOME_UNCHARGED by id(34727)
        val ALLIANCE_LOG by id(34729)              // view-only shop row, not obtainable
        val LEFTOVERS by id(34730)                 // failure shop

        /** Collection log order (enum 6017). */
        val COLLECTION_LOG by ids(
            34646, 34717, 34681, 34727, 34719, 34679,
            34675, 34677, 34673, 34671,
            34669, 34722,
            34659, 34661, 34663, 34665, 34667,
        )
    }

    /** Stock items the raid names: loot table (§3.15), poor-performance roll, tertiaries, teleports. */
    object StockObj : IdGroup(IdKind.OBJ) {
        val CABBAGE by id(1965)
        val ELITE_CLUE by id(12073)
        val GUTHIXIAN_TEMPLE_TELEPORT by id(29684)
        val GRIMY_NOTE by id(29558)
        // Runes
        val NATURE_RUNE by id(561)
        val ASTRAL_RUNE by id(9075)
        val BLOOD_RUNE by id(565)
        val SOUL_RUNE by id(566)
        val EARTH_RUNE by id(557)
        // Gems
        val SAPPHIRE by id(1607)
        val EMERALD by id(1605)
        val RUBY by id(1603)
        val DIAMOND by id(1601)
        val ONYX_BOLT_TIPS by id(9194)
        // Alchables
        val BATTLESTAFF by id(1391)
        val EARTH_ORB by id(575)
        val DRAGON_PLATELEGS by id(4087)
        val DRAGON_PLATESKIRT by id(4585)
        val RUNE_KITESHIELD by id(1201)
        // Herbs and seeds
        val SNAPDRAGON_SEED by id(5300)
        val GRIMY_RANARR by id(207)
        val GRIMY_HARRALANDER by id(205)
        val GRIMY_TOADFLAX by id(3049)
        val KWUARM_SEED by id(5299)
        val YEW_SEED by id(5315)
        val WINE_OF_ZAMORAK by id(245)
        val SNAPE_GRASS_SEED by id(22879)
        // Misc resources
        val RUNITE_ORE by id(451)
        val ADAMANTITE_ORE by id(449)
        val DRAGON_ARROWTIPS by id(11237)
        val RAW_SHARK by id(383)
        val IRON_ORE by id(440)
        val PURE_ESSENCE by id(7936)
        val MAHOGANY_LOGS by id(6332)
        val DESICCATED_PAGE by id(30640)
        val AETHER_CATALYST by id(30771)
    }

    // ------------------------------------------------------------------------------------------------------------
    /**
     * Animations. Ids 15000-15162 clash with GadScape custom seqs and will move with the port (proposed +300);
     * reading them here keeps that transparent.
     */
    object Seq : IdGroup(IdKind.SEQ) {
        // Archive Guardian (§4.1)
        val GUARDIAN_IDLE_FLYING by id(14745)      // also the CA boss-entry anim (struct 1216)
        val GUARDIAN_IDLE_GROUNDED by id(14746)
        val GUARDIAN_LAND by id(14747)
        val GUARDIAN_LIFT by id(14748)
        val GUARDIAN_DEATH by id(14749)
        val GUARDIAN_MUSHROOM_CAST by id(14750)
        val GUARDIAN_RANGED by id(14751)
        val GUARDIAN_SLAM by id(14752)
        val GUARDIAN_LASER by id(14753)
        val GUARDIAN_ENRAGE by id(14754)
        val GUARDIAN_WAKE by id(14755)
        val GUARDIAN_INERT_IDLE by id(14757)
        val GUARDIAN_DEATH_POSE by id(14758)
        val GUARDIAN_TENDRIL_SWIPE by id(14768)
        val GUARDIAN_TENDRIL_DESPAWN by id(14769)
        val GUARDIAN_TENDRIL_SPAWN by id(14771)
        val GUARDIAN_BATTERY_IDLE by id(15024)
        val GUARDIAN_BATTERY_DESPAWN by id(15025)
        val GUARDIAN_BATTERY_DESPAWN_IDLE by id(15026)

        // Ezekiel (§4.2)
        val EZEKIEL_IDLE by id(14683)
        val EZEKIEL_WALK by id(14684)
        val EZEKIEL_CLEAVE by id(14685)
        val EZEKIEL_LIGHTNING by id(14686)
        val EZEKIEL_STAR by id(14687)
        val EZEKIEL_HEAL by id(14688)
        val EZEKIEL_DEATH_SWORD by id(14690)
        val EZEKIEL_STUN_IN by id(14691)
        val EZEKIEL_STUN_LOOP by id(14692)
        val EZEKIEL_STUN_OUT by id(14693)
        val EZEKIEL_FINALE by id(14694)
        val EZEKIEL_FINALE_LOOP by id(14696)
        val EZEKIEL_DEATH by id(14697)
        val PLAYER_EZEKIEL_ROLL by id(14705)
        val PLAYER_EZEKIEL_STRAFE by id(14706)
        val EZEKIEL_SWORD_SPIN by id(14707)
        val EZEKIEL_FINALE_SWORD_IDLE by id(14713)
        val EZEKIEL_FENCE_TEMPLATE by id(14729)
        /** Fence drop seqs of the 7 fence cells (`14731 + 2i`). */
        val EZEKIEL_FENCE_DROP by ids(14731, 14733, 14735, 14737, 14739, 14741, 14743)
        /** Fence idle seqs of the 7 fence cells (`14732 + 2i`). */
        val EZEKIEL_FENCE_IDLE by ids(14732, 14734, 14736, 14738, 14740, 14742, 14744)

        // Bork and the Smorks (§4.3)
        val BORK_STUNNED by id(14538)
        val BORK_WALK by id(14539)
        val BORK_IDLE by id(14540)
        val BORK_DEATH by id(14541)
        val BORK_BEAT by id(14542)
        val BORK_DAZE_8 by id(14543)
        val BORK_DAZE_12 by id(14544)
        val BORK_DAZE_14 by id(14545)
        val BORK_GONG_IN_FROM_DAZE by id(14546)
        val BORK_GONG by id(14547)
        val BORK_GONG_OUT by id(14548)
        val BORK_STOMP_IN_FROM_DAZE by id(14549)
        val BORK_STOMP by id(14550)
        val BORK_STOMP_CYCLE_OUT by id(14551)
        val BORK_STOMP_OUT by id(14552)
        val SMORKS_IDLE by id(14553)
        val SMORKS_VOLLEY by id(14554)
        val SMORKS_BIG_CANNON by id(14555)
        val BORK_STOMP_IN_FROM_IDLE by id(14556)
        val BORK_GONG_IN_FROM_IDLE by id(14557)
        val BORK_OIL_DESPAWN by id(14576)          // loc anim on the oil loc
        /** Animations of the Bork/Smorks spotanims 4035-4062 (no server use). */
        val BORK_SPOTANIM_SEQS by ids(14558..14579)

        // Dagon'hai (§4.6)
        val DAGONHAI_ELDER_IDLE by id(14581)
        val DAGONHAI_STAFF_IDLE by id(14582)
        val DAGONHAI_BOOK_IDLE by id(14583)
        val DAGONHAI_ELDER_IDLE_WEAK by id(14584)
        val DAGONHAI_STAFF_IDLE_WEAK by id(14585)
        val DAGONHAI_BOOK_IDLE_WEAK by id(14586)
        val DAGONHAI_ELDER_WALK by id(14587)
        val DAGONHAI_STAFF_WALK by id(14588)
        val DAGONHAI_BOOK_WALK by id(14589)
        val DAGONHAI_ELDER_WALK_WEAK by id(14590)
        val DAGONHAI_STAFF_WALK_WEAK by id(14591)
        val DAGONHAI_BOOK_WALK_WEAK by id(14592)
        val DAGONHAI_ELDER_RANGED by id(14593)
        val DAGONHAI_ELDER_MAGIC by id(14597)
        val DAGONHAI_EMPOWERED_STAFF by id(14599)
        val DAGONHAI_EMPOWERED_BOOK by id(14601)
        val DAGONHAI_MAGIC_04 by id(14603)          // unassigned; INF empowered monk cast
        val DAGONHAI_EMPOWERED_ELDER by id(14604)
        /** Channel loop per NPC: Elder, staff monk, book monk. */
        val DAGONHAI_CHANNEL_IDLE by ids(14607, 14612, 14617)
        /** Channel success per NPC: Elder, staff monk, book monk. */
        val DAGONHAI_CHANNEL_END by ids(14609, 14614, 14619)
        /** Channel interrupted per NPC: Elder, staff monk, book monk. */
        val DAGONHAI_CHANNEL_INTERRUPTED by ids(14611, 14616, 14621)
        val DAGONHAI_STAFF_SEARCH by id(14622)
        val DAGONHAI_STAFF_SEARCH_END by id(14623)
        val DAGONHAI_STAFF_THROW_ZAMORAK by id(14624)
        val DAGONHAI_STAFF_THROW_BANDOS by id(14625)
        val DAGONHAI_STAFF_THROW_SARADOMIN by id(14626)
        val DAGONHAI_BOOK_SEARCH by id(14630)
        val DAGONHAI_BOOK_SEARCH_END by id(14631)
        val DAGONHAI_BOOK_THROW_ZAMORAK by id(14632)
        val DAGONHAI_BOOK_THROW_BANDOS by id(14633)
        val DAGONHAI_BOOK_THROW_SARADOMIN by id(14634)
        val DAGONHAI_ELDER_DEATH by id(14652)
        val DAGONHAI_STAFF_DEATH by id(14653)
        val DAGONHAI_BOOK_EXPLODE_DEATH by id(14654)
        val DAGONHAI_CAULDRON_IDLE by id(14656)
        val DAGONHAI_CAULDRON_ZAMORAK by id(14657)
        val DAGONHAI_CAULDRON_SARADOMIN by id(14659)
        val DAGONHAI_CAULDRON_BANDOS by id(14661)
        val DAGONHAI_CAULDRON_EMPOWERED by id(14678)
        val DAGONHAI_CAULDRON_EXPLODE by id(14679)
        val DAGONHAI_BOOK_LOC_IDLE by id(14681)
        val DAGONHAI_BOOK_LOC_OPEN by id(14682)
        val DAGONHAI_IMP_ATTACK by id(14529)
        val DAGONHAI_IMP_DEATH by id(14530)
        val IMP_IDLE by id(13841)                  // stock
        val IMP_WALK by id(13844)                  // stock
        val DEMONIC_RIFT_STAND by id(9355)         // stock GotR portal

        // Moia (§4.4)
        val MOIA_IDLE by id(14783)
        val MOIA_DEATH by id(14784)
        val MOIA_CAST by id(14785)
        val MOIA_DAGGER by id(14786)
        val MOIA_CHANT by id(14787)
        val MOIA_TELEPORT by id(14788)
        val MOIA_BINDER_IDLE by id(14789)
        val MOIA_BINDER_WALK by id(14790)
        val MOIA_BINDER_ATTACK by id(14791)
        val MOIA_BINDER_DEATH by id(14792)
        val PLAYER_COUGH by id(11268)              // stock, virus cough (optional)

        // Dreadnoth (§4.5)
        val JELLY_JUMP_3 by id(14773)
        val JELLY_HOP by id(14774)
        val DREADNOTH_IDLE by id(14776)
        val DREADNOTH_HOP by id(14777)             // threshold slam
        val DREADNOTH_SLIDE by id(14778)
        val DREADNOTH_MELEE by id(14779)
        val DREADNOTH_JUMP by id(14780)            // enrage jump-slam
        val DREADNOTH_DEATH by id(14782)
        val JELLY_RANGED by id(15161)
        val JELLY_SPAWN by id(15162)
        val JELLY_READY by id(1583)                // stock jelly anims
        val JELLY_WALK by id(1584)
        val JELLY_ATTACK by id(1586)
        val JELLY_DEATH by id(1587)

        // Ocellus (§4.8)
        val OCELLUS_INTRO_STAND by id(14804)
        val OCELLUS_INTRO_WALK by id(14805)
        val OCELLUS_P1_PROJ_START by id(14806)
        val OCELLUS_P1_PROJ_END by id(14807)
        val OCELLUS_P1_RAILGUN by id(14808)
        val OCELLUS_P1_CHARGE by id(14809)
        val OCELLUS_P1_EXPEND by id(14810)
        val OCELLUS_P1_SLAM by id(14811)
        val OCELLUS_P1_TELEPORT_OUT by id(14812)
        val OCELLUS_P1_TELEPORT_IN by id(14813)
        val OCELLUS_P1_TRANSFORM by id(14814)
        val OCELLUS_VFX_TELEPORT_OUT by id(14827)
        val OCELLUS_VFX_TELEPORT_IN by id(14828)
        val OCELLUS_P2_PORTAL_SPAWN by id(14829)
        val OCELLUS_P2_PORTAL_IDLE by id(14830)
        val CENSER_READY by id(14833)
        val CENSER_INACTIVE by id(14834)
        val PLAYER_CENSER by id(14835)
        val OCELLUS_P2_TRANSFORM by id(14837)
        val OCELLUS_P2_IDLE by id(14839)
        val OCELLUS_P3_IDLE by id(14840)
        val OCELLUS_P3_IDLE_HIGH by id(14841)
        val OCELLUS_P3_MELEE by id(14842)
        val OCELLUS_P4_HYBRID by id(14844)
        val OCELLUS_P3_WAVE by id(14846)
        val OCELLUS_P3_SUPERIOR_SLASH by id(14848)
        val OCELLUS_P3_WALK by id(14849)
        val OCELLUS_P3_VOID_SHOT_START by id(14850)
        val OCELLUS_P3_VOID_SHOT_ALL by id(14851)
        val OCELLUS_P3_VOID_SHOT_OUT by id(14852)
        val OCELLUS_P3_VOID_SHOT_OUT_WALKMERGE by id(14854)
        val OCELLUS_P4_WIND_START by id(14855)
        val OCELLUS_P4_WIND_END by id(14856)
        val OCELLUS_P3_UNLEASH_IN by id(14857)
        val OCELLUS_P3_UNLEASH_LOOP by id(14858)
        val OCELLUS_OBLITERATION_IN by id(14859)
        val OCELLUS_OBLITERATION_LOOP by id(14860)
        val OCELLUS_P3_UNLEASH_OUT by id(14861)
        val OCELLUS_P4_DEFLECT_IN by id(14863)
        val OCELLUS_P4_DEFLECT_LOOP by id(14864)
        val OCELLUS_P4_DEFLECT_OUT by id(14865)
        /** Silent deflect in / loop / out for repeat cycles. */
        val OCELLUS_P4_DEFLECT_SILENT by ids(14866..14868)
        val OCELLUS_P3_SPIN_IN by id(14869)
        val OCELLUS_P3_SPIN_LOOP by id(14870)
        val OCELLUS_P3_SPIN_OUT by id(14871)
        val OCELLUS_DEATH by id(14872)
        val OCELLUS_P2_TO_P3_PART1 by id(14878)
        val OCELLUS_P2_TO_P3_PART2 by id(14881)
        val ORION_IDLE by id(14882)
        val ORION_WALK by id(14883)
        /** P2 floor portal spawn / idle / despawn. */
        val OCELLUS_P2_FLOOR_PORTAL by ids(14884..14886)
        val PLAYER_VOID_DEATH by id(14893)
        val VOID_RIFT_SPAWN by id(14896)
        val VOID_RIFT_DETONATE by id(14898)
        val VOID_SIPHON_SPAWN by id(14900)
        val VOID_SIPHON_DETONATE by id(14902)
        val VOID_AUGMENTER_SPAWN by id(14904)
        val VOID_AUGMENTER_DETONATE by id(14906)
        /** Insolence degen field frames `_00` .. `_08` (14931 counting down to 14923). */
        val INSOLENCE_DEGEN_FRAMES by ids(14931, 14930, 14929, 14928, 14927, 14926, 14925, 14924, 14923)
        val VOID_CHAIN_FULL by id(14933)
        val VOID_CHAIN_PLAYER by id(14934)
        val INSOLENCE_DEGEN_DESPAWN by id(14938)
        val THE_BLADE_STAND by id(14946)
        val OCELLUS_BARRIER_GLOW_1 by id(14947)
        val OCELLUS_BARRIER_GLOW_2 by id(14948)
        val OCELLUS_UNLEASH_CRACK by id(14955)
        val OCELLUS_BARRIER_DEFAULT by id(14970)
        val OCELLUS_BARRIER_SPAWN by id(14977)
        val OCELLUS_BARRIER_DESPAWN by id(14978)
        val OCELLUS_VINE_GATE_OPEN by id(15027)
        val OCELLUS_VINE_GATE_CLOSING by id(15028)
        val OCELLUS_VINE_GATE_CLOSED by id(15029)
        val OCELLUS_SLICE_COLLAPSE by ids(15030..15033)
        val OCELLUS_SLICE_BROKEN by ids(15034..15037)
        val OCELLUS_ARM_CRACKING by ids(15038..15041)
        val OCELLUS_ARM_COLLAPSING by ids(15042..15045)
        val OCELLUS_ARM_CRACK_EMPTY by ids(15046..15049)
        val OCELLUS_STONE_SPAWN by id(15050)
        val OCELLUS_STONE_IDLE by id(15051)
        val OCELLUS_STONE_DESPAWN by ids(15052..15054)

        // Xarth (§4.7)
        val XARTH_IDLE by id(14979)
        val XARTH_WALK by id(14980)
        val XARTH_TURN_LEFT by id(14981)
        val XARTH_TURN_RIGHT by id(14982)
        val XARTH_MELEE by id(14983)
        val XARTH_MAGIC by id(14984)               // also the corpse-summon cast
        val XARTH_RANGED by id(14985)
        val XARTH_FLAME_BLAST by id(14989)
        val XARTH_ROOT by id(14991)
        val XARTH_AOE by id(14992)
        val XARTH_DEATH by id(14995)
        val XARTH_RUBBLE_COLLAPSE by id(14997)
        val XARTH_CORPSE_IDLE by id(14999)
        val XARTH_CORPSE_WALK by id(15000)
        val XARTH_CORPSE_DEATH by id(15003)
        val XARTH_CORPSE_ABSORB by id(15005)
        /** Pile fire loc anims, piles 1..8. */
        val XARTH_PILE_FIRE by ids(15008..15015)
        val XARTH_PILLAR_FIRE by id(15016)
        val XARTH_P2_TRANSITION by id(15017)
        val XARTH_DEFEND by id(15018)
        val XARTH_CORPSE_DOUSED_IDLE by id(808)    // stock
        val XARTH_FLAME_SPAWN by id(12003)         // stock loc anim
        val XARTH_FLAME_IDLE by id(12005)          // stock loc anim

        // Nexus (§3.5)
        val DOOR_OPEN by id(15020)
        val DOOR_OPEN_IDLE by id(15021)
        val DOOR_CLOSE by id(15022)
        val TRANSPORTER_IDLE by id(15023)
        val NAVIGATOR_STAND by id(808)             // stock
        val NAVIGATOR_WALK by ids(819..822)        // stock
        val NAVIGATOR_RUN by id(824)               // stock
        val CAMP_ALLY_STAND by id(14465)           // Armacus 16724 / Eva 16725
        val CAMP_ALLY_WALK by id(14466)
        val ARMACUS_SIT_RECOVERING by id(14534)    // Talk-to Armacus 16696
        val EVA_STAND by id(13993)                 // Eva 16697 / cutscene 16728
        val ARMACUS_CUTSCENE_STAND by id(13999)

        // Lobby (§3.2)
        val LOBBY_BANKER_IDLE by id(15055)
        val LOBBY_STRATEGIST_IDLE by id(15056)
        val LOBBY_RECORD_KEEPER_IDLE by id(15057)
        val SPRITE_WALK by id(15061)
        val SPRITE_IDLE by id(15062)
        val SPRITE_EXPLODE by id(15063)
        /** Ascension rock raise loc anims, rocks 1..3. */
        val ROCK_RAISE by ids(15064..15066)
        /** Ascension rock lower loc anims, rocks 1..3. */
        val ROCK_LOWER by ids(15067..15069)
        /** Idle loc anims of the active rocks 1..3. */
        val ROCK_IDLE_ACTIVE by ids(15071, 15073, 15075)
        /** Idle loc anims of the inactive rocks 1..3. */
        val ROCK_IDLE_INACTIVE by ids(15070, 15072, 15074)

        // Vault (§3.15)
        val VAULT_CHEST_DEFAULT by id(15076)
        val UNIQUE_CHEST_FLOAT_DOWN by id(15078)
        val UNIQUE_CHEST_OPEN by id(15080)
        val VAULT_CHEST_OPEN by id(15101)
        val PLAYER_CHEST_OPEN by id(15103)
        val VAULT_BRAZIER_SPAWN by id(15105)
        /** The whole vault seq block 15076-15105 (loot vfx, chest sequences), for the port check. */
        val VAULT_SEQS by ids(15076..15105)

        // Reward items (§3.16, task #50)
        val MOTE_BREAK by id(15106)
        val ZEAL_KNEEL by id(15117)                // Horn of Bran Camelot cutscene
        val FLETCH_ASCENSION_BOLT_TIPS by id(15158)
        /** The reward-item seq block 15106-15158 (weapon anims 15108-15140 belong to task #50). */
        val REWARD_ITEM_SEQS by ids(15106..15158)
    }

    // ------------------------------------------------------------------------------------------------------------
    object Spot : IdGroup(IdKind.SPOTANIM) {
        // Archive Guardian (§4.1)
        val GUARDIAN_RANGED_CAST by id(4199)
        val GUARDIAN_RANGED_IMPACT by id(4200)
        val GUARDIAN_RANGED_PROJ by id(4201)
        val GUARDIAN_LAND by id(4202)
        val GUARDIAN_LIFT by id(4203)
        val GUARDIAN_ENRAGE by id(4204)
        val GUARDIAN_LASER by id(4205)
        val GUARDIAN_WAKE by id(4206)
        val GUARDIAN_INERT_IDLE by id(4207)
        val GUARDIAN_TENDRIL_TELEGRAPH by id(4208)

        // Ezekiel (§4.2)
        val EZEKIEL_LIGHTNING_CAST by id(4167)
        val EZEKIEL_LIGHTNING_IMPACT by id(4168)
        val EZEKIEL_STAR_CRACK by id(4169)
        val EZEKIEL_STAR_SPAWN by id(4170)
        val EZEKIEL_STAR_MARKER by id(4171)
        val EZEKIEL_STAR_EXPLOSION by id(4172)
        val EZEKIEL_HEAL_GLOW by id(4173)
        val EZEKIEL_HEAL by id(4174)
        val EZEKIEL_FINALE_CAST by id(4175)
        val EZEKIEL_FINALE_CAST_LOOP by id(4176)
        val EZEKIEL_FINALE_GLOW by id(4177)
        val EZEKIEL_FINALE_GLOW_LOOP by id(4178)
        val EZEKIEL_DEATH_GLOW by id(4179)
        /** Finale sword "left" (north of the pen): drop, idle, telegraph, despawn. */
        val EZEKIEL_FINALE_SWORD_LEFT by ids(4180..4183)
        /** Finale sword "right" (south): drop, idle, telegraph, despawn. */
        val EZEKIEL_FINALE_SWORD_RIGHT by ids(4184..4187)
        /** Finale sword "front" (east): drop, idle, telegraph, despawn. */
        val EZEKIEL_FINALE_SWORD_FRONT by ids(4188..4191)
        /** Unassigned fourth sword set (`_04`), spare. */
        val EZEKIEL_FINALE_SWORD_SPARE by ids(4192..4195)
        val EZEKIEL_FINALE_IMPACT_LARGE by id(4196)
        val EZEKIEL_FINALE_IMPACT_MEDIUM by id(4197)
        val EZEKIEL_FINALE_IMPACT_SMALL by id(4198)

        // Bork and the Smorks (§4.3)
        val BORK_STUN_STARS by id(4035)            // npc_stunned_4x4
        val BORK_DEATH by id(4036)
        val BORK_BEAT by id(4037)
        val BORK_DAZE_8 by id(4038)
        val BORK_STOMP by id(4039)
        val BORK_DAZE_12 by id(4040)
        val BORK_DAZE_14 by id(4041)
        val SMORKS_VOLLEY by id(4042)
        val SMORKS_BIG_CANNON by id(4043)
        val SMORKS_BIG_CANNON_FLASH by id(4044)
        val SMORKS_INCENDIARY_PROJ by id(4045)
        val SMORKS_INCENDIARY_IMPACT by id(4046)
        /** Oil splat at 0 / 90 / 180 / 270 degrees. */
        val SMORKS_OIL_SPLAT by ids(4047..4050)
        /** Oil despawn at 0 / 90 / 180 / 270 degrees. */
        val SMORKS_OIL_DESPAWN by ids(4051..4054)
        val SMORKS_BONE_IMPACT by id(4055)
        val SMORKS_BONE_PROJ by id(4056)
        val SMORKS_POTION_PROJ by id(4057)
        val SMORKS_POTION_SPLASH by id(4058)       // at the tile
        val SMORKS_POTION_HIT by id(4059)          // on the player
        /** Smorks death: tower, goblins, explosion. */
        val SMORKS_DEATH by ids(4060, 4061, 4062)
        val SMORKS_HEAVY_PROJ by id(4440)          // cannonball_travel_big
        val SMORKS_HEAVY_SHADOW by id(4442)
        val SMORKS_INCENDIARY_MARKER by id(4443)
        /** Wave dust: 0, short, 90, short 90, 180, short 180, 270, short 270, short 45/135/225/315. */
        val WAVE_DUST by ids(4394..4405)

        // Dagon'hai (§4.6)
        val DAGONHAI_SARA_DAMAGE_TELEGRAPH by id(4063)
        val DAGONHAI_SARA_HEAL_TELEGRAPH_150 by id(4064)
        val DAGONHAI_SARA_HEAL_TELEGRAPH_180 by id(4065)
        val DAGONHAI_SARA_HEAL_IMPACT by id(4066)
        val DAGONHAI_DEMONIC_GLOW by id(4067)
        val DAGONHAI_SARA_DAMAGE_LIGHTNING by id(4068)
        val DAGONHAI_SARA_HEAL_LIGHTNING by id(4069)
        val DAGONHAI_ELDER_RANGED_CAST by id(4070)
        val DAGONHAI_ELDER_RANGED_PROJ by id(4071)
        val DAGONHAI_ELDER_RANGED_IMPACT by id(4072)
        val DAGONHAI_EMPOWERED_STAFF by id(4074)
        val DAGONHAI_EMPOWERED_BOOK by id(4075)
        val DAGONHAI_MAGIC_04 by id(4076)          // unassigned
        val DAGONHAI_EMPOWERED_ELDER by id(4077)
        val DAGONHAI_EMPOWERED_ELDER_PROJ by id(4078)
        /** Elder channel vfx: fire idle/end, water idle/end, air idle/end, earth idle/end. */
        val DAGONHAI_CHANNEL_ELDER by ids(4079..4086)
        /** Staff monk channel vfx, same layout. */
        val DAGONHAI_CHANNEL_STAFF by ids(4087..4094)
        /** Book monk channel vfx, same layout. */
        val DAGONHAI_CHANNEL_BOOK by ids(4095..4102)
        /** Elder surge cast spot: fire, water, air, earth. */
        val DAGONHAI_SURGE_CAST by ids(4103..4106)
        /** Staff monk hand spots: Zamorak, Saradomin, Bandos. */
        val DAGONHAI_HAND_STAFF by ids(4107, 4108, 4109)
        /** Book monk hand spots: Zamorak, Saradomin, Bandos. */
        val DAGONHAI_HAND_BOOK by ids(4110, 4111, 4112)
        /** Tome projectile: Zamorak, Saradomin, Bandos. */
        val DAGONHAI_TOME_PROJ by ids(4113, 4114, 4115)
        /** Tome impact: Zamorak, Saradomin, Bandos. */
        val DAGONHAI_TOME_IMPACT by ids(4116, 4117, 4118)
        /** Zamorak lane gusts: N, NE, E, SE, S, SW, W, NW. */
        val DAGONHAI_GUSTS_ZAMORAK by ids(4119..4126)
        /** Saradomin lane gusts: N, NE, E, SE, S, SW, W, NW. */
        val DAGONHAI_GUSTS_SARADOMIN by ids(4127..4134)
        /** Bandos lane gusts: N, NE, E, SE, S, SW, W, NW. */
        val DAGONHAI_GUSTS_BANDOS by ids(4135..4142)
        val DAGONHAI_CAULDRON_ZAMORAK by id(4143)
        val DAGONHAI_ZAMORAK_FIREBALL_PROJ by id(4144)
        /** Zamorak fireball fuses of 3 / 4 / 5 / 6 ticks. */
        val DAGONHAI_ZAMORAK_FUSE by ids(4145..4148)
        val DAGONHAI_ZAMORAK_BLAST by id(4149)
        val DAGONHAI_CAULDRON_SARADOMIN by id(4150)
        val DAGONHAI_STORMCLOUD_HEAL by id(4151)
        val DAGONHAI_STORMCLOUD_HEAL_DESPAWN by id(4152)
        val DAGONHAI_STORMCLOUD_DAMAGE by id(4153)
        val DAGONHAI_STORMCLOUD_DAMAGE_DESPAWN by id(4154)
        val DAGONHAI_CAULDRON_BANDOS by id(4155)
        /** Bandos shockwave rings 1-2, 3-4, 5-6, 7-8. */
        val DAGONHAI_BANDOS_RINGS by ids(4156..4159)
        val DAGONHAI_CAULDRON_EXPLODE by id(4160)
        val DAGONHAI_CAULDRON_DUST by id(4161)
        val DAGONHAI_DEMON_BUFF by id(4162)
        val DAGONHAI_BOOK_DEATH by id(4163)
        val DAGONHAI_CAULDRON_SIZZLE by id(4164)
        val DAGONHAI_CAULDRON_DOME by id(4165)
        val DAGONHAI_CAULDRON_UNLOCK by id(4166)
        /** Stock surge projectiles: fire, water, air, earth. */
        val SURGE_PROJ by ids(1465, 1459, 1456, 1462)
        /** Stock surge impacts: fire, water, air, earth. */
        val SURGE_IMPACT by ids(1466, 1460, 1457, 1463)

        // Dreadnoth (§4.5)
        val DREADNOTH_JUMP_SHADOW by id(4209)
        val JELLY_FORMING by id(4210)
        val DREADNOTH_RANGED_PROJ by id(4211)
        val DREADNOTH_SPLATTER_PROJ by id(4212)

        // Moia (§4.4)
        val MOIA_MAGIC_PROJ by id(4213)
        val MOIA_DAGGER_PROJ by id(4214)
        val MOIA_MAGIC_IMPACT by id(4216)
        val MOIA_INFECT_GROUND by id(4217)
        val MOIA_INFECT_PROJ by id(4218)
        val MOIA_TELEPORT_OUT by id(4219)
        val MOIA_TELEPORT_IN by id(4220)
        val MOIA_DEATH by id(4221)
        val MOIA_BINDER_EXPLODE by id(4222)
        val MOIA_BINDER_EMPOWER_LOOP by id(4223)
        val MOIA_BINDER_EMPOWER_IN by id(4224)
        val MOIA_PLAYER_EXPLODE by id(4225)

        // Ocellus (§4.8)
        val OCELLUS_SOUL_FRAGMENT_PROJ by id(4226)
        val OCELLUS_WATER_CAST by id(4228)
        val OCELLUS_WATER_PROJ by id(4229)
        val OCELLUS_WATER_IMPACT by id(4230)
        val OCELLUS_FIRE_CAST by id(4231)
        val OCELLUS_FIRE_PROJ by id(4232)
        val OCELLUS_FIRE_IMPACT by id(4233)
        val OCELLUS_P1_RELEASE_PROJ by id(4234)
        /**
         * Straight tendril cracks: start 0/90/180/270, body 0, end 0, body 90, end 90, body 180, end 180,
         * body 270, end 270.
         */
        val OCELLUS_CRACK_STRAIGHT by ids(4236..4247)
        /** Diagonal tendril cracks, same layout as [OCELLUS_CRACK_STRAIGHT]. */
        val OCELLUS_CRACK_DIAGONAL by ids(4248..4259)
        val OCELLUS_P1_CHARGE by id(4260)
        val OCELLUS_P1_SLAM by id(4261)
        /** P1 slam floor at 0 / 90 / 180 / 270 degrees. */
        val OCELLUS_P1_SLAM_FLOOR by ids(4262..4265)
        val OCELLUS_P1_TELEPORT_OUT by id(4266)
        val OCELLUS_P1_TELEPORT_IN by id(4267)
        val OCELLUS_P1_TO_P2 by id(4268)
        val OCELLUS_PORTAL_SHOT by id(4269)
        val OCELLUS_P2_VOID_POOL by id(4270)
        val OCELLUS_FLOOR_PORTAL_SPAWN by id(4271)
        val OCELLUS_FLOOR_PORTAL_IDLE by id(4272)
        val OCELLUS_FLOOR_PORTAL_DESPAWN by id(4273)
        val OCELLUS_DEATH by id(4274)
        val OCELLUS_DEATH_SKIRT by id(4275)
        val OCELLUS_DEATH_CRACK by id(4276)
        val BLADE_IDLE_STATIC by id(4277)
        val BLADE_IDLE_1 by id(4278)
        val BLADE_IDLE_2 by id(4279)
        val BLADE_DROP by id(4280)
        val BLADE_ROOT_FIRE by id(4281)
        val OCELLUS_P2_TO_P3_ARM by id(4282)
        val OCELLUS_P2_TO_P3_ARM_GLOW by id(4283)
        /** Orion pet emote spots: p1 explode floor, charge, explode, fire attack, water attack, p3 magic travel. */
        val ORION_EMOTE by ids(4284..4289)
        val OCELLUS_VINE_ROOT_START by id(4290)
        val OCELLUS_VINE_ROOT_IDLE by id(4291)
        val OCELLUS_VINE_ROOT_END by id(4292)
        val OCELLUS_VOID_SHOT_IMPACT by id(4293)
        val OCELLUS_VOID_SHOT_CAST by id(4294)
        val OCELLUS_VOID_SHOT_CAST_IN by id(4295)
        val OCELLUS_VOID_SHOT_CAST_OUT by id(4296)
        val OCELLUS_VOID_SHOT_PROJ_1 by id(4297)
        val OCELLUS_VOID_SHOT_PROJ_2 by id(4298)
        /** Untether quadrant bombs: idle, impact, drop, spawn. */
        val OCELLUS_UNTETHER_BOMB by ids(4299..4302)
        val OCELLUS_SIPHON_HEAL_PROJ by id(4303)
        val BLADE_REPAIR by id(4304)
        val BLADE_DISCHARGE by id(4305)
        val OCELLUS_KILLER_BUFF_RIFT by id(4306)
        val OCELLUS_KILLER_BUFF_SIPHON by id(4307)
        val OCELLUS_KILLER_BUFF_AUGMENTER by id(4308)
        val OCELLUS_ORB_SPAWN_6 by id(4309)
        val OCELLUS_ORB_IDLE by id(4310)
        val OCELLUS_ORB_SPAWN_12 by id(4311)
        val OCELLUS_ORB_DESPAWN by id(4312)
        val OCELLUS_PLAYER_ROBBED by id(4313)
        val OCELLUS_ORB_PICKUP by id(4314)
        val OCELLUS_SOUL_IDLE by id(4315)
        val OCELLUS_SOUL_LEVITATING by id(4316)
        /** Untether arena eruption at 0 / 90 / 180 / 270 degrees. */
        val OCELLUS_UNTETHER_ERUPTION by ids(4317..4320)
        val OCELLUS_UNTETHER_ON_BOSS by id(4321)
        val OCELLUS_DEFLECT_WIND by id(4322)
        val OCELLUS_INSOLENCE_DEGEN by id(4323)
        val OCELLUS_DEFLECT_WIND_90 by id(4324)
        val OCELLUS_SPIN_IN by id(4325)
        val OCELLUS_SPIN by id(4326)
        val OCELLUS_SPIN_OUT by id(4327)
        val OCELLUS_HYBRID_MELEE by id(4328)
        val OCELLUS_HYBRID_MAGIC by id(4329)
        val OCELLUS_HYBRID_RANGED by id(4330)
        /** Obliteration floor: in, loop, out 1, out 2. */
        val OCELLUS_OBLITERATION_FLOOR by ids(4331..4334)
        val OCELLUS_WAVE by id(4335)
        val OCELLUS_INSOLENCE_SLASH by id(4336)
        val OCELLUS_WAVE_LIGHTNING by id(4337)
        val OCELLUS_INSOLENCE_LIGHTNING by id(4338)
        val VOID_CHAIN_EXPLOSION by id(4339)
        val VOID_CHAIN_TELEGRAPH by id(4340)
        val GLOW_ZAROS by id(4341)
        val GLOW_BANDOS by id(4342)
        val GLOW_SARADOMIN by id(4343)
        val GLOW_ARMADYL by id(4344)
        val OCELLUS_DEBRIS_FALL by id(4346)
        val OCELLUS_DEBRIS_IDLE by id(4347)
        val OCELLUS_DEBRIS_DESPAWN by id(4348)
        /** Deflect arena, range (quadrant void) at 0 degrees: start, loop, end. */
        val OCELLUS_DEFLECT_RANGE_0 by ids(4349..4351)
        /** Deflect arena, melee (ring) at 0 degrees: start, loop, end. */
        val OCELLUS_DEFLECT_MELEE_0 by ids(4352..4354)
        val OCELLUS_DEFLECT_RANGE_90 by ids(4355..4357)
        val OCELLUS_DEFLECT_MELEE_90 by ids(4358..4360)
        val OCELLUS_DEFLECT_RANGE_180 by ids(4361..4363)
        val OCELLUS_DEFLECT_MELEE_180 by ids(4364..4366)
        val OCELLUS_DEFLECT_RANGE_270 by ids(4367..4369)
        val OCELLUS_DEFLECT_MELEE_270 by ids(4370..4372)
        val BLADE_DROP_EXPLOSION by id(4388)
        val BLADE_DROP_EXPLOSION_INSTANT by id(4389)
        /** Quadrant collapse explosions, one per slice. */
        val OCELLUS_COLLAPSE_EXPLOSION by ids(4390..4393)

        // Xarth (§4.7)
        val XARTH_P2_RESTORE by id(4032)           // TD shield restore spot, P2 transition
        val XARTH_P2_DEPLETE by id(4033)
        val XARTH_MAGIC_PROJ by id(4034)
        val XARTH_MAGIC_IMPACT by id(2854)         // stock
        val XARTH_RANGED_CAST by id(4373)
        val XARTH_RANGED_PROJ by id(4374)
        val XARTH_RANGED_IMPACT by id(4375)
        val XARTH_AOE by id(4376)
        val XARTH_ROOT by id(4377)
        val XARTH_BOMB_PROJ by id(4378)
        val XARTH_BOMB_IMPACT by id(4379)
        val XARTH_FLAME_BLAST by id(4380)
        val XARTH_DEATH by id(4381)
        val XARTH_RUBBLE_COLLAPSE by id(4382)
        val XARTH_CORPSE_WALK_FIRE by id(4383)
        val XARTH_CORPSE_WALK_DOUSED by id(4384)
        val XARTH_CORPSE_DEATH by id(4385)
        val XARTH_CORPSE_ABSORBED by id(4386)
        val XARTH_ABSORB by id(4387)

        // Vault and reward items (§3.15, §3.16)
        val VAULT_CHEST_SPARKLES by id(4406)
        val UNIQUE_LOOT_LIGHT by id(4407)
        val UNIQUE_LOOT_TOME by ids(4408, 4409)
        val UNIQUE_LOOT_OBLIGATOR by ids(4410, 4411)
        val UNIQUE_LOOT_HORN by ids(4412, 4413)
        val UNIQUE_LOOT_CROSSBOWS by ids(4414, 4415)
        val UNIQUE_LOOT_RONDACHE by ids(4416, 4417)
        /** Elemental fragment Break: earth, fire, water, air. */
        val MOTE_DESTROY by ids(4418, 4419, 4420, 4421)
        /** Reward item effect spots (Rondache special, Obligator thrusts, Zorya's tome, ascension bolts). */
        val REWARD_ITEM_EFFECTS by ids(4422..4439)
    }

    // ------------------------------------------------------------------------------------------------------------
    /** Interfaces (§5.1). Component indices are in [Component]. */
    object Interface : IdGroup(IdKind.INTERFACE) {
        val CHESTS by id(973)                      // tfa_chests, loot modal
        val HUD by id(974)                         // tfa_hud overlay
        val LOBBY by id(975)                       // tfa_lobby overlay
        val PARTY_DETAILS by id(976)               // tfa_partydetails
        val PARTY_LIST by id(977)                  // tfa_partylist
        val SPECTATE_INV by id(978)                // tfa_spectate_hud_inv
        val STATS by id(979)                       // tfa_stats "Alliance Log"
        val HPBAR_HUD by id(303)                   // stock, changed in 2746
    }

    /** Component indices on the raid interfaces (not remapped; the interface id is). */
    object Component {
        const val CHESTS_UNIQUE_ART = 3            // 973:3 model 62364, hidden by default
        const val CHESTS_RIFT_ART = 4              // 973:4 rift model
        const val CHESTS_BANK_ALL = 5              // op6
        const val CHESTS_TAKE_ALL = 7              // op1
        const val CHESTS_DISCARD_ALL = 9           // op6, confirm dialog
        const val CHESTS_ITEMS = 11                // clientscript 149, 2 x 3 grid
        const val HUD_ACTIONS = 1                  // 974:1, IF_SCRIPT_TRIGGER 1373305897
        const val HUD_ORBS = 5                     // 974:5, IF_SCRIPT_TRIGGER 2481737999
        const val HUD_TIMER = 44                   // 974:44
        const val LOBBY_NAMES = 5                  // 975:5
        const val PARTY_DETAILS_BUTTONS = 1        // 976:1, pause subs 0-47
        const val PARTY_LIST_BUTTONS = 1           // 977:1, pause subs 0-2
        const val PARTY_LIST_ROWS = 14             // 977:14, pause sub = row 0..44
        const val SPECTATE_ITEMS = 0               // 978:0 "items"
        const val STATS_DEBUG_SETTER = 24          // 979:24, ignore
        const val STATS_CATEGORY = 34              // 979:34, IF_SCRIPT_TRIGGER 2359217665
        const val HPBAR_EXTRA_BAR_COLOUR_A = 25    // 303:25, Dagon'hai recolour
        const val HPBAR_EXTRA_BAR_COLOUR_B = 26    // 303:26
    }

    /** IF_SCRIPT_TRIGGER crcs (§5.3), as the signed 32-bit values the packet carries. Not cache ids. */
    object Crc {
        const val SPECTATE_SLOT = -1813229297      // 2481737999 on 974:5: (int slot or -1, int 1)
        const val HUD_ACTION = 1373305897          // 974:1: (int) 1 skip phase, 2 leave
        const val STATS_CATEGORY = -1935749631     // 2359217665 on 979:34: (dbrow category)
        const val STATS_DEBUG_SETTER = -652584508  // 3642382788 on 979:24: ignore
        const val OMNISHOP_BUY = 407137374         // omnishop_main:trigger_buy: (dbrow stock, int, int)
    }

    object Inv : IdGroup(IdKind.INV) {
        val CHESTS by id(1029)                     // 6 slots
        val SUPPLIES_SHOP by id(1030)              // 10 slots
        val SUPPLIES_SNAPSHOT by id(1031)          // 28 slots, Practice Mode
    }

    // ------------------------------------------------------------------------------------------------------------
    /**
     * Clientscripts the server runs (RUNCLIENTSCRIPT, §5.2) and procs it mirrors. 10036, 10042 and 10102 are in
     * the clashing range and move to 11002 / 11008 / 11016 with the proposed remap.
     */
    object Script : IdGroup(IdKind.CLIENTSCRIPT) {
        val PREFETCH_BORK by id(10010)
        val PREFETCH_DAGONHAI by id(10011)
        val PREFETCH_EZEKIEL by id(10012)
        val PREFETCH_GUARDIAN by id(10013)
        val PREFETCH_DREADNOTH by id(10014)
        val PREFETCH_MOIA by id(10015)
        val PREFETCH_OCELLUS by id(10016)
        val PREFETCH_XARTH by id(10030)
        val HUD_TIMER by id(10036)                 // (int elapsedTicks or -1, int frozen)
        val HUD_NAMES by id(10042)                 // (string x 8)
        val PARTY_MEMBER_ROW by id(10053)          // (int viewerRole, string row)
        val PARTY_APPLICANT_ROW by id(10058)       // (string row)
        val PARTY_DETAILS_SETTINGS by id(10060)    // (role, prefCompletions, mode, prefSize, spectatingDisabled, tab)
        val BUTTON_RELABEL by id(10072)            // (component, string)
        val PARTY_LIST_ROW by id(10094)            // (int index 0..44, string row); always all 45
        val STATS_OPEN by id(10102)                // (int inActiveRaid)
        val STATS_MSG_VIEWING_PARTY by id(10110)
        val STATS_MSG_NOT_IN_RAID by id(10115)
        val STATS_ROWS_DONE by id(10124)
        val STATS_ROW by id(10125)
        val PARTY_STATS_MVP by id(10128)
        val PARTY_STATS_TABLE by id(10131)
        val PARTY_STATS_MEMBER by id(10136)
        val PARTY_STATS_TOTAL by id(10137)
        val PARTY_STATS_SPLITS by id(10139)        // needs array args (E2)
        val HPBAR_EXTRA_BAR by id(10152)           // (boolean instant)
        /** Procs the server mirrors or merges: hpbar 10149-10153, cape messages 10173/10174, Obligator mode 10178. */
        val HPBAR_PROCS by ids(10149..10153)
        val CAPE_MESSAGE_PROCS by ids(10173, 10174)
        val OBLIGATOR_MODE_PROC by id(10178)
        val DEATH_ZONE_PROC by id(10182)           // copy into DeathFeeZone
        /** Generic dynamic-table library hooks the server never runs. */
        val UNUSED_LIBRARY by ids(9977, 9979, 9993, 10003)
        // Stock scripts
        val INTERFACE_INV_INIT by id(149)
        val UI_HIGHLIGHT by ids(8471..8486)
        val UI_HIGHLIGHT_INIT_FX by id(8474)       // signature changed in 2746: (component, int, dbrow)
        val OMNISHOP_LIST_SETUP by id(7143)
        val OMNISHOP_LIST_ITEM_SETUP by id(7144)
        val GRAVESTONE_FEE by id(1797)
        /** Stock scripts with 3-way merges in the port (R4-06/07). */
        val MERGED_STOCK by ids(98, 608, 6156, 6551)
    }

    // ------------------------------------------------------------------------------------------------------------
    object Varp : IdGroup(IdKind.VARP) {
        val KC by id(5894)                         // total_completed_thefracturedarchive
        val WORN_ITEM_BONUS7 by id(5900)           // task #50
        val PERM_TRANSMIT_1 by id(5907)            // 16380-16384
        val PERM_TRANSMIT_2 by id(5908)            // server use only
        val PERM_TRANSMIT_3 by id(5909)            // server use only
        val TEMP_TRANSMIT_1 by id(5910)            // 16386-16392
        val TEMP_TRANSMIT_2 by id(5911)            // 16393-16407
        val TEMP_TRANSMIT_3 by id(5912)            // 16408-16425
        val MY_CONTROLLER by id(5913)              // -1 when not in a party
        val GATHERING_COORD_TEMP by id(5916)       // unused by the server
        val MINIQUEST_MAIN by id(5920)             // 16426-16428
        val UNNAMED_5921 by id(5921)               // 16429-16432
        val LOOT_TEMP_1 by id(5922)                // 16433
        val REWARDS_1 by id(5925)
        val ZORYAS_TOME_STACKS by id(5926)         // task #50
        val PET_METAMORPHS by id(5927)             // 16440-16447
        /** Unnamed server-only varps, two 16-bit varbits each (INF: personal bests). */
        val PERSONAL_BEST_STORE by ids(5938..5987)
        val HPBAR_EXTRA_INFO by id(5990)           // 16570 / 16571
        val HPBAR_SHOW_EXTRA_BAR by id(5991)
        val UNNAMED_5992 by id(5992)               // 16574 / 16575
        val PRAYER_ALLACTIVE_ALTERNATE_0 by id(5994)
        // Stock varps
        val HPBAR_HUD_NPC by id(1683)
        val COLLECTION_ITEMS by id(5729)           // 16326-16331
        val WGS_WEIGHT by id(4234)                 // 16354
        val POH_MENAGERIE by id(4268)              // 16576
    }

    object Varbit : IdGroup(IdKind.VARBIT) {
        val CA_TASKS_COMPLETED by id(16321)        // keep 0: no TFA tasks at launch
        val CHARGES_FOLIO by id(16322)
        val CHARGES_OBLIGATOR by id(16323)
        val CHARGES_ZORYAS_TOME by id(16324)
        /** Collection items: capes tiers 1..5. */
        val COLLECTION_CAPES by ids(16326..16330)
        val COLLECTION_FOLIO by id(16331)
        val WORN_OBLIGATOR by id(16347)
        val WORN_OBLIGATOR_UNCHARGED by id(16348)
        val WORN_TUMEKENS_SHADOW by id(16349)
        val WORN_SCYTHE_OF_VITUR by id(16350)
        val WORN_TWISTED_BOW by id(16351)
        val WORN_FLAT_ARMOUR_PHYSICAL by id(16352)
        val WORN_FLAT_ARMOUR_MAGIC by id(16353)
        val ENTRANCE_UNLOCKED by id(16354)         // wgs_tfa_shortcut_unlocked
        /** Unnamed candidates for Practice Mode "encountered" flags (use server-side storage). */
        val PRACTICE_ENCOUNTERED by ids(16358..16379)
        val FRIENDS_FILTER by id(16380)
        val SUPPLY_POINTS by id(16381)             // 7 bits, reset after a raid
        val PREFERRED_MODE by id(16382)
        val RECORDS_ROOM_UNLOCKED by id(16383)
        val OCELLUS_MET by id(16384)
        val PARTY_STATUS by id(16386)              // 0 none, 1 party, 2 "Step Inside Now!"
        /** HUD orbs p0..p7 (5 bits each). */
        val ORBS by ids(16387..16394)
        val PARTY_SLOT by id(16395)                // slot + 1; 0 for external spectators
        val HIDE_PLAYERS by id(16396)
        /** Vault chest k opened (k = 0..7). */
        val VAULT_CHEST_OPENED by ids(16397..16404)
        val SPECTATE_MODE by id(16405)             // 0 alive, 1 dead party member, 2 external
        val SPECTATE_SLOT by id(16406)
        val SKIP_PHASE_MODE by id(16407)
        /** tfa_client_primary0..7 (tank / main target, for RuneLite). */
        val PRIMARY by ids(16408..16415)
        val HAS_CLAIMABLE_LOOT by id(16416)
        val CURRENT_PATH by id(16417)              // bit (section - 1): 0 west, 1 east
        val MOIA_SHADOW_SHIELD by id(16418)        // keep 0
        /** Cape stand visibility, tiers 1..5 (16420 is not one of them). */
        val CAPE_STAND_VISIBLE by ids(16419, 16421, 16422, 16423, 16424)
        val EXTERNAL_SPECTATORS by id(16425)
        val MINIQUEST by id(16426)
        val WIZARD_APPRENTICE_MET by id(16427)
        val JUNA_DIALOGUE by id(16428)
        /** Unnamed server-only varbits of varp 5921. */
        val UNNAMED_5921 by ids(16429..16432)
        val VAULT_OWN_CHEST by id(16433)
        val MOTE_EARTH_UNLOCKED by id(16434)
        val MOTE_AIR_UNLOCKED by id(16435)
        val MOTE_FIRE_UNLOCKED by id(16436)
        val MOTE_WATER_UNLOCKED by id(16437)
        val RONDACHE_CHARGES by id(16438)
        val ORION_ROBED_UNLOCKED by id(16440)
        /** Pet metamorph unlocks: xarth, ezekiel, guardian, moia, bork, jelly, dagonhai (cache order). */
        val PET_METAMORPH_UNLOCKED by ids(16441..16447)
        /** Unnamed server-only 16-bit varbits of varps 5938-5987 (INF: personal bests). */
        val PERSONAL_BEST_STORE by ids(16449..16569)
        val HPBAR_EXTRA_CURRENT by id(16570)
        val HPBAR_EXTRA_BASE by id(16571)
        val UNNAMED_5992 by ids(16574, 16575)
        val POH_MENAGERIE_ORION by id(16576)
        val PRAYER_ZEAL by id(16577)
        val PRAYER_ZEAL_UNLOCKED by id(16578)      // Horn of Bran
        // Stock varbits
        val HPBAR_CURRENT by id(6099)
        val HPBAR_MAX by id(6100)
        val BURN_DAMAGE_TOTAL by id(10964)         // player_own_burn_damage_total
    }

    /** Client-owned varcs (reference only; the server never writes them). */
    object Varc : IdGroup(IdKind.VARC) {
        val PARTY_LIST_NEXT_REFRESH by id(1569)
        val PARTY_LIST_SCROLL by id(1570)
        val HUD_NAMES by ids(1573..1580)           // written by script 10042
        val STATS_STATE by ids(1581..1587)
    }

    // ------------------------------------------------------------------------------------------------------------
    object Dbrow : IdGroup(IdKind.DBROW) {
        // Ambience (ambient_sfx, values stripped)
        val AMBIENCE_VAULT by id(16990)
        val AMBIENCE_OCELLUS by id(16991)
        val AMBIENCE_NEXUS by id(16992)
        val AMBIENCE_LOBBY by id(16993)
        val AMBIENCE_BORK by id(16994)
        val AMBIENCE_XARTH by id(16995)
        val AMBIENCE_DAGONHAI by id(16996)
        val AMBIENCE_DREADNOTH by id(16997)
        val AMBIENCE_MOIA by id(16998)
        val AMBIENCE_EZEKIEL by id(16999)
        val AMBIENCE_EZEKIEL_FINALE by id(17000)
        // Music
        val MUSIC_LOBBY by id(17001)
        val MUSIC_GUARDIAN by id(17002)
        val MUSIC_EZEKIEL by id(17003)
        val MUSIC_BORK by id(17004)
        val MUSIC_DAGONHAI by id(17005)
        val MUSIC_DREADNOTH by id(17006)
        val MUSIC_MOIA by id(17007)
        val MUSIC_XARTH by id(17008)
        val MUSIC_OCELLUS by id(17009)
        val MUSIC_OCELLUS_P3 by id(17010)
        val TABLE_THEMES by ids(17011..17013)      // client only
        /** Living record transmog, tiers 1..5 (item_transmog group 28). */
        val CAPE_TRANSMOG by ids(17014..17018)
        /** Charge rows: folio, Obligator, Zorya's tome. */
        val CHARGES by ids(17019..17021)
        /** drop_instant_reveal rows: cox, tob, toa, yama, tfa. */
        val DROP_INSTANT_REVEAL by ids(17022..17026)
        val DROP_INSTANT_REVEAL_TFA by id(17026)
        val MINIQUEST by id(17027)
        /** Ezekiel sword patterns and finale tables (values stripped). */
        val EZEKIEL_PATTERNS by ids(17028..17050)
        val EZEKIEL_FINALE_LEFT by id(17048)
        val EZEKIEL_FINALE_RIGHT by id(17049)
        val EZEKIEL_FINALE_FRONT by id(17050)
        val OCELLUS_BARRAGES by ids(17051..17053)  // values stripped
        // Room entry config (dbtable 259, stripped)
        val ENTRY_GUARDIAN by id(17054)
        val ENTRY_EZEKIEL by id(17055)
        val ENTRY_XARTH by id(17056)
        val ENTRY_DAGONHAI by id(17057)
        val ENTRY_BORK by id(17058)
        val ENTRY_MOIA by id(17059)
        val ENTRY_DREADNOTH by id(17060)
        val ENTRY_OCELLUS by id(17061)
        val NEXUS_DOORS by ids(17062..17064)       // stripped
        // Drop tables (server-only, values stripped)
        val DROPTABLE_UNIQUE by ids(17065..17071)
        val DROPTABLE_FOLIO by ids(17072..17074)
        val DROPTABLE_MOTES by ids(17075..17080)
        val DROPTABLE_RESOURCES by ids(17081..17120)
        /** combat_dynamic_anims: crossbows 17121-17124, Obligator 17125-17132 (task #50). */
        val COMBAT_ANIMS by ids(17121..17132)
        // Stats categories (dbtable 264)
        val STATS_OVERALL by id(17133)
        val STATS_GUARDIAN by id(17134)
        val STATS_BORK by id(17135)
        val STATS_DAGONHAI by id(17136)
        val STATS_EZEKIEL by id(17137)
        val STATS_MOIA by id(17138)
        val STATS_OCELLUS by id(17139)
        val STATS_DREADNOTH by id(17140)
        val STATS_XARTH by id(17141)
        // Stats rows (dbtable 265)
        val STATS_ROW_ATTEMPTS by id(17142)
        val STATS_ROW_COMPLETIONS by id(17143)
        val STATS_ROW_COMPLETIONS_PRACTICE by id(17144)
        val STATS_ROW_DEATHS by id(17145)
        val STATS_ROW_WIPES by id(17146)
        val STATS_ROW_FAILURES by id(17147)
        val STATS_ROW_TIME by id(17148)
        // Alliance Supplies (omnishop)
        val SUPPLY_CURRENCY by id(17149)
        val SHOP_NORMAL by id(17150)
        val SHOP_FAILURE by id(17151)
        val STOCK_NOTE by id(17152)                // view-only, not buyable
        val STOCK_LEFTOVERS by id(17153)
        val STOCK_GIANT_KRILL by id(17154)
        val STOCK_MANTA_RAY by id(17155)
        val STOCK_HALIBUT by id(17156)
        val STOCK_PRAYER_POTION_2 by id(17157)
        val STOCK_PRAYER_POTION_4 by id(17158)
        val STOCK_SARADOMIN_BREW_4 by id(17159)
        val STOCK_SUPER_RESTORE_4 by id(17160)
        val STOCK_RANGING_POTION_2 by id(17161)
        val STOCK_SUPER_COMBAT_2 by id(17162)
        val SKILL_FEATURES by ids(17163..17169)    // client only
        val ALTERNATE_PRAYERS by ids(17172..17174) // task #50 (Zeal)
        val HISCORES by id(17175)
        val PING_STYLE by id(17176)
        val PING_PULSE by id(17177)
    }

    object Dbtable : IdGroup(IdKind.DBTABLE) {
        val DROP_INSTANT_REVEAL by id(135)
        val EZEKIEL_SWORD_PATTERNS by id(136)
        val EZEKIEL_CROSS by id(137)
        val EZEKIEL_FINALE_ROTATIONS by id(138)
        val EZEKIEL_FINALE_COMBOS by id(139)
        val EZEKIEL_FINALE_ATTACKS by id(140)
        val OCELLUS_BARRAGES by id(141)
        val ROOM_ENTRY_CONFIG by id(259)
        val NEXUS_DOORS by id(260)
        val DROP_TABLES by ids(261..263)
        val STATS_CATEGORY by id(264)
        val STATS_ROW by id(265)
        val COMBAT_DYNAMIC_ANIMS by ids(266..269)
        val ALTERNATE_PRAYERS by id(270)
    }

    object Enums : IdGroup(IdKind.ENUM) {
        val PET_FORMS by id(6016)                  // param 446 on the pet items
        val COLLECTION_LOG_ITEMS by id(6017)
        val PARTY_MODES by id(6051)                // 0 Normal, 1 Practice
        val PARTY_DETAILS_PAUSE_MAP by id(6057)
        val PARTY_LIST_ROWS by id(6058)            // 45 parties
        val PARTY_LIST_BUTTONS by id(6059)
        val STATS_TAB_ORDER by id(6066)
        val COMBAT_STYLE_STRUCTS by ids(6076, 6077)
        /** Server-only enums, empty in the client: rebuild in JSON where needed. */
        val SERVER_ONLY by ids(
            *(6018..6050).toList().toIntArray(), *(6052..6056).toList().toIntArray(),
            *(6060..6065).toList().toIntArray(), *(6067..6075).toList().toIntArray(), 6078, 6079,
        )
        // Stock enums
        val PETS by id(985)                        // index 71
        val CLOG_RAIDS_TAB by id(2104)             // index 1
        val KC_NAMES by ids(3970, 3971)            // index 26
        val CAPE_RACK by ids(3292, 3293)           // 70-74
    }

    object Struct : IdGroup(IdKind.STRUCT) {
        val CA_BOSS by id(1216)                    // proposed remap -> 13000; "Immunities - Poison, Venom"
        val CLOG by id(1220)                       // proposed remap -> 13004
        val CA_PLACEHOLDERS by ids(1219, *(1221..1232).toList().toIntArray(), 1234)
        val COMBAT_STYLES by ids(3721..3728)       // task #50
    }

    object Param : IdGroup(IdKind.PARAM) {
        val ATTACK_SPEED by id(14)
        val PET_ENUM by id(446)
        val NPC_HUD_NAME by id(510)
        val HPBAR_PERCENT_TEXT by id(2289)
        /** HP HUD extra-bar params 2762-2769. */
        val HPBAR_EXTRA by ids(2762..2769)
    }

    object Headbar : IdGroup(IdKind.HEADBAR) {
        val CHARGE_70 by id(85)                    // yellow
        val SHIELD_60 by id(86)                    // cyan
        val BLOOD_45 by id(87)
        val HEAT_60 by id(88)                      // orange
        val SHIELD_30 by id(89)                    // cyan, new and unassigned
    }

    object Hitsplat : IdGroup(IdKind.HITSPLAT) {
        val BLEED by id(67)
        val BURN by id(74)
    }

    /** Sprite groups the raid uses. */
    object Sprite : IdGroup(IdKind.SPRITE) {
        /** NPC head icons; indexes in [HeadIcon]. */
        val HEAD_ICONS by id(440)
    }

    /** Indexes in [Sprite.HEAD_ICONS] (E13). */
    object HeadIcon {
        const val PROTECT_FROM_MELEE = 0
        const val SOUL_SPLIT = 11
        const val DEFLECT_MELEE = 12
        const val DEFLECT_RANGE = 13
        const val DEFLECT_MAGE = 14
    }

    object Midi : IdGroup(IdKind.MIDI) {
        val GUARDIAN by id(887)
        val BORK by id(888)
        val DAGONHAI by id(889)
        val EZEKIEL by id(890)
        val LOBBY by id(891)                       // "Into the Archive"
        val MOIA by id(892)
        val OCELLUS by id(893)                     // P1/P2, "Artifacts of Old"
        val OCELLUS_P3 by id(894)                  // P3/P4, "Ascension"
        val DREADNOTH by id(895)
        val XARTH by id(896)
    }

    /** Music unlock positions (varp index, bit) from the §3.7 table. Not cache ids. */
    object MusicUnlock {
        const val VARP_INDEX = 27
        const val LOBBY_BIT = 22
        const val GUARDIAN_BIT = 23
        const val EZEKIEL_BIT = 24
        const val BORK_BIT = 25
        const val DAGONHAI_BIT = 26
        const val DREADNOTH_BIT = 27
        const val MOIA_BIT = 28
        const val XARTH_BIT = 29
        const val OCELLUS_BIT = 30
        const val OCELLUS_P3_BIT = 31
    }

    object Jingle : IdGroup(IdKind.JINGLE) {
        val COMPLETION by id(886)                  // INF: raid completion
    }

    object Synth : IdGroup(IdKind.SYNTH) {
        val STATS_MESSAGE by id(2277)              // played by scripts 10110 / 10115
        val UNIQUE_CHEST_OPEN by ids(12840, 12841)
    }

    object Ambience : IdGroup(IdKind.AMBIENCE) {
        val EZEKIEL_FINALE by id(11)
        val XARTH by id(12)
        /** Generic archive soundscapes for the other rooms, the nexus, the lobby and the vault. */
        val GENERIC by ids(13..21)
    }

    object Model : IdGroup(IdKind.MODEL) {
        val CA_BOSS by id(62570)                   // struct 1216 boss entry, anim 14745
        val ALLIANCE_RECORDS by id(62420)          // also Merit scroll 62774
        val CHEST_UNIQUE_ART by id(62364)          // 973:3
        val CHEST_RIFT_ART by id(62366)            // 973:4
        val ARCHIVE_TABLE by id(30319)             // loc 62776
        val HELPER_16689 by id(3023)
        val OCELLUS_BARRIER_SEGMENTS by ids(62102..62104)
    }

    /** Non-id numbers the spec names next to the ids. */
    object Misc {
        const val MINIQUEST_QUEST_ID = 217
        const val MINIQUEST_ENDSTATE = 8
        const val CA_KC_ID = 26                    // also the index in KC-name enums 3970/3971
        const val ITEM_TRANSMOG_GROUP = 28         // living record "Change-style"
        const val HISCORES_ICON_FRAME = 72         // icon_boss_25x25
        const val ROOM_ICON_FRAME_FIRST = 72       // icon_boss_25x25 frames 72-80, one per stats category
        const val MAX_PARTIES_LISTED = 45          // enum 6058
        const val MAX_APPLICANTS = 7
        const val MAX_PARTY_SIZE = 8
    }
}

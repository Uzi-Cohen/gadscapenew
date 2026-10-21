# Build 241 interface remap (211 -> 241)

Generated for the rev-241 test build (branch `claude/rev241-content`). It maps every component id the server
uses on the 211 cache to the component on the vanilla build-241 cache (OpenRS2 cache 2746), lists the
clientscripts and varbits whose meaning changed, and records what was changed in code. GadScape's custom
interfaces (ids that are not in the vanilla 211 cache) are out of scope.

Machine-readable table: `docs/rev241/interface-remap.tsv` (one row per 211 component).

## How the table was built

Inputs: Joshua-F/osrs-dumps commit `c43ec08` (2023-03-01, rev 211; its per-interface component counts match
GadScape's vanilla 211 cache exactly) and commit `6e5033a` (2026-10-20, rev 241, = OpenRS2 cache 2746), which
carries the 241 gamevals names for interfaces and components.

1. **Structural alignment.** For each interface, the 211 and 241 component lists are aligned in id order
   (Needleman-Wunsch style dynamic programming). Two components are compared on type, geometry, text,
   graphic, ops and hook scripts (script names and literal arguments; component references are compared by
   interface only). Ties go to the earliest 241 component, so components Jagex inserted after a sibling
   (e.g. `maincrm` after `mainmodal`) do not steal the old id.
2. **Anchors.** Pairs that are known independently are fixed before the alignment:
   - top-level panes 548/164/165/601 from the remap enums 1129/1131/1132/1745 (both revisions map
     161 components to the other panes, so a verified 161 mapping fixes the others);
   - component references in the same lines of the same clientscript in both revisions, and in component
     valued enums with the same keys (4,050 components); and hook-argument lists (e.g. `bankmain_init`);
   - semantic anchors: prayer slots from `enum_859` (211) vs `oc_param(enum_4956[p], param_1751)` (241), and
     hand-verified rows for the bank, bank side panel, settings side panel, music, combat, skillmulti, orbs and
     journal (marked `anchored` below).
3. **Confidence.** `high`: identical component, or same type/geometry/parent with only ops or handlers changed,
   or the same onload script call with the same literal arguments, or anchored. `medium`: similar but not
   proven. `low`: no 241 counterpart found (usually removed or redesigned).

**Verification.** Mapping the 211 entries of the pane remap enums through the table reproduces the 241 enums
exactly: enum 1129 (79/79 entries), 1130 (79/79), 1131 (80/80), 1132 (36/36); enum 1745 (mobile) 81/83, the two
misses are 601-internal entries that no longer exist in 241. Over all 232 interfaces: 7075 component rows, 6404 high,
204 medium, 467 low.

## Changed in code (core game loop)

Only high-confidence or hand-verified rows were applied. 211 ids are kept in a `// 211: N` comment next to
every changed literal.

| Area | Files | What changed |
|---|---|---|
| Game frame | InterfacePosition | All resizable (161) seed components (+1 from side0, +2 from viewport_tracker_back); DIALOGUE 559 -> 568 (chatbox:chatmodal); COLOUR_PICKER 8 -> 9 (settings:popup). Other panes still resolve through the 241 remap enums. PaneType/GameTab unchanged (GameTab.tabIds is unused). |
| Pane buttons | Fixed/Resizable/SidePanelsResizable/MobilePaneInterface | 548, 161, 164, 601 stone buttons and gravestone moved by +1/+2. |
| Account tab | InterfaceHandler.sendGameFrame | If the custom tab 1702 is absent (vanilla 241 cache) the vanilla account tab 109 opens instead. |
| Custom interfaces | GameInterface.open, GameNoticeboardInterface.onLogin | Interfaces that are not in the cache are not opened and their plugin open() is skipped, so no custom clientscripts (10001+ now collide with real 241 scripts up to 10196) or custom component packets are sent at login. |
| Combat 593 | CombatTabInterface, CombatDefinitions | Styles 4/8/12/16 -> 6/10/14/18, autocast 21/26 -> 23/28, retaliate 30 -> 32, special 36 -> 41 plus the new main/off-hand bars 57/67; weapon name text 1 -> 3, category 2 -> 5. |
| Prayer 541 | PrayerTab | Prayers moved to components 9..37 and Hawk Eye/Eagle Eye/Mystic Will/Mystic Lore were regrouped; a component -> slot table replaces componentId - 5. |
| Spellbook 218 | SpellbookInterface | Filter menu 195 -> 206, house teleport 29 -> 34. Spell components come from the cache (item param 596). |
| Skills 320 | SkillsTabInterface | Skill buttons unchanged; guard for the new Sailing button (24). |
| Settings 116 | SettingsTabInterface | Redesigned tab: volume sliders 41/55/69 -> 108/122/136 (music/sound/area bobble containers), mute icons 30/44/58 -> 110/124/138, attack-option dropdowns 81/82 -> 38/39, bottom toggles 72-76 -> 29-33, zoom 87 -> 43. |
| Settings 134 | SettingsInterface | 10/19/21/23/25/28 -> 11/20/22/24/26/29. |
| Music 239 | MusicPlayerInterface | jukebox 5 -> 11, now playing 8 -> 4, Auto/Manual 9/11 -> Area/Single mode 14/16. |
| Bank 12 | BankInterface, Bonuses, GameCommands | Bottom bar and menu redesigned: Swap/Insert and Item/Note became single toggles (23, 25) bound as toggles; quantities 28-36 -> 29-37; placeholders 38 -> 40; search 40 -> 42; deposit inv/worn 42/44 -> 47/49; tabs 11 -> 10; items 13 -> 12; menu toggles and fillers -> 129-153; worn-item stat texts 90-108 -> 82-100. Tab drop-target slots are now relative to the bank size (241 bank has 1410 slots, 211 had 1200). |
| Bank side 15 | BankInventoryInterface | Looting bag deposit 8 -> 6, items 13 -> 11. |
| Dialogues | PlainMessage, LevelUpMessage, SkillMessage, SkillDialogueInterface | messagebox text/continue 1/2 -> 3/4; level-up skill icon layers re-derived from 241 names (adds sailing); skillmulti item buttons 14.. -> 15.. with 18 buttons and 18 obj arguments for script 2046. npc/player/options/sprite/double-sprite dialogues (231/217/219/193/11) are unchanged. |
| Shops 300 | ShopInterface, GameShop | Script 1074 shop_main_init now (inv, title, obj, int, boolean): sent as (inv, name, -1, 0, 1). |
| Orbs 160 | OrbsInterface | All buttons +1 (241 inserted 'triggers' at 1): xp 5 -> 6, health 8 -> 9, prayer 19 -> 20, run 27 -> 28, spec 35 -> 36, store 45 -> 46, world map 53 -> 55. |
| Journal 629 | JournalHeaderTabInterface + 6 journal panels | Tabs 3/8/13/28 -> 2/10/18/34, content container 33 -> 43. The custom 'Game Noticeboard' tab has no 241 counterpart. |
| Chat channel tabs 707, grouping 76 | RegularChatChannelInterface, GroupingTabInterface, MinigameTabInterface | 707 tabs 3-6 -> 2-5; grouping teleport 26 -> 28. |
| Unchanged |  | 149 inventory, 387 worn items, 84 equipment stats (stat texts and slots), 216 emotes, 429/432 friends/ignore, 7/701/702 clan panels, 182 logout, 122 XP drops, 77 quick prayers, 231/217/219/193/11 dialogues, 301 shop side. |
| Enums | EnumDefinitions, Enums | New getIntEnumOrEmpty/getStringEnumOrEmpty: Enums constants that are missing or retyped in 241 (819/812 music, 273/834/836/854/824 slayer and fairy rings, custom 10024/10053-10056) no longer throw during class initialisation. |
| Varbits | SettingVariables, SettingsInterface, VarCollection, Player, dialogues | See below. |

## Clientscripts

Compared by script header in both dumps (all 207 literal script ids the server sends).

| Script | 211 name | 241 name | 211 args | 241 args | Status |
|---|---|---|---|---|---|
| 417 | chat_set_filter_conditional | script417 | int x3 | stat | TODO: not in the core list |
| 450 | rareitems_diango | rareitems_diango | int x6, string | string, int x8 | TODO: not in the core list |
| 603 | poll_initialise | camera_set_zoom_limits | string, component x4, string, int | int x4 | TODO: id now belongs to a different script; do not send |
| 604 | poll_setbutton | camera_set_zoom_limits | component x2, string | int x4 | TODO: id now belongs to a different script; do not send |
| 609 | script609 | script609 | string, int x2, graphic, fontmetrics, int x2, component | int x3 | TODO: not in the core list |
| 610 | script610 | abbreviate_tostring_normal | string x2, component | int, string | TODO: not in the core list |
| 618 | poll_conclude | button_flat | component x3, boolean | component, int | TODO: id now belongs to a different script; do not send |
| 619 | poll_voting_addquestion_full | button_flat | int x2, string | component, int | TODO: id now belongs to a different script; do not send |
| 620 | poll_voting_addquestion_refresh | healthbar_draw | int x2 | component, int x3 | TODO: id now belongs to a different script; do not send |
| 624 | poll_results_addquestion_full | healthbar_draw_hp_full | int x2, string, int x34 | component, int x9 | TODO: id now belongs to a different script; do not send |
| 627 | poll_history_setup | sailing_intro_hint | int, string x2 | int | TODO: id now belongs to a different script; do not send |
| 785 | ge_pricechecker_prices | ge_pricechecker_prices | int x28 | long x28 | TODO: 241 takes longs (packed values); call sites not changed |
| 1074 | shop_main_init | shop_main_init | inv, obj, int, string | inv, string, obj, int, boolean | fixed (ShopInterface, GameShop) |
| 1119 | farming_view_setpanel | farming_view_setpanel | int, obj, coord, int | int, obj, coord, int, string | TODO: not in the core list |
| 1216 | script1216 | script1216 | int x28 | long x28 | TODO: 241 takes longs (packed values); call sites not changed |
| 1217 | script1217 | script1217 | int x28 | long x28 | TODO: 241 takes longs (packed values); call sites not changed |
| 1235 | wilderness_lootingbag_setprices | wilderness_lootingbag_setprices | inv, int x28 | inv, long x28 | TODO: 241 takes longs (packed values); call sites not changed |
| 1404 | poh_furniture_creation_entry | poh_furniture_creation_entry | int, obj, int, string, boolean | int, dbrow, int, string, boolean | TODO: not in the core list |
| 1584 | killcounts | seed_vault_build_drag_item | int, string x4 | component, int | TODO: id now belongs to a different script; do not send |
| 1645 | ge_history_addline | ge_history_addline | int, obj, int x4 | int, obj, int x2, long x2 | TODO: 241 takes longs (packed values); call sites not changed |
| 2046 | skillmulti_setup | skillmulti_setup | int, string, int, obj x10, int | int, string, int, obj x18, int | fixed (SkillMessage) |
| 2180 | deadman_spectator_single | deadman_spectator_single | int x9, string x2 | int x14, string x2 | TODO: not in the core list |
| 2494 | skill_guide_subsection_prayer | script2494 | int | int x4 | TODO: not in the core list |
| 4472 | side_channels_setall | side_channels_setall | component x5 | component x6 | TODO: not in the core list |
| 6126 | pet_insurance_init | pet_insurance_init | int x11 | int x15 | TODO: not in the core list |

Custom GadScape scripts (ids 10001-10912) are not in the vanilla cache; 241 already uses ids up to 10196, so
custom ids 10001-10196 now run unrelated vanilla scripts if sent. The custom interfaces that send them are
not opened on the 241 cache (see GameInterface.open).

**Note for the NET track (RUNCLIENTSCRIPT with long arguments).** rsprot 241's `RunClientScript` infers the type
char from the value: `Int` -> 'i', `String` -> 's', `Long` -> 'Ï' (written with p8), `IntArray` -> 'W',
`Array<String>` -> 'X'. `PacketDispatcher.sendClientScript(int, Object...)` should therefore pass `java.lang.Long`
arguments through unchanged (the old 211 encoder casts every non-String to Integer). Scripts that take longs on
241: 785 ge_pricechecker_prices, 1216/1217 (trade values), 1235 wilderness_lootingbag_setprices, 1645
ge_history_addline (last two). The client reads arguments by type in order (ints, strings and longs are
separate stacks), so only the order within each type matters.

## Varps and varbits

Varps: none of the 3,775 varps of 211 changed type or was deleted in 241. Varbits: 1729 of 14,795 211 varbits have
a different base varp or bit range in 241. Because the server resolves varbits through the cache definitions, a
moved varbit is fine as long as its id kept its meaning; the ones below were reused for something else.

| Varbit | Server use | 241 meaning of the id | Action |
|---|---|---|---|
| 5542 | SHIFT_CLICK_TO_DROP_ITEMS | varplayerbit_5542 (unrelated) | -> 11556 desktop_shiftclickdrop_enabled (same bits as 211's 5542) |
| 12377 | SHOW_MOUSEOVER_TEXT | aerial_fishing_whole_fish_toggle | -> 10035 mouseover_text_disabled |
| 9638 / 9639 | settings search left/right | blast_furnace_visited / chromium_crafting_unlocked | -> 16073 floater_is_searching / 16074 floater_search_listen_for_keyboard |
| 5983 | DIALOGUE_FULL_SIZE, cleared on stopAll | settings_barbarian_potion_makex | -> 10670 chatmodal_unclamp (varp 1021 was reorganised; inferred from the name) |
| 9666 | MUSIC_VOLUME_MUTED (saved music volume) | mourning_teleport_destination_toggle | TODO: 241 stores it as option_music_saved (12426)? not changed |
| 4371 / 4372 | skill guide skill / subsection | option_level_up_popup_list / unnamed | TODO: the 241 skill guide is skill_guide_v2 (varp 965); not in the core list |
| 5362 / 6251 | Bigger and Badder / Stop the Wyvern rewards | unnamed / poison_type | TODO: 241 has slayer_toggleoff_superiormobs (14825) / slayer_toggleoff_fossilwyvernblock (14824) on the old bits |
| 12233 | MUSIC_AREA_MODE | music_area_mode (now 2 bits) | kept |
| 6254 | MINIMIZE_MINIMAP | osm_minimap_toggle | kept |

Attack style (varp 43, varbit 357), run (varp 173), special attack (varps 300/301), autocast (varbits 276/2668),
quick prayers, bank settings (varbits 3958/3959/3755/5102), XP drop and orb settings keep their ids and meaning.

## Not done / approximated

- Settings side tab: the client layout dropdown (211 component 84) became a generic 'dynamic setting' dropdown (241 display_dynamic_setting_1, 40); not rebound. The music-unlock-message toggle (211: 71) moved to the settings interface.
- Music: the jukebox slot order in 241 comes from the music dbtable (enum 812 is empty in 241), so slot -> song can be wrong; there is no loop button.
- Advanced settings (134): component ids are remapped, but the setting structs/categories (enums 422/423, structs) changed in 241 and were not checked.
- Spellbook filters: the filter menu slots (0..6) were not re-verified against 241's dynamic menu.
- Mobile pane (601) was restructured; the mapping is enum-anchored but mobile was not otherwise checked.
- Polls (scripts 603-627), POH costumes (417), kill counts (1584): those script ids now belong to other scripts in 241; the callers were not changed.
- Trade/price checker/looting bag/GE history scripts take packed longs in 241 (see the NET note); call sites unchanged.
- Welcome screen/lobby (378) is unused by the server and was not changed.
- Everything outside the core list below keeps 211 ids; use the table to port it.

## Interface table

Per interface: component counts, confidence totals, then every row whose id changes or is not high
confidence (rows not listed are unchanged, high confidence). `-1` = no 241 counterpart. Core interfaces first.

### 548 toplevel (FIXED_PANE)

211: 93 components, 241: 96. Moved: 87. High 93, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 6 | 7 | high | com_7 |  |
| 7 | 8 | high | com_8 |  |
| 8 | 9 | high | mapcontainer |  |
| 9 | 10 | high | main |  |
| 10 | 11 | high | chat_container |  |
| 11 | 12 | high | com_12 |  |
| 12 | 13 | high | com_13 |  |
| 13 | 14 | high | com_14 |  |
| 14 | 15 | high | side_bottom_container |  |
| 15 | 16 | high | side_top_container |  |
| 16 | 17 | high | side |  |
| 17 | 18 | high | com_18 |  |
| 18 | 19 | high | com_19 |  |
| 19 | 20 | high | com_20 |  |
| 20 | 21 | high | com_21 |  |
| 21 | 22 | high | minimap |  |
| 22 | 23 | high | com_23 |  |
| 23 | 24 | high | compassclick |  |
| 24 | 25 | high | orbs |  |
| 25 | 26 | high | viewport |  |
| 26 | 27 | high | overlay_atmosphere |  |
| 27 | 28 | high | hpbar_hud |  |
| 28 | 29 | high | pvp_icons |  |
| 29 | 30 | high | helper |  |
| 30 | 31 | high | stat_boosts_hud |  |
| 31 | 32 | high | buff_bar |  |
| 32 | 33 | high | overlay_hud |  |
| 33 | 34 | high | xp_drops |  |
| 34 | 35 | high | zeah |  |
| 35 | 36 | high | pm_container |  |
| 36 | 37 | high | multiway_icon |  |
| 37 | 38 | high | helper_dodger |  |
| 38 | 39 | high | helper_content |  |
| 39 | 40 | high | gravestone |  |
| 40 | 41 | high | mainmodal |  |
| 41 | 43 | high | floater |  |
| 42 | 44 | high | notifications |  |
| 43 | 45 | high | debug |  |
| 44 | 46 | high | com_46 |  |
| 45 | 47 | high | side_bottom |  |
| 46 | 48 | high | stone7 |  |
| 47 | 49 | high | stone8 |  |
| 48 | 50 | high | stone9 |  |
| 49 | 51 | high | stone10 |  |
| 50 | 52 | high | stone11 |  |
| 51 | 53 | high | stone12 |  |
| 52 | 54 | high | stone13 |  |
| 53 | 55 | high | icon7 |  |
| 54 | 56 | high | icon8 |  |
| 55 | 57 | high | icon9 |  |
| 56 | 58 | high | icon10 |  |
| 57 | 59 | high | icon11 |  |
| 58 | 60 | high | icon12 |  |
| 59 | 61 | high | icon13 |  |
| 60 | 62 | high | com_62 |  |
| 61 | 63 | high | side_top |  |
| 62 | 64 | high | stone0 |  |
| 63 | 65 | high | stone1 |  |
| 64 | 66 | high | stone2 |  |
| 65 | 67 | high | stone3 |  |
| 66 | 68 | high | stone4 |  |
| 67 | 69 | high | stone5 | op2,transmitop,anchor,anchored |
| 68 | 70 | high | stone6 |  |
| 69 | 71 | high | icon0 |  |
| 70 | 72 | high | icon1 |  |
| 71 | 73 | high | icon2 |  |
| 72 | 74 | high | icon3 |  |
| 73 | 75 | high | icon4 |  |
| 74 | 76 | high | icon5 |  |
| 75 | 77 | high | icon6 |  |
| 76 | 78 | high | com_78 |  |
| 77 | 79 | high | sidemodal |  |
| 78 | 80 | high | side_panels |  |
| 79 | 81 | high | side0 |  |
| 80 | 82 | high | side1 |  |
| 81 | 83 | high | side2 |  |
| 82 | 84 | high | side3 |  |
| 83 | 85 | high | side4 |  |
| 84 | 86 | high | side5 |  |
| 85 | 87 | high | side6 |  |
| 86 | 88 | high | side7 |  |
| 87 | 89 | high | side8 |  |
| 88 | 90 | high | side9 |  |
| 89 | 91 | high | side10 |  |
| 90 | 92 | high | side11 |  |
| 91 | 93 | high | side12 |  |
| 92 | 94 | high | side13 |  |

New in 241: 6 ui_highlights, 42 maincrm, 95 sidecrm

### 161 toplevel_osrs_stretch (RESIZABLE_PANE)

211: 96 components, 241: 99. Moved: 79. High 96, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 17 | 18 | high | floater |  |
| 18 | 19 | high | debug |  |
| 19 | 20 | high | multiway_icon |  |
| 20 | 21 | high | gravestone | x,y |
| 21 | 22 | high | map_minimap |  |
| 22 | 23 | high | map_noclick_0 |  |
| 23 | 24 | high | map_noclick_1 |  |
| 24 | 25 | high | map_noclick_2 |  |
| 25 | 26 | high | map_noclick_3 |  |
| 26 | 27 | high | map_noclick_4 |  |
| 27 | 28 | high | map_noclick_5 |  |
| 28 | 29 | high | com_29 |  |
| 29 | 30 | high | minimap |  |
| 30 | 31 | high | compassclick |  |
| 31 | 32 | high | com_32 |  |
| 32 | 33 | high | orbs |  |
| 33 | 34 | high | gameframe |  |
| 34 | 35 | high | popout |  |
| 35 | 36 | high | tli_listener |  |
| 36 | 37 | high | mouseover |  |
| 37 | 38 | high | side_background |  |
| 38 | 39 | high | com_39 |  |
| 39 | 40 | high | com_40 |  |
| 40 | 41 | high | com_41 |  |
| 41 | 42 | high | side_bottom |  |
| 42 | 43 | high | stone7 |  |
| 43 | 44 | high | stone8 |  |
| 44 | 45 | high | stone9 |  |
| 45 | 46 | high | stone10 |  |
| 46 | 47 | high | stone11 |  |
| 47 | 48 | high | stone12 |  |
| 48 | 49 | high | stone13 |  |
| 49 | 50 | high | icon7 |  |
| 50 | 51 | high | icon8 |  |
| 51 | 52 | high | icon9 |  |
| 52 | 53 | high | icon10 |  |
| 53 | 54 | high | icon11 |  |
| 54 | 55 | high | icon12 |  |
| 55 | 56 | high | icon13 |  |
| 56 | 57 | high | com_57 |  |
| 57 | 58 | high | side_top |  |
| 58 | 59 | high | stone0 |  |
| 59 | 60 | high | stone1 |  |
| 60 | 61 | high | stone2 |  |
| 61 | 62 | high | stone3 |  |
| 62 | 63 | high | stone4 |  |
| 63 | 64 | high | stone5 | op2,transmitop,anchor,anchored |
| 64 | 65 | high | stone6 |  |
| 65 | 66 | high | icon0 |  |
| 66 | 67 | high | icon1 |  |
| 67 | 68 | high | icon2 |  |
| 68 | 69 | high | icon3 |  |
| 69 | 70 | high | icon4 |  |
| 70 | 71 | high | icon5 |  |
| 71 | 72 | high | icon6 |  |
| 72 | 73 | high | side_container |  |
| 73 | 74 | high | sidemodal |  |
| 74 | 75 | high | side_panels |  |
| 75 | 76 | high | side0 |  |
| 76 | 77 | high | side1 |  |
| 77 | 78 | high | side2 |  |
| 78 | 79 | high | side3 |  |
| 79 | 80 | high | side4 |  |
| 80 | 81 | high | side5 |  |
| 81 | 82 | high | side6 |  |
| 82 | 83 | high | side7 |  |
| 83 | 84 | high | side8 |  |
| 84 | 85 | high | side9 |  |
| 85 | 86 | high | side10 |  |
| 86 | 87 | high | side11 |  |
| 87 | 88 | high | side12 |  |
| 88 | 89 | high | side13 |  |
| 89 | 91 | high | viewport |  |
| 90 | 92 | high | viewport_tracker_back |  |
| 91 | 93 | high | pm_container |  |
| 92 | 94 | high | viewport_tracker_front |  |
| 93 | 95 | high | map_container |  |
| 94 | 96 | high | chat_container |  |
| 95 | 97 | high | side_menu |  |

New in 241: 17 maincrm, 90 sidecrm, 98 ui_highlights

### 164 toplevel_pre_eoc (SIDE_PANELS_RESIZABLE_PANE)

211: 95 components, 241: 98. Moved: 78. High 95, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 17 | 18 | high | floater |  |
| 18 | 19 | high | debug |  |
| 19 | 20 | high | multiway_icon |  |
| 20 | 21 | high | gravestone | x,y,anchor,anchored |
| 21 | 22 | high | map_minimap |  |
| 22 | 23 | high | map_noclick_0 |  |
| 23 | 24 | high | map_noclick_1 |  |
| 24 | 25 | high | map_noclick_2 |  |
| 25 | 26 | high | map_noclick_3 |  |
| 26 | 27 | high | map_noclick_4 |  |
| 27 | 28 | high | map_noclick_5 |  |
| 28 | 29 | high | com_29 |  |
| 29 | 30 | high | minimap |  |
| 30 | 31 | high | compassclick |  |
| 31 | 32 | high | com_32 |  |
| 32 | 33 | high | orbs |  |
| 33 | 34 | high | stone10 |  |
| 34 | 35 | high | icon10 |  |
| 35 | 36 | high | side_static_background |  |
| 36 | 37 | high | side_static |  |
| 37 | 38 | high | stone7 |  |
| 38 | 39 | high | stone8 |  |
| 39 | 40 | high | stone9 |  |
| 40 | 41 | high | stone11 |  |
| 41 | 42 | high | stone12 |  |
| 42 | 43 | high | stone13 |  |
| 43 | 44 | high | icon7 |  |
| 44 | 45 | high | icon8 |  |
| 45 | 46 | high | icon9 |  |
| 46 | 47 | high | icon11 |  |
| 47 | 48 | high | icon12 |  |
| 48 | 49 | high | icon13 |  |
| 49 | 50 | high | side_movable_background |  |
| 50 | 51 | high | side_movable |  |
| 51 | 52 | high | stone0 |  |
| 52 | 53 | high | stone1 |  |
| 53 | 54 | high | stone2 |  |
| 54 | 55 | high | stone3 |  |
| 55 | 56 | high | stone4 |  |
| 56 | 57 | high | stone5 | op2,transmitop,anchor,anchored |
| 57 | 58 | high | stone6 |  |
| 58 | 59 | high | icon0 |  |
| 59 | 60 | high | icon1 |  |
| 60 | 61 | high | icon2 |  |
| 61 | 62 | high | icon3 |  |
| 62 | 63 | high | icon4 |  |
| 63 | 64 | high | icon5 |  |
| 64 | 65 | high | icon6 |  |
| 65 | 66 | high | gameframe |  |
| 66 | 67 | high | popout |  |
| 67 | 68 | high | tli_listener |  |
| 68 | 69 | high | mouseover |  |
| 69 | 70 | high | side_background |  |
| 70 | 71 | high | sidemodal |  |
| 71 | 72 | high | side_panels |  |
| 72 | 73 | high | side0 |  |
| 73 | 74 | high | side1 |  |
| 74 | 75 | high | side2 |  |
| 75 | 76 | high | side3 |  |
| 76 | 77 | high | side4 |  |
| 77 | 78 | high | side5 |  |
| 78 | 79 | high | side6 |  |
| 79 | 80 | high | side7 |  |
| 80 | 81 | high | side8 |  |
| 81 | 82 | high | side9 |  |
| 82 | 83 | high | side10 |  |
| 83 | 84 | high | side11 |  |
| 84 | 85 | high | side12 |  |
| 85 | 86 | high | side13 |  |
| 86 | 88 | high | viewport |  |
| 87 | 89 | high | viewport_tracker_back |  |
| 88 | 90 | high | pm_container |  |
| 89 | 91 | high | viewport_tracker_front |  |
| 90 | 92 | high | map_container |  |
| 91 | 93 | high | chat_container |  |
| 92 | 94 | high | side_static_layer |  |
| 93 | 95 | high | side_movable_layer |  |
| 94 | 96 | high | side_container |  |

New in 241: 17 maincrm, 87 sidecrm, 97 ui_highlights

### 165 toplevel_display

211: 40 components, 241: 43. Moved: 25. High 40, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 15 | 16 | high | sidemodal |  |
| 16 | 17 | high | side0 |  |
| 17 | 18 | high | side1 |  |
| 18 | 19 | high | side2 |  |
| 19 | 20 | high | side3 |  |
| 20 | 21 | high | side4 |  |
| 21 | 22 | high | side5 |  |
| 22 | 23 | high | side6 |  |
| 23 | 24 | high | side7 |  |
| 24 | 25 | high | side8 |  |
| 25 | 26 | high | side9 |  |
| 26 | 27 | high | side10 |  |
| 27 | 28 | high | side11 |  |
| 28 | 29 | high | side12 |  |
| 29 | 30 | high | side13 |  |
| 30 | 32 | high | pvp_icons |  |
| 31 | 33 | high | pm_container |  |
| 32 | 34 | high | orbs |  |
| 33 | 35 | high | tli_listener |  |
| 34 | 36 | high | mouseover |  |
| 35 | 38 | high | control |  |
| 36 | 39 | high | blackout |  |
| 37 | 40 | high | displaymodal |  |
| 38 | 41 | high | floater |  |
| 39 | 42 | high | popout |  |

New in 241: 15 maincrm, 31 sidecrm, 37 ui_highlights

### 601 toplevel_osm (MOBILE_PANE)

211: 99 components, 241: 135. Moved: 77. High 88, medium 0, low 11.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 22 | 23 | high | viewport_tracker_front |  |
| 23 | 22 | high | map_container | width,anchored |
| 24 | 38 | high | wifi |  |
| 25 | 39 | high | battery |  |
| 26 | -1 | low |  | unmatched |
| 27 | 49 | high | chat_container |  |
| 28 | 50 | low | side_right_tabs | x,height,xmode,width |
| 29 | 61 | low | tabs_group_right | x,y,height,ymode,width,parent |
| 30 | 111 | high | side_container | x,noclickthrough,y,ymode,anchored |
| 31 | 24 | high | multiway_icon |  |
| 32 | 25 | high | gravestone |  |
| 33 | 26 | high | hud_container_front | x,width,anchored |
| 34 | 27 | high | mainmodal |  |
| 35 | 29 | high | floater |  |
| 36 | 30 | high | debug |  |
| 37 | -1 | low |  | unmatched |
| 38 | 85 | low | tabs_blocking | x,y,height,width,heightmode,parent |
| 39 | -1 | low |  | unmatched |
| 40 | 34 | high | minimap | parent,anchored |
| 41 | 35 | high | compassclick | parent,anchored |
| 42 | -1 | low |  | unmatched |
| 43 | 37 | high | orbs | y,height,width,widthmode,anchored |
| 44 | -1 | low |  | unmatched |
| 45 | -1 | low |  | unmatched |
| 46 | -1 | low |  | unmatched |
| 47 | 96 | high | tabs_left | height,width,heightmode,parent,anchored |
| 48 | 101 | high | stone0 | y,graphic,height,width,anchored |
| 49 | 99 | high | stone5 | y,graphic,height,width,anchored |
| 50 | 100 | high | stone6 | y,graphic,height,width,anchored |
| 51 | 72 | high | stone12 | y,graphic,height,width,parent,anchored |
| 52 | 74 | high | stone7 | y,graphic,height,width,parent,anchored |
| 53 | 76 | high | stone8 | y,graphic,height,width,parent,anchored |
| 54 | 75 | high | stone9 | y,graphic,height,width,parent,anchored |
| 55 | 108 | high | icon0 | y,anchored |
| 56 | 106 | high | icon5 | y,anchored |
| 57 | 107 | high | icon6 | y,anchored |
| 58 | 79 | high | icon12 | y,parent,anchored |
| 59 | 81 | high | icon7 | y,parent,anchored |
| 60 | 83 | high | icon8 | y,parent,anchored |
| 61 | 82 | high | icon9 | y,parent,anchored |
| 62 | -1 | low |  | unmatched |
| 63 | 70 | high | tabs_right | height,width,heightmode,anchored |
| 64 | 97 | high | stone3 | graphic,height,width,parent,anchored |
| 65 | 98 | high | stone4 | graphic,height,width,parent,anchored |
| 66 | 71 | high | stone1 | y,graphic,height,width,anchored |
| 67 | 102 | high | stone2 | y,graphic,height,width,parent,anchored |
| 68 | 73 | high | stone13 | y,graphic,height,width,anchored |
| 69 | 77 | high | stone11 | y,graphic,height,width,anchored |
| 70 | 44 | high | stone10 | y,graphic,height,width,parent,anchored |
| 71 | 104 | high | icon3 | y,parent,anchored |
| 72 | 105 | high | icon4 | y,parent,anchored |
| 73 | 78 | high | icon1 | y,anchored |
| 74 | 109 | high | icon2 | y,parent,anchored |
| 75 | 80 | high | icon13 | y,anchored |
| 76 | 84 | high | icon11 | y,anchored |
| 77 | 45 | high | icon10 | y,graphic,ymode,parent,anchored |
| 78 | 112 | high | side_container_warning |  |
| 79 | 113 | high | side_background |  |
| 80 | 114 | high | sidemodal |  |
| 81 | 115 | high | side_panels |  |
| 82 | 116 | high | side0 |  |
| 83 | 117 | high | side1 |  |
| 84 | 118 | high | side2 |  |
| 85 | 119 | high | side3 |  |
| 86 | 120 | high | side4 |  |
| 87 | 121 | high | side5 |  |
| 88 | 122 | high | side6 |  |
| 89 | 123 | high | side7 |  |
| 90 | 124 | high | side8 |  |
| 91 | 125 | high | side9 |  |
| 92 | 126 | high | side10 |  |
| 93 | 127 | high | side11 |  |
| 94 | 128 | high | side12 |  |
| 95 | 129 | high | side13 |  |
| 96 | 131 | high | popout | y,height,ymode,xmode,anchored |
| 97 | 132 | high | mouseover |  |
| 98 | 133 | high | tli_listener |  |

New in 241: 28 maincrm, 31 map_minimap, 32 map_noclick, 33 com_33, 36 com_36, 40 hotkeys, 41 side_left_chat, 42 logout_container, 43 stone10_background, 46 chat_toggle, 47 chat_filter_toggle, 48 chatting_button, 51 side_right_tabs_background, 52 top_left, 53 top_right, 54 top_middle, 55 middle_left, 56 middle_middle, 57 middle_right, 58 bottom_left, 59 bottom_right, 60 bottom_middle, 62 side_left_background, 63 stone14, 64 stone15, 65 stone16, 66 stone17, 67 stone18, 68 stone19, 69 stone20, 86 com_86, 87 tabs_group_left, 88 side_right_background, 89 stone21, 90 stone22, 91 stone23, 92 stone24, 93 stone25, 94 stone26, 95 stone27, 103 expand_stone, 110 expand_icon, 130 sidecrm, 134 ui_highlights

### 162 chatbox (CHAT)

211: 560 components, 241: 569. Moved: 525. High 560, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 35 | 37 | high | chat_background |  |
| 36 | 38 | high | input_clickarea |  |
| 37 | 39 | high | mes_layer |  |
| 38 | 40 | high | mes_layer_keyboard_show |  |
| 39 | 41 | high | mes_layer_close |  |
| 40 | 42 | high | close_icon |  |
| 41 | 43 | high | mes_text |  |
| 42 | 44 | high | mes_text2 |  |
| 43 | 45 | high | mes_layer_button_1 |  |
| 44 | 46 | high | mes_layer_button_2 |  |
| 45 | 47 | high | mes_layer_button_3 |  |
| 46 | 49 | high | mes_layer_scrollarea |  |
| 47 | 50 | high | com_50 |  |
| 48 | 51 | high | com_51 |  |
| 49 | 52 | high | com_52 |  |
| 50 | 53 | high | mes_layer_scrollcontents |  |
| 51 | 54 | high | mes_layer_scrollbar |  |
| 52 | 55 | high | mes_layer_toggle |  |
| 53 | 56 | high | mes_layer_hide |  |
| 54 | 57 | high | chatdisplay |  |
| 55 | 58 | high | input |  |
| 56 | 59 | high | scrollarea |  |
| 57 | 60 | high | line0 |  |
| 58 | 61 | high | line1 |  |
| 59 | 62 | high | line2 |  |
| 60 | 63 | high | line3 |  |
| 61 | 64 | high | line4 |  |
| 62 | 65 | high | line5 |  |
| 63 | 66 | high | line6 |  |
| 64 | 67 | high | line7 |  |
| 65 | 68 | high | line8 |  |
| 66 | 69 | high | line9 |  |
| 67 | 70 | high | line10 |  |
| 68 | 71 | high | line11 |  |
| 69 | 72 | high | line12 |  |
| 70 | 73 | high | line13 |  |
| 71 | 74 | high | line14 |  |
| 72 | 75 | high | line15 |  |
| 73 | 76 | high | line16 |  |
| 74 | 77 | high | line17 |  |
| 75 | 78 | high | line18 |  |
| 76 | 79 | high | line19 |  |
| 77 | 80 | high | line20 |  |
| 78 | 81 | high | line21 |  |
| 79 | 82 | high | line22 |  |
| 80 | 83 | high | line23 |  |
| 81 | 84 | high | line24 |  |
| 82 | 85 | high | line25 |  |
| 83 | 86 | high | line26 |  |
| 84 | 87 | high | line27 |  |
| 85 | 88 | high | line28 |  |
| 86 | 89 | high | line29 |  |
| 87 | 90 | high | line30 |  |
| 88 | 91 | high | line31 |  |
| 89 | 92 | high | line32 |  |
| 90 | 93 | high | line33 |  |
| 91 | 94 | high | line34 |  |
| 92 | 95 | high | line35 |  |
| 93 | 96 | high | line36 |  |
| 94 | 97 | high | line37 |  |
| 95 | 98 | high | line38 |  |
| 96 | 99 | high | line39 |  |
| 97 | 100 | high | line40 |  |
| 98 | 101 | high | line41 |  |
| 99 | 102 | high | line42 |  |
| 100 | 103 | high | line43 |  |
| 101 | 104 | high | line44 |  |
| 102 | 105 | high | line45 |  |
| 103 | 106 | high | line46 |  |
| 104 | 107 | high | line47 |  |
| 105 | 108 | high | line48 |  |
| 106 | 109 | high | line49 |  |
| 107 | 110 | high | line50 |  |
| 108 | 111 | high | line51 |  |
| 109 | 112 | high | line52 |  |
| 110 | 113 | high | line53 |  |
| 111 | 114 | high | line54 |  |
| 112 | 115 | high | line55 |  |
| 113 | 116 | high | line56 |  |
| 114 | 117 | high | line57 |  |
| 115 | 118 | high | line58 |  |
| 116 | 119 | high | line59 |  |
| 117 | 120 | high | line60 |  |
| 118 | 121 | high | line61 |  |
| 119 | 122 | high | line62 |  |
| 120 | 123 | high | line63 |  |
| 121 | 124 | high | line64 |  |
| 122 | 125 | high | line65 |  |
| 123 | 126 | high | line66 |  |
| 124 | 127 | high | line67 |  |
| 125 | 128 | high | line68 |  |
| 126 | 129 | high | line69 |  |
| 127 | 130 | high | line70 |  |
| 128 | 131 | high | line71 |  |
| 129 | 132 | high | line72 |  |
| 130 | 133 | high | line73 |  |
| 131 | 134 | high | line74 |  |
| 132 | 135 | high | line75 |  |
| 133 | 136 | high | line76 |  |
| 134 | 137 | high | line77 |  |
| 135 | 138 | high | line78 |  |
| 136 | 139 | high | line79 |  |
| 137 | 140 | high | line80 |  |
| 138 | 141 | high | line81 |  |
| 139 | 142 | high | line82 |  |
| 140 | 143 | high | line83 |  |
| 141 | 144 | high | line84 |  |
| 142 | 145 | high | line85 |  |
| 143 | 146 | high | line86 |  |
| 144 | 147 | high | line87 |  |
| 145 | 148 | high | line88 |  |
| 146 | 149 | high | line89 |  |
| 147 | 150 | high | line90 |  |
| 148 | 151 | high | line91 |  |
| 149 | 152 | high | line92 |  |
| 150 | 153 | high | line93 |  |
| 151 | 154 | high | line94 |  |
| 152 | 155 | high | line95 |  |
| 153 | 156 | high | line96 |  |
| 154 | 157 | high | line97 |  |
| 155 | 158 | high | line98 |  |
| 156 | 159 | high | line99 |  |
| 157 | 160 | high | line100 |  |
| 158 | 161 | high | line101 |  |
| 159 | 162 | high | line102 |  |
| 160 | 163 | high | line103 |  |
| 161 | 164 | high | line104 |  |
| 162 | 165 | high | line105 |  |
| 163 | 166 | high | line106 |  |
| 164 | 167 | high | line107 |  |
| 165 | 168 | high | line108 |  |
| 166 | 169 | high | line109 |  |
| 167 | 170 | high | line110 |  |
| 168 | 171 | high | line111 |  |
| 169 | 172 | high | line112 |  |
| 170 | 173 | high | line113 |  |
| 171 | 174 | high | line114 |  |
| 172 | 175 | high | line115 |  |
| 173 | 176 | high | line116 |  |
| 174 | 177 | high | line117 |  |
| 175 | 178 | high | line118 |  |
| 176 | 179 | high | line119 |  |
| 177 | 180 | high | line120 |  |
| 178 | 181 | high | line121 |  |
| 179 | 182 | high | line122 |  |
| 180 | 183 | high | line123 |  |
| 181 | 184 | high | line124 |  |
| 182 | 185 | high | line125 |  |
| 183 | 186 | high | line126 |  |
| 184 | 187 | high | line127 |  |
| 185 | 188 | high | line128 |  |
| 186 | 189 | high | line129 |  |
| 187 | 190 | high | line130 |  |
| 188 | 191 | high | line131 |  |
| 189 | 192 | high | line132 |  |
| 190 | 193 | high | line133 |  |
| 191 | 194 | high | line134 |  |
| 192 | 195 | high | line135 |  |
| 193 | 196 | high | line136 |  |
| 194 | 197 | high | line137 |  |
| 195 | 198 | high | line138 |  |
| 196 | 199 | high | line139 |  |
| 197 | 200 | high | line140 |  |
| 198 | 201 | high | line141 |  |
| 199 | 202 | high | line142 |  |
| 200 | 203 | high | line143 |  |
| 201 | 204 | high | line144 |  |
| 202 | 205 | high | line145 |  |
| 203 | 206 | high | line146 |  |
| 204 | 207 | high | line147 |  |
| 205 | 208 | high | line148 |  |
| 206 | 209 | high | line149 |  |
| 207 | 210 | high | line150 |  |
| 208 | 211 | high | line151 |  |
| 209 | 212 | high | line152 |  |
| 210 | 213 | high | line153 |  |
| 211 | 214 | high | line154 |  |
| 212 | 215 | high | line155 |  |
| 213 | 216 | high | line156 |  |
| 214 | 217 | high | line157 |  |
| 215 | 218 | high | line158 |  |
| 216 | 219 | high | line159 |  |
| 217 | 220 | high | line160 |  |
| 218 | 221 | high | line161 |  |
| 219 | 222 | high | line162 |  |
| 220 | 223 | high | line163 |  |
| 221 | 224 | high | line164 |  |
| 222 | 225 | high | line165 |  |
| 223 | 226 | high | line166 |  |
| 224 | 227 | high | line167 |  |
| 225 | 228 | high | line168 |  |
| 226 | 229 | high | line169 |  |
| 227 | 230 | high | line170 |  |
| 228 | 231 | high | line171 |  |
| 229 | 232 | high | line172 |  |
| 230 | 233 | high | line173 |  |
| 231 | 234 | high | line174 |  |
| 232 | 235 | high | line175 |  |
| 233 | 236 | high | line176 |  |
| 234 | 237 | high | line177 |  |
| 235 | 238 | high | line178 |  |
| 236 | 239 | high | line179 |  |
| 237 | 240 | high | line180 |  |
| 238 | 241 | high | line181 |  |
| 239 | 242 | high | line182 |  |
| 240 | 243 | high | line183 |  |
| 241 | 244 | high | line184 |  |
| 242 | 245 | high | line185 |  |
| 243 | 246 | high | line186 |  |
| 244 | 247 | high | line187 |  |
| 245 | 248 | high | line188 |  |
| 246 | 249 | high | line189 |  |
| 247 | 250 | high | line190 |  |
| 248 | 251 | high | line191 |  |
| 249 | 252 | high | line192 |  |
| 250 | 253 | high | line193 |  |
| 251 | 254 | high | line194 |  |
| 252 | 255 | high | line195 |  |
| 253 | 256 | high | line196 |  |
| 254 | 257 | high | line197 |  |
| 255 | 258 | high | line198 |  |
| 256 | 259 | high | line199 |  |
| 257 | 260 | high | line200 |  |
| 258 | 261 | high | line201 |  |
| 259 | 262 | high | line202 |  |
| 260 | 263 | high | line203 |  |
| 261 | 264 | high | line204 |  |
| 262 | 265 | high | line205 |  |
| 263 | 266 | high | line206 |  |
| 264 | 267 | high | line207 |  |
| 265 | 268 | high | line208 |  |
| 266 | 269 | high | line209 |  |
| 267 | 270 | high | line210 |  |
| 268 | 271 | high | line211 |  |
| 269 | 272 | high | line212 |  |
| 270 | 273 | high | line213 |  |
| 271 | 274 | high | line214 |  |
| 272 | 275 | high | line215 |  |
| 273 | 276 | high | line216 |  |
| 274 | 277 | high | line217 |  |
| 275 | 278 | high | line218 |  |
| 276 | 279 | high | line219 |  |
| 277 | 280 | high | line220 |  |
| 278 | 281 | high | line221 |  |
| 279 | 282 | high | line222 |  |
| 280 | 283 | high | line223 |  |
| 281 | 284 | high | line224 |  |
| 282 | 285 | high | line225 |  |
| 283 | 286 | high | line226 |  |
| 284 | 287 | high | line227 |  |
| 285 | 288 | high | line228 |  |
| 286 | 289 | high | line229 |  |
| 287 | 290 | high | line230 |  |
| 288 | 291 | high | line231 |  |
| 289 | 292 | high | line232 |  |
| 290 | 293 | high | line233 |  |
| 291 | 294 | high | line234 |  |
| 292 | 295 | high | line235 |  |
| 293 | 296 | high | line236 |  |
| 294 | 297 | high | line237 |  |
| 295 | 298 | high | line238 |  |
| 296 | 299 | high | line239 |  |
| 297 | 300 | high | line240 |  |
| 298 | 301 | high | line241 |  |
| 299 | 302 | high | line242 |  |
| 300 | 303 | high | line243 |  |
| 301 | 304 | high | line244 |  |
| 302 | 305 | high | line245 |  |
| 303 | 306 | high | line246 |  |
| 304 | 307 | high | line247 |  |
| 305 | 308 | high | line248 |  |
| 306 | 309 | high | line249 |  |
| 307 | 310 | high | line250 |  |
| 308 | 311 | high | line251 |  |
| 309 | 312 | high | line252 |  |
| 310 | 313 | high | line253 |  |
| 311 | 314 | high | line254 |  |
| 312 | 315 | high | line255 |  |
| 313 | 316 | high | line256 |  |
| 314 | 317 | high | line257 |  |
| 315 | 318 | high | line258 |  |
| 316 | 319 | high | line259 |  |
| 317 | 320 | high | line260 |  |
| 318 | 321 | high | line261 |  |
| 319 | 322 | high | line262 |  |
| 320 | 323 | high | line263 |  |
| 321 | 324 | high | line264 |  |
| 322 | 325 | high | line265 |  |
| 323 | 326 | high | line266 |  |
| 324 | 327 | high | line267 |  |
| 325 | 328 | high | line268 |  |
| 326 | 329 | high | line269 |  |
| 327 | 330 | high | line270 |  |
| 328 | 331 | high | line271 |  |
| 329 | 332 | high | line272 |  |
| 330 | 333 | high | line273 |  |
| 331 | 334 | high | line274 |  |
| 332 | 335 | high | line275 |  |
| 333 | 336 | high | line276 |  |
| 334 | 337 | high | line277 |  |
| 335 | 338 | high | line278 |  |
| 336 | 339 | high | line279 |  |
| 337 | 340 | high | line280 |  |
| 338 | 341 | high | line281 |  |
| 339 | 342 | high | line282 |  |
| 340 | 343 | high | line283 |  |
| 341 | 344 | high | line284 |  |
| 342 | 345 | high | line285 |  |
| 343 | 346 | high | line286 |  |
| 344 | 347 | high | line287 |  |
| 345 | 348 | high | line288 |  |
| 346 | 349 | high | line289 |  |
| 347 | 350 | high | line290 |  |
| 348 | 351 | high | line291 |  |
| 349 | 352 | high | line292 |  |
| 350 | 353 | high | line293 |  |
| 351 | 354 | high | line294 |  |
| 352 | 355 | high | line295 |  |
| 353 | 356 | high | line296 |  |
| 354 | 357 | high | line297 |  |
| 355 | 358 | high | line298 |  |
| 356 | 359 | high | line299 |  |
| 357 | 360 | high | line300 |  |
| 358 | 361 | high | line301 |  |
| 359 | 362 | high | line302 |  |
| 360 | 363 | high | line303 |  |
| 361 | 364 | high | line304 |  |
| 362 | 365 | high | line305 |  |
| 363 | 366 | high | line306 |  |
| 364 | 367 | high | line307 |  |
| 365 | 368 | high | line308 |  |
| 366 | 369 | high | line309 |  |
| 367 | 370 | high | line310 |  |
| 368 | 371 | high | line311 |  |
| 369 | 372 | high | line312 |  |
| 370 | 373 | high | line313 |  |
| 371 | 374 | high | line314 |  |
| 372 | 375 | high | line315 |  |
| 373 | 376 | high | line316 |  |
| 374 | 377 | high | line317 |  |
| 375 | 378 | high | line318 |  |
| 376 | 379 | high | line319 |  |
| 377 | 380 | high | line320 |  |
| 378 | 381 | high | line321 |  |
| 379 | 382 | high | line322 |  |
| 380 | 383 | high | line323 |  |
| 381 | 384 | high | line324 |  |
| 382 | 385 | high | line325 |  |
| 383 | 386 | high | line326 |  |
| 384 | 387 | high | line327 |  |
| 385 | 388 | high | line328 |  |
| 386 | 389 | high | line329 |  |
| 387 | 390 | high | line330 |  |
| 388 | 391 | high | line331 |  |
| 389 | 392 | high | line332 |  |
| 390 | 393 | high | line333 |  |
| 391 | 394 | high | line334 |  |
| 392 | 395 | high | line335 |  |
| 393 | 396 | high | line336 |  |
| 394 | 397 | high | line337 |  |
| 395 | 398 | high | line338 |  |
| 396 | 399 | high | line339 |  |
| 397 | 400 | high | line340 |  |
| 398 | 401 | high | line341 |  |
| 399 | 402 | high | line342 |  |
| 400 | 403 | high | line343 |  |
| 401 | 404 | high | line344 |  |
| 402 | 405 | high | line345 |  |
| 403 | 406 | high | line346 |  |
| 404 | 407 | high | line347 |  |
| 405 | 408 | high | line348 |  |
| 406 | 409 | high | line349 |  |
| 407 | 410 | high | line350 |  |
| 408 | 411 | high | line351 |  |
| 409 | 412 | high | line352 |  |
| 410 | 413 | high | line353 |  |
| 411 | 414 | high | line354 |  |
| 412 | 415 | high | line355 |  |
| 413 | 416 | high | line356 |  |
| 414 | 417 | high | line357 |  |
| 415 | 418 | high | line358 |  |
| 416 | 419 | high | line359 |  |
| 417 | 420 | high | line360 |  |
| 418 | 421 | high | line361 |  |
| 419 | 422 | high | line362 |  |
| 420 | 423 | high | line363 |  |
| 421 | 424 | high | line364 |  |
| 422 | 425 | high | line365 |  |
| 423 | 426 | high | line366 |  |
| 424 | 427 | high | line367 |  |
| 425 | 428 | high | line368 |  |
| 426 | 429 | high | line369 |  |
| 427 | 430 | high | line370 |  |
| 428 | 431 | high | line371 |  |
| 429 | 432 | high | line372 |  |
| 430 | 433 | high | line373 |  |
| 431 | 434 | high | line374 |  |
| 432 | 435 | high | line375 |  |
| 433 | 436 | high | line376 |  |
| 434 | 437 | high | line377 |  |
| 435 | 438 | high | line378 |  |
| 436 | 439 | high | line379 |  |
| 437 | 440 | high | line380 |  |
| 438 | 441 | high | line381 |  |
| 439 | 442 | high | line382 |  |
| 440 | 443 | high | line383 |  |
| 441 | 444 | high | line384 |  |
| 442 | 445 | high | line385 |  |
| 443 | 446 | high | line386 |  |
| 444 | 447 | high | line387 |  |
| 445 | 448 | high | line388 |  |
| 446 | 449 | high | line389 |  |
| 447 | 450 | high | line390 |  |
| 448 | 451 | high | line391 |  |
| 449 | 452 | high | line392 |  |
| 450 | 453 | high | line393 |  |
| 451 | 454 | high | line394 |  |
| 452 | 455 | high | line395 |  |
| 453 | 456 | high | line396 |  |
| 454 | 457 | high | line397 |  |
| 455 | 458 | high | line398 |  |
| 456 | 459 | high | line399 |  |
| 457 | 460 | high | line400 |  |
| 458 | 461 | high | line401 |  |
| 459 | 462 | high | line402 |  |
| 460 | 463 | high | line403 |  |
| 461 | 464 | high | line404 |  |
| 462 | 465 | high | line405 |  |
| 463 | 466 | high | line406 |  |
| 464 | 467 | high | line407 |  |
| 465 | 468 | high | line408 |  |
| 466 | 469 | high | line409 |  |
| 467 | 470 | high | line410 |  |
| 468 | 471 | high | line411 |  |
| 469 | 472 | high | line412 |  |
| 470 | 473 | high | line413 |  |
| 471 | 474 | high | line414 |  |
| 472 | 475 | high | line415 |  |
| 473 | 476 | high | line416 |  |
| 474 | 477 | high | line417 |  |
| 475 | 478 | high | line418 |  |
| 476 | 479 | high | line419 |  |
| 477 | 480 | high | line420 |  |
| 478 | 481 | high | line421 |  |
| 479 | 482 | high | line422 |  |
| 480 | 483 | high | line423 |  |
| 481 | 484 | high | line424 |  |
| 482 | 485 | high | line425 |  |
| 483 | 486 | high | line426 |  |
| 484 | 487 | high | line427 |  |
| 485 | 488 | high | line428 |  |
| 486 | 489 | high | line429 |  |
| 487 | 490 | high | line430 |  |
| 488 | 491 | high | line431 |  |
| 489 | 492 | high | line432 |  |
| 490 | 493 | high | line433 |  |
| 491 | 494 | high | line434 |  |
| 492 | 495 | high | line435 |  |
| 493 | 496 | high | line436 |  |
| 494 | 497 | high | line437 |  |
| 495 | 498 | high | line438 |  |
| 496 | 499 | high | line439 |  |
| 497 | 500 | high | line440 |  |
| 498 | 501 | high | line441 |  |
| 499 | 502 | high | line442 |  |
| 500 | 503 | high | line443 |  |
| 501 | 504 | high | line444 |  |
| 502 | 505 | high | line445 |  |
| 503 | 506 | high | line446 |  |
| 504 | 507 | high | line447 |  |
| 505 | 508 | high | line448 |  |
| 506 | 509 | high | line449 |  |
| 507 | 510 | high | line450 |  |
| 508 | 511 | high | line451 |  |
| 509 | 512 | high | line452 |  |
| 510 | 513 | high | line453 |  |
| 511 | 514 | high | line454 |  |
| 512 | 515 | high | line455 |  |
| 513 | 516 | high | line456 |  |
| 514 | 517 | high | line457 |  |
| 515 | 518 | high | line458 |  |
| 516 | 519 | high | line459 |  |
| 517 | 520 | high | line460 |  |
| 518 | 521 | high | line461 |  |
| 519 | 522 | high | line462 |  |
| 520 | 523 | high | line463 |  |
| 521 | 524 | high | line464 |  |
| 522 | 525 | high | line465 |  |
| 523 | 526 | high | line466 |  |
| 524 | 527 | high | line467 |  |
| 525 | 528 | high | line468 |  |
| 526 | 529 | high | line469 |  |
| 527 | 530 | high | line470 |  |
| 528 | 531 | high | line471 |  |
| 529 | 532 | high | line472 |  |
| 530 | 533 | high | line473 |  |
| 531 | 534 | high | line474 |  |
| 532 | 535 | high | line475 |  |
| 533 | 536 | high | line476 |  |
| 534 | 537 | high | line477 |  |
| 535 | 538 | high | line478 |  |
| 536 | 539 | high | line479 |  |
| 537 | 540 | high | line480 |  |
| 538 | 541 | high | line481 |  |
| 539 | 542 | high | line482 |  |
| 540 | 543 | high | line483 |  |
| 541 | 544 | high | line484 |  |
| 542 | 545 | high | line485 |  |
| 543 | 546 | high | line486 |  |
| 544 | 547 | high | line487 |  |
| 545 | 548 | high | line488 |  |
| 546 | 549 | high | line489 |  |
| 547 | 550 | high | line490 |  |
| 548 | 551 | high | line491 |  |
| 549 | 552 | high | line492 |  |
| 550 | 553 | high | line493 |  |
| 551 | 554 | high | line494 |  |
| 552 | 555 | high | line495 |  |
| 553 | 556 | high | line496 |  |
| 554 | 557 | high | line497 |  |
| 555 | 558 | high | line498 |  |
| 556 | 559 | high | line499 |  |
| 557 | 560 | high | chatscrollbar |  |
| 558 | 567 | high | chatoverlay | hide,anchor,anchored |
| 559 | 568 | high | chatmodal | hide,anchor,anchored |

New in 241: 35 chat_achievement_click, 36 chat_miscaction_click, 48 mes_layer_button_4, 561 chatcrm, 562 crmspace, 563 crmcanvas, 564 crmhighlight, 565 crmclose, 566 crmclose_graphic

### 149 inventory (INVENTORY_TAB)

211: 1 components, 241: 1. Moved: 0. High 1, medium 0, low 0.

### 387 wornitems (EQUIPMENT_TAB)

211: 28 components, 241: 31. Moved: 2. High 28, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 26 | 29 | high | deadman_sigils |  |
| 27 | 30 | high | extra_ammo_slot |  |

New in 241: 26 extra_quiver_slot, 27 extra_quiver_ammo_graphic, 28 extra_quiver_ammo

### 84 equipment (EQUIPMENT_STATS)

211: 54 components, 241: 57. Moved: 3. High 25, medium 23, low 6.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 21 | -1 | low |  | unmatched |
| 22 | -1 | low |  | unmatched |
| 23 | 23 | medium | com_23 | parent |
| 24 | 24 | medium | stabatt | onmouserepeat,onmouseleave,parent |
| 25 | 25 | medium | slashatt | onmouserepeat,onmouseleave,parent |
| 26 | 26 | medium | crushatt | onmouserepeat,onmouseleave,parent |
| 27 | 27 | medium | magicatt | onmouserepeat,onmouseleave,parent |
| 28 | 28 | medium | rangeatt | onmouserepeat,onmouseleave,parent |
| 29 | 29 | medium | com_29 | parent |
| 30 | 30 | medium | stabdef | onmouserepeat,onmouseleave,parent |
| 31 | 31 | medium | slashdef | onmouserepeat,onmouseleave,parent |
| 32 | 32 | medium | crushdef | onmouserepeat,onmouseleave,parent |
| 33 | 33 | medium | magicdef | onmouserepeat,onmouseleave,parent |
| 34 | 34 | medium | rangedef | onmouserepeat,onmouseleave,parent |
| 35 | 35 | medium | com_35 | parent |
| 36 | 36 | medium | meleestrength | onmouserepeat,onmouseleave,parent |
| 37 | 37 | medium | rangestrength | onmouserepeat,onmouseleave,parent |
| 38 | 38 | medium | magicdamage | onmouserepeat,onmouseleave,parent |
| 39 | 39 | medium | prayer | onmouserepeat,onmouseleave,parent |
| 40 | 40 | medium | com_40 | parent |
| 41 | 41 | medium | typemultiplier | onmouserepeat,onmouseleave,parent |
| 42 | 42 | medium | slayermultiplier | onmouserepeat,onmouseleave,parent |
| 43 | 43 | medium | com_43 | parent |
| 45 | 45 | low | com_45 | x,text,xmode |
| 46 | -1 | low |  | unmatched |
| 47 | 47 | low | scrollbar | x,y,height,ymode |
| 48 | 48 | low | previous_page | x,width |
| 52 | 52 | medium | attackspeedbase | onmouserepeat,onmouseleave,parent |
| 53 | 53 | medium | attackspeedactual | onmouserepeat,onmouseleave,parent |

New in 241: 21 extra_quiver_slot, 22 stat_group, 46 set_effect, 54 next_page, 55 extra_quiver_ammo_graphic, 56 extra_quiver_ammo

### 85 equipment_side (EQUIPMENT_INVENTORY)

211: 1 components, 241: 1. Moved: 0. High 1, medium 0, low 0.

### 593 combat_interface (COMBAT_TAB)

211: 43 components, 241: 77. Moved: 42. High 37, medium 6, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 1 | 3 | medium | title | xmode,widthmode,width,parent |
| 2 | 5 | high | category |  |
| 3 | 4 | high | level | width,widthmode,xmode,parent,anchored |
| 4 | 6 | high | 0 |  |
| 5 | 7 | high | 0_back |  |
| 6 | 8 | high | 0_icon |  |
| 7 | 9 | high | 0_text |  |
| 8 | 10 | high | 1 |  |
| 9 | 11 | high | 1_back |  |
| 10 | 12 | high | 1_icon |  |
| 11 | 13 | high | 1_text |  |
| 12 | 14 | high | 2 |  |
| 13 | 15 | high | 2_back |  |
| 14 | 16 | high | 2_icon |  |
| 15 | 17 | high | 2_text |  |
| 16 | 18 | high | 3 |  |
| 17 | 19 | high | 3_back |  |
| 18 | 20 | high | 3_icon |  |
| 19 | 21 | high | 3_text |  |
| 20 | 22 | high | autocast_buttons |  |
| 21 | 23 | high | autocast_defensive |  |
| 22 | 24 | high | defensive_container |  |
| 23 | 25 | high | com_25 | onstattransmit,onvartransmit,oninvtransmit,geometry-match |
| 24 | 26 | high | com_26 |  |
| 25 | 27 | high | com_27 |  |
| 26 | 28 | high | autocast_normal |  |
| 27 | 29 | high | normal_container |  |
| 28 | 30 | high | com_30 | onstattransmit,onvartransmit,oninvtransmit,geometry-match |
| 29 | 31 | high | com_31 |  |
| 30 | 32 | high | retaliate | onmouserepeat,onvartransmit,geometry-match |
| 31 | 33 | high | com_33 |  |
| 32 | 34 | high | retaliate_back |  |
| 33 | 35 | high | retaliate_icon |  |
| 34 | 36 | high | retaliate_text |  |
| 35 | 38 | high | sp_attackbars_container | height,width,anchored |
| 36 | 41 | medium | special_attack | parent |
| 37 | 42 | medium | sp_empty | parent |
| 38 | 43 | medium | currentenergy | parent |
| 39 | 44 | high | currentenergy_colour |  |
| 40 | 45 | medium | sp_indicator | parent |
| 41 | 46 | medium | sp_border | parent |
| 42 | 47 | high | tooltip |  |

New in 241: 1 glow, 2 header, 37 set_effect, 39 sp_attackbar_mainhand, 40 sp_attackbar_offhand, 48 switch_button, 49 infobutton, 50 info_layer, 51 buttons_click_overlay, 52 com_52, 53 set_effect_back, 54 set_effect_icon, 55 set_effect_text, 56 sp_attackbar, 57 special_attack_mainhand, 58 special_attack_bg_mainhand, 59 sp_empty_mainhand, 60 currentenergy_mainhand, 61 currentenergy_colour_mainhand, 62 sp_indicator_mainhand, 63 sp_border_mainhand, 64 sp_orb_border_mainhand, 65 sp_orb_mainhand, 66 sp_icon_mainhand, 67 special_attack_offhand, 68 special_attack_bg_offhand, 69 sp_empty_offhand, 70 currentenergy_offhand, 71 currentenergy_colour_offhand, 72 sp_indicator_offhand, 73 sp_border_offhand, 74 sp_orb_border_offhand, 75 sp_orb_offhand, 76 sp_icon_offhand

### 541 prayerbook (PRAYER_TAB_INTERFACE)

211: 35 components, 241: 43. Moved: 34. High 33, medium 2, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 1 | 2 | medium | infoglow | parent |
| 2 | 7 | high | level | parent,anchored |
| 3 | 8 | medium | info | xmode,parent |
| 4 | 3 | high | container | parent,anchored |
| 5 | 9 | high | prayer1 |  |
| 6 | 10 | high | prayer2 |  |
| 7 | 11 | high | prayer3 |  |
| 8 | 12 | high | prayer4 |  |
| 9 | 13 | high | prayer5 |  |
| 10 | 14 | high | prayer6 |  |
| 11 | 15 | high | prayer7 |  |
| 12 | 16 | high | prayer8 |  |
| 13 | 17 | high | prayer9 |  |
| 14 | 18 | high | prayer10 |  |
| 15 | 19 | high | prayer11 |  |
| 16 | 20 | high | prayer12 |  |
| 17 | 21 | high | prayer13 |  |
| 18 | 22 | high | prayer14 |  |
| 19 | 23 | high | prayer15 |  |
| 20 | 24 | high | prayer16 |  |
| 21 | 25 | high | prayer17 |  |
| 22 | 26 | high | prayer18 |  |
| 23 | 27 | high | prayer19 |  |
| 24 | 30 | high | prayer22 |  |
| 25 | 28 | high | prayer20 |  |
| 26 | 31 | high | prayer23 |  |
| 27 | 29 | high | prayer21 |  |
| 28 | 32 | high | prayer24 |  |
| 29 | 34 | high | prayer26 |  |
| 30 | 35 | high | prayer27 |  |
| 31 | 33 | high | prayer25 |  |
| 32 | 36 | high | prayer28 |  |
| 33 | 37 | high | prayer29 |  |
| 34 | 39 | high | tooltip |  |

New in 241: 1 top, 4 filtermenu_container, 5 bottom, 6 filterbutton, 38 prayer30, 40 com_40, 41 com_41, 42 filtermenu

### 77 quickprayer (QUICK_PRAYERS)

211: 7 components, 241: 7. Moved: 0. High 7, medium 0, low 0.

### 218 magic_spellbook (SPELLBOOK)

211: 200 components, 241: 211. Moved: 195. High 199, medium 0, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 4 | 7 | high | teleport_minigame_standard |  |
| 5 | 8 | high | teleport_minigame_ancient |  |
| 7 | 11 | high | wind_strike |  |
| 8 | 12 | high | confuse |  |
| 9 | 13 | high | xbows_enchant | op1,geometry-match |
| 10 | 14 | high | water_strike |  |
| 11 | 16 | high | enchant_1 |  |
| 12 | 17 | high | earth_strike |  |
| 13 | 18 | high | weaken |  |
| 14 | 19 | high | fire_strike |  |
| 15 | 20 | high | bones_bananas |  |
| 16 | 21 | high | wind_bolt |  |
| 17 | 22 | high | curse |  |
| 18 | 23 | high | bind |  |
| 19 | 24 | high | low_alchemy | op9,transmitop,anchored |
| 20 | 25 | high | water_bolt |  |
| 21 | 26 | high | varrock_teleport |  |
| 22 | 27 | high | enchant_2 |  |
| 23 | 28 | high | earth_bolt |  |
| 24 | 29 | high | lumbridge_teleport |  |
| 25 | 30 | high | telegrab |  |
| 26 | 31 | high | fire_bolt |  |
| 27 | 32 | high | falador_teleport |  |
| 28 | 33 | high | crumble_undead |  |
| 29 | 34 | high | teleport_to_your_house |  |
| 30 | 35 | high | wind_blast |  |
| 31 | 36 | high | superheat |  |
| 32 | 37 | high | camelot_teleport |  |
| 33 | 38 | high | water_blast |  |
| 34 | 40 | high | enchant_3 |  |
| 35 | 41 | high | iban_blast |  |
| 36 | 42 | high | snare |  |
| 37 | 43 | high | magic_dart |  |
| 38 | 44 | high | ardougne_teleport |  |
| 39 | 45 | high | earth_blast |  |
| 40 | 47 | high | high_alchemy | op9,transmitop,anchored |
| 41 | 48 | high | charge_water_orb |  |
| 42 | 49 | high | enchant_4 |  |
| 43 | 50 | high | watchtower_teleport |  |
| 44 | 51 | high | fire_blast |  |
| 45 | 52 | high | charge_earth_orb |  |
| 46 | 53 | high | bones_peaches |  |
| 47 | 54 | high | saradomin_strike |  |
| 48 | 55 | high | claws_of_guthix |  |
| 49 | 56 | high | flames_of_zamorak |  |
| 50 | 57 | high | trollheim_teleport |  |
| 51 | 58 | high | wind_wave |  |
| 52 | 59 | high | charge_fire_orb |  |
| 53 | 60 | high | ape_teleport |  |
| 54 | 61 | high | water_wave |  |
| 55 | 62 | high | charge_air_orb |  |
| 56 | 63 | high | vulnerability |  |
| 57 | 64 | high | enchant_5 |  |
| 58 | -1 | low |  | unmatched |
| 59 | 65 | high | earth_wave |  |
| 60 | 66 | high | enfeeble |  |
| 61 | 67 | high | teleother_lumbridge |  |
| 62 | 68 | high | fire_wave |  |
| 63 | 69 | high | entangle |  |
| 64 | 70 | high | stun |  |
| 65 | 71 | high | charge |  |
| 66 | 72 | high | wind_surge |  |
| 67 | 73 | high | teleother_falador |  |
| 68 | 74 | high | water_surge |  |
| 69 | 75 | high | teleport_block |  |
| 70 | 104 | high | zarosteleport5 |  |
| 71 | 77 | high | enchant_6 |  |
| 72 | 78 | high | teleother_camelot |  |
| 73 | 79 | high | earth_surge |  |
| 74 | 80 | high | enchant_7 |  |
| 75 | 81 | high | fire_surge |  |
| 76 | 84 | high | ice_rush |  |
| 77 | 85 | high | ice_blitz |  |
| 78 | 86 | high | ice_burst |  |
| 79 | 87 | high | ice_barrage |  |
| 80 | 88 | high | blood_rush |  |
| 81 | 89 | high | blood_blitz |  |
| 82 | 90 | high | blood_burst |  |
| 83 | 91 | high | blood_barrage |  |
| 84 | 92 | high | smoke_rush |  |
| 85 | 93 | high | smoke_blitz |  |
| 86 | 94 | high | smoke_burst |  |
| 87 | 95 | high | smoke_barrage |  |
| 88 | 96 | high | shadow_rush |  |
| 89 | 97 | high | shadow_blitz |  |
| 90 | 98 | high | shadow_burst |  |
| 91 | 99 | high | shadow_barrage |  |
| 92 | 100 | high | zarosteleport1 |  |
| 93 | 101 | high | zarosteleport2 |  |
| 94 | 102 | high | zarosteleport3 |  |
| 95 | 103 | high | zarosteleport4 |  |
| 96 | 105 | high | zarosteleport6 |  |
| 97 | 106 | high | zarosteleport7 |  |
| 98 | 107 | high | zarosteleport8 |  |
| 99 | 82 | high | teleport_boat_to_me | op10,op2,transmitop,anchored |
| 100 | 108 | high | teleport_home_zaros |  |
| 101 | 109 | high | teleport_home_lunar |  |
| 102 | 110 | high | bake_pie |  |
| 103 | 111 | high | cure_plant |  |
| 104 | 112 | high | monster_examine |  |
| 105 | 113 | high | npc_contact | transmitop,op3,op9,anchored |
| 106 | 114 | high | cure_other |  |
| 107 | 115 | high | humidify |  |
| 108 | 116 | high | tele_moonclan |  |
| 109 | 117 | high | tele_group_moonclan |  |
| 110 | 118 | high | cure_me |  |
| 111 | 119 | high | hunter_kit |  |
| 112 | 120 | high | tele_waterbirth |  |
| 113 | 121 | high | tele_group_waterbirth |  |
| 114 | 122 | high | cure_group |  |
| 115 | 123 | high | stat_spy |  |
| 116 | 124 | high | tele_barb_out |  |
| 117 | 125 | high | tele_group_barbarian |  |
| 118 | 126 | high | superglass |  |
| 119 | 127 | high | tan_leather |  |
| 120 | 128 | high | tele_khazard |  |
| 121 | 129 | high | tele_group_khazard |  |
| 122 | 130 | high | dream |  |
| 123 | 131 | high | string_jewel |  |
| 124 | 132 | high | rest_pot_share |  |
| 125 | 133 | high | magic_imbue |  |
| 126 | 134 | high | fertile_soil |  |
| 127 | 135 | high | stren_pot_share |  |
| 128 | 136 | high | tele_fish |  |
| 129 | 137 | high | tele_group_fishing_guild |  |
| 130 | 138 | high | plank_make |  |
| 131 | 139 | high | tele_cather |  |
| 132 | 140 | high | tele_group_catherby |  |
| 133 | 141 | high | recharge_dragonstone |  |
| 134 | 142 | high | tele_ghorrock |  |
| 135 | 143 | high | tele_group_ghorrock |  |
| 136 | 144 | high | energy_trans |  |
| 137 | 145 | high | heal_other |  |
| 138 | 146 | high | vengeance_other |  |
| 139 | 147 | high | vengeance | op9,transmitop,anchored |
| 140 | 148 | high | heal_group |  |
| 141 | 149 | high | spellbook_swap |  |
| 142 | 150 | high | geomancy |  |
| 143 | 151 | high | spin_flax |  |
| 144 | 152 | high | ourania_teleport |  |
| 145 | 153 | high | teleport_home_arceuus |  |
| 146 | 154 | high | reanimation_basic |  |
| 147 | 155 | high | teleport_arceuus_library |  |
| 148 | 156 | high | reanimation_adept |  |
| 149 | 157 | high | reanimation_expert |  |
| 150 | 158 | high | reanimation_master |  |
| 151 | 159 | high | teleport_draynor_manor |  |
| 152 | 160 | high | necromancy_dog |  |
| 153 | 161 | high | teleport_mind_altar |  |
| 154 | 162 | high | teleport_respawn |  |
| 155 | 163 | high | teleport_salve_graveyard |  |
| 156 | 164 | high | teleport_fenkenstrain_castle |  |
| 157 | 165 | high | teleport_west_ardougne |  |
| 158 | 166 | high | teleport_harmony_island |  |
| 159 | 167 | high | teleport_cemetery |  |
| 160 | 168 | high | resurrect_crops |  |
| 161 | 169 | high | teleport_barrows |  |
| 162 | 170 | high | teleport_ape_atoll_dungeon |  |
| 163 | 171 | high | teleport_battlefront |  |
| 164 | 172 | high | inferior_demonbane |  |
| 165 | 173 | high | superior_demonbane |  |
| 166 | 174 | high | dark_demonbane |  |
| 167 | 175 | high | mark_of_darkness | targetmask,transmitop,op1,geometry-match |
| 168 | 176 | high | ghostly_grasp |  |
| 169 | 177 | high | skeletal_grasp |  |
| 170 | 178 | high | undead_grasp |  |
| 171 | 179 | high | ward_of_arceuus |  |
| 172 | 180 | high | lesser_corruption |  |
| 173 | 181 | high | greater_corruption |  |
| 174 | 182 | high | demonic_offering |  |
| 175 | 183 | high | sinister_offering |  |
| 176 | 184 | high | degrime |  |
| 177 | 185 | high | shadow_veil |  |
| 178 | 186 | high | vile_vigour |  |
| 179 | 187 | high | dark_lure |  |
| 180 | 188 | high | death_charge |  |
| 181 | 189 | high | resurrect_lesser_ghost | op9,transmitop,anchored |
| 182 | 190 | high | resurrect_lesser_skeleton | op9,transmitop,anchored |
| 183 | 191 | high | resurrect_lesser_zombie | op9,transmitop,anchored |
| 184 | 192 | high | resurrect_superior_ghost | op9,transmitop,anchored |
| 185 | 193 | high | resurrect_superior_skeleton | op9,transmitop,anchored |
| 186 | 194 | high | resurrect_superior_zombie | op9,transmitop,anchored |
| 187 | 195 | high | resurrect_greater_ghost | op9,transmitop,anchored |
| 188 | 196 | high | resurrect_greater_skeleton | op9,transmitop,anchored |
| 189 | 197 | high | resurrect_greater_zombie | op9,transmitop,anchored |
| 190 | 201 | high | infolayer |  |
| 191 | 202 | high | com_202 |  |
| 192 | 203 | high | filtermenu_container |  |
| 193 | 204 | high | com_204 |  |
| 194 | 205 | high | com_205 |  |
| 195 | 206 | high | filtermenu |  |
| 196 | 207 | high | bottom |  |
| 197 | 208 | high | infobutton |  |
| 198 | 209 | high | filterbutton |  |
| 199 | 210 | high | tooltip |  |

New in 241: 4 back_button, 5 league_home_teleport, 9 teleport_minigame_arceuus, 10 teleport_minigame_lunar, 15 enchant_jewellery, 39 kourend_teleport, 46 fortis_teleport, 76 bounty_target, 83 teleport_me_to_boat, 198 monster_inspect, 199 transmute_upgrade, 200 transmute_downgrade

### 320 stats (SKILLS_TAB)

211: 29 components, 241: 34. Moved: 5. High 25, medium 1, low 3.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 24 | -1 | low |  | unmatched |
| 25 | -1 | low |  | unmatched |
| 26 | -1 | low |  | unmatched |
| 27 | 32 | medium | com_32 | y,heightmode,width,widthmode,height,parent |
| 28 | 33 | high | tooltip |  |

New in 241: 24 sailing, 25 total, 26 com_26, 27 com_27, 28 com_28, 29 com_29, 30 com_30, 31 com_31

### 629 side_journal (JOURNAL_HEADER_TAB)

211: 34 components, 241: 44. Moved: 31. High 24, medium 4, low 6.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 2 | -1 | low |  | unmatched |
| 3 | 2 | high | summary_list |  |
| 5 | 6 | high | tab_1_2 |  |
| 6 | 8 | high | tab_1_3 |  |
| 7 | 9 | high | tab_icon_1 |  |
| 8 | 10 | high | quest_list |  |
| 9 | 12 | high | tab_2_1 |  |
| 10 | 14 | high | tab_2_2 |  |
| 11 | 16 | high | tab_2_3 |  |
| 12 | 17 | high | tab_icon_2 |  |
| 13 | 18 | high | task_list |  |
| 14 | 19 | low | tab_3_1_backing | graphic |
| 15 | -1 | low |  | unmatched |
| 16 | 20 | medium | tab_3_1 | hflip,xmode |
| 17 | 21 | low | tab_3_2_backing | y,graphic,height |
| 18 | -1 | low |  | unmatched |
| 19 | -1 | low |  | unmatched |
| 20 | 22 | medium | tab_3_2 | parent |
| 21 | 24 | medium | tab_3_3 | parent |
| 22 | 25 | medium | tab_icon_3 | graphic,parent |
| 23 | 26 | high | adventurepath_list | x |
| 24 | 28 | high | tab_4_1 |  |
| 25 | 30 | high | tab_4_2 |  |
| 26 | 32 | high | tab_4_3 |  |
| 27 | 33 | high | tab_icon_4 |  |
| 28 | 34 | high | league_list |  |
| 29 | 37 | high | tab_5_1 |  |
| 30 | 39 | high | tab_5_2 |  |
| 31 | 41 | high | tab_5_3 |  |
| 32 | 42 | high | tab_icon_5 | graphic,anchor,anchored |
| 33 | 43 | high | tab_container |  |

New in 241: 3 tab_1_1_backing, 5 tab_1_2_backing, 7 tab_1_3_backing, 11 tab_2_1_backing, 13 tab_2_2_backing, 15 tab_2_3_backing, 23 tab_3_3_backing, 27 tab_4_1_backing, 29 tab_4_2_backing, 31 tab_4_3_backing, 35 tab_line, 36 tab_5_1_backing, 38 tab_5_2_backing, 40 tab_5_3_backing

### 399 questlist (QUEST_TAB)

211: 12 components, 241: 12. Moved: 0. High 12, medium 0, low 0.

### 712 account_summary_sidepanel (CHARACTER_SUMMARY)

211: 4 components, 241: 4. Moved: 0. High 4, medium 0, low 0.

### 259 area_task (ACHIEVEMENT_DIARY_TAB)

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.

### 109 account (ACCOUNT_MANAGEMENT)

211: 88 components, 241: 94. Moved: 85. High 78, medium 6, low 4.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 2 | -1 | low |  | unmatched |
| 3 | -1 | low |  | unmatched |
| 4 | -1 | low |  | unmatched |
| 5 | -1 | low |  | unmatched |
| 6 | 2 | high | account_tab | x,width,anchor,anchored |
| 7 | 4 | medium | tab_1_1 | x |
| 8 | 6 | high | tab_1_2 |  |
| 9 | 8 | medium | tab_1_3 | x |
| 10 | 9 | high | tab_icon_1 |  |
| 11 | 10 | high | community_tab | width,anchor,anchored |
| 12 | 12 | medium | tab_2_1 | x |
| 13 | 14 | high | tab_2_2 |  |
| 14 | 16 | medium | tab_2_3 | x |
| 15 | 17 | high | tab_icon_2 |  |
| 16 | 18 | high | links_tab | x,width,anchor,anchored |
| 17 | 21 | medium | tab_3_1 | x |
| 18 | 23 | high | tab_3_2 |  |
| 19 | 25 | medium | tab_3_3 | x |
| 20 | 26 | high | tab_icon_3 |  |
| 21 | 27 | high | content |  |
| 22 | 28 | high | account |  |
| 23 | 29 | high | account_text |  |
| 24 | 30 | high | billing |  |
| 25 | 31 | high | billing_text |  |
| 26 | 32 | high | store_button |  |
| 27 | 33 | high | store_button_background |  |
| 28 | 34 | high | store_button_icon_left |  |
| 29 | 35 | high | store_button_icon_right |  |
| 30 | 36 | high | store_button_text |  |
| 31 | 37 | high | bond_button |  |
| 32 | 38 | high | bond_button_background |  |
| 33 | 39 | high | bond_button_icon_left |  |
| 34 | 40 | high | bond_button_icon_right |  |
| 35 | 41 | high | bond_button_text |  |
| 36 | 42 | high | inbox |  |
| 37 | 43 | high | inbox_text |  |
| 38 | 44 | high | inbox_button |  |
| 39 | 45 | high | inbox_button_background |  |
| 40 | 46 | high | inbox_button_icon_left |  |
| 41 | 47 | high | inbox_button_icon_right |  |
| 42 | 48 | high | inbox_button_text |  |
| 43 | 49 | high | name |  |
| 44 | 50 | high | name_text |  |
| 45 | 51 | high | name_button |  |
| 46 | 52 | high | name_button_background |  |
| 47 | 53 | high | name_button_icon_left |  |
| 48 | 54 | high | name_button_icon_right |  |
| 49 | 55 | high | name_button_text |  |
| 50 | 56 | high | community |  |
| 51 | 57 | high | community_text |  |
| 52 | 58 | high | polls |  |
| 53 | 59 | high | polls_text |  |
| 54 | 60 | high | poll_button_broadcasts |  |
| 55 | 61 | high | poll_button |  |
| 56 | 62 | high | poll_button_background |  |
| 57 | 63 | high | poll_button_icon_left |  |
| 58 | 64 | high | poll_button_icon_right |  |
| 59 | 65 | high | poll_button_text | onmouseover,onmouseleave,geometry-match |
| 60 | 66 | high | poll_button_foreground |  |
| 61 | 67 | high | poll_archive_button |  |
| 62 | 68 | high | poll_archive_button_background |  |
| 63 | 69 | high | poll_archive_button_icon_left |  |
| 64 | 70 | high | poll_archive_button_icon_right |  |
| 65 | 71 | high | poll_archive_button_text |  |
| 66 | 72 | high | news |  |
| 67 | 73 | high | news_text |  |
| 68 | 74 | high | newspost_button |  |
| 69 | 75 | high | newspost_button_background |  |
| 70 | 76 | high | newspost_button_icon_left |  |
| 71 | 77 | high | newspost_button_icon_right |  |
| 72 | 78 | high | newspost_button_text |  |
| 73 | 79 | high | news_archive_button |  |
| 74 | 80 | high | news_archive_button_background |  |
| 75 | 81 | high | news_archive_button_icon_left |  |
| 76 | 82 | high | news_archive_button_icon_right |  |
| 77 | 83 | high | news_archive_button_text |  |
| 78 | 84 | high | usefullinks |  |
| 79 | 85 | high | usefullinks_text |  |
| 80 | 86 | high | links_area |  |
| 81 | 87 | high | link0 |  |
| 82 | 88 | high | link1 |  |
| 83 | 89 | high | link2 |  |
| 84 | 90 | high | link3 |  |
| 85 | 91 | high | link4 |  |
| 86 | 92 | high | link5 |  |
| 87 | 93 | high | link6 |  |

New in 241: 3 tab_1_1_backing, 5 tab_1_2_backing, 7 tab_1_3_backing, 11 tab_2_1_backing, 13 tab_2_2_backing, 15 tab_2_3_backing, 19 tab_line, 20 tab_3_1_backing, 22 tab_3_2_backing, 24 tab_3_3_backing

### 116 settings_side (SETTINGS)

211: 121 components, 241: 140. Moved: 93. High 60, medium 36, low 25.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 28 | -1 | low |  | unmatched |
| 29 | 97 | high | music_holder | onload,y,anchored |
| 30 | 110 | high | music_icon | noclickthrough,anchored |
| 31 | 98 | high | music_zoom_container | onmouserepeat,noclickthrough,anchored |
| 32 | 85 | medium | zoom37 | parent |
| 33 | 86 | medium | zoom47 | parent |
| 34 | 87 | medium | zoom48 | parent |
| 35 | 88 | medium | zoom49 | parent |
| 36 | 89 | medium | zoom50 | parent |
| 37 | 90 | medium | zoom51 | parent |
| 38 | 91 | medium | zoom52 | parent |
| 39 | 92 | medium | zoom53 | parent |
| 40 | 93 | medium | zoom54 | parent |
| 41 | 108 | high | music_bobble_container |  |
| 42 | -1 | low |  | unmatched |
| 43 | 111 | high | sound_holder | onload,y,ymode,anchored |
| 44 | 124 | high | sound_icon | noclickthrough,anchored |
| 45 | 112 | high | sound_zoom_container | onmouserepeat,noclickthrough,anchored |
| 46 | 99 | medium | zoom10 | parent |
| 47 | 100 | medium | zoom11 | parent |
| 48 | 101 | medium | zoom12 | parent |
| 49 | 102 | medium | zoom13 | parent |
| 50 | 103 | medium | zoom14 | parent |
| 51 | 104 | medium | zoom15 | parent |
| 52 | 105 | medium | zoom16 | parent |
| 53 | 106 | medium | zoom17 | parent |
| 54 | 107 | medium | zoom18 | parent |
| 55 | 122 | high | sound_bobble_container |  |
| 56 | -1 | low |  | unmatched |
| 57 | 125 | high | areasounds_holder | onload,y,ymode,anchored |
| 58 | 138 | high | areasound_icon | noclickthrough,anchored |
| 59 | 126 | high | areasounds_zoom_container | onmouserepeat,noclickthrough,anchored |
| 60 | 113 | medium | zoom19 | parent |
| 61 | 114 | medium | zoom20 | parent |
| 62 | 115 | medium | zoom21 | parent |
| 63 | 116 | medium | zoom22 | parent |
| 64 | 117 | medium | zoom23 | parent |
| 65 | 118 | medium | zoom24 | parent |
| 66 | 119 | medium | zoom25 | parent |
| 67 | 120 | medium | zoom26 | parent |
| 68 | 121 | medium | zoom27 | parent |
| 69 | 136 | high | areasounds_bobble_container |  |
| 70 | -1 | low |  | unmatched |
| 71 | -1 | low |  | unmatched |
| 72 | 29 | high | acceptaid |  |
| 73 | 30 | high | runmode |  |
| 74 | 31 | high | houseoptions |  |
| 75 | 32 | high | settings_open |  |
| 76 | 33 | high | bondoptions |  |
| 77 | 34 | high | dropdown_container |  |
| 78 | -1 | low |  | unmatched |
| 79 | 36 | high | dropdown_panel |  |
| 80 | -1 | low |  | unmatched |
| 81 | 38 | high | attack_priority_player_buttons |  |
| 82 | 39 | high | attack_priority_npc_buttons |  |
| 83 | -1 | low |  | unmatched |
| 84 | -1 | low |  | unmatched |
| 85 | 42 | high | dropdown_scrollbar |  |
| 86 | 139 | high | tooltip |  |
| 87 | 43 | high | zoom_toggle | onvartransmit,anchored |
| 88 | 44 | high | mouse_zoom |  |
| 89 | 45 | high | mouse_zoom_disable | x,graphic,height,width,anchored |
| 90 | -1 | low |  | unmatched |
| 91 | 127 | medium | zoom28 | parent |
| 92 | 128 | medium | zoom29 | parent |
| 93 | 129 | medium | zoom30 | parent |
| 94 | 130 | medium | zoom31 | parent |
| 95 | 131 | medium | zoom32 | parent |
| 96 | 132 | medium | zoom33 | parent |
| 97 | 133 | medium | zoom34 | parent |
| 98 | 134 | medium | zoom35 | parent |
| 99 | 135 | medium | zoom36 | parent |
| 100 | 56 | high | zoom_slider |  |
| 101 | 57 | high | zoom_slider_bobble |  |
| 102 | -1 | low |  | unmatched |
| 103 | -1 | low |  | unmatched |
| 104 | -1 | low |  | unmatched |
| 105 | -1 | low |  | unmatched |
| 106 | 58 | high | settings_tab | x,width,anchored |
| 107 | -1 | low |  | unmatched |
| 108 | -1 | low |  | unmatched |
| 109 | -1 | low |  | unmatched |
| 110 | -1 | low |  | unmatched |
| 111 | 66 | high | audio_tab | width,anchored |
| 112 | 67 | high | display_tab | x,width,anchored |
| 113 | -1 | low |  | unmatched |
| 114 | -1 | low |  | unmatched |
| 115 | -1 | low |  | unmatched |
| 116 | -1 | low |  | unmatched |
| 117 | -1 | low |  | unmatched |
| 118 | -1 | low |  | unmatched |
| 119 | -1 | low |  | unmatched |
| 120 | 82 | high | tab_icon_3 |  |

New in 241: 28 display_dynamic_setting_2, 35 dropdown_close, 37 background, 40 display_dynamic_setting_1_buttons, 41 display_dynamic_setting_2_buttons, 46 zoom_container, 47 zoom1, 48 zoom2, 49 zoom3, 50 zoom4, 51 zoom5, 52 zoom6, 53 zoom7, 54 zoom8, 55 zoom9, 59 tab_1_1_backing, 60 tab_1_1, 61 tab_1_2_backing, 62 tab_1_2, 63 tab_1_3_backing, 64 tab_1_3, 65 tab_icon_1, 68 tab_line, 69 tab_2_1_backing, 70 tab_2_1, 71 tab_2_2_backing, 72 tab_2_2, 73 tab_2_3_backing, 74 tab_2_3, 75 tab_icon_2, 76 tab_3_1_backing, 77 tab_3_1, 78 tab_3_2_backing, 79 tab_3_2, 80 tab_3_3_backing, 81 tab_3_3, 83 master_holder, 84 master_zoom_container, 94 master_bobble_container, 95 master_slider_bobble, 96 master_icon, 109 music_slider_bobble, 123 sound_slider_bobble, 137 areasounds_slider_bobble

### 134 settings (ADVANCED_SETTINGS)

211: 30 components, 241: 32. Moved: 23. High 29, medium 0, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 7 | 8 | high | content |  |
| 8 | 9 | high | popup |  |
| 9 | 10 | low | searchbar_container | xmode,width |
| 10 | 11 | high | searchbar_image | x,width |
| 11 | 12 | high | search_text | x,width |
| 12 | 13 | high | search_title | width |
| 13 | 14 | high | settings |  |
| 14 | 15 | high | settings_frame |  |
| 15 | 16 | high | categories_frame | height |
| 16 | 17 | high | categories | height |
| 17 | 18 | high | settings_sliderzone |  |
| 18 | 19 | high | settings_content |  |
| 19 | 20 | high | settings_clickzone |  |
| 20 | 21 | high | scrollbar |  |
| 21 | 22 | high | settings_sliderclickzone |  |
| 22 | 23 | high | dropdown_container |  |
| 23 | 24 | high | categories_clickzone |  |
| 24 | 25 | high | categories_tabs |  |
| 25 | 26 | high | dropdown_close |  |
| 26 | 27 | high | dropdown_panel |  |
| 27 | 28 | high | background |  |
| 28 | 29 | high | dropdown_buttons |  |
| 29 | 30 | high | dropdown_scrollbar |  |

New in 241: 7 declutter_button_clickzone, 31 declutter_button

### 216 emote (EMOTE_TAB)

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.

### 239 music (MUSIC_TAB)

211: 16 components, 241: 23. Moved: 15. High 10, medium 1, low 5.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 1 | 6 | high | frame | y,height,parent,anchored |
| 2 | 7 | high | background |  |
| 3 | 8 | high | inner | x,width,anchored |
| 4 | -1 | low |  | unmatched |
| 5 | 11 | high | jukebox | parent,anchored |
| 6 | 12 | high | scrollbar | x,height,parent,anchored |
| 7 | 3 | high | now_playing_title | x,y,height,width,parent,anchored |
| 8 | 4 | high | now_playing_text | op5,op4,x,y,transmitop,op3,op2,parent,anchored |
| 9 | 14 | high | area | type,parent,anchored |
| 10 | -1 | low |  | unmatched |
| 11 | 16 | high | single | type,parent,anchored |
| 12 | -1 | low |  | unmatched |
| 13 | -1 | low |  | unmatched |
| 14 | -1 | low |  | unmatched |
| 15 | 5 | medium | count | y,parent |

New in 241: 1 contents, 2 now_playing, 9 scrollable, 10 overlay, 13 controls, 15 shuffle, 17 skip, 18 playlist, 19 dropdown_container, 20 dropdown, 21 dropdown_content, 22 dropdown_scrollbar

### 429 friends (FRIEND_LIST_TAB)

211: 19 components, 241: 19. Moved: 0. High 19, medium 0, low 0.

### 432 ignore (IGNORE_LIST_TAB)

211: 17 components, 241: 17. Moved: 0. High 17, medium 0, low 0.

### 7 chatchannel_current (CHAT_CHANNEL_TAB)

211: 22 components, 241: 22. Moved: 0. High 22, medium 0, low 0.

### 707 side_channels (REGULAR_CHAT_CHANNELS)

211: 9 components, 241: 9. Moved: 5. High 8, medium 0, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 2 | -1 | low |  | unmatched |
| 3 | 2 | high | tab_0 |  |
| 4 | 3 | high | tab_1 |  |
| 5 | 4 | high | tab_2 |  |
| 6 | 5 | high | tab_3 |  |

New in 241: 6 tab_line

### 701 clans_sidepanel (YOUR_CLAN_TAB)

211: 10 components, 241: 10. Moved: 0. High 10, medium 0, low 0.

### 702 clans_guest_sidepanel (ANOTHER_CLAN_TAB)

211: 11 components, 241: 11. Moved: 0. High 11, medium 0, low 0.

### 76 grouping (GROUPING_TAB)

211: 33 components, 241: 35. Moved: 7. High 33, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 26 | 28 | high | suggestedworld | heightmode,height,width,anchor,parent,anchored |
| 27 | 29 | high | joinleave |  |
| 28 | 30 | high | com_30 |  |
| 29 | 31 | high | joinleave_text |  |
| 30 | 32 | high | teleport |  |
| 31 | 33 | high | com_33 |  |
| 32 | 34 | high | com_34 | onload,onvartransmit,geometry-match |

New in 241: 26 hop_button, 27 hop_button_background

### 182 logout (LOGOUT)

211: 40 components, 241: 24. Moved: 24. High 17, medium 0, low 23.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 16 | -1 | low |  | unmatched |
| 17 | -1 | low |  | unmatched |
| 18 | -1 | low |  | unmatched |
| 19 | -1 | low |  | unmatched |
| 20 | -1 | low |  | unmatched |
| 21 | -1 | low |  | unmatched |
| 22 | -1 | low |  | unmatched |
| 23 | -1 | low |  | unmatched |
| 24 | 18 | high | satisfaction_5scale | onload,geometry-match |
| 25 | -1 | low |  | unmatched |
| 26 | -1 | low |  | unmatched |
| 27 | -1 | low |  | unmatched |
| 28 | -1 | low |  | unmatched |
| 29 | -1 | low |  | unmatched |
| 30 | -1 | low |  | unmatched |
| 31 | 21 | low | satisfaction_3 | onload,op1,width |
| 32 | -1 | low |  | unmatched |
| 33 | -1 | low |  | unmatched |
| 34 | -1 | low |  | unmatched |
| 35 | -1 | low |  | unmatched |
| 36 | -1 | low |  | unmatched |
| 37 | -1 | low |  | unmatched |
| 38 | -1 | low |  | unmatched |
| 39 | -1 | low |  | unmatched |

New in 241: 16 satisfaction_good, 17 satisfaction_bad, 19 satisfaction_1, 20 satisfaction_2, 22 satisfaction_4, 23 satisfaction_5

### 69 worldswitcher (WORLD_SWITCHER)

211: 25 components, 241: 27. Moved: 23. High 24, medium 0, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 2 | 3 | high | title | heightmode,width,height,ymode,xmode,anchor,parent,anchored |
| 3 | 5 | low | close | x,width,tiling,trans,onop,height,ymode,heightmode,parent |
| 4 | 6 | high | middle | height,heightmode,xmode,anchor,anchored |
| 5 | 7 | high | com_7 |  |
| 6 | 8 | high | com_8 |  |
| 7 | 9 | high | com_9 |  |
| 8 | 10 | high | list |  |
| 9 | 11 | high | sortarea |  |
| 10 | 12 | high | sort_member |  |
| 11 | 13 | high | sort_number |  |
| 12 | 14 | high | sort_country |  |
| 13 | 15 | high | sort_players |  |
| 14 | 16 | high | sort_theme |  |
| 15 | 17 | high | scrollarea |  |
| 16 | 18 | high | buttons |  |
| 17 | 19 | high | info |  |
| 18 | 20 | high | scrollbar |  |
| 19 | 21 | high | bottom | xmode,anchor,anchored |
| 20 | 22 | high | nofavourites |  |
| 21 | 23 | high | favourite_1 |  |
| 22 | 24 | high | favourite_2 |  |
| 23 | 25 | high | logout |  |
| 24 | 26 | high | tooltip |  |

New in 241: 2 top, 4 options

### 12 bankmain (BANK)

211: 124 components, 241: 158. Moved: 116. High 107, medium 1, low 16.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 8 | 125 | high | gim_storage | x,y,hide,height,ymode,width,parent,anchored |
| 9 | 8 | high | capacity |  |
| 10 | 9 | high | items_container | hide,geometry-match |
| 11 | 10 | high | tabs |  |
| 12 | 11 | high | com_11 |  |
| 13 | 12 | high | items |  |
| 14 | 13 | high | scrollbar |  |
| 15 | 22 | high | bottom | y,height,anchored |
| 16 | -1 | low |  | unmatched |
| 17 | 23 | high | swap_insert | type,anchored |
| 18 | -1 | low |  | unmatched |
| 19 | -1 | low |  | unmatched |
| 20 | -1 | low |  | unmatched |
| 21 | -1 | low |  | unmatched |
| 22 | -1 | low |  | unmatched |
| 23 | -1 | low |  | unmatched |
| 24 | 25 | high | note | type,anchored |
| 25 | -1 | low |  | unmatched |
| 26 | 28 | high | quantity_layer | widthmode,x,width,anchored |
| 27 | -1 | low |  | unmatched |
| 28 | 29 | high | quantity1 | type,anchored |
| 29 | 30 | high | quantity1_text | x,y |
| 30 | 31 | high | quantity5 | type,anchored |
| 31 | 32 | high | quantity5_text | x,y |
| 32 | 33 | high | quantity10 | type,anchored |
| 33 | 34 | high | quantity10_text | x,y |
| 34 | 35 | high | quantityx | type,anchored |
| 35 | 36 | high | quantityx_text | x,y |
| 36 | 37 | high | quantityall | type,anchored |
| 37 | 38 | high | quantityall_text | x,y |
| 38 | 40 | high | placeholder | x,anchored |
| 39 | 41 | high | placeholder_graphic | x,graphicshadow,anchored |
| 40 | 42 | high | search | type,anchored |
| 41 | 43 | high | search_graphic | x,y,graphicshadow,ymode,anchored |
| 42 | 47 | high | depositinv | x,anchored |
| 43 | 48 | high | depositinv_graphic | graphicshadow,anchored |
| 44 | 49 | high | depositworn | x,anchored |
| 45 | 50 | high | depositworn_graphic | x,graphicshadow,anchored |
| 46 | 54 | high | incinerator_target | x,y,hide,height,width,anchored |
| 47 | 55 | high | incinerator_confirm |  |
| 48 | 57 | high | com_57 |  |
| 49 | 60 | high | menu_container |  |
| 50 | 129 | high | tabdisplay_select_1 | y,widthmode,height,ymode,xmode,width,heightmode,parent,anchored |
| 51 | 132 | high | sideops_toggle | y,height,ymode,anchored |
| 52 | 131 | high | banktut_toggle | y,height,ymode,anchored |
| 53 | 130 | high | incinerator_toggle | y,height,ymode,anchored |
| 54 | 135 | high | depositworn_toggle | y,height,ymode,anchored |
| 55 | 134 | high | depositinv_toggle | y,height,ymode,anchored |
| 56 | 140 | high | release_placeholders | onload,x,y,op6,transmitop,op1,width,anchored |
| 57 | 142 | high | bank_fillers | x,y,anchored |
| 58 | 143 | high | bank_filler_1 |  |
| 59 | -1 | low |  | unmatched |
| 60 | 145 | high | bank_filler_10 |  |
| 61 | -1 | low |  | unmatched |
| 62 | 147 | high | bank_filler_50 |  |
| 63 | -1 | low |  | unmatched |
| 64 | 149 | high | bank_filler_x |  |
| 65 | -1 | low |  | unmatched |
| 66 | 151 | high | bank_filler_all |  |
| 67 | -1 | low |  | unmatched |
| 68 | 153 | high | bank_filler_confirm |  |
| 69 | 61 | high | wornitems_container | hide,geometry-match |
| 70 | 62 | high | com_62 |  |
| 71 | 63 | high | com_63 |  |
| 72 | 64 | high | com_64 |  |
| 73 | 65 | high | com_65 |  |
| 74 | 66 | high | com_66 |  |
| 75 | 67 | high | com_67 |  |
| 76 | 68 | high | wornslot0 |  |
| 77 | 69 | high | wornslot1 |  |
| 78 | 70 | high | wornslot2 |  |
| 79 | 71 | high | wornslot3 |  |
| 80 | 72 | high | wornslot4 |  |
| 81 | 73 | high | wornslot5 |  |
| 82 | 74 | high | wornslot7 |  |
| 83 | 75 | high | wornslot9 |  |
| 84 | 76 | high | wornslot10 |  |
| 85 | 77 | high | wornslot12 |  |
| 86 | 78 | high | wornslot13 |  |
| 87 | 80 | high | stat_group | onload,anchored |
| 88 | -1 | low |  | unmatched |
| 89 | 81 | high | com_81 |  |
| 90 | 82 | high | stabatt | onmouserepeat,onmouseleave,anchored |
| 91 | 83 | high | slashatt | onmouserepeat,onmouseleave,anchored |
| 92 | 84 | high | crushatt | onmouserepeat,onmouseleave,anchored |
| 93 | 85 | high | magicatt | y,onmouserepeat,onmouseleave,anchored |
| 94 | 86 | high | rangeatt | y,onmouserepeat,onmouseleave,anchored |
| 95 | 87 | high | com_87 |  |
| 96 | 88 | high | stabdef | onmouserepeat,onmouseleave,anchored |
| 97 | 89 | high | slashdef | onmouserepeat,onmouseleave,anchored |
| 98 | 90 | high | crushdef | onmouserepeat,onmouseleave,anchored |
| 99 | 91 | high | magicdef | y,onmouserepeat,onmouseleave,anchored |
| 100 | 92 | high | rangedef | y,onmouserepeat,onmouseleave,anchored |
| 101 | 93 | high | com_93 |  |
| 102 | 94 | high | meleestrength | onmouserepeat,onmouseleave,anchored |
| 103 | 95 | high | rangestrength | onmouserepeat,onmouseleave,anchored |
| 104 | 96 | high | magicdamage | onmouserepeat,onmouseleave,anchored |
| 105 | 97 | high | prayer | onmouserepeat,onmouseleave,anchored |
| 106 | 98 | high | com_98 |  |
| 107 | 99 | high | typemultiplier | onmouserepeat,onmouseleave,anchored |
| 108 | 100 | high | slayermultiplier | onmouserepeat,onmouseleave,anchored |
| 109 | 101 | high | com_101 |  |
| 110 | 103 | high | set_bonus |  |
| 111 | 104 | high | com_104 |  |
| 112 | 105 | high | com_105 | onload,oninvtransmit,geometry-match |
| 113 | 106 | high | menu_button | x,y,height,xmode,width,anchored |
| 114 | 107 | high | wornitems_button | x,y,height,xmode,width,anchored |
| 115 | 108 | high | bank_highlight |  |
| 116 | 109 | high | popup |  |
| 117 | 111 | high | tooltip |  |
| 118 | 112 | high | com_112 | y |
| 119 | -1 | low |  | unmatched |
| 120 | 114 | medium | scrollbar_1 | x,y,height |
| 121 | 115 | high | previous_page |  |
| 122 | 116 | high | attackspeedbase | onmouserepeat,onmouseleave,geometry-match |
| 123 | 117 | high | attackspeedactual | onmouserepeat,onmouseleave,geometry-match |

New in 241: 14 banktags_display_container, 15 banktags_display_draglayer, 16 banktags_display_items, 17 banktags_display_scrollbar, 18 banktags_headers, 19 potionstore_container, 20 potionstore_background, 21 banktags_header_separator, 24 swap_insert_graphic, 26 note_graphic, 27 com_27, 39 com_39, 44 deposit_line, 45 depositcontainers, 46 depositcontainers_graphic, 51 horizontal_line, 52 popup_button_out, 53 storage_popup_tab, 56 banktags_popup_stringexport_container, 58 potionstore_items, 59 potionstore_scrollbar, 79 extra_quiver_slot, 102 next_page, 110 dropdown_container, 113 set_effect, 118 extra_quiver_ammo_graphic, 119 extra_quiver_ammo, 120 dropdown, 121 dropdown_content, 122 dropdown_scroller, 123 storage_popup_tab_frame, 124 popup_button_in, 126 potionstore_button, 127 tabdisplay, 128 com_128, 133 bankops_toggle, 136 depositcontainers_toggle, 137 popup_tab_forgetpos, 138 depositpotion_toggle, 139 chuggingbarrel_toggle, 141 locks, 144 bank_filler_1_text, 146 bank_filler_10_text, 148 bank_filler_50_text, 150 bank_filler_x_text, 152 bank_filler_all_text, 154 com_154, 155 banktags_popup_stringexport_outer, 156 banktags_popup_stringexport_window, 157 banktags_popup_stringexport_inputfield

### 15 bankside (BANK_INVENTORY)

211: 20 components, 241: 97. Moved: 14. High 15, medium 4, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 6 | 12 | high | league_secondinv_container | widthmode,height,ymode,xmode,width,heightmode,anchored |
| 7 | 58 | medium | bankside_highlight | ymode,xmode |
| 8 | 6 | high | lootingbag_bankall |  |
| 9 | 7 | high | com_7 | width,widthmode,xmode,anchored |
| 10 | 8 | high | lootingbag_dismiss |  |
| 11 | 9 | high | lootingbag_frame | widthmode,height,width,heightmode,anchored |
| 12 | 10 | high | com_10 |  |
| 13 | 11 | high | lootingbag_items |  |
| 14 | 60 | medium | prepot_device_bankall | graphic,op1,parent |
| 15 | 62 | low | com_62 | text,width,widthmode,xmode,parent |
| 16 | 63 | medium | prepot_device_dismiss | parent |
| 17 | 64 | medium | prepot_device_frame | widthmode,height,width,heightmode,parent |
| 18 | 65 | high | com_65 |  |
| 19 | 66 | high | prepot_device_contents_container |  |

New in 241: 13 league_secondinv_bankall, 14 com_14, 15 league_secondinv_dismiss, 16 league_secondinv_frame, 17 com_17, 18 league_secondinv_items, 19 runepouch_container, 20 runepouch_bankall, 21 com_21, 22 runepouch_dismiss, 23 runepouch_frame, 24 com_24, 25 runepouch_contents, 26 runepouch_top, 27 runepouch_load_a, 28 runepouch_load_b, 29 runepouch_load_c, 30 runepouch_load_d, 31 runepouch_loadout_scrollbar, 32 runepouch_loadout_container, 33 runepouch_loadout_a, 34 runepouch_name_a, 35 runepouch_select_container, 36 runepouch_name_select_container, 37 runepouch_loadout_b, 38 runepouch_name_b, 39 com_39, 40 runepouch_loadout_c, 41 runepouch_name_c, 42 runepouch_select_list, 43 runepouch_loadout_d, 44 runepouch_name_d, 45 runepouch_loadout_e, 46 runepouch_name_e, 47 runepouch_loadout_f, 48 runepouch_name_f, 49 runepouch_loadout_g, 50 runepouch_name_g, 51 runepouch_loadout_h, 52 runepouch_name_h, 53 runepouch_loadout_i, 54 runepouch_name_i, 55 runepouch_loadout_j, 56 runepouch_name_j, 57 runepouch_select_scrollbar, 59 prepot_device_container, 61 prepot_device_fill, 67 prepot_device_contents_bg, 68 prepot_device_contents, 69 com_69, 70 prepot_device_loadouts_scrollbar, 71 prepot_device_loadouts, 72 prepot_device_loadout_0, 73 prepot_device_loadout_1, 74 prepot_device_loadout_2, 75 prepot_device_loadout_3, 76 prepot_device_loadout_1_load, 77 prepot_device_loadout_1_save, 78 prepot_device_loadout_1_contents, 79 prepot_device_loadout_2_load, 80 prepot_device_loadout_2_save, 81 prepot_device_loadout_2_contents, 82 prepot_device_loadout_3_load, 83 prepot_device_loadout_3_save, 84 prepot_device_loadout_3_contents, 85 prepot_device_loadout_0_load, 86 prepot_device_loadout_0_save, 87 prepot_device_loadout_0_contents, 88 runepouch_load_e, 89 runepouch_load_f, 90 runepouch_load_g, 91 runepouch_load_h, 92 runepouch_load_i, 93 runepouch_load_j, 94 com_94, 95 runepouch_name_select_list, 96 runepouch_name_select_scrollbar

### 231 chat_left

211: 7 components, 241: 7. Moved: 0. High 7, medium 0, low 0.

### 217 chat_right

211: 7 components, 241: 7. Moved: 0. High 7, medium 0, low 0.

### 219 chatmenu

211: 2 components, 241: 2. Moved: 0. High 2, medium 0, low 0.

### 193 objectbox

211: 3 components, 241: 3. Moved: 0. High 3, medium 0, low 0.

### 11 objectbox_double

211: 5 components, 241: 5. Moved: 0. High 5, medium 0, low 0.

### 229 messagebox

211: 3 components, 241: 5. Moved: 2. High 3, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 1 | 3 | high | text | xmode,textalignv,textlineheight,widthmode,width,anchor,parent,anchored |
| 2 | 4 | high | continue | width,widthmode,xmode,anchor,parent,anchored |

New in 241: 1 safezone, 2 content

### 233 levelup_display

211: 53 components, 241: 59. Moved: 20. High 52, medium 1, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 33 | 35 | medium | com_35 | parent |
| 34 | 36 | high | mining |  |
| 35 | 37 | high | com_37 |  |
| 36 | 38 | high | prayer |  |
| 37 | 39 | high | com_39 |  |
| 38 | 40 | high | ranged |  |
| 39 | 41 | high | com_41 |  |
| 40 | 42 | high | com_42 |  |
| 41 | 43 | high | runecraft |  |
| 42 | 44 | high | com_44 |  |
| 43 | 45 | high | slayer |  |
| 44 | 46 | high | com_46 |  |
| 45 | 47 | high | smithing |  |
| 46 | 48 | high | com_48 |  |
| 47 | 49 | high | strength |  |
| 48 | 50 | high | com_50 |  |
| 49 | 51 | high | thieving |  |
| 50 | 52 | high | com_52 |  |
| 51 | 53 | high | woodcutting |  |
| 52 | 54 | high | com_54 |  |

New in 241: 33 com_33, 34 magic, 55 combat, 56 com_56, 57 sailing, 58 com_58

### 270 skillmulti

211: 25 components, 241: 35. Moved: 12. High 25, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 13 | 14 | high | bottom | x,y,height,ymode,width,anchor,parent,anchored |
| 14 | 15 | high | a |  |
| 15 | 16 | high | b |  |
| 16 | 17 | high | c |  |
| 17 | 18 | high | d |  |
| 18 | 19 | high | e |  |
| 19 | 20 | high | f |  |
| 20 | 21 | high | g |  |
| 21 | 22 | high | h |  |
| 22 | 23 | high | i |  |
| 23 | 24 | high | j |  |
| 24 | 34 | high | tooltip |  |

New in 241: 13 bottom_holder, 25 k, 26 l, 27 m, 28 n, 29 o, 30 p, 31 q, 32 r, 33 scrollbar

### 300 shopmain (SHOP)

211: 19 components, 241: 19. Moved: 2. High 18, medium 0, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 17 | 18 | high | scrollbar |  |
| 18 | -1 | low |  | unmatched |

New in 241: 17 note_button

### 301 shopside (SHOP_INVENTORY)

211: 1 components, 241: 1. Moved: 0. High 1, medium 0, low 0.

### 160 orbs (ORBS)

211: 54 components, 241: 57. Moved: 53. High 54, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 1 | 2 | high | cr_backing |  |
| 2 | 3 | high | cr_indicator |  |
| 3 | 4 | high | cr_button |  |
| 4 | 5 | high | cr_icon |  |
| 5 | 6 | high | xp_drops |  |
| 6 | 7 | high | orb_health |  |
| 7 | 8 | high | health_backing |  |
| 8 | 9 | high | healthbutton |  |
| 9 | 10 | high | health_text |  |
| 10 | 11 | high | health_indicator |  |
| 11 | 12 | high | health_extrapoisonhalf |  |
| 12 | 13 | high | health_extrapoisonhalf_contents |  |
| 13 | 14 | high | orb_health_empty |  |
| 14 | 15 | high | health_empty_contents |  |
| 15 | 16 | high | health_regen |  |
| 16 | 17 | high | orb_health_heart_icon |  |
| 17 | 18 | high | orb_prayer |  |
| 18 | 19 | high | prayer_backing |  |
| 19 | 20 | high | prayerbutton |  |
| 20 | 21 | high | prayer_text |  |
| 21 | 22 | high | prayer_indicator |  |
| 22 | 23 | high | orb_prayer_empty |  |
| 23 | 24 | high | com_24 |  |
| 24 | 25 | high | prayer_icon |  |
| 25 | 26 | high | orb_runenergy |  |
| 26 | 27 | high | runenergy_backing |  |
| 27 | 28 | high | runbutton |  |
| 28 | 29 | high | runenergy_text |  |
| 29 | 30 | high | runenergy_indicator |  |
| 30 | 31 | high | orb_runenergy_empty |  |
| 31 | 32 | high | com_32 |  |
| 32 | 33 | high | runenergy_icon |  |
| 33 | 34 | high | orb_specenergy | onload,geometry-match |
| 34 | 35 | high | specenergy_backing |  |
| 35 | 36 | high | specbutton | op2,transmitop,geometry-match |
| 36 | 37 | high | specenergy_text |  |
| 37 | 38 | high | specenergy_indicator |  |
| 38 | 39 | high | orb_specenergy_empty |  |
| 39 | 40 | high | com_40 |  |
| 40 | 41 | high | spec_regen |  |
| 41 | 42 | high | specenergy_icon |  |
| 42 | 43 | high | orb_store |  |
| 43 | 44 | high | store_backing |  |
| 44 | 45 | high | store_indicator |  |
| 45 | 46 | high | store_button |  |
| 46 | 47 | high | store_icon |  |
| 47 | 48 | high | orb_contentrecom |  |
| 48 | 49 | high | orb_worldmap | onload,geometry-match |
| 49 | 50 | high | wiki |  |
| 50 | 52 | high | wiki_icon |  |
| 51 | 53 | high | wiki_icon_graphic |  |
| 52 | 54 | high | worldmap_backing |  |
| 53 | 55 | high | worldmap |  |

New in 241: 1 triggers, 51 tooltip, 56 worldmap_tooltip

### 122 xp_drops (EXPERIENCE_DROPS_WINDOW)

211: 26 components, 241: 26. Moved: 0. High 26, medium 0, low 0.

### 0 100guide_eggs_overlay

211: 23 components, 241: 23. Moved: 0. High 23, medium 0, low 0.

### 1 100guide_flour_overlay

211: 11 components, 241: 11. Moved: 0. High 11, medium 0, low 0.

### 4 deathkeep (ITEMS_KEPT_ON_DEATH)

211: 19 components, 241: 19. Moved: 0. High 19, medium 0, low 0.

### 6 silver_crafting (SILVER_JEWELLERY_INTERFACE)

211: 38 components, 241: 38. Moved: 0. High 38, medium 0, low 0.

### 14 bankpin_settings (BANK_PIN_SETTINGS)

211: 37 components, 241: 37. Moved: 0. High 37, medium 0, low 0.

### 16 oculus

211: 34 components, 241: 34. Moved: 0. High 34, medium 0, low 0.

### 21 raids_challenge (RAIDS_SCOREBOARD)

211: 19 components, 241: 19. Moved: 0. High 19, medium 0, low 0.

### 23 tob_chests (TOB_REWARDS)

211: 13 components, 241: 13. Moved: 0. High 9, medium 2, low 2.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 2 | 2 | low | content | y,height,ymode,width |
| 7 | 7 | medium | bank_icon | x,xmode |
| 9 | 9 | medium | inventory_icon | x,xmode |
| 12 | 12 | low | items | x,y |

### 24 barrows_overlay (BARROWS_OVERLAY)

211: 13 components, 241: 13. Moved: 0. High 13, medium 0, low 0.

### 25 barrows_puzzle (BARROWS_PUZZLE)

211: 18 components, 241: 18. Moved: 0. High 18, medium 0, low 0.

### 26 pirate_combilock (BOOK)

211: 27 components, 241: 27. Moved: 0. High 27, medium 0, low 0.

### 28 tob_hud (TOB_PARTY)

211: 39 components, 241: 39. Moved: 0. High 39, medium 0, low 0.

### 35 jad_challenge_scoreboard (STONE_TEXT_INTERFACE)

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.

### 42 boardgames_runesquares_overlay

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.

### 50 tob_partydetails (TOB_PARTY_INFORMATION)

211: 73 components, 241: 73. Moved: 0. High 73, medium 0, low 0.

### 54 castlewars_catapult (CASTLE_WARS_CATAPULT)

211: 26 components, 241: 26. Moved: 0. High 26, medium 0, low 0.

### 58 castlewars_status_overlay_saradomin (CASTLE_WARS_OVERLAY)

211: 26 components, 241: 29. Moved: 24. High 24, medium 1, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 2 | -1 | low |  | unmatched |
| 3 | 2 | high | dodger_side |  |
| 4 | 3 | medium | sidebox | height |
| 5 | 4 | high | com_4 |  |
| 6 | 5 | high | maindoor |  |
| 7 | 6 | high | com_6 |  |
| 8 | 7 | high | sidedoor |  |
| 9 | 8 | high | com_8 |  |
| 10 | 9 | high | tunnel1 |  |
| 11 | 10 | high | com_10 |  |
| 12 | 11 | high | tunnel2 |  |
| 13 | 12 | high | com_12 |  |
| 14 | 13 | high | catapult |  |
| 15 | 16 | high | dodger_top |  |
| 16 | 19 | high | content_top |  |
| 17 | 20 | high | scores | onload,onvartransmit,geometry-match |
| 18 | 21 | high | com_21 |  |
| 19 | 22 | high | com_22 |  |
| 20 | 23 | high | saradominflag |  |
| 21 | 24 | high | flag_separator |  |
| 22 | 25 | high | zamorakflag |  |
| 23 | 26 | high | castlewars_saradomin_eject |  |
| 24 | 27 | high | zamorakscore |  |
| 25 | 28 | high | saradominscore |  |

New in 241: 14 com_14, 15 barricades, 17 dodger_timer, 18 timer

### 64 league_rank

211: 50 components, 241: 18. Moved: 50. High 0, medium 0, low 50.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | -1 | low |  | unmatched |
| 1 | -1 | low |  | unmatched |
| 2 | -1 | low |  | unmatched |
| 3 | -1 | low |  | unmatched |
| 4 | -1 | low |  | unmatched |
| 5 | -1 | low |  | unmatched |
| 6 | -1 | low |  | unmatched |
| 7 | -1 | low |  | unmatched |
| 8 | -1 | low |  | unmatched |
| 9 | -1 | low |  | unmatched |
| 10 | -1 | low |  | unmatched |
| 11 | -1 | low |  | unmatched |
| 12 | -1 | low |  | unmatched |
| 13 | -1 | low |  | unmatched |
| 14 | -1 | low |  | unmatched |
| 15 | -1 | low |  | unmatched |
| 16 | -1 | low |  | unmatched |
| 17 | -1 | low |  | unmatched |
| 18 | -1 | low |  | unmatched |
| 19 | -1 | low |  | unmatched |
| 20 | -1 | low |  | unmatched |
| 21 | -1 | low |  | unmatched |
| 22 | -1 | low |  | unmatched |
| 23 | -1 | low |  | unmatched |
| 24 | -1 | low |  | unmatched |
| 25 | -1 | low |  | unmatched |
| 26 | -1 | low |  | unmatched |
| 27 | -1 | low |  | unmatched |
| 28 | -1 | low |  | unmatched |
| 29 | -1 | low |  | unmatched |
| 30 | -1 | low |  | unmatched |
| 31 | -1 | low |  | unmatched |
| 32 | -1 | low |  | unmatched |
| 33 | -1 | low |  | unmatched |
| 34 | -1 | low |  | unmatched |
| 35 | -1 | low |  | unmatched |
| 36 | -1 | low |  | unmatched |
| 37 | -1 | low |  | unmatched |
| 38 | -1 | low |  | unmatched |
| 39 | -1 | low |  | unmatched |
| 40 | -1 | low |  | unmatched |
| 41 | -1 | low |  | unmatched |
| 42 | -1 | low |  | unmatched |
| 43 | -1 | low |  | unmatched |
| 44 | -1 | low |  | unmatched |
| 45 | -1 | low |  | unmatched |
| 46 | -1 | low |  | unmatched |
| 47 | -1 | low |  | unmatched |
| 48 | -1 | low |  | unmatched |
| 49 | -1 | low |  | unmatched |

New in 241: 0 infinity, 1 universe, 2 frame, 3 progress_bar, 4 divider, 5 content, 6 bronze, 7 iron, 8 steel, 9 mithril, 10 adamant, 11 rune, 12 dragon, 13 close_button, 14 league_btn_menu, 15 league_menu_overlay, 16 league_menu_frame, 17 tooltip

### 67 anma_reward (AVAS_DEVICES)

211: 17 components, 241: 17. Moved: 0. High 17, medium 0, low 0.

### 68 slayer_partner

211: 8 components, 241: 8. Moved: 1. High 3, medium 2, low 3.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | -1 | low |  | unmatched |
| 1 | 1 | low | frame | widthmode,height,width,heightmode,parent |
| 2 | 2 | low | contents | height,heightmode,parent |
| 3 | 3 | medium | com_3 | modelorigin,x,y |
| 5 | 5 | medium | explanation | height,heightmode |

New in 241: 0 universe

### 72 sailing_menu (SHIP_DESTINATION_CHART)

211: 39 components, 241: 4. Moved: 38. High 1, medium 1, low 37.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 1 | -1 | low |  | unmatched |
| 2 | -1 | low |  | unmatched |
| 3 | -1 | low |  | unmatched |
| 4 | -1 | low |  | unmatched |
| 5 | -1 | low |  | unmatched |
| 6 | -1 | low |  | unmatched |
| 7 | -1 | low |  | unmatched |
| 8 | -1 | low |  | unmatched |
| 9 | -1 | low |  | unmatched |
| 10 | -1 | low |  | unmatched |
| 11 | -1 | low |  | unmatched |
| 12 | -1 | low |  | unmatched |
| 13 | -1 | low |  | unmatched |
| 14 | -1 | low |  | unmatched |
| 15 | -1 | low |  | unmatched |
| 16 | -1 | low |  | unmatched |
| 17 | -1 | low |  | unmatched |
| 18 | -1 | low |  | unmatched |
| 19 | -1 | low |  | unmatched |
| 20 | -1 | low |  | unmatched |
| 21 | -1 | low |  | unmatched |
| 22 | -1 | low |  | unmatched |
| 23 | -1 | low |  | unmatched |
| 24 | -1 | low |  | unmatched |
| 25 | -1 | low |  | unmatched |
| 26 | -1 | low |  | unmatched |
| 27 | -1 | low |  | unmatched |
| 28 | -1 | low |  | unmatched |
| 29 | -1 | low |  | unmatched |
| 30 | -1 | low |  | unmatched |
| 31 | -1 | low |  | unmatched |
| 32 | -1 | low |  | unmatched |
| 33 | -1 | low |  | unmatched |
| 34 | -1 | low |  | unmatched |
| 35 | -1 | low |  | unmatched |
| 36 | 2 | medium | com_2 | parent |
| 37 | -1 | low |  | unmatched |
| 38 | -1 | low |  | unmatched |

New in 241: 1 com_1, 3 content

### 73 trail_rewardscreen (CLUE_SCROLL_REWARD)

211: 5 components, 241: 5. Moved: 0. High 5, medium 0, low 0.

### 74 gim_raffle (SETTINGS_GIM_RAFFLE)

211: 18 components, 241: 18. Moved: 0. High 18, medium 0, low 0.

### 78 rareitems_diango (DIANGO_ITEM_RETRIEVAL)

211: 5 components, 241: 5. Moved: 0. High 5, medium 0, low 0.

### 79 dov_night (REGULAR_LECTERN/TELETAB_CREATION)

211: 26 components, 241: 2. Moved: 25. High 0, medium 1, low 25.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | -1 | low |  | unmatched |
| 1 | 1 | medium | background | colour,trans,height,width,parent |
| 2 | -1 | low |  | unmatched |
| 3 | -1 | low |  | unmatched |
| 4 | -1 | low |  | unmatched |
| 5 | -1 | low |  | unmatched |
| 6 | -1 | low |  | unmatched |
| 7 | -1 | low |  | unmatched |
| 8 | -1 | low |  | unmatched |
| 9 | -1 | low |  | unmatched |
| 10 | -1 | low |  | unmatched |
| 11 | -1 | low |  | unmatched |
| 12 | -1 | low |  | unmatched |
| 13 | -1 | low |  | unmatched |
| 14 | -1 | low |  | unmatched |
| 15 | -1 | low |  | unmatched |
| 16 | -1 | low |  | unmatched |
| 17 | -1 | low |  | unmatched |
| 18 | -1 | low |  | unmatched |
| 19 | -1 | low |  | unmatched |
| 20 | -1 | low |  | unmatched |
| 21 | -1 | low |  | unmatched |
| 22 | -1 | low |  | unmatched |
| 23 | -1 | low |  | unmatched |
| 24 | -1 | low |  | unmatched |
| 25 | -1 | low |  | unmatched |

New in 241: 0 universe

### 80 toplevel_spectator

211: 44 components, 241: 47. Moved: 21. High 44, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 23 | 24 | high | sidemodal |  |
| 24 | 25 | high | side0 |  |
| 25 | 26 | high | side1 |  |
| 26 | 27 | high | side2 |  |
| 27 | 28 | high | side3 |  |
| 28 | 29 | high | side4 |  |
| 29 | 30 | high | side5 |  |
| 30 | 31 | high | side6 |  |
| 31 | 32 | high | side7 |  |
| 32 | 33 | high | side8 |  |
| 33 | 34 | high | side9 |  |
| 34 | 35 | high | side10 |  |
| 35 | 36 | high | side11 |  |
| 36 | 37 | high | side12 |  |
| 37 | 38 | high | side13 |  |
| 38 | 39 | high | sidecrm |  |
| 39 | 40 | high | stat_boosts_hud |  |
| 40 | 41 | high | buff_bar |  |
| 41 | 42 | high | notifications |  |
| 42 | 43 | high | tli_listener |  |
| 43 | 46 | high | popout |  |

New in 241: 23 chat_container, 44 mouseover, 45 ui_highlights

### 81 wilderness_lootingbag (LOOTING_BAG)

211: 8 components, 241: 8. Moved: 0. High 8, medium 0, low 0.

### 82 textfield_csv (HAIRDRESSER)

211: 10 components, 241: 11. Moved: 10. High 0, medium 0, low 10.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | -1 | low |  | unmatched |
| 1 | -1 | low |  | unmatched |
| 2 | -1 | low |  | unmatched |
| 3 | -1 | low |  | unmatched |
| 4 | -1 | low |  | unmatched |
| 5 | -1 | low |  | unmatched |
| 6 | -1 | low |  | unmatched |
| 7 | -1 | low |  | unmatched |
| 8 | -1 | low |  | unmatched |
| 9 | -1 | low |  | unmatched |

New in 241: 0 universe, 1 clamp, 2 fader, 3 window, 4 frame, 5 container, 6 instructions, 7 textfield_tab_content, 8 list_tab_content, 9 cancel, 10 submit

### 90 pvp_icons (WILDERNESS_OVERLAY)

211: 55 components, 241: 55. Moved: 0. High 55, medium 0, low 0.

### 94 chatchannel_setup (CLAN_CHAT_SETUP)

211: 31 components, 241: 31. Moved: 0. High 31, medium 0, low 0.

### 95 messagescroll_handwriting_2

211: 14 components, 241: 14. Moved: 0. High 14, medium 0, low 0.

### 100 tournament_supplies (TOURNAMENT_SUPPLIES)

211: 34 components, 241: 34. Moved: 0. High 34, medium 0, low 0.

### 103 pattern_next

211: 22 components, 241: 22. Moved: 0. High 22, medium 0, low 0.

### 108 cr_tutorial (DUEL_WINNINGS/DUEL_SCOREBOARD)

211: 7 components, 241: 7. Moved: 0. High 7, medium 0, low 0.

### 125 farming_tools (FARMING_STORAGE)

211: 20 components, 241: 23. Moved: 0. High 16, medium 4, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 4 | 4 | medium | quantity_1 | x,xmode |
| 5 | 5 | medium | quantity_5 | x,xmode |
| 6 | 6 | medium | quantity_x | x,xmode |
| 7 | 7 | medium | quantity_all | x,xmode |

New in 241: 20 deposit_all, 21 depositinv, 22 depositinv_graphic

### 126 farming_tools_side (FARMING_STORAGE_INVENTORY)

211: 13 components, 241: 13. Moved: 0. High 13, medium 0, low 0.

### 128 hosidius_seedbox (SEED_BOX)

211: 13 components, 241: 13. Moved: 0. High 13, medium 0, low 0.

### 131 castlewars_waitingroom (CASTLE_WARS_LOBBY_OVERLAY)

211: 3 components, 241: 3. Moved: 0. High 3, medium 0, low 0.

### 136 unmorph (UNMORPH_TAB)

211: 7 components, 241: 7. Moved: 0. High 7, medium 0, low 0.

### 137 xpdrops_setup (EXPERIENCE_TRACKER)

211: 62 components, 241: 62. Moved: 0. High 62, medium 0, low 0.

### 138 glidermap (GNOME_GLIDER)

211: 27 components, 241: 27. Moved: 0. High 27, medium 0, low 0.

### 140 graphical_multi

211: 7 components, 241: 7. Moved: 0. High 7, medium 0, low 0.

### 148 pet_insurance (PET_INSURANCE)

211: 39 components, 241: 39. Moved: 0. High 38, medium 1, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 29 | 29 | medium | insurance_credits_label | text |

### 153 questscroll (QUEST_COMPLETED)

211: 18 components, 241: 18. Moved: 0. High 18, medium 0, low 0.

### 154 deadman_spectator (TOURNAMENT_SPECTATING)

211: 63 components, 241: 63. Moved: 0. High 63, medium 0, low 0.

### 155 barrows_reward (BARROWS_REWARDS)

211: 4 components, 241: 4. Moved: 0. High 4, medium 0, low 0.

### 156 bugreport (BUG_REPORT_FORM)

211: 27 components, 241: 27. Moved: 0. High 27, medium 0, low 0.

### 157 ii_scroll (PURO_PURO_IMPLING_OVERLAY)

211: 26 components, 241: 26. Moved: 0. High 26, medium 0, low 0.

### 178 pvp_store (BOUNTY_HUNTER_STORE)

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.

### 179 farming_view

211: 281 components, 241: 329. Moved: 230. High 281, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 51 | 57 | high | flower_1_back |  |
| 52 | 58 | high | flower_1_pic |  |
| 53 | 59 | high | flower_1_front |  |
| 54 | 60 | high | flower_2_back |  |
| 55 | 61 | high | flower_2_pic |  |
| 56 | 62 | high | flower_2_front |  |
| 57 | 63 | high | flower_3_back |  |
| 58 | 64 | high | flower_3_pic |  |
| 59 | 65 | high | flower_3_front |  |
| 60 | 66 | high | flower_4_back |  |
| 61 | 67 | high | flower_4_pic |  |
| 62 | 68 | high | flower_4_front |  |
| 63 | 69 | high | flower_5_back |  |
| 64 | 70 | high | flower_5_pic |  |
| 65 | 71 | high | flower_5_front |  |
| 66 | 72 | high | flower_6_back |  |
| 67 | 73 | high | flower_6_pic |  |
| 68 | 74 | high | flower_6_front |  |
| 69 | 75 | high | flower_7_back |  |
| 70 | 76 | high | flower_7_pic |  |
| 71 | 77 | high | flower_7_front |  |
| 72 | 84 | high | herb_1_back |  |
| 73 | 85 | high | herb_1_pic |  |
| 74 | 86 | high | herb_1_front |  |
| 75 | 87 | high | herb_2_back |  |
| 76 | 88 | high | herb_2_pic |  |
| 77 | 89 | high | herb_2_front |  |
| 78 | 90 | high | herb_3_back |  |
| 79 | 91 | high | herb_3_pic |  |
| 80 | 92 | high | herb_3_front |  |
| 81 | 93 | high | herb_4_back |  |
| 82 | 94 | high | herb_4_pic |  |
| 83 | 95 | high | herb_4_front |  |
| 84 | 96 | high | herb_5_back |  |
| 85 | 97 | high | herb_5_pic |  |
| 86 | 98 | high | herb_5_front |  |
| 87 | 99 | high | herb_6_back |  |
| 88 | 100 | high | herb_6_pic |  |
| 89 | 101 | high | herb_6_front |  |
| 90 | 102 | high | herb_7_back |  |
| 91 | 103 | high | herb_7_pic |  |
| 92 | 104 | high | herb_7_front |  |
| 93 | 105 | high | herb_myarm_back |  |
| 94 | 106 | high | herb_myarm_pic |  |
| 95 | 107 | high | herb_myarm_front |  |
| 96 | 108 | high | herb_my2arm_back |  |
| 97 | 109 | high | herb_my2arm_pic |  |
| 98 | 110 | high | herb_my2arm_front |  |
| 99 | 114 | high | compost_1_back |  |
| 100 | 115 | high | compost_1_pic |  |
| 101 | 116 | high | compost_1_front |  |
| 102 | 117 | high | compost_2_back |  |
| 103 | 118 | high | compost_2_pic |  |
| 104 | 119 | high | compost_2_front |  |
| 105 | 120 | high | compost_3_back |  |
| 106 | 121 | high | compost_3_pic |  |
| 107 | 122 | high | compost_3_front |  |
| 108 | 123 | high | compost_4_back |  |
| 109 | 124 | high | compost_4_pic |  |
| 110 | 125 | high | compost_4_front |  |
| 111 | 126 | high | compost_5_back |  |
| 112 | 127 | high | compost_5_pic |  |
| 113 | 128 | high | compost_5_front |  |
| 114 | 129 | high | compost_6_back |  |
| 115 | 130 | high | compost_6_pic |  |
| 116 | 131 | high | compost_6_front |  |
| 117 | 135 | high | compost_guild_back |  |
| 118 | 136 | high | compost_guild_pic |  |
| 119 | 137 | high | compost_guild_front |  |
| 120 | 138 | high | hops_1_back |  |
| 121 | 139 | high | hops_1_pic |  |
| 122 | 140 | high | hops_1_front |  |
| 123 | 141 | high | hops_2_back |  |
| 124 | 142 | high | hops_2_pic |  |
| 125 | 143 | high | hops_2_front |  |
| 126 | 144 | high | hops_3_back |  |
| 127 | 145 | high | hops_3_pic |  |
| 128 | 146 | high | hops_3_front |  |
| 129 | 147 | high | hops_4_back |  |
| 130 | 148 | high | hops_4_pic |  |
| 131 | 149 | high | hops_4_front |  |
| 132 | 153 | high | bush_1_back |  |
| 133 | 154 | high | bush_1_pic |  |
| 134 | 155 | high | bush_1_front |  |
| 135 | 156 | high | bush_2_back |  |
| 136 | 157 | high | bush_2_pic |  |
| 137 | 158 | high | bush_2_front |  |
| 138 | 159 | high | bush_3_back |  |
| 139 | 160 | high | bush_3_pic |  |
| 140 | 161 | high | bush_3_front |  |
| 141 | 162 | high | bush_4_back |  |
| 142 | 163 | high | bush_4_pic |  |
| 143 | 164 | high | bush_4_front |  |
| 144 | 165 | high | bush_5_back |  |
| 145 | 166 | high | bush_5_pic |  |
| 146 | 167 | high | bush_5_front |  |
| 147 | 168 | high | fruittree_1_back |  |
| 148 | 169 | high | fruittree_1_pic |  |
| 149 | 170 | high | fruittree_1_front |  |
| 150 | 171 | high | fruittree_2_back |  |
| 151 | 172 | high | fruittree_2_pic |  |
| 152 | 173 | high | fruittree_2_front |  |
| 153 | 174 | high | fruittree_3_back |  |
| 154 | 175 | high | fruittree_3_pic |  |
| 155 | 176 | high | fruittree_3_front |  |
| 156 | 177 | high | fruittree_4_back |  |
| 157 | 178 | high | fruittree_4_pic |  |
| 158 | 179 | high | fruittree_4_front |  |
| 159 | 180 | high | fruittree_5_back |  |
| 160 | 181 | high | fruittree_5_pic |  |
| 161 | 182 | high | fruittree_5_front |  |
| 162 | 183 | high | fruittree_6_back |  |
| 163 | 184 | high | fruittree_6_pic |  |
| 164 | 185 | high | fruittree_6_front |  |
| 165 | 189 | high | tree_1_back |  |
| 166 | 190 | high | tree_1_pic |  |
| 167 | 191 | high | tree_1_front |  |
| 168 | 192 | high | tree_2_back |  |
| 169 | 193 | high | tree_2_pic |  |
| 170 | 194 | high | tree_2_front |  |
| 171 | 195 | high | tree_3_back |  |
| 172 | 196 | high | tree_3_pic |  |
| 173 | 197 | high | tree_3_front |  |
| 174 | 198 | high | tree_4_back |  |
| 175 | 199 | high | tree_4_pic |  |
| 176 | 200 | high | tree_4_front |  |
| 177 | 201 | high | tree_5_back |  |
| 178 | 202 | high | tree_5_pic |  |
| 179 | 203 | high | tree_5_front |  |
| 180 | 204 | high | tree_6_back |  |
| 181 | 205 | high | tree_6_pic |  |
| 182 | 206 | high | tree_6_front |  |
| 183 | 210 | high | spirittree_1_back |  |
| 184 | 211 | high | spirittree_1_pic |  |
| 185 | 212 | high | spirittree_1_front |  |
| 186 | 213 | high | spirittree_2_back |  |
| 187 | 214 | high | spirittree_2_pic |  |
| 188 | 215 | high | spirittree_2_front |  |
| 189 | 216 | high | spirittree_3_back |  |
| 190 | 217 | high | spirittree_3_pic |  |
| 191 | 218 | high | spirittree_3_front |  |
| 192 | 219 | high | spirittree_4_back |  |
| 193 | 220 | high | spirittree_4_pic |  |
| 194 | 221 | high | spirittree_4_front |  |
| 195 | 222 | high | spirittree_5_back |  |
| 196 | 223 | high | spirittree_5_pic |  |
| 197 | 224 | high | spirittree_5_front |  |
| 198 | 225 | high | mushroom_back |  |
| 199 | 226 | high | mushroom_pic |  |
| 200 | 227 | high | mushroom_front |  |
| 201 | 228 | high | calquat_back |  |
| 202 | 229 | high | calquat_pic |  |
| 203 | 230 | high | calquat_front |  |
| 204 | 237 | high | cactus_back |  |
| 205 | 238 | high | cactus_pic |  |
| 206 | 239 | high | cactus_front |  |
| 207 | 240 | high | cactus_2_back |  |
| 208 | 241 | high | cactus_2_pic |  |
| 209 | 242 | high | cactus_2_front |  |
| 210 | 243 | high | belladonna_back |  |
| 211 | 244 | high | belladonna_pic |  |
| 212 | 245 | high | belladonna_front |  |
| 213 | 249 | high | grapevine_1_back |  |
| 214 | 250 | high | grapevine_1_pic |  |
| 215 | 251 | high | grapevine_1_front |  |
| 216 | 252 | high | grapevine_2_back |  |
| 217 | 253 | high | grapevine_2_pic |  |
| 218 | 254 | high | grapevine_2_front |  |
| 219 | 255 | high | grapevine_3_back |  |
| 220 | 256 | high | grapevine_3_pic |  |
| 221 | 257 | high | grapevine_3_front |  |
| 222 | 258 | high | grapevine_4_back |  |
| 223 | 259 | high | grapevine_4_pic |  |
| 224 | 260 | high | grapevine_4_front |  |
| 225 | 261 | high | grapevine_5_back |  |
| 226 | 262 | high | grapevine_5_pic |  |
| 227 | 263 | high | grapevine_5_front |  |
| 228 | 264 | high | grapevine_6_back |  |
| 229 | 265 | high | grapevine_6_pic |  |
| 230 | 266 | high | grapevine_6_front |  |
| 231 | 267 | high | grapevine_7_back |  |
| 232 | 268 | high | grapevine_7_pic |  |
| 233 | 269 | high | grapevine_7_front |  |
| 234 | 270 | high | grapevine_8_back |  |
| 235 | 271 | high | grapevine_8_pic |  |
| 236 | 272 | high | grapevine_8_front |  |
| 237 | 273 | high | grapevine_9_back |  |
| 238 | 274 | high | grapevine_9_pic |  |
| 239 | 275 | high | grapevine_9_front |  |
| 240 | 276 | high | grapevine_10_back |  |
| 241 | 277 | high | grapevine_10_pic |  |
| 242 | 278 | high | grapevine_10_front |  |
| 243 | 279 | high | grapevine_11_back |  |
| 244 | 280 | high | grapevine_11_pic |  |
| 245 | 281 | high | grapevine_11_front |  |
| 246 | 282 | high | grapevine_12_back |  |
| 247 | 283 | high | grapevine_12_pic |  |
| 248 | 284 | high | grapevine_12_front |  |
| 249 | 285 | high | seaweed_1_back |  |
| 250 | 286 | high | seaweed_1_pic |  |
| 251 | 287 | high | seaweed_1_front |  |
| 252 | 288 | high | seaweed_2_back |  |
| 253 | 289 | high | seaweed_2_pic |  |
| 254 | 290 | high | seaweed_2_front |  |
| 255 | 291 | high | hardwood_tree_1_back |  |
| 256 | 292 | high | hardwood_tree_1_pic |  |
| 257 | 293 | high | hardwood_tree_1_front |  |
| 258 | 294 | high | hardwood_tree_2_back |  |
| 259 | 295 | high | hardwood_tree_2_pic |  |
| 260 | 296 | high | hardwood_tree_2_front |  |
| 261 | 297 | high | hardwood_tree_3_back |  |
| 262 | 298 | high | hardwood_tree_3_pic |  |
| 263 | 299 | high | hardwood_tree_3_front |  |
| 264 | 306 | high | hespori_1_back |  |
| 265 | 307 | high | hespori_1_pic |  |
| 266 | 308 | high | hespori_1_front |  |
| 267 | 309 | high | celastrus_1_back |  |
| 268 | 310 | high | celastrus_1_pic |  |
| 269 | 311 | high | celastrus_1_front |  |
| 270 | 312 | high | anima_1_back |  |
| 271 | 313 | high | anima_1_pic |  |
| 272 | 314 | high | anima_1_front |  |
| 273 | 315 | high | redwood_back |  |
| 274 | 316 | high | redwood_pic |  |
| 275 | 317 | high | redwood_front |  |
| 276 | 318 | high | crystal_tree_1_back |  |
| 277 | 319 | high | crystal_tree_1_pic |  |
| 278 | 320 | high | crystal_tree_1_front |  |
| 279 | 327 | high | scrollbar |  |
| 280 | 328 | high | tooltip |  |

New in 241: 51 allotment_16_back, 52 allotment_16_pic, 53 allotment_16_front, 54 allotment_17_back, 55 allotment_17_pic, 56 allotment_17_front, 78 flower_8_back, 79 flower_8_pic, 80 flower_8_front, 81 flower_9_back, 82 flower_9_pic, 83 flower_9_front, 111 herb_8_back, 112 herb_8_pic, 113 herb_8_front, 132 compost_7_back, 133 compost_7_pic, 134 compost_7_front, 150 hops_5_back, 151 hops_5_pic, 152 hops_5_front, 186 fruittree_7_back, 187 fruittree_7_pic, 188 fruittree_7_front, 207 tree_7_back, 208 tree_7_pic, 209 tree_7_front, 231 calquat_2_back, 232 calquat_2_pic, 233 calquat_2_front, 234 calquat_3_back, 235 calquat_3_pic, 236 calquat_3_front, 246 belladonna_2_back, 247 belladonna_2_pic, 248 belladonna_2_front, 300 hardwood_tree_4_back, 301 hardwood_tree_4_pic, 302 hardwood_tree_4_front, 303 hardwood_tree_5_back, 304 hardwood_tree_5_pic, 305 hardwood_tree_5_front, 321 coral_1_back, 322 coral_1_pic, 323 coral_1_front, 324 coral_2_back, 325 coral_2_pic, 326 coral_2_front

### 180 ii_tracker (IMPLING_TRACKER)

211: 23 components, 241: 23. Moved: 0. High 23, medium 0, low 0.

### 187 menu

211: 5 components, 241: 5. Moved: 0. High 5, medium 0, low 0.

### 190 rune_pouch (RUNE_POUCH)

211: 12 components, 241: 15. Moved: 11. High 0, medium 2, low 10.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | -1 | low |  | unmatched |
| 1 | -1 | low |  | unmatched |
| 2 | -1 | low |  | unmatched |
| 3 | -1 | low |  | unmatched |
| 4 | -1 | low |  | unmatched |
| 5 | -1 | low |  | unmatched |
| 6 | -1 | low |  | unmatched |
| 7 | 6 | medium | com_6 | xmode,parent |
| 8 | -1 | low |  | unmatched |
| 9 | 9 | medium | com_9 | xmode,parent |
| 10 | -1 | low |  | unmatched |
| 11 | -1 | low |  | unmatched |

New in 241: 0 universe, 1 frame, 2 contents, 3 top, 4 pouch, 5 com_5, 7 inventory, 8 com_8, 10 bottom, 11 1, 12 5, 13 x, 14 all

### 192 bank_depositbox (BANK_DEPOSIT_INTERFACE)

211: 21 components, 241: 43. Moved: 19. High 1, medium 1, low 19.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | 0 | low | universe | onload,height,width |
| 2 | 26 | medium | item_background | y,height,width,parent |
| 3 | -1 | low |  | unmatched |
| 4 | -1 | low |  | unmatched |
| 5 | -1 | low |  | unmatched |
| 6 | -1 | low |  | unmatched |
| 7 | -1 | low |  | unmatched |
| 8 | -1 | low |  | unmatched |
| 9 | -1 | low |  | unmatched |
| 10 | 29 | low | deposit_all_buttons | x,y,width,parent |
| 11 | -1 | low |  | unmatched |
| 12 | -1 | low |  | unmatched |
| 13 | -1 | low |  | unmatched |
| 14 | -1 | low |  | unmatched |
| 15 | -1 | low |  | unmatched |
| 16 | -1 | low |  | unmatched |
| 17 | -1 | low |  | unmatched |
| 18 | -1 | low |  | unmatched |
| 19 | -1 | low |  | unmatched |
| 20 | -1 | low |  | unmatched |

New in 241: 2 menu_button, 3 contents, 4 worn, 5 com_5, 6 com_6, 7 com_7, 8 com_8, 9 com_9, 10 slot0, 11 slot1, 12 slot2, 13 slot3, 14 slot4, 15 slot5, 16 slot7, 17 slot9, 18 slot10, 19 slot12, 20 slot13, 21 extra_quiver_slot, 22 extra_quiver_ammo_graphic, 23 extra_quiver_ammo, 24 inventory, 25 com_25, 27 com_27, 28 com_28, 30 deposit_worn, 31 deposit_inv, 32 deposit_lootingbag, 33 quantity_buttons, 34 lock_menu, 35 1, 36 5, 37 10, 38 x, 39 all, 40 popup, 41 menu_container, 42 depositworn_toggle

### 201 autocast (AUTOCAST_TAB)

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.

### 203 trail_cluetext (CLUE_SCROLL)

211: 3 components, 241: 3. Moved: 0. High 3, medium 0, low 0.

### 205 makeover_mage (MAKEOVER)

211: 11 components, 241: 34. Moved: 10. High 0, medium 6, low 5.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | -1 | low |  | unmatched |
| 1 | 1 | medium | frame | onload,parent |
| 2 | -1 | low |  | unmatched |
| 3 | 5 | medium | com_5 | modelorigin,x,modelanim,parent |
| 4 | 6 | medium | com_6 | modelorigin,x,modelanim,parent |
| 5 | 7 | medium | a_title | y,text,colour,parent |
| 6 | -1 | low |  | unmatched |
| 7 | 11 | low | com_11 | modelorigin,x,xmode,modelanim,parent |
| 8 | 12 | medium | b_title | y,text,colour,parent |
| 9 | 13 | medium | colour | y,height,parent |
| 10 | -1 | low |  | unmatched |

New in 241: 0 universe, 2 bodytype_a, 3 a_bg, 4 com_4, 8 bodytype_b, 9 b_bg, 10 com_10, 14 pronouns, 15 com_15, 16 buttons, 17 apply_button, 18 confirm_button, 19 confirm, 20 pronouns_title, 21 he_him, 22 radio_he, 23 pronouns_he, 24 they_them, 25 radio_they, 26 pronouns_they, 27 she_her, 28 radio_she, 29 pronouns_she, 30 burger_btn_menu, 31 burger_menu_overlay, 32 burger_menu_frame, 33 apply

### 208 clan_cup_hud (PVM_ARENA_HUD)

211: 5 components, 241: 5. Moved: 0. High 5, medium 0, low 0.

### 210 poh_petlist

211: 5 components, 241: 5. Moved: 0. High 5, medium 0, low 0.

### 212 poh_add_room

211: 70 components, 241: 70. Moved: 0. High 70, medium 0, low 0.

### 213 bankpin_keypad (BANK_PIN_VERIFICATION)

211: 36 components, 241: 36. Moved: 0. High 36, medium 0, low 0.

### 214 skill_guide

211: 26 components, 241: 33. Moved: 26. High 14, medium 4, low 8.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | -1 | low |  | unmatched |
| 1 | 3 | medium | com_3 | parent |
| 2 | -1 | low |  | unmatched |
| 3 | -1 | low |  | unmatched |
| 4 | -1 | low |  | unmatched |
| 5 | -1 | low |  | unmatched |
| 6 | 4 | medium | title | parent |
| 7 | -1 | low |  | unmatched |
| 8 | -1 | low |  | unmatched |
| 9 | -1 | low |  | unmatched |
| 10 | 5 | medium | description | parent |
| 11 | 12 | high | 00 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 12 | 13 | high | 01 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 13 | 14 | high | 02 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 14 | 15 | high | 03 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 15 | 16 | high | 04 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 16 | 17 | high | 05 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 17 | 18 | high | 06 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 18 | 19 | high | 07 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 19 | 20 | high | 08 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 20 | 21 | high | 09 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 21 | 22 | high | 10 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 22 | 23 | high | 11 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 23 | 24 | high | 12 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 24 | 25 | high | 13 | x,y,transmitop,op1,xmode,anchor,parent,anchored |
| 25 | 30 | medium | close | parent |

New in 241: 0 infinity, 1 window, 2 com_2, 6 categories, 7 noclick, 8 middle, 9 com_9, 10 com_10, 11 com_11, 26 14, 27 move_layer, 28 scrollbar, 29 close_layer, 31 info, 32 icons

### 221 messagescroll2 (NIEVE_GRAVESTONE)

211: 14 components, 241: 14. Moved: 0. High 14, medium 0, low 0.

### 238 ge_pricechecker_side (PRICE_CHECKER_INVENTORY)

211: 1 components, 241: 1. Moved: 0. High 1, medium 0, low 0.

### 240 xpreward (EXPERIENCE_LAMP)

211: 28 components, 241: 29. Moved: 21. High 28, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 7 | 8 | high | hitpoints | x,y,height,width,anchor,anchored |
| 8 | 9 | high | prayer | x,y,height,width,anchor,anchored |
| 9 | 10 | high | agility | y,height,width,anchor,anchored |
| 10 | 11 | high | herblore | x,y,height,width,anchor,anchored |
| 11 | 12 | high | thieving | x,y,height,width,anchor,anchored |
| 12 | 13 | high | crafting | x,y,height,width,anchor,anchored |
| 13 | 14 | high | runecraft | x,y,height,width,anchor,anchored |
| 14 | 15 | high | slayer | x,y,height,width,anchor,anchored |
| 15 | 16 | high | farming | y,height,width,anchor,anchored |
| 16 | 17 | high | mining | x,y,height,width,anchor,anchored |
| 17 | 18 | high | smithing | x,y,height,width,anchor,anchored |
| 18 | 19 | high | fishing | x,y,height,width,anchor,anchored |
| 19 | 20 | high | cooking | x,y,height,width,anchor,anchored |
| 20 | 21 | high | firemaking | x,y,height,width,anchor,anchored |
| 21 | 22 | high | woodcutting | y,height,width,anchor,anchored |
| 22 | 23 | high | fletching | x,y,height,width,anchor,anchored |
| 23 | 24 | high | construction | x,y,height,width,anchor,anchored |
| 24 | 25 | high | hunter | x,y,height,width,anchor,anchored |
| 25 | 26 | high | title | y |
| 26 | 27 | high | confirm | y,height,anchor,anchored |
| 27 | 28 | high | com_28 |  |

New in 241: 7 sailing

### 243 pest_rewardshop (VOID_KNIGHT_REWARDS)

211: 9 components, 241: 9. Moved: 0. High 9, medium 0, low 0.

### 245 dov_census (KOUREND_FAVOUR_TAB)

211: 23 components, 241: 10. Moved: 22. High 1, medium 0, low 22.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 1 | -1 | low |  | unmatched |
| 2 | -1 | low |  | unmatched |
| 3 | -1 | low |  | unmatched |
| 4 | 1 | low | content | widthmode,height,width,heightmode,parent |
| 5 | -1 | low |  | unmatched |
| 6 | -1 | low |  | unmatched |
| 7 | -1 | low |  | unmatched |
| 8 | -1 | low |  | unmatched |
| 9 | -1 | low |  | unmatched |
| 10 | -1 | low |  | unmatched |
| 11 | -1 | low |  | unmatched |
| 12 | -1 | low |  | unmatched |
| 13 | -1 | low |  | unmatched |
| 14 | -1 | low |  | unmatched |
| 15 | -1 | low |  | unmatched |
| 16 | -1 | low |  | unmatched |
| 17 | -1 | low |  | unmatched |
| 18 | -1 | low |  | unmatched |
| 19 | -1 | low |  | unmatched |
| 20 | -1 | low |  | unmatched |
| 21 | -1 | low |  | unmatched |
| 22 | -1 | low |  | unmatched |

New in 241: 2 background, 3 closebutton, 4 titleleft, 5 textleft, 6 titleright, 7 textright, 8 pageleft, 9 pageright

### 255 olaf2_ulric_parchment

211: 4 components, 241: 4. Moved: 0. High 4, medium 0, low 0.

### 261 soul_wars_tutorial_instructions

211: 3 components, 241: 3. Moved: 0. High 3, medium 0, low 0.

### 265 partydrop_main (PARTY_DROP_CHEST)

211: 19 components, 241: 19. Moved: 0. High 19, medium 0, low 0.

### 266 partydrop_side (PARTY_DROP_CHEST_INVENTORY)

211: 1 components, 241: 1. Moved: 0. High 1, medium 0, low 0.

### 269 f2p_bond_redeem (TUTORIAL_MAKE_OVER)

211: 7 components, 241: 7. Moved: 2. High 6, medium 0, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 5 | -1 | low |  | unmatched |
| 6 | 5 | high | bond_button | width |

New in 241: 6 close

### 271 raids_storage_private (RAIDS_PRIVATE_STORAGE)

211: 12 components, 241: 12. Moved: 0. High 12, medium 0, low 0.

### 288 colour_pallet (COLOUR_PICKER)

211: 14 components, 241: 14. Moved: 0. High 14, medium 0, low 0.

### 289 popupoverlay (SETTINGS_GIM_LEAVE/TOA_REWARD_POTENTIAL)

211: 10 components, 241: 10. Moved: 0. High 10, medium 0, low 0.

### 293 loading_icon_modal

211: 3 components, 241: 3. Moved: 0. High 3, medium 0, low 0.

### 302 horror_prayerbooks (JOSSIKS_SALVAGED_GODBOOKS)

211: 9 components, 241: 9. Moved: 0. High 9, medium 0, low 0.

### 303 hpbar_hud (HP_HUD)

211: 21 components, 241: 28. Moved: 10. High 21, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 11 | 12 | high | inner_border |  |
| 12 | 13 | high | inner |  |
| 13 | 14 | high | health_bar_back |  |
| 14 | 15 | high | health_bar_sliding |  |
| 15 | 16 | high | health_bar_remaining |  |
| 16 | 17 | high | hp_bar_1 |  |
| 17 | 18 | high | hp_bar_2 |  |
| 18 | 19 | high | health_bar_lower_threshold |  |
| 19 | 20 | high | health_bar_upper_threshold |  |
| 20 | 21 | high | hp_bar_text | hide,geometry-match |

New in 241: 11 hp_bar_extra, 22 inner_border_extra, 23 inner_extra, 24 health_bar_back_extra, 25 health_bar_sliding_extra, 26 health_bar_remaining_extra, 27 hp_bar_text_extra

### 306 trail_slidepuzzle (PUZZLE_BOX)

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.

### 308 cata_boss (SKOTIZO_OVERLAY)

211: 9 components, 241: 9. Moved: 0. High 9, medium 0, low 0.

### 311 league_combat_mastery (MINIMIZED_ORBS)

211: 55 components, 241: 83. Moved: 54. High 1, medium 0, low 54.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 1 | -1 | low |  | unmatched |
| 2 | -1 | low |  | unmatched |
| 3 | -1 | low |  | unmatched |
| 4 | 71 | low | confirm_button | x,y,height,ymode,xmode,width,parent |
| 5 | -1 | low |  | unmatched |
| 6 | -1 | low |  | unmatched |
| 7 | -1 | low |  | unmatched |
| 8 | -1 | low |  | unmatched |
| 9 | -1 | low |  | unmatched |
| 10 | -1 | low |  | unmatched |
| 11 | -1 | low |  | unmatched |
| 12 | -1 | low |  | unmatched |
| 13 | -1 | low |  | unmatched |
| 14 | -1 | low |  | unmatched |
| 15 | -1 | low |  | unmatched |
| 16 | -1 | low |  | unmatched |
| 17 | -1 | low |  | unmatched |
| 18 | -1 | low |  | unmatched |
| 19 | -1 | low |  | unmatched |
| 20 | -1 | low |  | unmatched |
| 21 | -1 | low |  | unmatched |
| 22 | -1 | low |  | unmatched |
| 23 | -1 | low |  | unmatched |
| 24 | -1 | low |  | unmatched |
| 25 | -1 | low |  | unmatched |
| 26 | -1 | low |  | unmatched |
| 27 | -1 | low |  | unmatched |
| 28 | -1 | low |  | unmatched |
| 29 | -1 | low |  | unmatched |
| 30 | -1 | low |  | unmatched |
| 31 | -1 | low |  | unmatched |
| 32 | -1 | low |  | unmatched |
| 33 | -1 | low |  | unmatched |
| 34 | -1 | low |  | unmatched |
| 35 | -1 | low |  | unmatched |
| 36 | -1 | low |  | unmatched |
| 37 | -1 | low |  | unmatched |
| 38 | -1 | low |  | unmatched |
| 39 | -1 | low |  | unmatched |
| 40 | -1 | low |  | unmatched |
| 41 | -1 | low |  | unmatched |
| 42 | -1 | low |  | unmatched |
| 43 | -1 | low |  | unmatched |
| 44 | -1 | low |  | unmatched |
| 45 | -1 | low |  | unmatched |
| 46 | -1 | low |  | unmatched |
| 47 | -1 | low |  | unmatched |
| 48 | -1 | low |  | unmatched |
| 49 | -1 | low |  | unmatched |
| 50 | -1 | low |  | unmatched |
| 51 | -1 | low |  | unmatched |
| 52 | 75 | low | tutorial_highlight | targetverb,targetmask,hide,op10,transmitop,opbase,parent |
| 53 | -1 | low |  | unmatched |
| 54 | -1 | low |  | unmatched |

New in 241: 1 universe, 2 tooltip, 3 frame, 4 content_outer, 5 progress_bar, 6 divider, 7 content, 8 view_all, 9 view_all_scrollbar, 10 loading, 11 view_one, 12 extra_info, 13 confirm, 14 view_all_frame, 15 summary_tab, 16 mastery_summary, 17 passive_summary, 18 summary_passive_text, 19 summary_passive_frame, 20 summary_passive_list, 21 summary_passive_scrollbar, 22 points_tab, 23 points_text, 24 points_frame, 25 points_list, 26 points_scrollbar, 27 masteries_tab, 28 backgrounds, 29 tutorial, 30 lines_background, 31 lines, 32 outlines, 33 icons, 34 tier_icons, 35 sparks, 36 clickzones, 37 loading_overlay, 38 com_38, 39 name, 40 description_combat_mastery, 41 com_41, 42 com_42, 43 description_holder, 44 description_header, 45 description, 46 description_scrollbar, 47 description_passive, 48 com_48, 49 com_49, 50 passive_header, 51 passive_description, 52 passive_clickzone, 53 view_one_button_holder, 54 icon, 55 select_button, 56 select_back, 57 extra_info_backing, 58 extra_info_holder, 59 extra_info_frame, 60 extra_info_content, 61 extra_info_close_button, 62 extra_text_holder, 63 com_63, 64 extra_text, 65 extra_info_scroll, 66 league_menu_overlay, 67 confirm_overlay, 68 confirm_universe, 69 confirm_frame, 70 confirm_text, 72 confirm_cancel, 73 combat_mastery_tabs, 74 mastery_more_info, 76 close_button, 77 league_btn_menu, 78 league_menu_frame, 79 summary_mastery_text, 80 summary_mastery_frame, 81 summary_mastery_list, 82 summary_mastery_scrollbar

### 312 smithing (SMITHING_INTERFACE)

211: 36 components, 241: 39. Moved: 0. High 35, medium 0, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 2 | 2 | low | makex | onload,x |

New in 241: 36 other_4, 37 other_5, 38 other_6

### 322 light_puzzle (LIGHT_BOX)

211: 24 components, 241: 24. Moved: 0. High 24, medium 0, low 0.

### 332 thormac (BATTLESTAFF_ENCHANTMENT)

211: 13 components, 241: 13. Moved: 0. High 13, medium 0, low 0.

### 334 tradeconfirm (TRADE_STAGE2)

211: 33 components, 241: 33. Moved: 0. High 33, medium 0, low 0.

### 335 trademain (TRADE_STAGE1)

211: 33 components, 241: 33. Moved: 0. High 33, medium 0, low 0.

### 336 tradeside (TRADE_INVENTORY)

211: 1 components, 241: 1. Moved: 0. High 1, medium 0, low 0.

### 363 tob_scoreboard (TOB_STATS)

211: 67 components, 241: 67. Moved: 0. High 67, medium 0, low 0.

### 364 tob_partylist (TOB_PARTIES_OVERVIEW)

211: 68 components, 241: 68. Moved: 0. High 68, medium 0, low 0.

### 370 poh_options (HOUSE_OPTIONS_TAB)

211: 27 components, 241: 27. Moved: 0. High 25, medium 2, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 5 | 5 | medium | build_mode_on | graphic,onvartransmit |
| 8 | 8 | medium | tele_on | y,graphic,onvartransmit |

### 378 welcome_screen (LOBBY)

211: 86 components, 241: 85. Moved: 5. High 84, medium 0, low 2.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 81 | 82 | high | banner_loading |  |
| 82 | 83 | high | com_83 |  |
| 83 | 84 | high | spinner |  |
| 84 | -1 | low |  | unmatched |
| 85 | -1 | low |  | unmatched |

New in 241: 81 banner_highlight

### 381 fairyrings_log

211: 153 components, 241: 172. Moved: 146. High 153, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 7 | 8 | high | contents |  |
| 8 | 9 | high | faves |  |
| 9 | 10 | high | divider |  |
| 10 | 11 | high | frame |  |
| 11 | 12 | high | aip |  |
| 12 | 13 | high | aip_fave |  |
| 13 | 14 | high | aiq |  |
| 14 | 15 | high | aiq_fave |  |
| 15 | 16 | high | air |  |
| 16 | 17 | high | air_fave |  |
| 17 | 18 | high | ais |  |
| 18 | 19 | high | ais_fave |  |
| 19 | 20 | high | ajp |  |
| 20 | 21 | high | ajp_fave |  |
| 21 | 22 | high | ajq |  |
| 22 | 23 | high | ajq_fave |  |
| 23 | 24 | high | ajr |  |
| 24 | 25 | high | ajr_fave |  |
| 25 | 26 | high | ajs |  |
| 26 | 27 | high | ajs_fave |  |
| 27 | 28 | high | akp |  |
| 28 | 29 | high | akp_fave |  |
| 29 | 30 | high | akq |  |
| 30 | 31 | high | akq_fave |  |
| 31 | 32 | high | akr |  |
| 32 | 33 | high | akr_fave |  |
| 33 | 34 | high | aks |  |
| 34 | 35 | high | aks_fave |  |
| 35 | 36 | high | alp |  |
| 36 | 37 | high | alp_fave |  |
| 37 | 38 | high | alq |  |
| 38 | 39 | high | alq_fave |  |
| 39 | 40 | high | alr |  |
| 40 | 41 | high | alr_fave |  |
| 41 | 42 | high | als |  |
| 42 | 43 | high | als_fave |  |
| 43 | 44 | high | bip |  |
| 44 | 45 | high | bip_fave |  |
| 45 | 46 | high | biq |  |
| 46 | 47 | high | biq_fave |  |
| 47 | 48 | high | bir |  |
| 48 | 49 | high | bir_fave |  |
| 49 | 50 | high | bis |  |
| 50 | 51 | high | bis_fave |  |
| 51 | 52 | high | bjp |  |
| 52 | 53 | high | bjp_fave |  |
| 53 | 54 | high | bjq |  |
| 54 | 55 | high | bjq_fave |  |
| 55 | 56 | high | bjr |  |
| 56 | 57 | high | bjr_fave |  |
| 57 | 58 | high | bjs |  |
| 58 | 59 | high | bjs_fave |  |
| 59 | 60 | high | bkp |  |
| 60 | 61 | high | bkp_fave |  |
| 61 | 62 | high | bkq |  |
| 62 | 63 | high | bkq_fave |  |
| 63 | 64 | high | bkr |  |
| 64 | 65 | high | bkr_fave |  |
| 65 | 66 | high | bks |  |
| 66 | 67 | high | bks_fave |  |
| 67 | 68 | high | blp |  |
| 68 | 69 | high | blp_fave |  |
| 69 | 70 | high | blq |  |
| 70 | 71 | high | blq_fave |  |
| 71 | 72 | high | blr |  |
| 72 | 73 | high | blr_fave |  |
| 73 | 74 | high | bls |  |
| 74 | 75 | high | bls_fave |  |
| 75 | 76 | high | cip |  |
| 76 | 77 | high | cip_fave |  |
| 77 | 78 | high | ciq |  |
| 78 | 79 | high | ciq_fave |  |
| 79 | 80 | high | cir |  |
| 80 | 81 | high | cir_fave |  |
| 81 | 82 | high | cis |  |
| 82 | 83 | high | cis_fave |  |
| 83 | 84 | high | cjp |  |
| 84 | 85 | high | cjp_fave |  |
| 85 | 86 | high | cjq |  |
| 86 | 87 | high | cjq_fave |  |
| 87 | 88 | high | cjr |  |
| 88 | 89 | high | cjr_fave |  |
| 89 | 90 | high | cjs |  |
| 90 | 91 | high | cjs_fave |  |
| 91 | 92 | high | ckp |  |
| 92 | 93 | high | ckp_fave |  |
| 93 | 94 | high | ckq |  |
| 94 | 95 | high | ckq_fave |  |
| 95 | 96 | high | ckr |  |
| 96 | 97 | high | ckr_fave |  |
| 97 | 98 | high | cks |  |
| 98 | 99 | high | cks_fave |  |
| 99 | 100 | high | clp |  |
| 100 | 101 | high | clp_fave |  |
| 101 | 102 | high | clq |  |
| 102 | 103 | high | clq_fave |  |
| 103 | 104 | high | clr |  |
| 104 | 105 | high | clr_fave |  |
| 105 | 106 | high | cls |  |
| 106 | 107 | high | cls_fave |  |
| 107 | 108 | high | dip |  |
| 108 | 109 | high | dip_fave |  |
| 109 | 110 | high | diq |  |
| 110 | 111 | high | diq_fave |  |
| 111 | 112 | high | dir |  |
| 112 | 113 | high | dir_fave |  |
| 113 | 114 | high | dis |  |
| 114 | 115 | high | dis_fave |  |
| 115 | 116 | high | djp |  |
| 116 | 117 | high | djp_fave |  |
| 117 | 118 | high | djq |  |
| 118 | 119 | high | djq_fave |  |
| 119 | 120 | high | djr |  |
| 120 | 121 | high | djr_fave |  |
| 121 | 122 | high | djs |  |
| 122 | 123 | high | djs_fave |  |
| 123 | 124 | high | dkp |  |
| 124 | 125 | high | dkp_fave |  |
| 125 | 126 | high | dkq |  |
| 126 | 127 | high | dkq_fave |  |
| 127 | 128 | high | dkr |  |
| 128 | 129 | high | dkr_fave |  |
| 129 | 130 | high | dks |  |
| 130 | 131 | high | dks_fave |  |
| 131 | 132 | high | dlp |  |
| 132 | 133 | high | dlp_fave |  |
| 133 | 134 | high | dlq |  |
| 134 | 135 | high | dlq_fave |  |
| 135 | 136 | high | dlr |  |
| 136 | 137 | high | dlr_fave |  |
| 137 | 138 | high | dls |  |
| 138 | 139 | high | dls_fave |  |
| 139 | 140 | high | hideout |  |
| 140 | 141 | high | fave_1 |  |
| 141 | 142 | high | fave_2 |  |
| 142 | 143 | high | fave_3 |  |
| 143 | 144 | high | fave_4 |  |
| 144 | 151 | high | fave_code_1 |  |
| 145 | 152 | high | fave_code_2 |  |
| 146 | 153 | high | fave_code_3 |  |
| 147 | 154 | high | fave_code_4 |  |
| 148 | 161 | high | fave_icon_1 |  |
| 149 | 162 | high | fave_icon_2 |  |
| 150 | 163 | high | fave_icon_3 |  |
| 151 | 164 | high | fave_icon_4 |  |
| 152 | 166 | high | scrollbar |  |

New in 241: 7 search, 145 fave_5, 146 fave_6, 147 fave_7, 148 fave_8, 149 fave_9, 150 fave_10, 155 fave_code_5, 156 fave_code_6, 157 fave_code_7, 158 fave_code_8, 159 fave_code_9, 160 fave_code_10, 165 fave_icon_5, 167 fave_icon_6, 168 fave_icon_7, 169 fave_icon_8, 170 fave_icon_9, 171 fave_icon_10

### 382 motherlode_hud (MOTHERLODE_MINE)

211: 5 components, 241: 7. Moved: 0. High 3, medium 1, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 3 | 3 | low | data | height,width |
| 4 | 4 | medium | com_4 | y,width,height,modelzoom |

New in 241: 5 pay_dirt, 6 deposits

### 383 ge_history (GRAND_EXCHANGE_HISTORY)

211: 5 components, 241: 5. Moved: 0. High 5, medium 0, low 0.

### 392 book

211: 79 components, 241: 79. Moved: 0. High 79, medium 0, low 0.

### 396 wint_status (WINTERTODT)

211: 22 components, 241: 27. Moved: 19. High 21, medium 0, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 3 | -1 | low |  | unmatched |
| 4 | 3 | high | brazier_layer |  |
| 5 | 4 | high | brazier_center |  |
| 6 | 5 | high | north_arrow |  |
| 7 | 6 | high | points |  |
| 8 | 7 | high | pyro1 |  |
| 9 | 8 | high | pyro2 |  |
| 10 | 9 | high | pyro3 |  |
| 11 | 10 | high | pyro4 |  |
| 12 | 11 | high | brazier1 |  |
| 13 | 12 | high | brazier2 |  |
| 14 | 13 | high | brazier3 |  |
| 15 | 14 | high | brazier4 |  |
| 16 | 21 | high | energy | y,anchor,anchored |
| 17 | 22 | high | energy_bar_layer |  |
| 18 | 23 | high | energy_bar_back |  |
| 19 | 24 | high | energy_bar |  |
| 20 | 25 | high | bar_border |  |
| 21 | 26 | high | energy_title | onload,anchor,anchored |

New in 241: 15 warmth, 16 warmth_bar_layer, 17 warmth_bar_back, 18 warmth_bar, 19 warmth_bar_border, 20 warmth_title

### 398 fairyrings

211: 28 components, 241: 28. Moved: 0. High 28, medium 0, low 0.

### 402 ge_collect (GRAND_EXCHANGE_COLLECTION_BOX)

211: 14 components, 241: 14. Moved: 0. High 14, medium 0, low 0.

### 403 teletabs_craft_if (ARCEUUS_LECTERN)

211: 25 components, 241: 41. Moved: 25. High 11, medium 13, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | -1 | low |  | unmatched |
| 1 | 2 | medium | frame | onvartransmit,parent |
| 2 | 3 | medium | content | height,width,parent |
| 3 | 4 | high | makex |  |
| 4 | 5 | high | make_1 |  |
| 5 | 6 | high | make_5 |  |
| 6 | 7 | high | make_10 |  |
| 7 | 8 | high | make_x |  |
| 8 | 9 | high | make_all |  |
| 9 | 10 | high | make_some |  |
| 10 | 11 | high | rune_box | width |
| 11 | 12 | high | com_12 |  |
| 12 | 13 | high | levelreq | width,textfont |
| 13 | 14 | high | confirm | width |
| 14 | 19 | medium | tab_1 | x,y,parent |
| 15 | 20 | medium | tab_2 | x,y,parent |
| 16 | 21 | medium | tab_3 | x,y,parent |
| 17 | 22 | medium | tab_4 | x,y,parent |
| 18 | 23 | medium | tab_5 | x,y,parent |
| 19 | 24 | medium | tab_6 | x,y,parent |
| 20 | 25 | medium | tab_7 | x,y,parent |
| 21 | 26 | medium | tab_8 | x,y,parent |
| 22 | 27 | medium | tab_9 | x,y,parent |
| 23 | 28 | medium | tab_10 | x,y,parent |
| 24 | 29 | medium | tab_11 | x,y,parent |

New in 241: 0 universe, 1 control, 15 tablets, 16 com_16, 17 tablets_inner, 18 tablets_scroll_layer, 30 tab_12, 31 tab_13, 32 tab_14, 33 tab_15, 34 tab_16, 35 tab_17, 36 tab_18, 37 tab_19, 38 tab_20, 39 tab_21, 40 tablets_scrollbar

### 405 tob_midway_stores (TOB_CHEST_SUPPLIES)

211: 9 components, 241: 9. Moved: 0. High 9, medium 0, low 0.

### 406 godwars_overlay

211: 20 components, 241: 20. Moved: 0. High 20, medium 0, low 0.

### 407 pest_lander_overlay (PEST_CONTROL_LANDER_OVERLAY)

211: 21 components, 241: 21. Moved: 0. High 21, medium 0, low 0.

### 408 pest_status_overlay (PEST_CONTROL_GAME_OVERLAY)

211: 37 components, 241: 37. Moved: 0. High 37, medium 0, low 0.

### 411 warguild_defence_mini

211: 14 components, 241: 14. Moved: 0. High 14, medium 0, low 0.

### 413 nightmare_totems (NIGHTMARE_TOTEMS)

211: 28 components, 241: 28. Moved: 0. High 28, medium 0, low 0.

### 416 canoeing (CANOE_SHAPE)

211: 23 components, 241: 23. Moved: 0. High 23, medium 0, low 0.

### 421 pvp_arena_stagingarea_shareloadout

211: 34 components, 241: 34. Moved: 0. High 34, medium 0, low 0.

### 422 poh_viewer

211: 67 components, 241: 71. Moved: 27. High 67, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 40 | 44 | high | preview |  |
| 41 | 45 | high | frame |  |
| 42 | 46 | high | portal | modelanim,geometry-match |
| 43 | 47 | high | sidecontent |  |
| 44 | 48 | high | layerbuttons |  |
| 45 | 49 | high | level0 |  |
| 46 | 50 | high | level1 |  |
| 47 | 51 | high | level2 |  |
| 48 | 52 | high | name |  |
| 49 | 53 | high | gfx_container |  |
| 50 | 54 | high | com_54 |  |
| 51 | 55 | high | com_55 |  |
| 52 | 56 | high | com_56 |  |
| 53 | 57 | high | neighbour_n |  |
| 54 | 58 | high | neighbour_s |  |
| 55 | 59 | high | neighbour_e |  |
| 56 | 60 | high | neighbour_w |  |
| 57 | 61 | high | gfx |  |
| 58 | 62 | high | hint |  |
| 59 | 63 | high | move |  |
| 60 | 64 | high | rotate |  |
| 61 | 65 | high | clockwise |  |
| 62 | 66 | high | anticlockwise |  |
| 63 | 67 | high | delete |  |
| 64 | 68 | high | cancel |  |
| 65 | 69 | high | done |  |
| 66 | 70 | high | tooltip |  |

New in 241: 40 35, 41 36, 42 37, 43 38

### 426 slayer_rewards (SLAYER_REWARDS)

211: 46 components, 241: 39. Moved: 46. High 17, medium 7, low 22.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | -1 | low |  | unmatched |
| 1 | 2 | low | border | widthmode,height,ymode,xmode,width,heightmode,parent |
| 2 | 3 | high | confirm | x,y,ymode,xmode,anchor,parent,anchored |
| 3 | 6 | high | com_6 |  |
| 4 | 7 | high | confirm_info |  |
| 5 | 8 | high | back_button |  |
| 6 | 9 | high | com_9 |  |
| 7 | 10 | high | back_text |  |
| 8 | 11 | high | confirm_button |  |
| 9 | 12 | high | com_12 |  |
| 10 | 13 | high | confirm_text |  |
| 11 | -1 | low |  | unmatched |
| 12 | 14 | medium | tabs | parent |
| 13 | -1 | low |  | unmatched |
| 14 | 16 | medium | unlock | parent |
| 15 | 17 | high | unlock_contents | onload,geometry-match |
| 16 | -1 | low |  | unmatched |
| 17 | 19 | medium | extend | parent |
| 18 | 20 | high | extend_contents | onload,geometry-match |
| 19 | 21 | high | extend_scrollbar |  |
| 20 | 22 | high | extend_etcetera |  |
| 21 | 23 | medium | buy | parent |
| 22 | 24 | high | buy_contents | onload,geometry-match |
| 23 | 25 | high | buy_items |  |
| 24 | 26 | high | buy_prices |  |
| 25 | 27 | high | buy_scrollbar |  |
| 26 | 28 | medium | tasks | onload,parent |
| 27 | -1 | low |  | unmatched |
| 28 | 32 | medium | com_32 | colour,parent |
| 29 | -1 | low |  | unmatched |
| 30 | -1 | low |  | unmatched |
| 31 | -1 | low |  | unmatched |
| 32 | -1 | low |  | unmatched |
| 33 | -1 | low |  | unmatched |
| 34 | -1 | low |  | unmatched |
| 35 | -1 | low |  | unmatched |
| 36 | -1 | low |  | unmatched |
| 37 | -1 | low |  | unmatched |
| 38 | -1 | low |  | unmatched |
| 39 | -1 | low |  | unmatched |
| 40 | -1 | low |  | unmatched |
| 41 | -1 | low |  | unmatched |
| 42 | -1 | low |  | unmatched |
| 43 | -1 | low |  | unmatched |
| 44 | 33 | medium | com_33 | colour,parent |
| 45 | -1 | low |  | unmatched |

New in 241: 0 infinity, 1 universe, 4 main, 5 popup, 15 contents, 18 unlock_scrollbar, 29 tasks_content_upper, 30 rewards, 31 tasks_content_lower, 34 tasks_content_scrollable, 35 tasks_scrollbar, 36 view_tasks, 37 rewards_contents, 38 rewards_scrollbar

### 428 ntk_overlay (PYRAMID_PLUNDER)

211: 8 components, 241: 8. Moved: 0. High 8, medium 0, low 0.

### 430 itemsets_side (ITEM_SETS_INVENTORY_INTERFACE)

211: 1 components, 241: 1. Moved: 0. High 1, medium 0, low 0.

### 436 soul_wars_stats (GNOME_COCKTAIL)

211: 5 components, 241: 5. Moved: 0. High 5, medium 0, low 0.

### 446 crafting_gold (GOLD_JEWELLERY_INTERFACE)

211: 66 components, 241: 66. Moved: 0. High 66, medium 0, low 0.

### 451 itemsets (ITEM_SETS)

211: 5 components, 241: 9. Moved: 1. High 5, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 4 | 8 | high | com_8 |  |

New in 241: 4 itemlist_no_items, 5 com_5, 6 com_6, 7 filter_button

### 458 poh_furniture_creation (FURNITURE_CREATION)

211: 36 components, 241: 36. Moved: 0. High 36, medium 0, low 0.

### 459 tob_infoboard (TOB_PERFORMANCE_DETAILS)

211: 56 components, 241: 56. Moved: 0. High 56, medium 0, low 0.

### 464 ge_pricechecker (PRICE_CHECKER)

211: 13 components, 241: 13. Moved: 0. High 13, medium 0, low 0.

### 474 blast_furnace_hud (BLAST_FURNACE_COFFER)

211: 4 components, 241: 4. Moved: 0. High 4, medium 0, low 0.

### 475 wilderness_warningscreen

211: 14 components, 241: 14. Moved: 0. High 14, medium 0, low 0.

### 476 br_settings (DUEL_CONFIRMATION)

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.

### 477 hunting_customfurs (CUSTOM_FUR_CLOTHING)

211: 17 components, 241: 17. Moved: 0. High 17, medium 0, low 0.

### 482 toa_raid_summary (TOA_SCOREBOARD)

211: 77 components, 241: 77. Moved: 0. High 77, medium 0, low 0.

### 483 lumbridge_alchemy (EXPLORER_RING_ALCH)

211: 9 components, 241: 9. Moved: 0. High 9, medium 0, low 0.

### 493 hidey_holes (STASH_UNIT)

211: 35 components, 241: 35. Moved: 0. High 35, medium 0, low 0.

### 499 raids_lobby_partylist (RAIDING_PARTIES)

211: 59 components, 241: 60. Moved: 46. High 58, medium 1, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 12 | 12 | medium | sort_scaling | width |
| 13 | 14 | high | sort_age | width,widthmode,anchor,anchored |
| 14 | 15 | high | list |  |
| 15 | 16 | high | 00 |  |
| 16 | 17 | high | 01 |  |
| 17 | 18 | high | 02 |  |
| 18 | 19 | high | 03 |  |
| 19 | 20 | high | 04 |  |
| 20 | 21 | high | 05 |  |
| 21 | 22 | high | 06 |  |
| 22 | 23 | high | 07 |  |
| 23 | 24 | high | 08 |  |
| 24 | 25 | high | 09 |  |
| 25 | 26 | high | 10 |  |
| 26 | 27 | high | 11 |  |
| 27 | 28 | high | 12 |  |
| 28 | 29 | high | 13 |  |
| 29 | 30 | high | 14 |  |
| 30 | 31 | high | 15 |  |
| 31 | 32 | high | 16 |  |
| 32 | 33 | high | 17 |  |
| 33 | 34 | high | 18 |  |
| 34 | 35 | high | 19 |  |
| 35 | 36 | high | 20 |  |
| 36 | 37 | high | 21 |  |
| 37 | 38 | high | 22 |  |
| 38 | 39 | high | 23 |  |
| 39 | 40 | high | 24 |  |
| 40 | 41 | high | 25 |  |
| 41 | 42 | high | 26 |  |
| 42 | 43 | high | 27 |  |
| 43 | 44 | high | 28 |  |
| 44 | 45 | high | 29 |  |
| 45 | 46 | high | 30 |  |
| 46 | 47 | high | 31 |  |
| 47 | 48 | high | 32 |  |
| 48 | 49 | high | 33 |  |
| 49 | 50 | high | 34 |  |
| 50 | 51 | high | 35 |  |
| 51 | 52 | high | 36 |  |
| 52 | 53 | high | 37 |  |
| 53 | 54 | high | 38 |  |
| 54 | 55 | high | 39 |  |
| 55 | 56 | high | scrollbar |  |
| 56 | 57 | high | refresh |  |
| 57 | 58 | high | tooltip |  |
| 58 | 59 | high | myparty |  |

New in 241: 13 sort_challenge

### 500 raids_sidepanel (RAID_PARTY_TAB)

211: 16 components, 241: 16. Moved: 0. High 16, medium 0, low 0.

### 507 raids_lobby_partydetails (RAIDING_PARTY)

211: 45 components, 241: 48. Moved: 21. High 35, medium 9, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 1 | 1 | medium | canvas | height,width |
| 24 | 25 | high | com_25 |  |
| 25 | 26 | high | com_26 |  |
| 26 | 27 | high | com_27 |  |
| 27 | 28 | medium | com_28 | x,y,xmode,height,ymode |
| 28 | 29 | high | timer | y,height,ymode,anchor,anchored |
| 29 | 30 | high | stats | y,anchor,anchored |
| 30 | 31 | high | com_31 | width |
| 31 | 32 | high | advertise | y,height,anchor,anchored |
| 32 | 33 | high | disband | y,height,anchor,anchored |
| 33 | 34 | high | join | y,height,anchor,anchored |
| 34 | 35 | high | back | height,anchor,anchored |
| 35 | 36 | high | refresh | height,anchor,anchored |
| 36 | 37 | high | tooltip |  |
| 37 | 38 | medium | com_38 | parent |
| 38 | 39 | medium | com_39 | parent |
| 39 | 40 | medium | com_40 | parent |
| 40 | 41 | medium | challenge_checkbox | parent |
| 41 | 42 | medium | com_42 | parent |
| 42 | 43 | medium | com_43 | parent |
| 43 | 44 | medium | com_44 | parent |
| 44 | -1 | low |  | unmatched |

New in 241: 24 mappool_layer, 45 com_45, 46 com_46, 47 com_47

### 513 raids_overlay (RAID_OVERLAY)

211: 4 components, 241: 4. Moved: 0. High 4, medium 0, low 0.

### 522 dream_monster_stat (MONSTER_EXAMINE)

211: 28 components, 241: 28. Moved: 0. High 28, medium 0, low 0.

### 523 dream_player_stats

211: 98 components, 241: 102. Moved: 6. High 98, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 92 | 96 | high | scrollbar_surround |  |
| 93 | 97 | high | scrollbar |  |
| 94 | 98 | high | stat_name |  |
| 95 | 99 | high | dream_playerstat_close |  |
| 96 | 100 | high | dream_player_close |  |
| 97 | 101 | high | com_101 |  |

New in 241: 92 sailing, 93 stat_sailing, 94 statbase_sailing, 95 com_95

### 530 nightmare_scoreboard (NIGHTMARE_STATISTICS)

211: 19 components, 241: 19. Moved: 0. High 19, medium 0, low 0.

### 539 raids_rewards (RAID_REWARDS)

211: 6 components, 241: 12. Moved: 0. High 6, medium 0, low 0.

New in 241: 6 bank, 7 bank_icon, 8 inventory, 9 inventory_icon, 10 discard, 11 discard_icon

### 540 ii_elnocks_exchange (ELNOCKS_EXCHANGE)

211: 8 components, 241: 8. Moved: 0. High 8, medium 0, low 0.

### 549 kill_log

211: 29 components, 241: 29. Moved: 0. High 29, medium 0, low 0.

### 550 raids_storage_shared (RAIDS_SHARED_STORAGE)

211: 9 components, 241: 9. Moved: 0. High 9, medium 0, low 0.

### 551 raids_storage_side (RAIDS_STORAGE_INVENTORY_INTERFACE)

211: 9 components, 241: 9. Moved: 0. High 9, medium 0, low 0.

### 582 decant (DECANTING)

211: 9 components, 241: 9. Moved: 0. High 9, medium 0, low 0.

### 584 confirmdestroy

211: 8 components, 241: 8. Moved: 0. High 8, medium 0, low 0.

### 591 araxxor_scoreboard (THESSALIA_MAKEOVER)

211: 15 components, 241: 20. Moved: 15. High 0, medium 0, low 15.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | -1 | low |  | unmatched |
| 1 | -1 | low |  | unmatched |
| 2 | -1 | low |  | unmatched |
| 3 | -1 | low |  | unmatched |
| 4 | -1 | low |  | unmatched |
| 5 | -1 | low |  | unmatched |
| 6 | -1 | low |  | unmatched |
| 7 | -1 | low |  | unmatched |
| 8 | -1 | low |  | unmatched |
| 9 | -1 | low |  | unmatched |
| 10 | -1 | low |  | unmatched |
| 11 | -1 | low |  | unmatched |
| 12 | -1 | low |  | unmatched |
| 13 | -1 | low |  | unmatched |
| 14 | -1 | low |  | unmatched |

New in 241: 0 universe, 1 background, 2 close, 3 close_icon, 4 content, 5 title, 6 title_text, 7 stats, 8 pkc, 9 pkc_content, 10 pd, 11 pd_content, 12 pbt, 13 pbt_content, 14 gkc, 15 gkc_content, 16 gd, 17 gd_content, 18 gbt, 19 gbt_content

### 592 dizanas_quiver (MAGIC_STORAGE)

211: 25 components, 241: 13. Moved: 25. High 0, medium 0, low 25.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | -1 | low |  | unmatched |
| 1 | 4 | low | ammo | onload,parent |
| 2 | 8 | low | charges | y,height,ymode,width,parent |
| 3 | -1 | low |  | unmatched |
| 4 | 11 | low | ammo_obj | x,dragdepth,height,ymode,width,parent |
| 5 | -1 | low |  | unmatched |
| 6 | -1 | low |  | unmatched |
| 7 | -1 | low |  | unmatched |
| 8 | -1 | low |  | unmatched |
| 9 | -1 | low |  | unmatched |
| 10 | -1 | low |  | unmatched |
| 11 | -1 | low |  | unmatched |
| 12 | -1 | low |  | unmatched |
| 13 | -1 | low |  | unmatched |
| 14 | -1 | low |  | unmatched |
| 15 | -1 | low |  | unmatched |
| 16 | -1 | low |  | unmatched |
| 17 | -1 | low |  | unmatched |
| 18 | -1 | low |  | unmatched |
| 19 | -1 | low |  | unmatched |
| 20 | -1 | low |  | unmatched |
| 21 | -1 | low |  | unmatched |
| 22 | -1 | low |  | unmatched |
| 23 | -1 | low |  | unmatched |
| 24 | -1 | low |  | unmatched |

New in 241: 0 universe, 1 content, 2 storage, 3 com_3, 5 bolt_title, 6 ammo_slot, 7 title, 9 charges_text, 10 com_10, 12 close

### 594 floater_blankmodal

211: 1 components, 241: 1. Moved: 0. High 1, medium 0, low 0.

### 595 worldmap (WORLD_MAP)

211: 43 components, 241: 43. Moved: 0. High 43, medium 0, low 0.

### 597 bookofscrolls (MASTER_SCROLL_BOOK)

211: 65 components, 241: 85. Moved: 13. High 37, medium 26, low 2.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 1 | 1 | medium | com_1 | x,y,modelzoom |
| 2 | -1 | low |  | unmatched |
| 3 | 3 | medium | close | x,xmode,parent |
| 4 | 4 | medium | title | x,parent |
| 5 | 5 | medium | teleportscroll_nardah | x,y,parent |
| 9 | 9 | medium | teleportscroll_digsite | x,y,parent |
| 13 | 13 | medium | teleportscroll_feldip | x,y,parent |
| 17 | 17 | medium | teleportscroll_lunarisle | x,y,parent |
| 21 | 21 | medium | teleportscroll_mortton | x,y,parent |
| 25 | 25 | medium | teleportscroll_pestcontrol | x,y,parent |
| 29 | 29 | medium | teleportscroll_piscatoris | x,y,parent |
| 33 | 33 | medium | teleportscroll_taibwo | x,y,parent |
| 37 | 37 | medium | teleportscroll_elf | x,y,parent |
| 41 | 41 | medium | teleportscroll_mosles | x,y,parent |
| 45 | 45 | medium | teleportscroll_lumberyard | x,y,parent |
| 49 | 49 | medium | teleportscroll_zulandra | x,y,parent |
| 53 | -1 | low |  | unmatched |
| 54 | 53 | medium | gfx_cerberus | parent |
| 55 | 54 | medium | text_cerberus | parent |
| 56 | 55 | medium | title_cerberus | parent |
| 57 | 56 | medium | teleportscroll_cerberus | x,y,opbase,parent |
| 58 | 59 | medium | gfx_revenants | parent |
| 59 | 60 | medium | text_revenants | parent |
| 60 | 61 | medium | title_revenants | parent |
| 61 | 62 | medium | teleportscroll_guthixian_temple | x,y,opbase,parent |
| 62 | 64 | medium | gfx_watson | parent |
| 63 | 65 | medium | text_watson | parent |
| 64 | 66 | medium | title_watson | parent |

New in 241: 2 contents, 57 teleportscroll_revenants, 58 teleportscroll_watson, 63 teleportscroll_spidercave, 67 gfx_guthixian_temple, 68 text_guthixian_temple, 69 title_guthixian_temple, 70 gfx_spidercave, 71 text_spidercave, 72 title_spidercave, 73 teleportscroll_colossal_wyrm, 74 teleportscroll_chasmoffire, 75 teleportscroll_ardeaglais, 76 gfx_colossal_wyrm, 77 text_colossal_wyrm, 78 title_colossal_wyrm, 79 gfx_chasmoffire, 80 text_chasmoffire, 81 title_chasmoffire, 82 gfx_ardeaglais, 83 text_ardeaglais, 84 title_ardeaglais

### 602 gravestone_retrieval (ITEM_RETRIEVAL_SERVICE)

211: 12 components, 241: 16. Moved: 4. High 12, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 8 | 10 | high | discard |  |
| 9 | 11 | high | discard_gfx |  |
| 10 | 14 | high | info | x,xmode,anchor,anchored |
| 11 | 15 | high | fee |  |

New in 241: 8 button_bank, 9 button_bank_gfx, 12 bank, 13 com_13

### 608 fossil_mushtrees (MYCELIUM_TELEPORTATION)

211: 19 components, 241: 19. Moved: 0. High 19, medium 0, low 0.

### 621 collection (COLLECTION_LOG)

211: 83 components, 241: 90. Moved: 66. High 71, medium 6, low 6.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | 0 | medium | frame | widthmode,height,width,parent |
| 1 | -1 | low |  | unmatched |
| 2 | 2 | medium | content | parent |
| 9 | 9 | low | list | widthmode,width |
| 17 | 17 | medium | main | widthmode,width |
| 18 | -1 | low |  | unmatched |
| 19 | 20 | medium | header_text | parent |
| 20 | 21 | high | combat_achievements |  |
| 21 | 22 | medium | com_22 | parent |
| 22 | 23 | high | raid_scrollbar |  |
| 23 | 24 | high | clue_container |  |
| 24 | 25 | high | clue_scrollbar |  |
| 25 | 26 | high | minigame_container |  |
| 26 | 27 | high | minigame_background |  |
| 27 | 28 | high | minigame_scrollbar |  |
| 28 | 29 | high | other_container |  |
| 29 | 30 | high | other_scrollbar |  |
| 30 | 31 | high | com_31 |  |
| 31 | 32 | high | clue_background |  |
| 32 | 33 | high | clue_text |  |
| 33 | 34 | high | other_background |  |
| 34 | 35 | high | other_text |  |
| 35 | 36 | high | minigame_text |  |
| 36 | 37 | high | items_contents |  |
| 37 | 38 | high | items_scrollbar |  |
| 38 | 39 | high | com_39 |  |
| 39 | 40 | low | items | heightmode,height,xmode |
| 40 | 41 | medium | search_keyboard_listener | parent |
| 41 | 42 | high | a |  |
| 42 | 43 | high | b |  |
| 43 | 44 | high | c |  |
| 44 | 45 | high | d |  |
| 45 | 46 | high | e |  |
| 46 | 47 | high | f |  |
| 47 | 48 | high | g |  |
| 48 | 49 | high | h |  |
| 49 | 50 | high | i |  |
| 50 | 51 | high | j |  |
| 51 | 52 | high | k |  |
| 52 | 53 | high | l |  |
| 53 | 54 | high | m |  |
| 54 | 55 | high | n |  |
| 55 | 56 | high | o |  |
| 56 | 57 | high | p |  |
| 57 | 58 | high | q |  |
| 58 | 59 | high | r |  |
| 59 | 60 | high | s |  |
| 60 | 61 | high | t |  |
| 61 | 62 | high | u |  |
| 62 | 63 | high | v |  |
| 63 | 64 | high | w |  |
| 64 | 65 | high | x |  |
| 65 | 66 | high | y |  |
| 66 | 67 | high | z |  |
| 67 | 68 | high | space |  |
| 68 | 69 | high | backspace |  |
| 69 | 70 | high | tab |  |
| 70 | 71 | high | content_search | parent,anchored |
| 71 | 77 | high | searchbar_container |  |
| 72 | 78 | high | searchbar_image |  |
| 73 | 79 | high | search_text |  |
| 74 | 80 | high | search_title |  |
| 75 | 81 | high | search_container | widthmode,height,width,heightmode,anchored |
| 76 | 82 | high | search_scrollbar |  |
| 77 | 85 | high | search_results | height,parent,anchored |
| 78 | 86 | high | search_results_clickzone |  |
| 79 | -1 | low |  | unmatched |
| 80 | 76 | high | search_toggle | parent,anchored |
| 81 | 75 | high | search_button | parent,anchored |
| 82 | -1 | low |  | unmatched |

New in 241: 1 close, 18 burger_menu_overlay, 19 header, 72 burger_btn_menu, 73 burger_menu_frame, 74 search_toggle_clickzone, 83 search_results_container, 84 search_results_background, 87 infinity, 88 universe, 89 resize_preview

### 630 seed_vault_deposit (SEED_VAULT_INVENTORY)

211: 2 components, 241: 3. Moved: 2. High 0, medium 1, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | 1 | medium | locked_slot | parent |
| 1 | 2 | low | inv | onload,parent |

New in 241: 0 universe

### 631 seed_vault (SEED_VAULT)

211: 33 components, 241: 37. Moved: 29. High 33, medium 0, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 4 | 5 | high | left_panel | onload,geometry-match |
| 5 | 6 | high | left_scroll |  |
| 6 | 7 | high | left_list |  |
| 7 | 8 | high | category_backgrounds |  |
| 8 | 9 | high | category_list |  |
| 9 | 10 | high | right_panel | onload,geometry-match |
| 10 | 11 | high | right_scroll |  |
| 11 | 12 | high | list |  |
| 12 | 13 | high | contract_seeds |  |
| 13 | 16 | high | category_headers | parent,anchored |
| 14 | 17 | high | category_lines | parent,anchored |
| 15 | 18 | high | obj_list | parent,anchored |
| 16 | 19 | high | text_list | parent,anchored |
| 17 | 20 | high | buttons |  |
| 18 | 22 | high | com_22 |  |
| 19 | 23 | high | x1 | x,onvartransmit,anchor,anchored |
| 20 | 24 | high | x5 | x,onvartransmit,anchor,anchored |
| 21 | 25 | high | x10 | x,onvartransmit,anchor,anchored |
| 22 | 26 | high | xx | x,onvartransmit,anchor,anchored |
| 23 | 27 | high | xall | onvartransmit,anchor,anchored |
| 24 | 28 | high | search |  |
| 25 | 29 | high | deposit_all |  |
| 26 | 30 | high | deposit_all_gfx |  |
| 27 | 31 | high | text_1 |  |
| 28 | 32 | high | text_5 |  |
| 29 | 33 | high | text_10 |  |
| 30 | 34 | high | text_x |  |
| 31 | 35 | high | text_all |  |
| 32 | 36 | high | search_gfx |  |

New in 241: 4 popup, 14 main_vault, 15 faves, 21 lock_menu

### 634 overlay_portal (DEATHS_OFFICE_ENTER_OVERLAY)

211: 3 components, 241: 4. Moved: 0. High 3, medium 0, low 0.

New in 241: 3 fader

### 639 gauntlet_scoreboard (GAUNTLET_SCOREBOARD)

211: 31 components, 241: 31. Moved: 0. High 31, medium 0, low 0.

### 660 notification_display (NOTIFICATION)

211: 9 components, 241: 9. Moved: 0. High 9, medium 0, low 0.

### 663 loading_icon

211: 2 components, 241: 2. Moved: 0. High 2, medium 0, low 0.

### 669 death_office (DEATHS_OFFICE_RETRIEVAL)

211: 12 components, 241: 12. Moved: 0. High 12, medium 0, low 0.

### 670 death_coffer (DEATHS_OFFICE_SACRIFICE)

211: 14 components, 241: 14. Moved: 0. High 14, medium 0, low 0.

### 671 death_coffer_side (DEATHS_OFFICE_SACRIFICE_INV)

211: 1 components, 241: 1. Moved: 0. High 1, medium 0, low 0.

### 672 gravestone_generic (GRAVESTONE_RECLAIM)

211: 20 components, 241: 20. Moved: 0. High 20, medium 0, low 0.

### 674 poh_costumes_side (STORAGE_ROOM_INV)

211: 1 components, 241: 1. Moved: 0. High 1, medium 0, low 0.

### 675 poh_costumes (STORAGE_ROOM)

211: 11 components, 241: 11. Moved: 0. High 11, medium 0, low 0.

### 679 player_design (CHARACTER_DESIGN)

211: 69 components, 241: 80. Moved: 68. High 67, medium 2, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 1 | 3 | high | noclick |  |
| 2 | 4 | high | main |  |
| 3 | 5 | high | frame | onload,geometry-match |
| 4 | 6 | high | divider_left |  |
| 5 | 7 | high | divider_right |  |
| 6 | 8 | high | divider_right_bottom |  |
| 7 | 10 | high | contents_left |  |
| 8 | 11 | high | contents_left_header |  |
| 9 | 12 | high | contents_left_buttons |  |
| 10 | 13 | high | head |  |
| 11 | 14 | high | head_text |  |
| 12 | 15 | high | head_left |  |
| 13 | 16 | high | head_right |  |
| 14 | 17 | high | jaw |  |
| 15 | 18 | high | jaw_text | onload,geometry-match |
| 16 | 19 | high | jaw_left | onload,geometry-match |
| 17 | 20 | high | jaw_right | onload,geometry-match |
| 18 | 21 | high | torso |  |
| 19 | 22 | high | torso_text |  |
| 20 | 23 | high | torso_left |  |
| 21 | 24 | high | torso_right |  |
| 22 | 25 | high | arms |  |
| 23 | 26 | high | arms_text |  |
| 24 | 27 | high | arms_left |  |
| 25 | 28 | high | arms_right |  |
| 26 | 29 | high | hands |  |
| 27 | 30 | high | hands_text |  |
| 28 | 31 | high | hands_left |  |
| 29 | 32 | high | hands_right |  |
| 30 | 33 | high | legs |  |
| 31 | 34 | high | legs_text |  |
| 32 | 35 | high | legs_left |  |
| 33 | 36 | high | legs_right |  |
| 34 | 37 | high | feet |  |
| 35 | 38 | high | feet_text |  |
| 36 | 39 | high | feet_left |  |
| 37 | 40 | high | feet_right |  |
| 38 | 41 | high | contents_right |  |
| 39 | 42 | high | contents_right_header |  |
| 40 | 43 | high | contents_right_buttons |  |
| 41 | 44 | high | hair |  |
| 42 | 45 | high | hair_text |  |
| 43 | 46 | high | hair_left |  |
| 44 | 47 | high | hair_right |  |
| 45 | 48 | high | torso_col |  |
| 46 | 49 | high | torso_col_text |  |
| 47 | 50 | high | torso_col_left |  |
| 48 | 51 | high | torso_col_right |  |
| 49 | 52 | high | legs_col |  |
| 50 | 53 | high | legs_col_text |  |
| 51 | 54 | high | legs_col_left |  |
| 52 | 55 | high | legs_col_right |  |
| 53 | 56 | high | feet_col |  |
| 54 | 57 | high | feet_col_text |  |
| 55 | 58 | high | feet_col_left |  |
| 56 | 59 | high | feet_col_right |  |
| 57 | 60 | high | skin |  |
| 58 | 61 | high | skin_text |  |
| 59 | 62 | high | skin_left |  |
| 60 | 63 | high | skin_right |  |
| 61 | 64 | high | contents_bottom |  |
| 62 | 65 | medium | contents_bottom_header | text |
| 63 | 66 | high | contents_bottom_buttons |  |
| 64 | 67 | high | gender |  |
| 65 | 68 | high | gender_male | onload,geometry-match |
| 66 | 69 | high | gender_female | onload,geometry-match |
| 67 | 73 | medium | player | modelorigin,y,height,modelzoom |
| 68 | 74 | high | confirm |  |

New in 241: 1 dropdown_container, 2 tooltip, 9 divider_middle_top, 70 contents_middle, 71 contents_middle_header, 72 pronouns, 75 dropdown_close, 76 dropdown_panel, 77 background, 78 pronouns_buttons, 79 dropdown_scrollbar

### 680 indexed_book

211: 112 components, 241: 112. Moved: 0. High 112, medium 0, low 0.

### 711 helper_generic

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.

### 713 ca_boss (CA_BOSS)

211: 37 components, 241: 37. Moved: 0. High 37, medium 0, low 0.

### 714 ca_rewards (CA_REWARDS)

211: 17 components, 241: 19. Moved: 15. High 15, medium 1, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 2 | 3 | high | frame |  |
| 3 | 5 | medium | rewards | y,height |
| 4 | 6 | high | rewards_content |  |
| 5 | 7 | high | tiers_content_frame |  |
| 6 | 8 | high | com_8 |  |
| 7 | 9 | high | com_9 |  |
| 8 | 10 | high | tiers_content_scrollbar |  |
| 9 | 11 | low | tiers | y,height,ymode |
| 10 | 12 | high | tiers_content |  |
| 11 | 13 | high | tiers_content_borders |  |
| 12 | 14 | high | burger_btn_menu |  |
| 13 | 15 | high | burger_menu_overlay |  |
| 14 | 16 | high | burger_menu_frame |  |
| 15 | 17 | high | tiers_click |  |
| 16 | 18 | high | tiers_tabs |  |

New in 241: 2 tooltip, 4 progress_bar

### 715 ca_tasks (CA_TASKS)

211: 36 components, 241: 36. Moved: 0. High 36, medium 0, low 0.

### 716 ca_bosses (CA_BOSS_OVERVIEW)

211: 25 components, 241: 25. Moved: 0. High 25, medium 0, low 0.

### 717 ca_overview (CA_OVERVIEW)

211: 17 components, 241: 18. Moved: 14. High 15, medium 2, low 0.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 3 | 4 | high | ca_general_header | y,anchor,anchored |
| 4 | 5 | high | ca_personal_header | y |
| 5 | 6 | medium | ca_general | y,height |
| 6 | 7 | high | ca_buttons |  |
| 7 | 8 | high | ca_buttons_click |  |
| 8 | 9 | high | ca_general_content |  |
| 9 | 10 | high | ca_tier_name |  |
| 10 | 11 | high | ca_tier_icon |  |
| 11 | 12 | high | ca_progress |  |
| 12 | 13 | medium | ca_personal | y,height |
| 13 | 14 | high | ca_personal_content |  |
| 14 | 15 | high | burger_btn_menu |  |
| 15 | 16 | high | burger_menu_overlay |  |
| 16 | 17 | high | burger_menu_frame |  |

New in 241: 3 progress_bar

### 721 gim_limited_sidepanel (DEFAULT_GIM_TAB)

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.

### 723 gim_creation_sidepanel (FORM_GIM_TAB)

211: 13 components, 241: 13. Moved: 0. High 13, medium 0, low 0.

### 724 shared_bank (STORAGE_GIM)

211: 48 components, 241: 41. Moved: 28. High 20, medium 9, low 19.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 20 | -1 | low |  | unmatched |
| 21 | 20 | medium | mode_layer | x,width,parent |
| 22 | -1 | low |  | unmatched |
| 23 | -1 | low |  | unmatched |
| 24 | -1 | low |  | unmatched |
| 25 | -1 | low |  | unmatched |
| 26 | -1 | low |  | unmatched |
| 27 | 22 | medium | withdrawal_layer | x,width,parent |
| 28 | -1 | low |  | unmatched |
| 29 | -1 | low |  | unmatched |
| 30 | -1 | low |  | unmatched |
| 31 | -1 | low |  | unmatched |
| 32 | -1 | low |  | unmatched |
| 33 | 26 | medium | com_26 | x,parent |
| 34 | 27 | medium | save_button | x,parent |
| 35 | 29 | low | popup | x,y,widthmode,xmode,width,parent |
| 36 | -1 | low |  | unmatched |
| 37 | -1 | low |  | unmatched |
| 38 | 32 | medium | quantity1_text | x,y,parent |
| 39 | -1 | low |  | unmatched |
| 40 | 34 | medium | quantity5_text | x,y,parent |
| 41 | -1 | low |  | unmatched |
| 42 | 36 | medium | quantity10_text | x,y,xmode,parent |
| 43 | -1 | low |  | unmatched |
| 44 | 38 | medium | quantityx_text | x,y,xmode,parent |
| 45 | -1 | low |  | unmatched |
| 46 | 40 | medium | quantityall_text | x,y,xmode,parent |
| 47 | -1 | low |  | unmatched |

New in 241: 21 swap_insert, 23 note, 24 swap_insert_graphic, 25 quantity_layer, 28 bottom, 30 note_graphic, 31 quantity1, 33 quantity5, 35 quantity10, 37 quantityx, 39 quantityall

### 725 shared_bank_side (STORAGE_GIM_INVENTORY)

211: 1 components, 241: 1. Moved: 0. High 1, medium 0, low 0.

### 726 gim_sidepanel (ACTIVE_GIM_TAB)

211: 9 components, 241: 9. Moved: 0. High 9, medium 0, low 0.

### 727 side_channels_large (GIM_CHAT_CHANNELS)

211: 10 components, 241: 10. Moved: 6. High 9, medium 0, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 2 | -1 | low |  | unmatched |
| 3 | 2 | high | tab_gim |  |
| 4 | 3 | high | tab_0 |  |
| 5 | 4 | high | tab_1 |  |
| 6 | 5 | high | tab_2 |  |
| 7 | 6 | high | tab_3 |  |

New in 241: 7 tab_line

### 730 gim_settings (SETTINGS_GIM)

211: 22 components, 241: 22. Moved: 0. High 22, medium 0, low 0.

### 732 nex_scoreboard (NEX_STATS)

211: 20 components, 241: 20. Moved: 0. High 20, medium 0, low 0.

### 742 wildy_loot_chest (WILDERNESS_LOOT_KEY)

211: 37 components, 241: 47. Moved: 33. High 11, medium 11, low 15.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 0 | -1 | low |  | unmatched |
| 1 | 1 | medium | contents | height,parent |
| 3 | 5 | low | items | y,height,width |
| 4 | 6 | high | items_empty | y |
| 5 | 7 | high | items_empty_text |  |
| 6 | -1 | low |  | unmatched |
| 7 | -1 | low |  | unmatched |
| 8 | -1 | low |  | unmatched |
| 9 | 12 | medium | destroy | onop,onmouseover,onmouseleave,parent |
| 10 | 13 | medium | destroy_graphic | parent |
| 11 | 14 | low | destroy_divider | x,height,xmode |
| 12 | 17 | high | withdrawitemgfx1 | parent,anchored |
| 13 | 18 | high | withdrawitemgfx2 | parent,anchored |
| 14 | 19 | high | withdrawitemgfx3 | parent,anchored |
| 15 | 20 | medium | withdrawitem_text | parent |
| 16 | 22 | high | withdrawnotegfx1 | parent,anchored |
| 17 | 23 | high | withdrawnotegfx2 | parent,anchored |
| 18 | 24 | high | withdrawnotegfx3 | parent,anchored |
| 19 | 25 | medium | withdrawnote_text | parent |
| 20 | 27 | medium | withdrawinv | onop,onmouseover,onmouseleave,parent |
| 21 | 28 | medium | withdrawinv_graphic | graphicshadow,parent |
| 22 | -1 | low |  | unmatched |
| 23 | -1 | low |  | unmatched |
| 24 | -1 | low |  | unmatched |
| 25 | -1 | low |  | unmatched |
| 26 | -1 | low |  | unmatched |
| 27 | -1 | low |  | unmatched |
| 28 | 29 | medium | withdraw_divider | x,height,parent |
| 29 | 30 | medium | occupiedslots | x,y,ymode,parent |
| 30 | 31 | low | occupied_divider | x,y,ymode,parent |
| 31 | 32 | medium | capacity | x,y,ymode,parent |
| 32 | 33 | low | withdrawbank_layer | x,y,ymode,parent |
| 33 | -1 | low |  | unmatched |
| 36 | 37 | medium | controls_greyout | parent |

New in 241: 0 universe, 3 tab_divider, 4 tabs, 8 divider_h1, 9 divider_h2, 10 divider_h3, 11 destroy_layer, 15 withdraw_as, 16 withdrawitem, 21 withdrawnote, 26 withdrawinv_layer, 36 controls_greyout_layer, 38 confirm_layer, 39 greyout, 40 confirm_title, 41 confirm_body, 42 confirm_buttons, 43 destroy_confirm, 44 destroy_cancel, 45 destroy_confirm_text, 46 destroy_cancel_text

### 743 gim_shared_bank_unlocks (SETTINGS_GIM_STORAGE)

211: 17 components, 241: 17. Moved: 0. High 17, medium 0, low 0.

### 744 gim_options (SETTINGS_GIM_OPTIONS)

211: 15 components, 241: 15. Moved: 0. High 15, medium 0, low 0.

### 771 toa_chests (TOA_LOOT)

211: 11 components, 241: 11. Moved: 0. High 7, medium 3, low 1.

| 211 | 241 | conf | 241 name | notes |
|---|---|---|---|---|
| 2 | 2 | medium | content | x,xmode,width |
| 5 | 5 | medium | bank_icon | x,xmode |
| 7 | 7 | medium | inventory_icon | x,xmode |
| 10 | 10 | low | items | y,ymode |

### 772 toa_partylist (TOA_PARTY_OVERVIEW)

211: 67 components, 241: 67. Moved: 0. High 67, medium 0, low 0.

### 773 toa_lobby (TOA_PARTY)

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.

### 774 toa_partydetails (TOA_PARTY_MANAGEMENT)

211: 101 components, 241: 101. Moved: 0. High 101, medium 0, low 0.

### 775 toa_scoreboard (TOA_STATISTICS)

211: 88 components, 241: 88. Moved: 0. High 88, medium 0, low 0.

### 776 toa_invocations (TOA_INVOCATION_INFO)

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.

### 777 toa_midraid_loot (TOA_SUPPLIES_SHOP)

211: 13 components, 241: 13. Moved: 0. High 13, medium 0, low 0.

### 778 toa_midraidloot_bag (TOA_SUPPLIES_INV)

211: 6 components, 241: 6. Moved: 0. High 6, medium 0, low 0.


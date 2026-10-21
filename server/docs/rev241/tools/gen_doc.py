"""Write server/docs/rev241-interfaces.md and server/docs/rev241/interface-remap.tsv from remap.json etc."""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = sys.argv[1]
remap = json.load(open(os.path.join(HERE, "remap.json")))
scripts = json.load(open(os.path.join(HERE, "scripts.json")))
varchk = json.load(open(os.path.join(HERE, "varchk.json")))
I = remap["interfaces"]
summ = remap["summary"]

gi = open(os.path.join(SERVER, "core/src/main/java/com/zenyte/game/GameInterface.java")).read()
import re

gnames = {}
for m in re.finditer(r"^\s*([A-Z_0-9]+)\(\s*(\d+)", gi, re.M):
    gnames.setdefault(int(m.group(2)), []).append(m.group(1))

CORE = [548, 161, 164, 165, 601, 162, 149, 387, 84, 85, 593, 541, 77, 218, 320, 629, 399, 712, 259, 109, 116, 134,
        216, 239, 429, 432, 7, 707, 701, 702, 76, 182, 69, 12, 15, 231, 217, 219, 193, 11, 229, 233, 270, 300, 301,
        160, 122]

os.makedirs(os.path.join(SERVER, "docs", "rev241"), exist_ok=True)
with open(os.path.join(SERVER, "docs", "rev241", "interface-remap.tsv"), "w") as f:
    f.write("# interface\tname241\tcomponent211\tcomponent241(-1 = none)\tconfidence\tname241\tnotes\n")
    for i in sorted(I, key=int):
        r = I[i]
        if "rows" not in r:
            continue
        for row in r["rows"]:
            f.write("%s\t%s\t%d\t%d\t%s\t%s\t%s\n" % (i, r["name241"], row[0], row[1], row[2], row[4], ",".join(row[5])))

L = []
w = L.append
w("# Build 241 interface remap (211 -> 241)")
w("")
w("Generated for the rev-241 test build (branch `claude/rev241-content`). It maps every component id the server")
w("uses on the 211 cache to the component on the vanilla build-241 cache (OpenRS2 cache 2746), lists the")
w("clientscripts and varbits whose meaning changed, and records what was changed in code. GadScape's custom")
w("interfaces (ids that are not in the vanilla 211 cache) are out of scope.")
w("")
w("Machine-readable table: `docs/rev241/interface-remap.tsv` (one row per 211 component).")
w("")
w("## How the table was built")
w("")
w("Inputs: Joshua-F/osrs-dumps commit `c43ec08` (2023-03-01, rev 211; its per-interface component counts match")
w("GadScape's vanilla 211 cache exactly) and commit `6e5033a` (2026-10-20, rev 241, = OpenRS2 cache 2746), which")
w("carries the 241 gamevals names for interfaces and components.")
w("")
w("1. **Structural alignment.** For each interface, the 211 and 241 component lists are aligned in id order")
w("   (Needleman-Wunsch style dynamic programming). Two components are compared on type, geometry, text,")
w("   graphic, ops and hook scripts (script names and literal arguments; component references are compared by")
w("   interface only). Ties go to the earliest 241 component, so components Jagex inserted after a sibling")
w("   (e.g. `maincrm` after `mainmodal`) do not steal the old id.")
w("2. **Anchors.** Pairs that are known independently are fixed before the alignment:")
w("   - top-level panes 548/164/165/601 from the remap enums 1129/1131/1132/1745 (both revisions map")
w("     161 components to the other panes, so a verified 161 mapping fixes the others);")
w("   - component references in the same lines of the same clientscript in both revisions, and in component")
w("     valued enums with the same keys (4,050 components); and hook-argument lists (e.g. `bankmain_init`);")
w("   - semantic anchors: prayer slots from `enum_859` (211) vs `oc_param(enum_4956[p], param_1751)` (241), and")
w("     hand-verified rows for the bank, bank side panel, settings side panel, music, combat, skillmulti, orbs and")
w("     journal (marked `anchored` below).")
w("3. **Confidence.** `high`: identical component, or same type/geometry/parent with only ops or handlers changed,")
w("   or the same onload script call with the same literal arguments, or anchored. `medium`: similar but not")
w("   proven. `low`: no 241 counterpart found (usually removed or redesigned).")
w("")
w("**Verification.** Mapping the 211 entries of the pane remap enums through the table reproduces the 241 enums")
w("exactly: enum 1129 (79/79 entries), 1130 (79/79), 1131 (80/80), 1132 (36/36); enum 1745 (mobile) 81/83, the two")
w("misses are 601-internal entries that no longer exist in 241. Over all %d interfaces: %d component rows, %d high,"
  % (len(I), sum(v.get("n211", 0) for v in summ.values()), sum(v.get("high", 0) for v in summ.values())))
w("%d medium, %d low." % (sum(v.get("medium", 0) for v in summ.values()), sum(v.get("low", 0) for v in summ.values())))
w("")
w("## Changed in code (core game loop)")
w("")
w("Only high-confidence or hand-verified rows were applied. 211 ids are kept in a `// 211: N` comment next to")
w("every changed literal.")
w("")
w("| Area | Files | What changed |")
w("|---|---|---|")
rows = [
    ("Game frame", "InterfacePosition", "All resizable (161) seed components (+1 from side0, +2 from viewport_tracker_back); DIALOGUE 559 -> 568 (chatbox:chatmodal); COLOUR_PICKER 8 -> 9 (settings:popup). Other panes still resolve through the 241 remap enums. PaneType/GameTab unchanged (GameTab.tabIds is unused)."),
    ("Pane buttons", "Fixed/Resizable/SidePanelsResizable/MobilePaneInterface", "548, 161, 164, 601 stone buttons and gravestone moved by +1/+2."),
    ("Account tab", "InterfaceHandler.sendGameFrame", "If the custom tab 1702 is absent (vanilla 241 cache) the vanilla account tab 109 opens instead."),
    ("Custom interfaces", "GameInterface.open, GameNoticeboardInterface.onLogin", "Interfaces that are not in the cache are not opened and their plugin open() is skipped, so no custom clientscripts (10001+ now collide with real 241 scripts up to 10196) or custom component packets are sent at login."),
    ("Combat 593", "CombatTabInterface, CombatDefinitions", "Styles 4/8/12/16 -> 6/10/14/18, autocast 21/26 -> 23/28, retaliate 30 -> 32, special 36 -> 41 plus the new main/off-hand bars 57/67; weapon name text 1 -> 3, category 2 -> 5."),
    ("Prayer 541", "PrayerTab", "Prayers moved to components 9..37 and Hawk Eye/Eagle Eye/Mystic Will/Mystic Lore were regrouped; a component -> slot table replaces componentId - 5."),
    ("Spellbook 218", "SpellbookInterface", "Filter menu 195 -> 206, house teleport 29 -> 34. Spell components come from the cache (item param 596)."),
    ("Skills 320", "SkillsTabInterface", "Skill buttons unchanged; guard for the new Sailing button (24)."),
    ("Settings 116", "SettingsTabInterface", "Redesigned tab: volume sliders 41/55/69 -> 108/122/136 (music/sound/area bobble containers), mute icons 30/44/58 -> 110/124/138, attack-option dropdowns 81/82 -> 38/39, bottom toggles 72-76 -> 29-33, zoom 87 -> 43."),
    ("Settings 134", "SettingsInterface", "10/19/21/23/25/28 -> 11/20/22/24/26/29."),
    ("Music 239", "MusicPlayerInterface", "jukebox 5 -> 11, now playing 8 -> 4, Auto/Manual 9/11 -> Area/Single mode 14/16."),
    ("Bank 12", "BankInterface, Bonuses, GameCommands", "Bottom bar and menu redesigned: Swap/Insert and Item/Note became single toggles (23, 25) bound as toggles; quantities 28-36 -> 29-37; placeholders 38 -> 40; search 40 -> 42; deposit inv/worn 42/44 -> 47/49; tabs 11 -> 10; items 13 -> 12; menu toggles and fillers -> 129-153; worn-item stat texts 90-108 -> 82-100. Tab drop-target slots are now relative to the bank size (241 bank has 1410 slots, 211 had 1200)."),
    ("Bank side 15", "BankInventoryInterface", "Looting bag deposit 8 -> 6, items 13 -> 11."),
    ("Dialogues", "PlainMessage, LevelUpMessage, SkillMessage, SkillDialogueInterface", "messagebox text/continue 1/2 -> 3/4; level-up skill icon layers re-derived from 241 names (adds sailing); skillmulti item buttons 14.. -> 15.. with 18 buttons and 18 obj arguments for script 2046. npc/player/options/sprite/double-sprite dialogues (231/217/219/193/11) are unchanged."),
    ("Shops 300", "ShopInterface, GameShop", "Script 1074 shop_main_init now (inv, title, obj, int, boolean): sent as (inv, name, -1, 0, 1)."),
    ("Orbs 160", "OrbsInterface", "All buttons +1 (241 inserted 'triggers' at 1): xp 5 -> 6, health 8 -> 9, prayer 19 -> 20, run 27 -> 28, spec 35 -> 36, store 45 -> 46, world map 53 -> 55."),
    ("Journal 629", "JournalHeaderTabInterface + 6 journal panels", "Tabs 3/8/13/28 -> 2/10/18/34, content container 33 -> 43. The custom 'Game Noticeboard' tab has no 241 counterpart."),
    ("Chat channel tabs 707, grouping 76", "RegularChatChannelInterface, GroupingTabInterface, MinigameTabInterface", "707 tabs 3-6 -> 2-5; grouping teleport 26 -> 28."),
    ("Unchanged", "", "149 inventory, 387 worn items, 84 equipment stats (stat texts and slots), 216 emotes, 429/432 friends/ignore, 7/701/702 clan panels, 182 logout, 122 XP drops, 77 quick prayers, 231/217/219/193/11 dialogues, 301 shop side."),
    ("Enums", "EnumDefinitions, Enums", "New getIntEnumOrEmpty/getStringEnumOrEmpty: Enums constants that are missing or retyped in 241 (819/812 music, 273/834/836/854/824 slayer and fairy rings, custom 10024/10053-10056) no longer throw during class initialisation."),
    ("Varbits", "SettingVariables, SettingsInterface, VarCollection, Player, dialogues", "See below."),
]
for a, b, c in rows:
    w("| %s | %s | %s |" % (a, b, c))
w("")

w("## Clientscripts")
w("")
w("Compared by script header in both dumps (all %d literal script ids the server sends)." % len(scripts))
w("")
w("| Script | 211 name | 241 name | 211 args | 241 args | Status |")
w("|---|---|---|---|---|---|")


def short(sig):
    if sig is None:
        return "-"
    if len(sig) > 6 and len(set(sig)) == 1:
        return "%s x%d" % (sig[0], len(sig))
    out = []
    for t in sig:
        if out and out[-1][0] == t:
            out[-1][1] += 1
        else:
            out.append([t, 1])
    return ", ".join(t if n == 1 else "%s x%d" % (t, n) for t, n in out)


fixed = {"1074": "fixed (ShopInterface, GameShop)", "2046": "fixed (SkillMessage)"}
for sid, v in sorted(scripts.items(), key=lambda x: int(x[0])):
    if v["sig211"] is None:
        continue
    changed = v["sig211"] != v["sig241"]
    renamed = v.get("renamed")
    if not changed and not renamed:
        continue
    st = fixed.get(sid, "")
    if not st:
        if "long" in (v["sig241"] or []):
            st = "TODO: 241 takes longs (packed values); call sites not changed"
        elif renamed:
            st = "TODO: id now belongs to a different script; do not send"
        else:
            st = "TODO: not in the core list"
    w("| %s | %s | %s | %s | %s | %s |" % (sid, (v["name211"] or "").split(",")[-1].rstrip("]"),
                                          (v["name241"] or "").split(",")[-1].rstrip("]"), short(v["sig211"]),
                                          short(v["sig241"]), st))
w("")
w("Custom GadScape scripts (ids 10001-10912) are not in the vanilla cache; 241 already uses ids up to 10196, so")
w("custom ids 10001-10196 now run unrelated vanilla scripts if sent. The custom interfaces that send them are")
w("not opened on the 241 cache (see GameInterface.open).")
w("")
w("**Note for the NET track (RUNCLIENTSCRIPT with long arguments).** rsprot 241's `RunClientScript` infers the type")
w("char from the value: `Int` -> 'i', `String` -> 's', `Long` -> 'Ï' (written with p8), `IntArray` -> 'W',")
w("`Array<String>` -> 'X'. `PacketDispatcher.sendClientScript(int, Object...)` should therefore pass `java.lang.Long`")
w("arguments through unchanged (the old 211 encoder casts every non-String to Integer). Scripts that take longs on")
w("241: 785 ge_pricechecker_prices, 1216/1217 (trade values), 1235 wilderness_lootingbag_setprices, 1645")
w("ge_history_addline (last two). The client reads arguments by type in order (ints, strings and longs are")
w("separate stacks), so only the order within each type matters.")
w("")

w("## Varps and varbits")
w("")
w("Varps: none of the 3,775 varps of 211 changed type or was deleted in 241. Varbits: %d of 14,795 211 varbits have"
  % len(varchk["varbit"]))
w("a different base varp or bit range in 241. Because the server resolves varbits through the cache definitions, a")
w("moved varbit is fine as long as its id kept its meaning; the ones below were reused for something else.")
w("")
w("| Varbit | Server use | 241 meaning of the id | Action |")
w("|---|---|---|---|")
vrows = [
    ("5542", "SHIFT_CLICK_TO_DROP_ITEMS", "varplayerbit_5542 (unrelated)", "-> 11556 desktop_shiftclickdrop_enabled (same bits as 211's 5542)"),
    ("12377", "SHOW_MOUSEOVER_TEXT", "aerial_fishing_whole_fish_toggle", "-> 10035 mouseover_text_disabled"),
    ("9638 / 9639", "settings search left/right", "blast_furnace_visited / chromium_crafting_unlocked", "-> 16073 floater_is_searching / 16074 floater_search_listen_for_keyboard"),
    ("5983", "DIALOGUE_FULL_SIZE, cleared on stopAll", "settings_barbarian_potion_makex", "-> 10670 chatmodal_unclamp (varp 1021 was reorganised; inferred from the name)"),
    ("9666", "MUSIC_VOLUME_MUTED (saved music volume)", "mourning_teleport_destination_toggle", "TODO: 241 stores it as option_music_saved (12426)? not changed"),
    ("4371 / 4372", "skill guide skill / subsection", "option_level_up_popup_list / unnamed", "TODO: the 241 skill guide is skill_guide_v2 (varp 965); not in the core list"),
    ("5362 / 6251", "Bigger and Badder / Stop the Wyvern rewards", "unnamed / poison_type", "TODO: 241 has slayer_toggleoff_superiormobs (14825) / slayer_toggleoff_fossilwyvernblock (14824) on the old bits"),
    ("12233", "MUSIC_AREA_MODE", "music_area_mode (now 2 bits)", "kept"),
    ("6254", "MINIMIZE_MINIMAP", "osm_minimap_toggle", "kept"),
]
for r in vrows:
    w("| %s | %s | %s | %s |" % r)
w("")
w("Attack style (varp 43, varbit 357), run (varp 173), special attack (varps 300/301), autocast (varbits 276/2668),")
w("quick prayers, bank settings (varbits 3958/3959/3755/5102), XP drop and orb settings keep their ids and meaning.")
w("")

w("## Not done / approximated")
w("")
for t in [
    "Settings side tab: the client layout dropdown (211 component 84) became a generic 'dynamic setting' dropdown (241 display_dynamic_setting_1, 40); not rebound. The music-unlock-message toggle (211: 71) moved to the settings interface.",
    "Music: the jukebox slot order in 241 comes from the music dbtable (enum 812 is empty in 241), so slot -> song can be wrong; there is no loop button.",
    "Advanced settings (134): component ids are remapped, but the setting structs/categories (enums 422/423, structs) changed in 241 and were not checked.",
    "Spellbook filters: the filter menu slots (0..6) were not re-verified against 241's dynamic menu.",
    "Mobile pane (601) was restructured; the mapping is enum-anchored but mobile was not otherwise checked.",
    "Polls (scripts 603-627), POH costumes (417), kill counts (1584): those script ids now belong to other scripts in 241; the callers were not changed.",
    "Trade/price checker/looting bag/GE history scripts take packed longs in 241 (see the NET note); call sites unchanged.",
    "Welcome screen/lobby (378) is unused by the server and was not changed.",
    "Everything outside the core list below keeps 211 ids; use the table to port it.",
]:
    w("- " + t)
w("")

w("## Interface table")
w("")
w("Per interface: component counts, confidence totals, then every row whose id changes or is not high")
w("confidence (rows not listed are unchanged, high confidence). `-1` = no 241 counterpart. Core interfaces first.")
w("")
order = [str(i) for i in CORE if str(i) in I] + [i for i in sorted(I, key=int) if int(i) not in CORE]
for i in order:
    r = I[i]
    s = summ.get(i, {})
    if "rows" not in r:
        continue
    g = "/".join(gnames.get(int(i), []))
    w("### %s %s%s" % (i, r["name241"], (" (" + g + ")") if g else ""))
    w("")
    w("211: %d components, 241: %d. Moved: %d. High %d, medium %d, low %d." % (r["n211"], r["n241"], s.get("moved", 0),
                                                                              s.get("high", 0), s.get("medium", 0), s.get("low", 0)))
    lines = [row for row in r["rows"] if row[0] != row[1] or row[2] != "high"]
    if lines:
        w("")
        w("| 211 | 241 | conf | 241 name | notes |")
        w("|---|---|---|---|---|")
        for row in lines:
            w("| %d | %d | %s | %s | %s |" % (row[0], row[1], row[2], row[4], ",".join(x for x in row[5] if x)))
    if r.get("added241"):
        w("")
        w("New in 241: " + ", ".join("%d %s" % (c, n) for c, n in r["added241"]))
    w("")

open(os.path.join(SERVER, "docs", "rev241-interfaces.md"), "w").write("\n".join(L) + "\n")
print("lines", len(L))

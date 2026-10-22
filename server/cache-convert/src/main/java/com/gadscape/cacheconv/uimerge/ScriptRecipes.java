package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.convert.ScriptConv;
import com.gadscape.cacheconv.convert.ScriptTrailer;
import com.gadscape.cacheconv.core.Arch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The 31 user-modified vanilla clientscripts: per id, whether the user's edit is re-applied onto BASE's body (a
 * recipe of anchored edits), USER's version is taken (BASE kept the 211 script, so only the user changed it), or
 * BASE's is kept (with the reason). The recipes name their anchors by content so a changed BASE makes the generator
 * fail instead of patching the wrong place.
 */
public final class ScriptRecipes {
    private ScriptRecipes() {}

    enum D { USER, BASE, MERGE }

    /** What a recipe works with: the generator, USER/VAN211 211 scripts, notes for NOTES.md. */
    static final class In {
        final UiMergeGen g;
        final UserCode uc;
        final int id;
        final Cs2.Script user, van;
        final List<String> notes = new ArrayList<>();
        In(UiMergeGen g, UserCode uc, int id) {
            this.g = g; this.uc = uc; this.id = id;
            this.user = Cs2.parse211(g.user.single(Arch.SCRIPTS, id));
            this.van = Cs2.parse211(g.van.single(Arch.SCRIPTS, id));
        }
        /** Copies USER instructions [from, to) (211 indices). */
        List<Cs2.Ins> user(ScriptEdit e, int from, int to) { return uc.snippet(e, id, from, to); }
    }

    interface Recipe { void apply(ScriptEdit e, In in); }

    record Spec(int id, D d, String what, String reason, Recipe recipe) {}

    static TreeMap<Integer, Spec> specs() {
        TreeMap<Integer, Spec> m = new TreeMap<>();
        // ---- USER: BASE kept the 211 script byte for byte (after the 211 -> 241 conversion): only GadScape changed it
        user(m, 205, "duel_initworn: GadScape's version fills its own duel screen 2000 (equipment slots)");
        user(m, 336, "godwars_hideshow: the GWD kill-count overlay shows when the server sets varp 3625 (GadScape's GWD instances) instead of checking coordinates and varbit 11709");
        user(m, 342, "godwars_darkness: darkness levels driven by varp 3625 (GWD instances)");
        user(m, 1261, "deadman_tournament_fogcolour: GadScape's tournament fog (other components and colours when varbit 1497 = 2)");
        user(m, 1311, "tog_sidepanel_timer: reads varp 262 (GadScape's Tears of Guthix timer) instead of varp 101");
        user(m, 2066, "chat_broadcast_parseurl: GadScape's broadcast links");
        user(m, 2186, "spectator_keypress: GadScape's tournament spectator keys");
        user(m, 2200, "diary_completion_info: GadScape's achievement diary sizes");
        user(m, 4073, "summary_diary_total: GadScape's diary totals");
        // ---- BASE: the 241 script replaces the user's for a reason given
        base(m, 37, "scrollbar_vertical_doscroll", "USER's version is an older vanilla revision (no saved scroll positions for varcs 1082-1084, horizontal scroll reset); no GadScape feature in it");
        base(m, 687, "lunar_contact_npc_draw (211: ironman setup)", "211's ironman-setup proc for interface 215; 241 reuses the id for lunar_contact_npc_draw and GadScape's server opens its own setup screen 1606, so nothing calls the user's version");
        base(m, 2094, "lunar_contact_npc_draw (211: ironman setup option)", "part of the dead 215 ironman setup (called only by 687); 241 reuses the id with another signature");
        base(m, 2096, "hotkey_settings_new_resize (211: ironman setup)", "part of the dead 215 ironman setup; 241 reuses the id");
        base(m, 1448, "2d_intarray_num_cols", "USER's 1448 is an old duel-arena script (14 args) nothing calls: its only callers are the vanilla procs 1441/1442, which call 1448 as 2d_intarray_num_cols with one argument (broken on 211). 241's version is kept, which also repairs 1441/1442");
        base(m, 4104, "emote_set_graphic", "USER's version is an older revision without the Relic-unlock (emote 50) variants; GadScape's emotes take their graphics from enum 1001, which BASE's version reads the same way");
        String slayer = "241 draws slayer rewards from dbtable 117 (db_find/db_getfield) instead of the 211 enum 834 index range; GadScape's edit only raised the "
                + "211 index limit 52 -> 53 for one extra reward (plus decompiler artefacts and old hook descriptors). On 241 that reward is a row of table 117 "
                + "(step db-slayer-rewards: 'Slayer Statues', reward id 95), not a script change";
        base(m, 414, "slayer_rewards_unlock_confirm", slayer);
        base(m, 423, "slayer_rewards_tasks_canceltask", slayer);
        base(m, 426, "slayer_rewards_tasks_blocked_draw", slayer);
        base(m, 1090, "slayer_rewards_unlock_draw", slayer);
        base(m, 1092, "slayer_rewards_etcetera_draw", slayer);
        // ---- MERGE
        merge(m, 912, "toplevel_sidebuttons_enable", "GadScape calls custom script 10593 ('Previous teleport' label on the spellbook side stone, op 3)", ScriptRecipes::r912);
        merge(m, 1074, "shop_main_init", "GadScape adds op 7 'Buy X' to shop items", ScriptRecipes::r1074);
        merge(m, 1705, "worldmap_intermap_op", "GadScape moved three world-map link destinations (Asgarnian Ice Dungeon both ways, Edgeville)", ScriptRecipes::r1705);
        merge(m, 393, "stats_init", "GadScape's construction skill (stat 22) block: two greyed graphics (sprites 174, 176) and an early return", ScriptRecipes::r393);
        String emotes = "GadScape's six emotes 52-57 (Trick, Give Thanks, Snowman Dance, Freeze, Dramatic Point, Around the World in Eggty Days) with their own "
                + "unlock vars; the config merge keeps USER's emote enums 1000-1002 for keys 52-57 (USER wins over 241's Fortis Salute / Sit down / Crab dance), so the "
                + "emote list runs 0-57";
        merge(m, 699, "emote_init", emotes, ScriptRecipes::r699);
        merge(m, 701, "emote_update", emotes, ScriptRecipes::r701);
        merge(m, 702, "emote_checkunlocked", emotes, ScriptRecipes::r702);
        merge(m, 2735, "collection_category_count", "GadScape's kill counts: varp 2048 (the count the server sends for the open category) for Callisto, Venenatis and Vet'ion, "
                + "and three custom categories (structs 10300 Ganodermic Beast, 10321 Rise of the Six, 10322 Vanstrom Klause)", ScriptRecipes::r2735);
        merge(m, 6138, "pet_insurance_draw_pets", "GadScape shows each pet's item on its slot (cc_setobject); its pet count edit (51 -> 61) is covered by BASE's 71", ScriptRecipes::r6138);
        merge(m, 3310, "summary_sidepanel_draw", "GadScape's 'Completionist overview' entry (sprite 10037, varbits 6347/11877, onOp hook 10707) in the character summary", ScriptRecipes::r3310);
        merge(m, 3178, "side_journal_additional", JOURNAL, ScriptRecipes::r3178);
        return m;
    }

    private static void user(TreeMap<Integer, Spec> m, int id, String what) { m.put(id, new Spec(id, D.USER, what, null, null)); }
    private static void base(TreeMap<Integer, Spec> m, int id, String what, String reason) { m.put(id, new Spec(id, D.BASE, what, reason, null)); }
    private static void merge(TreeMap<Integer, Spec> m, int id, String what, String reason, Recipe r) { m.put(id, new Spec(id, D.MERGE, what, reason, r)); }

    // ------------------------------------------------------------------ recipes

    /** 912: USER's first two instructions are "iload 0; gosub 10593". BASE gets the call before its only return. */
    static void r912(ScriptEdit e, In in) {
        expect(in, 0, "33:0", "40:10593");
        int ret = e.find(0, "21:0");
        e.insertBefore(ret, in.user(e, 0, 2), "iload 0; gosub 10593 (set the side stone's op 3 to 'Previous teleport: <name>'). Placed before the return instead of first: "
                + "10593 throws for the fullscreen world map pane (TELE-05 / RES-4), which on 211 aborted the rest of 912");
    }

    /** 1074: "Buy X" as op 7 after "Buy 50", before "Examine" (BASE made Buy 50 conditional; Buy X stays unconditional). */
    static void r1074(ScriptEdit e, In in) {
        int k = find211(in.user, "0:7", "3:\"Buy X", "1300:0");
        int at = e.find(0, "0:10", "3:\"Examine", "1300:0");
        e.insertBefore(at, in.user(e, k, k + 3), "cc_setop(7, \"Buy X\")");
    }

    /** 1705: three link destinations (coord constants passed to proc 1706). */
    static void r1705(ScriptEdit e, In in) {
        int[][] moves = {{49276238, 49292622}, {49269838, 49286222}, {50744716, 50810271}};
        for (int[] mv : moves) {
            int u = find211(in.user, "0:" + mv[1], "40:1706");
            if (find211(in.van, "0:" + mv[0], "40:1706") < 0 || u < 0) throw new IllegalStateException("1705: edit " + mv[0] + " -> " + mv[1] + " not found in VAN211/USER");
            int at = e.find(0, "0:" + mv[0], "40:1706");
            e.operand(at, mv[1], "coord", "destination " + coord(mv[0]) + " -> " + coord(mv[1]));
        }
    }

    /** 393: USER's block right after "gosub 395" (stat 22: two graphics, return). */
    static void r393(ScriptEdit e, In in) {
        int k = find211(in.user, "40:395", "33:2", "0:22");
        int end = k + 1;
        while (!(in.user.code.get(end).op == Cs2.RETURN)) end++;
        int at = e.find(0, "40:395", "33:4", "40:8950");
        e.insertAfter(at, in.user(e, k + 1, end + 1), "if (stat == 22) { two cc_create graphics 174 / 176 at transparency 90; return } (USER " + (k + 1) + ".." + end + ")");
    }

    /** 699: emote count 55 -> 57 (both loops and the second-row child offset) and the user's var triggers. */
    static void r699(ScriptEdit e, In in) {
        int max = emoteMax(in);
        List<Integer> at = e.findAll("0:55");
        if (!at.equals(List.of(16, 93, 98))) throw new IllegalStateException("699: BASE's emote count constants moved: " + at);
        for (int a : at) e.operand(a, max, null, "last emote index 55 -> " + max);
        int u = find211(in.user, "0:2886", "0:3620", "0:3711", "0:3702", "0:10", "3:\"iiY");
        if (u < 0) throw new IllegalStateException("699: USER's trigger list not found");
        int t = e.find(0, "0:2886", "0:4130", "0:8", "3:\"iiY");
        e.insertAfter(t + 1, in.user(e, u + 1, u + 4), "var triggers 3620, 3711, 3702 (GadScape's emote unlock vars)");
        e.operand(t + 5, 11, null, "trigger count 8 -> 11");
    }

    /** 701: emote count 55 -> 57 (loop bound and the graphic child offset). */
    static void r701(ScriptEdit e, In in) {
        int max = emoteMax(in);
        List<Integer> at = e.findAll("0:55");
        if (!at.equals(List.of(3, 34))) throw new IllegalStateException("701: BASE's emote count constants moved: " + at);
        for (int a : at) e.operand(a, max, null, "last emote index 55 -> " + max);
    }

    /** 702: unlock checks of emotes 52-57 from USER (each "get var; return"), appended and switched to. */
    static void r702(ScriptEdit e, In in) {
        int usw = find211(in.user, "60:0");
        int sw = e.find(0, "60:0");
        Cs2.Ins last = e.s().code.get(e.s().code.size() - 1);
        if (last.op != Cs2.RETURN) throw new IllegalStateException("702: BASE does not end with a return");
        for (int key = 52; key <= emoteMax(in); key++) {
            Integer off = in.user.switches.get(in.user.code.get(usw).i).get(key);
            if (off == null) throw new IllegalStateException("702: USER has no case " + key);
            int t = usw + 1 + off;
            if (in.user.code.get(t + 1).op != Cs2.RETURN) throw new IllegalStateException("702: USER case " + key + " is not 'get var; return'");
            int at = e.append(in.user(e, t, t + 2), "unlock check of emote " + key + " (" + CsText.name(in.user.code.get(t).op) + " " + in.user.code.get(t).i + ")");
            e.switchCase(sw, key, at, "emote " + key + " (GadScape)");
        }
    }

    /** The last emote index of GadScape's emote_init (USER 699's loop bound). */
    static int emoteMax(In in) {
        Cs2.Script u699 = Cs2.parse211(in.g.user.single(Arch.SCRIPTS, 699));
        if (find211(u699, "0:57", "31:1") < 0) throw new IllegalStateException("USER 699's emote count 57 not found");
        return 57;
    }

    /** 2735: varp 2941 -> 2048 for the three wilderness bosses, three custom categories appended (4 strings, as 241 returns). */
    static void r2735(ScriptEdit e, In in) {
        for (String boss : new String[]{"Callisto", "Venenatis", "Vet'ion"}) {
            int at = e.find(1, "3:\"" + boss + " kills: <col=ffffff>", "1:2941");
            e.operand(at, 2048, "varp", boss + " kills read varp 2048 like the other categories (USER: 2941 -> 2048)");
        }
        int usw = find211(in.user, "60:0");
        int sw = e.find(0, "60:0");
        if (e.s().code.get(e.s().code.size() - 1).op != Cs2.RETURN) throw new IllegalStateException("2735: BASE does not end with a return");
        for (int key : new int[]{10300, 10321, 10322}) {
            Integer off = in.user.switches.get(in.user.code.get(usw).i).get(key);
            if (off == null) throw new IllegalStateException("2735: USER has no case " + key);
            int t = usw + 1 + off;
            int r = t;
            while (in.user.code.get(r).op != Cs2.RETURN) r++;
            List<Cs2.Ins> block = in.user(e, t, r);
            block.add(e.str(""));
            block.add(e.u(Cs2.RETURN, 0));
            int at = e.append(block, "category struct " + key + " \"" + in.user.code.get(t).s.trim() + "\" (USER's 3 strings + one \"\": 241 returns 4)");
            e.switchCase(sw, key, "struct", at, "custom category " + key);
        }
    }

    /** 6138: "iload <pet obj>; iconst 1; cc_setobject" after the Examine op; the pet obj local is BASE's own. */
    static void r6138(ScriptEdit e, In in) {
        int u = find211(in.user, "3:\"Examine", "1300:0", "33:19", "0:1", "1200:0");
        int uo = find211(in.user, "0:105", "0:79", "0:985", "33:16", "3408:0", "34:19");
        if (u < 0 || uo < 0) throw new IllegalStateException("6138: USER's edit not found");
        int bo = e.find(5, "0:105", "0:79", "0:985", "33:20", "3408:0", "34:*");
        int local = e.s().code.get(bo).i;
        int at = e.find(1, "3:\"Examine", "1300:0");
        e.insertAfter(at, e.L(e.u(Cs2.ILOAD, local), e.u(Cs2.ICONST, 1), e.u(1200, 0)), "cc_setobject(pet obj, 1): USER's local 19 is BASE's local " + local + " (enum 985 lookup)");
        int b = e.find(1, "33:20", "0:71", "31:1");
        e.note("pet loop bound: BASE " + e.s().code.get(b).i + " >= USER 61, kept");
    }

    /** 3310: USER's completionist entry before the achievement diary entry (same locals and 3950 call shape as BASE's entries). */
    static void r3310(ScriptEdit e, In in) {
        int from = find211(in.user, "0:52", "34:7", "33:0", "33:1", "33:5", "33:4", "33:6", "33:7", "3:\"Complestionist progress:");
        int to = find211(in.user, "0:10707");
        while (in.user.code.get(to).op != 1407) to++;
        int at = e.find(0, "33:15", "33:16", "8:*", "6:*", "33:0", "33:1", "33:5", "33:4", "33:6", "33:7", "3:\"Achievements Completed:");
        e.insertBefore(at, in.user(e, from, to + 1), "completionist entry (USER " + from + ".." + to + "): gosub 3950 with BASE's local layout, then its onOp hook 10707");
    }

    static final String JOURNAL = "GadScape's journal tab bar: Character Summary, Achievement Diaries, 'Events' (the server's Server Events) and "
            + "'Game Noticeboard', no quest list. On 211 it sat on the Kourend Favour tab (noticeboard, tab value 3) and the Leagues tab (events, value 5); "
            + "241 removed the Kourend tab and put Adventure Paths (value 3) in its slot, so the noticeboard takes the Adventure Paths tab 629:26 and the "
            + "events the Leagues tab 629:34 (value 4), laid out like 241's own four-tab bar";

    /**
     * 3178: GadScape's tab bar replaces BASE's default branch (the normal world type; BASE's Leagues / Deadman / Adventure Paths
     * world branches stay). USER's 211 version (33 arguments, hard-coded 629:18 and 629:32) is re-done against 241's 44 arguments:
     * arg 2 quest list (hidden), 3 diaries, 4 summary, 5 Adventure Paths tab (-> Game Noticeboard), 6 Leagues tab (-> Events),
     * 23 the Leagues tab's icon (USER's 629:32: graphic 2272). The hover and op hooks of the two re-used tabs are BASE's own
     * blocks for those tabs (2803/2804 with their 241 sprite arguments, 2798 with tab values 3 and 4). Varbit 8168 = 5 (GadScape's
     * 211 value for Server Events, still sent by the server) is read as 4, the Leagues slot 241's 2800 highlights. Then BASE's
     * shared tail sizes the tabs and draws them.
     */
    static void r3178(ScriptEdit e, In in) {
        // USER's 211 edit as anchors: the tab ops it sets and the Events graphic
        if (find211(in.user, "0:1", "3:\"Events", "33:7", "2300:0") < 0 || find211(in.user, "0:2272", "0:" + ((629 << 16) | 32), "2105:0") < 0)
            throw new IllegalStateException("3178: USER's Events tab edit not found");
        int def = e.find(0, "33:5", "2307:0", "0:1", "33:5", "2003:0", "33:6", "2307:0", "0:1", "33:6", "2003:0", "25:8168");
        int tail = e.find(0, "33:44", "0:0", "0:0", "0:1", "33:2", "2001:0");
        if (tail <= def) throw new IllegalStateException("3178: BASE's shared tail is not after its default branch");
        int apHover = e.find(0, "40:1972", "0:0", "8:*", "6:*", "0:2803", "33:20", "33:21", "33:22", "33:37", "33:38", "33:39", "0:3");
        int apOp = e.find(0, "0:2798", "0:-2147483644", "0:3", "33:1");
        int lgHover = e.find(0, "40:1972", "0:0", "8:*", "6:*", "0:2803", "33:24", "33:25", "33:26", "33:40", "33:41", "33:42", "0:4");
        int lgOp = e.find(0, "0:2798", "0:-2147483644", "0:4", "33:1");
        List<Cs2.Ins> b = new ArrayList<>();
        // the quest list is not part of GadScape's journal (USER hid 629:8; the interface merge hides 629:10 too)
        b.addAll(e.L(e.u(Cs2.ILOAD, 2), e.u(2307, 0), e.u(Cs2.ICONST, 1), e.u(Cs2.ILOAD, 2), e.u(2003, 0)));
        // varbit 8168 = 5 (211's Leagues slot, GadScape's Server Events) -> 4 (241's Leagues slot)
        b.addAll(e.L(e.u(25, 8168), e.u(Cs2.ICONST, 5), e.u(8, 1), e.u(Cs2.JUMP, 2), e.u(Cs2.ICONST, 4), e.u(27, 8168)));
        // Events on the Leagues tab (arg 6): op, icon, BASE's hover/op hooks for that tab (value 4), shown
        b.addAll(e.L(e.u(Cs2.ILOAD, 6), e.u(2307, 0), e.u(Cs2.ICONST, 1), e.str("Events"), e.u(Cs2.ILOAD, 6), e.u(2300, 0),
                e.u(Cs2.ICONST, 2272, "graphic"), e.u(Cs2.ILOAD, 23), e.u(2105, 0)));
        b.addAll(copyBlock(e, lgHover, 2404));
        b.addAll(copyBlock(e, lgOp, 2409));
        b.addAll(e.L(e.u(Cs2.ICONST, 0), e.u(Cs2.ILOAD, 6), e.u(2003, 0)));
        // Game Noticeboard on the Adventure Paths tab (arg 5): op, BASE's hover/op hooks for that tab (value 3), shown; its icon
        // (sprite 4524) comes from the interface merge (USER's 629:22 edit on 629:33)
        b.addAll(e.L(e.u(Cs2.ILOAD, 5), e.u(2307, 0), e.u(Cs2.ICONST, 1), e.str("Game Noticeboard"), e.u(Cs2.ILOAD, 5), e.u(2300, 0)));
        b.addAll(copyBlock(e, apHover, 2404));
        b.addAll(copyBlock(e, apOp, 2409));
        b.addAll(e.L(e.u(Cs2.ICONST, 0), e.u(Cs2.ILOAD, 5), e.u(2003, 0)));
        // four tabs of 44 px, centred like 241's own four-tab bar, in GadScape's order: summary, diaries, events, noticeboard
        b.addAll(e.L(e.u(Cs2.ICONST, 44), e.u(34, 44)));
        int[][] pos = {{-69, 4}, {-23, 3}, {23, 6}, {69, 5}};
        for (int[] p : pos) b.addAll(e.L(e.u(Cs2.ICONST, p[0]), e.u(Cs2.ICONST, 0), e.u(Cs2.ICONST, 1), e.u(Cs2.ICONST, 2), e.u(Cs2.ILOAD, p[1]), e.u(2000, 0)));
        e.insertBefore(def, b, "GadScape's journal tab bar on the normal world branch (" + b.size() + " instructions; USER's 211 layout on 241's arguments, "
                + "hooks copied from BASE's Leagues and Adventure Paths branches); it falls through into BASE's shared tail");
        int newTail = tail + b.size(), afterBlock = def + b.size();
        e.remove(afterBlock, newTail, "BASE's default-branch layout (quest list, three tabs of 58 px; Adventure Paths and Leagues tabs hidden), replaced by GadScape's");
        if (!ScriptEdit.matches(e.s().code.get(afterBlock), "33:44") || !ScriptEdit.matches(e.s().code.get(afterBlock + 5), "2001:0"))
            throw new IllegalStateException("3178: GadScape's block does not fall through into BASE's tail");
    }

    /**
     * Copies of BASE's instructions from {@code at} through the next instruction with opcode {@code lastOp} (a hook setter), as
     * GadScape lines. Relative jumps inside the block must stay inside it or land right after it.
     */
    static List<Cs2.Ins> copyBlock(ScriptEdit e, int at, int lastOp) {
        List<Cs2.Ins> code = e.s().code;
        int end = at;
        while (end < code.size() && code.get(end).op != lastOp) end++;
        if (end == code.size()) throw new IllegalStateException("script " + e.doc.id + ": no opcode " + lastOp + " after " + at);
        List<Cs2.Ins> r = new ArrayList<>();
        for (int k = at; k <= end; k++) {
            Cs2.Ins x = code.get(k);
            if (Cs2.BRANCH.contains(x.op) && (k + 1 + x.i < at || k + 1 + x.i > end + 1)) throw new IllegalStateException("script " + e.doc.id + ": jump at " + k + " leaves the copied block " + at + ".." + end);
            if (x.op == Cs2.SWITCH) throw new IllegalStateException("script " + e.doc.id + ": switch in the copied block " + at + ".." + end);
            r.add(x.op == Cs2.SCONST ? e.str(x.s) : e.u(x.op, x.i, x.op == Cs2.ICONST ? null : RefKinds.implied(x.op)));
        }
        return r;
    }

    // ------------------------------------------------------------------ helpers

    static String coord(int c) { return ((c >>> 14) & 0x3fff) + "," + (c & 0x3fff) + "," + (c >>> 28); }

    static void expect(In in, int at, String... keys) {
        for (int k = 0; k < keys.length; k++)
            if (!ScriptEdit.matches(in.user.code.get(at + k), keys[k])) throw new IllegalStateException("script " + in.id + ": USER instruction " + (at + k) + " is not " + keys[k]);
    }

    /** Index of the only match of the pattern in a 211 script (-1 if none; throws when ambiguous). */
    static int find211(Cs2.Script s, String... pattern) {
        int found = -1;
        for (int i = 0; i + pattern.length <= s.code.size(); i++) {
            boolean ok = true;
            for (int k = 0; k < pattern.length && ok; k++) ok = ScriptEdit.matches(s.code.get(i + k), pattern[k]);
            if (!ok) continue;
            if (found >= 0) throw new IllegalStateException("pattern " + String.join(" ", pattern) + " is ambiguous");
            found = i;
        }
        return found;
    }

    // ------------------------------------------------------------------ driver

    static void run(UiMergeGen g) {
        UserCode uc = new UserCode(g);
        TreeMap<Integer, Spec> specs = specs();
        for (int id : UiMergeSpecs.SCRIPTS) if (!specs.containsKey(id)) g.problems.add("script " + id + " has no merge spec");
        for (Spec sp : specs.values()) {
            int id = sp.id();
            byte[] bb = g.base.single(Arch.SCRIPTS, id);
            String sha = MergeData.sha(g.base.group(Arch.SCRIPTS, id));
            StringBuilder n = g.notesCs;
            In in = new In(g, uc, id);
            n.append("\n### Script ").append(id).append(" (").append(sp.what()).append(")\n\n");
            n.append("VAN211 ").append(in.van.code.size()).append(", USER ").append(in.user.code.size()).append(", BASE ").append(bb == null ? "-" : Cs2.parse241(bb).code.size()).append(" instructions. ");
            boolean baseIsVan = baseIsVan(g, id);
            n.append(baseIsVan ? "BASE == VAN211 (converted). " : "BASE changed the script since 211. ");
            try {
                switch (sp.d()) {
                    case BASE -> {
                        n.append("**base**: ").append(sp.reason()).append(".\n");
                        g.manifest.add(String.join("\t", "script", "" + id, "base", "-", sha, sp.what() + ": " + sp.reason()));
                    }
                    case USER -> {
                        if (!baseIsVan) throw new IllegalStateException("decision USER but BASE changed the script since 211");
                        CsText.Doc d = uc.whole(id, in.notes);
                        byte[] b = Cs2.write241(d.s);
                        ScriptTrailer.verify241(id, b);
                        n.append("**user**: ").append(sp.what()).append(". BASE did not change the script, so USER's version is taken (converted; ids it names follow the id map).\n");
                        write(g, id, d, "user", sha, sp.what());
                    }
                    case MERGE -> {
                        ScriptEdit e = new ScriptEdit(id, Cs2.parse241(bb));
                        sp.recipe().apply(e, in);
                        byte[] b = e.bytes();
                        ScriptTrailer.verify241(id, b);
                        if (!Arrays.equals(Cs2.write241(Cs2.parse241(b)), b)) throw new IllegalStateException("merged script does not round-trip");
                        n.append("**merged**: ").append(sp.reason()).append(".\n\nRe-applied onto BASE:\n");
                        for (String l : e.log()) n.append("- ").append(l).append('\n');
                        write(g, id, e.doc, "merged", sha, sp.what() + ": " + sp.reason());
                    }
                }
                for (String x : in.notes) n.append("- ").append(x).append('\n');
            } catch (RuntimeException ex) {
                g.problems.add("script " + id + ": " + ex.getMessage());
                n.append("**FAILED**: ").append(ex.getMessage()).append('\n');
            }
        }
    }

    static void write(UiMergeGen g, int id, CsText.Doc d, String decision, String sha, String note) {
        String file = "scripts/" + id + ".cs2";
        StringBuilder sb = new StringBuilder();
        sb.append("# script ").append(id).append(": ").append(decision).append(" (see NOTES.md); generated by UiMergeGen. Lines marked u are GadScape's;\n");
        sb.append("# their @kind operands follow the id map at build time.\n");
        sb.append(CsText.write(d));
        g.files.put(file, sb.toString());
        g.manifest.add(String.join("\t", "script", "" + id, decision, file, sha, note));
    }

    /** Whether BASE's script equals VAN211's after the 211 -> 241 conversion. */
    static boolean baseIsVan(UiMergeGen g, int id) {
        byte[] v = g.van.single(Arch.SCRIPTS, id), b = g.base.single(Arch.SCRIPTS, id);
        if (v == null || b == null) return false;
        try {
            return Arrays.equals(ScriptConv.convertAll(Map.of(id, v)).scripts.get(id), b);
        } catch (RuntimeException e) {
            return false;
        }
    }
}

package com.gadscape.cacheconv.uimerge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

/**
 * The hand-made decisions of the UI merge, one per user-modified vanilla interface and clientscript (spec policy 8).
 * Each says how the user's edit is carried onto BASE and why; {@link UiMergeGen} turns them into the data the build
 * step writes. The reasoning per id is in resources/ui-merge/NOTES.md.
 */
public final class UiMergeSpecs {
    private UiMergeSpecs() {}

    public enum Decision {
        /** Re-apply the user's edit onto BASE's version. */
        MERGE,
        /** Take USER's version (BASE did not change the id since 211, so only the user's side changed). */
        USER,
        /** Keep BASE's version: nothing of the user's edit is needed on 241 (reason given). */
        BASE
    }

    /**
     * {@code remapOverride}: VAN211 -> BASE component correspondences that correct the docs table (used by the merge and
     * the component remap TSV). {@code retarget}: where an edit goes when that is not the corresponding component
     * (merge only).
     */
    public record IfSpec(int id, Decision decision, IfMerge.Options options, TreeMap<Integer, Integer> remapOverride, TreeMap<Integer, Integer> retarget, String reason) {
        IfSpec(int id, Decision decision, IfMerge.Options options, TreeMap<Integer, Integer> remapOverride, String reason) { this(id, decision, options, remapOverride, null, reason); }
        /** 241 gave the id to a different interface: no 211 component has a counterpart. */
        public boolean idReused() { return id == 215; }
    }

    public static final int[] INTERFACES = {12, 121, 160, 161, 164, 192, 205, 215, 218, 465, 476, 548, 621, 629};
    public static final int[] SCRIPTS = {37, 205, 336, 342, 393, 414, 423, 426, 687, 699, 701, 702, 912, 1074, 1090, 1092, 1261, 1311, 1448,
            1705, 2066, 2094, 2096, 2186, 2200, 2735, 3178, 3310, 4073, 4104, 6138};

    public static TreeMap<Integer, IfSpec> interfaces() {
        TreeMap<Integer, IfSpec> m = new TreeMap<>();
        merge(m, 12, "bank: GadScape's 'Preset Manager' button (sprite 185 frame + custom sprite 10000 icon) is appended to BASE's bank");
        merge(m, 121, "keybinding: GadScape's 'Pre-EOC Layout' button (3 components) is appended; BASE's 121 is the 211 layout with two cosmetic changes");
        merge(m, 160, "orbs: the XP drops orb gets GadScape's third op 'XP Multiplier'");
        merge(m, 161, "resizable toplevel: the spellbook side stone gets a third op slot ('*', label set by custom script 10593: 'Previous teleport')");
        merge(m, 164, "resizable (side panels) toplevel: same third op on the spellbook side stone");
        merge(m, 548, "fixed toplevel: same third op on the spellbook side stone");
        merge(m, 192, "deposit box: the user's title edit ('Gielinor' -> 'GadScape') re-applied to BASE's new title: 'The Bank of GadScape - Item Deposits'");
        {
            IfMerge.Options o = new IfMerge.Options();
            o.userWins.add("0.width");
            LinkedHashMap<String, Object> hide = new LinkedHashMap<>();
            hide.put("hidden", 1);
            o.extra.put(14, hide);
            o.why.add("241 put a pronoun row (BASE 205:14) next to the body type choice; GadScape's edit removed that choice (skin colour only), so the row is hidden too");
            TreeMap<Integer, Integer> ov = new TreeMap<>();
            ov.put(0, 0);   // universe (table: unmatched; same root layer, 241 made it 500x330)
            ov.put(2, 2);   // 211 'Male' button -> 241 bodytype_a (same onLoad script 694)
            ov.put(6, 8);   // 211 'Female' button -> 241 bodytype_b
            m.put(205, new IfSpec(205, Decision.MERGE, o, ov, "makeover mage: GadScape made it a skin-colour-only screen (gender buttons hidden, narrower window, title)"));
        }
        m.put(215, new IfSpec(215, Decision.BASE, null, null,
                "211's 215 was the ironman setup screen; 241 reuses id 215 for ntk_scores (12 components). GadScape's server opens its own copy 1606 (GAME_MODE_SETUP) and nothing opens 215, so BASE's ntk_scores is kept"));
        {
            IfMerge.Options o = new IfMerge.Options();
            o.why.add("GadScape's 211 edit sits two components early: it put the ops on 211's 218:4 (Kourend home teleport), 218:99 (Ghorrock teleport) and "
                    + "218:143 (Spin Flax), while the home teleports are 218:6 / 101 / 145 there (spell objs, param 596). The server only reads these options on the "
                    + "home teleport spells (SpellbookTeleport: 'Lumbridge', 'Lunar Isle', 'Arceuus'), so they are put on 241's home teleports: "
                    + "teleport_home_standard 6, teleport_home_lunar 109, teleport_home_arceuus 153 (literally re-applied they would replace 241's "
                    + "'Yama's Lair' op of the league home teleport)");
            TreeMap<Integer, Integer> ov = new TreeMap<>();
            ov.put(4, 6);
            ov.put(99, 109);
            ov.put(143, 153);
            m.put(218, new IfSpec(218, Decision.MERGE, o, null, ov, "spellbook: GadScape's second op on the home teleports (Lumbridge / Lunar Isle / Arceuus destinations)"));
        }
        {
            IfMerge.Options o = new IfMerge.Options();
            o.align = IfMerge.Align.CONTENT;
            o.onlyAdded = true;
            o.unpaired.add(30);
            o.unpaired.add(31);
            o.why.add("USER's 465 is the pre-2021 GE layout (no fee components) plus two GadScape components; only those two are appended");
            m.put(465, new IfSpec(465, Decision.MERGE, o, null, "GE offers: GadScape's 'Exchange' / 'Offer Viewer' button and an extra layer are appended to BASE's 465"));
        }
        m.put(476, new IfSpec(476, Decision.USER, null, null,
                "GadScape replaced br_settings (6 components) with its own duel confirmation screen (84 components); BASE's 476 is byte-identical to 211's, so USER's version is taken"));
        merge(m, 621, "collection log: GadScape's 'Log Completion Rewards' panel (11 components) and two resized scroll layers");
        TreeMap<Integer, Integer> j = new TreeMap<>();
        // 211 -> 241 journal tabs by role (the docs table is off by one to three for 14-22): 241 added a backing sprite per tab
        int[][] tabs = {{14, 20}, {15, 22}, {16, 24}, {17, 25}, {18, -1}, {19, -1}, {20, -1}, {21, -1}, {22, -1},
                {23, 26}, {24, 28}, {25, 30}, {26, 32}, {27, 33}, {28, 34}, {29, 37}, {30, 39}, {31, 41}, {32, 42}, {33, 43}};
        for (int[] t : tabs) j.put(t[0], t[1]);
        m.put(629, new IfSpec(629, Decision.BASE, null, j,
                "not re-applied: GadScape's journal tab bar ('Game Noticeboard' on 211's Kourend Favour tab, 'Events' on the Leagues tab, quests and adventure paths hidden, "
                + "wider tabs) is laid out by script 3178, and 241 removed the Kourend tab and put Adventure Paths in its slot; the interface and scripts 2797/3178 have to be "
                + "ported together (NOTES.md, open issues). BASE's 629 is kept meanwhile"));
        return m;
    }

    private static void merge(TreeMap<Integer, IfSpec> m, int id, String reason) {
        m.put(id, new IfSpec(id, Decision.MERGE, new IfMerge.Options(), null, reason));
    }

    public static List<String> notesFor(IfSpec s) { return s.options() == null ? List.of() : s.options().why; }
}

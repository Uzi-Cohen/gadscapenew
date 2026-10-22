package com.zenyte.game.content.lootkeys;

/**
 * Loot key constants. OSRS values (oldschool.runescape.wiki/w/Loot_key, /w/Skully) unless noted.
 */
public class LootkeyConstants {
    /** wildy_loot_key0. Kept for callers that only know "the" loot key id. */
    public final static int LOOT_KEY_ITEM_ID = 26_651;

    /**
     * wildy_loot_key0..4 (objs 26651-26655, all named "Loot key" in the 211 and 241 caches). Key index i is the
     * obj LOOT_KEY_IDS[i]; the 241 loot chest (interface 742) shows one tab per index (enum 4212) backed by
     * inventory deadman_loot_inv{i} (enum 1243: inv 558 + i).
     */
    public final static int[] LOOT_KEY_IDS = {26_651, 26_652, 26_653, 26_654, 26_655};

    /** OSRS: a player can hold at most 5 loot keys. */
    public final static int MAX_KEYS = 5;

    /** OSRS: Skully charges 1,000,000 coins (5,000,000 before 9 March 2022). */
    public final static int UNLOCK_COST = 1_000_000;

    /** Default "valuables" threshold given when the enchantment is bought. */
    public final static int DEFAULT_THRESHOLD = 1_000_000;

    /** OSRS: in the Wilderness a key may only be destroyed if its loot is worth less than 1,000,000 (since Nov 2024). */
    public final static long WILDERNESS_DESTROY_LIMIT = 1_000_000L;

    /** OSRS: a loot key that lands on the floor (full inventory) is only visible to the killer and despawns after 2 minutes. */
    public final static int FLOOR_KEY_TICKS = 200;

    /** Each key holds at most one inventory's worth of stacks (inv 797 / deadman_loot_inv*: size 28). */
    public final static int KEY_CAPACITY = 28;

    public final static String LOOT_KEY_ITEM_LOOT_ITEMS_ATTR = "loot-key-loot-items";
    public final static String LOOT_KEY_ITEM_LOOT_VALUE_ATTR = "loot-key-loot-total-value";

    /* Interface 742 (wildy_loot_chest). */
    public final static int CHEST_INTERFACE = 742;
    /** 211 layout: single inventory loot_inv_access. */
    public final static int INV_LOOT_ACCESS = 797;
    /** 241 layout: deadman_loot_inv0..4. */
    public final static int INV_DEADMAN_LOOT_BASE = 558;
    /** varbits in varp 1299 (deadman_loot). */
    public final static int VARBIT_LOOT_TAB = 4842;
    public final static int VARBIT_WITHDRAW_NOTES = 4843;
    /** varbit wildy_loot_chest_has_loot (loc 43486 multiloc: 0 closed, 1 open). */
    public final static int VARBIT_CHEST_HAS_LOOT = 13651;
    /** 241 clientscript wildy_loot_value_update(int tab, string text). */
    public final static int SCRIPT_VALUE_UPDATE = 8013;

    public final static int SKULLY_NPC_ID = 10382;

    public static boolean isLootKey(final int itemId) {
        return itemId >= LOOT_KEY_IDS[0] && itemId <= LOOT_KEY_IDS[LOOT_KEY_IDS.length - 1];
    }

    public static int keyIndex(final int itemId) {
        return isLootKey(itemId) ? itemId - LOOT_KEY_IDS[0] : -1;
    }
}

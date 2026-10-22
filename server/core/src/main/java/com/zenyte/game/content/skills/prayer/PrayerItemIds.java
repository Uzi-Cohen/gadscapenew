package com.zenyte.game.content.skills.prayer;

/**
 * Stock OSRS (build 241, cache 2746) item ids used by the prayer code that are newer than
 * {@link com.zenyte.game.item.ItemId} (which stops at 30301). Stock ids are stable across GadScape cache rebuilds.
 */
public final class PrayerItemIds {

    private PrayerItemIds() {
    }

    /** Royal Titans reward: unlocks Deadeye (replaces Eagle Eye). Needs 62 Prayer to read. */
    public static final int DEADEYE_PRAYER_SCROLL = 30626;
    /** Royal Titans reward: unlocks Mystic Vigour (replaces Mystic Might). Needs 63 Prayer to read. */
    public static final int MYSTIC_VIGOUR_PRAYER_SCROLL = 30627;
    /** The Fractured Archive reward: taken to King Arthur in Camelot it unlocks Zeal (replaces Piety). */
    public static final int HORN_OF_BRAN = 34679;
    public static final int HORN_OF_BRAN_NOTED = 34680;
    /** Deadman/Leagues item: "Change Prayerbook" switches to the Ruinous Powers prayer book. */
    public static final int RUINOUS_POWERS = com.zenyte.game.item.ItemId.RUINOUS_POWERS;
}

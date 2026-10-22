package com.zenyte.game.content.skills.construction;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import mgi.types.config.ObjectDefinitions;

/**
 * House furniture objects whose ids OSRS reassigned between GadScape's 211 cache and the 241 (2746) cache it is being rebuilt
 * from. The furniture data keeps the 211 ids; when the loaded cache has the 241 definitions (13481 is no longer the crawling
 * hand), the 241 ids are spawned instead. Everything else in the house templates is unchanged (compared name by name).
 */
public final class PohIds {

    private static final Int2IntOpenHashMap REV_241 = new Int2IntOpenHashMap();
    private static volatile int mode = -1;

    static {
        REV_241.put(13481, 56113); // Crawling hand (241: trophy with tiers; the first tier)
        REV_241.put(13482, 56116); // Cockatrice head
        REV_241.put(13483, 56119); // Basilisk head
        REV_241.put(13484, 56122); // Kurask head
        REV_241.put(13485, 56124); // Abyssal demon head
        REV_241.put(13486, 56126); // KBD heads
        REV_241.put(13487, 56127); // KQ head
        REV_241.put(13488, 56133); // Mounted sea bass
        REV_241.put(13489, 56136); // Mounted swordfish
        REV_241.put(13490, 56138); // Mounted shark
        REV_241.put(31977, 56128); // Vorkath head
    }

    private PohIds() {
    }

    private static boolean rev241() {
        int value = mode;
        if (value == -1) {
            value = 0;
            try {
                final ObjectDefinitions hand = ObjectDefinitions.get(13481);
                final ObjectDefinitions moved = ObjectDefinitions.get(56113);
                if ((hand == null || !"Crawling hand".equalsIgnoreCase(hand.getName())) && moved != null && "Crawling hand".equalsIgnoreCase(moved.getName())) {
                    value = 1;
                }
            } catch (final Throwable ignored) {
                value = 0;
            }
            mode = value;
        }
        return value == 1;
    }

    /** The id to spawn for a furniture object id from the furniture data. */
    public static int resolve(final int id) {
        if (id <= 0 || !rev241()) {
            return id;
        }
        return REV_241.getOrDefault(id, id);
    }
}

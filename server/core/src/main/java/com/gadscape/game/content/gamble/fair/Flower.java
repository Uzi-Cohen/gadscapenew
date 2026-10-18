/*
 * Provably Fair Gaming Utilities for RSPS
 * Copyright (c) Roat Pkz - https://roatpkz.com
 *
 * Source & License: https://github.com/roatpkz/rsps-provably-fair
 *
 * This code is open source and may be reused or audited freely under the MIT License.
 */
package com.gadscape.game.content.gamble.fair;

public enum Flower {
    RED(0, 2462, 2981),
    BLUE(1, 2464, 2982),
    YELLOW(2, 2466, 2983),
    PURPLE(3, 2468, 2984),
    ORANGE(4, 2470, 2985),
    MIXED(5, 2472, 2986),
    ASSORTED(6, 2460, 2980),
    BLACK(7, 2476, 2988),
    WHITE(8, 2474, 2987);
  
    // The constructor
    Flower(int id, int itemId, int objectId) {
        this.id = id;
        this.itemId = itemId;
        this.objectId = objectId;
    }

    // The flower id
    private final int id;
    public int getId() { return this.id; }

    // The flower object id
    private final int objectId;
    public int getObjectId() { return this.objectId; }

    // The flower item id
    private final int itemId;
    public int getItemId() { return this.itemId; }
}

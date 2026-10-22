package com.zenyte.plugins.item;

import com.zenyte.game.item.ItemId;

/** Tome of Water pages (soaked pages). */
@SuppressWarnings("unused")
public final class TomeOfWater extends ElementalTomePages {
    @Override
    protected int tomeId() {
        return ItemId.TOME_OF_WATER;
    }

    @Override
    protected int emptyTomeId() {
        return ItemId.TOME_OF_WATER_EMPTY;
    }

    @Override
    protected int pageId() {
        return ItemId.SOAKED_PAGE;
    }

    @Override
    protected String tomeName() {
        return "Tome of Water";
    }
}

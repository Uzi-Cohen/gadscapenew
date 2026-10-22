package com.zenyte.plugins.item;

import com.zenyte.game.item.ItemId;

/** Tome of Earth pages (soiled pages). */
@SuppressWarnings("unused")
public final class TomeOfEarth extends ElementalTomePages {
    @Override
    protected int tomeId() {
        return ItemId.TOME_OF_EARTH;
    }

    @Override
    protected int emptyTomeId() {
        return ItemId.TOME_OF_EARTH_EMPTY;
    }

    @Override
    protected int pageId() {
        return ItemId.SOILED_PAGE;
    }

    @Override
    protected String tomeName() {
        return "Tome of Earth";
    }
}

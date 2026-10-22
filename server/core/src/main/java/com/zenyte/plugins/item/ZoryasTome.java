package com.zenyte.plugins.item;

import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.world.entity.player.action.combat.ArchiveWeapons;

/**
 * Zorya's tome charges (OSRS wiki, Zorya's tome): each charge costs 3 astral runes and 2 nature runes, one charge per
 * attack, up to 20,000 charges (60,000 astral + 40,000 nature runes). Uncharging works anywhere and gives every rune
 * back.
 */
@SuppressWarnings("unused")
public final class ZoryasTome extends ArchiveChargedWeapon {
    public static final int MAX_CHARGES = 20_000;

    @Override
    protected int chargedId() {
        return ArchiveWeapons.ZORYAS_TOME;
    }

    @Override
    protected int unchargedId() {
        return ArchiveWeapons.ZORYAS_TOME_UNCHARGED;
    }

    @Override
    protected int maxCharges() {
        return MAX_CHARGES;
    }

    @Override
    protected int chargesPerSet() {
        return 1;
    }

    @Override
    protected Item[] materialsPerSet() {
        return new Item[]{new Item(ItemId.ASTRAL_RUNE, 3), new Item(ItemId.NATURE_RUNE, 2)};
    }

    @Override
    protected int chargesVarbit() {
        return ArchiveWeapons.ZORYAS_TOME_CHARGES_VARBIT;
    }

    @Override
    protected String displayName() {
        return "tome";
    }
}

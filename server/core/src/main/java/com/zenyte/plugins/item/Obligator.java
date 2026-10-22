package com.zenyte.plugins.item;

import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.world.entity.player.action.combat.ArchiveWeapons;

/**
 * Obligator charges (wiki, 21 Oct 2026): 1 Ascension shard, 20 nature runes and 20 death runes per 10 charges; one
 * charge per attack ({@link com.zenyte.game.world.entity.player.action.combat.melee.ObligatorCombat}). Uncharging gives
 * the materials of the whole sets back. The capacity is unpublished: 20,000 like the Scythe of Vitur.
 */
@SuppressWarnings("unused")
public final class Obligator extends ArchiveChargedWeapon {
    public static final int MAX_CHARGES = 20_000;

    @Override
    protected int chargedId() {
        return ArchiveWeapons.OBLIGATOR;
    }

    @Override
    protected int unchargedId() {
        return ArchiveWeapons.OBLIGATOR_UNCHARGED;
    }

    @Override
    protected int maxCharges() {
        return MAX_CHARGES;
    }

    @Override
    protected int chargesPerSet() {
        return 10;
    }

    @Override
    protected Item[] materialsPerSet() {
        return new Item[]{new Item(ArchiveWeapons.ASCENSION_SHARDS, 1), new Item(ItemId.NATURE_RUNE, 20), new Item(ItemId.DEATH_RUNE, 20)};
    }

    @Override
    protected int chargesVarbit() {
        return ArchiveWeapons.OBLIGATOR_CHARGES_VARBIT;
    }

    @Override
    protected String displayName() {
        return "Obligator";
    }
}

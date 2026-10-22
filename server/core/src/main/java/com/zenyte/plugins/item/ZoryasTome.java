package com.zenyte.plugins.item;

import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.world.entity.player.action.combat.ArchiveWeapons;

/**
 * Zorya's tome charges. Jagex has not published what charges the tome (the Out Today post says Ascension shards charge
 * only the Obligator and the Archivist's folio), so this is a placeholder until the live cost is known: one chaos rune
 * and one death rune per charge, one charge per attack, up to 20,000 charges. Change {@link #materialsPerSet()} when
 * the real cost is published.
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
        return new Item[]{new Item(ItemId.CHAOS_RUNE, 1), new Item(ItemId.DEATH_RUNE, 1)};
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

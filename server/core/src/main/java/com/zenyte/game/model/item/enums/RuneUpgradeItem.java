package com.zenyte.game.model.item.enums;

import com.zenyte.game.item.Item;
import com.zenyte.game.world.entity.player.Player;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import mgi.types.config.items.ItemDefinitions;

/**
 * Cosmetic upgrades made with a kit that is kept (unlimited uses) and runes that are used up, as in OSRS:
 * Torva + Ancient blood ornament kit + 20,000 blood runes = Sanguine torva; Oathplate + Purifying sigil + 2,500 aether
 * runes = Radiant oathplate. "Revert" gives the plain piece back; the runes aren't returned.
 * Handled by ItemAttaching (kit on piece) and Dismantleable (Revert).
 */
public enum RuneUpgradeItem {
    SANGUINE_TORVA_FULL_HELM(26382, 28254, 28336, 565, 20_000),
    SANGUINE_TORVA_PLATEBODY(26384, 28256, 28336, 565, 20_000),
    SANGUINE_TORVA_PLATELEGS(26386, 28258, 28336, 565, 20_000),
    RADIANT_OATHPLATE_HELM(30750, 30777, 30793, 30843, 2_500),
    RADIANT_OATHPLATE_CHEST(30753, 30779, 30793, 30843, 2_500),
    RADIANT_OATHPLATE_LEGS(30756, 30781, 30793, 30843, 2_500),
    ;

    public static final RuneUpgradeItem[] VALUES = values();
    private static final Int2ObjectOpenHashMap<RuneUpgradeItem> BY_UPGRADED = new Int2ObjectOpenHashMap<>();

    static {
        for (final RuneUpgradeItem u : VALUES) BY_UPGRADED.put(u.upgraded, u);
    }

    private final int base, upgraded, kit, rune, runes;

    RuneUpgradeItem(final int base, final int upgraded, final int kit, final int rune, final int runes) {
        this.base = base;
        this.upgraded = upgraded;
        this.kit = kit;
        this.rune = rune;
        this.runes = runes;
    }

    public int getBase() { return base; }
    public int getUpgraded() { return upgraded; }
    public int getKit() { return kit; }

    /** The upgrade for using [a] on [b] (either way round), or null. */
    public static RuneUpgradeItem find(final int a, final int b) {
        for (final RuneUpgradeItem u : VALUES) {
            if (u.base == a && u.kit == b || u.base == b && u.kit == a) return u;
        }
        return null;
    }

    public static RuneUpgradeItem forUpgraded(final int id) {
        return BY_UPGRADED.get(id);
    }

    /** Upgrades the piece in [pieceSlot]: the kit stays, the runes are used. */
    public void upgrade(final Player player, final int pieceSlot) {
        final String runeName = ItemDefinitions.nameOfLowercase(rune);
        if (player.getInventory().getAmountOf(rune) < runes) {
            player.sendMessage("You need " + String.format("%,d", runes) + " " + runeName + "s to do that.");
            return;
        }
        player.getInventory().deleteItem(rune, runes);
        player.getInventory().replaceItem(upgraded, 1, pieceSlot);
        player.sendMessage("You use the " + ItemDefinitions.nameOfLowercase(kit) + " and " + String.format("%,d", runes) + " " + runeName
                + "s to make " + ItemDefinitions.nameOfLowercase(upgraded) + ".");
    }

    /** "Revert": the plain piece back; the runes are gone. */
    public void revert(final Player player, final Item item, final int slot) {
        player.getInventory().replaceItem(base, 1, slot);
        player.sendMessage("You revert the " + ItemDefinitions.nameOfLowercase(upgraded) + ". The runes are lost.");
    }
}

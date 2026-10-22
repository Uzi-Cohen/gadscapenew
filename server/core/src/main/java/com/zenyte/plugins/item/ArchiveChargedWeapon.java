package com.zenyte.plugins.item;

import com.zenyte.game.item.Item;
import com.zenyte.game.model.item.PairedItemOnItemPlugin;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.Container;
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import mgi.types.config.items.ItemDefinitions;

/**
 * Shared Check / Charge / Uncharge handling for the charged Fractured Archive weapons (Obligator, Zorya's tome). The
 * charges live on the item; the charged id becomes the uncharged id at 0 charges and the other way round when charged.
 * Charging uses whole "sets" of materials, each set adding {@link #chargesPerSet()} charges.
 */
public abstract class ArchiveChargedWeapon extends ItemPlugin implements PairedItemOnItemPlugin {

    protected abstract int chargedId();

    protected abstract int unchargedId();

    protected abstract int maxCharges();

    protected abstract int chargesPerSet();

    /** The materials for one set, as item ids and amounts. */
    protected abstract Item[] materialsPerSet();

    /** The client varbit that shows this weapon's charges (charges_*_quantity). */
    protected abstract int chargesVarbit();

    protected String displayName() {
        return ItemDefinitions.nameOf(chargedId());
    }

    @Override
    public void handle() {
        bind("Check", (player, item, container, slotId) -> check(player, item));
        bind("Charge", (player, item, container, slotId) -> charge(player, item, container, slotId));
        bind("Uncharge", (player, item, container, slotId) -> uncharge(player, item, container, slotId));
    }

    @Override
    public int[] getItems() {
        return new int[]{chargedId(), unchargedId()};
    }

    @Override
    public ItemPair[] getMatchingPairs() {
        final Item[] materials = materialsPerSet();
        final ItemPair[] pairs = new ItemPair[materials.length * 2];
        for (int i = 0; i < materials.length; i++) {
            pairs[i * 2] = ItemPair.of(materials[i].getId(), chargedId());
            pairs[i * 2 + 1] = ItemPair.of(materials[i].getId(), unchargedId());
        }
        return pairs;
    }

    @Override
    public void handleItemOnItemAction(final Player player, final Item from, final Item to, final int fromSlot, final int toSlot) {
        final boolean fromIsWeapon = from.getId() == chargedId() || from.getId() == unchargedId();
        final Item weapon = fromIsWeapon ? from : to;
        charge(player, weapon, player.getInventory().getContainer(), fromIsWeapon ? fromSlot : toSlot);
    }

    protected void check(final Player player, final Item item) {
        final int charges = item.getId() == chargedId() ? item.getCharges() : 0;
        player.getVarManager().sendBit(chargesVarbit(), Math.min(charges, 0xFFFF));
        player.sendMessage("Your " + displayName() + " has " + Utils.format(charges) + " charge" + (charges == 1 ? "" : "s") + " remaining.");
    }

    private int affordableSets(final Player player) {
        int sets = Integer.MAX_VALUE;
        for (final Item material : materialsPerSet()) {
            sets = Math.min(sets, player.getInventory().getAmountOf(material.getId()) / material.getAmount());
        }
        return sets;
    }

    private String materialList() {
        final StringBuilder builder = new StringBuilder();
        final Item[] materials = materialsPerSet();
        for (int i = 0; i < materials.length; i++) {
            if (i > 0) {
                builder.append(i == materials.length - 1 ? " and " : ", ");
            }
            builder.append(materials[i].getAmount()).append(" x ").append(ItemDefinitions.nameOf(materials[i].getId()));
        }
        return builder.toString();
    }

    protected void charge(final Player player, final Item item, final Container container, final int slotId) {
        final int current = item.getId() == chargedId() ? item.getCharges() : 0;
        final int roomSets = (maxCharges() - current) / chargesPerSet();
        if (roomSets <= 0) {
            player.sendMessage("Your " + displayName() + " can't hold any more charges.");
            return;
        }
        final int sets = Math.min(roomSets, affordableSets(player));
        if (sets <= 0) {
            player.sendMessage("You need " + materialList() + " for every " + chargesPerSet() + " charges.");
            return;
        }
        player.sendInputInt("How many charges would you like to add? (" + chargesPerSet() + " to " + Utils.format(sets * chargesPerSet()) + ")", value -> {
            final int wantedSets = Math.min(Math.min(roomSets, affordableSets(player)), value / chargesPerSet());
            if (wantedSets <= 0) {
                return;
            }
            if (container.get(slotId) != item) {
                return;
            }
            for (final Item material : materialsPerSet()) {
                player.getInventory().deleteItem(material.getId(), material.getAmount() * wantedSets);
            }
            final int charges = (item.getId() == chargedId() ? item.getCharges() : 0) + wantedSets * chargesPerSet();
            item.setId(chargedId());
            item.setCharges(charges);
            container.refresh(slotId);
            refreshIfWorn(player, container);
            player.getVarManager().sendBit(chargesVarbit(), Math.min(charges, 0xFFFF));
            player.sendMessage("You add " + Utils.format(wantedSets * chargesPerSet()) + " charges. Your " + displayName() + " now has " + Utils.format(charges) + " charges.");
        });
    }

    protected void uncharge(final Player player, final Item item, final Container container, final int slotId) {
        if (item.getId() != chargedId()) {
            return;
        }
        if (player.getDuel() != null && player.getDuel().inDuel()) {
            player.sendMessage("You can't do this during a duel.");
            return;
        }
        final int sets = item.getCharges() / chargesPerSet();
        player.getDialogueManager().start(new Dialogue(player) {
            @Override
            public void buildDialogue() {
                options("Uncharge your " + displayName() + "?", new DialogueOption("Yes.", () -> {
                    if (container.get(slotId) != item || item.getId() != chargedId()) {
                        return;
                    }
                    for (final Item material : materialsPerSet()) {
                        if (sets > 0) {
                            player.getInventory().addOrDrop(material.getId(), material.getAmount() * sets);
                        }
                    }
                    item.setCharges(0);
                    item.setId(unchargedId());
                    container.refresh(slotId);
                    refreshIfWorn(player, container);
                    player.getVarManager().sendBit(chargesVarbit(), 0);
                    player.sendMessage("You uncharge your " + displayName() + ".");
                }), new DialogueOption("No."));
            }
        });
    }

    private static void refreshIfWorn(final Player player, final Container container) {
        if (container == player.getEquipment().getContainer()) {
            player.getEquipment().refresh(EquipmentSlot.WEAPON.getSlot());
            player.getCombatDefinitions().refresh();
        }
    }
}

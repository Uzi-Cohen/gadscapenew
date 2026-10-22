package com.zenyte.plugins.item;

import com.zenyte.game.item.Item;
import com.zenyte.game.model.item.PairedItemOnItemPlugin;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.Container;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;

/**
 * Pages handling for the Tome of Water and the Tome of Earth, the same as the Tome of Fire ({@link TomeOfFire}): each
 * page adds 20 charges, up to 1,000 pages; removing pages gives back the whole pages left. The combat effects are in
 * {@link com.zenyte.game.world.entity.player.action.combat.magic.ElementalTomes}, the free runes in TomeOfFireResource.
 */
public abstract class ElementalTomePages extends ItemPlugin implements PairedItemOnItemPlugin {
    private static final int CHARGES_PER_PAGE = 20;
    private static final int MAX_CHARGES = 20_000;

    protected abstract int tomeId();

    protected abstract int emptyTomeId();

    protected abstract int pageId();

    protected abstract String tomeName();

    @Override
    public void handle() {
        bind("Pages", (player, item, container, slotId) -> player.getDialogueManager().start(new Dialogue(player) {
            @Override
            public void buildDialogue() {
                options(TITLE, new DialogueOption("Add all pages", () -> addPages(player, item, slotId, container)),
                        new DialogueOption("Remove pages", () -> removePages(player, item, slotId, container)));
            }
        }));
        bind("Check", (player, item, container, slotId) -> player.sendMessage("Your " + tomeName() + " has " + item.getCharges() + " charges (" + item.getCharges() / CHARGES_PER_PAGE + " pages)."));
    }

    private void addPages(final Player player, final Item item, final int slotId, final Container container) {
        player.getDialogueManager().finish();
        final int current = item.getId() == tomeId() ? item.getCharges() : 0;
        if (current + CHARGES_PER_PAGE > MAX_CHARGES) {
            player.sendMessage("The " + tomeName() + " can't hold any more pages.");
            return;
        }
        int pages = player.getInventory().getAmountOf(pageId());
        if (pages <= 0) {
            player.sendMessage("You need some pages to fill the " + tomeName() + ".");
            return;
        }
        pages = Math.min(pages, (MAX_CHARGES - current) / CHARGES_PER_PAGE);
        player.getInventory().deleteItem(pageId(), pages);
        item.setId(tomeId());
        item.setCharges(current + pages * CHARGES_PER_PAGE);
        container.refresh(slotId);
        player.sendMessage("Your tome currently holds " + item.getCharges() + " charges.");
    }

    private void removePages(final Player player, final Item item, final int slotId, final Container container) {
        player.getDialogueManager().finish();
        if (item.getId() != tomeId() || item.getCharges() < CHARGES_PER_PAGE) {
            player.sendMessage("There are no complete pages left in this " + tomeName() + ".");
            return;
        }
        if (!player.getInventory().hasFreeSlots() && !player.getInventory().containsItem(pageId(), 1)) {
            player.sendMessage("You need some more free space to remove the pages.");
            return;
        }
        player.sendInputInt("How many pages would you like to remove?", value -> {
            if (container.get(slotId) != item || item.getId() != tomeId()) {
                return;
            }
            final int pages = Math.min(value, item.getCharges() / CHARGES_PER_PAGE);
            if (pages <= 0) {
                return;
            }
            player.getInventory().addItem(pageId(), pages);
            item.setCharges(item.getCharges() - pages * CHARGES_PER_PAGE);
            if (item.getCharges() <= 0) {
                item.setCharges(0);
                item.setId(emptyTomeId());
            }
            container.refresh(slotId);
            player.sendMessage("You remove " + pages + " page" + (pages > 1 ? "s" : "") + " from the " + tomeName() + ".");
        });
    }

    @Override
    public void handleItemOnItemAction(final Player player, final Item from, final Item to, final int fromSlot, final int toSlot) {
        final Item book = from.getId() == pageId() ? to : from;
        addPages(player, book, book == from ? fromSlot : toSlot, player.getInventory().getContainer());
    }

    @Override
    public int[] getItems() {
        return new int[]{tomeId(), emptyTomeId()};
    }

    @Override
    public ItemPair[] getMatchingPairs() {
        return new ItemPair[]{ItemPair.of(pageId(), emptyTomeId()), ItemPair.of(pageId(), tomeId())};
    }
}

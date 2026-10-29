package com.zenyte.game.content.partyroom;

import com.gadscape.game.content.kits.KitItems;
import com.zenyte.game.GameInterface;
import com.zenyte.game.item.Item;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.util.AccessMask;
import com.zenyte.game.util.Colour;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.Container;
import com.zenyte.game.world.entity.player.container.impl.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The Party Drop Chest (interface 265, build 241 layout): buttons 1 / 5 / X / All are components 7-10 (varbit 7792 =
 * 0, 1, 3, 2), the chest's contents (inventory 91, 216 slots) are drawn into component 11, the player's offer row
 * (inventory 92, 8 slots) into component 16 and Confirm is component 17.
 *
 * @author Kris | 25/12/2019
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class PartyDropChestInterface extends Interface {
    @Override
    protected void attach() {
        put(7, "Add 1");
        put(8, "Add 5");
        put(9, "Add x");
        put(10, "Add all");
        put(17, "Accept");
        put(11, "Chest public deposit container");
        put(16, "Chest private deposit container");
    }

    @Override
    public void open(Player player) {
        player.getInterfaceHandler().sendInterface(this);
        GameInterface.PARTY_DROP_CHEST_INVENTORY.open(player);
        final FaladorPartyRoom partyroom = FaladorPartyRoom.getPartyRoom();
        partyroom.startViewing(player);
        final Container container = partyroom.getContainer();
        container.setFullUpdate(true);
        container.refresh(player);
        final Container privateContainer = partyroom.getPrivateContainer(player);
        privateContainer.setFullUpdate(true);
        privateContainer.refresh(player);
        player.getPacketDispatcher().sendComponentSettings(getInterface(), getComponent("Chest public deposit container"), 0, 215, AccessMask.CLICK_OP10);
        player.getPacketDispatcher().sendComponentSettings(getInterface(), getComponent("Chest private deposit container"), 0, 7,
                AccessMask.CLICK_OP1, AccessMask.CLICK_OP2, AccessMask.CLICK_OP3, AccessMask.CLICK_OP4, AccessMask.CLICK_OP5, AccessMask.CLICK_OP10);
        player.getVarManager().sendBitInstant(7792, 0);
    }

    @Override
    public void close(final Player player, final Optional<GameInterface> replacement) {
        FaladorPartyRoom.getPartyRoom().stopViewing(player);
        player.getInterfaceHandler().closeInterface(GameInterface.PARTY_DROP_CHEST_INVENTORY);
        final Container privateContainer = FaladorPartyRoom.getPartyRoom().getPrivateContainer(player);
        final Inventory inventory = player.getInventory();
        privateContainer.getItems().values().forEach(inventory::addOrDrop);
        privateContainer.clear();
    }

    @Override
    protected void build() {
        bind("Add 1", player -> player.getVarManager().sendBitInstant(7792, 0));
        bind("Add 5", player -> player.getVarManager().sendBitInstant(7792, 1));
        bind("Add x", player -> player.getVarManager().sendBitInstant(7792, 3));
        bind("Add all", player -> player.getVarManager().sendBitInstant(7792, 2));
        bind("Accept", player -> {
            final FaladorPartyRoom partyroom = FaladorPartyRoom.getPartyRoom();
            if (partyroom.getPrivateContainer(player).isEmpty()) {
                return;
            }
            if (partyroom.getVariables().isDisabled()) {
                player.sendMessage("The party room has been disabled.");
                return;
            }
            if (!player.getPrivilege().eligibleTo(partyroom.getVariables().getMinimumPrivilegeToDepositItems())) {
                player.sendMessage("You are not eligible to deposit items in the party chest right now.");
                return;
            }
            final Container publicContainer = partyroom.getContainer();
            confirm(player, publicContainer, partyroom.getVariables().getMinimumItemStackValueThreshold(), () -> partyroom.forEachViewing(publicContainer::refresh));
        });
        bind("Chest private deposit container", (player, slotId, itemId, option) -> withdraw(player, slotId, option));
    }

    /**
     * A click on the offer row: takes the item back (1 / 5 / X / All) or examines it.
     */
    static void withdraw(@NotNull final Player player, final int slotId, final int option) {
        final Container container = FaladorPartyRoom.getPartyRoom().getPrivateContainer(player);
        final Item item = container.get(slotId);
        if (item == null) {
            return;
        }
        final Inventory inventory = player.getInventory();
        final Container inventoryContainer = inventory.getContainer();
        final PartyRoomOptionType optionType = getType(player, option);
        if (optionType == PartyRoomOptionType.EXAMINE) {
            player.sendMessage(item.getDefinitions().getExamine());
            return;
        }
        if (optionType == PartyRoomOptionType.X) {
            player.sendInputInt("How many would you like to withdraw?", value -> {
                if (container.get(slotId) != item) {
                    return;
                }
                inventoryContainer.deposit(player, container, slotId, value);
                container.refresh(player);
                inventoryContainer.refresh(player);
                PartyDropInventoryInterface.refreshContainer(player);
            });
            return;
        }
        final int amount = optionType.getAmount();
        inventoryContainer.deposit(player, container, slotId, amount);
        container.refresh(player);
        inventoryContainer.refresh(player);
        PartyDropInventoryInterface.refreshContainer(player);
    }

    /**
     * Whether {@code item} may go into a party chest: tradeable and not a spawned premade-kit item.
     */
    static boolean isDepositable(@NotNull final Item item) {
        return item.isTradable() && !KitItems.isKitItem(item);
    }

    /**
     * Confirm: moves the player's offer row into {@code chest}. Every item is checked again here; untradeables and kit items
     * go back to the inventory, stacks under {@code minimumStackValue} (0: no limit) stay in the offer row.
     */
    static void confirm(@NotNull final Player player, @NotNull final Container chest, final int minimumStackValue, @NotNull final Runnable refreshViewers) {
        final Container container = FaladorPartyRoom.getPartyRoom().getPrivateContainer(player);
        final Inventory inventory = player.getInventory();
        final int length = container.getContainerSize();
        boolean unableToDeposit = false;
        for (int i = 0; i < length; i++) {
            final Item item = container.get(i);
            if (item == null) {
                continue;
            }
            if (!isDepositable(item)) {
                unableToDeposit = true;
                container.set(i, null);
                inventory.addOrDrop(item);
                player.sendMessage(Colour.RED.wrap(item.getName() + " can't be put in the party chest."));
                continue;
            }
            if (minimumStackValue > 0) {
                final long valueOfStack = (long) item.getSellPrice() * item.getAmount();
                if (valueOfStack < minimumStackValue) {
                    unableToDeposit = true;
                    player.sendMessage(Colour.RED.wrap(item.getAmount() + " x " + item.getName() + " could not be deposited: Value of stack under the threshold of " + minimumStackValue + "."));
                    continue;
                }
            }
            chest.deposit(null, container, i, item.getAmount());
        }
        container.setFullUpdate(true);
        container.refresh(player);
        inventory.refreshAll();
        PartyDropInventoryInterface.refreshContainer(player);
        chest.setFullUpdate(true);
        refreshViewers.run();
        if (!unableToDeposit && !container.isEmpty()) {
            player.sendMessage("Not enough space in the drop chest.");
        }
    }

    /**
     * The op clicked on an offer or inventory item: op 1 is the amount the 1 / 5 / X / All buttons picked (varbit 7792),
     * ops 2-5 are 1, 5, X and All, op 10 Examine (clientscripts 3612 / 3620).
     */
    public static PartyRoomOptionType getType(Player player, int option) {
        PartyRoomOptionType optionType;
        switch (option) {
            case 1:
                final int type = player.getVarManager().getBitValue(7792);
                switch (type) {
                    case 0:
                        optionType = PartyRoomOptionType.ONE;
                        break;
                    case 1:
                        optionType = PartyRoomOptionType.FIVE;
                        break;
                    case 2:
                        optionType = PartyRoomOptionType.ALL;
                        break;
                    default:
                        optionType = PartyRoomOptionType.X;
                        break;
                }
                break;
            case 2:
                optionType = PartyRoomOptionType.ONE;
                break;
            case 3:
                optionType = PartyRoomOptionType.FIVE;
                break;
            case 4:
                optionType = PartyRoomOptionType.X;
                break;
            case 10:
                optionType = PartyRoomOptionType.EXAMINE;
                break;
            default:
                optionType = PartyRoomOptionType.ALL;
                break;
        }
        return optionType;
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.PARTY_DROP_CHEST;
    }
}

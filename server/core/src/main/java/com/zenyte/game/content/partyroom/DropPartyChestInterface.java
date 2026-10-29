package com.zenyte.game.content.partyroom;

import com.gadscape.game.content.giveaway.PartyGiveawayUi;
import com.zenyte.game.GameInterface;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.util.AccessMask;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.Container;
import com.zenyte.game.world.entity.player.container.impl.Inventory;

import java.util.Optional;

/**
 * The staff copy of the Party Drop Chest (::dropparty, interface 5240 from the editor's PartyGiveawayPatch): interface 265
 * as it is, with its own chest ({@link DropParty}) and a Party button under All that drops the chest's items in balloons
 * around the staff member. The inventory side is the usual 266 ({@link PartyDropInventoryInterface}), which offers into
 * the same offer row.
 */
public class DropPartyChestInterface extends Interface {
    @Override
    protected void attach() {
        put(PartyGiveawayUi.DropPartyChest.ONE, "Add 1");
        put(PartyGiveawayUi.DropPartyChest.FIVE, "Add 5");
        put(PartyGiveawayUi.DropPartyChest.X, "Add x");
        put(PartyGiveawayUi.DropPartyChest.ALL, "Add all");
        put(PartyGiveawayUi.DropPartyChest.CONFIRM, "Accept");
        put(PartyGiveawayUi.DropPartyChest.CHEST, "Chest public deposit container");
        put(PartyGiveawayUi.DropPartyChest.OFFER, "Chest private deposit container");
        put(PartyGiveawayUi.DropPartyChest.PARTY, "Party");
    }

    @Override
    public void open(Player player) {
        if (!DropParty.mayUse(player)) {
            return;
        }
        player.getInterfaceHandler().sendInterface(this);
        GameInterface.PARTY_DROP_CHEST_INVENTORY.open(player);
        DropParty.startViewing(player);
        final Container container = DropParty.getContainer();
        container.setFullUpdate(true);
        container.refresh(player);
        final Container privateContainer = FaladorPartyRoom.getPartyRoom().getPrivateContainer(player);
        privateContainer.setFullUpdate(true);
        privateContainer.refresh(player);
        player.getPacketDispatcher().sendComponentSettings(getInterface(), getComponent("Chest public deposit container"), 0, 215, AccessMask.CLICK_OP10);
        player.getPacketDispatcher().sendComponentSettings(getInterface(), getComponent("Chest private deposit container"), 0, 7,
                AccessMask.CLICK_OP1, AccessMask.CLICK_OP2, AccessMask.CLICK_OP3, AccessMask.CLICK_OP4, AccessMask.CLICK_OP5, AccessMask.CLICK_OP10);
        player.getVarManager().sendBitInstant(7792, 0);
    }

    @Override
    public void close(final Player player, final Optional<GameInterface> replacement) {
        DropParty.stopViewing(player);
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
            if (!DropParty.mayUse(player) || FaladorPartyRoom.getPartyRoom().getPrivateContainer(player).isEmpty()) {
                return;
            }
            PartyDropChestInterface.confirm(player, DropParty.getContainer(), 0, DropParty::refreshViewers);
        });
        bind("Chest private deposit container", (player, slotId, itemId, option) -> PartyDropChestInterface.withdraw(player, slotId, option));
        bind("Party", player -> {
            if (!DropParty.mayUse(player)) {
                return;
            }
            DropParty.start(player);
        });
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.DROP_PARTY_CHEST;
    }
}

package com.zenyte.game.content.skills.construction.servant;

import com.zenyte.game.item.Item;
import com.zenyte.game.model.item.ItemOnNPCAction;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.npc.NpcId;
import com.zenyte.game.world.entity.player.Player;

/**
 * Using an item on the hired servant: take to the bank, unnote, or (cook and up) take logs to the sawmill.
 */
public final class ServantItemAction implements ItemOnNPCAction {

    @Override
    public void handleItemOnNPCAction(final Player player, final Item item, final int slot, final NPC npc) {
        if (!(npc instanceof ServantNPC)) {
            player.sendMessage("Nothing interesting happens.");
            return;
        }
        ((ServantNPC) npc).getHouse().getServantManager().onItemUsed(player, item);
    }

    @Override
    public Object[] getItems() {
        return null;
    }

    @Override
    public Object[] getObjects() {
        return new Object[] { NpcId.RICK, NpcId.MAID, NpcId.COOK, NpcId.BUTLER, NpcId.DEMON_BUTLER };
    }
}

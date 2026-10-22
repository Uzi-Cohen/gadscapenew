package com.zenyte.plugins.itemonitem;

import com.zenyte.game.item.Item;
import com.zenyte.game.model.item.ItemOnItemAction;
import com.zenyte.game.model.item.enums.DismantleableItem;
import com.zenyte.game.model.item.enums.RuneUpgradeItem;
import com.zenyte.game.world.entity.player.Player;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * @author Kris | 4. apr 2018 : 17:56.08
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
@SuppressWarnings("unused")
public final class ItemAttaching implements ItemOnItemAction {

	@Override
	public void handleItemOnItemAction(final Player player, final Item from, final Item to, final int fromSlot, final int toSlot) {
		DismantleableItem dis = null;
		final int f = from.getId();
		final int t = to.getId();
		// kits that are kept and cost runes (Sanguine torva, Radiant oathplate)
		final RuneUpgradeItem upgrade = RuneUpgradeItem.find(f, t);
		if (upgrade != null) {
			upgrade.upgrade(player, f == upgrade.getBase() ? fromSlot : toSlot);
			return;
		}
		for (final DismantleableItem val : DismantleableItem.VALUES) {
			final int base = val.getBaseItem();
			final int kit = val.getKit();
			if (base == f && kit == t || base == t && kit == f) {
				dis = val;
				break;
			}
		}
		if (dis == null) {
			return;
		}
		// A result the loaded cache does not have (e.g. GadScape kit results before the cache adds them) would be an unknown
		// obj for the client, and a GadScape result id the cache holds another item at would hand out that item.
		if (!dis.isAvailable()) {
			player.sendMessage("Nothing interesting happens.");
			return;
		}
		player.sendMessage("You attach the " + dis.getKitName() + " onto the " + dis.getBaseName() + ".");
		player.getInventory().deleteItem(fromSlot, from);
		player.getInventory().deleteItem(toSlot, to);
		int charges = Math.max(from.getCharges(), to.getCharges());
		player.getInventory().addItem(dis.getCompleteItem(), 1, charges);
	}

	@Override
	public int[] getItems() {
		final IntArrayList list = new IntArrayList();
		for (final DismantleableItem val : DismantleableItem.VALUES) {
			list.add(val.getKit());
			list.add(val.getBaseItem());
		}
		for (final RuneUpgradeItem val : RuneUpgradeItem.VALUES) {
			list.add(val.getKit());
			list.add(val.getBase());
		}
		return list.toArray(new int[0]);
	}

}

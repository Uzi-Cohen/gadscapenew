package com.zenyte.plugins.flooritem;

import com.zenyte.game.content.lootkeys.LootKeys;
import com.zenyte.game.content.lootkeys.LootkeyConstants;
import com.zenyte.game.item.Item;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.masks.UpdateFlag;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.flooritem.FloorItem;
import org.jetbrains.annotations.NotNull;

/**
 * Loot keys on the floor (OSRS): only the player the key was dropped for can take it (it never turns public), a
 * player can carry at most 5 keys, and taking one refreshes the key-count skull. The key keeps its loot only when
 * taken by its owner, so anyone else is refused instead of getting an empty key.
 */
public class LootKeyFloorPlugin implements FloorItemPlugin {

	@Override
	public void handle(Player player, FloorItem item, int optionId, String option) {
		if (!canTake(player, item)) {
			return;
		}
		World.takeFloorItem(player, item);
		LootKeys.normalizeIds(player);
		player.getUpdateFlags().flag(UpdateFlag.APPEARANCE);
	}

	@Override
	public boolean canTelegrab(@NotNull Player player, @NotNull FloorItem item) {
		return canTake(player, item);
	}

	@Override
	public void telegrab(@NotNull Player player, @NotNull FloorItem item) {
		if (!canTake(player, item)) {
			return;
		}
		World.destroyFloorItem(item);
		player.getInventory().addItem(new Item(item.getId(), item.getAmount(), item.getAttributesCopy()))
				.onFailure(it -> World.spawnFloorItem(it, item.getLocation(), -1, player, player, LootkeyConstants.FLOOR_KEY_TICKS, 0));
		LootKeys.normalizeIds(player);
		player.getUpdateFlags().flag(UpdateFlag.APPEARANCE);
	}

	private static boolean canTake(final Player player, final FloorItem item) {
		if (item.hasOwner() && !item.isOwner(player)) {
			player.sendMessage("This loot key belongs to someone else.");
			return false;
		}
		if (LootKeys.countKeys(player) >= LootkeyConstants.MAX_KEYS) {
			player.sendMessage("You can't carry more than " + LootkeyConstants.MAX_KEYS + " loot keys.");
			return false;
		}
		return true;
	}

	@Override
	public boolean overrideTake() {
		return true;
	}

	@Override
	public int[] getItems() {
		return LootkeyConstants.LOOT_KEY_IDS;
	}

}

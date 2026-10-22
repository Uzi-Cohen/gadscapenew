package com.zenyte.game.world.entity.player.calog;

import com.zenyte.game.GameInterface;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.util.AccessMask;
import com.zenyte.game.world.entity.player.Player;

/**
 * @author Savions.
 */
public class CALogRewardInterface extends Interface {

	@Override protected void attach() {
		put(16, "Navigation"); // 241 ca_rewards:burger_menu_frame (211: 14)
	}

	@Override public void open(Player player) {
		super.open(player);
		player.getPacketDispatcher().sendComponentSettings(GameInterface.CA_REWARDS, 16, 0, 100, AccessMask.CLICK_OP1);
	}

	@Override protected void build() {
		bind("Navigation", (player, slotId, itemId, option) -> {
			if (slotId == 10) {
				GameInterface.CA_OVERVIEW.open(player);
			} else if (slotId == 12) {
				GameInterface.CA_TASKS.open(player);
			} else if (slotId == 14) {
				GameInterface.CA_BOSS_OVERVIEW.open(player);
			}
		});
	}

	@Override public GameInterface getInterface() {
		return GameInterface.CA_REWARDS;
	}
}

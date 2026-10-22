package com.zenyte.plugins.interfaces.dialogue;

import com.zenyte.game.world.entity.player.dialogue.SkillMessage;

import com.zenyte.game.model.ui.UserInterface;
import com.zenyte.game.world.entity.player.Player;

/**
 * @author Kris | 12. juuli 2018 : 14:32:51
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public class SkillDialogueInterface implements UserInterface {

	@Override
	public void handleComponentClick(final Player player, final int interfaceId, final int componentId, final int slotId, final int itemId, final int optionId, final String option) {
		// 241 skillmulti (270): the item buttons a..r are components 15..32 (211: 14..23).
		player.getDialogueManager().onClick(slotId, componentId - SkillMessage.FIRST_ITEM_BUTTON);
	}

	@Override
	public int[] getInterfaceIds() {
		return new int[] { 270 };
	}

}

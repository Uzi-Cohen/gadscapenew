package com.zenyte.game.content.skills.construction.dialogue;

import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.RoomReference;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;

/**
 * Staircase "Remove-room": removes the room above. Acts on the house the room belongs to, and only for its owner in building
 * mode (see {@link Construction#removeRoom}).
 *
 * @author Kris | 28. nov 2017 : 23:07.54
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public final class UpperRoomRemovalD extends Dialogue {

	public UpperRoomRemovalD(final Player player, final Construction house, final RoomReference ref) {
		super(player);
		this.house = house;
		this.ref = ref;
	}

	private final Construction house;

	private final RoomReference ref;

	@Override
	public void buildDialogue() {
		plain("Are you sure you wish to remove the " + ref.getRoom().toString().toLowerCase() + " above?");
		options(TITLE, "Yes, remove it.", "No, don't remove it.")
		.onOptionOne(() -> {
			finish();
			house.removeRoom(player, ref);
		})
		.onOptionTwo(() -> finish());
	}

}

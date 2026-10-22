package com.zenyte.plugins.dialogue;

import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.RoomReference;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;

/**
 * "Remove the room?" for a door hotspot or the dungeon entrance's "Remove-room". Acts on the house the room belongs to, and
 * only for its owner in building mode (see {@link Construction#removeRoom}).
 *
 * @author Tommeh | 17 nov. 2017 : 17:33:44
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server
 *      profile</a>}
 */
public class RoomRemovingD extends Dialogue {

	private final Construction house;

	private final RoomReference reference;

	public RoomRemovingD(final Player player, final Construction house, final RoomReference reference) {
		super(player);
		this.house = house;
		this.reference = reference;
	}

	@Override
	public void buildDialogue() {
		if (player.getPlane() == 1 && house.getReference(reference.getX(), reference.getY(), 2) != null) {
			plain("You can't remove a room supporting another room.");
			return;
		}
		options("Remove the " + reference.getRoom().toString().toLowerCase() + "?", "Yes", "No").onOptionOne(() -> {
			finish();
			house.removeRoom(player, reference);
		}).onOptionTwo(() -> finish());
	}

}

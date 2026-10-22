package com.zenyte.plugins.object;

import com.zenyte.game.content.skills.construction.constants.House;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.object.ObjectAction;
import com.zenyte.game.world.object.WorldObject;
import com.zenyte.plugins.dialogue.HousePortal;

import java.util.ArrayList;

/**
 * House portals: Enter (choice), Home, Build mode, Friend's house.
 *
 * @author Tommeh | 16 nov. 2017 : 22:06:57
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server
 *      profile</a>}
 */
public final class HousePortalObject implements ObjectAction {
	@Override
	public void handleObjectAction(final Player player, final WorldObject object, final String name, final int optionId, final String option) {
		final House location = House.getHouseByObject(object);
		if (optionId == 1) {
			player.getDialogueManager().start(new HousePortal(player, object));
		} else if (optionId == 2) {
			HousePortal.enterOwnHouse(player, location, false);
		} else if (optionId == 3) {
			HousePortal.enterOwnHouse(player, location, true);
		} else if (optionId == 4) {
			HousePortal.askFriendsHouse(player, location);
		}
	}

	@Override
	public Object[] getObjects() {
		final ArrayList<Object> list = new ArrayList<Object>();
		for (final House house : House.VALUES) {
			list.add(house.getObjectId());
		}
		return list.toArray(new Object[list.size()]);
	}
}

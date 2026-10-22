package com.zenyte.plugins.dialogue;

import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.constants.House;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.game.world.object.WorldObject;

/**
 * House portal (Rimmington, Taverley, ...): enter your own house, in building mode, or a friend's house. Your own house only
 * through the portal of the town it is in; a friend's house only through the portal of the town their house is in.
 */
public class HousePortal extends Dialogue {

	private final House location;

	public HousePortal(final Player player, final WorldObject portal) {
		super(player);
		this.location = portal == null ? null : House.getHouseByObject(portal);
	}

	public HousePortal(Player player) {
		this(player, null);
	}

	@Override
	public void buildDialogue() {
		options(TITLE, "Go to your house", "Go to your house (building mode)", "Go to a friend's house", "Never mind").onOptionOne(() -> {
			finish();
			enterOwnHouse(player, location, false);
		}).onOptionTwo(() -> {
			finish();
			enterOwnHouse(player, location, true);
		}).onOptionThree(() -> {
			finish();
			askFriendsHouse(player, location);
		}).onOptionFour(this::finish);
	}

	public static void enterOwnHouse(final Player player, final House location, final boolean building) {
		final Construction construction = player.getConstruction();
		if (location != null && construction.getHouse() != location) {
			player.sendMessage("Your house isn't in " + location + ". It's in " + construction.getHouse() + ".");
			return;
		}
		construction.enterHouse(building);
	}

	public static void askFriendsHouse(final Player player, final House location) {
		player.sendInputName("Enter name:", name -> enterFriendsHouse(player, location, name));
	}

	public static void enterFriendsHouse(final Player player, final House location, final String name) {
		if (player.getGameMode().isNonGroupIronman()) {
			player.sendMessage("As an Iron Man, you cannot enter other players' houses.");
			return;
		}
		final Player owner = World.getPlayer(name).orElse(null);
		if (owner == null || owner.isFinished()) {
			player.sendMessage("That player is not at home.");
			return;
		}
		if (owner == player) {
			enterOwnHouse(player, location, false);
			return;
		}
		final Construction house = owner.getConstruction();
		if (location != null && house.getHouse() != location) {
			player.sendMessage("That player's house isn't in " + location + ".");
			return;
		}
		house.enterAsGuest(player);
	}

}

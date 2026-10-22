package com.zenyte.game.content.skills.magic.spells.teleports.structures;

import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.magic.spells.teleports.Teleport;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.util.AnimationUtil;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.masks.Graphics;
import com.zenyte.game.world.entity.player.Player;

import static com.zenyte.utils.TimeUnit.MILLISECONDS;

/**
 * Teleport to House (spell, and the house teleports that reuse it). Lands inside the house at the exit portal, or outside at
 * the house portal: the "Inside" / "Outside" options choose, otherwise the house options "Teleport inside" setting does.
 *
 * @author Kris | 15. juuli 2018 : 22:32:57
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public class HouseStructure extends RegularStructure {

	/** Temporary attribute naming where the next house teleport lands: {@link #INSIDE} or {@link #OUTSIDE}; absent = setting. */
	public static final String MODE_ATTR = "house_teleport_mode";
	private static final String ACTIVE_MODE_ATTR = "house_teleport_active_mode";
	public static final String INSIDE = "inside";
	public static final String OUTSIDE = "outside";

	/**
	 * Starts a house teleport that lands inside or outside regardless of the setting.
	 */
	public static void teleport(final Player player, final Teleport teleport, final String mode) {
		player.getTemporaryAttributes().put(MODE_ATTR, mode);
		teleport.teleport(player);
	}

	@Override
	public void teleport(final Player player, final Teleport teleport) {
		final Object mode = player.getTemporaryAttributes().remove(MODE_ATTR);
		player.getTemporaryAttributes().put(ACTIVE_MODE_ATTR, mode == null ? "" : mode);
		super.teleport(player, teleport);
	}

	@Override
	public boolean isTeleportPrevented(final Player player, final Teleport teleport) {
		return isHouseTeleportPrevented(player);
	}

	static boolean isHouseTeleportPrevented(final Player player) {
		if (player.getConstruction().getHouse() == null) {
			player.sendMessage("You need to purchase yourself a house before you may use this teleport.");
			return true;
		}
		return false;
	}

	@Override
	public void end(final Player player, final Teleport teleport) {
		if (isTeleportPrevented(player, teleport) || isAreaPrevented(player, teleport) || isRestricted(player, teleport)) {
			player.getTemporaryAttributes().remove(ACTIVE_MODE_ATTR);
			stop(player, teleport);
			return;
		}
		final Animation endAnimation = Utils.getOrDefault(getEndAnimation(), Animation.STOP);
		final Graphics endGraphics = Utils.getOrDefault(getEndGraphics(), Graphics.RESET);
		arrive(player, teleport, this, endAnimation, endGraphics, (int) MILLISECONDS.toTicks(AnimationUtil.getCeiledDuration(endAnimation)) - 1);
		updateDiaries(player, teleport);
	}

	/**
	 * Lands the player inside their house (the instance is built, or joined when it is already up) or outside its portal.
	 */
	static void arrive(final Player player, final Teleport teleport, final TeleportStructure structure, final Animation endAnimation, final Graphics endGraphics, final int stopDelay) {
		final Object mode = player.getTemporaryAttributes().remove(ACTIVE_MODE_ATTR);
		final Construction house = player.getConstruction();
		final boolean outside = OUTSIDE.equals(mode) || !INSIDE.equals(mode) && !house.isTeleportInside();
		player.getInterfaceHandler().closeInterfaces();
		teleport.onArrival(player);
		player.setAnimation(endAnimation);
		player.setGraphics(endGraphics);
		if (outside) {
			player.setLocation(new Location(house.getHouse().getLocation()));
			WorldTasksManager.scheduleOrExecute(() -> structure.stop(player, teleport), stopDelay);
			return;
		}
		// The house unlocks the player once its map has loaded.
		player.blockIncomingHits();
		player.resetFreeze();
		if (!house.enterHouseByTeleport()) {
			// The house could not be built (no free map space, or an error): nothing will unlock the player, so do it here.
			structure.stop(player, teleport);
		}
	}

	@Override
	public Location getRandomizedLocation(final Player player, final Teleport teleport) {
		return player.getLocation();
	}
}

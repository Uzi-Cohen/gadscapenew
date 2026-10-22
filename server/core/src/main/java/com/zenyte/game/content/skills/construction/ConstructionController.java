package com.zenyte.game.content.skills.construction;

import com.zenyte.game.content.skills.construction.dialogue.RemoveObjectD;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.player.controller.Controller;
import com.zenyte.game.world.object.WorldObject;

/**
 * Active while a player (the owner or a guest) is inside a house instance. The house is {@link Construction#current}; the
 * controller notices the player leaving the instance in any way (portal, teleport, logout, death) and lets the house clean
 * up (guests, the instance itself).
 *
 * @author Kris | 6. march 2018 : 16:47.36
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public final class ConstructionController extends Controller {

	private static final Animation ANIM = new Animation(834);

	/** Whether the "Fight" player option is up (inside a combat ring, or the dungeon with dungeon PvP on). */
	private transient boolean fighting;

	private Construction house() {
		return player.getCurrentHouse();
	}

	@Override
	public boolean processObjectClick1(final WorldObject object) {
		if (object.getId() == ConstructionConstants.EXIT_PORTAL) {
			final Construction house = house();
			if (house == null) {
				player.getControllerManager().forceStop();
				return true;
			}
			house.exit(player);
			return false;
		}
		return true;
	}

	@Override
	public boolean processObjectClick2(final WorldObject object) {
		if (object.getId() == ConstructionConstants.EXIT_PORTAL) {
			final Construction house = house();
			if (house != null && house.isOwner(player)) {
				house.toggleLock();
			} else {
				player.sendMessage("Only the owner of the house can do that.");
			}
			return false;
		}
		return true;
	}

	@Override
	public boolean processObjectClick5(final WorldObject object) {
		final String option = object.getDefinitions().getOption(5);
		if (option == null) {
			return true;
		}
		if (option.equalsIgnoreCase("Build") || option.equals("Remove")) {
			final Construction house = house();
			if (house == null || !house.isOwner(player)) {
				player.sendMessage("You can only do that in your own house.");
				return false;
			}
			if (!house.isBuildingMode()) {
				player.sendMessage("You can only do that in building mode.");
				return false;
			}
		}
		if (option.equalsIgnoreCase("Build")) {
			if (ConstructionConstants.isDoor(object)) {
				player.getConstruction().sendRoomCreationMenu(object);
				return false;
			} else {
				player.getConstruction().sendFurnitureCreationMenu(object);
				return false;
			}
		} else if (option.equals("Remove")) {
			player.getDialogueManager().start(new RemoveObjectD(player, object));
			return false;
		}
		return true;
	}

	@Override
	public boolean processNPCClick3(final NPC npc) {
		final int id = npc.getId();
		if (id == 2668 || id == 7413) {
			if (!(npc instanceof CombatDummyNPC)) {
				return false;
			}
			final CombatDummyNPC n = (CombatDummyNPC) npc;
			player.setAnimation(ANIM);
			WorldTasksManager.schedule(() -> {
				n.finish();
				World.spawnObject(n.getObject());
			});
			return false;
		}
		return true;
	}

	/**
	 * Every move into a new location (house rebuilds, stairs, portals, teleport spells...) passes here: once the player is no
	 * longer inside the house instance, they have left the house.
	 */
	@Override
	public void teleport(final Location location) {
		final Construction house = house();
		if (house == null) {
			player.getControllerManager().forceStop();
			return;
		}
		if (!house.contains(location)) {
			house.onLeft(player, true);
		}
	}

	@Override
	public boolean sendDeath(final Entity source) {
		final Construction house = house();
		if (house == null) {
			return true;
		}
		house.onDeath(player);
		return false;
	}

	@Override
	public boolean removeOnLogout() {
		final Construction house = house();
		if (house != null) {
			house.onLogout(player);
		} else {
			player.forceLocation(new Location(player.getConstruction().getHouse().getLocation()));
		}
		return true;
	}

	@Override
	public void process() {
		final Construction house = house();
		if (house == null) {
			return;
		}
		final boolean fight = house.getCombat().canFightAt(player.getLocation());
		if (fight != fighting) {
			fighting = fight;
			player.setCanPvp(fight, true);
		}
		house.getCombat().onPlayerTick(player);
	}

	@Override
	public void forceStop() {
		if (fighting) {
			fighting = false;
			player.setCanPvp(false, true);
		}
	}

	@Override
	public boolean processPlayerCombat(final Entity entity, final String style) {
		if (!(entity instanceof com.zenyte.game.world.entity.player.Player)) {
			return true;
		}
		final Construction house = house();
		return house != null && house.getCombat().checkCombat(player, (com.zenyte.game.world.entity.player.Player) entity, style);
	}

	@Override
	public boolean removeOnLogin() {
		return true;
	}

}

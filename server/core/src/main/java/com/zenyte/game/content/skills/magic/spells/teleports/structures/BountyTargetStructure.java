package com.zenyte.game.content.skills.magic.spells.teleports.structures;

import com.gadscape.game.content.bountyhunter.WildyExtKt;
import com.gadscape.game.content.bountyhunter.teleport.TeleportToTarget;
import com.zenyte.game.content.skills.magic.spells.teleports.Teleport;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.region.area.wilderness.WildernessArea;

/**
 * "Teleport to Bounty Target" spell. Uses the Bounty Hunter (BH2020) pairs in BountyHunterController through
 * {@link TeleportToTarget}; the old temp attribute "bountyHunterTarget" / varbit 2010 were never set any more, so the
 * spell always answered "no target".
 *
 * @author Kris | 15. juuli 2018 : 18:59:21 (original)
 */
public class BountyTargetStructure extends RegularStructure {
	/** Set while the spell animation plays, so the end-of-cast check only re-validates the target. */
	private static final String CASTING_ATTR = "bh target teleport casting";

	@Override
	public void teleport(final Player player, final Teleport teleport) {
		player.getTemporaryAttributes().remove(CASTING_ATTR);
		super.teleport(player, teleport);
	}

	@Override
	public boolean isTeleportPrevented(final Player player, final Teleport teleport) {
		if (player.getTemporaryAttributes().remove(CASTING_ATTR) != null) {
			final Player target = WildyExtKt.getTarget(player).orElse(null);
			if (target == null || target.isFinished() || target.isNulled() || !WildernessArea.isWithinWilderness(target)) {
				player.sendMessage("Your target can no longer be reached.");
				return true;
			}
			return false;
		}
		return !TeleportToTarget.canTeleportToTarget(player, true);
	}

	/**
	 * The wilderness-level limit of normal spells (20) does not apply: the spell is cast inside the wilderness by
	 * design. Combat is checked by {@link TeleportToTarget#canTeleportToTarget}.
	 */
	@Override
	public boolean isRestricted(final Player player, final Teleport teleport) {
		return false;
	}

	@Override
	public void start(final Player player, final Teleport teleport) {
		TeleportToTarget.countTeleport(player);
		player.getTemporaryAttributes().put(CASTING_ATTR, Boolean.TRUE);
		super.start(player, teleport);
	}

	@Override
	public Location getRandomizedLocation(final Player player, final Teleport teleport) {
		final Player target = WildyExtKt.getTarget(player).orElse(null);
		if (target == null || target.isFinished() || target.isNulled()) {
			return new Location(player.getLocation());
		}
		return TeleportToTarget.landingLocation(target);
	}
}

package com.zenyte.game.content.skills.magic.spells.teleports.structures;

import com.zenyte.game.content.skills.magic.spells.teleports.Teleport;
import com.zenyte.game.util.AnimationUtil;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.masks.Graphics;
import com.zenyte.game.world.entity.player.Player;

import static com.zenyte.utils.TimeUnit.MILLISECONDS;

/**
 * The house teleport tablet: the usual tablet break animation, then the house arrival of {@link HouseStructure} (inside or
 * outside, by option or by the house setting).
 */
public class HouseTabletStructure extends TabletStructure {

	private static final String ACTIVE_MODE_ATTR = "house_teleport_active_mode";

	@Override
	public void teleport(final Player player, final Teleport teleport) {
		final Object mode = player.getTemporaryAttributes().remove(HouseStructure.MODE_ATTR);
		player.getTemporaryAttributes().put(ACTIVE_MODE_ATTR, mode == null ? "" : mode);
		super.teleport(player, teleport);
	}

	@Override
	public boolean isTeleportPrevented(final Player player, final Teleport teleport) {
		return HouseStructure.isHouseTeleportPrevented(player);
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
		HouseStructure.arrive(player, teleport, this, endAnimation, endGraphics, (int) MILLISECONDS.toTicks(AnimationUtil.getCeiledDuration(endAnimation)) + EXTRA_DELAY - 1);
		updateDiaries(player, teleport);
	}
}

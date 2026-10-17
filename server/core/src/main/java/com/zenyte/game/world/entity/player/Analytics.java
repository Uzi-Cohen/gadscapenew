package com.zenyte.game.world.entity.player;

import com.zenyte.game.GameConstants;
import com.zenyte.utils.StaticInitializer;

import java.sql.Timestamp;
import java.time.Instant;

/*
 * The MySQL "analytics" database this class used to write to (host, user and
 * password hardcoded in the now-deleted SQLManager) was never our infrastructure
 * and its credentials were stripped as part of the public-hosting secrets audit.
 * logLogin/logLogout are now no-ops; flagInteraction stays wired since ~20 call
 * sites across the codebase report to it.
 */
@StaticInitializer
public class Analytics {

	public static boolean enabled = !GameConstants.WORLD_PROFILE.isDevelopment();

	public static void logLogin(Player player) {
		if (!enabled) return;
		player.addTemporaryAttribute("analytics_login_timestamp", Timestamp.from(Instant.now()));
	}

	public static void logLogout(Player player) {
		if (!enabled) return;
	}

	public static void flagInteraction(Player player, InteractionType type) {
		int flags = player.getNumericTemporaryAttribute("analytics_interaction_flag").intValue();
		flags |= 1 << type.getIndex();
		player.addTemporaryAttribute("analytics_interaction_flag", flags);
	}

	public enum InteractionType {
		ORNATE_POOL(0),
		COMBAT_DUMMY(1),
		SLAYER_MASTER(2),
		ELVEN_CRYSTAL_CHEST(3),
		BRIMSTONE_CHEST(4),
		LOOT_CHEST(5),
		SHOP_NPCS(6),
		AFK_SKILLING(7),
		HOME_STALLS(8),
		DEATHS_DOMAIN(9),
		TELEPORT_INTERFACE(10),
		HISCORES(11),
		GAME_SETTINGS(12),
		DROP_VIEWER(13),
		DAILY_CHALLENGES(14),
		PREMIUM_TOGGLES(15),
		CHECK_BOOSTERS(16),
		;

		private final int index;

		InteractionType(int index) {
			this.index = index;
		}

		public int getIndex() {
			return index;
		}

	}

}

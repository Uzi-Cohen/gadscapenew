package com.zenyte.game;

import com.zenyte.ContentConstants;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.Player;
import com.gadscape.game.world.info.WorldProfile;
import com.zenyte.utils.TimeUnit;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.Set;

/**
 * @author Kris | 5. march 2018 : 17:05.26
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server
 *      profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status
 *      profile</a>}
 */
public class GameConstants {

	/**
	 * The current cache revision.
	 */
	public static final double REVISION = 179.3;
	public static final int CLIENT_VERSION = 3;
	public static final int WORLD_CYCLE_TIME = 600;
	public static final int LOGIN_PORT = 43596;
	public static final boolean DEV_DEBUG = false;
	public static final Location REGISTRATION_LOCATION = new Location(3360, 7206, 1);
	public static final String SERVER_NAME = ContentConstants.SERVER_NAME;
	public static final String SERVER_CHANNEL_NAME = "help";
	public static final String SERVER_WEBSITE_URL = "https://GadScape.com";
	public static final String SERVER_ACCOUNT_URL = SERVER_WEBSITE_URL + "/account";
	public static final String SERVER_FORUMS_URL = SERVER_WEBSITE_URL + "/forums";
	public static final String SERVER_STORE_URL = SERVER_WEBSITE_URL + "/donate";
	public static final String SERVER_VOTE_URL = SERVER_WEBSITE_URL + "/vote";
	public static final String SERVER_RULES_URL = SERVER_WEBSITE_URL + "/rules";
	public static final String DISCORD_INVITE = "https://discord.gg/9phpruzpC";

	/**
	 * The instance world profile.
	 */
	public static WorldProfile WORLD_PROFILE;

	public static boolean CYCLE_DEBUG = false;

	public static boolean CHECK_HUNTER_TRAPS_QUANTITY = true;

	public static boolean ANTIKNOX = false;
	public static boolean DUEL_ARENA = true;
	public static boolean GROTESQUE_GUARDIANS = true;
	public static boolean PURGING_CHUNKS = true;

	public static final boolean BOUNTY_HUNTER = false;

	public static double defenceMultiplier = 0.825;

	public static int randomEvent = (int) TimeUnit.HOURS.toTicks(5);

	public static boolean CHAMBERS_OF_XERIC = true;
	public static boolean ALCHEMICAL_HYDRA = true;

	/**
	 * The GadScape owner account(s). There is deliberately no built-in name: an account is an owner only when the
	 * operator names it, in worlds.yml ({@code owners: ["name"]} under the world) or with
	 * {@code -Dgadscape.owners=name1,name2}. With neither set nobody is an owner, so the owner-only commands
	 * (::checkowner, ::isolemnlysweariamuptonogood, ::magicbuffet, ::teleparty, ...) do nothing.
	 * Names are compared like login names: case-insensitive, spaces and underscores equal.
	 */
	public static Set<String> owners() {
		final Set<String> names = new ObjectOpenHashSet<>();
		final WorldProfile profile = WORLD_PROFILE;
		if (profile != null && profile.getOwners() != null) {
			for (final String name : profile.getOwners()) {
				addOwnerName(names, name);
			}
		}
		final String property = System.getProperty("gadscape.owners");
		if (property != null) {
			for (final String name : property.split(",")) {
				addOwnerName(names, name);
			}
		}
		return names;
	}

	private static void addOwnerName(final Set<String> names, final String name) {
		if (name != null && !name.isBlank()) {
			names.add(normaliseOwnerName(name));
		}
	}

	private static String normaliseOwnerName(final String name) {
		return name.trim().toLowerCase().replace(' ', '_');
	}

	public static boolean isOwnerName(final String username) {
		return username != null && !username.isBlank() && owners().contains(normaliseOwnerName(username));
	}

	public static boolean isOwner(final Player player) {
		return player != null && isOwnerName(player.getUsername());
	}

	/**
	 * Whether the game should connect the forums database and validate the
	 * login on a new registration or not.
	 */
	public static final boolean REGISTER_ON_FORUMS = false;

	public static final float TICK = WORLD_CYCLE_TIME;

	public static final float CLIENT_CYCLE = 20;

	public static final float CYCLES_PER_TICK = TICK / CLIENT_CYCLE;


	public static boolean BOOSTED_XP = false;

	public static boolean BOOSTED_SKILLING_PETS = false;
	public static final double BOOSTED_SKILLING_PET_RATE = 0.25; // this is a 15% boost

	public static boolean BOOSTED_BOSS_PETS = false;
	public static final double BOOSTED_BOSS_PET_RATE = 0.25; // this is a 15% boost

	public static int BOOSTED_XP_MODIFIER = 50;

	public static boolean FILTERING_DUPLICATE_JS5_REQUESTS = false;

	public static final String UPDATE_LOG_BROADCAST = "Quality of Life & Bug Fixes! Click here to join the Discord.";

	public static final String UPDATE_LOG_URL = "discord.gg/9phpruzpC";

	/**
	 * Item ids added to this list are restricted from trade, selling to shops, and posting on the GE. It is primarily used as an economy control measure
	 */
	public static final IntArrayList RESTRICTED_TRADE_ITEMS = new IntArrayList();
}

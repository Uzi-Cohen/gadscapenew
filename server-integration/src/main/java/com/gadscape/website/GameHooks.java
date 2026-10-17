package com.gadscape.website;

/**
 * The few things the integration needs from the game server. Implement this
 * once against your server's player/item APIs (see README). Every method is
 * called on the game thread via {@link #runOnGameThread(Runnable)}.
 */
public interface GameHooks {

	/** Run on the game/world thread (e.g. schedule for the next tick). */
	void runOnGameThread(Runnable task);

	/** True when this player is logged in. Names are as typed in game. */
	boolean isOnline(String player);

	void sendMessage(String player, String message);

	/** Open a link in the player's browser (the client's "open URL" packet). */
	void openUrl(String player, String url);

	/** Give an item to the inventory or bank. Return false if there's no room (it stays queued). */
	boolean giveItem(String player, int itemId, int amount);

	/** Add points: type is "VOTE_POINTS" or "DONATOR_POINTS". */
	void addPoints(String player, String type, int amount);

	/**
	 * Give a reward the site sent without an item id (PIN with no configured item,
	 * MYSTERY_BOX, CUSTOM_ITEM, BOSS_SPAWNER, CUSTOM_MAP...). Return false to leave it queued.
	 */
	boolean giveReward(String player, Reward reward);

	/** Apply a donator rank. tierKey is REGULAR, SUPER, ELITE, EXTREME, RESPECTED, PLATINUM or null. */
	void setDonatorTier(String player, String tierKey);

	/** Disconnect a player (ban or failed login check). */
	void kick(String player, String reason);
}

package com.zenyte.plugins.dialogue;

import com.zenyte.game.GameConstants;
import com.zenyte.game.content.achievementdiary.diaries.KandarinDiary;
import com.zenyte.game.content.achievementdiary.diaries.VarrockDiary;
import com.zenyte.game.content.skills.construction.constants.House;
import com.zenyte.game.item.Item;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.utils.TextUtils;

/**
 * Estate agents: move the house (every location in {@link House}, levels and prices per the OSRS wiki "Estate agent") and
 * redecorate it (the seven styles the house templates in GadScape's cache have).
 */
public class EstateAgentD extends Dialogue {

	/** Decoration index (as used by the house templates), name, Construction level, price. */
	private static final Object[][] STYLES = new Object[][] {
			{ 0, "Basic wood", 1, 5_000 },
			{ 1, "Basic stone", 10, 5_000 },
			{ 2, "Whitewashed stone", 20, 7_500 },
			{ 3, "Fremennik-style wood", 30, 10_000 },
			{ 4, "Tropical wood", 40, 15_000 },
			{ 5, "Fancy stone", 50, 25_000 },
			{ 6, "Deathly mansion", 1, 35_000 },
	};

	private static final int MOVE_PAGE = 6, STYLE_PAGE_1 = 11, STYLE_PAGE_2 = 12;
	private static final int MOVE_BASE = 100, STYLE_BASE = 200;

	public EstateAgentD(final Player player, final NPC npc) {
		super(player, npc);
	}

	@Override
	public void buildDialogue() {
		npc("Hello. Welcome to the " + GameConstants.SERVER_NAME + " Housing Agency!<br> What can I do for you?");
		options(TITLE, "Can you move my house please?", "Can you redecorate my house please?").onOptionOne(() -> setKey(5)).onOptionTwo(() -> setKey(10));
		npc(5, "Certainly. Where would you like it moved to?");
		final House[] houses = House.VALUES;
		// Pages of four locations each, the last option of a page leading to the next one (or back to the first).
		final int pages = (houses.length + 3) / 4;
		for (int page = 0; page < pages; page++) {
			final int from = page * 4;
			final int to = Math.min(houses.length, from + 4);
			final DialogueOption[] list = new DialogueOption[to - from + 1];
			for (int i = from; i < to; i++) {
				list[i - from] = moveOption(houses[i]);
			}
			final int next = page + 1 < pages ? MOVE_PAGE + page + 1 : MOVE_PAGE;
			list[to - from] = new DialogueOption(page + 1 < pages ? "More..." : "...Previous", () -> setKey(next));
			options(MOVE_PAGE + page, TITLE, list);
		}
		for (final House house : houses) {
			buildMove(house);
		}
		npc(10, "Certainly. My magic can rebuild the house in a<br> completely new style! What style would you like?");
		options(STYLE_PAGE_1, TITLE, styleOption(0), styleOption(1), styleOption(2), styleOption(3), new DialogueOption("More...", () -> setKey(STYLE_PAGE_2)));
		options(STYLE_PAGE_2, TITLE, styleOption(4), styleOption(5), styleOption(6), new DialogueOption("...Previous", () -> setKey(STYLE_PAGE_1)));
		for (int i = 0; i < STYLES.length; i++) {
			buildStyle(i);
		}
	}

	private static String name(final House house) {
		return house == House.KOUREND ? "Hosidius" : TextUtils.capitalize(house.name().toLowerCase());
	}

	private DialogueOption moveOption(final House house) {
		return new DialogueOption(name(house) + " (" + String.format("%,d", house.getPrice()) + ")", () -> setKey(MOVE_BASE + house.ordinal() * 5));
	}

	private void buildMove(final House house) {
		player(MOVE_BASE + house.ordinal() * 5, "To " + name(house) + " please!");
		if (player.getConstruction().getHouse() == house) {
			npc("Your house is already there!");
		} else if (player.getSkills().getLevelForXp(SkillConstants.CONSTRUCTION) < house.getLevel()) {
			npc("I'm afraid you don't have a high enough Construction<br> level to move there. You need to have level " + house.getLevel() + ".");
		} else if (player.getInventory().getAmountOf(995) < house.getPrice()) {
			npc("I'm afraid you don't have enough coins on you to move your house.");
		} else {
			npc("There you go! Your house was moved to " + name(house) + "!").executeAction(() -> {
				if (player.getInventory().getAmountOf(995) < house.getPrice()) {
					return;
				}
				player.getInventory().deleteItem(new Item(995, house.getPrice()));
				player.getConstruction().setHouse(house);
			});
		}
	}

	private DialogueOption styleOption(final int index) {
		return new DialogueOption(STYLES[index][1] + " (" + String.format("%,d", (int) STYLES[index][3]) + ")", () -> setKey(STYLE_BASE + index * 5));
	}

	private void buildStyle(final int index) {
		final int decoration = (int) STYLES[index][0];
		final String name = (String) STYLES[index][1];
		final int level = (int) STYLES[index][2];
		final int price = (int) STYLES[index][3];
		player(STYLE_BASE + index * 5, name + " please!");
		if (player.getConstruction().getDecoration() == decoration) {
			npc("Your house is already in that style!");
		} else if (player.getSkills().getLevelForXp(SkillConstants.CONSTRUCTION) < level) {
			npc("I'm afraid you need a Construction level of " + level + " for that style.");
		} else if (player.getInventory().getAmountOf(995) < price) {
			npc("I'm afraid you don't have enough coins on you to redecorate your house.");
		} else {
			npc("There you go! Your house was redecorated.").executeAction(() -> {
				if (player.getInventory().getAmountOf(995) < price) {
					return;
				}
				player.getInventory().deleteItem(new Item(995, price));
				player.getConstruction().setDecoration(decoration);
				if (decoration == 5) {
					player.getAchievementDiaries().update(VarrockDiary.DECORATE_HOUSE_WITH_FANCY_STONE);
					player.getAchievementDiaries().update(KandarinDiary.DECORATE_HOUSE_WITH_FANCY_STONE);
				}
			});
		}
	}

}

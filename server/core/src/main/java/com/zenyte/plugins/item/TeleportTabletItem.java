package com.zenyte.plugins.item;

import com.zenyte.game.content.itemtransportation.TeleportTablet;
import com.zenyte.game.item.Item;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.game.world.entity.player.Setting;
import com.zenyte.game.world.entity.player.Settings;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.plugins.dialogue.ItemChat;

import static com.zenyte.game.content.itemtransportation.TeleportTablet.TELEPORT_TO_HOUSE;

/**
 * @author Kris | 26. aug 2018 : 16:39:24
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public class TeleportTabletItem extends ItemPlugin {
	/** Wilderness crabs / Ice plateau tablets: "Configure" switches the Wilderness warning off and on (OSRS). */
	private static final String WILDERNESS_WARNING_OFF = "tablet_wilderness_warning_off";

	@Override
	public void handle() {
		bind("Break", (player, item, slotId) -> {
			TeleportTablet tablet = TeleportTablet.getTeleportTab(item);
			if (tablet == TELEPORT_TO_HOUSE) {
				// construction area: inside or outside by the house options setting.
				com.zenyte.game.content.skills.construction.HouseTeleport.TABLET.teleport(player, (String) null);
				return;
			}
			final Settings settings = player.getSettings();
			switch (tablet) {
			case VARROCK: 
				if (settings.valueOf(Setting.VARROCK_TELEPORT_CONFIGURATION) == 1) {
					tablet = TeleportTablet.GRAND_EXCHANGE;
				}
				break;
			case CAMELOT: 
				if (settings.valueOf(Setting.CAMELOT_TELEPORT_CONFIGURATION) == 1) {
					tablet = TeleportTablet.SEERS_VILLAGE;
				}
				break;
			case WATCHTOWER: 
				if (settings.valueOf(Setting.WATCHTOWER_TELEPORT_CONFIGURATION) == 1) {
					tablet = TeleportTablet.YANILLE_ALT;
				}
				break;
			}
			if ((tablet == TeleportTablet.WILDERNESS_CRABS || tablet == TeleportTablet.ICE_PLATEAU) && player.getNumericAttribute(WILDERNESS_WARNING_OFF).intValue() != 1) {
				final TeleportTablet destination = tablet;
				player.getDialogueManager().start(new Dialogue(player) {
					@Override
					public void buildDialogue() {
						plain("This tablet will teleport you into the Wilderness (level " + (destination == TeleportTablet.ICE_PLATEAU ? 53 : 33) + ").<br>Are you sure you wish to continue?");
						options("Teleport into the Wilderness?", "Yes, teleport me.", "No.").onOptionOne(() -> destination.teleport(player));
					}
				});
				return;
			}
			tablet.teleport(player);
		});
		/* House tablet right-click options (construction area). */
		bind("Inside", (player, item, slotId) -> com.zenyte.game.content.skills.construction.HouseTeleport.TABLET.teleport(player,
				com.zenyte.game.content.skills.magic.spells.teleports.structures.HouseStructure.INSIDE));
		bind("Outside", (player, item, slotId) -> com.zenyte.game.content.skills.construction.HouseTeleport.TABLET.teleport(player,
				com.zenyte.game.content.skills.magic.spells.teleports.structures.HouseStructure.OUTSIDE));
		bind("Group", (player, item, slotId) -> player.sendMessage("Only Group Ironmen can teleport to a group member's house."));
		bind("Configure", (player, item, slotId) -> {
			final boolean off = player.getNumericAttribute(WILDERNESS_WARNING_OFF).intValue() == 1;
			player.addAttribute(WILDERNESS_WARNING_OFF, off ? 0 : 1);
			player.sendMessage("The Wilderness warning of these tablets is now " + (off ? "on" : "off") + ".");
		});
		bind("Varrock", (player, item, slotId) -> TeleportTablet.VARROCK.teleport(player));
		bind("Grand Exchange", (player, item, slotId) -> TeleportTablet.GRAND_EXCHANGE.teleport(player));
		bind("Camelot", (player, item, slotId) -> TeleportTablet.CAMELOT.teleport(player));
		bind("Seers' Village", (player, item, slotId) -> TeleportTablet.SEERS_VILLAGE.teleport(player));
		bind("Watchtower", (player, item, slotId) -> TeleportTablet.WATCHTOWER.teleport(player));
		bind("Yanille", (player, item, slotId) -> TeleportTablet.YANILLE_ALT.teleport(player));
		bind("Revert", (player, item, slotId) -> {
			player.getDialogueManager().start(new Dialogue(player) {
				@Override
				public void buildDialogue() {
					options("Revert these tablets?", new DialogueOption("Revert ALL - you will not get your scrolls back.", () -> {
						player.getDialogueManager().finish();
						final int succeeded = player.getInventory().deleteItem(item).getSucceededAmount();
						if (succeeded > 0) {
							final Item houseTabs = new Item(8013, succeeded);
							player.getInventory().addOrDrop(houseTabs);
							player.getDialogueManager().start(new ItemChat(player, houseTabs, "You revert the tablets to their original form."));
						}
					}), new DialogueOption("Revert SOME - you will not get your scrolls back.", () -> {
						player.getDialogueManager().finish();
						player.sendInputInt("How many do you wish to revert? " + item.getAmount(), value -> {
							final int succeeded = player.getInventory().deleteItem(new Item(item.getId(), value)).getSucceededAmount();
							if (succeeded > 0) {
								final Item houseTabs = new Item(8013, succeeded);
								player.getInventory().addOrDrop(houseTabs);
								player.getDialogueManager().start(new ItemChat(player, houseTabs, "You revert the tablets to their original form."));
							}
						});
					}), new DialogueOption("No - leave them alone."));
				}
			});
		});
		bind("Toggle", (player, item, slotId) -> {
			final TeleportTablet tablet = TeleportTablet.getTeleportTab(item);
			String current;
			String target;
			Setting setting;
			int currentValue;
			switch (tablet) {
			case VARROCK: 
				currentValue = player.getSettings().valueOf(setting = Setting.VARROCK_TELEPORT_CONFIGURATION);
				current = currentValue == 0 ? "Varrock" : "Grand Exchange";
				target = currentValue == 0 ? "Grand Exchange" : "Varrock";
				break;
			case CAMELOT: 
				currentValue = player.getSettings().valueOf(setting = Setting.CAMELOT_TELEPORT_CONFIGURATION);
				current = currentValue == 0 ? "Camelot" : "Seers' Village";
				target = currentValue == 0 ? "Seers' Village" : "Camelot";
				break;
			case WATCHTOWER: 
				currentValue = player.getSettings().valueOf(setting = Setting.WATCHTOWER_TELEPORT_CONFIGURATION);
				current = currentValue == 0 ? "Watchtower" : "Yanille";
				target = currentValue == 0 ? "Yanille" : "Watchtower";
				break;
			default: 
				throw new IllegalStateException();
			}
			final String message = "Your default option is now the " + target + " teleport.";
			player.getDialogueManager().start(new Dialogue(player) {
				@Override
				public void buildDialogue() {
					options("Spellbook/portal default: " + current, new DialogueOption("Change to the " + target + ".", () -> {
						player.getSettings().toggleSetting(setting);
						player.sendMessage(message);
					}), new DialogueOption("Cancel."));
				}
			});
		});
	}

	@Override
	public int[] getItems() {
		return TeleportTablet.tabs.keySet().toIntArray();
	}
}

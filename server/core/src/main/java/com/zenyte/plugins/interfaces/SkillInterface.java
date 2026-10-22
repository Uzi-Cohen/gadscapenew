package com.zenyte.plugins.interfaces;

import com.zenyte.game.content.skills.construction.constants.Furniture;
import com.zenyte.game.item.Item;
import com.zenyte.game.model.ui.UserInterface;
import com.zenyte.game.world.entity.player.Player;
import mgi.types.config.items.ItemDefinitions;

/**
 * @author Kris | 10. nov 2017 : 18:04.10
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server
 *      profile</a>}
 */
public final class SkillInterface implements UserInterface {

	@Override
	public void handleComponentClick(final Player player, final int interfaceId, final int componentId, final int slotId, final int itemId, final int optionId, final String option) {
		if (player.getTemporaryAttributes().get("viewingSkill") == null) {
			return;
		}
		// Build 241 skill_guide (214): sections are switched client-side (script 9341); 211 sent varp 965 here, which is
		// the level-up popup list in 241. Only the close button (30, 211: 25) and the item icons (32, 211: 8; 241 sets no
		// "Check materials" op on them, so this only runs if a later cache adds it back) reach the server.
		if (componentId == 32) {
			final Furniture furn = Furniture.DISPLAYED_MAP.get(itemId);
			final ItemDefinitions defs = ItemDefinitions.get(itemId);
			if (furn == null) {
				player.sendMessage("Invalid furniture: " + itemId + "(" + (defs == null ? "null" : defs.getName()) + ")");
				return;
			}
			final StringBuilder builder = new StringBuilder();
			builder.append((defs == null ? "null" : defs.getName()) + ": ");
			for (final Item i : furn.getMaterials()) {
				String name = i.getName();
				if (name.equals("Bronze nails")) {
					name = "Nails";
				}
				builder.append("<col=ff0000>" + i.getAmount() + " x " + name + "</col>, ");
			}
			if (itemId == 8095) {
				builder.append("<col=ff0000>" + "1 x " + " of each of the four runes of the respective group" + "</col>, ");
			}
			builder.delete(builder.length() - 2, builder.length());
			player.sendMessage(builder.toString());
		} else if (componentId == 30) {
			player.getTemporaryAttributes().remove("viewingSkill");
			player.getInterfaceHandler().closeInterface(214);
		}

	}

	@Override
	public int[] getInterfaceIds() {
		return new int[] { 214 };
	}

}

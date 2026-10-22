package com.zenyte.game.content.skills.magic.resources;

import com.zenyte.game.content.skills.magic.Magic;
import com.zenyte.game.content.skills.magic.RuneContainer;
import com.zenyte.game.item.Item;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.plugins.item.TomeOfFire;

/**
 * @author Kris | 7. juuli 2018 : 13:40:50
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public final class TomeOfFireResource implements RuneResource {
	@Override
	public RuneContainer getContainer() {
		return RuneContainer.EQUIPMENT;
	}

	/**
	 * Every charged elemental tome is an infinite source of its own rune: Tome of Fire (fire), Tome of Water (water),
	 * Tome of Earth (earth). Charges are only used by the combat spells the tome boosts (ElementalTomes).
	 */
	@Override
	public int getAmountOf(final Player player, final int runeId, final int amountRequired) {
		final Item shield = player.getShield();
		if (shield == null || shield.getCharges() <= 0) {
			return 0;
		}
		final int id = shield.getId();
		final boolean source = id == TomeOfFire.TOME_OF_FIRE && runeId == Magic.FIRE_RUNE
				|| id == com.zenyte.game.item.ItemId.TOME_OF_WATER && runeId == Magic.WATER_RUNE
				|| id == com.zenyte.game.item.ItemId.TOME_OF_EARTH && runeId == Magic.EARTH_RUNE;
		return source ? amountRequired : 0;
	}

	@Override
	public void consume(final Player player, final int runeId, final int amount) {
	}

	@Override
	public int[] getAffectedRunes() {
		return new int[] {Magic.FIRE_RUNE, Magic.WATER_RUNE, Magic.EARTH_RUNE};
	}
}

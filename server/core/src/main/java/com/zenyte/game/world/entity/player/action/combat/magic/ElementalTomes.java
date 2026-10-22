package com.zenyte.game.world.entity.player.action.combat.magic;

import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot;

import java.util.EnumSet;

/**
 * Combat effects of the charged elemental tomes (OSRS wiki, Oct 2026). Each one is an infinite source of its rune
 * while it has a charge (TomeOfFireResource) and only boosts the standard spellbook's spells of its element:
 * <ul>
 * <li>Tome of Fire: fire spells +10% damage against NPCs, +50% against players (Project Rebalance, May 2024).</li>
 * <li>Tome of Water: water spells +10% accuracy and damage against NPCs, +20% against players; curses and binds
 * (Confuse ... Stun, Bind, Snare, Entangle) +20% accuracy.</li>
 * <li>Tome of Earth: earth spells +10% damage against NPCs.</li>
 * </ul>
 * A boosted cast uses one charge; other spells (or using the runes for non-combat spells) use none.
 */
public final class ElementalTomes {

    private ElementalTomes() {
    }

    private static final EnumSet<CombatSpell> FIRE = EnumSet.of(CombatSpell.FIRE_STRIKE, CombatSpell.FIRE_BOLT, CombatSpell.FIRE_BLAST, CombatSpell.FIRE_WAVE, CombatSpell.FIRE_SURGE);
    private static final EnumSet<CombatSpell> WATER = EnumSet.of(CombatSpell.WATER_STRIKE, CombatSpell.WATER_BOLT, CombatSpell.WATER_BLAST, CombatSpell.WATER_WAVE, CombatSpell.WATER_SURGE);
    private static final EnumSet<CombatSpell> EARTH = EnumSet.of(CombatSpell.EARTH_STRIKE, CombatSpell.EARTH_BOLT, CombatSpell.EARTH_BLAST, CombatSpell.EARTH_WAVE, CombatSpell.EARTH_SURGE);
    private static final EnumSet<CombatSpell> CURSES = EnumSet.of(CombatSpell.CONFUSE, CombatSpell.WEAKEN, CombatSpell.CURSE, CombatSpell.VULNERABILITY, CombatSpell.ENFEEBLE, CombatSpell.STUN, CombatSpell.BIND, CombatSpell.SNARE, CombatSpell.ENTANGLE);

    private static Item chargedTome(final Player player) {
        final Item shield = player.getEquipment().getItem(EquipmentSlot.SHIELD);
        if (shield == null || shield.getCharges() <= 0) {
            return null;
        }
        final int id = shield.getId();
        return id == ItemId.TOME_OF_FIRE || id == ItemId.TOME_OF_WATER || id == ItemId.TOME_OF_EARTH ? shield : null;
    }

    /** Damage multiplier for this cast (1 when no tome applies). */
    public static double damageMultiplier(final Player player, final Entity target, final CombatSpell spell) {
        final Item tome = chargedTome(player);
        if (tome == null || spell == null) {
            return 1;
        }
        final boolean pvp = target instanceof Player;
        return switch (tome.getId()) {
            case ItemId.TOME_OF_FIRE -> FIRE.contains(spell) ? (pvp ? 1.5 : 1.1) : 1;
            case ItemId.TOME_OF_WATER -> WATER.contains(spell) ? (pvp ? 1.2 : 1.1) : 1;
            case ItemId.TOME_OF_EARTH -> EARTH.contains(spell) && !pvp ? 1.1 : 1;
            default -> 1;
        };
    }

    /** Accuracy multiplier for this cast (Tome of Water only). */
    public static double accuracyMultiplier(final Player player, final Entity target, final CombatSpell spell) {
        final Item tome = chargedTome(player);
        if (tome == null || spell == null || tome.getId() != ItemId.TOME_OF_WATER) {
            return 1;
        }
        if (WATER.contains(spell)) {
            return target instanceof Player ? 1.2 : 1.1;
        }
        return CURSES.contains(spell) ? 1.2 : 1;
    }

    /** Uses one charge if the equipped tome boosted this cast. */
    public static void consumeCharge(final Player player, final CombatSpell spell) {
        final Item tome = chargedTome(player);
        if (tome == null || spell == null) {
            return;
        }
        final boolean boosted = switch (tome.getId()) {
            case ItemId.TOME_OF_FIRE -> FIRE.contains(spell);
            case ItemId.TOME_OF_WATER -> WATER.contains(spell) || CURSES.contains(spell);
            case ItemId.TOME_OF_EARTH -> EARTH.contains(spell);
            default -> false;
        };
        if (!boosted) {
            return;
        }
        if (tome.getId() == ItemId.TOME_OF_FIRE) {
            // degradable item entry: empties into the Tome of Fire (empty) at 0
            player.getChargesManager().removeCharges(tome, 1, player.getEquipment().getContainer(), EquipmentSlot.SHIELD.getSlot());
            return;
        }
        final int left = Math.max(0, tome.getCharges() - 1);
        tome.setCharges(left);
        if (left <= 0) {
            tome.setId(tome.getId() == ItemId.TOME_OF_WATER ? ItemId.TOME_OF_WATER_EMPTY : ItemId.TOME_OF_EARTH_EMPTY);
            player.getEquipment().refresh(EquipmentSlot.SHIELD.getSlot());
            player.sendMessage("Your tome has run out of pages.");
        }
    }
}

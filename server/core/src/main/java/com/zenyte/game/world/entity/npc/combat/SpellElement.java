package com.zenyte.game.world.entity.npc.combat;

import com.zenyte.game.world.entity.player.action.combat.magic.CombatSpell;
import org.jetbrains.annotations.Nullable;

/**
 * The element of a standard-spellbook elemental spell (Strike, Bolt, Blast, Wave and Surge of Wind, Water, Earth and
 * Fire). Elemental weakness applies only to these spells (OSRS wiki "Elemental weakness": it "does not include spells
 * which could be viewed as elemental, such as Flames of Zamorak, or spells from other spellbooks, such as Ice Rush").
 */
public enum SpellElement {
    AIR, WATER, EARTH, FIRE;

    /** The element of {@code spell}, or null when it is not a standard elemental spell. */
    @Nullable
    public static SpellElement of(@Nullable final CombatSpell spell) {
        return spell == null ? null : ofName(spell.name());
    }

    /**
     * The element of the {@link CombatSpell} constant named {@code spellName} (e.g. {@code "WATER_BOLT"}), or null.
     * Wind spells are AIR.
     */
    @Nullable
    public static SpellElement ofName(@Nullable final String spellName) {
        if (spellName == null) {
            return null;
        }
        final int split = spellName.indexOf('_');
        if (split <= 0) {
            return null;
        }
        final String tier = spellName.substring(split + 1);
        switch (tier) {
            case "STRIKE", "BOLT", "BLAST", "WAVE", "SURGE" -> {
            }
            default -> {
                return null;
            }
        }
        return switch (spellName.substring(0, split)) {
            case "WIND" -> AIR;
            case "WATER" -> WATER;
            case "EARTH" -> EARTH;
            case "FIRE" -> FIRE;
            default -> null;
        };
    }

    /** Ancient Magicks ice spells (Ice Rush, Burst, Blitz, Barrage). Xarth's corpses treat them like water (E17). */
    public static boolean isAncientIce(@Nullable final CombatSpell spell) {
        return spell != null && spell.name().startsWith("ICE_");
    }
}

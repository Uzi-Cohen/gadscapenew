package com.zenyte.game.world.entity.npc.combat;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The three ranged defence types an NPC can have (OSRS Monster Inspect "light / standard / heavy"): thrown weapons are
 * light, bows standard, crossbows, ballistae and chinchompas heavy. Raids 4 engine item E6.
 */
public enum RangedAttackType {
    LIGHT, STANDARD, HEAVY;

    /**
     * Classifies an attack from its ammunition (the {@code AmmunitionDefinitions} constant name, or any other
     * definition's name) and, when that is not decisive, the weapon name. Unknown attacks are {@link #STANDARD}
     * (spec §2.7 E6: "fallback: the standard value").
     *
     * @param ammunitionName the ammunition definition's name (e.g. {@code "RUNITE_BOLT"}), or null
     * @param weaponName     the weapon's item name, or null
     */
    public static RangedAttackType classify(@Nullable final String ammunitionName, @Nullable final String weaponName) {
        if (ammunitionName != null) {
            final String ammo = ammunitionName.toUpperCase(Locale.ROOT);
            if (ammo.contains("MORRIGANS")) {
                return LIGHT; // Morrigan's javelins and throwing axes are thrown, not ballista ammo
            }
            if (ammo.contains("CHINCHOMPA") || ammo.contains("BOLT") || ammo.contains("JAVELIN")) {
                return HEAVY;
            }
            if (ammo.contains("ARROW") || ammo.contains("CRAWS_BOW") || ammo.contains("WEBWEAVER")) {
                return STANDARD;
            }
            if (ammo.contains("DART") || ammo.contains("KNIFE") || ammo.contains("THROWNAXE") || ammo.contains("THROWING_AXE")
                    || ammo.contains("HOLY_WATER") || ammo.contains("MUD_PIE") || ammo.contains("TEPHRA")) {
                return LIGHT;
            }
        }
        if (weaponName != null) {
            final String weapon = weaponName.toLowerCase(Locale.ROOT);
            if (weapon.contains("crossbow") || weapon.contains("ballista") || weapon.contains("chinchompa")) {
                return HEAVY;
            }
            if (weapon.contains("dart") || weapon.contains("knife") || weapon.contains("knives") || weapon.contains("thrownaxe")
                    || weapon.contains("throwing axe") || weapon.contains("blowpipe") || weapon.contains("atlatl")
                    || weapon.contains("toktz-xil-ul") || weapon.contains("javelin")) {
                return LIGHT;
            }
        }
        return STANDARD;
    }
}

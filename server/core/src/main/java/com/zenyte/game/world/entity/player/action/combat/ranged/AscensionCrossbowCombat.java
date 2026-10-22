package com.zenyte.game.world.entity.player.action.combat.ranged;

import com.gadscape.game.world.entity.player.action.combat.AmmunitionDefinition;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.action.combat.ArchiveWeapons;
import com.zenyte.game.world.entity.player.action.combat.RangedCombat;

/**
 * Ascension crossbows (The Fractured Archive, OSRS 20 Oct 2026): dual-wielded, 3 ticks (2 on Rapid), range 6 (8 on
 * Longrange), Ascension bolts only. Against players Rapid stays at 3 ticks, like the Toxic blowpipe (First Rewards
 * Proposal Q&A). The shots alternate between the left and the right crossbow and the side is remembered across
 * targets (Mod Hend, 17 Oct 2026); the second crossbow is the right-hand item of the fire animations (34714).
 */
public final class AscensionCrossbowCombat extends RangedCombat {
    private static final String LAST_SIDE = "ascension crossbows left";

    public AscensionCrossbowCombat(final Entity target, final AmmunitionDefinition defs) {
        super(target, defs);
    }

    @Override
    protected void animate() {
        final boolean left = !player.getBooleanTemporaryAttribute(LAST_SIDE);
        player.getTemporaryAttributes().put(LAST_SIDE, left);
        final boolean pvp = target instanceof Player;
        final int animation = left
                ? (pvp ? ArchiveWeapons.CROSSBOWS_FIRE_LEFT : ArchiveWeapons.CROSSBOWS_FIRE_LEFT_PVN)
                : (pvp ? ArchiveWeapons.CROSSBOWS_FIRE_RIGHT : ArchiveWeapons.CROSSBOWS_FIRE_RIGHT_PVN);
        player.setAnimation(new Animation(animation));
    }

    @Override
    protected int getWeaponSpeed() {
        int speed = super.getWeaponSpeed();
        if (target instanceof Player && player.getCombatDefinitions().getStyle() == 1) {
            speed++;
        }
        return speed;
    }
}

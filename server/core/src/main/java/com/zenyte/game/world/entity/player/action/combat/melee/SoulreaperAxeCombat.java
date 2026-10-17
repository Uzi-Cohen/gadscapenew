package com.zenyte.game.world.entity.player.action.combat.melee;

import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.masks.Hit;
import com.zenyte.game.world.entity.masks.HitType;
import com.zenyte.game.world.entity.player.action.combat.MeleeCombat;
import com.zenyte.game.world.entity.player.action.combat.SpecialAttackScript;

/**
 * Soulreaper axe: normal attacks add a soul stack ({@link SoulreaperAxe}); the special, Behead, costs no special
 * energy and uses every stack: per stack +12% accuracy, +6% max hit, and the hit is at least 6% of the max per stack.
 * The stacks' Strength bonus is gone before Behead's hit is worked out (OSRS does the same).
 */
public final class SoulreaperAxeCombat extends MeleeCombat {
    private boolean beheaded;

    public SoulreaperAxeCombat(final Entity target) {
        super(target);
    }

    @Override
    public int processAfterMovement() {
        beheaded = false;
        final int delay = super.processAfterMovement();
        // the stack comes after the attack's damage, so that attack doesn't get it; Behead doesn't add one
        if (delay > 0 && !beheaded) {
            SoulreaperAxe.addStack(player);
        }
        return delay;
    }

    @Override
    protected int special() {
        if (!player.getCombatDefinitions().isUsingSpecial()) {
            return -2;
        }
        beheaded = true;
        final int stacks = SoulreaperAxe.takeStacks(player);
        sendSoundEffect();
        final double accuracy = 1 + 0.12 * stacks;
        final int maxHit = getMaxHit(player, 1, 1 + 0.06 * stacks, false);
        int damage = getRandomHit(player, target, maxHit, accuracy);
        final int minimum = (int) Math.floor(maxHit * 0.06 * stacks);
        if (damage > 0 && damage < minimum) {
            damage = minimum;
        }
        final Hit hit = new Hit(player, damage, HitType.MELEE);
        extra(hit);
        addPoisonTask(hit.getDamage(), 0);
        delayHit(0, hit);
        animate();
        player.getCombatDefinitions().setSpecial(false, true);
        resetFlag();
        checkIfShouldTerminate(HitType.MELEE);
        return SpecialAttackScript.WEAPON_SPEED;
    }
}

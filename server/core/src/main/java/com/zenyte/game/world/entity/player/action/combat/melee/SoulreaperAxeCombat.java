package com.zenyte.game.world.entity.player.action.combat.melee;

import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.masks.Graphics;
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities;
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot;
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
    private static final Animation BEHEAD_ANIMATION = new Animation(10173);
    private static final Graphics BEHEAD_GFX = new Graphics(2430);
    private static final Graphics BEHEAD_GFX_ORNAMENT = new Graphics(4008);
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
        player.sendFilteredMessage("Behead: " + stacks + " soul stack" + (stacks == 1 ? "" : "s") + " (+" + (stacks * 12) + "% accuracy, +" + (stacks * 6) + "% damage).");
        sendSoundEffect();
        final double accuracy = 1 + 0.12 * stacks;
        final int maxHit = getMaxHit(player, 1, 1 + 0.06 * stacks, false);
        // The minimum (6% of the max hit per stack) applies to every hit that passes the accuracy roll, also one that
        // would have rolled 0 damage.
        final int minimum = Math.min(maxHit, Math.max((int) Math.floor(maxHit * 0.06 * stacks), calculateMinimumHit(player, maxHit)));
        final int damage;
        if (CombatUtilities.isAlwaysTakeMaxHit(target, HitType.MELEE)) {
            damage = maxHit;
        } else {
            final int attackRoll = getAccuracy(player, target, accuracy);
            final int defenceRoll = getTargetDefenceRoll(player, target, player.getCombatDefinitions().getAttackType());
            sendDebug(attackRoll, defenceRoll, maxHit);
            final boolean accurate = isGuaranteedHit(target) // Raids 4 E8
                    || (attackRoll > 0 ? Utils.random(attackRoll) : 0) > (defenceRoll > 0 ? Utils.random(defenceRoll) : 0);
            damage = accurate ? Utils.random(minimum, maxHit) : 0;
        }
        final Hit hit = new Hit(player, damage, HitType.MELEE);
        extra(hit);
        addPoisonTask(hit.getDamage(), 0);
        delayHit(0, hit);
        // Behead: ancient_axe_special (10173) with the axe's own spotanim (2430, ornament 4008), as in the 2746 cache.
        player.setAnimation(BEHEAD_ANIMATION);
        player.setGraphics(player.getEquipment().getId(EquipmentSlot.WEAPON) == SoulreaperAxe.AXE ? BEHEAD_GFX : BEHEAD_GFX_ORNAMENT);
        player.getCombatDefinitions().setSpecial(false, true);
        resetFlag();
        checkIfShouldTerminate(HitType.MELEE);
        return SpecialAttackScript.WEAPON_SPEED;
    }
}

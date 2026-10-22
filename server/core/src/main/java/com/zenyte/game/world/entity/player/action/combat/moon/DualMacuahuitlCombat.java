package com.zenyte.game.world.entity.player.action.combat.moon;

import com.zenyte.game.model.item.degradableitems.DegradeType;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.masks.Graphics;
import com.zenyte.game.world.entity.masks.Hit;
import com.zenyte.game.world.entity.masks.HitType;
import com.zenyte.game.world.entity.npc.combatdefs.AttackType;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities;
import com.zenyte.game.world.entity.player.action.combat.MeleeCombat;
import com.zenyte.game.world.entity.player.action.combat.SpecialAttackScript;
import com.zenyte.game.world.entity.player.action.combat.SpecialType;
import com.zenyte.game.world.region.RegionArea;
import com.zenyte.game.world.region.area.plugins.PlayerCombatPlugin;

/**
 * Dual macuahuitl (OSRS wiki "Dual macuahuitl", "Blood Moon armour"):
 * <ul>
 *     <li>Every attack hits twice: the max hit is split, the first splat gets half (rounded down), the second the rest.
 *     Each splat rolls accuracy, but the second automatically misses when the first missed. Against monsters the
 *     second splat lands one tick after the first.</li>
 *     <li>Bloodrager (full Blood Moon set, broken pieces count): every successful splat rolls 0-99; 32 or lower makes the
 *     next attack come one tick sooner (two successful splats: the lower roll counts).</li>
 *     <li>Special Blood Infusion (25%, full set needed): costs 25% of current Hitpoints, both splats roll accuracy on
 *     their own, +25% accuracy, +25% max hit (before the split), and Bloodrager is guaranteed on a hit.</li>
 * </ul>
 */
public class DualMacuahuitlCombat extends MeleeCombat {

    static final Graphics BLOOD_INFUSION_GFX = new Graphics(2792);

    /** Set when the current attack triggered Bloodrager: the next attack comes one tick sooner. */
    private boolean bloodrager;

    public DualMacuahuitlCombat(final Entity target) {
        super(target);
    }

    @Override
    protected int special() {
        if (!player.getCombatDefinitions().isUsingSpecial()) {
            return -2;
        }
        if (!MoonSets.hasFullBloodMoon(player)) {
            player.sendMessage("You need to be wearing the full Blood Moon armour set to use this special attack.");
            player.getCombatDefinitions().setSpecial(false, true);
            return -2;
        }
        return useSpecial(player, SpecialType.MELEE);
    }

    @Override
    public int processAfterMovement() {
        if (!isWithinAttackDistance()) {
            return 0;
        }
        if (!canAttack()) {
            return -1;
        }
        final RegionArea area = player.getArea();
        if (area instanceof PlayerCombatPlugin) {
            ((PlayerCombatPlugin) area).onAttack(player, target, "Melee", null, false);
        }
        addAttackedByDelay(player, target);
        bloodrager = false;
        final int delay = special();
        if (delay != -2) {
            return delay == SpecialAttackScript.WEAPON_SPEED ? getSpeed() : delay;
        }
        sendSoundEffect();
        attack(1.0, 1.0, false);
        animate();
        player.getChargesManager().removeCharges(DegradeType.OUTGOING_HIT);
        resetFlag();
        checkIfShouldTerminate(HitType.MELEE);
        return getSpeed();
    }

    /** Blood Infusion: the special attack script of {@code SpecialAttack.BLOOD_INFUSION}. */
    public void bloodInfusion() {
        final int cost = player.getHitpoints() / 4;
        if (cost > 0) {
            player.applyHit(new Hit(cost, HitType.REGULAR));
        }
        player.setGraphics(BLOOD_INFUSION_GFX);
        sendSoundEffect();
        attack(1.25, 1.25, true);
        player.getChargesManager().removeCharges(DegradeType.OUTGOING_HIT);
        resetFlag();
        checkIfShouldTerminate(HitType.MELEE);
    }

    private void attack(final double accuracyModifier, final double damageModifier, final boolean infusion) {
        final AttackType type = player.getCombatDefinitions().getAttackType();
        final int maxHit = getMaxHit(player, 1, damageModifier, false);
        final int firstMax = maxHit / 2;
        final int secondMax = maxHit - firstMax;
        final boolean alwaysMax = CombatUtilities.isAlwaysTakeMaxHit(target, HitType.MELEE);
        final boolean firstSuccess = alwaysMax || isSuccessful(player, target, accuracyModifier, type);
        final boolean secondSuccess = alwaysMax || ((infusion || firstSuccess) && isSuccessful(player, target, accuracyModifier, type));
        final Hit first = new Hit(player, roll(firstSuccess, firstMax, alwaysMax), HitType.MELEE);
        final Hit second = new Hit(player, roll(secondSuccess, secondMax, alwaysMax), HitType.MELEE);
        extra(first);
        extra(second);
        addPoisonTask(first.getDamage(), 0);
        delayHit(0, first);
        delayHit(target instanceof Player ? 0 : 1, second);
        if ((firstSuccess || secondSuccess) && MoonSets.hasFullBloodMoon(player)) {
            if (infusion) {
                bloodrager = true;
            } else {
                int lowest = 99;
                if (firstSuccess) {
                    lowest = Math.min(lowest, Utils.random(99));
                }
                if (secondSuccess) {
                    lowest = Math.min(lowest, Utils.random(99));
                }
                bloodrager = lowest <= 32;
            }
        }
    }

    private int roll(final boolean success, final int max, final boolean alwaysMax) {
        if (!success || max <= 0) {
            return 0;
        }
        if (alwaysMax) {
            return max;
        }
        return Utils.random(Math.min(max, calculateMinimumHit(player, max)), max);
    }

    @Override
    protected int getSpeed() {
        final int speed = super.getSpeed();
        if (bloodrager) {
            bloodrager = false;
            return Math.max(1, speed - 1);
        }
        return speed;
    }
}

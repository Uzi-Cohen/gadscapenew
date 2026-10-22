package com.zenyte.game.world.entity.player.action.combat.moon;

import com.gadscape.game.content.custom.SlayerHelmetEffects;
import com.zenyte.game.content.boons.impl.MinionsMight;
import com.zenyte.game.content.boons.impl.SlayersSovereignty;
import com.zenyte.game.content.skills.prayer.Prayer;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.masks.Graphics;
import com.zenyte.game.world.entity.masks.Hit;
import com.zenyte.game.world.entity.masks.HitType;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.npc.combatdefs.AttackType;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.gadscape.game.world.entity.player.action.combat.AmmunitionDefinition;
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities;
import com.zenyte.game.world.entity.player.action.combat.RangedCombat;
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot;
import mgi.types.config.items.ItemDefinitions;

/**
 * Eclipse atlatl (OSRS wiki "Eclipse atlatl"): a two-handed "bow" that throws atlatl darts. Accuracy is the normal
 * ranged roll (Ranged level, ranged attack bonus), but the max hit uses the visible Strength level and the melee
 * strength bonus; ranged prayers (not melee ones) boost both, the void ranger helm counts, and the melee salve amulet /
 * black mask bonuses apply.
 * <ul>
 *     <li>Speed: 4 ticks, 3 on rapid. In PvP one tick slower unless the full Eclipse Moon set is worn (22 July 2026).</li>
 *     <li>Eclipse set effect: each successful hit has a 20% chance to inflict a burn ({@link EclipseBurn}).</li>
 *     <li>Special Eclipse (50%, full set, melee distance, no darts needed): magic-based (rolls against magic defence),
 *     +50% accuracy; consumes the target's remaining burn damage, which raises the max hit by that amount and the
 *     minimum hit by half of it (bonus capped at 50).</li>
 * </ul>
 */
public class EclipseAtlatlCombat extends RangedCombat {

    static final Animation ECLIPSE_ANIMATION = new Animation(11060);
    private static final Graphics ECLIPSE_CAST_GFX = new Graphics(2797);
    private static final Graphics ECLIPSE_IMPACT_GFX = new Graphics(2798);
    private static final int BURN_BONUS_CAP = 50;

    public EclipseAtlatlCombat(final Entity target, final AmmunitionDefinition defs) {
        super(target, defs);
    }

    /** The special is ready to be used: it is switched on and the full Eclipse Moon set is worn. */
    private boolean specialReady() {
        return player != null && player.getCombatDefinitions().isUsingSpecial() && MoonSets.hasFullEclipseMoon(player);
    }

    @Override
    protected boolean checkPreconditions() {
        if (specialReady()) {
            return true;
        }
        return super.checkPreconditions();
    }

    @Override
    protected boolean outOfAmmo() {
        return !specialReady() && (ammunition == null || super.outOfAmmo());
    }

    @Override
    protected int getAttackDistance() {
        return specialReady() ? 0 : super.getAttackDistance();
    }

    @Override
    public int processAfterMovement() {
        if (player.getCombatDefinitions().isUsingSpecial() && !MoonSets.hasFullEclipseMoon(player)) {
            player.sendMessage("You need to be wearing the full Eclipse Moon armour set to use this special attack.");
            player.getCombatDefinitions().setSpecial(false, true);
        }
        if (!player.getCombatDefinitions().isUsingSpecial() && (ammunition == null || !ammunition.isCompatible(player.getEquipment().getId(EquipmentSlot.WEAPON)))) {
            player.sendMessage(ammunition == null ? "You don't have any ammunition." : "You cannot use that ammunition with this weapon.");
            return -1;
        }
        return super.processAfterMovement();
    }

    @Override
    protected void extra(final Hit hit) {
        super.extra(hit);
        if (hit.getDamage() > 0 && MoonSets.hasFullEclipseMoon(player) && Utils.random(4) == 0) {
            EclipseBurn.apply(player, target);
        }
    }

    @Override
    protected int getWeaponSpeed() {
        int speed = super.getWeaponSpeed();
        if (target instanceof Player && !MoonSets.hasFullEclipseMoon(player)) {
            speed++;
        }
        return speed;
    }

    @Override
    public int getMaxHit(final Player player, final double specialModifier, final double activeModifier, final boolean ignorePrayers) {
        float boost = CombatUtilities.hasFullRangedVoid(player, true) ? 1.125F : CombatUtilities.hasFullRangedVoid(player, false) ? 1.1F : 1.0F;
        if (CombatUtilities.applyForinthrySurge(player, target)) {
            boost += 0.15F;
        }
        if (CombatUtilities.applyPvmArenaBoost(player, target)) {
            boost += 0.05F;
        }
        boost += determineBountyHunterDmgBoost(player, target);
        final double effectiveStrength = (Math.floor(player.getSkills().getLevel(SkillConstants.STRENGTH) * player.getPrayerManager().getRangedBoost(SkillConstants.STRENGTH))
                + (player.getCombatDefinitions().getStyle() == 0 ? 3 : 0) + 8) * boost;
        final float meleeStrength = (float) player.getBonuses().getBonus(10);
        double result = Math.floor(0.5F + effectiveStrength * (meleeStrength + 64.0F) / 640.0F);
        final int amuletId = player.getEquipment().getId(EquipmentSlot.AMULET);
        final String amuletName = ItemDefinitions.nameOf(amuletId).toLowerCase();
        if (amuletId == ItemId.AMULET_OF_AVARICE && CombatUtilities.isRevenant(target)) {
            result *= 1.2F;
        } else if (amuletName.startsWith("salve amulet") && (CombatUtilities.SALVE_AFFECTED_NPCS.contains(name) || CombatUtilities.isUndeadCombatDummy(target))) {
            result *= (amuletId == 4081 || amuletId == 12017) ? (7.0F / 6.0F) : 1.2F;
        }
        final boolean hasTask = (player.getSlayer().isCurrentAssignment(target) || player.hasBoon(SlayersSovereignty.class)) || CombatUtilities.isCombatDummy(target);
        result *= determineSlayerHelmetDamageBoost(hasTask, HitType.MELEE, player, target);
        result = Math.floor(result);
        if (target instanceof NPC npc && MinionsMight.shouldBoostCombat(player, npc)) {
            result *= 1.1F;
        }
        if (!ignorePrayers && target instanceof Player && ((Player) target).getPrayerManager().isActive(Prayer.PROTECT_FROM_MISSILES)) {
            result *= target.getRangedPrayerMultiplier();
        }
        result *= specialModifier;
        result = SlayerHelmetEffects.INSTANCE.rollBonusDamage(player, target, result);
        return (int) Math.floor(result);
    }

    /** The Eclipse special: the script of {@code SpecialAttack.ECLIPSE}. */
    public void eclipse() {
        player.setGraphics(ECLIPSE_CAST_GFX);
        final int burn = Math.min(BURN_BONUS_CAP, EclipseBurn.consume(target));
        final int maxHit = getMaxHit(player, 1.0, 1.0, false) + burn;
        final boolean alwaysMax = CombatUtilities.isAlwaysTakeMaxHit(target, HitType.RANGED);
        final int accuracy = getAccuracy(player, target, 1.5);
        final int defence = getTargetDefenceRoll(player, target, AttackType.MAGIC);
        final boolean success = alwaysMax || Utils.random(accuracy) > Utils.random(defence);
        int damage = 0;
        if (success) {
            damage = alwaysMax ? maxHit : Utils.random(Math.min(maxHit, Math.max(burn / 2, calculateMinimumHit(player, maxHit))), maxHit);
        }
        final Hit hit = new Hit(player, damage, HitType.RANGED);
        super.extra(hit);
        target.setGraphics(ECLIPSE_IMPACT_GFX);
        delayHit(0, hit);
        resetFlag();
        checkIfShouldTerminate(HitType.RANGED);
    }
}

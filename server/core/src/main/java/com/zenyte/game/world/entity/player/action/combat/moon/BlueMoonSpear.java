package com.zenyte.game.world.entity.player.action.combat.moon;

import com.zenyte.game.content.boons.impl.SlayersSovereignty;
import com.zenyte.game.content.skills.prayer.Prayer;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.WorldThread;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.masks.Graphics;
import com.zenyte.game.world.entity.masks.Hit;
import com.zenyte.game.world.entity.masks.HitType;
import com.zenyte.game.world.entity.npc.combatdefs.AttackType;
import com.zenyte.game.world.entity.player.CombatDefinitions;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities;
import com.zenyte.game.world.entity.player.action.combat.MeleeCombat;
import com.zenyte.game.world.entity.player.action.combat.PlayerCombat;
import com.zenyte.game.world.entity.player.action.combat.SpecialType;
import com.zenyte.game.world.entity.player.action.combat.magic.CombatSpell;
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot;

/**
 * Blue Moon spear (OSRS wiki "Blue Moon spear"):
 * <ul>
 *     <li>Bladed staff: autocasts standard elemental, Arceuus and Ancient combat spells; +5% magic damage (item stats).</li>
 *     <li>Frostweaver (full Blue Moon set): after a successful Bind/Snare/Entangle or Ice spell 20% chance, after a
 *     Grasp spell 50% chance, of an instant melee attack with no action delay. It needs melee distance but ignores
 *     diagonals and small walls. With the special switched on (and the energy), that attack is Break Shackles.</li>
 *     <li>Special Break Shackles (50%, full set): +1.5% accuracy and damage for every tick the target is still bound
 *     (damage boost capped at +112.5%, accuracy uncapped), then the bind is removed. Uses the Fend animation.</li>
 * </ul>
 */
public final class BlueMoonSpear {

    private BlueMoonSpear() {
    }

    public static final Animation BREAK_SHACKLES_ANIMATION = new Animation(1710);
    static final Graphics BREAK_SHACKLES_GFX = new Graphics(2793);
    private static final Graphics FROSTWEAVER_GFX = new Graphics(2791);
    private static final Animation FROSTWEAVER_ANIMATION = new Animation(1711);
    private static final double BONUS_PER_TICK = 0.015;
    private static final double DAMAGE_BONUS_CAP = 1.125;

    /** Remaining ticks the target stays bound. */
    static int boundTicks(final Entity target) {
        return (int) Math.max(0, target.getFreezeDelay() - WorldThread.getCurrentCycle());
    }

    private static void unbind(final Entity target) {
        if (boundTicks(target) > 0) {
            target.forceFreezeDelay(0);
        }
    }

    private static boolean isBindingSpell(final CombatSpell spell) {
        return switch (spell) {
            case BIND, SNARE, ENTANGLE, ICE_RUSH, ICE_BURST, ICE_BLITZ, ICE_BARRAGE -> true;
            default -> false;
        };
    }

    private static boolean isGraspSpell(final CombatSpell spell) {
        return spell == CombatSpell.GHOSTLY_GRASP || spell == CombatSpell.SKELETAL_GRASP || spell == CombatSpell.UNDEAD_GRASP;
    }

    private static boolean withinMeleeDistance(final Player player, final Entity target) {
        if (player.getPlane() != target.getPlane()) {
            return false;
        }
        final Location tile = target.getLocation();
        final int distanceX = player.getX() - tile.getX();
        final int distanceY = player.getY() - tile.getY();
        final int size = target.getSize();
        return distanceX <= size && distanceX >= -1 && distanceY <= size && distanceY >= -1;
    }

    /** Frostweaver: called by MagicCombat after every cast. */
    public static void onSpellCast(final Player player, final Entity target, final CombatSpell spell, final boolean splash) {
        if (splash || spell == null || target == null || target.isDead() || target.isFinished()) {
            return;
        }
        if (!MoonSets.isBlueMoonSpear(player.getEquipment().getId(EquipmentSlot.WEAPON)) || !MoonSets.hasFullBlueMoon(player)) {
            return;
        }
        final int chance = isGraspSpell(spell) ? 50 : isBindingSpell(spell) ? 20 : 0;
        if (chance == 0 || Utils.random(99) >= chance || !withinMeleeDistance(player, target)) {
            return;
        }
        final FreeHit combat = new FreeHit(target);
        combat.setPlayer(player);
        if (player.getCombatDefinitions().isUsingSpecial() && combat.spendSpecialEnergy()) {
            player.setAnimation(BREAK_SHACKLES_ANIMATION);
            breakShackles(player, combat, target);
            return;
        }
        player.setAnimation(FROSTWEAVER_ANIMATION);
        player.setGraphics(FROSTWEAVER_GFX);
        combat.delayHit(0, combat.freeHit(1.0, 1.0));
    }

    /** Break Shackles: the script of {@code SpecialAttack.BREAK_SHACKLES} (and of a Frostweaver hit with the special on). */
    public static void breakShackles(final Player player, final PlayerCombat combat, final Entity target) {
        final int bound = boundTicks(target);
        final double accuracy = 1 + BONUS_PER_TICK * bound;
        final double damage = 1 + Math.min(DAMAGE_BONUS_CAP, BONUS_PER_TICK * bound);
        final Hit hit = combat instanceof FreeHit free ? free.freeHit(accuracy, damage)
                : combat.getHit(player, target, accuracy, 1, damage, false);
        player.setGraphics(BREAK_SHACKLES_GFX);
        unbind(target);
        combat.delayHit(0, hit);
    }

    /** Melee combat with the spear (no autocast): checks the set before the special is used. */
    public static final class Combat extends MeleeCombat {
        public Combat(final Entity target) {
            super(target);
        }

        @Override
        protected int special() {
            if (!player.getCombatDefinitions().isUsingSpecial()) {
                return -2;
            }
            if (!MoonSets.hasFullBlueMoon(player)) {
                player.sendMessage("You need to be wearing the full Blue Moon armour set to use this special attack.");
                player.getCombatDefinitions().setSpecial(false, true);
                return -2;
            }
            return useSpecial(player, SpecialType.MELEE);
        }
    }

    /**
     * A melee attack made outside the melee combat action (Frostweaver, usually while autocasting): stab with the
     * spear's melee bonuses, no style bonus.
     */
    static final class FreeHit extends MeleeCombat {
        FreeHit(final Entity target) {
            super(target);
        }

        /** Uses the special energy of the spear's special (enum 906: 50%); false when there is not enough. */
        boolean spendSpecialEnergy() {
            final CombatDefinitions definitions = player.getCombatDefinitions();
            final int required = getRequiredSpecial(player);
            if (definitions.getSpecialEnergy() < required) {
                return false;
            }
            definitions.setSpecial(false, true);
            definitions.setSpecialEnergy(definitions.getSpecialEnergy() - required);
            return true;
        }

        Hit freeHit(final double accuracyModifier, final double damageModifier) {
            final boolean hasTask = player.getSlayer().isCurrentAssignment(target) || player.hasBoon(SlayersSovereignty.class) || CombatUtilities.isCombatDummy(target);
            final float voidBoost = CombatUtilities.hasFullMeleeVoid(player, false) || CombatUtilities.hasFullMeleeVoid(player, true) ? 1.1F : 1.0F;
            final double effectiveAttack = Math.floor(Math.floor(player.getSkills().getLevel(SkillConstants.ATTACK) * player.getPrayerManager().getSkillBoost(SkillConstants.ATTACK)) + 8) * voidBoost;
            double attackRoll = effectiveAttack * (player.getBonuses().getBonus(AttackType.STAB.ordinal()) + 64);
            attackRoll *= determineSlayerHelmetAccuracyBoost(hasTask, HitType.MELEE, player, target);
            attackRoll = Math.floor(attackRoll * accuracyModifier);
            final double effectiveStrength = Math.floor(Math.floor(player.getSkills().getLevel(SkillConstants.STRENGTH) * player.getPrayerManager().getSkillBoost(SkillConstants.STRENGTH)) + 8) * voidBoost;
            double maxHit = Math.floor(0.5 + effectiveStrength * (player.getBonuses().getBonus(10) + 64) / 640.0);
            maxHit = Math.floor(maxHit * determineSlayerHelmetDamageBoost(hasTask, HitType.MELEE, player, target));
            if (target instanceof Player p && p.getPrayerManager().isActive(Prayer.PROTECT_FROM_MELEE)) {
                maxHit = Math.floor(maxHit * target.getMeleePrayerMultiplier());
            }
            maxHit = Math.floor(maxHit * damageModifier);
            final int max = (int) maxHit;
            final boolean alwaysMax = CombatUtilities.isAlwaysTakeMaxHit(target, HitType.MELEE);
            final int defenceRoll = getTargetDefenceRoll(player, target, AttackType.STAB);
            final boolean success = alwaysMax || Utils.random((int) attackRoll) > Utils.random(defenceRoll);
            final int damage = !success || max <= 0 ? 0 : alwaysMax ? max : Utils.random(Math.min(max, calculateMinimumHit(player, max)), max);
            return new Hit(player, damage, HitType.MELEE);
        }
    }
}

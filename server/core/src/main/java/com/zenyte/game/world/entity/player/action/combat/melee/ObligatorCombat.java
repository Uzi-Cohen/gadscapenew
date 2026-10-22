package com.zenyte.game.world.entity.player.action.combat.melee;

import com.zenyte.game.item.Item;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.masks.Graphics;
import com.zenyte.game.world.entity.masks.Hit;
import com.zenyte.game.world.entity.masks.HitType;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.npc.combatdefs.AttackType;
import com.zenyte.game.world.entity.npc.combatdefs.StatType;
import com.zenyte.game.world.entity.player.Bonuses;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.action.combat.ArchiveWeapons;
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities;
import com.zenyte.game.world.entity.player.action.combat.GadScapeWeaponIds;
import com.zenyte.game.world.entity.player.action.combat.MeleeCombat;
import com.zenyte.game.world.entity.player.action.combat.SpecialAttackScript;
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot;
import com.zenyte.game.world.region.CharacterLoop;
import com.zenyte.game.world.region.RegionArea;
import com.zenyte.game.world.region.area.plugins.PlayerCombatPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * The Obligator (The Fractured Archive, OSRS 20 Oct 2026), category "Extending chain mace" (client 36):
 * <ul>
 * <li>Thrust (Accurate / Aggressive), Crush, 5 ticks: one accuracy roll, then damage is rolled once, plus 2 more
 * times if the target's Stab defence is higher than its Crush defence and 2 more if its Slash defence is (5 rolls
 * against an enemy weakest to Crush, OSRS wiki), and the best roll is kept.
 * The max hit is +20% against 2x2 targets and +40% against 3x3 or larger. Range 1, but the chain goes on two tiles
 * past the first target: up to 3 targets on that line are hit (no size bonus for the extra ones), only in multi-combat
 * and only when no 3x3-or-larger enemy is in the way.</li>
 * <li>Smash (Aggressive), Crush, 3 ticks: one normal hit, no re-rolls, no size bonus, one target.</li>
 * <li>Each attack uses a charge. With no charges it is the Obligator (uncharged): it still attacks, with plain hits.</li>
 * </ul>
 * GadScape's own Obligator copies ({@link GadScapeWeaponIds#isGadScapeObligator}) have no charges and always count as
 * charged.
 */
public final class ObligatorCombat extends MeleeCombat {
    private static final int SMASH_STYLE = 2;

    public ObligatorCombat(final Entity target) {
        super(target);
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
        final int specialDelay = special();
        if (specialDelay != -2) {
            return specialDelay == SpecialAttackScript.WEAPON_SPEED ? getSpeed() : specialDelay;
        }
        sendSoundEffect();
        final boolean charged = isCharged();
        final boolean smash = player.getCombatDefinitions().getStyle() >= SMASH_STYLE;
        if (smash || !charged) {
            final Hit hit = getHit(player, target, 1, 1, 1, false);
            extra(hit);
            delayHit(0, hit);
        } else {
            thrust();
        }
        animate(smash, charged);
        if (charged) {
            useCharge();
        }
        resetFlag();
        checkIfShouldTerminate(HitType.MELEE);
        return getSpeed();
    }

    private void thrust() {
        final Entity primary = target;
        final List<Entity> others = lineTargets(primary);
        final double sizeBonus = primary.getSize() >= 3 ? 1.4 : primary.getSize() == 2 ? 1.2 : 1;
        final Hit primaryHit = new Hit(player, rollThrust(primary, sizeBonus), HitType.MELEE);
        extra(primaryHit);
        delayHit(primary, 0, primaryHit);
        for (final Entity other : others) {
            target = other;
            try {
                final Hit hit = new Hit(player, rollThrust(other, 1), HitType.MELEE);
                extra(hit);
                delayHit(other, 0, hit);
            } finally {
                target = primary;
            }
        }
    }

    /** One accuracy roll against Crush; on a hit the best of 2-4 damage rolls. */
    private int rollThrust(final Entity victim, final double sizeBonus) {
        final int maxHit = getMaxHit(player, 1, sizeBonus, false);
        if (CombatUtilities.isAlwaysTakeMaxHit(victim, HitType.MELEE)) {
            return maxHit;
        }
        final int accuracy = getAccuracy(player, victim, 1);
        final int defence = getTargetDefenceRoll(player, victim, AttackType.CRUSH);
        sendDebug(accuracy, defence, maxHit);
        if ((accuracy > 0 ? Utils.random(accuracy) : 0) <= (defence > 0 ? Utils.random(defence) : 0)) {
            return 0;
        }
        final int rolls = damageRolls(victim);
        final int minimum = Math.min(calculateMinimumHit(player, maxHit), maxHit);
        int best = 0;
        for (int i = 0; i < rolls; i++) {
            best = Math.max(best, Utils.random(minimum, maxHit));
        }
        return best;
    }

    /** 1, plus 2 for each of Stab and Slash defence that is higher than Crush defence (5 at most). */
    public static int damageRolls(final Entity victim) {
        final int stab;
        final int slash;
        final int crush;
        if (victim instanceof final NPC npc) {
            stab = npc.getCombatDefinitions().getStatDefinitions().get(StatType.DEFENCE_STAB);
            slash = npc.getCombatDefinitions().getStatDefinitions().get(StatType.DEFENCE_SLASH);
            crush = npc.getCombatDefinitions().getStatDefinitions().get(StatType.DEFENCE_CRUSH);
        } else if (victim instanceof final Player p) {
            stab = p.getBonuses().getBonus(Bonuses.Bonus.DEF_STAB);
            slash = p.getBonuses().getBonus(Bonuses.Bonus.DEF_SLASH);
            crush = p.getBonuses().getBonus(Bonuses.Bonus.DEF_CRUSH);
        } else {
            return 1;
        }
        return 1 + (stab > crush ? 2 : 0) + (slash > crush ? 2 : 0);
    }

    /**
     * Up to two more enemies on the three tiles in front of the player, on the line through the target. None when the
     * target is not in multi-combat or a 3x3-or-larger enemy is on that line.
     */
    private List<Entity> lineTargets(final Entity primary) {
        final List<Entity> list = new ArrayList<>(2);
        if (primary.getSize() >= 3 || !primary.isMultiArea()) {
            return list;
        }
        final int size = primary.getSize();
        final int stepX = player.getX() < primary.getX() ? 1 : player.getX() >= primary.getX() + size ? -1 : 0;
        final int stepY = stepX != 0 ? 0 : player.getY() < primary.getY() ? 1 : -1;
        final int plane = player.getPlane();
        final Location origin = player.getLocation();
        final List<Entity> nearby = CharacterLoop.find(primary.getLocation(), 3, Entity.class,
                e -> e != player && e != primary && isPotentialTarget(player, primary.getLocation(), e));
        for (int step = 1; step <= 3 && list.size() < 2; step++) {
            final int x = origin.getX() + stepX * step;
            final int y = origin.getY() + stepY * step;
            for (final Entity e : nearby) {
                if (list.contains(e) || e.getPlane() != plane || !occupies(e, x, y)) {
                    continue;
                }
                if (e.getSize() >= 3) {
                    list.clear();
                    return list;
                }
                if (e instanceof final Player p && !player.canHit(p)) {
                    continue;
                }
                list.add(e);
                if (list.size() >= 2) {
                    break;
                }
            }
        }
        return list;
    }

    private static boolean occupies(final Entity e, final int x, final int y) {
        final int size = e.getSize();
        return x >= e.getX() && x < e.getX() + size && y >= e.getY() && y < e.getY() + size;
    }

    private boolean isCharged() {
        final Item weapon = player.getWeapon();
        if (weapon == null) {
            return false;
        }
        if (GadScapeWeaponIds.isGadScapeObligator(weapon.getId())) {
            return true;
        }
        return weapon.getId() == ArchiveWeapons.OBLIGATOR && weapon.getCharges() > 0;
    }

    private void useCharge() {
        final Item weapon = player.getWeapon();
        if (weapon == null || weapon.getId() != ArchiveWeapons.OBLIGATOR) {
            return;
        }
        final int left = Math.max(0, weapon.getCharges() - 1);
        weapon.setCharges(left);
        player.getVarManager().sendBit(ArchiveWeapons.OBLIGATOR_CHARGES_VARBIT, Math.min(left, 0xFFFF));
        if (left <= 0) {
            weapon.setId(ArchiveWeapons.OBLIGATOR_UNCHARGED);
            weapon.setCharges(0);
            player.getEquipment().refresh(EquipmentSlot.WEAPON.getSlot());
            player.sendMessage("Your Obligator has run out of charges.");
        }
    }

    private void animate(final boolean smash, final boolean charged) {
        final boolean male = player.getAppearance().isMale();
        final int animation = smash
                ? (male ? ArchiveWeapons.OBLIGATOR_ATTACK : ArchiveWeapons.OBLIGATOR_ATTACK_FEMALE)
                : (male ? ArchiveWeapons.OBLIGATOR_MULTIHIT : ArchiveWeapons.OBLIGATOR_MULTIHIT_FEMALE);
        player.setAnimation(new Animation(animation));
        if (!smash) {
            final int gfx = charged
                    ? (male ? ArchiveWeapons.OBLIGATOR_CHARGED_THRUST_GFX : ArchiveWeapons.OBLIGATOR_CHARGED_THRUST_GFX_FEMALE)
                    : (male ? ArchiveWeapons.OBLIGATOR_UNCHARGED_THRUST_GFX : ArchiveWeapons.OBLIGATOR_UNCHARGED_THRUST_GFX_FEMALE);
            player.setGraphics(new Graphics(gfx));
        }
    }

    @Override
    protected int getSpeed() {
        if (player.getCombatDefinitions().getStyle() >= SMASH_STYLE) {
            return adjustAttackSpeed(player, 2);
        }
        return super.getSpeed();
    }
}

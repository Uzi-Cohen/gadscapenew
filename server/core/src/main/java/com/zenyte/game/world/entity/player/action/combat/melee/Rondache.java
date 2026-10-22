package com.zenyte.game.world.entity.player.action.combat.melee;

import com.google.common.eventbus.Subscribe;
import com.zenyte.game.content.skills.prayer.Prayer;
import com.zenyte.game.task.WorldTask;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.util.ProjectileUtils;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.WorldThread;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.masks.Graphics;
import com.zenyte.game.world.entity.masks.Hit;
import com.zenyte.game.world.entity.masks.HitType;
import com.zenyte.game.world.entity.npc.combatdefs.AttackType;
import com.zenyte.game.world.entity.player.Action;
import com.zenyte.game.world.entity.player.Bonuses;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.zenyte.game.world.entity.player.action.combat.ArchiveWeapons;
import com.zenyte.game.world.entity.player.action.combat.AttackStyle;
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities;
import com.zenyte.game.world.entity.player.action.combat.PlayerCombat;
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot;
import com.zenyte.plugins.events.LoginEvent;
import mgi.types.config.items.ItemDefinitions;

/**
 * The Rondache (34719), The Fractured Archive (OSRS, 20 Oct 2026).
 * <ul>
 * <li>+2 flat armour and magic resistance: every Melee, Ranged and Magic hit taken (not typeless damage) is 2 lower.</li>
 * <li>The damage it stops becomes charges, up to 20 (player varbit rondache_charges 16438, which the 2746 client draws
 * as the Rondache special attack bar and as the small bar under the special attack orb).</li>
 * <li>Shield Bash (the off-hand special attack, sa_attack = 2): instant like the granite maul special, it uses every
 * charge x and deals x + x% damage (floor(x * (100 + x) / 100): 20 charges = 24, 16 = 18) when it hits. Accuracy is a
 * Crush roll using the Crush bonus of all gear except the weapon, +6 per charge, against the target's Crush defence.
 * Experience goes to the selected attack style. After a bash no charges are gained for a short while (the flat armour
 * keeps working).</li>
 * </ul>
 * Not published by Jagex, so chosen here: the post-bash cooldown ({@link #BASH_COOLDOWN_TICKS}) and the minimum charges
 * for a bash, which follows the 2746 client: enum 906 gives the Rondache a cost of 10 and the bar only turns green
 * (usable) from 10 charges.
 */
public final class Rondache {
    public static final int MAX_CHARGES = 20;
    public static final int FLAT_ARMOUR = 2;
    /** Unpublished ("a short cooldown"): 10 ticks. */
    public static final int BASH_COOLDOWN_TICKS = 10;
    private static final int DEFAULT_MINIMUM_CHARGES = 10;
    private static final String CHARGES = "rondache charges";
    private static final String COOLDOWN = "rondache bash cooldown";
    private static final String WATCH_TASK = "rondache bash watch";
    private static final Animation BASH_ANIMATION = new Animation(ArchiveWeapons.RONDACHE_SPECIAL_ANIM);
    private static final Graphics BASH_GRAPHICS = new Graphics(ArchiveWeapons.RONDACHE_SPECIAL_GFX);

    private Rondache() {
    }

    public static boolean isWearing(final Player player) {
        return player.getEquipment().getId(EquipmentSlot.SHIELD) == ArchiveWeapons.RONDACHE;
    }

    public static int charges(final Player player) {
        return Math.max(0, Math.min(MAX_CHARGES, player.getNumericAttribute(CHARGES).intValue()));
    }

    public static void setCharges(final Player player, final int charges) {
        final int value = Math.max(0, Math.min(MAX_CHARGES, charges));
        player.addAttribute(CHARGES, value);
        player.getVarManager().sendBit(ArchiveWeapons.RONDACHE_CHARGES_VARBIT, value);
    }

    @Subscribe
    public static void onLogin(final LoginEvent event) {
        final Player player = event.getPlayer();
        final int charges = charges(player);
        if (charges > 0) {
            player.getVarManager().sendBit(ArchiveWeapons.RONDACHE_CHARGES_VARBIT, charges);
        }
    }

    /**
     * Flat armour: called for every Melee, Ranged or Magic hit the wearer takes, after the other damage reducers.
     * Returns the damage left; the damage taken off becomes charges unless a bash was used very recently.
     */
    public static int mitigate(final Player player, final int damage) {
        if (damage <= 0) {
            return damage;
        }
        final int reduced = Math.min(FLAT_ARMOUR, damage);
        final long cooldownEnd = player.getNumericTemporaryAttribute(COOLDOWN).longValue();
        if (WorldThread.getCurrentCycle() >= cooldownEnd) {
            final int charges = charges(player);
            if (charges < MAX_CHARGES) {
                setCharges(player, charges + reduced);
            }
        }
        return damage - reduced;
    }

    /** The fewest charges a Shield Bash needs: the Rondache's special cost in enum 906 (10 in 2746). */
    public static int minimumCharges() {
        final int cost = PlayerCombat.getSpecialCost(ArchiveWeapons.RONDACHE);
        return cost > 0 ? Math.min(cost, MAX_CHARGES) : DEFAULT_MINIMUM_CHARGES;
    }

    /**
     * The off-hand special was selected. Shield Bash goes off at once if the player is next to their target, else on
     * the first tick that they are, as long as it stays selected and the Rondache stays equipped.
     */
    public static void onSelected(final Player player) {
        final int charges = charges(player);
        if (charges < minimumCharges()) {
            player.sendMessage("Your rondache needs at least " + minimumCharges() + " charges to use Shield Slam. It has " + charges + ".");
            player.getCombatDefinitions().setOffhandSpecial(false);
            return;
        }
        if (tryBash(player)) {
            return;
        }
        if (player.getTemporaryAttributes().containsKey(WATCH_TASK)) {
            return;
        }
        final WorldTask task = new WorldTask() {
            @Override
            public void run() {
                if (player.isFinished() || player.isNulled() || !player.getCombatDefinitions().isUsingOffhandSpecial()) {
                    player.getTemporaryAttributes().remove(WATCH_TASK);
                    stop();
                    return;
                }
                if (!isWearing(player)) {
                    player.getCombatDefinitions().setOffhandSpecial(false);
                    player.getTemporaryAttributes().remove(WATCH_TASK);
                    stop();
                    return;
                }
                if (tryBash(player)) {
                    player.getTemporaryAttributes().remove(WATCH_TASK);
                    stop();
                }
            }
        };
        player.getTemporaryAttributes().put(WATCH_TASK, task);
        WorldTasksManager.schedule(task, 0, 0);
    }

    /** Fires Shield Bash at the player's combat target if it's in melee reach. */
    private static boolean tryBash(final Player player) {
        if (player.isDead() || player.isLocked()) {
            return false;
        }
        final Action action = player.getActionManager().getAction();
        if (!(action instanceof final PlayerCombat combat)) {
            return false;
        }
        final Entity target = combat.getTarget();
        if (target == null || target.isDead() || target.isFinished() || !inMeleeReach(player, target)) {
            return false;
        }
        bash(player, combat, target);
        return true;
    }

    private static boolean inMeleeReach(final Player player, final Entity target) {
        if (player.getPlane() != target.getPlane()) {
            return false;
        }
        final int size = target.getSize();
        final int dx = player.getX() - target.getX();
        final int dy = player.getY() - target.getY();
        final boolean besideX = (dx == -1 || dx == size) && dy >= 0 && dy < size;
        final boolean besideY = (dy == -1 || dy == size) && dx >= 0 && dx < size;
        if (!besideX && !besideY) {
            return false;
        }
        return !ProjectileUtils.isProjectileClipped(player, target, player.getLocation(), target, true);
    }

    private static void bash(final Player player, final PlayerCombat combat, final Entity target) {
        final int charges = charges(player);
        setCharges(player, 0);
        player.addTemporaryAttribute(COOLDOWN, WorldThread.getCurrentCycle() + BASH_COOLDOWN_TICKS);
        player.getCombatDefinitions().setOffhandSpecial(false);
        player.setAnimation(BASH_ANIMATION);
        player.setGraphics(BASH_GRAPHICS);
        PlayerCombat.addAttackedByDelay(player, target);
        final boolean accurate = Utils.random(attackRoll(player, charges)) > Utils.random(PlayerCombat.getTargetDefenceRoll(player, target, AttackType.CRUSH));
        int damage = accurate ? charges * (100 + charges) / 100 : 0;
        if (damage > 0 && target instanceof final Player victim && victim.getPrayerManager().isActive(Prayer.PROTECT_FROM_MELEE)) {
            damage = (int) Math.floor(damage * target.getMeleePrayerMultiplier());
        }
        combat.delayHit(target, 0, new Hit(player, damage, HitType.MELEE));
    }

    /** Crush attack roll from every worn item but the weapon, +6 Crush per charge. */
    private static int attackRoll(final Player player, final int charges) {
        final AttackStyle.AttackExperienceType type = player.getCombatDefinitions().getAttackExperienceType();
        final int styleBonus = type == AttackStyle.AttackExperienceType.ATTACK_XP ? 3 : type == AttackStyle.AttackExperienceType.SHARED_XP ? 1 : 0;
        double effective = Math.floor(Math.floor(player.getSkills().getLevel(SkillConstants.ATTACK) * player.getPrayerManager().getSkillBoost(SkillConstants.ATTACK)) + styleBonus + 8);
        if (CombatUtilities.hasFullMeleeVoid(player, true)) {
            effective = Math.floor(effective * 1.125F);
        } else if (CombatUtilities.hasFullMeleeVoid(player, false)) {
            effective = Math.floor(effective * 1.1F);
        }
        int bonus = player.getBonuses().getBonus(Bonuses.Bonus.ATT_CRUSH);
        final int weaponId = player.getEquipment().getId(EquipmentSlot.WEAPON);
        if (weaponId != -1) {
            final ItemDefinitions weapon = ItemDefinitions.get(weaponId);
            final int[] weaponBonuses = weapon == null ? null : weapon.getBonuses();
            if (weaponBonuses != null && weaponBonuses.length > Bonuses.Bonus.ATT_CRUSH.getBonusIndex()) {
                bonus -= weaponBonuses[Bonuses.Bonus.ATT_CRUSH.getBonusIndex()];
            }
        }
        bonus += 6 * charges;
        return (int) Math.max(0, effective * (bonus + 64));
    }
}

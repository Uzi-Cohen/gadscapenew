package com.zenyte.game.world.entity.player.action.combat.magic;

import com.zenyte.game.item.Item;
import com.zenyte.game.world.Projectile;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.masks.Graphics;
import com.zenyte.game.world.entity.masks.Hit;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.zenyte.game.world.entity.player.action.combat.ArchiveWeapons;
import com.zenyte.game.world.entity.player.action.combat.MagicCombat;
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot;

/**
 * Zorya's tome (The Fractured Archive, OSRS 20 Oct 2026): a two-handed powered "staff" in the shape of a book.
 * <ul>
 * <li>3 ticks, range 7 (9 on Longrange), base max hit 18 at 99 Magic ({@link #MAX_HIT_OFFSET}: Jagex published the
 * value, not the formula; it scales with the Magic level like the other powered staves), +15% magic damage from the
 * item.</li>
 * <li>Zorya's flow (special, 50%): +150% accuracy and +60% damage. If it hits, the next 3 tome attacks get the same
 * bonus and fire every 2 ticks; they can be spread over several targets (player varp 5926 zoryas_tome_stacks).</li>
 * <li>Each attack uses a charge ({@link com.zenyte.plugins.item.ZoryasTome}); without charges it can't attack.</li>
 * </ul>
 */
public final class ZoryasTomeCombat extends MagicCombat {
    /** floor(Magic / 3) - 15: 18 at 99 Magic, as published. */
    public static final int MAX_HIT_OFFSET = 15;
    public static final int EMPOWERED_ATTACKS = 3;
    private static final double ACCURACY_BONUS = 2.5;
    private static final double DAMAGE_BONUS = 1.6;
    private static final String STACKS = "zoryas tome stacks";
    private static final Projectile SPECIAL_PROJECTILE = new Projectile(ArchiveWeapons.ZORYAS_TOME_SPECIAL_PROJECTILE, 40, 31, 46, 16, 10, 64, 5);

    private static final int NORMAL = 0;
    private static final int SPECIAL = 1;
    private static final int EMPOWERED = 2;
    private int mode = NORMAL;
    private boolean specialFired;

    public ZoryasTomeCombat(final Entity target, final CombatSpell spell, final CastType type) {
        super(target, spell, type);
    }

    public static int stacks(final Player player) {
        return player.getNumericTemporaryAttribute(STACKS).intValue();
    }

    public static void setStacks(final Player player, final int stacks) {
        final int value = Math.max(0, Math.min(EMPOWERED_ATTACKS, stacks));
        player.addTemporaryAttribute(STACKS, value);
        player.getVarManager().sendVar(ArchiveWeapons.ZORYAS_TOME_STACKS_VARP, value);
    }

    @Override
    public int processAfterMovement() {
        specialFired = false;
        mode = stacks(player) > 0 ? EMPOWERED : NORMAL;
        try {
            final int delay = super.processAfterMovement();
            if (delay > 0 && !specialFired && mode == EMPOWERED) {
                setStacks(player, stacks(player) - 1);
                // the speed was worked out before the stack went: the attack after the last empowered one is normal
                return stacks(player) > 0 ? delay : delay + 1;
            }
            return delay;
        } finally {
            mode = NORMAL;
        }
    }

    /** Zorya's flow, called by the special attack (energy is checked and taken by the caller). */
    public void fireSpecial() {
        specialFired = true;
        mode = SPECIAL;
        final boolean male = player.getAppearance().isMale();
        player.setAnimation(new Animation(male ? ArchiveWeapons.ZORYAS_TOME_SPECIAL : ArchiveWeapons.ZORYAS_TOME_SPECIAL_FEMALE));
        player.setGraphics(new Graphics(male ? ArchiveWeapons.ZORYAS_TOME_SPECIAL_CAST : ArchiveWeapons.ZORYAS_TOME_SPECIAL_CAST_FEMALE));
        final int delay = World.sendProjectile(player, target, SPECIAL_PROJECTILE);
        final int clientCycles = SPECIAL_PROJECTILE.getProjectileDuration(player.getLocation(), target.getLocation());
        final Hit hit = getHit(player, target, 1, 1, 1, false);
        extra(hit);
        applyHit(target, hit, !hit.isAccurate() && hit.getDamage() <= 0, delay, clientCycles);
        degrade();
    }

    @Override
    public Hit getHit(final Player player, final Entity target, final double accuracyModifier, final double passiveModifier, final double activeModifier, final boolean ignorePrayers) {
        if (mode == NORMAL) {
            return super.getHit(player, target, accuracyModifier, passiveModifier, activeModifier, ignorePrayers);
        }
        return super.getHit(player, target, accuracyModifier * ACCURACY_BONUS, passiveModifier * DAMAGE_BONUS, activeModifier, ignorePrayers);
    }

    @Override
    protected void applyHit(final Entity target, final Hit hit, final boolean splash, final int delay, final int clientcycles) {
        super.applyHit(target, hit, splash, delay, clientcycles);
        if (splash || mode == NORMAL) {
            return;
        }
        target.setGraphics(new Graphics(mode == SPECIAL ? ArchiveWeapons.ZORYAS_TOME_SPECIAL_IMPACT : ArchiveWeapons.ZORYAS_TOME_FOLLOW_UP_IMPACT, clientcycles, 60));
        if (mode == SPECIAL) {
            setStacks(player, EMPOWERED_ATTACKS);
            player.sendFilteredMessage("Zorya's tome is empowered for your next " + EMPOWERED_ATTACKS + " attacks.");
        }
    }

    @Override
    protected void animate() {
        final boolean male = player.getAppearance().isMale();
        player.setAnimation(new Animation(male ? ArchiveWeapons.ZORYAS_TOME_ATTACK : ArchiveWeapons.ZORYAS_TOME_ATTACK_FEMALE));
        player.setGraphics(new Graphics(male ? ArchiveWeapons.ZORYAS_TOME_CAST : ArchiveWeapons.ZORYAS_TOME_CAST_FEMALE));
    }

    @Override
    protected int baseDamage() {
        return Math.max(1, player.getSkills().getLevel(SkillConstants.MAGIC) / 3 - MAX_HIT_OFFSET);
    }

    /** 3 ticks, 2 while empowered (the action manager adds one tick). */
    @Override
    protected int attackSpeed() {
        return stacks(player) > 0 ? 1 : 2;
    }

    /** Range 7, 9 on Longrange (the distance check counts from the tile next to the target, like the Tumeken's shadow's 6/8). */
    @Override
    protected int getAttackDistance() {
        return player.getCombatDefinitions().getStyle() == 3 ? 8 : 6;
    }

    @Override
    protected boolean canAttack() {
        final Item weapon = player.getWeapon();
        if (weapon == null || weapon.getId() != ArchiveWeapons.ZORYAS_TOME || weapon.getCharges() <= 0) {
            player.sendMessage("Your tome has no charges. Charge it to use it.");
            return false;
        }
        return super.canAttack();
    }

    /** One charge per attack; at 0 the tome becomes Zorya's tome (uncharged). */
    @Override
    protected void degrade() {
        final Item weapon = player.getWeapon();
        if (weapon == null || weapon.getId() != ArchiveWeapons.ZORYAS_TOME) {
            return;
        }
        final int left = Math.max(0, weapon.getCharges() - 1);
        weapon.setCharges(left);
        player.getVarManager().sendBit(ArchiveWeapons.ZORYAS_TOME_CHARGES_VARBIT, Math.min(left, 0xFFFF));
        if (left <= 0) {
            weapon.setId(ArchiveWeapons.ZORYAS_TOME_UNCHARGED);
            weapon.setCharges(0);
            player.getEquipment().refresh(EquipmentSlot.WEAPON.getSlot());
            player.sendMessage("Your tome has run out of charges.");
            interrupt = true;
        }
    }
}

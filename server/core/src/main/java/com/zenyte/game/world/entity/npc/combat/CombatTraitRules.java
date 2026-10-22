package com.zenyte.game.world.entity.npc.combat;

import java.util.Random;

/**
 * The arithmetic behind {@link NpcCombatTraits}, free of any world state so it can be unit tested (spec §6.2 WU-04).
 * The combat pipeline calls these; tests call them with fixed inputs and a seeded {@link Random}.
 */
public final class CombatTraitRules {

    private CombatTraitRules() {
    }

    /** Accuracy with an elemental weakness: {@code roll + floor(roll × pct / 100)} (+1% per point). */
    public static int weaknessAccuracy(final int attackRoll, final int weaknessPercent) {
        if (weaknessPercent == 0 || attackRoll <= 0) {
            return attackRoll;
        }
        return (int) Math.max(0L, attackRoll + ((long) attackRoll * weaknessPercent) / 100L);
    }

    /** Max-hit bonus of an elemental weakness: {@code floor(baseSpellMax × pct / 100)}, added after every multiplier. */
    public static int weaknessBonusDamage(final int baseSpellMax, final int weaknessPercent) {
        if (weaknessPercent == 0 || baseSpellMax <= 0) {
            return 0;
        }
        return Math.floorDiv(baseSpellMax * weaknessPercent, 100);
    }

    /**
     * Damage after flat armour (or magic resistance): a successful hit ({@code damage > 0}) loses {@code armour} but
     * never drops below 1; a miss stays 0 (OSRS wiki "Flat armour").
     */
    public static int applyFlatArmour(final int damage, final int armour) {
        if (damage <= 0 || armour == 0) {
            return damage;
        }
        return Math.max(1, damage - armour);
    }

    /** {@code ceil(pct% × base)}: the Defence floor of the 60% reduction cap (Bork 300 → 180). */
    public static int percentFloor(final int baseLevel, final int percent) {
        if (percent <= 0 || baseLevel <= 0) {
            return 0;
        }
        return (int) ((baseLevel * (long) percent + 99L) / 100L);
    }

    /**
     * The level a drain of {@code amount} leaves, given a floor. A level already below the floor is never raised.
     *
     * @return the new level
     */
    public static int drainTo(final int currentLevel, final int amount, final int floor) {
        if (amount <= 0) {
            return currentLevel;
        }
        final int lowest = Math.max(0, Math.min(currentLevel, floor));
        return Math.max(lowest, currentLevel - amount);
    }

    /**
     * The level after a drain of {@code amount} under combat traits: an inverted magic drain raises the level by the
     * amount (Archive Guardian), any other drain stops at {@code floor} ({@link #drainTo}).
     */
    public static int drainedLevel(final int currentLevel, final int amount, final int floor, final boolean inverted) {
        if (amount <= 0) {
            return currentLevel;
        }
        return inverted ? currentLevel + amount : drainTo(currentLevel, amount, floor);
    }

    /** Whether a melee weapon can still hit a flying NPC: only halberds reach it (Archive Guardian, OSRS wiki). */
    public static boolean meleeReachesFlying(final String weaponName) {
        return weaponName != null && weaponName.toLowerCase(java.util.Locale.ROOT).contains("halberd");
    }

    /** The drain amount of a percentage drain, as {@code NPCCombatDefinitions#drainSkill} computes it. */
    public static int percentDrainAmount(final int currentLevel, final double percentage, final int minimumDrain) {
        return Math.max((int) (currentLevel * (percentage / 100.0F)), minimumDrain);
    }

    /** Freeze length with a resistance: {@code floor(ticks × (100 - pct) / 100)}; 0 = immune (Barrage 32 t at 40% → 19 t). */
    public static int resistedFreeze(final int ticks, final int resistancePercent) {
        if (resistancePercent <= 0) {
            return ticks;
        }
        if (resistancePercent >= 100) {
            return 0;
        }
        return Math.floorDiv(ticks * (100 - resistancePercent), 100);
    }

    /**
     * A demonbane modifier scaled by the target's demonbane effectiveness: Arclight 1.7 (+70%) at 80% → 1.56.
     *
     * @param baseBonus the weapon's bonus as a fraction (0.7 for +70%)
     */
    public static double demonbaneMultiplier(final double baseBonus, final int demonbanePercent) {
        return 1.0 + baseBonus * demonbanePercent / 100.0;
    }

    /** The flat bonus fraction of a demonbane source scaled by effectiveness (0.2 → 0.2 × pct / 100). */
    public static double scaledDemonbaneBonus(final double baseBonus, final int demonbanePercent) {
        return baseBonus * demonbanePercent / 100.0;
    }

    /**
     * The tick a spell hit lands on for a hit-delay override, in {@code PlayerCombat#delayHit} terms: an override of
     * 0 lands the hit on the cast tick (-1 = processed immediately), anything else is the delay in ticks.
     */
    public static int landingDelay(final int overrideTicks) {
        return overrideTicks <= 0 ? -1 : overrideTicks;
    }

    /**
     * One player attack roll as the pipeline does it: {@code guaranteed} skips the accuracy roll; otherwise
     * {@code rand(accuracy) > rand(defence)} hits. A hit rolls {@code minimum..maxHit}.
     *
     * @return the damage (0 = miss)
     */
    public static int rollHit(final Random random, final int accuracy, final int defence, final int minimum, final int maxHit,
                              final boolean guaranteed) {
        if (!guaranteed) {
            final int accRoll = accuracy > 0 ? random.nextInt(accuracy + 1) : 0;
            final int defRoll = defence > 0 ? random.nextInt(defence + 1) : 0;
            if (accRoll <= defRoll) {
                return 0;
            }
        }
        if (maxHit <= minimum) {
            return Math.max(0, maxHit);
        }
        return minimum + random.nextInt(maxHit - minimum + 1);
    }
}

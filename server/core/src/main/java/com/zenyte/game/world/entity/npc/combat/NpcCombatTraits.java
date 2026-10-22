package com.zenyte.game.world.entity.npc.combat;

import com.zenyte.game.world.entity.masks.HitType;
import com.zenyte.game.world.entity.player.SkillConstants;
import org.jetbrains.annotations.Nullable;

/**
 * Per-NPC combat rules the stock {@code NPCCombatDefinitions} cannot express (Raids 4 engine items E5-E9, spec §2.7).
 * An NPC hands one out through {@code NPC#getCombatTraits()}; {@code null}, the default for every NPC, keeps the
 * engine's behaviour exactly as it was.
 * <p>
 * The engine asks at hit time, never caches the answers, so an implementation can change them while the fight runs
 * (Bork's flat armour drops to 0 and melee hits skip the accuracy roll while he is dazed). Every method has a neutral
 * default, so an implementation overrides only what it needs.
 *
 * <table>
 * <caption>Where each rule is applied</caption>
 * <tr><td>{@link #weaknessElement()} / {@link #weaknessPercent()}</td><td>{@code MagicCombat#getAccuracy}, {@code #getMaxHit}
 * (standard-spellbook elemental spells only)</td></tr>
 * <tr><td>{@link #rangedDefenceBonus}</td><td>{@code RangedCombat#getTargetDefenceRollRanged}</td></tr>
 * <tr><td>{@link #drainFloor}, {@link #magicDrainInverted()}</td><td>{@code NPC#drainSkill} (all three overloads: every
 * drain source)</td></tr>
 * <tr><td>{@link #flatArmour()}, {@link #magicResistance()}</td><td>{@code PlayerCombat#delayHit} (each successful player
 * hitsplat)</td></tr>
 * <tr><td>{@link #guaranteedHit}</td><td>the accuracy rolls in {@code MeleeCombat}, {@code RangedCombat}, {@code MagicCombat}
 * and {@code PlayerCombat#isSuccessful}</td></tr>
 * <tr><td>{@link #freezeResistancePercent()}</td><td>{@code NPC#freeze}</td></tr>
 * <tr><td>{@link #isDemon()}, {@link #demonbanePercent()}</td><td>{@code CombatUtilities#isDemon}, {@code Demon#isDemon},
 * demonbane weapons and spells</td></tr>
 * <tr><td>{@link #immuneToAncientMagicks()}</td><td>{@code MagicCombat#start} / {@code #canAttack}</td></tr>
 * <tr><td>{@link #flying()}</td><td>{@code MeleeCombat#start} / {@code #canAttack} (halberds still reach)</td></tr>
 * </table>
 */
public interface NpcCombatTraits {

    /** The element this NPC is weak to, or null. */
    @Nullable
    default SpellElement weaknessElement() {
        return null;
    }

    /**
     * Elemental weakness in percent. Each point gives +1% magic accuracy and adds {@code floor(base spell max × pct / 100)}
     * to the max hit after every other magic damage modifier (OSRS wiki "Elemental weakness").
     */
    default int weaknessPercent() {
        return 0;
    }

    /**
     * The ranged defence bonus against an attack of {@code type}.
     *
     * @param fallback the NPC's single ranged defence bonus from its combat definitions
     */
    default int rangedDefenceBonus(final RangedAttackType type, final int fallback) {
        return fallback;
    }

    /**
     * The lowest level drains can take {@code skill} ({@link SkillConstants} id) to. Raids 4: Defence floor =
     * ceil(60% of the base Defence) for the bosses on the hotfix list.
     *
     * @param baseLevel the NPC's unboosted level in that skill
     */
    default int drainFloor(final int skill, final int baseLevel) {
        return 0;
    }

    /** Magic-level drains raise this NPC's Magic instead of lowering it (Archive Guardian). */
    default boolean magicDrainInverted() {
        return false;
    }

    /** Subtracted from every successful player melee and ranged hitsplat (never below 1). Negative values add. */
    default int flatArmour() {
        return 0;
    }

    /** Subtracted from every successful player magic hitsplat (never below 1). Player magic ignores flat armour. */
    default int magicResistance() {
        return 0;
    }

    /** Player attacks of {@code style} (MELEE, RANGED or MAGIC) skip the accuracy roll. */
    default boolean guaranteedHit(final HitType style) {
        return false;
    }

    /** Freeze resistance in percent: freezes last {@code floor(duration × (100 - pct) / 100)} ticks; 100 = immune. */
    default int freezeResistancePercent() {
        return 0;
    }

    /** Whether this NPC has the demon attribute; null = decide by name as before (the stock demon lists). */
    @Nullable
    default Boolean isDemon() {
        return null;
    }

    /**
     * Demonbane effectiveness in percent (100 = normal): Arclight's +70% becomes +70% × pct / 100 (Dreadnoth 25%,
     * Xarth and Ocellus 80%). Applies to demonbane weapons, demonbane spells and Mark of Darkness.
     */
    default int demonbanePercent() {
        return 100;
    }

    /** Ancient Magicks combat spells cannot be cast on this NPC (Archive Guardian). */
    default boolean immuneToAncientMagicks() {
        return false;
    }

    /** Message for a refused Ancient Magicks cast; null = the default "The {name} is immune to Ancient Magicks." */
    @Nullable
    default String ancientMagicksMessage() {
        return null;
    }

    /** Flying: only ranged, magic and halberds can hit it (Archive Guardian flying form). */
    default boolean flying() {
        return false;
    }

    /** Message for a refused melee attack on a flying NPC; null = "The {name} is flying too high to hit." */
    @Nullable
    default String flyingMessage() {
        return null;
    }
}

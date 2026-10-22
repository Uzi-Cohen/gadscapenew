package com.gadscape.raids.tfa.encounter

import com.gadscape.raids.tfa.TfaServices
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combat.NpcCombatTraits

/**
 * Base of every boss NPC (spec §2.2). On top of [TfaNpc]:
 * - **Scaled HP**: [baseHp] is the 2-player HP from the boss JSON; [applyScaling] sets the max and current HP to
 *   `Scaling.hp(baseHp, scale, scalingKey)` for the encounter's party scale. [spawn] does it if not done yet.
 *   [getMaxHitpoints] returns the scaled value, so thresholds are fractions of it (`Scaling.crossed`).
 * - **Combat-level override** (E12): if `scaling.json combatLevelOverride` has [scalingKey], [getCombatLevel]
 *   returns it (Moia 1064, Ocellus P3 1601). The client sees it once WU-06 sends the extended info.
 * - **Traits** default to [CombatTraits.BOSS_DEFAULT] (immune to poison, venom and cannon, 60% defence floor).
 * - **HP HUD**: every processed hit calls `TfaServices.bossHud.onHpChanged`; [spawn] calls `onShown`.
 *
 * Subclasses keep their own timeline (tick counter + action queue, §2.5) driven from the encounter's `tick()`.
 */
abstract class TfaBossNpc(
    context: TfaEncounterContext,
    id: Int,
    tile: Location,
    facing: Direction = Direction.SOUTH,
) : TfaNpc(context, id, tile, facing) {

    /** 2-player base HP (from the boss's config spec). */
    protected abstract val baseHp: Int

    /**
     * Key in `scaling.json` for an HP table override and/or a combat-level override (e.g. `"moia"`,
     * `"ocellus_p3"`), or null. Must not depend on fields: it can be read during construction.
     */
    open val scalingKey: String? get() = null

    override val traits: CombatTraits get() = CombatTraits.BOSS_DEFAULT

    /**
     * Engine view of [traits] (WU-04, E5-E9): weakness %, ranged defence types, defence floor, flat armour, guaranteed
     * hits, freeze resistance, demonbane %, Ancient Magicks immunity, inverted magic drain, flying. Re-reads [traits]
     * on every hit, so overriding [traits] with a state-dependent getter is enough (e.g. Bork dazed). Null while the
     * `NPC` super constructor runs. Rules set explicitly with `setCombatTraits` (the binder does that on spawn with
     * the same live view) take precedence.
     */
    private val engineTraits: NpcCombatTraits? = CombatTraitsView { traits }

    override fun getCombatTraits(): NpcCombatTraits? = super.getCombatTraits() ?: engineTraits

    /** Scaled max HP; 0 until [applyScaling]. */
    var scaledMaxHp: Int = 0
        private set

    /**
     * Sets [scaledMaxHp] for the encounter's scale and, if [fill], the current HP to it. Call again after a
     * transformation that changes the HP pool (e.g. Guardian shield → main HP) with the new [base].
     */
    @JvmOverloads
    fun applyScaling(base: Int = baseHp, fill: Boolean = true) {
        val ctx = context
        scaledMaxHp = ctx.scaling.hp(base, ctx.scale, scalingKey)
        if (fill) setHitpoints(scaledMaxHp)
        TfaServices.bossHud.onHpChanged(this)
    }

    override fun getMaxHitpoints(): Int {
        val scaled = scaledMaxHp // 0 while the NPC super constructor runs
        return if (scaled > 0) scaled else super.getMaxHitpoints()
    }

    override fun getCombatLevel(): Int {
        val key = scalingKey ?: return super.getCombatLevel()
        return contextOrNull?.scaling?.combatLevel(key) ?: super.getCombatLevel()
    }

    override fun spawn(): NPC {
        if (scaledMaxHp == 0) applyScaling()
        val npc = super.spawn()
        TfaServices.bossHud.onShown(this)
        return npc
    }

    override fun onHitProcessed(hit: Hit) {
        TfaServices.bossHud.onHpChanged(this)
    }

    override fun finish() {
        TfaServices.bossHud.onHidden(this)
        super.finish()
    }

    /** Current HP as a percentage of [getMaxHitpoints] (0-100), for `::tfa hp` and thresholds. */
    fun hpPercent(): Double {
        val max = maxHitpoints
        return if (max <= 0) 0.0 else hitpoints * 100.0 / max
    }

    /** Sets the current HP to [percent]% of the scaled max (debug, `::tfa hp`). */
    fun setHpPercent(percent: Int) {
        val max = maxHitpoints
        setHitpoints((max.toLong() * percent.coerceIn(0, 100) / 100).toInt().coerceAtLeast(if (percent > 0) 1 else 0))
        TfaServices.bossHud.onHpChanged(this)
    }
}

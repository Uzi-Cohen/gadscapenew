package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combatdefs.AttackType

/** How the room hears about deaths (the NPCs never run the engine's death task, so the room's timeline owns them). */
internal interface MoiaDeathListener {
    fun onMoiaDeath(moia: MoiaNpc)
    fun onBinderDeath(binder: UndeadBinder)
}

/**
 * Moia, npc 16667 (spec §4.4): size 3, drawn at 150%. She never walks (her walk anim is her idle 14783); she only
 * teleports between the arms ([MoiaRoom] moves her with `setLocation`). Every attack is scripted by [MoiaRoom]; the
 * engine's aggression, retaliation and attack loop are switched off here.
 *
 * - HP: `moia.json baseHp` (1900) scaled by party size ([TfaBossNpc.applyScaling]).
 * - Displayed combat level: `scaling.json combatLevelOverride.moia` (1064), else `moia.json combatLevel` (E12).
 * - Traits: `moia.json traits` (60% defence floor = 117, air weakness 80%, ranged defence 60 / 120 / 140,
 *   poison / venom / cannon immune), applied by the engine through [TfaBossNpc.getCombatTraits].
 * - Combat stats: `moia.json stats` (cache: Def 195, Mag 350, Rng 200; defences 20 / 110 / 15 / 30).
 * - Death: [sendDeath] hands over to the room (seq 14784 + spot 4221, then removal); no drop.
 */
internal class MoiaNpc(
    context: TfaEncounterContext,
    tile: Location,
    facing: Direction,
) : TfaBossNpc(context, MoiaIds.MOIA, tile, facing) {

    /** Set by the room right after construction. */
    var listener: MoiaDeathListener? = null

    /** The arm she stands on. */
    var arm: MoiaArm = MoiaArm.W

    private var deathHandled = false

    private var cachedConfig: MoiaConfig? = null

    /** The room config; null while the `NPC` super constructor runs. */
    private val cfg: MoiaConfig?
        get() = cachedConfig ?: contextOrNull?.config?.get(MoiaConfig)?.also { cachedConfig = it }

    override val baseHp: Int get() = cfg?.baseHp ?: 1900

    override val scalingKey: String get() = SCALING_KEY

    override val traits: CombatTraits get() = cfg?.traits ?: MoiaConfig.DEFAULT_TRAITS

    override fun getCombatLevel(): Int {
        val ctx = contextOrNull ?: return super.getCombatLevel()
        return ctx.scaling.combatLevel(SCALING_KEY) ?: cfg?.combatLevel ?: super.getCombatLevel()
    }

    override fun updateCombatDefinitions() {
        val c = cfg ?: return super.updateCombatDefinitions()
        val first = combatDefinitionsMap.isEmpty()
        val hp = if (scaledMaxHp > 0) scaledMaxHp else c.baseHp
        val def = combatDefinitionsMap.get(id)
            ?: MoiaCombatDefs.build(id, hp, c.attackSpeed, c.stats, AttackType.RANGED, c.rangedMax, MoiaIds.SEQ_DEATH, traits)
        setCombatDefinitions(def)
        if (first) setHitpoints(def.hitpoints)
    }

    override fun spawn(): NPC {
        val npc = super.spawn()
        applyTraits() // NPC.spawn replaced the combat definitions after TfaNpc applied the traits (ENGINE-combat §2)
        Bridge.showCombatLevel(this, combatLevel)
        return npc
    }

    // ---- the room scripts her: no engine AI ------------------------------------------------------------------

    override fun processNPC() {}

    override fun afterMovement() {}

    override fun checkAggressivity(): Boolean = false

    override fun autoRetaliate(source: Entity) {}

    /** No defend seq exists for her (cache 14783-14788: idle, death, two attacks, chant, teleport). */
    override fun performDefenceAnimation(attacker: Entity) {}

    override fun isCycleHealable(): Boolean = false

    override fun sendDeath() {
        if (deathHandled) return
        deathHandled = true
        resetWalkSteps()
        combat.removeTarget()
        optionMask = 0
        listener?.onMoiaDeath(this)
    }

    /** Drops nothing (rewards come from the vault). */
    override fun drop(tile: Location) {}

    companion object {
        /** `scaling.json` key: combat-level override (and an optional HP table). */
        const val SCALING_KEY = "moia"
    }
}

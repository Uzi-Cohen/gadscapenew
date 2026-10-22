package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combatdefs.AggressionType
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.npc.combatdefs.NPCCDLoader
import com.zenyte.game.world.entity.npc.combatdefs.NPCCombatDefinitions
import com.zenyte.game.world.entity.player.Player

/**
 * Xarth, npc 16684 (spec §4.7): a [TfaBossNpc] with scaled HP (`baseHp` 2600 → the §2.6 table), the `xarth.json`
 * traits (defence floor 102, water 85%, ranged 125/230/155, demonbane 80%, freeze-immune; applied by the engine through
 * `TfaBossNpc.getCombatTraits`), contribution and the HP HUD on every hit.
 *
 * He never moves (house: static all fight) and the engine's NPC combat loop is off: [XarthBrain] decides every seq,
 * projectile and hit. His death does not run the stock death task: the room plays 14995 and despawns him.
 */
internal class Xarth(
    room: XarthRoom,
    context: TfaEncounterContext,
    tile: Location,
    facing: Direction,
) : TfaBossNpc(context, XarthIds.XARTH, tile, facing) {

    /** Null only while the `NPC` super constructor runs. */
    private val roomRef: XarthRoom? = room

    /** False while dying. */
    var targetable: Boolean = true
        private set

    init {
        maxDistance = 64
        radius = 0
    }

    override val baseHp: Int get() = roomRef?.config?.baseHp ?: DEFAULT_BASE_HP

    override val traits: CombatTraits get() = roomRef?.config?.traits ?: XarthConfig.BOSS_TRAITS

    /**
     * `NPC.spawn()` reloads the combat definitions from `NPCCDLoader`, which has nothing for 16684 (and would reset the
     * HP to 1): install ours. HP stays with [TfaBossNpc.applyScaling]. During the `NPC` super constructor the room is
     * not set yet, so the stock path runs then.
     */
    override fun updateCombatDefinitions() {
        val room = roomRef ?: return super.updateCombatDefinitions()
        val defs = XarthCombatDefs.boss(room.config)
        if (scaledMaxHp > 0) defs.hitpoints = scaledMaxHp
        combatDefinitions = defs
    }

    /** `NPC.spawn()` replaced the definitions [applyTraits] wrote the immunities into: apply the traits again. */
    override fun spawn(): NPC {
        val npc = super.spawn()
        applyTraits()
        return npc
    }

    fun setTargetableState(value: Boolean) {
        targetable = value
        isCantInteract = !value
    }

    // ------------------------------------------------------------------------------ engine combat switched off

    override fun processNPC() {}

    override fun afterMovement() {}

    override fun autoRetaliate(source: Entity) {}

    override fun checkAggressivity(): Boolean = false

    override fun canAttack(source: Player): Boolean = targetable && !isDead && super.canAttack(source)

    override fun isAttackableNPC(): Boolean = targetable && super.isAttackableNPC()

    /** Raid bosses neither regenerate HP nor restore drained stats. */
    override fun isCycleHealable(): Boolean = false

    /** DOC wiki infobox ("approximate ... empirical"): +180% combat xp. */
    override fun getXpModifier(hit: Hit): Float = (roomRef?.config?.xpModifier ?: DEFAULT_XP).toFloat()

    /** Spec [C+]: defend seq 15018 on player hits while he is not attacking (the brain knows). */
    override fun performDefenceAnimation(attacker: Entity) {
        val room = roomRef ?: return
        if (room.bossMayDefend()) setUnprioritizedAnimation(Animation(XarthIds.SEQ_DEFEND))
    }

    /**
     * Corpse heals arrive as HEALED hits. `NPC.processHit` caps every hit at the current HP, which would cut a heal
     * when he is low; heal the remainder and show the full amount.
     */
    override fun processHit(hit: Hit) {
        if (hit.hitType != HitType.HEALED) {
            super.processHit(hit)
            return
        }
        val wanted = hit.damage
        super.processHit(hit)
        val missing = wanted - hit.damage
        if (missing > 0 && !isDead) heal(missing)
        hit.damage = wanted
    }

    override fun onHitProcessed(hit: Hit) {
        super.onHitProcessed(hit)
        roomRef?.onBossHit(hit)
    }

    override fun sendDeath() {
        onDeath(mostDamagePlayer)
        setTargetableState(false)
        roomRef?.onBossDeath()
    }

    override fun drop(tile: Location) {}

    private companion object {
        const val DEFAULT_BASE_HP = 2600
        const val DEFAULT_XP = 2.8
    }
}

/** The Zenyte combat definitions the 2746 npcs lack (GadScape has no combat def files for 16684-16688). */
internal object XarthCombatDefs {

    fun boss(cfg: XarthConfig): NPCCombatDefinitions =
        build(
            npcId = XarthIds.XARTH,
            hitpoints = cfg.baseHp,
            attackSpeed = cfg.attackSpeed,
            stats = cfg.stats,
            attackType = AttackType.MAGIC,
            maxHit = cfg.magicMax,
            attackAnim = XarthIds.SEQ_MAGIC,
            blockAnim = XarthIds.SEQ_DEFEND,
            deathAnim = XarthIds.SEQ_DEATH,
        )

    /** A corpse, burning (16687) or doused (16688); it never attacks. */
    fun corpse(cfg: XarthConfig.Corpses, npcId: Int, hitpoints: Int): NPCCombatDefinitions =
        build(
            npcId = npcId,
            hitpoints = hitpoints,
            attackSpeed = 6,
            stats = if (npcId == XarthIds.CORPSE_DOUSED) cfg.dousedStats else cfg.stats,
            attackType = AttackType.CRUSH,
            maxHit = 0,
            attackAnim = -1,
            blockAnim = -1, // no corpse defend seq exists in the cache (seq_xarth.txt)
            deathAnim = XarthIds.SEQ_CORPSE_DEATH,
        )

    /** The pillar helpers 16685 / 16686: no combat, 1 HP that nothing can take. */
    fun helper(npcId: Int): NPCCombatDefinitions {
        val defs = NPCCombatDefinitions.clone(npcId, NPCCDLoader.get(npcId))
        defs.id = npcId
        defs.hitpoints = 1
        defs.aggressionType = AggressionType.PASSIVE
        defs.aggressionDistance = 0
        return defs
    }

    fun meleeStyle(name: String): AttackType = when (name) {
        "stab" -> AttackType.STAB
        "slash" -> AttackType.SLASH
        else -> AttackType.CRUSH
    }

    private fun build(
        npcId: Int,
        hitpoints: Int,
        attackSpeed: Int,
        stats: XarthConfig.Stats,
        attackType: AttackType,
        maxHit: Int,
        attackAnim: Int,
        blockAnim: Int,
        deathAnim: Int,
    ): NPCCombatDefinitions {
        val defs = NPCCombatDefinitions.clone(npcId, NPCCDLoader.get(npcId))
        defs.id = npcId
        defs.hitpoints = hitpoints
        defs.attackSpeed = attackSpeed
        defs.attackDistance = 0
        defs.maximumDistance = 64
        defs.aggressionDistance = 0
        defs.aggressionType = AggressionType.PASSIVE // the room picks every target
        defs.targetType = Entity.EntityType.PLAYER
        val s = defs.statDefinitions
        s.combatStats = stats.levels.toIntArray()
        s.aggressiveStats = stats.attackBonuses.toIntArray()
        s.defensiveStats = stats.defenceBonuses.toIntArray()
        s.otherBonuses = stats.otherBonuses.toIntArray()
        val attack = defs.attackDefinitions
        attack.type = attackType
        attack.maxHit = maxHit
        attack.animation = if (attackAnim < 0) Animation.STOP else Animation(attackAnim)
        defs.blockDefinitions.animation = if (blockAnim < 0) Animation.STOP else Animation(blockAnim)
        defs.spawnDefinitions.deathAnimation = Animation(deathAnim)
        return defs
    }
}

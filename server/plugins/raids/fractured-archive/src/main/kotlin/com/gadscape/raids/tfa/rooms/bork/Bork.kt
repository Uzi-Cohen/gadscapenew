package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combatdefs.AggressionType
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.npc.combatdefs.NPCCDLoader
import com.zenyte.game.world.entity.npc.combatdefs.NPCCombatDefinitions
import com.zenyte.game.world.entity.player.Player

/**
 * Bork, npc 16640 (spec §4.3): a [TfaBossNpc] with scaled HP (`bork.baseHp` 1500 → §2.6 table), the §4.3 traits
 * (earth 40%, ranged 80/50/5, 60% defence floor = 180, freeze/poison/venom/cannon immune) and a **live** flat armour /
 * magic resistance read from the room's brain on every hit: 12 / 2, 10 at or below 66% HP, 0 / 0 and guaranteed
 * player melee hits while dazed (E8, through `TfaBossNpc.getCombatTraits`).
 *
 * The engine's NPC combat loop is off: [BorkSmorksBrain] (driven by [BorkSmorksRoom.tick]) decides every step,
 * animation and attack. Protect from Melee takes `bork.pfmMult` (0.5) off each of his hits as it lands
 * ([getMeleePrayerMultiplier]). Death runs no stock death task: the room plays 14541 and despawns him.
 */
internal class Bork(
    room: BorkSmorksRoom,
    context: TfaEncounterContext,
    tile: Location,
    facing: Direction,
) : TfaBossNpc(context, BorkIds.BORK, tile, facing) {

    /** Null only while the `NPC` super constructor runs. */
    private val roomRef: BorkSmorksRoom? = room

    /** False while dying: players cannot attack him. */
    var targetable: Boolean = true
        private set

    init {
        maxDistance = 64
        radius = 0
    }

    override val baseHp: Int get() = roomRef?.config?.bork?.baseHp ?: DEFAULT_BASE_HP

    /** The configured traits with the brain's live armour (dazed → 0 / 0 and guaranteed melee). */
    override val traits: CombatTraits
        get() {
            val room = roomRef ?: return BorkSmorksConfig.BORK_TRAITS
            val base = room.config.bork.traits
            val brain = room.brainOrNull ?: return base
            return base.copy(
                flatArmour = brain.borkFlatArmour(),
                magicResist = brain.borkMagicResist(),
                guaranteedMelee = brain.isDazed,
            )
        }

    /**
     * `NPC.spawn()` reloads the combat definitions from `NPCCDLoader`, which has nothing for 16640 (and would reset the
     * HP to 1): install ours instead. HP stays with [TfaBossNpc.applyScaling].
     */
    override fun updateCombatDefinitions() {
        val room = roomRef ?: return super.updateCombatDefinitions()
        val defs = BorkCombatDefs.bork(room.config)
        if (scaledMaxHp > 0) defs.hitpoints = scaledMaxHp
        combatDefinitions = defs
    }

    /** `NPC.spawn()` replaced the definitions `applyTraits` wrote the immunities into: apply the traits again. */
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

    /** DOC wiki infobox: +60% combat xp ("empirical"). */
    override fun getXpModifier(hit: Hit): Float = (roomRef?.config?.bork?.xpModifier ?: 1.0).toFloat()

    /** HOUSE: Protect from Melee reduces his beats, stomps and gong by 50% (spec §4.3). */
    override fun getMeleePrayerMultiplier(): Double = roomRef?.config?.bork?.pfmMult ?: 0.5

    override fun onHitProcessed(hit: Hit) {
        super.onHitProcessed(hit)
        roomRef?.onNpcHit()
    }

    override fun sendDeath() {
        onDeath(mostDamagePlayer)
        setTargetableState(false)
        roomRef?.onBorkDeath()
    }

    private companion object {
        const val DEFAULT_BASE_HP = 1500
    }
}

/** Builds the Zenyte combat definitions the 2746 npcs lack (GadScape has no combat def files for 16640/16641). */
internal object BorkCombatDefs {

    fun bork(cfg: BorkSmorksConfig): NPCCombatDefinitions =
        build(BorkIds.BORK, cfg.bork.baseHp, Tempo.DEFAULT_SPEED, cfg.bork.stats, AttackType.CRUSH, cfg.bork.beatMax, BorkIds.SEQ_BEAT, BorkIds.SEQ_DEATH)

    fun smorks(cfg: BorkSmorksConfig): NPCCombatDefinitions =
        build(BorkIds.SMORKS, cfg.smorks.baseHp, Tempo.DEFAULT_SPEED, cfg.smorks.stats, AttackType.RANGED, cfg.smorks.autoMax, BorkIds.SEQ_VOLLEY, -1)

    private fun build(
        npcId: Int,
        hitpoints: Int,
        attackSpeed: Int,
        stats: BorkSmorksConfig.Stats,
        attackType: AttackType,
        maxHit: Int,
        attackAnim: Int,
        deathAnim: Int,
    ): NPCCombatDefinitions {
        val defs = NPCCombatDefinitions.clone(npcId, NPCCDLoader.get(npcId))
        defs.id = npcId
        defs.hitpoints = hitpoints
        defs.attackSpeed = attackSpeed
        defs.attackDistance = 0
        defs.maximumDistance = 64
        defs.aggressionDistance = 0
        defs.aggressionType = AggressionType.PASSIVE // targets are chosen by the room
        defs.targetType = Entity.EntityType.PLAYER
        val s = defs.statDefinitions
        s.combatStats = stats.levels.toIntArray()
        s.aggressiveStats = stats.attackBonuses.toIntArray()
        s.defensiveStats = stats.defenceBonuses.toIntArray()
        s.otherBonuses = stats.otherBonuses.toIntArray()
        val attack = defs.attackDefinitions
        attack.type = attackType
        attack.maxHit = maxHit
        attack.animation = Animation(attackAnim)
        // No defend seq exists for either npc in the cache (NOTES §5): no block animation.
        defs.blockDefinitions.animation = Animation.STOP
        defs.spawnDefinitions.deathAnimation = if (deathAnim < 0) Animation.STOP else Animation(deathAnim)
        return defs
    }
}

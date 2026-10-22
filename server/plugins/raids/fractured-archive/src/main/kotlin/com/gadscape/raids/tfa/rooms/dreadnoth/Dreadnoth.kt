package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.SoundEffect
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combatdefs.AggressionType
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.npc.combatdefs.NPCCDLoader
import com.zenyte.game.world.entity.npc.combatdefs.NPCCombatDefinitions
import com.zenyte.game.world.entity.player.Player

/**
 * Dreadnoth the Jellied, npc 16665 (spec §4.5). A [TfaBossNpc]: scaled HP (`baseHp` 2100 → §2.6 table), the traits of
 * `dreadnoth.json` (fire 85%, ranged 100/80/80, freeze resistance 40%, demonbane 25%, no defence floor; applied by the
 * engine through `TfaBossNpc.getCombatTraits`), contribution and HP HUD on every hit.
 *
 * The engine's NPC combat loop is switched off: [DreadnothBrain] (driven by [DreadnothRoom.tick]) decides every
 * movement, animation and attack. Death does not run the stock death task (no drops, no respawn, no
 * `WorldTasksManager`): the room's timeline plays seq 14782 and despawns him.
 */
internal class Dreadnoth(
    room: DreadnothRoom,
    context: TfaEncounterContext,
    tile: Location,
    facing: Direction,
) : TfaBossNpc(context, DreadnothIds.BOSS, tile, facing) {

    /** Null only while the `NPC` super constructor runs. */
    private val roomRef: DreadnothRoom? = room

    /** False while airborne (enrage jump) or dying. */
    var targetable: Boolean = true
        private set

    init {
        maxDistance = 64
        radius = 0
    }

    override val baseHp: Int get() = roomRef?.config?.baseHp ?: DEFAULT_BASE_HP

    override val traits: CombatTraits get() = roomRef?.config?.traits ?: DreadnothConfig.BOSS_TRAITS

    /**
     * `NPC.spawn()` reloads the combat definitions from `NPCCDLoader`, which has nothing for 16665 (and would reset the
     * HP to 1): install ours instead. HP stays with [TfaBossNpc.applyScaling]. During the `NPC` super constructor the
     * room is not set yet, so the stock path runs then.
     */
    override fun updateCombatDefinitions() {
        val room = roomRef ?: return super.updateCombatDefinitions()
        val defs = DreadnothCombatDefs.boss(room.config)
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

    /** The brain drives him: no stock aggression, retaliation, random walk or attack loop. */
    override fun processNPC() {}

    override fun afterMovement() {}

    override fun autoRetaliate(source: Entity) {}

    override fun checkAggressivity(): Boolean = false

    override fun canAttack(source: Player): Boolean = targetable && !isDead && super.canAttack(source)

    /** Area attacks skip him while airborne or dying too. */
    override fun isAttackableNPC(): Boolean = targetable && super.isAttackableNPC()

    /** Raid bosses neither regenerate HP nor restore drained stats. */
    override fun isCycleHealable(): Boolean = false

    /** DOC wiki infobox: +77.5% combat xp ("empirical"). */
    override fun getXpModifier(hit: Hit): Float = (roomRef?.config?.xpModifier ?: 1.0).toFloat()

    override fun onHitProcessed(hit: Hit) {
        super.onHitProcessed(hit)
        roomRef?.onBossHit()
    }

    override fun sendDeath() {
        val source = mostDamagePlayer
        onDeath(source)
        setTargetableState(false)
        roomRef?.onBossDeath()
    }

    private companion object {
        const val DEFAULT_BASE_HP = 2100
    }
}

/** Builds the Zenyte combat definitions the 2746 npcs lack (GadScape has no combat def files for 16665/16666). */
internal object DreadnothCombatDefs {

    fun boss(cfg: DreadnothConfig): NPCCombatDefinitions =
        build(
            npcId = DreadnothIds.BOSS,
            hitpoints = cfg.baseHp,
            attackSpeed = cfg.attackSpeed,
            stats = cfg.stats,
            attackType = meleeStyle(cfg.melee.style),
            maxHit = cfg.meleeMax,
            attackAnim = DreadnothIds.SEQ_MELEE,
            blockAnim = -1, // no Dreadnoth defend seq exists in the cache (NOTES §5)
            deathAnim = DreadnothIds.SEQ_DEATH,
            sounds = null,
        )

    fun jelly(cfg: DreadnothConfig.Jelly): NPCCombatDefinitions =
        build(
            npcId = DreadnothIds.JELLY,
            hitpoints = cfg.hp,
            attackSpeed = cfg.attackSpeed,
            stats = cfg.stats,
            attackType = AttackType.MELEE, // the stock jelly's style (GadScape npc 437)
            maxHit = cfg.max,
            attackAnim = DreadnothIds.SEQ_JELLY_ATTACK,
            blockAnim = DreadnothIds.SEQ_JELLY_BLOCK,
            deathAnim = DreadnothIds.SEQ_JELLY_DEATH,
            sounds = if (cfg.stockSounds) Triple(
                DreadnothIds.SYNTH_JELLY_ATTACK, DreadnothIds.SYNTH_JELLY_BLOCK, DreadnothIds.SYNTH_JELLY_DEATH,
            ) else null,
        )

    fun meleeStyle(name: String): AttackType = when (name) {
        "stab" -> AttackType.STAB
        "slash" -> AttackType.SLASH
        else -> AttackType.CRUSH
    }

    private fun build(
        npcId: Int,
        hitpoints: Int,
        attackSpeed: Int,
        stats: DreadnothConfig.Stats,
        attackType: AttackType,
        maxHit: Int,
        attackAnim: Int,
        blockAnim: Int,
        deathAnim: Int,
        sounds: Triple<Int, Int, Int>?,
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
        attack.startSound = sounds?.let { SoundEffect(it.first, SOUND_RADIUS, 0) }
        val block = defs.blockDefinitions
        block.animation = if (blockAnim < 0) Animation.STOP else Animation(blockAnim)
        block.sound = sounds?.let { SoundEffect(it.second, SOUND_RADIUS, 0) }
        val spawn = defs.spawnDefinitions
        spawn.deathAnimation = Animation(deathAnim)
        spawn.deathSound = sounds?.let { SoundEffect(it.third, SOUND_RADIUS, 0) }
        return defs
    }

    /** The stock jelly sounds' radius (GadScape npc 437 combat def). */
    private const val SOUND_RADIUS = 10
}

package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.npc.combatdefs.AggressionType
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.npc.combatdefs.ImmunityType
import com.zenyte.game.world.entity.npc.combatdefs.NPCCDLoader
import com.zenyte.game.world.entity.npc.combatdefs.NPCCombatDefinitions
import com.zenyte.game.world.entity.npc.combatdefs.StatDefinitions
import com.zenyte.game.world.entity.npc.combatdefs.StatType
import java.util.EnumSet

/**
 * Combat definitions for the room's NPCs. The server has no `cache/data/npcs/combat` file for 16643-16651, so
 * without these they would spawn with Defence 0 and 1 HP (`NPC.spawn` reloads the definitions); the numbers are the
 * cache 2746 npc config values kept in `dagonhai.json stats` / `impStats` (spec §4.6 combat profile [CACHE]).
 */
internal object DagonhaiCombatDefs {

    private val LEVELS = arrayOf(StatType.ATTACK, StatType.STRENGTH, StatType.DEFENCE, StatType.MAGIC, StatType.RANGED)
    private val ATTACK = arrayOf(StatType.ATTACK_STAB, StatType.ATTACK_SLASH, StatType.ATTACK_CRUSH, StatType.ATTACK_MAGIC, StatType.ATTACK_RANGED)
    private val DEFENCE = arrayOf(StatType.DEFENCE_STAB, StatType.DEFENCE_SLASH, StatType.DEFENCE_CRUSH, StatType.DEFENCE_MAGIC, StatType.DEFENCE_RANGED)
    private val STRENGTH = arrayOf(StatType.MELEE_STRENGTH_BONUS, StatType.RANGED_STRENGTH_BONUS, StatType.MAGIC_STRENGTH_BONUS)

    /** One of the trio (shielded: the pool; unshielded: 7 HP). The room scripts every attack. */
    fun trio(id: Int, hitpoints: Int, cfg: DagonhaiConfig, deathAnim: Int): NPCCombatDefinitions =
        build(id, hitpoints, cfg.elder.speed, cfg.stats, AttackType.MAGIC, cfg.elder.rangedMax, attackAnim = -1, deathAnim = deathAnim,
            traits = cfg.traits, aggressive = false)

    /** The cauldron: HP = ritual stability, never attacked or attacking. */
    fun cauldron(id: Int, cfg: DagonhaiConfig): NPCCombatDefinitions =
        build(id, cfg.stability, 10, NONE, AttackType.MELEE, 0, attackAnim = -1, deathAnim = -1, traits = cfg.impTraits, aggressive = false)

    /** An imp: melee (style from `protect.impStyle`), max `protect.impMax`, attack rate 4, attack 14529, death 14530. */
    fun imp(id: Int, cfg: DagonhaiConfig): NPCCombatDefinitions =
        build(id, cfg.protect.impHp, cfg.protect.impSpeed, cfg.impStats, impStyle(cfg.protect.impStyle), cfg.protect.impMax,
            attackAnim = DagonhaiIds.SEQ_IMP_ATTACK, deathAnim = DagonhaiIds.SEQ_IMP_DEATH, traits = cfg.impTraits, aggressive = false)

    /** A rift: 20 HP in the cache, no ops; never attacked. */
    fun rift(id: Int, cfg: DagonhaiConfig): NPCCombatDefinitions =
        build(id, RIFT_HP, 10, NONE, AttackType.MELEE, 0, attackAnim = -1, deathAnim = -1, traits = cfg.impTraits, aggressive = false)

    fun impStyle(name: String): AttackType = when (name) {
        "slash" -> AttackType.SLASH
        "crush" -> AttackType.CRUSH
        else -> AttackType.STAB
    }

    private val NONE = DagonhaiConfig.NpcProfile(listOf(1, 1, 1, 1, 1), listOf(0, 0, 0, 0, 0), listOf(0, 0, 0, 0, 0), listOf(0, 0, 0))

    /** Cache 2746 hitpoints of npc 16651. */
    private const val RIFT_HP = 20

    private fun build(
        id: Int,
        hitpoints: Int,
        attackSpeed: Int,
        profile: DagonhaiConfig.NpcProfile,
        style: AttackType,
        maxHit: Int,
        attackAnim: Int,
        deathAnim: Int,
        traits: CombatTraits,
        aggressive: Boolean,
    ): NPCCombatDefinitions {
        val def = NPCCombatDefinitions.clone(id, NPCCDLoader.get(id))
        def.id = id
        def.hitpoints = hitpoints
        def.attackSpeed = attackSpeed
        def.maximumDistance = 64
        def.aggressionDistance = 0
        def.aggressionType = if (aggressive) AggressionType.AGGRESSIVE else AggressionType.PASSIVE
        def.targetType = Entity.EntityType.PLAYER
        val stats = StatDefinitions()
        profile.levels.forEachIndexed { i, v -> stats.set(LEVELS[i], v) }
        profile.attack.forEachIndexed { i, v -> stats.set(ATTACK[i], v) }
        profile.defence.forEachIndexed { i, v -> stats.set(DEFENCE[i], v) }
        profile.strength.forEachIndexed { i, v -> stats.set(STRENGTH[i], v) }
        def.statDefinitions = stats
        def.attackDefinitions.type = style
        def.attackDefinitions.maxHit = maxHit
        if (attackAnim >= 0) def.attackDefinitions.animation = Animation(attackAnim)
        // No defend seq exists for any of these npcs in 2746 (seqs 14580-14682, imp 14529/14530).
        def.blockDefinitions.animation = Animation.STOP
        def.spawnDefinitions.deathAnimation = if (deathAnim >= 0) Animation(deathAnim) else Animation.STOP
        val immunities = EnumSet.noneOf(ImmunityType::class.java)
        if (traits.immunePoison) immunities += ImmunityType.POISON
        if (traits.immuneVenom) immunities += ImmunityType.VENOM
        def.immunityTypes = immunities
        return def
    }
}

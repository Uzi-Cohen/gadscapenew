package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.encounter.CombatTraits
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
 * Combat definitions for the room's NPCs. The server has no `cache/data/npcs/combat` file for 16667 / 16670, so
 * without this they would spawn with Defence 0 and 1 HP; the numbers are the cache 2746 npc config values kept in
 * `moia.json stats` / `binderStats` (spec §4.4 combat profile [CACHE]).
 */
internal object MoiaCombatDefs {

    private val LEVELS = arrayOf(StatType.ATTACK, StatType.STRENGTH, StatType.DEFENCE, StatType.MAGIC, StatType.RANGED)
    private val ATTACK = arrayOf(StatType.ATTACK_STAB, StatType.ATTACK_SLASH, StatType.ATTACK_CRUSH, StatType.ATTACK_MAGIC, StatType.ATTACK_RANGED)
    private val DEFENCE = arrayOf(StatType.DEFENCE_STAB, StatType.DEFENCE_SLASH, StatType.DEFENCE_CRUSH, StatType.DEFENCE_MAGIC, StatType.DEFENCE_RANGED)
    private val STRENGTH = arrayOf(StatType.MELEE_STRENGTH_BONUS, StatType.RANGED_STRENGTH_BONUS, StatType.MAGIC_STRENGTH_BONUS)

    fun build(
        id: Int,
        hitpoints: Int,
        attackSpeed: Int,
        profile: MoiaConfig.NpcProfile,
        style: AttackType,
        maxHit: Int,
        deathAnim: Int,
        traits: CombatTraits,
    ): NPCCombatDefinitions {
        val def = NPCCombatDefinitions.clone(id, NPCCDLoader.get(id))
        def.id = id
        def.hitpoints = hitpoints
        def.attackSpeed = attackSpeed
        // The room drives every attack; the engine's aggression and retaliation stay off.
        def.aggressionType = AggressionType.PASSIVE
        val stats = StatDefinitions()
        profile.levels.forEachIndexed { i, v -> stats.set(LEVELS[i], v) }
        profile.attack.forEachIndexed { i, v -> stats.set(ATTACK[i], v) }
        profile.defence.forEachIndexed { i, v -> stats.set(DEFENCE[i], v) }
        profile.strength.forEachIndexed { i, v -> stats.set(STRENGTH[i], v) }
        def.statDefinitions = stats
        def.attackDefinitions.type = style
        def.attackDefinitions.maxHit = maxHit
        def.spawnDefinitions.deathAnimation = Animation(deathAnim)
        val immunities = EnumSet.noneOf(ImmunityType::class.java)
        if (traits.immunePoison) immunities += ImmunityType.POISON
        if (traits.immuneVenom) immunities += ImmunityType.VENOM
        def.immunityTypes = immunities
        return def
    }
}

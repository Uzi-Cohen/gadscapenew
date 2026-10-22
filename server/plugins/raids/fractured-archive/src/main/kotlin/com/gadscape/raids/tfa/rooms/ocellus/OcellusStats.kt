package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.npc.combatdefs.NPCCombatDefinitions
import com.zenyte.game.world.entity.npc.combatdefs.StatType

/**
 * Combat stats of the Ocellus NPCs (spec §4.8 "Combat profile"), from the 2746 npc configs [CACHE
 * `raids4/ocellus/cfg/npc.txt`: hitpoints/attack/strength/defence/magic/ranged and params 3/4/5-8/10/12/14/65] plus
 * the wiki's Monster-Inspect values the cache lacks [DOC: P3 melee attack +150; ranged defence standard = the middle
 * of light/standard/heavy]. The server has no combat-definition file for raid NPCs, so the room builds them.
 *
 * Levels: attack, strength, defence, magic, ranged. Bonuses: attack stab/slash/crush/magic/ranged, defence
 * stab/slash/crush/magic/ranged(standard), strength melee/ranged/magic%.
 */
internal data class CombatProfile(
    val levels: IntArray,
    val attack: IntArray,
    val defence: IntArray,
    val strength: IntArray,
    val attackSpeed: Int,
    val style: AttackType,
) {
    /** Replaces [npc]'s combat definitions with this profile; HP is the caller's (scaled) value. */
    fun apply(npc: NPC, hitpoints: Int) {
        val def = NPCCombatDefinitions.clone(npc.id, null)
        def.hitpoints = hitpoints
        def.attackSpeed = attackSpeed
        def.setAttackStyle(style)
        val stats = def.statDefinitions
        StatType.levelTypes.forEachIndexed { i, t -> stats.set(t, levels[i]) }
        val attacks = arrayOf(StatType.ATTACK_STAB, StatType.ATTACK_SLASH, StatType.ATTACK_CRUSH, StatType.ATTACK_MAGIC, StatType.ATTACK_RANGED)
        val defences = arrayOf(StatType.DEFENCE_STAB, StatType.DEFENCE_SLASH, StatType.DEFENCE_CRUSH, StatType.DEFENCE_MAGIC, StatType.DEFENCE_RANGED)
        val strengths = arrayOf(StatType.MELEE_STRENGTH_BONUS, StatType.RANGED_STRENGTH_BONUS, StatType.MAGIC_STRENGTH_BONUS)
        attacks.forEachIndexed { i, t -> stats.set(t, attack[i]) }
        defences.forEachIndexed { i, t -> stats.set(t, defence[i]) }
        strengths.forEachIndexed { i, t -> stats.set(t, strength[i]) }
        npc.setCombatDefinitions(def)
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        /** P1 16674: 1/1/215/350/250; magic +150, ranged +150; def 150/95/60/100, ranged 135; str +1; speed 5. */
        val P1 = CombatProfile(
            levels = intArrayOf(1, 1, 215, 350, 250),
            attack = intArrayOf(1, 1, 1, 150, 150),
            defence = intArrayOf(150, 95, 60, 100, 135),
            strength = intArrayOf(1, 0, 0),
            attackSpeed = 5,
            style = AttackType.MAGIC,
        )

        /** P2 16675 and P3/P4 16677: 325/250/250/300/300; melee +150 [DOC], magic +350, str +10, magic dmg +10%; def 150/100/30/200, ranged 250; speed 6. */
        val P3 = CombatProfile(
            levels = intArrayOf(325, 250, 250, 300, 300),
            attack = intArrayOf(150, 150, 150, 350, 0),
            defence = intArrayOf(150, 100, 30, 200, 250),
            strength = intArrayOf(10, 0, 10),
            attackSpeed = 6,
            style = AttackType.SLASH,
        )

        /** The Blade hitbox 16676: def 250, magic 220; def 150/120/10/100, ranged 110 (standard); no attack. */
        val BLADE = CombatProfile(
            levels = intArrayOf(1, 1, 250, 220, 1),
            attack = intArrayOf(0, 0, 0, 0, 0),
            defence = intArrayOf(150, 120, 10, 100, 110),
            strength = intArrayOf(0, 0, 0),
            attackSpeed = 0,
            style = AttackType.CRUSH,
        )

        fun forNpc(id: Int): CombatProfile = when (id) {
            TfaIds.Npc.OCELLUS_P1 -> P1
            TfaIds.Npc.OCELLUS_BLADE_HITBOX -> BLADE
            else -> P3
        }
    }
}

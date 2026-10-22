package com.gadscape.raids.tfa.entry

import com.gadscape.raids.tfa.config.RaidConfig
import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.SkillConstants

/**
 * The GadScape entry gate (spec §3.1) [HOUSE], `tfa.json entryGate`:
 * - live needs While Guthix Sleeps [DOC]; GadScape has no WGS, so the gate is either `"waived"` (varbit 16354 = 1 for
 *   everyone at login, audit R4-16) or `"attunement"` (default): the levels and kills WGS proves.
 * - varbit 16354 `wgs_tfa_shortcut_unlocked` (varp 4234 bit 23) drives the entrance multiloc 62542: 0 shows 53638
 *   "Blocked cavity" (no ops), 1 shows 62543 "Opening" (Crawl-through) [CACHE].
 * - Meeting the requirements unlocks 16354; the messenger 16699 then hands over the Alliance's message 34657 and the
 *   miniquest starts (16426 = 1) ([MessengerService]).
 */
object TfaEntryGate {

    /** What the attunement requirements look at, read from base levels (pure, testable). */
    data class GateStats(
        val thieving: Int,
        val magic: Int,
        val agility: Int,
        val farming: Int,
        val herblore: Int,
        val hunter: Int,
        val firemaking: Int,
        val attack: Int,
        val strength: Int,
        val tormentedDemonKills: Int,
        val combatLevel: Int,
    )

    /** The unmet requirements as display lines (empty = gate passed). */
    @JvmStatic
    fun missing(req: RaidConfig.Requirements, s: GateStats): List<String> {
        val out = ArrayList<String>()
        fun level(name: String, have: Int, need: Int) {
            if (have < need) out += "$need $name"
        }
        level("Thieving", s.thieving, req.thieving)
        level("Magic", s.magic, req.magic)
        level("Agility", s.agility, req.agility)
        level("Farming", s.farming, req.farming)
        level("Herblore", s.herblore, req.herblore)
        level("Hunter", s.hunter, req.hunter)
        level("Firemaking", s.firemaking, req.firemaking)
        if (s.attack + s.strength < req.attackPlusStrength) out += "a combined ${req.attackPlusStrength} Attack and Strength"
        if (s.tormentedDemonKills < req.tormentedDemonKills) {
            out += "${req.tormentedDemonKills} Tormented demon kill" + if (req.tormentedDemonKills == 1) "" else "s"
        }
        if (s.combatLevel < req.combatLevel) out += "combat level ${req.combatLevel}"
        return out
    }

    @JvmStatic
    fun meets(req: RaidConfig.Requirements, s: GateStats): Boolean = missing(req, s).isEmpty()

    @JvmStatic
    fun statsOf(player: Player): GateStats {
        val skills = player.skills
        return GateStats(
            thieving = skills.getLevelForXp(SkillConstants.THIEVING),
            magic = skills.getLevelForXp(SkillConstants.MAGIC),
            agility = skills.getLevelForXp(SkillConstants.AGILITY),
            farming = skills.getLevelForXp(SkillConstants.FARMING),
            herblore = skills.getLevelForXp(SkillConstants.HERBLORE),
            hunter = skills.getLevelForXp(SkillConstants.HUNTER),
            firemaking = skills.getLevelForXp(SkillConstants.FIREMAKING),
            attack = skills.getLevelForXp(SkillConstants.ATTACK),
            strength = skills.getLevelForXp(SkillConstants.STRENGTH),
            tormentedDemonKills = player.notificationSettings.getKillcount("tormented demon"),
            combatLevel = skills.combatLevel,
        )
    }

    /** True once varbit 16354 is set (the entrance shows "Opening"). */
    @JvmStatic
    fun isUnlocked(player: Player): Boolean = player.varManager.getBitValue(TfaIds.Varbit.ENTRANCE_UNLOCKED) == 1

    /** True if [player] may be let in now under the configured gate mode. */
    @JvmStatic
    fun qualifies(player: Player, gate: RaidConfig.EntryGate = TfaConfig.current.raid.entryGate): Boolean =
        gate.mode == RaidConfig.GateMode.WAIVED || meets(gate.requirements, statsOf(player))

    /** Sets 16354 (the multiloc switches for this player at once). */
    @JvmStatic
    fun unlock(player: Player) {
        player.varManager.sendBit(TfaIds.Varbit.ENTRANCE_UNLOCKED, 1)
    }

    /**
     * Login / periodic check: `"waived"` unlocks everyone; `"attunement"` unlocks a player who now qualifies and
     * sends the messenger (once). Returns true if the gate opened just now.
     */
    @JvmStatic
    fun check(player: Player): Boolean {
        if (isUnlocked(player)) return false
        val gate = TfaConfig.current.raid.entryGate
        if (!qualifies(player, gate)) return false
        unlock(player)
        if (gate.mode == RaidConfig.GateMode.ATTUNEMENT) MessengerService.deliver(player)
        return true
    }

    /** The refusal (for a player who cannot enter yet), with what is missing. */
    @JvmStatic
    fun refuse(player: Player) {
        val gate = TfaConfig.current.raid.entryGate
        player.sendMessage(gate.refusalMessage)
        val missing = missing(gate.requirements, statsOf(player))
        if (missing.isNotEmpty()) player.sendMessage("You still need: ${missing.joinToString()}.")
    }
}

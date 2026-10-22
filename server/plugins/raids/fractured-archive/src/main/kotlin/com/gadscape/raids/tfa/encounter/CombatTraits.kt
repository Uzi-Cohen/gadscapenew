package com.gadscape.raids.tfa.encounter

import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.Evidence

/** Spell elements for weaknesses (E5) and spell-element hooks (E17). */
enum class Element { AIR, WATER, EARTH, FIRE }

/** Light / standard / heavy ranged defence (E6). */
data class RangedDefence(val light: Int, val standard: Int, val heavy: Int)

/**
 * Per-NPC combat rules the hotfix and the wiki give each boss (spec §2.2, §3.11). This is data only: the engine
 * hooks that apply it (E5-E9: weakness %, ranged defence types, defence floor, flat armour, freeze resistance,
 * demonbane %, Ancient Magicks immunity, inverted magic drain, flying) are WU-04's job, installed through
 * `TfaServices.combatTraits`. [TfaBossNpc] already applies the parts the engine supports today
 * (poison/venom immunity, cannon).
 *
 * In a boss JSON the block is `"traits": { ... }` with the property names below
 * (`rangedDef` is `[light, standard, heavy]`, `weakness` a lower-case element or null); read it with [read].
 */
data class CombatTraits(
    /** 60 → floor = ceil(0.6 × base Defence); null = drains can reach 0. */
    val defenceFloorPct: Int?,
    val weakness: Element?,
    /** e.g. FIRE 80. */
    val weaknessPct: Int,
    val rangedDef: RangedDefence?,
    val immunePoison: Boolean,
    val immuneVenom: Boolean,
    val immuneCannon: Boolean,
    /** 100 = immune; 40 = frozen for 60% of the duration. */
    val freezeResistPct: Int,
    /** Archive Guardian. */
    val immuneAncients: Boolean,
    /** 25 = Dreadnoth, 80 = Xarth and Ocellus; null = not a demon. */
    val demonbanePct: Int?,
    /** Bork 12, Smorks 2. */
    val flatArmour: Int,
    /** Bork 2. */
    val magicResist: Int,
    /** Archive Guardian: magic-level drains raise its Magic. */
    val magicDrainInverted: Boolean,
    /** Guardian flying form: only ranged, magic and halberds. */
    val flying: Boolean,
) {

    /** The Defence level drains cannot go below, or 0 when there is no floor. */
    fun defenceFloor(baseDefence: Int): Int {
        val pct = defenceFloorPct ?: return 0
        return (pct * baseDefence + 99) / 100 // ceil(pct% × base)
    }

    companion object {

        /**
         * The raid-wide boss default (§3.11): immune to poison, venom and cannon, 60% defence floor, nothing else.
         * Bosses copy it and change what their section says.
         */
        @JvmField
        val BOSS_DEFAULT = CombatTraits(
            defenceFloorPct = 60, weakness = null, weaknessPct = 0, rangedDef = null,
            immunePoison = true, immuneVenom = true, immuneCannon = true,
            freezeResistPct = 0, immuneAncients = false, demonbanePct = null,
            flatArmour = 0, magicResist = 0, magicDrainInverted = false, flying = false,
        )

        /** Adds can be poisoned (§3.11); cannon is not allowed anywhere in the raid. */
        @JvmField
        val ADD_DEFAULT = BOSS_DEFAULT.copy(defenceFloorPct = null, immunePoison = false, immuneVenom = false)

        /**
         * Reads a `traits` object with [default] as the per-key defaults. Most values are [Evidence.DOC]/[Evidence.CACHE]
         * facts; pass [evidence] = HOUSE for an NPC whose traits are guesses.
         */
        @JvmStatic
        @JvmOverloads
        fun read(r: ConfigReader, default: CombatTraits, key: String = "traits", evidence: Evidence = Evidence.DOC): CombatTraits =
            r.obj(key) {
                val ranged = intList("rangedDef", default.rangedDef?.let { listOf(it.light, it.standard, it.heavy) } ?: emptyList(),
                    evidence, size = 0..3, check = { if (it.size == 1 || it.size == 2) "must be [] or [light, standard, heavy]" else null })
                val weaknessName = string("weakness", default.weakness?.name?.lowercase() ?: "none", evidence,
                    allowed = listOf("none") + Element.entries.map { it.name.lowercase() })
                CombatTraits(
                    defenceFloorPct = intOrNull("defenceFloorPct", default.defenceFloorPct, evidence, 0, 100),
                    weakness = Element.entries.firstOrNull { it.name.lowercase() == weaknessName },
                    weaknessPct = int("weaknessPct", default.weaknessPct, evidence, 0, 1000),
                    rangedDef = if (ranged.size == 3) RangedDefence(ranged[0], ranged[1], ranged[2]) else null,
                    immunePoison = bool("immunePoison", default.immunePoison, evidence),
                    immuneVenom = bool("immuneVenom", default.immuneVenom, evidence),
                    immuneCannon = bool("immuneCannon", default.immuneCannon, evidence),
                    freezeResistPct = int("freezeResistPct", default.freezeResistPct, evidence, 0, 100),
                    immuneAncients = bool("immuneAncients", default.immuneAncients, evidence),
                    demonbanePct = intOrNull("demonbanePct", default.demonbanePct, evidence, 0, 1000),
                    flatArmour = int("flatArmour", default.flatArmour, evidence, 0, 1000),
                    magicResist = int("magicResist", default.magicResist, evidence, 0, 1000),
                    magicDrainInverted = bool("magicDrainInverted", default.magicDrainInverted, evidence),
                    flying = bool("flying", default.flying, evidence),
                )
            }
    }
}

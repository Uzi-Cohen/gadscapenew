package com.gadscape.raids.tfa.encounter

import com.gadscape.raids.tfa.CombatTraitsBinder
import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.Evidence
import com.google.common.eventbus.Subscribe
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combat.NpcCombatTraits
import com.zenyte.game.world.entity.npc.combat.RangedAttackType
import com.zenyte.game.world.entity.npc.combat.SpellElement
import com.zenyte.game.world.entity.player.SkillConstants
import com.zenyte.plugins.events.ServerLaunchEvent

/** Spell elements for weaknesses (E5) and spell-element hooks (E17). Same constants as the engine's [SpellElement]. */
enum class Element {
    AIR, WATER, EARTH, FIRE;

    /** The engine element (`NPC.onSpellHit` reports these). */
    val engine: SpellElement get() = SpellElement.valueOf(name)

    companion object {
        /** The raid element of an engine element, or null. */
        @JvmStatic
        fun of(element: SpellElement?): Element? = element?.let { valueOf(it.name) }
    }
}

/** Light / standard / heavy ranged defence (E6). */
data class RangedDefence(val light: Int, val standard: Int, val heavy: Int) {
    fun of(type: RangedAttackType): Int = when (type) {
        RangedAttackType.LIGHT -> light
        RangedAttackType.STANDARD -> standard
        RangedAttackType.HEAVY -> heavy
    }
}

/**
 * Per-NPC combat rules the hotfix and the wiki give each boss (spec §2.2, §3.11). The engine applies them through
 * the core hook [NpcCombatTraits] (WU-04, E5-E9): [TfaBossNpc.getCombatTraits] hands the engine a live
 * [CombatTraitsView] of the boss's [TfaNpc.traits], and every other raid NPC gets one from [CombatTraitsBinding]
 * when [TfaNpc] applies its traits on spawn. The engine asks on every hit, so a subclass that returns different
 * traits (e.g. `if (dazed) DAZED else NORMAL`) changes the rules at once. [TfaNpc] applies the parts the stock engine
 * already had (poison/venom immunity, cannon).
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
    /** Player melee attacks skip the accuracy roll (Bork while dazed, spec §4.3). Code only, not a JSON key. */
    val guaranteedMelee: Boolean = false,
    /** Player ranged attacks skip the accuracy roll. */
    val guaranteedRanged: Boolean = false,
    /** Player magic attacks skip the accuracy roll. */
    val guaranteedMagic: Boolean = false,
    /** Refusal text for melee on a flying NPC; null = "The {name} is flying too high to hit." [HOUSE, spec §4.1]. */
    val flyingMessage: String? = null,
    /** Refusal text for an Ancient Magicks spell; null = "The {name} is immune to Ancient Magicks." [HOUSE]. */
    val ancientsMessage: String? = null,
) {

    /** The Defence level drains cannot go below, or 0 when there is no floor. */
    fun defenceFloor(baseDefence: Int): Int {
        val pct = defenceFloorPct ?: return 0
        return (pct * baseDefence + 99) / 100 // ceil(pct% × base)
    }

    /** A fixed engine view of these traits (for a dynamic one use [CombatTraitsView] with a supplier). */
    fun toEngine(): NpcCombatTraits = CombatTraitsView { this }

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
                    flatArmour = int("flatArmour", default.flatArmour, evidence, -1000, 1000),
                    magicResist = int("magicResist", default.magicResist, evidence, -1000, 1000),
                    magicDrainInverted = bool("magicDrainInverted", default.magicDrainInverted, evidence),
                    flying = bool("flying", default.flying, evidence),
                    // Runtime state, not config (e.g. Bork dazed): carried over from the default, no JSON keys.
                    guaranteedMelee = default.guaranteedMelee,
                    guaranteedRanged = default.guaranteedRanged,
                    guaranteedMagic = default.guaranteedMagic,
                    flyingMessage = default.flyingMessage,
                    ancientsMessage = default.ancientsMessage,
                )
            }
    }
}

/**
 * The engine's view ([NpcCombatTraits], WU-04) of a raid NPC's [CombatTraits]. [source] is read on every call, so the
 * view follows a subclass whose traits change during the fight.
 *
 * | Trait | Engine rule |
 * |---|---|
 * | `weakness`/`weaknessPct` | standard elemental spells of that element: +pct% accuracy, + floor(base max × pct%) |
 * | `rangedDef` | defence bonus by light (thrown) / standard (bows) / heavy (crossbows, ballistae, chinchompas) |
 * | `defenceFloorPct` | Defence never drained below ceil(pct% × base Defence), whatever the drain source |
 * | `flatArmour` / `magicResist` | taken off successful player melee+ranged / magic hitsplats, never below 1 |
 * | `guaranteed*` | player attacks of that style skip the accuracy roll |
 * | `freezeResistPct` | freezes last floor(duration × (100 − pct) / 100); 100 = immune |
 * | `demonbanePct` | non-null = demon; demonbane weapons/spells work at pct% (null = not a demon) |
 * | `immuneAncients` | Ancient Magicks combat spells are refused |
 * | `magicDrainInverted` | magic-level drains raise Magic |
 * | `flying` | melee refused except halberds |
 */
class CombatTraitsView(private val source: () -> CombatTraits) : NpcCombatTraits {

    private val traits: CombatTraits get() = source()

    override fun weaknessElement(): SpellElement? = traits.weakness?.engine

    override fun weaknessPercent(): Int = if (traits.weakness == null) 0 else traits.weaknessPct

    override fun rangedDefenceBonus(type: RangedAttackType, fallback: Int): Int = traits.rangedDef?.of(type) ?: fallback

    override fun drainFloor(skill: Int, baseLevel: Int): Int =
        if (skill == SkillConstants.DEFENCE) traits.defenceFloor(baseLevel) else 0

    override fun magicDrainInverted(): Boolean = traits.magicDrainInverted

    override fun flatArmour(): Int = traits.flatArmour

    override fun magicResistance(): Int = traits.magicResist

    override fun guaranteedHit(style: HitType): Boolean = when (style) {
        HitType.MELEE -> traits.guaranteedMelee
        HitType.RANGED -> traits.guaranteedRanged
        HitType.MAGIC -> traits.guaranteedMagic
        else -> false
    }

    override fun freezeResistancePercent(): Int = traits.freezeResistPct

    override fun isDemon(): Boolean = traits.demonbanePct != null

    override fun demonbanePercent(): Int = traits.demonbanePct ?: 100

    override fun immuneToAncientMagicks(): Boolean = traits.immuneAncients

    override fun ancientMagicksMessage(): String? = traits.ancientsMessage

    override fun flying(): Boolean = traits.flying

    override fun flyingMessage(): String? = traits.flyingMessage
}

/**
 * [CombatTraitsBinder] installed into [TfaServices.combatTraits] at launch: gives a raid NPC a live engine view of its
 * traits ([TfaNpc.traits] is re-read on every hit), or a fixed view for any other NPC.
 */
object CombatTraitsBinding : CombatTraitsBinder {
    override fun bind(npc: NPC, traits: CombatTraits) {
        npc.combatTraits = if (npc is TfaNpc) CombatTraitsView { npc.traits } else traits.toEngine()
    }

    override fun toString() = "CombatTraitsBinding (engine-combat, WU-04)"
}

/**
 * Installs [CombatTraitsBinding] and the `::tfa combat` checks ([EngineCombatDebug]) once the world thread exists
 * (plugin-scanned, like every unit's module object).
 */
object CombatTraitsModule {
    @Subscribe
    @JvmStatic
    fun onServerLaunch(@Suppress("UNUSED_PARAMETER") event: ServerLaunchEvent) {
        TfaServices.combatTraits = CombatTraitsBinding
        EngineCombatDebug.register()
    }
}

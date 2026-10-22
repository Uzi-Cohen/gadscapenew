package com.gadscape.raids.tfa.encounter

import com.gadscape.raids.tfa.TfaServices
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combatdefs.ImmunityType
import com.zenyte.game.world.entity.player.Player
import java.util.EnumSet

/**
 * Base of every NPC a Fractured Archive room spawns (bosses, adds, helpers):
 * - multicombat everywhere (§3.11), never respawns, never becomes tolerant;
 * - applies its [traits] on [spawn] (poison/venom immunity and cannon here; the rest via
 *   `TfaServices.combatTraits`, WU-04);
 * - reports every point of damage a player deals, capped at the HP it had (§3.10), to [onDamageDealt], which
 *   forwards it to `TfaServices.contribution` (WU-16). Override [onDamageDealt] for shared pools (Dagon'hai).
 *
 * Note: the Java `NPC` constructor calls overridable methods before Kotlin subclass fields are set. Overrides
 * that may run during construction must tolerate unset fields (see [contextOrNull]).
 */
open class TfaNpc(
    context: TfaEncounterContext,
    id: Int,
    tile: Location,
    facing: Direction = Direction.SOUTH,
) : NPC(id, tile, facing, 0) {

    private val encounterContext: TfaEncounterContext? = context

    /** The encounter context; null only while the `NPC` super constructor runs. */
    protected val contextOrNull: TfaEncounterContext? get() = encounterContext

    val context: TfaEncounterContext get() = encounterContext ?: error("TfaNpc context used during construction")

    /** Combat rules of this NPC; adds default to [CombatTraits.ADD_DEFAULT]. */
    open val traits: CombatTraits get() = CombatTraits.ADD_DEFAULT

    init {
        spawned = true
        supplyCache = false
        isForceMultiArea = true
    }

    override fun spawn(): NPC {
        applyTraits()
        return super.spawn()
    }

    /** Applies [traits]; call again after a transformation that reloads combat definitions. */
    protected open fun applyTraits() {
        val t = traits
        val immunities = EnumSet.noneOf(ImmunityType::class.java)
        if (t.immunePoison) immunities += ImmunityType.POISON
        if (t.immuneVenom) immunities += ImmunityType.VENOM
        combatDefinitions?.immunityTypes = immunities
        TfaServices.combatTraits.bind(this, t)
    }

    override fun canBeMulticannoned(player: Player): Boolean = !traits.immuneCannon && super.canBeMulticannoned(player)

    override fun isTolerable(): Boolean = false

    /** Raid NPCs never respawn. */
    override fun setRespawnTask() {}

    override fun processHit(hit: Hit) {
        val before = hitpoints
        super.processHit(hit)
        val dealt = before - hitpoints
        if (dealt > 0) {
            val source = hit.source
            if (source is Player) onDamageDealt(source, dealt, hit)
        }
        onHitProcessed(hit)
    }

    /** A player took [amount] HP off this NPC (already capped at its remaining HP). */
    protected open fun onDamageDealt(player: Player, amount: Int, hit: Hit) {
        val ctx = encounterContext ?: return
        TfaServices.contribution.recordDamage(ctx, player, this, amount)
    }

    /** Called after every processed hit (damage, heal or zero). */
    protected open fun onHitProcessed(hit: Hit) {}
}

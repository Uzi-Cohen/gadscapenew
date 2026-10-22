package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.encounter.TfaNpc
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.magic.CombatSpell
import com.zenyte.game.world.entity.npc.combat.SpellElement

/**
 * One of the Dagon'hai trio (spec §4.6): the Elder 16643 or a monk 16645 / 16647, size 2, and after the explosion
 * their unshielded forms 16644 / 16646 / 16648 (7 HP). Every action is scripted by [DagonhaiFight] through
 * [DagonhaiRoom]; the engine's aggression, retaliation and attack loop are off (movement still runs: the monks walk
 * between desks).
 *
 * - **Shared pool** (spec "Shared pool"): while shielded, the NPC's HP is the pool. A hit is capped at the pool by
 *   `NPC.processHit`, recorded for contribution by [TfaNpc] (capped), then the room syncs the other two. The pool
 *   headbar (89 or 86) replaces the stock health bar. Reaching 0 is a break ([sendDeath] → the room), never a death.
 * - **Protected** after a break: not attackable ("shielded by Zamorak's energy"), hits do nothing.
 * - **Demonic strength**: a damaging hit from a buffed player is multiplied before the pool cap.
 * - **Channel interrupt** (E17): [onSpellHit] reports the element of every standard spell that lands (or splashes).
 * - **Empowered**: protection prayers only reduce the trio's magic by 25% ([getMagicPrayerMultiplier]).
 * - **Unshielded**: normal 7-HP NPCs with the stock health bar; their death is reported to the room, which plays
 *   14652 / 14653 and removes them (no drops, no engine death task).
 */
internal class DagonhaiNpc(
    room: DagonhaiRoom,
    context: TfaEncounterContext,
    role: Role,
    tile: Location,
    facing: Direction,
) : TfaNpc(context, DagonhaiIds.shieldedNpc(role), tile, facing) {

    /** Null only while the `NPC` super constructor runs. */
    private val roomRef: DagonhaiRoom? = room

    private val roleRef: Role? = role

    val role: Role get() = roleRef ?: Role.ELDER

    /** What this NPC accepts from players (set by the room). */
    var shield: ShieldState = ShieldState.SHIELDED

    /** Ritual complete: protection prayers only take 25% off its magic. */
    var empowered: Boolean = false

    /** Dead (explosion or final kill): not attackable, waiting for removal. */
    var killed: Boolean = false
        private set

    init {
        maxDistance = 64
        radius = 0
    }

    override val traits: CombatTraits get() = roomRef?.cfg?.traits ?: DagonhaiConfig.DEFAULT_TRAITS

    /**
     * `NPC.spawn()` (and a transformation) reload the definitions from `NPCCDLoader`, which has nothing for these ids
     * (and would leave them with 1 HP and Defence 0): install the cache profile from `dagonhai.json stats`. The room
     * sets the HP itself (pool or 7) right after.
     */
    override fun updateCombatDefinitions() {
        val room = roomRef ?: return super.updateCombatDefinitions()
        val hp = if (shield == ShieldState.UNSHIELDED) room.cfg.finalHp else room.poolMax
        combatDefinitions = DagonhaiCombatDefs.trio(id, hp, room.cfg, DagonhaiIds.seqDeath(role))
    }

    /** `NPC.spawn()` replaced the definitions [applyTraits] wrote the immunities into: apply the traits again. */
    override fun spawn(): NPC {
        val npc = super.spawn()
        applyTraits()
        return npc
    }

    /** Becomes the unshielded NPC with [hp] hitpoints (spec EXPLOSION). */
    fun unshield(hp: Int) {
        shield = ShieldState.UNSHIELDED
        empowered = false
        setTransformation(DagonhaiIds.unshieldedNpc(role))
        applyTraits()
        setHitpoints(hp)
    }

    /** Marks it dead for the room's own death sequence (no engine death task, no drop). */
    fun markKilled() {
        if (killed) return
        killed = true
        resetWalkSteps()
        combat.removeTarget()
        isCantInteract = true
    }

    // ------------------------------------------------------------------------------------------------ hits

    override fun processHit(hit: Hit) {
        val room = roomRef
        if (room != null && shield != ShieldState.UNSHIELDED && hit.hitType != HitType.HEALED) {
            if (shield == ShieldState.PROTECTED || !room.poolOpen()) {
                hit.damage = 0
            } else if (hit.damage > 0) {
                val player = hit.source as? Player
                if (player != null) {
                    val mult = room.strengthMultiplier(player)
                    if (mult != 1.0) hit.damage = (hit.damage * mult).toInt()
                }
            }
        }
        super.processHit(hit)
        room?.onTrioHit(this, hit)
    }

    /** While shielded the room shows the pool headbar on all three instead of the stock health bar. */
    override fun addHitbar() {
        if (shield == ShieldState.UNSHIELDED) super.addHitbar()
    }

    override fun onSpellHit(caster: Player, spell: CombatSpell, element: SpellElement?, hit: Hit, splash: Boolean) {
        roomRef?.onSpellLanded(this, Element.of(element), splash)
    }

    override fun sendDeath() {
        val room = roomRef ?: return super.sendDeath()
        if (shield != ShieldState.UNSHIELDED) {
            room.onPoolDepleted(this)
            return
        }
        if (killed) return
        val source = mostDamagePlayer
        onDeath(source)
        markKilled()
        room.onFinalDeath(this)
    }

    // ------------------------------------------------------------------------------------- player attacks

    override fun canAttack(source: Player): Boolean {
        if (killed || isFinished) return false
        if (shield == ShieldState.PROTECTED || roomRef?.poolBlocked() == true) {
            source.sendMessage(PROTECTED_MESSAGE)
            return false
        }
        return super.canAttack(source)
    }

    override fun isAttackableNPC(): Boolean =
        !killed && shield != ShieldState.PROTECTED && roomRef?.poolBlocked() != true && super.isAttackableNPC()

    /** Empowered: "decimate players, even when praying" (spec: prayer −25%). */
    override fun getMagicPrayerMultiplier(): Double =
        if (empowered) roomRef?.cfg?.empowered?.prayerMult ?: 0.0 else 0.0

    // ------------------------------------------------------------------------- the room scripts everything

    override fun processNPC() {}

    override fun checkAggressivity(): Boolean = false

    override fun autoRetaliate(source: Entity) {}

    /** No defend seq exists for the trio (seqs 14580-14682). */
    override fun performDefenceAnimation(attacker: Entity) {}

    /** Raid bosses neither regenerate HP nor restore drained stats. */
    override fun isCycleHealable(): Boolean = false

    /** Drops nothing (rewards come from the vault). */
    override fun drop(tile: Location) {}

    companion object {
        /** [HOUSE] message while Zamorak's protection holds. */
        const val PROTECTED_MESSAGE = "The Dagon'hai are shielded by Zamorak's energy."
    }
}

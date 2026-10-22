package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.encounter.TfaNpc
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combat.CombatScript
import com.zenyte.game.world.entity.player.Player

/**
 * An Imp, npc 16650 `tfa_dagonhai_demon` (spec §4.6 PROTECTED): 44 HP (not scaled), attack rate 4, Att 250 / Str 195 /
 * Def 50; stand 13841 / walk 13844 (framemap 1240, the Leagues 6 melee thrall imp). The stock NPC combat loop chases
 * its target and calls [attack] every 4 ticks in melee reach: seq 14529 (silent attack), a melee hit (max 20) that
 * Protect from Melee halves ([getMeleePrayerMultiplier], COMM "reduced"). The room picks a random player as its target
 * and retargets when it is lost. Death: the silent despawn 14530 + its vfx 3736, removal after 2 t; the killer gains
 * demonic strength. Damage to imps counts for contribution ([TfaNpc]).
 */
internal class Imp(
    room: DagonhaiRoom,
    context: TfaEncounterContext,
    tile: Location,
) : TfaNpc(context, DagonhaiIds.IMP, tile, Direction.SOUTH), CombatScript {

    private val roomRef: DagonhaiRoom? = room

    var dying: Boolean = false
        private set

    init {
        maxDistance = 64 // never "retreat" inside the arena (stock default 10)
        radius = 0
    }

    override val traits: CombatTraits get() = roomRef?.cfg?.impTraits ?: DagonhaiConfig.DEFAULT_IMP_TRAITS

    override fun updateCombatDefinitions() {
        val room = roomRef ?: return super.updateCombatDefinitions()
        combatDefinitions = DagonhaiCombatDefs.imp(id, room.cfg)
        setHitpoints(room.cfg.protect.impHp)
    }

    override fun spawn(): NPC {
        val npc = super.spawn()
        applyTraits()
        return npc
    }

    /** The room chooses the target (a random player); no stock aggression roll. */
    override fun checkAggressivity(): Boolean = false

    override fun canAttack(source: Player): Boolean = !dying && !isDead && super.canAttack(source)

    override fun isAttackableNPC(): Boolean = !dying && super.isAttackableNPC()

    override fun isCycleHealable(): Boolean = false

    /** Protect from Melee halves an imp's hit (spec "Protect from Melee halves" [COMM "reduced"]). */
    override fun getMeleePrayerMultiplier(): Double = roomRef?.cfg?.protect?.impPfmMult ?: 0.5

    override fun attack(target: Entity): Int {
        val room = roomRef ?: return DEFAULT_SPEED
        val cfg = room.cfg.protect
        if (dying || isDead) return 1
        setAnimation(Animation(DagonhaiIds.SEQ_IMP_ATTACK))
        val player = target as? Player
        val damage = if (player == null) 0 else room.rollNpcHit(this, player, cfg.impMax, DagonhaiCombatDefs.impStyle(cfg.impStyle))
        // Melee lands this tick; the protection prayer is applied as it lands (Player.appendHitEntry).
        delayHit(this, 0, target, Hit(this, damage, HitType.MELEE))
        return cfg.impSpeed
    }

    override fun sendDeath() {
        if (dying) return
        dying = true
        val source = mostDamagePlayer
        onDeath(source)
        isCantInteract = true
        setAnimation(Animation(DagonhaiIds.SEQ_IMP_DEATH))
        setGraphics(Graphics(DagonhaiIds.SPOT_IMP_DESPAWN))
        roomRef?.onImpDeath(this, source)
    }

    override fun drop(tile: Location) {}

    private companion object {
        const val DEFAULT_SPEED = 4
    }
}

/**
 * A Demonic rift, npc 16651 `tfa_dagonhai_rift` (spec PROTECTED: "3 rifts each drop 2 imps"): no ops, stand 9355 (the
 * GotR portal, from its npc config). Spawned and removed by the room; it only marks where the imps drop.
 */
internal class DemonicRift(
    room: DagonhaiRoom,
    context: TfaEncounterContext,
    tile: Location,
) : TfaNpc(context, DagonhaiIds.RIFT, tile, Direction.SOUTH) {

    private val roomRef: DagonhaiRoom? = room

    override val traits: CombatTraits get() = roomRef?.cfg?.impTraits ?: DagonhaiConfig.DEFAULT_IMP_TRAITS

    override fun updateCombatDefinitions() {
        val room = roomRef ?: return super.updateCombatDefinitions()
        combatDefinitions = DagonhaiCombatDefs.rift(id, room.cfg)
    }

    override fun canAttack(source: Player): Boolean = false

    override fun isAttackableNPC(): Boolean = false

    override fun processHit(hit: Hit) {}

    override fun sendDeath() {}

    override fun processNPC() {}

    override fun checkAggressivity(): Boolean = false

    override fun autoRetaliate(source: Entity) {}

    override fun isCycleHealable(): Boolean = false

    override fun drop(tile: Location) {}
}

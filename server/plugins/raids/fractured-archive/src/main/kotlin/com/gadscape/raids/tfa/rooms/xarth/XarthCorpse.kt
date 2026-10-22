package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.encounter.TfaNpc
import com.gadscape.raids.tfa.rooms.xarth.Timeline.Tag
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combat.SpellElement
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.magic.CombatSpell

/**
 * Corpse, npc 16687 (doused 16688) (spec §4.7 "Corpses"; wiki Corpse_(Xarth); template `NightmareSleepwalker`).
 * A [TfaNpc] add: damage on it counts toward contribution, it can be poisoned and frozen (resistance 0), water 40%.
 *
 * It never attacks and never chases: [CorpseSwarm] walks it one tile per tick straight at Xarth (through flames,
 * re-sending 4383 / 4384 each step) and absorbs it on arrival. Spell rules (E17):
 * - a water spell hit douses it: 16687 → 16688 (Def −20, idle `human_ready`), HP kept;
 * - water and ice spells land with no projectile delay (hit and freeze on the cast tick);
 * - a fire spell hit summons one extra corpse (at most once per `fireExtraCooldown`).
 */
internal class XarthCorpse(
    room: XarthRoom,
    context: TfaEncounterContext,
    tile: Location,
    val pile: Pile,
    startHp: Int,
) : TfaNpc(context, XarthIds.CORPSE, tile, Direction.SOUTH) {

    enum class Phase { SPAWNING, WALKING, ABSORBING, DYING }

    /** Null only while the `NPC` super constructor runs. */
    private val roomRef: XarthRoom? = room
    private val hpAtSpawn: Int = startHp
    private var defsInstalled = false

    var phase: Phase = Phase.SPAWNING
    var spawnTick: Int = 0
    var doused: Boolean = false
        private set
    var targetable: Boolean = false
        private set
    var steps: Int = 0

    /** Still a threat: spawning or walking (the cap counts these). */
    val active: Boolean get() = !isFinished && (phase == Phase.SPAWNING || phase == Phase.WALKING)

    override val traits: CombatTraits
        get() {
            val c = roomRef?.config?.corpses ?: return XarthConfig.CORPSE_TRAITS
            return if (doused) c.dousedTraits else c.traits
        }

    /**
     * Our definitions for the current id (the cache's 16687/16688 have no GadScape combat def). The HP is set once, at
     * the first install; a douse (transformation) keeps it.
     */
    override fun updateCombatDefinitions() {
        val room = roomRef ?: return super.updateCombatDefinitions()
        combatDefinitions = XarthCombatDefs.corpse(room.config.corpses, id, hpAtSpawn)
        if (!defsInstalled) {
            defsInstalled = true
            setHitpoints(hpAtSpawn)
        }
    }

    override fun spawn(): NPC {
        val npc = super.spawn()
        applyTraits() // NPC.spawn replaced the combat definitions after TfaNpc applied the traits (ENC-dreadnoth §8)
        return npc
    }

    fun setTargetable(value: Boolean) {
        targetable = value
        isCantInteract = !value
    }

    /** Water spell hit: 16687 → 16688, HP kept (spec §4.7 [DOC]). */
    fun douse() {
        if (doused || isFinished || isDead) return
        doused = true
        setTransformation(XarthIds.CORPSE_DOUSED)
        applyTraits()
    }

    // ------------------------------------------------------------------------------- scripted by the room

    override fun processNPC() {}

    override fun afterMovement() {}

    override fun autoRetaliate(source: Entity) {}

    override fun checkAggressivity(): Boolean = false

    /** Corpses walk through each other and the boss (sleepwalker template). */
    override fun isEntityClipped(): Boolean = false

    override fun canAttack(source: Player): Boolean = targetable && !isDead && super.canAttack(source)

    override fun isAttackableNPC(): Boolean = targetable && super.isAttackableNPC()

    override fun isCycleHealable(): Boolean = false

    /** The corpse seq set (14999, 15000, 15003, 15005) has no defend seq. */
    override fun performDefenceAnimation(attacker: Entity) {}

    override fun drop(tile: Location) {}

    override fun sendDeath() {
        roomRef?.corpses?.onDeath(this)
    }

    override fun onSpellHit(caster: Player, spell: CombatSpell, element: SpellElement?, hit: Hit, splash: Boolean) {
        roomRef?.onCorpseSpell(this, element, splash)
    }

    override fun getSpellHitDelay(caster: Player, spell: CombatSpell, defaultDelay: Int): Int =
        if (Bridge.isWaterOrIce(spell)) 0 else defaultDelay
}

/**
 * The live corpses of the room: spawn on a pile's entry tile, untargetable for `untargetableTicks`, then one step per
 * tick at Xarth until frozen or in reach (Chebyshev `arrivalDistance` from his 3x3), where they are absorbed (15005 +
 * 4386, despawn after 2 t) and heal him for their remaining HP (spot 4387 on him). Death: 15003 + 4385, despawn
 * after 4 t.
 */
internal class CorpseSwarm(private val room: XarthRoom) {

    private val list = ArrayList<XarthCorpse>()

    /** Spawning or walking corpses (for the cap). */
    val alive: Int get() = list.count { it.active }

    val all: List<XarthCorpse> get() = list.toList()

    fun spawn(pile: Pile) {
        val cfg = room.config.corpses
        val corpse = XarthCorpse(room, room.context, room.translate(pile.entry), pile, room.corpseHp())
        corpse.spawn()
        corpse.spawnTick = room.timeline.now
        corpse.setTargetable(cfg.untargetableTicks <= 0)
        if (cfg.untargetableTicks <= 0) corpse.phase = XarthCorpse.Phase.WALKING
        list += corpse
    }

    /** Room tick: spawn grace, arrivals, then steps. */
    fun tick() {
        val cfg = room.config.corpses
        val now = room.timeline.now
        list.removeAll { it.isFinished }
        for (c in list.toList()) { // a copy: an absorb can queue work that spawns corpses
            if (c.isFinished) continue
            when (c.phase) {
                XarthCorpse.Phase.SPAWNING -> if (now - c.spawnTick >= cfg.untargetableTicks) {
                    c.setTargetable(true)
                    c.phase = XarthCorpse.Phase.WALKING
                    step(c)
                }
                XarthCorpse.Phase.WALKING -> {
                    val here = room.toStatic(c.location)
                    if (XarthArena.isArrival(here, cfg.arrivalDistance, room.bossSw)) absorb(c) else step(c)
                }
                else -> {}
            }
        }
    }

    private fun step(c: XarthCorpse) {
        if (c.isMovementRestricted || c.isDead) return // frozen (resistance 0) or stunned: it waits
        val here = room.toStatic(c.location)
        val (dx, dy) = XarthArena.stepToward(here, XarthArena.CENTRE)
        if (dx == 0 && dy == 0) return
        val x = c.x
        val y = c.y
        c.resetWalkSteps()
        val moved = c.addWalkStep(x + dx, y + dy, x, y, true) ||
            (dx != 0 && dy != 0 && (c.addWalkStep(x + dx, y, x, y, true) || c.addWalkStep(x, y + dy, x, y, true)))
        if (moved) {
            c.steps++
            c.graphics = Graphics(if (c.doused) XarthIds.SPOT_CORPSE_WALK_DOUSED else XarthIds.SPOT_CORPSE_WALK)
        }
    }

    private fun absorb(c: XarthCorpse) {
        c.phase = XarthCorpse.Phase.ABSORBING
        c.setTargetable(false)
        c.resetWalkSteps()
        c.animation = Animation(XarthIds.SEQ_CORPSE_ABSORB)
        c.graphics = Graphics(XarthIds.SPOT_CORPSE_ABSORBED)
        room.onCorpseAbsorbed(c, c.hitpoints)
        room.timeline.after(room.config.corpses.absorbTicks, Tag.NPC) { remove(c) }
    }

    /** HP 0, or his death: 15003 + 4385, despawn after `deathTicks`. Idempotent. */
    fun onDeath(c: XarthCorpse) {
        if (c.phase == XarthCorpse.Phase.DYING || c.isFinished) return
        val wasAbsorbing = c.phase == XarthCorpse.Phase.ABSORBING
        c.phase = XarthCorpse.Phase.DYING
        c.setTargetable(false)
        c.resetWalkSteps()
        if (wasAbsorbing) return // already being removed
        c.animation = Animation(XarthIds.SEQ_CORPSE_DEATH)
        c.graphics = Graphics(XarthIds.SPOT_CORPSE_DEATH)
        room.timeline.after(room.config.corpses.deathTicks, Tag.NPC) { remove(c) }
    }

    private fun remove(c: XarthCorpse) {
        if (!c.isFinished) c.finish()
        list.remove(c)
        room.onCorpseGone(c)
    }

    /** His death: every corpse still walking or spawning dies. */
    fun killAll() {
        for (c in list.toList()) if (c.active) onDeath(c)
    }

    /** Teardown: remove everything now. */
    fun despawnAll() {
        for (c in list.toList()) if (!c.isFinished) c.finish()
        list.clear()
    }

    fun debugKillAll() {
        for (c in list.toList()) if (c.active && !c.isDead) c.setHitpoints(0)
    }

    fun describe(): String =
        "corpses ${list.size} (${list.groupingBy { it.phase }.eachCount().entries.joinToString { "${it.key} ${it.value}" }}), " +
            "doused ${list.count { it.doused }}"
}

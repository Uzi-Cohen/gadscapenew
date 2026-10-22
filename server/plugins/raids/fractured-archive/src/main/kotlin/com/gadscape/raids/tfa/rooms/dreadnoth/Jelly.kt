package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.encounter.TfaNpc
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.rooms.dreadnoth.Timeline.Tag
import com.zenyte.game.util.Direction
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combat.CombatScript
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities

/**
 * A Jelly, npc 16666 (spec §4.5 "Jellies"): 75 HP, stock jelly anims (ready 1583, walk 1584, attack 1586, defend 1585,
 * death 1587), fire 60%, freeze resistance 20%, demon, cannon-immune, poisonable (adds, §3.11).
 *
 * The stock NPC combat loop walks it to its target and calls [attack] every 5 ticks when adjacent: a melee hit (max 17,
 * Protect from Melee blocks it) and a prayer drain of 2 when the attack lands, blocked or not (E11, TzKih pattern).
 * When it has had no melee path for `rangedStuckTicks` ticks it spits instead (seq 15161, ranged, same max and drain;
 * U74). The room ([JellySwarm]) chooses its target: the nearest player.
 */
internal class Jelly(
    room: DreadnothRoom,
    context: TfaEncounterContext,
    tile: Location,
) : TfaNpc(context, DreadnothIds.JELLY, tile, Direction.SOUTH), CombatScript {

    private val roomRef: DreadnothRoom? = room

    /** True while forming, rising or hopping: untargetable and harmless. */
    var passive: Boolean = true
        private set

    private var stuckTicks = 0
    private var tileBeforeMove = -1

    init {
        maxDistance = 64 // never "retreat" inside the 15x15 arena (stock default 10)
        radius = 0
    }

    private val cfg: DreadnothConfig.Jelly? get() = roomRef?.config?.jelly

    override val traits: CombatTraits get() = cfg?.traits ?: DreadnothConfig.JELLY_TRAITS

    /** `NPC.spawn()` reloads definitions from `NPCCDLoader` (none for 16666): install ours and the 75 HP. */
    override fun updateCombatDefinitions() {
        val c = cfg ?: return super.updateCombatDefinitions()
        combatDefinitions = DreadnothCombatDefs.jelly(c)
        setHitpoints(c.hp)
    }

    override fun spawn(): NPC {
        val npc = super.spawn()
        applyTraits() // NPC.spawn() replaced the definitions the immunities were written into
        return npc
    }

    fun setPassive(value: Boolean) {
        passive = value
        isCantInteract = value
        if (value) combat.removeTarget()
    }

    // --------------------------------------------------------------------------------------------- targeting

    /** The room picks the target (nearest player); no stock aggression roll. */
    override fun checkAggressivity(): Boolean = false

    override fun canAttack(source: Player): Boolean = !passive && !isDead && super.canAttack(source)

    /** Area attacks (barrage, chinchompas) skip a jelly that is still forming, rising or hopping. */
    override fun isAttackableNPC(): Boolean = !passive && super.isAttackableNPC()

    override fun isCycleHealable(): Boolean = false

    // --------------------------------------------------------------------------------------------- attacks

    override fun attack(target: Entity): Int {
        val c = cfg ?: return 5
        val room = roomRef ?: return c.attackSpeed
        if (passive || isDead) return 1
        setAnimation(Animation(DreadnothIds.SEQ_JELLY_ATTACK))
        attackSound()
        land(room, target, AttackType.MELEE, HitType.MELEE, c, delay = 0)
        stuckTicks = 0
        return c.attackSpeed
    }

    /** The spit when no melee path (U74): seq 15161, a ranged hit landing next tick, no projectile known. */
    private fun spit(room: DreadnothRoom, target: Entity, c: DreadnothConfig.Jelly) {
        faceEntity(target)
        setAnimation(Animation(DreadnothIds.SEQ_JELLY_RANGED))
        attackSound()
        land(room, target, AttackType.RANGED, HitType.RANGED, c, delay = 0)
        combat.combatDelay = c.attackSpeed
        stuckTicks = 0
    }

    private fun land(room: DreadnothRoom, target: Entity, style: AttackType, type: HitType, c: DreadnothConfig.Jelly, delay: Int) {
        val player = target as? Player
        val damage = if (player == null) 0 else room.rollNpcHit(this, player, c.max, style)
        val hit = Hit(this, damage, type)
        if (player != null && c.prayerDrain > 0) {
            // The engine invokes a hit's on-land consumer twice (CombatUtilities.processHit): drain once.
            var drained = false
            hit.onLand {
                if (!drained) {
                    drained = true
                    Bridge.drainPrayerOnAttack(player, this, c.prayerDrain)
                }
            }
        }
        CombatUtilities.delayHit(this, delay, target, hit)
    }

    override fun processNPC() {
        tileBeforeMove = location.positionHash
        super.processNPC()
    }

    override fun afterMovement() {
        super.afterMovement()
        val c = cfg ?: return
        val room = roomRef ?: return
        if (!c.rangedWhenBlocked || passive || isDead || isFinished) return
        val target = combat.target ?: run { stuckTicks = 0; return }
        if (combat.combatDelay > 0 || CombatUtilities.isWithinMeleeDistance(this, target)) {
            stuckTicks = 0
            return
        }
        if (location.positionHash != tileBeforeMove || isFrozen || isStunned) {
            // Moving is not "blocked"; neither is being frozen (barraged jellies do not spit).
            stuckTicks = 0
            return
        }
        stuckTicks++
        if (stuckTicks < c.rangedStuckTicks) return
        if (location.getTileDistance(target.location) > c.rangedRange) return
        if (isProjectileClipped(target, false)) return
        spit(room, target, c)
    }

    // ------------------------------------------------------------------------------------------------ death

    override fun sendDeath() {
        val source = mostDamagePlayer
        onDeath(source)
        passive = true
        setAnimation(Animation(DreadnothIds.SEQ_JELLY_DEATH))
        combatDefinitions.spawnDefinitions.deathSound?.let { World.sendSoundEffect(middleLocation, it) }
        roomRef?.swarm?.onJellyDeath(this)
    }
}

/**
 * The room's jellies (spec §4.5): threshold batches that form and rise, the death wave that hops out of the boss,
 * their targets, deaths (blood after `bloodDelay`, corpse removed after `despawnTicks`) and the count the completion
 * check needs. Every delay runs on the room's [Timeline] (tag [Tag.JELLY]).
 */
internal class JellySwarm(private val room: DreadnothRoom) {

    enum class Phase { FORMING, RISING, HOPPING, ACTIVE, DEAD }

    class Record(val id: Int, var tile: StaticTile, var phase: Phase, val deathWave: Boolean) {
        var npc: Jelly? = null
    }

    private val records = ArrayList<Record>()
    private var nextId = 0

    private val cfg: DreadnothConfig.Jelly get() = room.config.jelly

    /** Jellies not dead yet, including the ones still forming (they must be killed for the room to end). */
    val alive: Int get() = records.count { it.phase != Phase.DEAD }

    fun countIn(phase: Phase): Int = records.count { it.phase == phase }

    fun npcs(): List<Jelly> = records.mapNotNull { it.npc }.filter { !it.isFinished }

    /** Tiles held by living or forming jellies. */
    private fun occupied(): Set<StaticTile> = records.filter { it.phase != Phase.DEAD }.map { r ->
        r.npc?.let { room.toStatic(it.location) } ?: r.tile
    }.toSet()

    // ---------------------------------------------------------------------------------------- threshold batch

    /** A threshold batch: graphic 4210 for `spawnTicks` on each tile, then the jelly rises (15162). */
    fun spawnBatch(count: Int) {
        val tiles = JellyPlanner.spawnTiles(count, room.bossSwOrNull(), occupied(), cfg.minBossDistance, room.context.random)
        for (tile in tiles) {
            val r = Record(nextId++, tile, Phase.FORMING, deathWave = false)
            records += r
            room.telegraph(DreadnothIds.SPOT_JELLY_FORMING, tile)
            room.timeline.after(cfg.spawnTicks, Tag.JELLY) { rise(r) }
        }
    }

    private fun rise(r: Record) {
        if (r.phase != Phase.FORMING) return
        val npc = Jelly(room, room.context, room.translate(r.tile))
        npc.spawn()
        npc.setPassive(true)
        npc.setAnimation(Animation(DreadnothIds.SEQ_JELLY_SPAWN))
        r.npc = npc
        r.phase = Phase.RISING
        room.timeline.after(cfg.riseTicks, Tag.JELLY) { activate(r) }
    }

    // ---------------------------------------------------------------------------------------- death wave

    /** The death wave: jellies hop out of the boss footprint (14774 one tile, 14773 further); the rest rise. */
    fun spawnDeathWave(count: Int, bossSw: StaticTile) {
        val plan = JellyPlanner.deathWave(count, bossSw, occupied(), cfg.hopMaxTiles, room.context.random)
        for (hop in plan.hops) {
            val r = Record(nextId++, hop.dest, Phase.HOPPING, deathWave = true)
            records += r
            val npc = Jelly(room, room.context, room.translate(hop.dest))
            npc.spawn()
            npc.setPassive(true)
            val ticks = if (hop.longHop) HOP_3_TICKS else HOP_1_TICKS
            npc.setAnimation(Animation(if (hop.longHop) DreadnothIds.SEQ_JELLY_HOP_3 else DreadnothIds.SEQ_JELLY_HOP_1))
            Bridge.exactMove(npc, room.translate(hop.origin), room.translate(hop.dest), 0, ticks * 30,
                DreadnothArena.faceDirection(hop.origin, hop.dest))
            r.npc = npc
            room.timeline.after(ticks, Tag.JELLY) { activate(r) }
        }
        for (tile in plan.risers) {
            val r = Record(nextId++, tile, Phase.FORMING, deathWave = true)
            records += r
            rise(r)
        }
    }

    // ------------------------------------------------------------------------------------------ activation

    private fun activate(r: Record) {
        if (r.phase == Phase.DEAD) return
        val npc = r.npc ?: return
        if (npc.isDead || npc.isFinished) return
        r.phase = Phase.ACTIVE
        npc.setPassive(false)
        retarget(npc)
    }

    /** Once per tick: active jellies whose target died or left go for the nearest player. */
    fun tick() {
        for (r in records) {
            if (r.phase != Phase.ACTIVE) continue
            val npc = r.npc ?: continue
            if (npc.isDead || npc.isFinished) continue
            val target = npc.combat.target as? Player
            if (target == null || !room.isFighting(target)) retarget(npc)
        }
    }

    private fun retarget(npc: Jelly) {
        val here = room.toStatic(npc.location)
        val pool = room.fightersAlive()
        if (pool.isEmpty()) return
        val best = pool.minOf { DreadnothArena.chebyshev(here, it.tile) }
        val tied = pool.filter { DreadnothArena.chebyshev(here, it.tile) == best }
        val pick = if (tied.size == 1) tied[0] else tied[room.context.random.nextInt(tied.size)]
        npc.setTarget(pick.player)
    }

    // ------------------------------------------------------------------------------------------------ deaths

    fun onJellyDeath(npc: Jelly) {
        val r = records.firstOrNull { it.npc === npc } ?: return
        if (r.phase == Phase.DEAD) return
        r.phase = Phase.DEAD
        val tile = room.toStatic(npc.location)
        r.tile = tile
        room.timeline.after(cfg.bloodDelay, Tag.JELLY) { room.addBlood(tile) }
        room.timeline.after(cfg.despawnTicks, Tag.JELLY) { if (!npc.isFinished) npc.finish() }
    }

    /** `::tfa`: kills every jelly at once (no blood). */
    fun debugKillAll() {
        for (r in records) {
            if (r.phase == Phase.DEAD) continue
            r.phase = Phase.DEAD
            r.npc?.let { if (!it.isFinished) it.finish() }
        }
    }

    /** Teardown / completion: removes every jelly NPC (alive or corpse). */
    fun despawnAll() {
        for (r in records) {
            r.npc?.let { if (!it.isFinished) it.finish() }
            r.phase = Phase.DEAD
        }
        records.clear()
    }

    fun describe(): String =
        "jellies: ${Phase.entries.joinToString { "${it.name.lowercase()} ${countIn(it)}" }} (alive $alive)"

    companion object {
        /** 14774 `npc_jelly_jump_1` is 30 cycles = 1 t; 14773 `npc_jelly_jump_3` is 92 cycles ~ 3 t [CACHE]. */
        const val HOP_1_TICKS = 1
        const val HOP_3_TICKS = 3
    }
}

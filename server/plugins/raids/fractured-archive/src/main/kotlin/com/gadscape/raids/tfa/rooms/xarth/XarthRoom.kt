package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.config.ScalingConfig
import com.gadscape.raids.tfa.encounter.TfaEncounter
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.gadscape.raids.tfa.rooms.xarth.Timeline.Tag
import com.zenyte.game.util.Direction
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.npc.combat.SpellElement
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities
import com.zenyte.game.world.`object`.WorldObject
import org.slf4j.LoggerFactory
import java.util.EnumSet

/**
 * Room 3, Xarth (spec §4.7, WU-26): region 16726, section 3 east.
 *
 * - [build] spawns Xarth (SW 4190,5534, facing west) and the three pillar helpers 16685 in the copied region.
 * - [onArenaEntry] runs the seq prefetch 10030 (and the music once the fight is on).
 * - [start] (first transporter use) fixes his scaled HP, shows the HUD, plays "Forged Through Fire" and starts
 *   [XarthBrain].
 * - [tick]: timeline (impacts, sequence steps, piles, flames landing), the brain's decisions, the corpses' steps and
 *   arrivals, then the flames hurt whoever stands on them (spec §2.5 order).
 * - [isComplete]: Xarth dead and his death seq played out (5 t).
 * - [onComplete] (once), [onWipe], [teardown]: everything the room created is cancelled and removed; piles and
 *   pillars are put back (the static debug map needs it).
 *
 * All coordinates are static; [translate] / [toStatic] go through the raid's `TfaInstance`.
 */
class XarthRoom(override val context: TfaEncounterContext) : TfaEncounter, XarthHost {

    override val room: TfaRoomId get() = TfaRoomId.XARTH

    /** The raid's snapshot of `xarth.json`. */
    val config: XarthConfig = context.config[XarthConfig]

    override val contributionPool: Int get() = Bridge.contributionPool(context)

    override val phases: List<String> = buildList {
        add(PHASE_P1)
        config.thresholds.forEach { add("$PHASE_BLAST_PREFIX$it") }
        add(PHASE_P2)
        add(PHASE_HEAL_BLAST)
        add(PHASE_DEATH)
    }

    internal val timeline = Timeline()
    internal val corpses = CorpseSwarm(this)
    private val pillars = XarthPillars(this)
    private val flameLocs = FlameLocs()
    private val pileLocs = PileLocs()
    internal val brain = XarthBrain(config, timeline, context.random, this) { context.scale }

    private var boss: Xarth? = null
    private val hud = Bridge.HudFallback(XarthIds.XARTH)
    private var hudMax = 0
    private val fighterMap = LinkedHashMap<Player, PlayerFighter>()
    private val runWasOn = HashMap<Player, Boolean>()

    private var built = false
    private var started = false
    private var completed = false
    private var tornDown = false

    /** The boss NPC (null before [build] and after teardown). */
    internal val bossNpc: Xarth? get() = boss

    // ================================================================================== TfaEncounter lifecycle

    override fun build() {
        if (built || tornDown) return
        built = true
        boss = Xarth(this, context, translate(config.arena.spawn), directionOf(config.arena.spawnFacing)).also { it.spawn() }
        pillars.build()
    }

    override fun onArenaEntry(player: Player) {
        Bridge.prefetch(player)
        if (started && !brain.isDead) Bridge.playMusic(listOf(player))
    }

    override fun start() {
        if (started || tornDown) return
        if (!built) build()
        started = true
        val b = boss ?: return
        // The party scale is fixed now; re-scale an untouched boss in case he was built before the scale was known.
        if (b.hitpoints >= b.scaledMaxHp) b.applyScaling()
        Bridge.hudShow(b)
        Bridge.playMusic(context.playersInArena())
        brain.start()
    }

    override fun tick() {
        if (!started || tornDown) return
        timeline.advance()
        if (completed) return // the raid moves everyone out; nothing left to run or show
        refreshFighters()
        brain.tick()
        corpses.tick()
        brain.tickHazards()
        syncHud()
    }

    /** The HP bar of the players in the arena (fallback until the HUD service exists); 0 once he is dead. */
    private fun syncHud() {
        val b = boss
        if (b != null && b.scaledMaxHp > 0) hudMax = b.scaledMaxHp
        if (hudMax <= 0) return
        val current = if (b == null || brain.isDead || b.isFinished) 0 else b.hitpoints
        hud.sync(fighterMap.values.filter { it.alive }.map { it.player }, current, hudMax)
    }

    override fun onPlayerDeath(player: Player) {
        val f = fighterMap.remove(player) ?: return
        brain.onFighterLost(f)
        runWasOn.remove(player)
        Bridge.clearStatuses(player)
    }

    override fun isComplete(): Boolean = started && !tornDown && brain.deathComplete

    override fun onComplete() {
        if (completed) return
        completed = true
        timeline.cancel(Tag.BOSS)
        timeline.cancel(Tag.CORPSE)
        timeline.cancel(Tag.FLAME)
        restoreRuns()
        boss?.let {
            Bridge.hudHide(it)
            if (!it.isFinished) it.finish()
        }
        corpses.despawnAll()
        brain.flames.clear()
        flameLocs.clear()
        pileLocs.restoreAll()
        hud.closeAll()
        log.info("Xarth room complete at tick {} (scale {}, blasts {}, heal blasts {}, summons {})",
            timeline.now, context.scale, brain.blastsDone, brain.healBlastsDone, brain.summons)
    }

    override fun onWipe() {
        boss?.let { Bridge.hudHide(it) }
        teardown()
    }

    override fun teardown() {
        if (tornDown) return
        tornDown = true
        timeline.cancelAll()
        restoreRuns()
        corpses.despawnAll()
        flameLocs.clear()
        pileLocs.restoreAll()
        pillars.restoreAll()
        pillars.despawn()
        hud.closeAll()
        boss?.let { if (!it.isFinished) it.finish() }
        boss = null
        fighterMap.clear()
    }

    // ========================================================================================== debug hooks

    override fun debugSkipToPhase(phase: String): Boolean {
        val b = boss ?: return false
        if (brain.isDead) return false
        if (!started) start()
        val key = phase.lowercase()
        return when {
            key == PHASE_P1 -> {
                restartP1()
                b.setHpPercent(100)
                true
            }
            key.startsWith(PHASE_BLAST_PREFIX) -> {
                val pct = key.removePrefix(PHASE_BLAST_PREFIX).toIntOrNull() ?: return false
                if (pct !in config.thresholds) return false
                if (brain.inPhase2) restartP1()
                setBossHpAt(b, pct)
                brain.debugBlast(pct)
            }
            key == PHASE_P2 -> {
                if (!brain.inPhase2) setBossHpAt(b, config.thresholds.last())
                brain.debugToP2()
            }
            key == PHASE_HEAL_BLAST || key == "heal" -> {
                if (!brain.inPhase2) setBossHpAt(b, config.thresholds.last())
                brain.debugHealBlast()
            }
            key == PHASE_DEATH -> {
                b.setHitpoints(0) // sendDeath → onBossDeath
                true
            }
            else -> false
        }
    }

    private fun restartP1() {
        brain.debugRestart()
        corpses.despawnAll()
        flameLocs.clear()
        pileLocs.restoreAll()
        pillars.restoreAll()
        restoreRuns()
    }

    /** HP at exactly [pct]% of the scaled max (rounded down, at least 1). */
    private fun setBossHpAt(b: Xarth, pct: Int) {
        val hp = (b.maxHitpoints.toLong() * pct / 100).toInt().coerceAtLeast(1)
        b.setHitpoints(hp)
        TfaServices.bossHud.onHpChanged(b)
    }

    override fun debugSetHpPercent(percent: Int): Boolean {
        val b = boss ?: return false
        if (brain.isDead) return false
        if (!started) start()
        b.setHpPercent(percent)
        brain.checkThresholds()
        return true
    }

    override fun debugState(): List<String> = buildList {
        add("Xarth room: built $built, started $started, completed $completed, torn down $tornDown, tick ${timeline.now}, scale ${context.scale}")
        addAll(brain.describe())
        add("  ${corpses.describe()}, burning piles ${pileLocs.burning.joinToString { it.name }}, pending actions ${timeline.pending()} (boss ${timeline.pending(Tag.BOSS)})")
        add("  fighters: ${fighterMap.values.joinToString { "${it.player.username}@${it.tile}${if (it.alive) "" else " (out)"}" }}")
    }

    /** `::tfa xarth corpses`: a summon now (phase 2). */
    internal fun debugSummon(): Int = brain.debugSummon()

    // ==================================================================================== room-level hooks

    /** Every processed hit on Xarth: the first player hit starts his autos; thresholds are checked at once (§2.5). */
    internal fun onBossHit(hit: Hit) {
        if (!started || tornDown) return
        if (hit.hitType != HitType.HEALED && hit.damage > 0 && hit.source is Player) brain.onPlayerHit()
        brain.checkThresholds()
    }

    internal fun onBossDeath() {
        if (tornDown) return
        if (!started) start()
        brain.onDeath()
    }

    /** Defend seq 15018 only while he is not attacking (spec [C+]). */
    internal fun bossMayDefend(): Boolean = started && !tornDown && brain.canDefend()

    internal fun onCorpseAbsorbed(corpse: XarthCorpse, hp: Int) {
        if (tornDown || completed) return
        brain.onCorpseAbsorbed(hp)
        boss?.let { if (!it.isFinished && !brain.isDead) it.graphics = Graphics(XarthIds.SPOT_ABSORB) }
    }

    internal fun onCorpseGone(corpse: XarthCorpse) {
        if (!tornDown) brain.onCorpseGone(corpse.pile)
    }

    /** E17 hook of a corpse: water douses, fire summons one more (spec §4.7 [DOC]). */
    internal fun onCorpseSpell(corpse: XarthCorpse, element: SpellElement?, splash: Boolean) {
        if (tornDown || completed || !corpse.active) return
        val c = config.corpses
        when {
            Bridge.isWater(element) -> if (!splash || c.douseOnSplash) corpse.douse()
            Bridge.isFire(element) -> if (!splash || c.fireOnSplash) brain.requestExtraCorpse()
        }
    }

    /** Corpse HP at the room's scale (spec §2.6: `scaling.json overrides.xarth_corpse_hp`, else `corpses.hp`). */
    internal fun corpseHp(): Int = corpseHpFor(
        context.scale, context.config.scaling.overrides[ScalingConfig.XARTH_CORPSE_HP], config.corpses.hp, context.config.scaling.minScale,
    )

    /** Static SW tile of his 3x3. */
    internal val bossSw: StaticTile get() = boss?.let { toStatic(it.location) } ?: config.arena.spawn

    internal fun translate(tile: StaticTile): Location = context.instance.translate(tile)

    internal fun toStatic(location: Location): StaticTile =
        context.instance.toStatic(location) ?: StaticTile(-1, -1, location.plane)

    /** True while [player] is a living member inside this arena. */
    internal fun isFighting(player: Player): Boolean =
        !player.isDead && !player.isFinished && XarthArena.inBox(toStatic(player.location)) && player in context.members

    private fun refreshFighters() {
        val inArena = context.playersInArena()
        for (p in inArena) fighterMap.getOrPut(p) { PlayerFighter(p, this) }
        val gone = fighterMap.keys.filter { it !in inArena || it.isFinished }
        for (p in gone) {
            val f = fighterMap.remove(p) ?: continue
            brain.onFighterLost(f)
        }
    }

    private fun restoreRuns() {
        for ((p, wasOn) in runWasOn) {
            if (wasOn && config.blast.restoreRun && !p.isFinished && !p.isDead && !p.isRun) p.setRun(true)
        }
        runWasOn.clear()
    }

    private fun player(target: Fighter): Player? = (target as? PlayerFighter)?.player

    // ========================================================================================== XarthHost

    override val bossHp: Int get() = boss?.hitpoints ?: 0
    override val bossMaxHp: Int get() = boss?.maxHitpoints ?: 0

    override fun bossAnimate(seq: Int) {
        boss?.animation = Animation(seq)
    }

    override fun bossGraphic(spot: Int) {
        boss?.graphics = Graphics(spot)
    }

    override fun bossFace(target: Fighter) {
        val b = boss ?: return
        val p = player(target) ?: return
        if (!b.isFacing(p)) b.setFaceEntity(p)
    }

    override fun bossFaceTile(tile: StaticTile) {
        val b = boss ?: return
        b.setFaceEntity(null)
        b.setFaceLocation(translate(tile))
    }

    override fun bossHeal(amount: Int): Int {
        val b = boss ?: return 0
        if (b.isDead || b.isFinished || amount <= 0) return 0
        val healed = minOf(amount, b.maxHitpoints - b.hitpoints)
        if (healed <= 0) return 0
        b.applyHit(Hit(healed, HitType.HEALED))
        return healed
    }

    override fun bossTargetable(targetable: Boolean) {
        boss?.setTargetableState(targetable)
    }

    override fun bossDespawn() {
        boss?.let {
            Bridge.hudHide(it)
            if (!it.isFinished) it.finish()
        }
    }

    override fun fighters(): List<Fighter> = fighterMap.values.toList()

    override fun attack(target: Fighter, style: AttackStyle, max: Int): Boolean {
        val b = boss ?: return false
        val p = player(target) ?: return false
        if (!isFighting(p)) return false
        val engineStyle = when (style) {
            AttackStyle.MAGIC -> AttackType.MAGIC
            AttackStyle.RANGED -> AttackType.RANGED
            AttackStyle.MELEE -> XarthCombatDefs.meleeStyle(config.melee.style)
        }
        val accurate = CombatUtilities.getHitAccuracy(b, p, engineStyle) >= context.random.nextDouble()
        if (Bridge.isGodmode(p)) return false
        val damage = if (accurate && max > 0) {
            CombatUtilities.clampMaxHit(b, p, Bridge.wardReduced(p, b, context.random.nextInt(max + 1)))
        } else 0
        val type = when (style) {
            AttackStyle.MAGIC -> HitType.MAGIC
            AttackStyle.RANGED -> HitType.RANGED
            AttackStyle.MELEE -> HitType.MELEE
        }
        // Lands this tick; the protection prayer is checked as the hit lands (Player.appendHitEntry).
        CombatUtilities.delayHit(b, -1, p, Hit(b, damage, type))
        return accurate
    }

    override fun damage(target: Fighter, amount: Int): Int {
        val b = boss ?: return 0
        val p = player(target) ?: return 0
        if (!isFighting(p) || Bridge.isGodmode(p) || amount <= 0) return 0
        val dealt = Bridge.wardReduced(p, b, amount)
        CombatUtilities.delayHit(b, -1, p, Hit(b, dealt, HitType.TYPELESS))
        return dealt
    }

    override fun burn(target: Fighter, stacks: Int) {
        val p = player(target) ?: return
        if (!isFighting(p) || Bridge.isGodmode(p)) return
        Bridge.burn(p, stacks)
    }

    override fun bind(target: Fighter, ticks: Int) {
        val p = player(target) ?: return
        if (!isFighting(p)) return
        p.freeze(ticks)
        runWasOn.putIfAbsent(p, p.isRun)
        if (p.isRun) p.setRun(false)
    }

    override fun holdBind(target: Fighter) {
        val p = player(target) ?: return
        if (p.isRun && !p.isFinished) p.setRun(false)
    }

    override fun release(target: Fighter) {
        val p = player(target) ?: return
        val wasOn = runWasOn.remove(p) ?: return
        if (wasOn && config.blast.restoreRun && !p.isFinished && !p.isDead && !p.isRun) p.setRun(true)
    }

    override fun playerGraphic(target: Fighter, spot: Int) {
        val p = player(target) ?: return
        p.graphics = Graphics(spot)
    }

    override fun tileGraphic(spot: Int, tile: StaticTile) {
        World.sendGraphics(Graphics(spot), translate(tile))
    }

    override fun projectile(kind: Proj, target: Fighter) {
        val b = boss ?: return
        val p = player(target) ?: return
        World.sendProjectile(b, p, projectileOf(kind))
    }

    override fun projectile(kind: Proj, tile: StaticTile) {
        val b = boss ?: return
        World.sendProjectile(b, translate(tile), projectileOf(kind))
    }

    override fun losBlocked(target: Fighter): Boolean {
        val p = player(target) ?: return false
        return Bridge.losClipped(translate(XarthArena.CENTRE), p)
    }

    override fun collapsePillar(pillar: Pillar) = pillars.collapse(pillar)

    override fun pileFire(pile: Pile, burning: Boolean) = pileLocs.set(pile, burning)

    override fun spawnCorpse(pile: Pile) {
        if (!tornDown && !completed) corpses.spawn(pile)
    }

    override fun corpsesAlive(): Int = corpses.alive

    override fun killCorpses() = corpses.killAll()

    override fun flameSpawn(tile: StaticTile) = flameLocs.spawn(tile)

    override fun flameRemove(tile: StaticTile) = flameLocs.remove(tile)

    // ================================================================================================ misc

    /**
     * A Zenyte projectile from a [XarthConfig.ProjectileSpec]: arrival = delay + duration + perTile × distance cycles,
     * heights in the server's units (×4 on rev 241), slope converted from client units to the constructor's degrees.
     */
    private fun projectileOf(kind: Proj): Projectile {
        val spec = when (kind) {
            Proj.MAGIC -> config.projectiles.magic
            Proj.RANGED -> config.projectiles.ranged
            Proj.BOMB -> config.projectiles.bomb
        }
        val spot = when (kind) {
            Proj.MAGIC -> XarthIds.SPOT_MAGIC_PROJ
            Proj.RANGED -> XarthIds.SPOT_RANGED_PROJ
            Proj.BOMB -> XarthIds.SPOT_BOMB_PROJ
        }
        return Projectile(spot, spec.startHeight, spec.endHeight, spec.delay, (spec.slope * 90 + 32) / 64, spec.duration, 0, spec.perTile)
    }

    private fun directionOf(name: String): Direction = when (name) {
        "north" -> Direction.NORTH
        "east" -> Direction.EAST
        "south" -> Direction.SOUTH
        else -> Direction.WEST
    }

    /** Flame locs 62713 (blockwalk=no): spawned with loc anim 12003 (5 t), then the idle 12005 again. */
    private inner class FlameLocs {
        private val placed = HashMap<StaticTile, WorldObject>()

        fun spawn(tile: StaticTile) {
            if (tornDown || placed.containsKey(tile)) return
            val obj = WorldObject(XarthIds.LOC_FLAME, WorldObject.DEFAULT_TYPE, context.random.nextInt(4), translate(tile))
            World.spawnObject(obj)
            World.sendObjectAnimation(obj, Animation(XarthIds.SEQ_FLAME_SPAWN))
            placed[tile] = obj
            timeline.after(config.flames.spawnAnimTicks, Tag.LOC) {
                if (placed[tile] === obj) World.sendObjectAnimation(obj, Animation(XarthIds.SEQ_FLAME_IDLE))
            }
        }

        fun remove(tile: StaticTile) {
            placed.remove(tile)?.let { World.removeObject(it) }
        }

        fun clear() {
            placed.values.forEach { World.removeObject(it) }
            placed.clear()
        }
    }

    /** Corpse piles swapped to their fire variant (loc id + 1, config anims 15008-15015) while reanimating. */
    private inner class PileLocs {
        val burning: MutableSet<Pile> = EnumSet.noneOf(Pile::class.java)
        private val rotation = HashMap<Pile, Int>()

        fun set(pile: Pile, on: Boolean) {
            if (on == (pile in burning)) return
            if (on && tornDown) return
            val tile = translate(pile.tile)
            val rot = rotation.getOrPut(pile) { World.getObjectWithType(tile, WorldObject.DEFAULT_TYPE)?.rotation ?: 0 }
            World.spawnObject(WorldObject(if (on) pile.fireLocId else pile.locId, WorldObject.DEFAULT_TYPE, rot, tile))
            if (on) burning += pile else burning -= pile
        }

        fun restoreAll() {
            for (p in burning.toList()) set(p, false)
        }
    }

    /** A player in the arena, wrapped once so the brain can keep a target. */
    internal class PlayerFighter(val player: Player, private val room: XarthRoom) : Fighter {
        override val tile: StaticTile get() = room.toStatic(player.location)
        override val alive: Boolean get() = room.isFighting(player)
        override fun toString(): String = "PlayerFighter(${player.username})"
    }

    companion object {
        const val PHASE_P1 = "p1"
        const val PHASE_BLAST_PREFIX = "blast"
        const val PHASE_P2 = "p2"
        const val PHASE_HEAL_BLAST = "healblast"
        const val PHASE_DEATH = "death"

        private val log = LoggerFactory.getLogger(XarthRoom::class.java)

        /**
         * Corpse HP for party scale [n]: the `scaling.json` override table first (framework, §2.6), then the room's
         * own `corpses.hp` table, both indexed from [minScale]; past the end of a table its last value.
         */
        @JvmStatic
        fun corpseHpFor(n: Int, scalingTable: List<Int>?, roomTable: List<Int>, minScale: Int): Int {
            val i = (n - minScale).coerceAtLeast(0)
            if (!scalingTable.isNullOrEmpty()) return scalingTable[minOf(i, scalingTable.size - 1)]
            if (roomTable.isNotEmpty()) return roomTable[minOf(i, roomTable.size - 1)]
            return DEFAULT_CORPSE_HP
        }

        private const val DEFAULT_CORPSE_HP = 20
    }
}

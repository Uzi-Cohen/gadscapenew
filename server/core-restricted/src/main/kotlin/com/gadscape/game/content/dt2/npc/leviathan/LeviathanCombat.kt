package com.gadscape.game.content.dt2.npc.leviathan

import com.gadscape.game.content.dt2.area.LeviathanInstance
import com.gadscape.game.content.dt2.npc.DT2BossDifficulty
import com.zenyte.game.content.boss.BossRespawnTimer
import com.zenyte.game.task.TickTask
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.util.Colour
import com.zenyte.game.util.Utils
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.NpcId.THE_LEVIATHAN
import com.zenyte.game.world.entity.npc.NpcId.THE_LEVIATHAN_12215
import com.zenyte.game.world.entity.npc.combat.CombatScript
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.PlayerCombat
import com.zenyte.game.world.entity.player.action.combat.magic.CombatSpell
import com.zenyte.game.world.`object`.WorldObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The Leviathan (Desert Treasure II), after the OSRS wiki (The_Leviathan, The_Leviathan/Strategies, 2026):
 *
 * - Volleys of orbs: magic (blue, Protect from Magic), ranged (green, Protect from Missiles), melee (orange, Protect
 *   from Melee) and falling rocks (move off the shadow). Each volley is faster than the last (3-tick magic/ranged,
 *   2-tick, 2-tick all styles, 1-tick, 1-tick all styles, faster projectiles, fastest projectiles).
 * - At the end of each volley it roars: debris falls on the player's tile (up to 7 damage when not moving) and stays
 *   there as a permanent blocker (a "+" of five in awakened mode). The other pieces telegraph the next special:
 *   east/west = Lightning barrage, north/south = Smoke blast; the two alternate.
 * - Lightning barrage: lightning chases the player while orbs land across the arena (never next to the pool).
 *   Smoke blast: debris drops on the player's tile every tick (2 ticks to land) ten times, then a smoke blast hits
 *   up to 59 (awakened 70+) unless the player hides behind debris. Damage to the boss is 33% lower during specials.
 * - Bite: only when the player stands next to the pool (diagonals included); Protect from Melee blocks it.
 * - Shadow spells stun it for 15 ticks (awakened 7); damage is capped at 10 while stunned except on the wound on its
 *   back: ranged then hits at least ~65% of the max hit, magic deals double damage. Hitting the back breaks the stun,
 *   the boss retaliates with its next special and the volley speed resets (to the second step, later resets one step
 *   further). Dealing 25% of its hitpoints during a stun resets it as well.
 * - Enrage at 20%: roar, one more debris set, an abyssal pathfinder spawns in the north-west corner (next corner
 *   clockwise if blocked) and walks clockwise around the arena. Magic/ranged orbs only (bite still possible) every 2
 *   ticks (awakened 1). Inside the pathfinder's 5x5 (awakened 7x7) the player's hits always land with at least 20%
 *   of their max hit (magic: double damage instead); outside, the boss's attacks ignore protection prayers and the
 *   player deals half damage. Debris keeps falling during the enrage; everything is cleared when the boss dies.
 * - Awakened: a tornado spawns at 50% and chases the player (40+ damage on contact).
 *
 * Numbers the wiki does not give (orbs per volley, lightning and smoke-blast hit ranges, pathfinder speed, tail rings)
 * are constants below; see content-work/bosses/NOTES.md.
 */
internal class LeviathanCombat(
    tile: Location,
    private val instance: LeviathanInstance,
    val difficulty: DT2BossDifficulty = instance.difficulty
) : NPC(
    if (difficulty == DT2BossDifficulty.AWAKENED) THE_LEVIATHAN_12215 else THE_LEVIATHAN,
    tile,
    true
), CombatScript {

    private enum class Style(val projectile: Int, val launch: Int, val impact: Int, val hitType: HitType, val attackType: AttackType) {
        RANGED(2487, 2481, 2490, HitType.RANGED, AttackType.RANGED),
        MELEE(2488, 2482, 2491, HitType.MELEE, AttackType.MELEE),
        MAGIC(2489, 2483, 2492, HitType.MAGIC, AttackType.MAGIC),
        ROCK(-1, -1, -1, HitType.TYPELESS, AttackType.RANGED)
    }

    /** One step of the volley sequence: ticks between orbs, whether melee orbs are used, orb flight time in ticks. */
    private class VolleyStep(val interval: Int, val melee: Boolean, val flight: Int)

    private enum class Phase { VOLLEY, ROARED }

    private val awakened = difficulty == DT2BossDifficulty.AWAKENED

    private var phase = Phase.VOLLEY
    private var volleyStep = 0
    private var resetStep = 1
    private var orbsFired = 0
    private var nextSpecialIsLightning = true
    private var specialTicks = 0

    private var stunTicks = 0
    private var stunFacingX = 0
    private var stunFacingY = 0
    private var damageDuringStun = 0

    private var enraged = false
    private var enragedAttacks = 0
    private var pathfinder: LeviathanPathfinder? = null
    private var tornado: LeviathanTornado? = null
    private var tornadoSpawned = false

    private val tails = ArrayList<NPC>(3)
    private val blockers = ArrayList<WorldObject>()
    private val smokeDebris = ArrayList<WorldObject>()

    override fun isForceAggressive(): Boolean = true
    override fun getRespawnDelay(): Int = BossRespawnTimer.LEVIATHAN.timer.toInteger()

    /** The boss sits in its pool. */
    override fun isMovementRestricted(): Boolean = true

    init {
        setDamageCap(200)
        setMaxDistance(64)
        // NPC.attackDistance defaults to 7 tiles from the boss's edge; the orbs reach the whole arena.
        setAttackDistance(32)
        possibleTargets.add(instance.player)
    }

    override fun spawn(): NPC {
        val npc = super.spawn()
        resetFight()
        if (!instance.player.isFinished) instance.player.hpHud.open(id, maxHitpoints)
        animation = Animation(SEQ_SPAWN)
        spawnTails()
        return npc
    }

    private fun resetFight() {
        phase = Phase.VOLLEY
        volleyStep = 0
        resetStep = 1
        orbsFired = 0
        nextSpecialIsLightning = true
        specialTicks = 0
        stunTicks = 0
        damageDuringStun = 0
        enraged = false
        enragedAttacks = 0
        tornadoSpawned = false
    }

    private val meleeMax get() = if (awakened) 86 else 50
    private val rangedMax get() = if (awakened) 41 else 24
    private val magicMax get() = if (awakened) 55 else 32
    private val stunDuration get() = if (awakened) 7 else 15

    // ---------------------------------------------------------------- attacks

    override fun attack(target: Entity): Int {
        if (target !is Player || isDead || isFinished) return 4
        if (stunTicks > 0) return stunTicks
        if (specialTicks > 0) return specialTicks
        if (enraged) return enragedAttack(target)
        when (phase) {
            Phase.VOLLEY -> {
                if (orbsFired >= ORBS_PER_VOLLEY) {
                    roar(target, nextSpecialIsLightning)
                    phase = Phase.ROARED
                    return ROAR_TICKS
                }
                if (instance.isNextToPool(target.location) && Utils.random(BITE_ONE_IN - 1) == 0) {
                    bite(target)
                    return 3
                }
                val step = VOLLEYS[volleyStep]
                fireOrb(target, step)
                orbsFired++
                return step.interval
            }
            Phase.ROARED -> {
                phase = Phase.VOLLEY
                orbsFired = 0
                volleyStep = min(volleyStep + 1, VOLLEYS.size - 1)
                return startSpecial(target)
            }
        }
    }

    private fun fireOrb(target: Player, step: VolleyStep) {
        val style = when {
            Utils.random(ROCK_ONE_IN - 1) == 0 -> Style.ROCK
            step.melee && Utils.random(2) == 0 -> Style.MELEE
            Utils.randomBoolean() -> Style.MAGIC
            else -> Style.RANGED
        }
        if (style == Style.ROCK) {
            animation = Animation(SEQ_PROJECTILE_SINGLE)
            val tiles = if (awakened) plus(target.location) else listOf(Location(target.location))
            tiles.forEach { dropRock(it, ROCK_MAX, permanent = false, landTicks = 3) }
            return
        }
        launchOrb(target, style, step.flight)
    }

    private fun launchOrb(target: Player, style: Style, flight: Int) {
        animation = Animation(SEQ_PROJECTILE_SINGLE)
        graphics = Graphics(style.launch)
        val projectile = Projectile(style.projectile, 110, 31, 0, 10, (flight + 1) * 30, 0, 0)
        val delay = World.sendProjectile(this, target, projectile)
        val max = when (style) {
            Style.MELEE -> meleeMax
            Style.RANGED -> rangedMax
            else -> magicMax
        }
        val hit = Hit(this, getRandomMaxHit(this, max, style.attackType, target), style.hitType)
        hit.onLand { target.graphics = Graphics(style.impact) }
        delayHit(this, delay, target, hit)
    }

    private fun bite(target: Player) {
        animation = Animation(if (Utils.randomBoolean()) SEQ_BITE_1 else SEQ_BITE_2)
        tails.forEachIndexed { i, tail -> tail.animation = Animation(SEQ_TAIL_MELEE + i) }
        delayHit(this, 1, target, Hit(this, getRandomMaxHit(this, meleeMax, AttackType.MELEE, target), HitType.MELEE))
    }

    private fun enragedAttack(target: Player): Int {
        val interval = if (awakened) 1 else 2
        if (instance.isNextToPool(target.location) && Utils.random(BITE_ONE_IN - 1) == 0) {
            bite(target)
            return max(interval, 3)
        }
        launchOrb(target, if (Utils.randomBoolean()) Style.MAGIC else Style.RANGED, 2)
        if (++enragedAttacks % ENRAGE_DEBRIS_EVERY == 0) {
            dropRock(Location(target.location), DEBRIS_MAX, permanent = true, landTicks = 2)
        }
        return interval
    }

    // ---------------------------------------------------------------- roar and debris

    /** End of a volley: roar, debris on the player's tile (stays) and the telegraph of the next special. */
    private fun roar(target: Player, lightningNext: Boolean) {
        animation = Animation(SEQ_ROAR)
        val centre = Location(target.location)
        val permanent = if (awakened) plus(centre) else listOf(centre)
        permanent.forEach { dropRock(it, DEBRIS_MAX, permanent = true, landTicks = 2) }
        // East/west pieces announce the lightning barrage, north/south pieces the smoke blast.
        val offsets = if (lightningNext) listOf(3 to 0, -3 to 0, 5 to 1, -5 to -1) else listOf(0 to 3, 0 to -3, 1 to 5, -1 to -5)
        for ((dx, dy) in offsets) {
            val tile = centre.transform(dx, dy, 0)
            if (instance.isInArena(tile) && !instance.isNextToPool(tile)) {
                dropRock(tile, DEBRIS_MAX, permanent = false, landTicks = 2)
            }
        }
    }

    /**
     * A rock or piece of debris falling on [tile]: shadow now, impact after [landTicks]. A player still on the tile
     * takes up to [maxHit]; a [permanent] piece then stays as a blocker unless a player stands on it.
     */
    private fun dropRock(tile: Location, maxHit: Int, permanent: Boolean, landTicks: Int, smoke: Boolean = false) {
        World.sendGraphics(Graphics(GFX_SHADOW), tile)
        World.sendGraphics(Graphics(GFX_ROCK_FALL, max(0, landTicks - 1) * 30, 0), tile)
        WorldTasksManager.schedule(landTicks) {
            if (isFinished || instance.player.isFinished) return@schedule
            val player = instance.player
            val occupied = player.location.matches(tile)
            if (occupied) {
                player.applyHit(Hit(this, Utils.random(maxHit), HitType.TYPELESS))
            }
            if ((permanent || smoke) && !occupied && World.isFloorFree(tile)) {
                val debris = WorldObject(LOC_DEBRIS, 10, Utils.random(3), tile)
                World.spawnObject(debris)
                if (smoke) smokeDebris.add(debris) else blockers.add(debris)
            }
        }
    }

    private fun plus(centre: Location): List<Location> =
        listOf(Location(centre), centre.transform(1, 0, 0), centre.transform(-1, 0, 0), centre.transform(0, 1, 0), centre.transform(0, -1, 0))
            .filter { instance.isInArena(it) && !instance.isNextToPool(it) }

    // ---------------------------------------------------------------- specials

    /** Starts the next special attack (they alternate) and returns how many ticks it lasts. */
    private fun startSpecial(target: Player): Int {
        val lightning = nextSpecialIsLightning
        nextSpecialIsLightning = !nextSpecialIsLightning
        specialTicks = if (lightning) lightningBarrage(target) else smokeBlast(target)
        return specialTicks
    }

    private fun lightningBarrage(target: Player): Int {
        val duration = LIGHTNING_TICKS
        animation = Animation(SEQ_360_START)
        var lastTile = Location(target.location)
        WorldTasksManager.schedule(object : TickTask() {
            override fun run() {
                if (isDead || isFinished || stunTicks > 0 || target.isFinished) {
                    stop()
                    return
                }
                ticks++
                if (ticks in 3 until duration - 2) animation = Animation(SEQ_360_LOOP)
                if (ticks == duration - 2) animation = Animation(SEQ_360_END)
                if (ticks < duration - 1) {
                    // The lightning lands where the player stood a tick ago: keep moving.
                    val strike = lastTile
                    World.sendGraphics(Graphics(GFX_LIGHTNING), strike)
                    if (target.location.matches(strike)) {
                        target.applyHit(Hit(this@LeviathanCombat, Utils.random(if (awakened) LIGHTNING_MAX_AWAKENED else LIGHTNING_MAX), HitType.TYPELESS))
                    }
                    lastTile = Location(target.location)
                    if (ticks % 2 == 0) {
                        repeat(3) { arenaOrb(target) }
                    }
                }
                if (ticks >= duration) stop()
            }
        }, 0, 0)
        return duration
    }

    /** A magic orb landing on a random arena tile that does not touch the pool. */
    private fun arenaOrb(target: Player) {
        val tiles = instance.arenaTiles()
        if (tiles.isEmpty()) return
        val tile = tiles[Utils.random(tiles.size - 1)]
        if (instance.isNextToPool(tile)) return
        val projectile = Projectile(Style.MAGIC.projectile, 110, 0, 0, 10, 90, 0, 0)
        val delay = World.sendProjectile(this, tile, projectile)
        WorldTasksManager.schedule(delay) {
            if (isFinished) return@schedule
            World.sendGraphics(Graphics(Style.MAGIC.impact), tile)
            if (target.location.matches(tile)) {
                target.applyHit(Hit(this, Utils.random(if (awakened) ARENA_ORB_MAX_AWAKENED else ARENA_ORB_MAX), HitType.TYPELESS))
            }
        }
    }

    private fun smokeBlast(target: Player): Int {
        val pieces = SMOKE_DEBRIS_PIECES
        val duration = pieces + 6
        animation = Animation(SEQ_SPIT_START)
        WorldTasksManager.schedule(object : TickTask() {
            override fun run() {
                if (isDead || isFinished || target.isFinished) {
                    clearSmokeDebris()
                    stop()
                    return
                }
                ticks++
                when {
                    ticks <= pieces -> {
                        animation = Animation(SEQ_SPIT_LOOP)
                        dropRock(Location(target.location), DEBRIS_MAX, permanent = false, landTicks = 2, smoke = true)
                    }
                    ticks == pieces + 2 -> {
                        animation = Animation(SEQ_EXPLOSION)
                        graphics = Graphics(GFX_EXPLODE)
                    }
                    ticks == pieces + 3 -> {
                        repeat(4) {
                            val tiles = instance.arenaTiles()
                            if (tiles.isNotEmpty()) World.sendGraphics(Graphics(GFX_SMOKE + Utils.random(4)), tiles[Utils.random(tiles.size - 1)])
                        }
                        // Debris between the player and the boss blocks the blast.
                        if (!isProjectileClipped(target, false)) {
                            val (min, max) = if (awakened) SMOKE_MIN_AWAKENED to SMOKE_MAX_AWAKENED else SMOKE_MIN to SMOKE_MAX
                            target.applyHit(Hit(this@LeviathanCombat, Utils.random(min, max), HitType.TYPELESS))
                        } else {
                            target.sendMessage("The debris shields you from the smoke blast.")
                        }
                    }
                    ticks >= pieces + 5 -> {
                        clearSmokeDebris()
                        stop()
                    }
                }
            }
        }, 0, 0)
        return duration
    }

    private fun clearSmokeDebris() {
        smokeDebris.forEach { World.removeObject(it) }
        smokeDebris.clear()
    }

    // ---------------------------------------------------------------- stun and incoming damage

    override fun handleIngoingHit(hit: Hit) {
        val source = hit.source
        if (source is Player && !isDead) {
            if (specialTicks > 0) {
                hit.damage = (hit.damage * 0.67).toInt()
            }
            if (stunTicks > 0) {
                if (isBehind(source)) {
                    hitTheWound(source, hit)
                } else if (hit.damage > STUN_DAMAGE_CAP) {
                    hit.damage = STUN_DAMAGE_CAP
                }
                damageDuringStun += hit.damage
            } else if ((hit.getAttribute("spell") as? CombatSpell)?.let { it in SHADOW_SPELLS } == true && specialTicks == 0) {
                stun(source)
                if (hit.damage > STUN_DAMAGE_CAP) hit.damage = STUN_DAMAGE_CAP
            }
            if (enraged) {
                applyPathfinderModifiers(source, hit)
            }
        }
        super.handleIngoingHit(hit)
    }

    private fun stun(player: Player) {
        stunTicks = stunDuration
        damageDuringStun = 0
        val middle = middleLocation
        stunFacingX = player.x - middle.x
        stunFacingY = player.y - middle.y
        setFaceEntity(null)
        combat.combatDelay = stunTicks
        animation = Animation(SEQ_STUN)
        tails.forEachIndexed { i, tail -> tail.animation = Animation(SEQ_TAIL_STUN + i) }
        player.sendMessage(Colour.RS_GREEN.wrap("The shadow spell stuns the Leviathan!"))
    }

    /** Whether the player stands behind the boss relative to where it faced when stunned (the wound is on its back). */
    private fun isBehind(player: Player): Boolean {
        val middle = middleLocation
        val dot = (player.x - middle.x) * stunFacingX + (player.y - middle.y) * stunFacingY
        return dot < 0
    }

    private fun hitTheWound(player: Player, hit: Hit) {
        val maxHit = playerMaxHit(player)
        when (hit.hitType) {
            HitType.RANGED -> if (maxHit > 0) hit.damage = max(hit.damage, Utils.random(ceil(maxHit * 0.65).toInt(), maxHit))
            HitType.MAGIC -> hit.damage = hit.damage * 2
            else -> {}
        }
        endStun(byWound = true)
        player.sendMessage(Colour.RS_RED.wrap("You strike the Leviathan's wound and it breaks free!"))
        WorldTasksManager.schedule(1) {
            if (!isDead && !isFinished && !player.isFinished) {
                combat.combatDelay = startSpecial(player)
            }
        }
    }

    private fun endStun(byWound: Boolean) {
        val reset = byWound || damageDuringStun >= maxHitpoints / 4
        stunTicks = 0
        damageDuringStun = 0
        animation = Animation.STOP
        if (reset) {
            volleyStep = resetStep
            resetStep = min(resetStep + 1, VOLLEYS.size - 1)
            orbsFired = 0
            phase = Phase.VOLLEY
        }
        combat.combatDelay = 2
    }

    private fun playerMaxHit(player: Player): Int {
        val action = player.actionManager.action
        return if (action is PlayerCombat) {
            try {
                action.getMaxHit(player, 1.0, 1.0, false)
            } catch (_: Exception) {
                0
            }
        } else 0
    }

    // ---------------------------------------------------------------- enrage and pathfinder

    private fun applyPathfinderModifiers(player: Player, hit: Hit) {
        val finder = pathfinder ?: return
        if (finder.covers(player.location)) {
            if (hit.hitType == HitType.MAGIC) {
                hit.damage = hit.damage * 2
            } else {
                val maxHit = playerMaxHit(player)
                if (maxHit > 0) {
                    val minHit = ceil(maxHit * 0.2).toInt()
                    if (hit.damage < minHit) hit.damage = Utils.random(minHit, maxHit)
                }
            }
        } else {
            hit.damage = hit.damage / 2
        }
    }

    /** Outside the pathfinder's aura, the enraged Leviathan's orbs ignore protection prayers. */
    private fun piercing(): Boolean {
        val finder = pathfinder ?: return false
        return enraged && !finder.covers(instance.player.location)
    }

    override fun getMagicPrayerMultiplier(): Double = if (piercing()) 1.0 else 0.0
    override fun getRangedPrayerMultiplier(): Double = if (piercing()) 1.0 else 0.0
    override fun getMeleePrayerMultiplier(): Double = if (piercing()) 1.0 else 0.0

    override fun postHitProcess(hit: Hit) {
        if (hit.source is Player) {
            instance.player.hpHud.updateValue(getHitpoints())
        }
        if (isDead || isFinished) return
        if (!enraged && getHitpoints() <= maxHitpoints / 5) {
            enrage()
        }
        if (awakened && !tornadoSpawned && getHitpoints() <= maxHitpoints / 2) {
            tornadoSpawned = true
            spawnTornado()
        }
    }

    private fun enrage() {
        enraged = true
        if (stunTicks > 0) endStun(byWound = false)
        animation = Animation(SEQ_ROAR)
        instance.player.sendMessage(Colour.RS_RED.wrap("The Leviathan becomes enraged! An abyssal pathfinder emerges."))
        val target = instance.player
        val centre = Location(target.location)
        (if (awakened) plus(centre) else listOf(centre)).forEach { dropRock(it, DEBRIS_MAX, permanent = true, landTicks = 2) }
        val corners = LeviathanPathfinder.cornerTiles(instance)
        val start = corners.indices.firstOrNull { World.isFloorFree(corners[it], 3) } ?: 0
        pathfinder = LeviathanPathfinder(instance, start, if (awakened) 3 else 2).also { it.spawn() }
        combat.combatDelay = ROAR_TICKS
    }

    private fun spawnTornado() {
        tornado?.finish()
        val corners = LeviathanPathfinder.cornerTiles(instance)
        val tile = corners.maxByOrNull { it.getTileDistance(instance.player.location) } ?: return
        tornado = LeviathanTornado(tile, instance.player, this).also { it.spawn() }
    }

    // ---------------------------------------------------------------- tails, ticks, death

    private fun spawnTails() {
        if (!SPAWN_TAILS) return
        tails.forEach { if (!it.isFinished) it.finish() }
        tails.clear()
        for (tailId in TAIL_IDS) {
            val tail = NPC(tailId, Location(respawnTile), true)
            tail.spawn()
            tails.add(tail)
        }
    }

    override fun processNPC() {
        super.processNPC()
        if (isDead || isFinished) return
        if (specialTicks > 0) specialTicks--
        if (stunTicks > 0) {
            stunTicks--
            if (stunTicks == 0) {
                endStun(byWound = false)
            } else if ((stunDuration - stunTicks) % 4 == 3) {
                animation = Animation(SEQ_STUN_IDLE)
            }
        }
    }

    override fun onFinish(source: Entity?) {
        cleanup()
        super.onFinish(source)
        setRespawnTask()
    }

    override fun finish() {
        cleanup()
        super.finish()
    }

    private fun cleanup() {
        pathfinder?.let { if (!it.isFinished) it.finish() }
        pathfinder = null
        tornado?.let { if (!it.isFinished) it.finish() }
        tornado = null
        tails.forEach { if (!it.isFinished) it.finish() }
        tails.clear()
        blockers.forEach { World.removeObject(it) }
        blockers.clear()
        clearSmokeDebris()
        if (!instance.player.isFinished) instance.player.hpHud.close()
    }

    companion object {
        private val VOLLEYS = arrayOf(
            VolleyStep(3, false, 3),
            VolleyStep(2, false, 3),
            VolleyStep(2, true, 3),
            VolleyStep(1, false, 3),
            VolleyStep(1, true, 3),
            VolleyStep(1, true, 2),
            VolleyStep(1, true, 1)
        )

        private val SHADOW_SPELLS = setOf(CombatSpell.SHADOW_RUSH, CombatSpell.SHADOW_BURST, CombatSpell.SHADOW_BLITZ, CombatSpell.SHADOW_BARRAGE)

        /** Orbs per volley (not on the wiki). */
        const val ORBS_PER_VOLLEY = 12
        /** About one orb in this many is a falling rock instead. */
        const val ROCK_ONE_IN = 8
        /** Chance (one in) that it bites a player next to the pool instead of firing. */
        const val BITE_ONE_IN = 6
        const val ROAR_TICKS = 4
        const val STUN_DAMAGE_CAP = 10
        const val ROCK_MAX = 25
        const val DEBRIS_MAX = 7
        const val ENRAGE_DEBRIS_EVERY = 6
        const val LIGHTNING_TICKS = 12
        const val LIGHTNING_MAX = 20
        const val LIGHTNING_MAX_AWAKENED = 35
        const val ARENA_ORB_MAX = 15
        const val ARENA_ORB_MAX_AWAKENED = 25
        const val SMOKE_DEBRIS_PIECES = 10
        const val SMOKE_MIN = 40
        const val SMOKE_MAX = 59
        const val SMOKE_MIN_AWAKENED = 55
        const val SMOKE_MAX_AWAKENED = 75
        /** Spawn the three cosmetic tail-ring NPCs (12216-12218) on the boss. */
        const val SPAWN_TAILS = true

        // 241 seqs (RuneLite AnimationID)
        const val SEQ_ROAR = 10277 // NPC_LEVIATHAN_01_IDLE_02
        const val SEQ_PROJECTILE_SINGLE = 10281
        const val SEQ_STUN = 10282
        const val SEQ_BITE_1 = 10283
        const val SEQ_BITE_2 = 10284
        const val SEQ_360_START = 10285
        const val SEQ_360_LOOP = 10286
        const val SEQ_360_END = 10287
        const val SEQ_SPIT_START = 10288
        const val SEQ_SPIT_LOOP = 10289
        const val SEQ_EXPLOSION = 10290
        const val SEQ_SPAWN = 10291
        const val SEQ_STUN_IDLE = 10294
        const val SEQ_TAIL_MELEE = 10301 // 10301..10303 for tails 1..3
        const val SEQ_TAIL_STUN = 10304 // 10304..10306

        // Spotanims (241 SpotanimID). 2500-2515 are GadScape's own (Fractured Archive) spotanims in this cache, so the
        // stock 360-lightning (2503/2504), tornado (2507) and bomb (2506) VFX are not used; see CACHE_NEEDS.md.
        const val GFX_EXPLODE = 2494 // SPOTANIM_LEVIATHAN_EXPLODE
        const val GFX_SMOKE = 2495 // VFX_LEVIATHAN_EXPLOSION_01..05
        const val GFX_LIGHTNING = 2545 // DT2_WIGHT_LIGHTNING
        const val GFX_SHADOW = 1446 // GARGBOSS_DEBRIS_SHADOW_90
        const val GFX_ROCK_FALL = 2529 // DT2_GUARDIAN_ROCK_FALL

        /** The Scar's 1x1 rock (model 48891); the debris blocker. */
        const val LOC_DEBRIS = 49226

        val TAIL_IDS = intArrayOf(12216, 12217, 12218)
    }
}

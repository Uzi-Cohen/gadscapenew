package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.StaticTile
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.WorldObject

/**
 * One P2 portal barrage in [quadrant] (spec §4.8 "Phase 2", steps 1-5; geometry in [BarrageLayout]):
 *
 * | tick (from start)                   | what                                                                          |
 * |-------------------------------------|-------------------------------------------------------------------------------|
 * | 0                                   | party gathered into the quadrant [HOUSE]; 10 portal walls spawn (anim 14829), the 2 edge-end tiles close |
 * | `firstVolleyDelay + v × speed`      | volley v (0..4): every purple wall shoots spot 4269 across its lane (4 t); 25-35 to a player on a lane tile as the shot passes |
 * | volleys end + `shotTicks` (= P)     | the orange walls turn purple; void pools (spot 4270, 6 t) on 8 random tiles   |
 * | P + `poolTicks` - 1                 | pools hit 15-25 (their last tick)                                             |
 * | P + `portalDelay` (= F)             | orange floor portal 4271 → 4272 on a free tile: stepping on it moves the player to the same tile rotated into the next quadrant (4273) |
 * | F + `quadrantAttackDelay` (= A)     | full quadrant attack: 60 to everyone still in the quadrant (every wall shoots from A - 4) |
 * | A + 1 / A + 2                       | walls despawn (14831), floor portal closes (4273) / barrage done             |
 *
 * Loc anims 14829-14831 are the portal walls' (framemap 1928, the default anim of 62684/62685 is 14830)
 * [CACHE loc_ocellus.txt, seq_ocellus.txt]. Damage is typeless (prayer does not help) [HOUSE].
 */
internal class PortalBarrage(
    private val room: OcellusRoom,
    val quadrant: Quadrant,
    val entry: OcellusConfig.Barrage,
    val number: Int,
    private val tag: String,
) {
    private val cfg = room.cfg.p2
    private val fx = room.fx
    val layout = BarrageLayout(room.geo, quadrant, entry.col, entry.row)

    private val walls = LinkedHashMap<PortalSegment, WorldObject>()
    private var pools: Set<StaticTile> = emptySet()
    private var floorPortal: StaticTile? = null
    private var floorOpenedAt = -1
    private var done = false
    private var onDone: (() -> Unit)? = null

    /** Ticks from [start] to the barrage's end (for tests and the debug dump). */
    val length: Int
        get() {
            val lastVolley = cfg.firstVolleyDelay + (cfg.volleys - 1) * entry.speed
            return lastVolley + cfg.shotTicks + cfg.portalDelay + cfg.quadrantAttackDelay + 2
        }

    fun start(then: () -> Unit) {
        onDone = then
        gather()
        spawnWalls()
        val tl = room.timeline
        for (v in 0 until cfg.volleys) {
            tl.after(cfg.firstVolleyDelay + v * entry.speed, tag) { volley() }
        }
        val poolsAt = cfg.firstVolleyDelay + (cfg.volleys - 1) * entry.speed + cfg.shotTicks
        tl.after(poolsAt, tag) { allPurple(); spawnPools() }
        tl.after(poolsAt + cfg.poolTicks - 1, tag) { poolDamage() }
        val portalAt = poolsAt + cfg.portalDelay
        tl.after(portalAt, tag) { openFloorPortal() }
        val attackAt = portalAt + cfg.quadrantAttackDelay
        tl.after(attackAt - cfg.shotTicks, tag) { fireAll() }
        tl.after(attackAt, tag) { quadrantAttack() }
        tl.after(attackAt + 1, tag) { despawnWalls(); closeFloorPortal() }
        tl.after(attackAt + 2, tag) { finish() }
    }

    fun tick() {
        val portal = floorPortal ?: return
        val now = room.timeline.now
        if (now > floorOpenedAt) fx.spotAt(TfaIds.Spot.OCELLUS_FLOOR_PORTAL_IDLE, portal) // 4272: 1 t loop, resent
        for (p in room.playersIn(quadrant)) {
            val t = room.tileOf(p) ?: continue
            if (onFloorPortal(t, portal)) hop(p, t)
        }
    }

    /** Stops now: walls and collision removed, nothing more scheduled (Blade destroyed, phase skip, wipe). */
    fun abort() {
        if (done) return
        done = true
        room.timeline.cancel(tag)
        for (obj in walls.values) fx.removeLoc(obj)
        walls.clear()
        layout.blockTiles.forEach(fx::unblock)
        floorPortal?.let { fx.spotAt(TfaIds.Spot.OCELLUS_FLOOR_PORTAL_DESPAWN, it) }
        floorPortal = null
    }

    // ---- steps --------------------------------------------------------------------------------------------------

    /** [HOUSE] Every arena player outside the quadrant is moved in (the walls seal it; spec "meet at the SW slice"). */
    private fun gather() {
        if (!cfg.gatherIntoQuadrant) return
        val inside = layout.quadrantTiles
        for (p in room.targets()) {
            val t = room.tileOf(p) ?: continue
            if (quadrant.contains(t.x, t.y)) continue
            val prev = Quadrant.entries.first { it.next == quadrant }
            val dest = if (prev.contains(t.x, t.y)) ArenaGeometry.rotateCw(t) else inside[room.random.nextInt(inside.size)]
            fx.teleport(p, dest)
            fx.spotAt(TfaIds.Spot.OCELLUS_FLOOR_PORTAL_DESPAWN, dest)
        }
    }

    private fun spawnWalls() {
        for (s in layout.segments) {
            val id = if (layout.isOrange(s)) TfaIds.Loc.OCELLUS_WALL_ORANGE else TfaIds.Loc.OCELLUS_WALL_PURPLE
            val obj = fx.spawnLoc(id, 10, s.rotation, s.locTile)
            fx.locAnim(obj, TfaIds.Seq.OCELLUS_P2_PORTAL_SPAWN)   // 14829, 1 t
            walls[s] = obj
        }
        layout.blockTiles.forEach(fx::block)
        room.timeline.after(1, tag) { for (obj in walls.values) fx.locAnim(obj, TfaIds.Seq.OCELLUS_P2_PORTAL_IDLE) } // 14830
    }

    private fun shoot(segments: List<PortalSegment>) {
        val spec = room.cfg.projectiles.p2Shot
        val cycles = cfg.shotTicks * 30
        for (s in segments) for ((from, to) in layout.shotTargets(s)) {
            fx.projectileTiles(TfaIds.Spot.OCELLUS_PORTAL_SHOT, from, to, spec, cycles)
        }
    }

    private fun volley() {
        val firing = layout.segments.filter { !layout.isOrange(it) }
        shoot(firing)
        val hit = HashSet<Pair<PortalSegment, Player>>()
        for (k in 1..cfg.shotTicks) {
            room.timeline.after(k, tag) {
                for (p in room.playersIn(quadrant)) {
                    val t = room.tileOf(p) ?: continue
                    for (s in firing) {
                        if (layout.laneIndex(s.edge, t) != s.index) continue
                        if (layout.passTick(s, t, cfg.shotTicks) != k) continue
                        if (!hit.add(s to p)) continue
                        fx.hurt(p, roll(cfg.laneDmg), HitType.DEFAULT, room.boss)
                    }
                }
            }
        }
    }

    private fun allPurple() {
        for ((s, obj) in walls.entries.toList()) {
            if (obj.id != TfaIds.Loc.OCELLUS_WALL_ORANGE) continue
            walls[s] = fx.swapLoc(obj, TfaIds.Loc.OCELLUS_WALL_PURPLE)
        }
    }

    private fun spawnPools() {
        val tiles = layout.quadrantTiles.shuffled(room.random)
        pools = tiles.take(cfg.pools.coerceAtMost(tiles.size)).toSet()
        for (t in pools) fx.spotAt(TfaIds.Spot.OCELLUS_P2_VOID_POOL, t)   // 4270, 6 t
    }

    private fun poolDamage() {
        for (p in room.playersIn(quadrant)) {
            val t = room.tileOf(p) ?: continue
            if (t in pools) fx.hurt(p, roll(cfg.poolDmg), HitType.DEFAULT, room.boss)
        }
    }

    private fun openFloorPortal() {
        val free = layout.quadrantTiles.filter { it !in pools && fitsPortal(it) }.ifEmpty { layout.quadrantTiles }
        val t = free[room.random.nextInt(free.size)]
        floorPortal = t
        floorOpenedAt = room.timeline.now
        fx.spotAt(TfaIds.Spot.OCELLUS_FLOOR_PORTAL_SPAWN, t)   // 4271, 1 t
    }

    private fun fitsPortal(t: StaticTile): Boolean {
        val n = cfg.floorPortalSize
        for (dx in 0 until n) for (dy in 0 until n) if (!quadrant.contains(t.x + dx, t.y + dy)) return false
        return true
    }

    private fun onFloorPortal(t: StaticTile, portal: StaticTile): Boolean {
        val n = cfg.floorPortalSize
        return t.x in portal.x until portal.x + n && t.y in portal.y until portal.y + n
    }

    /** Spec: "moved to the same relative tile of the next quadrant (spot 4273)". */
    private fun hop(p: Player, from: StaticTile) {
        val dest = ArenaGeometry.rotateCw(from)
        fx.spotAt(TfaIds.Spot.OCELLUS_FLOOR_PORTAL_DESPAWN, from)
        fx.teleport(p, dest)
    }

    private fun fireAll() {
        shoot(walls.keys.toList())
    }

    private fun quadrantAttack() {
        for (p in room.playersIn(quadrant)) fx.hurt(p, cfg.quadrantAttackDmg, HitType.DEFAULT, room.boss)
    }

    private fun despawnWalls() {
        for (obj in walls.values) fx.locAnim(obj, OcellusIds.PORTAL_DESPAWN)   // 14831, 1 t
        val gone = walls.values.toList()
        walls.clear()
        room.timeline.after(1, tag) { gone.forEach(fx::removeLoc) }
        layout.blockTiles.forEach(fx::unblock)
    }

    private fun closeFloorPortal() {
        floorPortal?.let { fx.spotAt(TfaIds.Spot.OCELLUS_FLOOR_PORTAL_DESPAWN, it) }
        floorPortal = null
    }

    private fun finish() {
        if (done) return
        done = true
        onDone?.invoke()
    }

    private fun roll(range: IntRange): Int = range.first + room.random.nextInt(range.last - range.first + 1)

    fun debugLine(): String =
        "barrage ${number + 1} in $quadrant speed=${entry.speed} orange col=${entry.col} row=${entry.row} " +
            "safe=${layout.safeTiles().joinToString(" ") { "${it.x},${it.y}" }} portal=${floorPortal?.let { "${it.x},${it.y}" }}"
}

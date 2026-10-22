package com.gadscape.raids.tfa.rooms.guardian

import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.content.skills.prayer.Prayer
import com.zenyte.game.world.World
import com.zenyte.game.world.`object`.WorldObject
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.player.Player

/**
 * Tendrils (§4.1, wiki: "While grounded, after an initial 6 tick delay, the Archive Guardian will target the players'
 * locations with a tendril every 3 ticks ... a 3x3 AoE swipe ... attacking 3 times before disappearing"):
 *
 * per wave, for each living player: spot 4208 telegraph on the tile one step further from the boss (4 t), then the
 * Vine 16664 emerges (seq 14771, 4 t), then swipes (seq 14768) on the tendril clock: the first clock beat after it
 * emerged, then every beat, 3 times; then it despawns (seq 14769) and is removed 2 t later. Tendrils alive when the
 * boss lifts finish their swipes. Each swipe hits players in its 3x3: 0-25 melee, 0-11 under Protect from Melee.
 */
internal class TendrilController(private val room: ArchiveGuardianRoom) {

    private val vines = ArrayList<GuardianVine>()
    private val pendingTiles = HashSet<Tile>()

    val alive: Int get() = vines.size

    /** One wave: a telegraph next to every living player in the arena. */
    fun wave() {
        val boss = room.boss ?: return
        val cfg = room.cfg.tendril
        val c = room.bossCentre(boss)
        val plane = boss.plane
        for (p in room.alivePlayers()) {
            val t = GuardianGeometry.tendrilTile(p.x, p.y, c.x, c.y)
            if (t in pendingTiles || vines.any { it.x == t.x && it.y == t.y }) continue
            if (!room.walkable(t.x, t.y, plane)) continue
            val loc = Location(t.x, t.y, plane)
            World.sendGraphics(GuardianAnims.TENDRIL_TELEGRAPH, loc)
            pendingTiles += t
            room.timeline.schedule(GuardianAnims.TELEGRAPH_TICKS, TAG) { emerge(t, loc) }
            // Swipes on the shared clock: the first beat at or after the vine has fully emerged (4 + 4 = 8 t → 9 t).
            val ready = GuardianAnims.TELEGRAPH_TICKS + GuardianAnims.VINE_EMERGE_TICKS
            val first = ((ready + cfg.interval - 1) / cfg.interval) * cfg.interval
            var swipesLeft = cfg.swipes
            room.timeline.every(first, cfg.interval, TAG) {
                val vine = vines.firstOrNull { it.x == t.x && it.y == t.y } ?: return@every false
                swipe(vine)
                swipesLeft--
                if (swipesLeft <= 0) {
                    room.timeline.schedule(GuardianAnims.VINE_SWIPE_TICKS, TAG) { despawn(vine) }
                    false
                } else true
            }
        }
    }

    private fun emerge(t: Tile, loc: Location) {
        pendingTiles -= t
        val vine = GuardianVine(room.context, loc)
        vine.spawn()
        vine.setAnimation(GuardianAnims.VINE_SPAWN)
        vine.emerged = true
        vines += vine
    }

    private fun swipe(vine: GuardianVine) {
        vine.setAnimation(GuardianAnims.VINE_SWIPE)
        val cfg = room.cfg.tendril
        for (p in room.alivePlayers()) {
            if (p.plane != vine.plane || !GuardianGeometry.inSwipe(vine.x, vine.y, p.x, p.y)) continue
            val prayed = p.prayerManager.isActive(Prayer.PROTECT_FROM_MELEE)
            val max = GuardianRules.tendrilMax(prayed, cfg.max, cfg.maxPrayed)
            Bridge.hit(p, vine, GuardianRules.roll(room.context.random, 0, max), HitType.MELEE)
        }
    }

    private fun despawn(vine: GuardianVine) {
        if (vine.isFinished) {
            vines -= vine
            return
        }
        vine.setAnimation(GuardianAnims.VINE_DESPAWN)
        room.timeline.schedule(GuardianAnims.VINE_DESPAWN_TICKS, TAG_CLEANUP) {
            vines -= vine
            if (!vine.isFinished) vine.finish()
        }
    }

    /** Boss death: every vine plays its despawn and is removed; telegraphs that have not grown yet are dropped. */
    fun retreat() {
        room.timeline.cancel(TAG)
        pendingTiles.clear()
        for (v in vines.toList()) despawn(v)
    }

    /** Wipe or teardown: removes everything at once. */
    fun clear() {
        room.timeline.cancel(TAG, TAG_CLEANUP)
        pendingTiles.clear()
        for (v in vines) if (!v.isFinished) v.finish()
        vines.clear()
    }

    companion object {
        const val TAG = "tendril"
        const val TAG_CLEANUP = "tendrilCleanup"
    }
}

/**
 * Mushrooms (§4.1, wiki: "mushroom spores around it, dealing 3-10 damage, inflicts poison and can make players
 * weary ... Running adjacent to a mushroom, then immediately two tiles away will pop it dealing no damage"):
 *
 * a cast (seq 14750) places `3 + floor(alive / 2)` locs at Chebyshev 2-6 from the boss centre (never under it, never
 * two on a tile, at most 12 alive): 62578 spawn (anim 14756, 3 t) → 62579 active (anim 8631, 2 t loop). An active
 * mushroom with a living player within 1 tile at the end of a tick becomes 62580 (anim 8632, 2 t) and bursts the
 * next tick on the players then within 1 tile: 3-10 typeless, poison 10, Weary 25 t. Untriggered for 30 t, it
 * becomes 62580 and goes without damage.
 */
internal class MushroomField(private val room: ArchiveGuardianRoom) {

    private enum class Stage { GROWING, ACTIVE, BURSTING }

    private class Mushroom(val tile: Tile, val plane: Int, val bornAt: Int, var obj: WorldObject, var stage: Stage)

    private val mushrooms = ArrayList<Mushroom>()

    val alive: Int get() = mushrooms.size

    /** The cast: seq 14750 now, the mushrooms after `spawnDelay`. */
    fun cast() {
        val boss = room.boss ?: return
        boss.setAnimation(GuardianAnims.MUSHROOM_CAST)
        room.timeline.schedule(room.cfg.mushrooms.spawnDelay, TAG) { spawn() }
    }

    private fun spawn() {
        val boss = room.boss ?: return
        val cfg = room.cfg.mushrooms
        val count = GuardianRules.mushroomCount(room.alivePlayers().size, cfg.base, cfg.perTwoAlive, cfg.cap - mushrooms.size)
        if (count <= 0) return
        val plane = boss.plane
        val taken = mushrooms.mapTo(HashSet()) { it.tile }
        val candidates = GuardianGeometry.mushroomCandidates(boss.x, boss.y, cfg.minDist, cfg.maxDist,
            walkable = { room.walkable(it.x, it.y, plane) }, taken = { it in taken }).toMutableList()
        java.util.Collections.shuffle(candidates, room.context.random)
        val now = room.timeline.now
        for (t in candidates.take(count)) {
            val obj = WorldObject(TfaIds.Loc.GUARDIAN_MUSHROOM_SPAWN, LOC_TYPE, room.context.random.nextInt(4), t.x, t.y, plane)
            World.spawnObject(obj)
            val m = Mushroom(t, plane, now, obj, Stage.GROWING)
            mushrooms += m
            room.timeline.schedule(GuardianAnims.MUSHROOM_SPAWN_TICKS, TAG) {
                if (m.stage == Stage.GROWING && m in mushrooms) swap(m, TfaIds.Loc.GUARDIAN_MUSHROOM_ACTIVE, Stage.ACTIVE)
            }
        }
    }

    /** End of tick: triggers and expiries. */
    fun endOfTick() {
        if (mushrooms.isEmpty()) return
        val cfg = room.cfg.mushrooms
        val now = room.timeline.now
        val players = room.alivePlayers()
        for (m in mushrooms.toList()) {
            if (m.stage != Stage.ACTIVE) continue
            if (now - m.bornAt >= cfg.lifetime) {
                pop(m, harmful = false)
                continue
            }
            val near = players.any { it.plane == m.plane && GuardianGeometry.chebyshev(it.x, it.y, m.tile.x, m.tile.y) <= 1 }
            if (near) pop(m, harmful = true)
        }
    }

    private fun pop(m: Mushroom, harmful: Boolean) {
        swap(m, TfaIds.Loc.GUARDIAN_MUSHROOM_BURST, Stage.BURSTING)
        if (harmful) room.timeline.schedule(1, TAG) { burst(m) }
        room.timeline.schedule(GuardianAnims.MUSHROOM_BURST_TICKS, TAG) { remove(m) }
    }

    private fun burst(m: Mushroom) {
        val boss = room.boss ?: return
        val cfg = room.cfg.mushrooms
        for (p in room.alivePlayers()) {
            if (p.plane != m.plane || GuardianGeometry.chebyshev(p.x, p.y, m.tile.x, m.tile.y) > 1) continue
            Bridge.hit(p, boss, GuardianRules.roll(room.context.random, cfg.dmgMin, cfg.dmgMax), HitType.TYPELESS)
            Bridge.applyPoison(p, cfg.poison, boss)
            Bridge.applyWeary(p, cfg.wearyTicks)
        }
    }

    private fun swap(m: Mushroom, id: Int, stage: Stage) {
        val next = WorldObject(id, LOC_TYPE, m.obj.rotation, m.tile.x, m.tile.y, m.plane)
        World.removeObject(m.obj)
        World.spawnObject(next)
        m.obj = next
        m.stage = stage
    }

    private fun remove(m: Mushroom) {
        if (mushrooms.remove(m)) World.removeObject(m.obj)
    }

    /** Boss death: every mushroom goes harmlessly (62580, removed 2 t later). */
    fun retreat() {
        room.timeline.cancel(TAG)
        for (m in mushrooms.toList()) {
            if (m.stage != Stage.BURSTING) swap(m, TfaIds.Loc.GUARDIAN_MUSHROOM_BURST, Stage.BURSTING)
            room.timeline.schedule(GuardianAnims.MUSHROOM_BURST_TICKS, TAG_CLEANUP) { remove(m) }
        }
    }

    /** Wipe or teardown: removes every loc now. */
    fun clear() {
        room.timeline.cancel(TAG, TAG_CLEANUP)
        for (m in mushrooms) World.removeObject(m.obj)
        mushrooms.clear()
    }

    /** Tiles of the mushrooms alive (debug). */
    fun tiles(): List<Tile> = mushrooms.map { it.tile }

    companion object {
        const val TAG = "mushroom"
        const val TAG_CLEANUP = "mushroomCleanup"

        /** Mushroom locs are centrepiece locs (type 10), walkable (blockwalk=no in their 2746 configs). */
        const val LOC_TYPE = 10
    }
}

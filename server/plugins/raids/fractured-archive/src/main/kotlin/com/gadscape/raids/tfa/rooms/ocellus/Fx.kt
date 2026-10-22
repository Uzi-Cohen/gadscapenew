package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.raid.TfaInstance
import com.zenyte.game.world.Position
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.ForceTalk
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.pathfinding.Flags
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.WorldObject
import kotlin.math.abs
import kotlin.math.max

/**
 * World output of the Ocellus room: graphics, projectiles, locs, collision and damage, all in static coordinates
 * converted through the raid's [TfaInstance]. Tracks every loc and collision flag it adds so [clear] (teardown)
 * removes them. Engine APIs only (World, masks, regions); capabilities other units own go through [Bridge].
 */
internal class Fx(private val instance: TfaInstance) {

    private val spawnedLocs = LinkedHashSet<WorldObject>()
    private val blocked = LinkedHashSet<Location>()

    fun loc(t: StaticTile): Location = instance.translate(t)

    fun static(l: Location): StaticTile? = instance.toStatic(l)

    // ---- graphics ---------------------------------------------------------------------------------------------

    /** Map graphic (spotanim on a tile), seen by everyone near it. */
    fun spotAt(id: Int, t: StaticTile, delay: Int = 0, height: Int = 0) {
        World.sendGraphics(Graphics(id, delay, height), loc(t))
    }

    /** Spotanim on an entity. */
    fun spotOn(e: Entity, id: Int, delay: Int = 0, height: Int = 0) {
        e.setGraphics(Graphics(id, delay, height))
    }

    fun anim(e: Entity, seq: Int) {
        e.setAnimation(Animation(seq))
    }

    fun shout(e: Entity, text: String) {
        e.setForceTalk(ForceTalk(text))
    }

    /**
     * A classic projectile ([spec] shape) from [from] to [to]; returns the ticks until it lands, from the same
     * cycle count the client uses (delay + base + perTile × Chebyshev distance).
     */
    fun projectile(spot: Int, from: Position, to: Position, spec: OcellusConfig.ProjectileSpec): Int {
        val a = if (from is Entity) from.middleLocation else from.position
        val b = if (to is Entity) to.middleLocation else to.position
        val distance = max(abs(a.x - b.x), abs(a.y - b.y))
        val projectile = Projectile(spot, spec.startHeight, spec.endHeight, spec.delay, spec.angle, spec.base, 0, spec.perTile)
        World.sendProjectile(from, to, projectile)
        return spec.ticks(distance)
    }

    /** A tile-to-tile projectile whose flight takes exactly [cycles] client cycles after its delay. */
    fun projectileTiles(spot: Int, from: StaticTile, to: StaticTile, spec: OcellusConfig.ProjectileSpec, cycles: Int) {
        val projectile = Projectile(spot, spec.startHeight, spec.endHeight, spec.delay, spec.angle, spec.base, 0, spec.perTile)
        World.sendProjectile(loc(from), loc(to), projectile, spec.delay + cycles)
    }

    // ---- locs ---------------------------------------------------------------------------------------------------

    fun spawnLoc(id: Int, type: Int, rotation: Int, t: StaticTile): WorldObject {
        val obj = WorldObject(id, type, rotation, loc(t))
        World.spawnObject(obj)
        spawnedLocs += obj
        return obj
    }

    fun removeLoc(obj: WorldObject?) {
        if (obj == null) return
        World.removeObject(obj)
        spawnedLocs.remove(obj)
    }

    /** Replaces [obj] by the same footprint with [newId]; returns the new loc. */
    fun swapLoc(obj: WorldObject, newId: Int): WorldObject {
        val next = WorldObject(newId, obj.type, obj.rotation, obj)
        spawnedLocs.remove(obj)
        World.spawnObject(next) // a loc of the same slot on that tile replaces the old one
        spawnedLocs += next
        return next
    }

    fun locAnim(obj: WorldObject, seq: Int) {
        World.sendObjectAnimation(obj, Animation(seq))
    }

    /** A static map loc of [id] on [t] (any type), or null. */
    fun findStaticLoc(id: Int, t: StaticTile): WorldObject? {
        val l = loc(t)
        for (type in intArrayOf(10, 11, 22, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9)) {
            val o = World.getObjectWithType(l, type) ?: continue
            if (o.id == id) return o
        }
        return null
    }

    // ---- collision ----------------------------------------------------------------------------------------------

    /** Closes [t] to walking (plain floor collision, no loc). */
    fun block(t: StaticTile) {
        val l = loc(t)
        if (!blocked.add(l)) return
        World.getRegion(l.regionId, true).addFlag(l.plane, l.x and 63, l.y and 63, Flags.FLOOR)
    }

    fun unblock(t: StaticTile) {
        val l = loc(t)
        if (!blocked.remove(l)) return
        World.getRegion(l.regionId, true).removeFlag(l.plane, l.x and 63, l.y and 63, Flags.FLOOR)
    }

    // ---- players ------------------------------------------------------------------------------------------------

    /** Applies [amount] damage of [type] from [source] to [p] (skipped in godmode); returns the damage sent. */
    fun hurt(p: Player, amount: Int, type: HitType = HitType.DEFAULT, source: Entity? = null): Int {
        if (Bridge.isProtected(p) || p.isDead || p.isFinished) return 0
        val hit = if (source != null) Hit(source, amount, type) else Hit(amount, type)
        p.applyHit(hit)
        return amount
    }

    fun heal(p: Player, amount: Int) {
        if (amount <= 0 || p.isDead || p.isFinished) return
        p.heal(amount)
    }

    fun teleport(e: Entity, t: StaticTile) {
        e.setLocation(loc(t))
    }

    // ---- teardown ----------------------------------------------------------------------------------------------

    /** Removes every loc spawned and collision flag added through this instance. */
    fun clear() {
        for (obj in spawnedLocs.toList()) World.removeObject(obj)
        spawnedLocs.clear()
        for (l in blocked.toList()) World.getRegion(l.regionId, true).removeFlag(l.plane, l.x and 63, l.y and 63, Flags.FLOOR)
        blocked.clear()
    }

    /** Forgets [obj] without removing it (it is left to the map, e.g. restored static locs). */
    fun release(obj: WorldObject) {
        spawnedLocs.remove(obj)
    }
}

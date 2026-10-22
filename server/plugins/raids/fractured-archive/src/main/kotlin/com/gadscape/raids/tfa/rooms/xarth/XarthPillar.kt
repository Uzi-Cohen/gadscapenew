package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.encounter.TfaNpc
import com.zenyte.game.util.Direction
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.WorldObject

/**
 * Pillar helper npc 16685 `tfa_xarth_rubble_npc` (invisible box 32709, size 2, `active=no`, no HP) and its destroyed
 * form 16686 (debris model 62467). Spec §4.7: destroyed by script, not by damage (ToB Verzik P1 pattern): the blast
 * removes the Rubble loc, transforms 16685 → 16686 and plays 14997 (2.4 t) with spot 4382. House: the debris stays
 * until the room ends; it blocks nothing.
 */
internal class PillarHelper(
    context: TfaEncounterContext,
    tile: Location,
) : TfaNpc(context, XarthIds.PILLAR, tile, Direction.SOUTH) {

    override fun updateCombatDefinitions() {
        combatDefinitions = XarthCombatDefs.helper(id)
        setHitpoints(1)
    }

    fun collapse() {
        setTransformation(XarthIds.PILLAR_DESTROYED)
        animation = Animation(XarthIds.SEQ_RUBBLE_COLLAPSE)
        graphics = Graphics(XarthIds.SPOT_RUBBLE_COLLAPSE)
    }

    fun restore() {
        if (id != XarthIds.PILLAR) setTransformation(XarthIds.PILLAR)
    }

    override fun processNPC() {}

    override fun afterMovement() {}

    override fun autoRetaliate(source: Entity) {}

    override fun checkAggressivity(): Boolean = false

    override fun isEntityClipped(): Boolean = false

    override fun canAttack(source: Player): Boolean = false

    override fun isAttackableNPC(): Boolean = false

    override fun isCycleHealable(): Boolean = false

    override fun performDefenceAnimation(attacker: Entity) {}

    override fun sendDeath() {}

    override fun drop(tile: Location) {}
}

/**
 * The three pillars of one room: their Rubble locs (already in the copied map) and their helper NPCs. [collapse]
 * removes a loc for good (blocks walking and line of sight no more); [restoreAll] puts every loc back (teardown on the
 * static debug map, `::tfa skipto p1`).
 */
internal class XarthPillars(private val room: XarthRoom) {

    private class Entry(val pillar: Pillar, val tile: Location) {
        var helper: PillarHelper? = null
        var removed: WorldObject? = null
    }

    private val entries = LinkedHashMap<Pillar, Entry>()

    fun build() {
        if (entries.isNotEmpty()) return
        for (p in Pillar.entries) {
            val e = Entry(p, room.translate(p.sw))
            e.helper = PillarHelper(room.context, e.tile).also { it.spawn() }
            entries[p] = e
        }
    }

    fun collapse(p: Pillar) {
        val e = entries[p] ?: return
        val loc = World.getObjectWithType(e.tile, WorldObject.DEFAULT_TYPE)
        if (loc != null && loc.id == p.locId && e.removed == null) {
            val copy = WorldObject(loc)
            World.removeObject(loc)
            e.removed = copy
        }
        e.helper?.collapse()
    }

    fun restoreAll() {
        for (e in entries.values) {
            e.removed?.let { World.spawnObject(it) }
            e.removed = null
            e.helper?.let { if (!it.isFinished) it.restore() }
        }
    }

    fun despawn() {
        for (e in entries.values) {
            e.helper?.let { if (!it.isFinished) it.finish() }
            e.helper = null
        }
    }

    fun isCollapsed(p: Pillar): Boolean = entries[p]?.removed != null

    fun helpers(): List<NPC> = entries.values.mapNotNull { it.helper }
}

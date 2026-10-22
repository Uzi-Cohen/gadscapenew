package com.gadscape.game.world.entity.player.status

import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Random
import java.util.WeakHashMap

/**
 * Numbers of a Nex-style contagious cough (Moia's infect, spec §4.4 "Virus" [COMM "similar to Nex"; HOUSE numbers],
 * `moia.json infect`).
 *
 * @property duration ticks an infection lasts; spreading to a carrier refreshes it
 * @property coughEvery ticks between coughs
 * @property damageMin damage range of a cough on a player (poison hitsplat; prayers do not help)
 * @property serpentineMultiplier factor on a player's cough damage with a serpentine helm (any charged variant)
 * @property npcDamage flat cough damage on an infected NPC (undead binders: 20)
 * @property coughText force-chat of a coughing carrier, or null for none
 */
data class VirusSettings @JvmOverloads constructor(
    val duration: Int = 20,
    val coughEvery: Int = 4,
    val damageMin: Int = 4,
    val damageMax: Int = 8,
    val serpentineMultiplier: Double = 0.25,
    val npcDamage: Int = 20,
    val coughText: String? = "*Cough*",
)

/** How a [VirusOutbreak] sees its carriers; [EntityVirusHost] for players and NPCs, a fake in tests. */
interface VirusHost<T : Any> {
    fun x(carrier: T): Int
    fun y(carrier: T): Int
    fun plane(carrier: T): Int
    fun size(carrier: T): Int = 1
    fun isGone(carrier: T): Boolean

    /** Cough damage on [carrier] before it is applied: the rolled player damage or the NPC flat damage. */
    fun coughDamage(carrier: T, settings: VirusSettings, random: Random): Int

    fun applyCough(carrier: T, damage: Int, settings: VirusSettings)
    fun onInfected(carrier: T, spread: Boolean) {}
    fun onCleared(carrier: T) {}
}

/**
 * One room's contagious virus (spec §4.4, template the GadScape Nex choke code). The encounter owns it and drives it
 * from its timeline: [infect] when the infect projectile lands, [tick] once per tick with everyone who can catch it
 * (players and binders in the arena), [clearAll] on room end, wipe or teardown.
 *
 * Each [tick]:
 * 1. every carrier counts down; on its cough tick it coughs (damage, force-chat); at 0 it is cured;
 * 2. carriers that died or left are dropped ("cleared on death");
 * 3. spread: anyone within Chebyshev distance 1 of a carrier (footprints considered) becomes infected or has its
 *    timer refreshed. So a carrier infects an adjacent player within one tick.
 */
class VirusOutbreak<T : Any>(
    val settings: VirusSettings,
    private val random: Random,
    private val host: VirusHost<T>,
) {
    private class Infection(var ticksLeft: Int, var untilCough: Int)

    private val infections = IdentityHashMap<T, Infection>()

    init {
        synchronized(live) { live.add(this) }
    }

    val carriers: Set<T> get() = Collections.unmodifiableSet(infections.keys)

    fun isInfected(carrier: T): Boolean = infections.containsKey(carrier)

    fun ticksLeft(carrier: T): Int = infections[carrier]?.ticksLeft ?: 0

    /** Infects [carrier] (or refreshes its timer). */
    @JvmOverloads
    fun infect(carrier: T, spread: Boolean = false) {
        if (host.isGone(carrier)) return
        val existing = infections[carrier]
        if (existing != null) {
            existing.ticksLeft = settings.duration
            return
        }
        infections[carrier] = Infection(settings.duration, settings.coughEvery)
        host.onInfected(carrier, spread)
    }

    fun clear(carrier: T) {
        if (infections.remove(carrier) != null) host.onCleared(carrier)
    }

    fun clearAll() {
        val all = infections.keys.toList()
        infections.clear()
        all.forEach(host::onCleared)
    }

    /** Removes [carrier] from this outbreak if it is one of its carriers (any type), e.g. on death. */
    internal fun clearAny(carrier: Any) {
        @Suppress("UNCHECKED_CAST")
        if (infections.containsKey(carrier as T)) clear(carrier)
    }

    /** Advances one tick; [population] is everyone who can catch the virus (carriers included or not). */
    fun tick(population: Collection<T>) {
        val iterator = infections.entries.iterator()
        while (iterator.hasNext()) {
            val (carrier, infection) = iterator.next()
            if (host.isGone(carrier)) {
                iterator.remove()
                host.onCleared(carrier)
                continue
            }
            if (--infection.untilCough <= 0) {
                infection.untilCough = settings.coughEvery
                host.applyCough(carrier, host.coughDamage(carrier, settings, random), settings)
            }
            if (--infection.ticksLeft <= 0) {
                iterator.remove()
                host.onCleared(carrier)
            }
        }
        if (infections.isEmpty()) return
        val sources = infections.keys.toList()
        for (candidate in population) {
            if (host.isGone(candidate)) continue
            if (sources.any { it !== candidate && adjacent(it, candidate) }) infect(candidate, spread = true)
        }
    }

    private fun adjacent(a: T, b: T): Boolean {
        if (host.plane(a) != host.plane(b)) return false
        val ax = host.x(a); val ay = host.y(a); val asz = host.size(a)
        val bx = host.x(b); val by = host.y(b); val bsz = host.size(b)
        // Chebyshev distance between the two footprints <= 1
        val dx = maxOf(0, maxOf(ax - (bx + bsz - 1), bx - (ax + asz - 1)))
        val dy = maxOf(0, maxOf(ay - (by + bsz - 1), by - (ay + asz - 1)))
        return maxOf(dx, dy) <= 1
    }

    companion object {
        private val live: MutableSet<VirusOutbreak<*>> = Collections.newSetFromMap(WeakHashMap())

        /** Cures [carrier] in every live outbreak (called on player death by [PlayerStatuses]). */
        @JvmStatic
        fun clearEverywhere(carrier: Any) {
            val outbreaks = synchronized(live) { live.toList() }
            outbreaks.forEach { it.clearAny(carrier) }
        }

        /** An outbreak over players and NPCs with the [EntityVirusHost] rules. */
        @JvmStatic
        fun ofEntities(settings: VirusSettings, random: Random): VirusOutbreak<Entity> =
            VirusOutbreak(settings, random, EntityVirusHost)

        /** A cough on a player: rolled damage, × [VirusSettings.serpentineMultiplier] with a serpentine helm (floor). */
        @JvmStatic
        fun playerCoughDamage(rolled: Int, serpentine: Boolean, settings: VirusSettings): Int =
            if (serpentine) (rolled * settings.serpentineMultiplier).toInt() else rolled
    }
}

/**
 * [VirusHost] for game entities: players cough for `damageMin..damageMax` (× 0.25 with a serpentine helm), NPCs for
 * the flat [VirusSettings.npcDamage]; poison hitsplat, no source (protection prayers do not help); force-chat
 * "*Cough*". Dead or finished entities are dropped.
 */
object EntityVirusHost : VirusHost<Entity> {
    override fun x(carrier: Entity) = carrier.x
    override fun y(carrier: Entity) = carrier.y
    override fun plane(carrier: Entity) = carrier.plane
    override fun size(carrier: Entity) = carrier.size
    override fun isGone(carrier: Entity) = carrier.isDead || carrier.isFinished

    override fun coughDamage(carrier: Entity, settings: VirusSettings, random: Random): Int = when (carrier) {
        is NPC -> settings.npcDamage
        is Player -> {
            val rolled = settings.damageMin + random.nextInt(maxOf(1, settings.damageMax - settings.damageMin + 1))
            VirusOutbreak.playerCoughDamage(rolled, CombatUtilities.isWearingSerpentineHelmet(carrier), settings)
        }
        else -> 0
    }

    override fun applyCough(carrier: Entity, damage: Int, settings: VirusSettings) {
        settings.coughText?.let { carrier.setForceTalk(it) }
        if (damage > 0) carrier.applyHit(Hit(damage, HitType.POISON))
    }

    override fun onInfected(carrier: Entity, spread: Boolean) {
        if (carrier is Player) {
            carrier.sendMessage(if (spread) "<col=ef1020>Someone has spread a cough to you!</col>"
            else "<col=ef1020>You have been infected with a cough!</col>")
        }
    }
}

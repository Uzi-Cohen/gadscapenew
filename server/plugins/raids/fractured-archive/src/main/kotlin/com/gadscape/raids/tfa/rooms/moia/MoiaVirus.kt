package com.gadscape.raids.tfa.rooms.moia

import com.zenyte.game.world.entity.Entity

/**
 * Moia's contagious cough (spec §4.4 "Virus" [COMM "similar to Nex"; HOUSE numbers, `moia.json infect`]), as the
 * room sees it. [Bridge.virus] backs it with the engine's Nex-style `VirusOutbreak` (WU-05):
 * - [infect] when the infect projectile lands (or to refresh a carrier's timer);
 * - [tick] once per tick with everyone who can catch it (players in the arena and living binders): carriers cough
 *   every `coughEvery` ticks (4-8 poison damage on players, × `serpMult` with a serpentine helm, flat `binderDmg`
 *   on binders; prayers do not help), and anyone within Chebyshev distance 1 of a carrier is infected or refreshed;
 * - [clear] on death, [clearAll] on room end, wipe and teardown.
 */
interface MoiaVirus {
    fun infect(carrier: Entity)
    fun isInfected(carrier: Entity): Boolean
    fun ticksLeft(carrier: Entity): Int
    fun clear(carrier: Entity)
    fun clearAll()
    fun tick(population: Collection<Entity>)
    val carriers: Collection<Entity>
}

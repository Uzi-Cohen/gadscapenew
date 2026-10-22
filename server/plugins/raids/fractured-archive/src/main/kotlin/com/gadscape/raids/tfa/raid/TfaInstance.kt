package com.gadscape.raids.tfa.raid

import com.zenyte.game.world.entity.Location

/**
 * Coordinate contract of a raid's map space (spec §2.4). WU-12 implements it with the 24 x 40-chunk block
 * (`translate(x, y, p) = (x - 4032 + base.x, y - 5504 + base.y, planeMap(region, p))`) and owns allocation,
 * the lazy region copies and release; encounters only use this interface.
 *
 * Every coordinate in the spec and in the config files is static ([StaticTile]); translate before spawning
 * or comparing with live positions, and use [toStatic] to test live positions against static areas
 * (the death-fee zone, hazard tables, debug tools).
 */
interface TfaInstance {

    /** Converts a static cache coordinate to the live instance coordinate. */
    fun translate(x: Int, y: Int, plane: Int): Location

    fun translate(tile: StaticTile): Location = translate(tile.x, tile.y, tile.plane)

    /** The static tile of a live [location], or null if the location is outside this instance. */
    fun toStatic(location: Location): StaticTile?

    /** True if [location] lies inside this instance's map space. */
    fun contains(location: Location): Boolean = toStatic(location) != null
}

/**
 * The identity mapping: static coordinates used as they are. For unit tests and for debug encounters run on the
 * static map (dev worlds), never for a real raid.
 */
object StaticTfaInstance : TfaInstance {
    override fun translate(x: Int, y: Int, plane: Int): Location = Location(x, y, plane)
    override fun toStatic(location: Location): StaticTile = StaticTile(location.x, location.y, location.plane)
}

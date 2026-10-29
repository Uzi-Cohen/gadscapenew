package com.gadscape.game.content.kits

/**
 * Interface and component ids of the Premade Kits tab, as the cache patch makes them (editor utils.KitsPatch,
 * object KitsPatch.Ids). Both files must say the same; the patch checks its own layout against these numbers.
 */
@Suppress("unused")
object KitsUi {
    const val ID = 5250

    const val ROOT = 0
    const val BACKGROUND = 1
    const val TITLE = 2

    /** Kit tiles: tile k's components start at TILE_FIRST + k * TILE_STRIDE; offsets below. */
    const val TILES = 6
    const val TILE_FIRST = 3
    const val TILE_STRIDE = 27
    /** The tile's layer (holds the op: Spawn / Re-spawn). */
    const val TILE = 0
    /** Normal frame layer (nine graphics follow it). */
    const val FRAME = 1
    /** Selected frame layer (nine graphics follow it), hidden unless it's the last kit spawned. */
    const val FRAME_SELECTED = 11
    const val NAME = 21
    const val LEVEL = 22
    /** Helm, body, weapon item models (three). */
    const val ITEM = 23
    const val SPAWN = 26

    const val FOOTER = TILE_FIRST + TILES * TILE_STRIDE

    /** The tab icon replacing the music note (sprite id, KitsPatch). */
    const val TAB_ICON_SPRITE = 21500

    fun tile(k: Int, part: Int) = TILE_FIRST + k * TILE_STRIDE + part

    /** The tile index of a tile's layer component, or -1. */
    fun tileOf(component: Int): Int {
        val rel = component - TILE_FIRST
        if (rel < 0 || rel >= TILES * TILE_STRIDE || rel % TILE_STRIDE != TILE) return -1
        return rel / TILE_STRIDE
    }
}

package utils

import cache.loaders.OverlayLoader
import cache.loaders.UnderlayLoader

/**
 * Twin floors for View > 117 HD floors: a floor the cache already has that looks exactly the same under another number
 * 117 HD has no rule for (overlay 34 is overlay 10's), so the floor shows in the game as painted. Only floors that
 * already exist are used (for an underlay, the closest colour when none is identical): nothing is written to the cache.
 */
object FloorTwins {
    private val twins = HashMap<Pair<Boolean, Int>, Int?>()

    /** The twin's stored number (file id + 1) for [stored], or null if the cache has no identical floor. */
    @Synchronized
    fun twinFor(overlay: Boolean, stored: Int, overlays: OverlayLoader, underlays: UnderlayLoader): Int? =
        twins.getOrPut(overlay to stored) {
            if (overlay) {
                val me = overlays.get(stored - 1) ?: return@getOrPut null
                overlays.ids().firstOrNull { f ->
                    val o = overlays.get(f)
                    f != stored - 1 && o != null && !HdFloors.hasRules(true, f + 1) &&
                        o.rgbColor == me.rgbColor && o.texture == me.texture &&
                        o.secondaryRgbColor == me.secondaryRgbColor && o.hideUnderlay == me.hideUnderlay
                }?.plus(1)
            } else {
                // no identical one: the closest colour (an underlay mostly sits under paint, where only edges blend it)
                val me = underlays.get(stored - 1) ?: return@getOrPut null
                underlays.ids().filter { f -> f != stored - 1 && underlays.get(f) != null && !HdFloors.hasRules(false, f + 1) }
                    .minByOrNull { f -> distance(underlays.get(f)!!.color, me.color) }
                    ?.takeIf { distance(underlays.get(it)!!.color, me.color) <= 40 * 40 * 3 }?.plus(1)
            }
        }

    private fun distance(a: Int, b: Int): Int {
        val dr = (a shr 16 and 255) - (b shr 16 and 255)
        val dg = (a shr 8 and 255) - (b shr 8 and 255)
        val db = (a and 255) - (b and 255)
        return dr * dr + dg * dg + db * db
    }
}

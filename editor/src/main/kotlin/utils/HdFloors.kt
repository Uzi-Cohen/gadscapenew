package utils

import com.google.gson.JsonParser

/**
 * 117 HD's floor rules, so the editor can draw floors the way players with HD see them (View > 117 HD floors).
 *
 * HD doesn't draw a floor in its painted colour everywhere: per area and floor number it recolours it and lays a
 * texture over it (Edgeville's old house floors turn into brown small tiles, for one). The rules are exported from
 * the GadScape client's own HD classes into resources/hd/floors.json; the lookup below is HD's
 * (Overlay.getOverlay / Underlay.getUnderlay), and the textures are HD's, in resources/hd/textures.
 */
object HdFloors {
    @Volatile var enabled = false

    class Rule(
        val name: String,
        val ids: IntArray,
        val area: String,
        val water: Boolean,
        val hue: Int, val shiftHue: Int,
        val saturation: Int, val shiftSaturation: Int,
        val lightness: Int, val shiftLightness: Int,
        /** per HD's pick for the tile: an OSRS texture id (>= 0), or -(1 + index into [images]) */
        val materials: IntArray
    ) {
        val isNone get() = name == "NONE"
        val recolours get() = hue >= 0 || saturation >= 0 || lightness >= 0 || shiftHue != 0 || shiftSaturation != 0 || shiftLightness != 0
    }

    private class Box(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int)

    /** HD's texture images, in the order they sit after the cache's own textures in the texture array. */
    val images: List<String>
    private val areas = HashMap<String, Array<Box>>()
    private val overlays: List<Rule>
    private val underlays: List<Rule>

    init {
        val res = HdFloors::class.java
        images = res.getResourceAsStream("/hd/textures/list.json")?.reader()?.use { r ->
            JsonParser.parseReader(r).asJsonArray.map { it.asString }
        } ?: emptyList()
        val root = res.getResourceAsStream("/hd/floors.json")?.reader()?.use { JsonParser.parseReader(it).asJsonObject }
        if (root == null) {
            overlays = emptyList(); underlays = emptyList()
        } else {
            for ((name, boxes) in root.getAsJsonObject("areas").entrySet()) {
                areas[name] = boxes.asJsonArray.map { b ->
                    val v = b.asJsonArray.map { it.asInt }
                    Box(v[0], v[1], v[2], v[3], v[4], v[5])
                }.toTypedArray()
            }
            fun rules(key: String) = root.getAsJsonArray(key).map { e ->
                val o = e.asJsonObject
                Rule(
                    o["name"].asString,
                    o.getAsJsonArray("ids").map { it.asInt }.toIntArray(),
                    o["area"].asString,
                    o["water"].asBoolean,
                    o["hue"].asInt, o["shiftHue"].asInt,
                    o["saturation"].asInt, o["shiftSaturation"].asInt,
                    o["lightness"].asInt, o["shiftLightness"].asInt,
                    o.getAsJsonArray("materials").map { m ->
                        val mo = m.asJsonObject
                        when {
                            mo.has("vanilla") -> mo["vanilla"].asInt
                            mo.has("image") -> images.indexOf(mo["image"].asString).let { if (it < 0) Int.MIN_VALUE else -(1 + it) }
                            else -> Int.MIN_VALUE
                        }
                    }.toIntArray()
                )
            }
            overlays = rules("overlays")
            underlays = rules("underlays")
        }
    }

    private fun inArea(area: String, x: Int, y: Int, z: Int): Boolean =
        areas[area]?.any { x in it.minX..it.maxX && y in it.minY..it.maxY && z in it.minZ..it.maxZ } ?: false

    // HD keeps floor numbers as the client's signed bytes: -119 is 137
    private fun idMatches(ruleId: Int, stored: Int) = ruleId == stored || (ruleId < 0 && ruleId + 256 == stored)

    /** HD's lookup: the first id-less rule whose area has the tile, unless an earlier rule for this floor number does. */
    private fun lookup(rules: List<Rule>, stored: Int, x: Int, y: Int, z: Int): Rule? {
        if (rules.isEmpty()) return null
        var any = rules.size - 1 // NONE, the last one
        for (i in rules.indices) {
            val r = rules[i]
            if (r.ids.isEmpty() && inArea(r.area, x, y, z)) { any = i; break }
        }
        for (i in 0 until any) {
            val r = rules[i]
            if (r.ids.any { idMatches(it, stored) } && inArea(r.area, x, y, z)) return r
        }
        return rules[any]
    }

    /**
     * The rule HD uses for a tile (stored floor numbers: overlay and underlay as in the map file, 0 = none).
     * Like HD's terrain pass, an overlay rule wins even on a tile with no overlay when its area covers the tile.
     */
    fun ruleFor(overlay: Int, underlay: Int, x: Int, y: Int, z: Int): Rule? {
        val o = lookup(overlays, overlay, x, y, z)
        if (o != null && !o.isNone) return o
        val u = lookup(underlays, underlay, x, y, z)
        return if (u != null && !u.isNone) u else o ?: u
    }

    /** HD's colour change on a packed HSL colour (6 bits hue, 3 saturation, 7 lightness). */
    fun recolour(rule: Rule, hsl: Int): Int {
        if (hsl < 0 || !rule.recolours) return hsl
        var h = hsl shr 10 and 63
        var s = hsl shr 7 and 7
        var l = hsl and 127
        h = ((if (rule.hue >= 0) rule.hue else h) + rule.shiftHue).coerceIn(0, 63)
        s = ((if (rule.saturation >= 0) rule.saturation else s) + rule.shiftSaturation).coerceIn(0, 7)
        l = ((if (rule.lightness >= 0) rule.lightness else l) + rule.shiftLightness).coerceIn(0, 127)
        return (h shl 10) or (s shl 7) or l
    }

    /**
     * The texture HD lays on the tile, as a texture-array layer for the editor: an OSRS texture id, or one of HD's
     * images after the cache's [cacheTextures] textures. -1 for none. HD picks among a material's textures per tile
     * with this seed (GroundMaterial.getRandomMaterial).
     */
    fun textureFor(rule: Rule, z: Int, x: Int, y: Int, cacheTextures: Int): Int {
        if (rule.water || rule.materials.isEmpty()) return -1
        val seed = (z + 1).toLong() * 10 * (x % 100) * 20 * (y % 100) * 30
        val m = rule.materials[java.util.Random(seed).nextInt(rule.materials.size)]
        return when {
            m == Int.MIN_VALUE -> -1
            m >= 0 -> m
            else -> cacheTextures + (-m - 1)
        }
    }
}

package utils

import cache.definitions.ModelDefinition
import cache.loaders.ModelLoader
import com.displee.cache.CacheLibrary

/**
 * GadScape's vote-only items, written into a build 241 cache (the server's ids: com.gadscape.game.item.CustomItemId;
 * the pets are MiscPet LIL_MITHRIL..RUNEY, the stats and examines are server/core/src/main/resources/gadscape/items/vote.json):
 *
 *  - Pets (items 50040-50044, follower NPCs 17000-17004) made from NPCs that have no pet: Lil' Mithril (Mithril dragon
 *    2919), Frostbite (Frost dragon 14922), Beastie (Dark beast 4005), Abby (Abyssal demon 415), Runey (Rune dragon 8027).
 *    The follower is the NPC's models, recolours, stand and walk animations at size 1, shrunk with its width / height
 *    scale, with a pet's settings as the stock pets have them (Baby mole 6635: Talk-to / Pick-up, combat 0, hidden on
 *    the minimap, follower flags 122 and 123). The item's picture is the NPC's body: its models (without a flat shadow)
 *    in one model with the NPC's recolours, centred, at 63110-63114; the camera distance follows the model's size.
 *  - The Voter's crown, sceptre and cape (50045-50047): the models in resources vote/models (OBJ + MTL, exported with
 *    the axes `v x -y -z`, so imported with y and z flipped back) at 63100-63105, on the settings of Royal crown 12397,
 *    Royal sceptre 12439 and Attack cape(t) 9748 (slot, wear flags, picture camera, worn offsets). The female worn
 *    model is the male one. No params: the items have no stats (the server takes them, all zero, from vote.json).
 *
 * Idempotent: everything is built from stock definitions and the resources, never from what this wrote, and written
 * only when the cache holds something else. Close the server, client and editor that use the cache first.
 *
 *     java -cp <editor fat jar> utils.VoteItemsPatch <cache folder>
 */
object VoteItemsPatch {

    private const val CONFIGS = 2
    private const val NPCS = 9
    private const val ITEMS = 10
    private const val MODELS = 7

    const val CROWN = 50045
    const val SCEPTRE = 50046
    const val CAPE = 50047

    const val CROWN_INV_MODEL = 63100
    const val CROWN_WORN_MODEL = 63101
    const val SCEPTRE_INV_MODEL = 63102
    const val SCEPTRE_WORN_MODEL = 63103
    const val CAPE_INV_MODEL = 63104
    const val CAPE_WORN_MODEL = 63105
    const val FIRST_PET_ICON_MODEL = 63110

    private const val ROYAL_CROWN = 12397
    private const val ROYAL_SCEPTRE = 12439
    private const val ATTACK_CAPE_T = 9748

    /** A vote pet: its item, its follower NPC, the NPC it's made from, its scale (128ths) and the picture's angles. */
    private class PetSpec(val item: Int, val npc: Int, val source: Int, val name: String, val scale: Int, val xan: Int, val yan: Int)

    private val PETS = listOf(
        PetSpec(50040, 17000, 2919, "Lil' Mithril", 32, 150, 1650),
        PetSpec(50041, 17001, 14922, "Frostbite", 32, 150, 1650),
        PetSpec(50042, 17002, 4005, "Beastie", 50, 150, 1700),
        PetSpec(50043, 17003, 415, "Abby", 45, 100, 1900),
        PetSpec(50044, 17004, 8027, "Runey", 32, 150, 1650),
    )

    /** The picture's camera distance per unit of the model's longest side (Herbi and Vorki are about 15.8). */
    private const val ZOOM_PER_UNIT = 15

    private class Cosmetic(val id: Int, val name: String, val base: Int, val option: String, val obj: String, val invModel: Int, val wornModel: Int)

    private val COSMETICS = listOf(
        Cosmetic(CROWN, "Voter's crown", ROYAL_CROWN, "Wear", "voters_crown", CROWN_INV_MODEL, CROWN_WORN_MODEL),
        Cosmetic(SCEPTRE, "Voter's sceptre", ROYAL_SCEPTRE, "Wield", "voters_sceptre", SCEPTRE_INV_MODEL, SCEPTRE_WORN_MODEL),
        Cosmetic(CAPE, "Voter's cape", ATTACK_CAPE_T, "Wear", "voters_cape", CAPE_INV_MODEL, CAPE_WORN_MODEL),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 1) {
            println("usage: java -cp <editor jar> utils.VoteItemsPatch <build 241 cache folder>")
            kotlin.system.exitProcess(2)
        }
        val lib = CacheLibrary.create(args[0])
        try {
            println(apply(lib) ?: "Nothing to add: the cache already has the vote pets and the Voter's outfit.")
        } finally {
            lib.close()
        }
    }

    /** Writes the pets and the Voter's outfit into [lib]. What was done, or null when the cache already had all of it. */
    fun apply(lib: CacheLibrary): String? {
        val rev = ConfigRev.of(lib)
        if (!ConfigRev.is241(rev)) return "Vote items: not added, the cache isn't a build 241 cache."
        fun item(id: Int): ItemFull = ItemStore.read(lib, id)?.takeIf { it.readOk } ?: error("item $id isn't in the cache (or can't be read)")
        fun npc(id: Int): NpcDef {
            val data = lib.data(CONFIGS, NPCS, id) ?: error("NPC $id isn't in the cache")
            return NpcDefs.decodeAs(id, data, rev).takeIf { it.readOk } ?: error("NPC $id can't be read")
        }
        val loader = ModelLoader(lib)
        fun model(id: Int): ModelDefinition = loader.decode(lib.data(MODELS, id and 0xFFFF, 0) ?: error("model $id isn't in the cache"), id)

        val models = LinkedHashMap<Int, ByteArray>()
        val npcs = LinkedHashMap<Int, ByteArray>()
        val items = ArrayList<ItemFull>()

        // the pets
        for ((i, p) in PETS.withIndex()) {
            val src = npc(p.source)
            npcs[p.npc] = petNpc(p, src)
            val icon = icon(src, ::model)
            val iconId = FIRST_PET_ICON_MODEL + i
            models[iconId] = ModelCodec.encodeOld(icon).data
            val span = maxOf(span(icon.vertexPositionsX), span(icon.vertexPositionsY), span(icon.vertexPositionsZ))
            items += ItemFull(p.item).apply {
                name = p.name
                tradeable = true
                inventoryModel = iconId
                zoom2d = (span * ZOOM_PER_UNIT).coerceIn(200, 65535)
                xan2d = p.xan; yan2d = p.yan
                inventoryOptions[4] = "Drop"
            }
        }

        // the Voter's outfit
        for (c in COSMETICS) {
            models[c.invModel] = obj("${c.obj}_inv")
            models[c.wornModel] = obj("${c.obj}_worn")
            val base = item(c.base)
            items += ItemFull(c.id).apply {
                name = c.name
                tradeable = true
                members = base.members
                weight = base.weight
                inventoryModel = c.invModel
                zoom2d = base.zoom2d; xan2d = base.xan2d; yan2d = base.yan2d; zan2d = base.zan2d
                offsetX2d = base.offsetX2d; offsetY2d = base.offsetY2d
                resizeX = base.resizeX; resizeY = base.resizeY; resizeZ = base.resizeZ
                ambient = base.ambient; contrast = base.contrast
                wearPos1 = base.wearPos1; wearPos2 = base.wearPos2; wearPos3 = base.wearPos3
                maleModel0 = c.wornModel; maleOffset = base.maleOffset
                femaleModel0 = c.wornModel; femaleOffset = base.femaleOffset
                inventoryOptions[1] = c.option
                inventoryOptions[4] = "Drop"
            }
        }

        val done = ArrayList<String>()
        var n = 0
        for ((id, data) in models) if (put(lib, MODELS, id, 0, data)) n++
        if (n > 0) { lib.index(MODELS).update(); done.add("$n model(s)") }
        n = 0
        for ((id, data) in npcs) {
            require(NpcDefs.decodeAs(id, data, rev).let { it.readOk && it.follower && it.size == 1 }) { "NPC $id didn't read back as asked, nothing was written" }
            if (put(lib, CONFIGS, NPCS, id, data)) n++
        }
        val npcCount = n
        n = 0
        for (it in items) if (put(lib, CONFIGS, ITEMS, it.id, ItemStore.checkedBytes(it, rev))) n++
        if (npcCount > 0) done.add("$npcCount pet NPC(s)")
        if (n > 0) done.add("$n item(s)")
        if (npcCount > 0 || n > 0) lib.index(CONFIGS).update()
        if (done.isEmpty()) return null
        return "Vote items: wrote " + done.joinToString() + "."
    }

    /** The follower NPC of [p]: [src]'s look at size 1, scaled down, with a pet's settings (in Baby mole's order). */
    private fun petNpc(p: PetSpec, src: NpcDef): ByteArray {
        val o = CfgOut()
        o.u8(12); o.u8(1)
        o.u8(95); o.u16(0)
        o.u8(97); o.u16(p.scale)
        o.u8(98); o.u16(p.scale)
        o.u8(30); o.str("Talk-to")
        o.u8(32); o.str("Pick-up")
        o.u8(122)
        o.u8(123)
        if (src.standAnim >= 0) { o.u8(13); o.u16(src.standAnim) }
        if (src.walkAnim >= 0) { o.u8(14); o.u16(src.walkAnim) }
        o.u8(93)
        val rf = src.recolorFrom; val rt = src.recolorTo
        if (rf != null && rt != null && rf.isNotEmpty()) { o.u8(40); o.u8(rf.size); for (k in rf.indices) { o.u16(rf[k].toInt() and 0xFFFF); o.u16(rt[k].toInt() and 0xFFFF) } }
        val tf = src.retextureFrom; val tt = src.retextureTo
        if (tf != null && tt != null && tf.isNotEmpty()) { o.u8(41); o.u8(tf.size); for (k in tf.indices) { o.u16(tf[k].toInt() and 0xFFFF); o.u16(tt[k].toInt() and 0xFFFF) } }
        o.u8(2); o.str(p.name)
        require(src.models.isNotEmpty()) { "NPC ${src.id} has no models" }
        o.u8(61); o.u8(src.models.size); for (m in src.models) o.i32(m)
        o.u8(0)
        return o.done()
    }

    /**
     * The item picture of NPC [src]: its models in one (a flat one, the shadow under a dragon, left out), with the NPC's
     * recolours, moved so its middle is over the origin (the game centres the height itself).
     */
    private fun icon(src: NpcDef, model: (Int) -> ModelDefinition): ModelDefinition {
        val parts = src.models.map(model).filter { span(it.vertexPositionsY) > 0 }.ifEmpty { listOf(model(src.models[0])) }
        for (m in parts) require(m.faceTextures?.all { it.toInt() == -1 } != false) { "model ${m.id} of NPC ${src.id} is textured; the pet picture takes colours only" }
        val vc = parts.sumOf { it.vertexCount }
        val fc = parts.sumOf { it.faceCount }
        val out = ModelDefinition()
        out.vertexCount = vc; out.faceCount = fc
        out.vertexPositionsX = IntArray(vc); out.vertexPositionsY = IntArray(vc); out.vertexPositionsZ = IntArray(vc)
        out.faceVertexIndices1 = IntArray(fc); out.faceVertexIndices2 = IntArray(fc); out.faceVertexIndices3 = IntArray(fc)
        out.faceColors = ShortArray(fc)
        val types = ByteArray(fc); var anyType = false
        val alphas = ByteArray(fc); var anyAlpha = false
        val priorities = ByteArray(fc)
        val samePriority = parts.all { it.faceRenderPriorities == null } && parts.map { it.priority }.distinct().size == 1
        val rf = src.recolorFrom; val rt = src.recolorTo
        var v0 = 0; var f0 = 0
        for (m in parts) {
            for (v in 0 until m.vertexCount) {
                out.vertexPositionsX[v0 + v] = m.vertexPositionsX[v]
                out.vertexPositionsY[v0 + v] = m.vertexPositionsY[v]
                out.vertexPositionsZ[v0 + v] = m.vertexPositionsZ[v]
            }
            for (f in 0 until m.faceCount) {
                out.faceVertexIndices1!![f0 + f] = m.faceVertexIndices1!![f] + v0
                out.faceVertexIndices2!![f0 + f] = m.faceVertexIndices2!![f] + v0
                out.faceVertexIndices3!![f0 + f] = m.faceVertexIndices3!![f] + v0
                var c = m.faceColors?.get(f)?.toInt()?.and(0xFFFF) ?: 0
                if (rf != null && rt != null) { val k = rf.indexOfFirst { (it.toInt() and 0xFFFF) == c }; if (k >= 0) c = rt[k].toInt() and 0xFFFF }
                out.faceColors!![f0 + f] = c.toShort()
                m.faceRenderTypes?.get(f)?.let { types[f0 + f] = it; if (it.toInt() != 0) anyType = true }
                m.faceAlphas?.get(f)?.let { alphas[f0 + f] = it; if (it.toInt() != 0) anyAlpha = true }
                priorities[f0 + f] = m.faceRenderPriorities?.get(f) ?: m.priority
            }
            v0 += m.vertexCount; f0 += m.faceCount
        }
        if (anyType) out.faceRenderTypes = types
        if (anyAlpha) out.faceAlphas = alphas
        if (samePriority) out.priority = parts[0].priority else out.faceRenderPriorities = priorities
        // centre it across (x) and front to back (z)
        val cx = (out.vertexPositionsX.minOrNull()!! + out.vertexPositionsX.maxOrNull()!!) / 2
        val cz = (out.vertexPositionsZ.minOrNull()!! + out.vertexPositionsZ.maxOrNull()!!) / 2
        for (v in 0 until vc) { out.vertexPositionsX[v] -= cx; out.vertexPositionsZ[v] -= cz }
        return out
    }

    private fun span(a: IntArray): Int = if (a.isEmpty()) 0 else a.maxOrNull()!! - a.minOrNull()!!

    /** A model from resources vote/models/[name].obj (+ .mtl): game units (scale 1), y and z flipped back. */
    private fun obj(name: String): ByteArray {
        fun res(file: String): ByteArray? = VoteItemsPatch::class.java.getResourceAsStream("/vote/models/$file")?.use { it.readBytes() }
        val obj = res("$name.obj") ?: error("missing resource vote/models/$name.obj")
        return ModelCodec.fromObj(obj, res("$name.mtl"), 1.0, true).data
    }

    private fun put(lib: CacheLibrary, index: Int, archive: Int, file: Int, data: ByteArray): Boolean {
        val current = try { lib.data(index, archive, file) } catch (e: Exception) { null }
        if (current != null && current.contentEquals(data)) return false
        lib.put(index, archive, file, data)
        return true
    }
}

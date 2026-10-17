package utils

import cache.definitions.ModelDefinition
import cache.loaders.ModelLoader
import cache.loaders.TextureLoader
import com.displee.cache.CacheLibrary
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.awt.image.BufferedImage

/**
 * The AI bridge's calls for items, models and animations (the map calls are in [AiBridge]). Reads come from the
 * cache the editor works on (the exported cache once it exists); every change goes into the exported cache with a
 * backup first, so POST /api/cache/undo (or "Undo last change" in the models window) takes it back. Item stats the
 * server keeps (bonuses, attack speed, weapon animations, special attack) go into the server's data files. Nothing is
 * deployed.
 */
class AssetBridge(private val log: (String) -> Unit) {
    class AssetError(msg: String) : Exception(msg)
    private fun fail(msg: String): Nothing = throw AssetError(msg)

    sealed class Reply {
        class Json(val value: Any?) : Reply()
        class Png(val bytes: ByteArray) : Reply()
        class Text(val text: String) : Reply()
    }

    private fun <T> withCache(block: (CacheLibrary, ModelLoader, AnimCache) -> T): T {
        val lib = CacheLibrary(NpcDefs.cacheDir().path)
        try { return block(lib, ModelLoader(lib, HashMap()), AnimCache(lib)) } finally { lib.close() }
    }

    private val configuration by lazy { models.Configuration() }
    private fun server() = ServerSource.dir(configuration)

    /** After a change: the models window and NPC list read the cache again. */
    private fun changed(what: String) {
        itemIndex = null
        log("AI bridge: $what")
        try { javafx.application.Platform.runLater { controllers.ModelAnimWindow.cacheChangedElsewhere() } } catch (e: Exception) {}
    }

    fun route(method: String, path: String, q: Map<String, String>, body: () -> JsonObject): Reply? = when ("$method $path") {
        "GET /api/assets/help" -> Reply.Text(HELP)
        "GET /api/items" -> Reply.Json(items(q))
        "GET /api/item" -> Reply.Json(item(q.int("id")))
        "POST /api/item" -> Reply.Json(saveItem(body()))
        "POST /api/item/noted" -> Reply.Json(makeNoted(body().int("id")))
        "GET /api/item/icon" -> Reply.Png(itemIcon(q.int("id"), q["scale"]?.toIntOrNull() ?: 4))
        "GET /api/item/render" -> Reply.Png(itemRender(q))
        "GET /api/specials" -> Reply.Json(specials())
        "GET /api/models" -> Reply.Json(modelList(q))
        "GET /api/model" -> Reply.Json(modelInfo(q.int("id")))
        "GET /api/model/render" -> Reply.Png(modelRender(q))
        "GET /api/model/on-player" -> Reply.Png(modelOnPlayer(q))
        "POST /api/model/edit" -> Reply.Json(modelEdit(body()))
        "GET /api/anims" -> Reply.Json(animList(q))
        "GET /api/anim" -> Reply.Json(animInfo(q.int("id")))
        "POST /api/anim/name" -> Reply.Json(animRename(body()))
        "POST /api/anims/rebuild-names" -> Reply.Json(animRebuildNames())
        "GET /api/anim/render" -> Reply.Png(animRender(q))
        "POST /api/anim/timing" -> Reply.Json(animTiming(body()))
        "GET /api/npc" -> Reply.Json(npcInfo(q.int("id")))
        "POST /api/cache/undo" -> Reply.Json(mapOf("message" to CacheEdits.undoLast().also { changed("undo") }))
        else -> null
    }

    private fun Map<String, String>.int(k: String): Int = this[k]?.trim()?.toIntOrNull() ?: fail("$k needed (a number)")
    private fun JsonObject.int(k: String): Int = this[k]?.takeIf { !it.isJsonNull }?.asInt ?: fail("$k needed")

    // ======================= items =======================

    @Volatile private var itemIndex: List<Pair<Int, String>>? = null

    private fun items(q: Map<String, String>): List<Map<String, Any?>> {
        val words = (q["q"] ?: "").toLowerCase().split(' ').filter { it.isNotBlank() }
        val limit = (q["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
        val index = itemIndex ?: withCache { lib, _, _ ->
            ItemStore.ids(lib).mapNotNull { id -> ItemStore.read(lib, id)?.let { id to (it.name + if (it.isNoted) " (noted)" else "") } }
        }.also { itemIndex = it }
        return index.asSequence().filter { (id, n) -> words.isEmpty() || words.all { w -> n.toLowerCase().contains(w) || id.toString() == w } }
            .take(limit).map { (id, n) -> mapOf("id" to id, "name" to n) }.toList()
    }

    private fun itemJson(d: ItemFull): Map<String, Any?> = linkedMapOf(
        "id" to d.id, "name" to d.name, "readOk" to d.readOk, "problem" to d.problem.ifEmpty { null },
        "cost" to d.cost, "weight" to d.weight?.let { it / 1000.0 }, "stackable" to d.stackable, "members" to d.members, "tradeable" to d.tradeable,
        "category" to d.category, "team" to d.team,
        "inventoryOptions" to d.inventoryOptions.toList(), "groundOptions" to d.groundOptions.toList(),
        "inventoryModel" to d.inventoryModel, "zoom2d" to d.zoom2d, "xan2d" to d.xan2d, "yan2d" to d.yan2d, "zan2d" to d.zan2d,
        "offsetX2d" to d.offsetX2d, "offsetY2d" to d.offsetY2d, "resize" to listOf(d.resizeX, d.resizeY, d.resizeZ),
        "ambient" to d.ambient, "contrast" to d.contrast,
        "slot" to d.wearPos1, "slotName" to ItemFull.SLOTS[d.wearPos1], "hides" to listOf(d.wearPos2, d.wearPos3),
        "maleModels" to listOf(d.maleModel0, d.maleModel1, d.maleModel2), "maleOffset" to d.maleOffset,
        "femaleModels" to listOf(d.femaleModel0, d.femaleModel1, d.femaleModel2), "femaleOffset" to d.femaleOffset,
        "maleHeads" to listOf(d.maleHead, d.maleHead2), "femaleHeads" to listOf(d.femaleHead, d.femaleHead2),
        "recolours" to d.recolorFrom.indices.map { listOf(d.recolorFrom[it].toInt() and 0xFFFF, d.recolorTo[it].toInt() and 0xFFFF) },
        "retextures" to d.retextureFrom.indices.map { listOf(d.retextureFrom[it].toInt(), d.retextureTo[it].toInt()) },
        "params" to d.params.mapKeys { it.key.toString() },
        "notedId" to d.noteLinked.takeIf { it >= 0 && !d.isNoted }, "isNoted" to d.isNoted
    )

    private fun statsJson(s: ServerItemStats): Map<String, Any?> = linkedMapOf(
        "weight" to s.weight, "slot" to s.slot, "equipmentType" to s.equipmentType,
        "bonuses" to ServerItems.BONUS_NAMES.mapIndexed { i, n -> n to s.bonuses[i] }.toMap(LinkedHashMap()),
        "requirements" to s.requirements.mapKeys { e -> ServerItems.SKILLS.getOrElse(e.key) { i -> i.toString() } },
        "weapon" to s.weapon?.let { w -> linkedMapOf("twoHanded" to w.twoHanded, "attackSpeed" to w.attackSpeed,
            "attackSpeedNote" to "game ticks (0.6 s): 4 = whip, 5 = scythe", "category" to w.category, "categoryName" to ServerItems.WEAPON_CATEGORIES.getOrNull(w.category),
            "range" to w.normalDistance, "longRange" to w.longDistance, "animations" to w.animations()) },
        "special" to s.special, "specialEnergy" to s.specialEnergy)

    private fun statsFor(lib: CacheLibrary, id: Int): Map<String, Any?> {
        val srv = server() ?: return mapOf("note" to "The server folder isn't set in the editor (Tools > NPCs or Shops), so server stats can't be read.")
        val f = ServerItems.definitionsFile(srv)
        if (!f.isFile) return mapOf("note" to "${f.path} isn't there (Items tab > Get from live server).")
        val s = ServerItems.read(srv, id)
        s.special = ServerItems.readSpecials(srv)[id] ?: ServerItems.specialNames(srv).entries.firstOrNull { id in it.value }?.key
        s.specialEnergy = ServerItems.specialEnergy(lib, id)
        return statsJson(s) + ("osrsItem" to (ItemStats.osrs(srv, id) != null))
    }

    private fun item(id: Int): Map<String, Any?> = withCache { lib, _, _ ->
        val d = ItemStore.read(lib, id) ?: fail("There's no item $id.")
        itemJson(d) + ("server" to statsFor(lib, id))
    }

    private fun ints(e: JsonElement?): List<Int>? = e?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asInt }

    /** Applies the cache fields in [o] to [d] (only the ones given). */
    private fun applyItem(d: ItemFull, o: JsonObject) {
        fun has(k: String) = o.has(k) && !o[k].isJsonNull
        if (has("name")) d.name = o["name"].asString
        if (has("cost")) d.cost = o["cost"].asInt
        if (o.has("weight")) d.weight = if (o["weight"].isJsonNull) null else Math.round(o["weight"].asDouble * 1000).toInt().coerceIn(-32768, 32767)
        if (has("stackable")) d.stackable = o["stackable"].asBoolean
        if (has("members")) d.members = o["members"].asBoolean
        if (has("tradeable")) d.tradeable = o["tradeable"].asBoolean
        if (has("category")) d.category = o["category"].asInt
        if (has("team")) d.team = o["team"].asInt.coerceIn(0, 255)
        fun opts(k: String, into: Array<String?>) { if (has(k)) { val a = o[k].asJsonArray; for (i in 0 until 5) into[i] = a.getOrNull(i)?.takeIf { !it.isJsonNull }?.asString?.ifBlank { null } } }
        opts("inventoryOptions", d.inventoryOptions); opts("groundOptions", d.groundOptions)
        if (has("inventoryModel")) d.inventoryModel = o["inventoryModel"].asInt.coerceIn(0, 65535)
        if (has("zoom2d")) d.zoom2d = o["zoom2d"].asInt.coerceIn(1, 65535)
        if (has("xan2d")) d.xan2d = o["xan2d"].asInt and 2047
        if (has("yan2d")) d.yan2d = o["yan2d"].asInt and 2047
        if (has("zan2d")) d.zan2d = o["zan2d"].asInt and 2047
        if (has("offsetX2d")) d.offsetX2d = o["offsetX2d"].asInt
        if (has("offsetY2d")) d.offsetY2d = o["offsetY2d"].asInt
        ints(o["resize"])?.let { d.resizeX = it[0]; d.resizeY = it[1]; d.resizeZ = it[2] }
        if (has("ambient")) d.ambient = o["ambient"].asInt and 255
        if (has("contrast")) d.contrast = o["contrast"].asInt and 255
        if (has("slot")) d.wearPos1 = o["slot"].asInt.also { if (it !in ItemFull.SLOTS.keys) fail("slot must be one of ${ItemFull.SLOTS}") }
        ints(o["hides"])?.let { d.wearPos2 = it.getOrElse(0) { -1 }; d.wearPos3 = it.getOrElse(1) { -1 } }
        ints(o["maleModels"])?.let { d.maleModel0 = it.getOrElse(0) { -1 }; d.maleModel1 = it.getOrElse(1) { -1 }; d.maleModel2 = it.getOrElse(2) { -1 } }
        ints(o["femaleModels"])?.let { d.femaleModel0 = it.getOrElse(0) { -1 }; d.femaleModel1 = it.getOrElse(1) { -1 }; d.femaleModel2 = it.getOrElse(2) { -1 } }
        if (has("maleOffset")) d.maleOffset = o["maleOffset"].asInt and 255
        if (has("femaleOffset")) d.femaleOffset = o["femaleOffset"].asInt and 255
        ints(o["maleHeads"])?.let { d.maleHead = it.getOrElse(0) { -1 }; d.maleHead2 = it.getOrElse(1) { -1 } }
        ints(o["femaleHeads"])?.let { d.femaleHead = it.getOrElse(0) { -1 }; d.femaleHead2 = it.getOrElse(1) { -1 } }
        if (has("recolours")) {
            val pairs = o["recolours"].asJsonArray.map { it.asJsonArray.let { p -> p[0].asInt to p[1].asInt } }
            if (pairs.size > 255) fail("at most 255 recolours")
            d.recolorFrom = ShortArray(pairs.size) { pairs[it].first.toShort() }; d.recolorTo = ShortArray(pairs.size) { pairs[it].second.toShort() }
        }
        if (has("params")) {
            d.params.clear()
            for ((k, v) in o["params"].asJsonObject.entrySet()) {
                val key = k.toIntOrNull() ?: fail("param keys are numbers")
                d.params[key] = if (v.asJsonPrimitive.isNumber) v.asInt else v.asString
            }
        }
    }

    /** Applies "server" stats from [o] to [s]. */
    private fun applyStats(s: ServerItemStats, o: JsonObject) {
        fun has(k: String) = o.has(k) && !o[k].isJsonNull
        if (has("weight")) s.weight = o["weight"].asDouble
        if (has("slot")) s.slot = o["slot"].asInt
        if (has("equipmentType")) s.equipmentType = o["equipmentType"].asString.toUpperCase().also { if (it !in ServerItems.EQUIPMENT_TYPES) fail("equipmentType is one of ${ServerItems.EQUIPMENT_TYPES}") }
        if (has("bonuses")) {
            val b = o["bonuses"]
            if (b.isJsonArray) { val l = b.asJsonArray; if (l.size() != 14) fail("bonuses needs 14 numbers"); for (i in 0 until 14) s.bonuses[i] = l[i].asInt }
            else for ((k, v) in b.asJsonObject.entrySet()) {
                val i = ServerItems.BONUS_NAMES.indexOfFirst { it.equals(k, true) }.takeIf { it >= 0 } ?: k.toIntOrNull() ?: fail("unknown bonus '$k' (${ServerItems.BONUS_NAMES})")
                s.bonuses[i] = v.asInt
            }
        }
        if (has("requirements")) {
            s.requirements.clear()
            for ((k, v) in o["requirements"].asJsonObject.entrySet()) {
                val skill = ServerItems.SKILLS.indexOfFirst { it.equals(k, true) }.takeIf { it >= 0 } ?: k.toIntOrNull() ?: fail("unknown skill '$k'")
                s.requirements[skill] = v.asInt.coerceIn(1, 99)
            }
        }
        if (o.has("weapon")) {
            if (o["weapon"].isJsonNull) s.weapon = null else {
                val w = s.weapon ?: ServerItemStats.Weapon()
                val wo = o["weapon"].asJsonObject
                fun wi(k: String) = wo[k]?.takeIf { !it.isJsonNull }?.asInt
                wo["twoHanded"]?.takeIf { !it.isJsonNull }?.let { w.twoHanded = it.asBoolean }
                wi("attackSpeed")?.let { w.attackSpeed = it.coerceIn(1, 20) }
                wi("category")?.let { w.category = it.coerceIn(0, ServerItems.WEAPON_CATEGORIES.size - 1) }
                wi("range")?.let { w.normalDistance = it }; wi("longRange")?.let { w.longDistance = it }
                wo["animations"]?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) ->
                    val a = v.asInt
                    when (k) { "stand" -> w.stand = a; "walk" -> w.walk = a; "run" -> w.run = a; "standTurn" -> w.standTurn = a
                        "turn90" -> w.turn90 = a; "turn180" -> w.turn180 = a; "turn270" -> w.turn270 = a; "block" -> w.block = a
                        "accurate" -> w.accurate = a; "aggressive" -> w.aggressive = a; "controlled" -> w.controlled = a; "defensive" -> w.defensive = a
                        else -> fail("unknown animation '$k' (${w.animations().keys})") }
                }
                s.weapon = w
            }
        }
        if (o.has("special")) s.special = if (o["special"].isJsonNull) null else o["special"].asString
        if (o.has("specialEnergy")) s.specialEnergy = if (o["specialEnergy"].isJsonNull) null else o["specialEnergy"].asInt.coerceIn(0, 100)
    }

    /**
     * Changes or makes an item: {"id": 4151, ...fields} changes it; {"copyOf": 4151, ...} or {"new": true, ...} makes a new
     * one. "server": {...} sets the server stats. "dryRun": true only checks.
     */
    private fun saveItem(o: JsonObject): Map<String, Any?> {
        val dry = o["dryRun"]?.asBoolean == true
        val srv = server()
        val statsIn = o["server"]?.takeIf { it.isJsonObject }?.asJsonObject
        if (statsIn != null && srv == null) fail("The server folder isn't set in the editor (Tools > NPCs or Shops), so server stats can't be saved.")
        if (statsIn != null) ServerItems.definitionsFile(srv!!).also { if (!it.isFile) fail("${it.path} isn't there.") }
        // everything is worked out and checked with the cache open; it's written after it's closed
        val (base, st, oldEnergy) = withCache { lib, _, _ ->
            val b: ItemFull = when {
                o.has("copyOf") -> (ItemStore.read(lib, o.int("copyOf")) ?: fail("There's no item ${o.int("copyOf")}.")).let { src ->
                    src.copy(ItemStore.nextFreeId(lib)).also { it.noteLinked = -1; it.placeholderLink = -1; it.placeholderTemplate = -1; it.boughtLink = -1; it.boughtTemplate = -1 } }
                o["new"]?.asBoolean == true -> ItemFull(ItemStore.nextFreeId(lib)).apply { name = "New item"; inventoryModel = 2429; zoom2d = 1200; xan2d = 300; inventoryOptions[4] = "Drop"; groundOptions[2] = "Take" }
                else -> (ItemStore.read(lib, o.int("id")) ?: fail("There's no item ${o.int("id")}; use \"new\": true or \"copyOf\" to make one.")).let { it.copy(it.id) }
            }
            if (!b.readOk) fail("Item ${b.id} can't be edited: ${b.problem}.")
            applyItem(b, o)
            for (m in b.modelIds()) if (m > 65535 || lib.index(7).archive(m) == null) fail("model $m isn't in the cache")
            val energy = ServerItems.specialEnergy(lib, b.id)
            // a copy starts from the stats of the item it copies (what it really has in game)
            val from = if (o.has("copyOf") && srv != null && ItemStats.get(srv, b.id) == null) o.int("copyOf") else b.id
            val stats = if (statsIn != null) ServerItems.read(srv!!, from).let { r -> ServerItemStats(b.id).also { x ->
                x.weight = r.weight; x.slot = r.slot; x.equipmentType = r.equipmentType; x.bonuses = r.bonuses.clone()
                x.requirements.putAll(r.requirements); x.weapon = r.copy().weapon } }.also { x ->
                x.special = ServerItems.readSpecials(srv!!)[b.id] ?: ServerItems.specialNames(srv).entries.firstOrNull { b.id in it.value }?.key
                x.specialEnergy = energy
                applyStats(x, statsIn)
                val sp = x.special
                if (sp != null && sp !in ServerItems.specialNames(srv).keys) fail("unknown special '${x.special}' (GET /api/specials lists them)")
            } else null
            Triple(b, stats, energy)
        }
        val enc = base.encode(); val back = ItemFull.decode(base.id, enc)
        if (!back.readOk || !back.encode().contentEquals(enc)) fail("The item didn't read back the same; nothing was written.")
        if (dry) return mapOf("ok" to true, "dryRun" to true, "item" to itemJson(base) + ("server" to st?.let { statsJson(it) }))
        ItemStore.save(listOf(base), "ai-item-${base.id}")
        val notes = ArrayList<String>()
        if (st != null) {
            ServerItems.write(srv!!, st)
            val specials = ServerItems.readSpecials(srv!!)
            val built = ServerItems.specialNames(srv).entries.firstOrNull { base.id in it.value }?.key
            if (st.special == null || st.special == built) specials.remove(base.id) else specials[base.id] = st.special!!
            if (specials != ServerItems.readSpecials(srv)) ServerItems.writeSpecials(srv, specials)
            val energy = if (st.special == null) null else st.specialEnergy
            // same undo step as the item itself (ItemStore.save just made the backup)
            if (energy != oldEnergy) ServerItems.setSpecialEnergy(base.id, energy, backup = false)
            if (st.special == null && built != null) notes.add("the server code gives it the $built special, so it keeps that")
            notes.add("server stats saved in ${ServerItems.definitionsFile(srv).path}")
        }
        changed("saved item ${base.id} (${base.name})")
        return mapOf("ok" to true, "id" to base.id, "name" to base.name, "notes" to notes,
            "next" to "It's in the exported cache (and the server's data files). The user deploys it; POST /api/cache/undo takes the cache change back.")
    }

    private fun makeNoted(id: Int): Map<String, Any?> {
        val (d, nid, template) = withCache { lib, _, _ ->
            val d = ItemStore.read(lib, id) ?: fail("There's no item $id.")
            if (d.isNoted) fail("Item $id is already a noted item.")
            if (d.stackable) fail("Stackable items don't need a noted version.")
            if (d.noteLinked >= 0 && lib.data(2, 10, d.noteLinked) != null) fail("It already has a noted version (item ${d.noteLinked}).")
            Triple(d, ItemStore.nextFreeId(lib), ItemStore.noteTemplate(lib))
        }
        d.noteLinked = nid
        ItemStore.save(listOf(d, ItemStore.notedFor(d, nid, template)), "ai-item-$id-noted")
        changed("noted version $nid of item $id")
        return mapOf("ok" to true, "notedId" to nid)
    }

    private fun toAwt(img: javafx.scene.image.Image): BufferedImage {
        val w = img.width.toInt(); val h = img.height.toInt()
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val r = img.pixelReader
        for (y in 0 until h) for (x in 0 until w) out.setRGB(x, y, r.getArgb(x, y))
        return out
    }

    private fun itemIcon(id: Int, scale: Int): ByteArray = withCache { lib, models, _ ->
        val d = ItemStore.read(lib, id) ?: fail("There's no item $id.")
        val shown = if (d.isNoted) ItemStore.read(lib, d.noteLinked) ?: d else d
        val img = ItemPreview.icon(shown, models, try { TextureLoader(lib) } catch (e: Exception) { null }, scale.coerceIn(1, 8)) ?: fail("Item $id has no inventory model.")
        SoftRender.png(toAwt(img))
    }

    /** mode = inventory | worn | player; anim (+frame or frames=N for a strip) plays an animation on the player. */
    private fun itemRender(q: Map<String, String>): ByteArray = withCache { lib, models, anims ->
        val d = ItemStore.read(lib, q.int("id")) ?: fail("There's no item ${q["id"]}.")
        val female = q["female"] == "1" || q["female"] == "true"
        val mode = q["mode"] ?: if (q["anim"] != null) "player" else "inventory"
        val md = when (mode) {
            "inventory" -> ItemPreview.inventoryModel(d, models)
            "worn" -> ItemPreview.wornModel(d, female, models)
            "player" -> ItemPreview.onPlayer(d, female, lib, models)
            else -> fail("mode is inventory, worn or player")
        } ?: fail("Item ${d.id} has no ${if (mode == "worn") (if (female) "female" else "male") + " worn" else mode} model.")
        renderWithAnim(md, q, anims, try { TextureLoader(lib) } catch (e: Exception) { null })
    }

    private val PLACE_WORDS = listOf("replace", "weapon", "shield", "head", "helm", "body", "platebody", "legs", "hands", "feet", "cape", "neck")

    /**
     * Model [model] worn by a plain player (in place of [item]'s worn model, or in a slot), optionally playing an
     * animation: the same as "Try a model on the player" in the Items tab. Nothing is written.
     */
    private fun modelOnPlayer(q: Map<String, String>): ByteArray = withCache { lib, models, anims ->
        val id = q.int("model")
        if (id !in 0..65535) fail("model ids go from 0 to 65535")
        val md0 = try { models.get(id) } catch (e: Exception) { null } ?: fail("There's no model $id.")
        if (md0.faceCount == 0) fail("Model $id has no faces.")
        val item = q["item"]?.let { s -> s.trim().toIntOrNull()?.let { ItemStore.read(lib, it) } ?: fail("There's no item $s.") }
        val placeArg = (q["place"] ?: "replace").trim().lowercase()
        val pi = placeArg.toIntOrNull() ?: PLACE_WORDS.indexOf(placeArg)
        val place = controllers.ItemOnPlayer.PLACES.getOrNull(pi) ?: fail("place is one of ${PLACE_WORDS.joinToString("|")} (or 0-${controllers.ItemOnPlayer.PLACES.size - 1})")
        val female = q["female"] == "1" || q["female"] == "true"
        val (main, also) = controllers.ItemOnPlayer.dress(item, id, place)
        val anim = q["anim"]?.toIntOrNull()
        val md = if (anim == null) ItemPreview.onPlayer(main, female, lib, models, also)
            else controllers.ItemOnPlayer.build(main, female, anim, lib, models, anims, also).let { (m, clip, msg) ->
                // a clip that can't move the player (skeletal animation, no weights) would just be a still picture
                if (m == null || clip == null || !clip.moves) fail(msg); m }
        md ?: fail("Couldn't build the player model.")
        renderWithAnim(md, q, anims, try { TextureLoader(lib) } catch (e: Exception) { null })
    }

    private fun renderWithAnim(md: ModelDefinition, q: Map<String, String>, anims: AnimCache, tex: TextureLoader?): ByteArray {
        val size = (q["size"]?.toIntOrNull() ?: 512).coerceIn(64, 1600)
        val yaw = q["yaw"]?.toIntOrNull() ?: 0; val pitch = q["pitch"]?.toIntOrNull() ?: 64
        val zoom = q["zoom"]?.toDoubleOrNull() ?: 1.0
        val anim = q["anim"]?.toIntOrNull() ?: return SoftRender.png(SoftRender.render(md, size, size, yaw, pitch, zoom, textures = tex))
        val (clip, note) = controllers.AnimPlayback.clipFor(md, anim, anims)
        if (clip == null) fail(note)
        val count = q["frames"]?.toIntOrNull()?.coerceIn(1, 12)
        val picks = if (count != null) (0 until count).map { it * (clip.frameCount - 1) / maxOf(1, count - 1) }
            else listOf((q["frame"]?.toIntOrNull() ?: 0).coerceIn(0, clip.frameCount - 1))
        val w = if (picks.size > 1) (size * 3 / 4) else size
        val imgs = picks.map { f -> val (x, y, z) = clip.pose(f); SoftRender.render(md, w, size, yaw, pitch, zoom, x.clone(), y.clone(), z.clone(), tex) }
        return SoftRender.png(if (imgs.size == 1) imgs[0] else SoftRender.strip(imgs))
    }

    private fun specials(): Map<String, Any?> {
        val srv = server() ?: fail("The server folder isn't set in the editor.")
        val anims = ServerItems.specialAnimations(srv)
        return mapOf("note" to "Give an item one with POST /api/item {\"id\":..., \"server\": {\"special\": NAME, \"specialEnergy\": 50}}. The behaviour is the server's code for that special.",
            "specials" to ServerItems.specialNames(srv).map { (n, ids) -> linkedMapOf("name" to n, "animation" to anims[n], "weapons" to ids) })
    }

    // ======================= models =======================

    private fun modelList(q: Map<String, String>): Map<String, Any?> = withCache { lib, _, _ ->
        val ids = lib.index(7).archiveIds().sorted()
        val from = q["from"]?.toIntOrNull() ?: 0
        val limit = (q["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 5000)
        mapOf("count" to ids.size, "highest" to ids.lastOrNull(), "ids" to ids.filter { it >= from }.take(limit))
    }

    private fun modelInfo(id: Int): Map<String, Any?> = withCache { _, models, _ ->
        val md = try { models.get(id) } catch (e: Exception) { null } ?: fail("Model $id couldn't be read.")
        val counts = LinkedHashMap<Int, Int>()
        for (i in 0 until md.faceCount) {
            if ((md.faceTextures?.get(i)?.toInt() ?: -1) != -1) continue
            val c = (md.faceColors?.get(i)?.toInt() ?: 0) and 0xFFFF
            if (c != 65535) counts[c] = (counts[c] ?: 0) + 1
        }
        var minY = 0; var maxY = 0; var minX = 0; var maxX = 0; var minZ = 0; var maxZ = 0
        for (v in 0 until md.vertexCount) { minX = minOf(minX, md.vertexPositionsX[v]); maxX = maxOf(maxX, md.vertexPositionsX[v])
            minY = minOf(minY, md.vertexPositionsY[v]); maxY = maxOf(maxY, md.vertexPositionsY[v]); minZ = minOf(minZ, md.vertexPositionsZ[v]); maxZ = maxOf(maxZ, md.vertexPositionsZ[v]) }
        linkedMapOf("id" to id, "vertices" to md.vertexCount, "faces" to md.faceCount,
            "size" to mapOf("width" to maxX - minX, "height" to maxY - minY, "depth" to maxZ - minZ, "note" to "game units, 128 = one tile; y grows downwards"),
            "textured" to (md.faceTextures?.any { it.toInt() != -1 } == true),
            "classicAnimationLabels" to (md.vertexSkins != null), "skeletalWeights" to (md.animayaGroups != null),
            "colours" to counts.entries.sortedByDescending { it.value }.map { (hsl, n) -> mapOf("hsl" to hsl, "rgb" to String.format("#%06x", ModelCodec.palette[hsl]), "faces" to n) })
    }

    private fun modelRender(q: Map<String, String>): ByteArray = withCache { lib, models, anims ->
        val md = try { models.get(q.int("id")) } catch (e: Exception) { null } ?: fail("Model ${q["id"]} couldn't be read.")
        renderWithAnim(md, q, anims, try { TextureLoader(lib) } catch (e: Exception) { null })
    }

    /**
     * {"id": 1234, "recolour": [[fromHsl, toHsl] or [fromHsl, "#rrggbb"]], "scale": [x%, y%, z%], "lift": units,
     *  "saveAs": "new" | "over"} - recolours / resizes a model and saves it.
     */
    private fun modelEdit(o: JsonObject): Map<String, Any?> {
        val id = o.int("id")
        val saveAs = o["saveAs"]?.asString ?: "new"
        if (saveAs != "new" && saveAs != "over") fail("saveAs is new or over")
        val md = withCache { _, models, _ -> try { models.get(id) } catch (e: Exception) { null } } ?: fail("Model $id couldn't be read.")
        val e = ModelDefinition.merge(listOf(md)).also { it.priority = md.priority; it.id = md.id }
        var changedFaces = 0
        o["recolour"]?.asJsonArray?.forEach { p ->
            val pair = p.asJsonArray
            val from = pair[0].asInt
            val to = pair[1].let { if (it.asJsonPrimitive.isNumber) it.asInt else ModelCodec.nearestHsl(Integer.parseInt(it.asString.removePrefix("#"), 16)) }
            for (i in 0 until e.faceCount) if ((e.faceColors!![i].toInt() and 0xFFFF) == from && (e.faceTextures?.get(i)?.toInt() ?: -1) == -1) { e.faceColors!![i] = to.toShort(); changedFaces++ }
        }
        ints(o["scale"])?.let { s ->
            val lift = o["lift"]?.asInt ?: 0
            for (v in 0 until e.vertexCount) {
                e.vertexPositionsX[v] = Math.round(e.vertexPositionsX[v] * s[0] / 100.0).toInt()
                e.vertexPositionsY[v] = Math.round(e.vertexPositionsY[v] * s[1] / 100.0).toInt() - lift
                e.vertexPositionsZ[v] = Math.round(e.vertexPositionsZ[v] * s[2] / 100.0).toInt()
            }
        }
        if (o["dryRun"]?.asBoolean == true) return mapOf("ok" to true, "dryRun" to true, "facesRecoloured" to changedFaces)
        val enc = ModelCodec.encodeOld(e)
        val newId = CacheEdits.putModel(enc.data, if (saveAs == "over") id else null)
        changed("model $id saved as $newId")
        return mapOf("ok" to true, "id" to newId, "facesRecoloured" to changedFaces, "warnings" to enc.warnings)
    }

    // ======================= animations =======================

    private fun animList(q: Map<String, String>): Map<String, Any?> = withCache { _, _, anims ->
        val skeletalOnly = q["skeletal"] == "1"
        val category = q["category"]?.let { c -> AnimCatalog.CATEGORIES.firstOrNull { it.equals(c, true) } ?: fail("category is one of ${AnimCatalog.CATEGORIES}") }
        val text = q["q"] ?: ""
        val from = q["from"]?.toIntOrNull() ?: 0
        val limit = (q["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 3000)
        val out = ArrayList<Map<String, Any?>>()
        for (id in anims.seqIds()) {
            if (id < from) continue
            val s = anims.seq(id) ?: continue
            if (skeletalOnly && s.skeletalModel == -1) continue
            if (!AnimCatalog.matches(id, category, text)) continue
            out.add(mapOf("id" to id, "name" to AnimCatalog.name(id), "category" to AnimCatalog.category(id), "skeletal" to (s.skeletalModel != -1), "length" to if (s.skeletalModel != -1) s.skeletalEnd - s.skeletalStart else s.lengthCycles,
                "frames" to if (s.skeletalModel != -1) null else s.frameCount))
            if (out.size >= limit) break
        }
        mapOf("count" to anims.seqIds().size, "categories" to AnimCatalog.counts(anims.seqIds()), "animations" to out, "note" to "length is in client cycles (20 ms)")
    }

    /** {"id": 1658, "name": "My whip swing"} names an animation; "name": null or "" puts the worked-out name back. */
    private fun animRename(o: JsonObject): Map<String, Any?> {
        val id = o.int("id")
        val n = o["name"]?.takeIf { !it.isJsonNull }?.asString
        AnimCatalog.rename(id, n)
        return mapOf("ok" to true, "id" to id, "name" to AnimCatalog.name(id), "category" to AnimCatalog.category(id))
    }

    /** Works every animation's name out again from the cache in use and the server folder. Takes about half a minute. */
    private fun animRebuildNames(): Map<String, Any?> {
        val counts = withCache { lib, _, _ -> AnimCatalog.rebuild(lib, server()) }
        changed("animation names rebuilt")
        return mapOf("ok" to true, "categories" to counts, "savedIn" to AnimCatalog.userCatalogFile().path)
    }

    private fun animInfo(id: Int): Map<String, Any?> = withCache { _, _, anims ->
        val s = anims.seq(id) ?: fail("There's no animation $id.")
        val npcs = try { NpcDefs.load().values.filter { it.standAnim == id || it.walkAnim == id }.take(20).map { mapOf("id" to it.id, "name" to it.name) } } catch (e: Exception) { emptyList<Any>() }
        val items = server()?.let { srv -> try {
            val f = ServerItems.definitionsFile(srv)
            if (!f.isFile) null else com.google.gson.JsonParser().parse(f.readText()).asJsonArray.mapNotNull { e ->
                val w = e.asJsonObject["equipmentDefinition"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("weaponDefinition")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@mapNotNull null
                val uses = w.entrySet().filter { it.key.endsWith("Animation") && !it.value.isJsonNull && it.value.asInt == id }.map { it.key.removeSuffix("Animation") }
                if (uses.isEmpty()) null else mapOf("item" to e.asJsonObject["id"].asInt, "as" to uses)
            }.take(30)
        } catch (e: Exception) { null } }
        val named = AnimCatalog.entry(id)
        linkedMapOf("id" to id, "name" to named?.name, "category" to (named?.category ?: "Unknown"), "renamed" to AnimCatalog.renamed(id),
            "uses" to (named?.uses ?: emptyList<String>()), "skeletal" to (s.skeletalModel != -1),
            "length" to if (s.skeletalModel != -1) s.skeletalEnd - s.skeletalStart else s.lengthCycles,
            "frames" to s.frameIds.indices.map { mapOf("frame" to "${s.frameIds[it] ushr 16}:${s.frameIds[it] and 0xFFFF}", "delay" to s.delays[it]) },
            "loopFrom" to s.loopOffset.takeIf { it >= 0 }, "maxLoops" to s.maxLoops, "priority" to s.priority,
            "showsInHand" to s.weapon.takeIf { it >= 0 }?.let { if (it >= 512) "item ${it - 512}" else "nothing" },
            "showsOnShieldArm" to s.shield.takeIf { it >= 0 }?.let { if (it >= 512) "item ${it - 512}" else "nothing" },
            "usedByNpcs" to npcs, "usedByItems" to items)
    }

    /** on = npc:ID | model:ID | item:ID (a player holding it); frame or frames=N. */
    private fun animRender(q: Map<String, String>): ByteArray = withCache { lib, models, anims ->
        val on = q["on"] ?: fail("on needed: npc:ID, model:ID or item:ID")
        val target = on.substringAfter(':').toIntOrNull() ?: fail("on needed: npc:ID, model:ID or item:ID")
        val md = when (on.substringBefore(':')) {
            "npc" -> { val n = NpcDefs.load()[target] ?: fail("There's no NPC $target.")
                ModelDefinition.merge(n.models.toList().mapNotNull { try { models.get(it) } catch (e: Exception) { null } }).also { m ->
                    val f = n.recolorFrom; val t = n.recolorTo
                    if (f != null && t != null) for (i in 0 until m.faceCount) for (k in f.indices) if (m.faceColors!![i] == f[k]) m.faceColors!![i] = t[k]
                } }
            "model" -> try { models.get(target) } catch (e: Exception) { null } ?: fail("Model $target couldn't be read.")
            "item" -> ItemPreview.onPlayer(ItemStore.read(lib, target) ?: fail("There's no item $target."), q["female"] == "1", lib, models) ?: fail("Couldn't build the player.")
            else -> fail("on is npc:ID, model:ID or item:ID")
        }
        renderWithAnim(md, q + ("anim" to q.int("id").toString()), anims, try { TextureLoader(lib) } catch (e: Exception) { null })
    }

    /** {"id": 808, "delays": [..] or "allDelays": 4, "frames": ["group:file", ...] (optional)} */
    private fun animTiming(o: JsonObject): Map<String, Any?> {
        val id = o.int("id")
        val s = withCache { _, _, anims -> anims.seq(id) } ?: fail("There's no animation $id.")
        if (s.skeletalModel != -1) fail("Animation $id is skeletal: its timing is in its keyframes.")
        val frames = o["frames"]?.asJsonArray?.map { f -> val p = f.asString.split(':'); (p[0].toInt() shl 16) or p[1].toInt() }?.toIntArray() ?: s.frameIds
        val delays = o["allDelays"]?.asInt?.let { d -> IntArray(frames.size) { d } }
            ?: o["delays"]?.asJsonArray?.map { it.asInt }?.toIntArray() ?: if (frames.size == s.delays.size) s.delays else fail("give delays for the new frame list")
        if (delays.size != frames.size) fail("${frames.size} frames but ${delays.size} delays")
        if (delays.any { it !in 1..65535 }) fail("delays are 1-65535 cycles")
        if (o["dryRun"]?.asBoolean == true) return mapOf("ok" to true, "dryRun" to true)
        CacheEdits.saveSeq(s, frames, delays)
        changed("timing of animation $id")
        return mapOf("ok" to true, "frames" to frames.size, "lengthMs" to delays.sum() * 20)
    }

    private fun npcInfo(id: Int): Map<String, Any?> {
        val n = NpcDefs.load()[id] ?: fail("There's no NPC $id.")
        return linkedMapOf("id" to id, "name" to n.name, "combat" to n.combat, "size" to n.size, "models" to n.models.toList(),
            "standAnim" to n.standAnim, "walkAnim" to n.walkAnim, "options" to n.options.toList())
    }

    private fun JsonArray.getOrNull(i: Int): JsonElement? = if (i < size()) get(i) else null

    companion object {
        val HELP = """
            # Items, models and animations
            All of these read the cache the editor works on (the exported cache once it exists). Changes go into the
            exported cache, with a backup first: POST /api/cache/undo takes back the last one. Server stats (in the server's data
            files) aren't part of that undo: to take them back, send the old values again. Nothing is deployed.

            ## Items
            - GET /api/items?q=whip&limit=50 - search by name or id.
            - GET /api/item?id=4151 - every cache field, plus "server": bonuses, attack speed, weapon animations, requirements, special.
            - POST /api/item {"id": 4151, "name": "...", ...} - change fields (only the ones given). {"copyOf": 4151, ...} or {"new": true, ...}
              makes a new item. Cache fields: name, cost, weight (kg), stackable, members, tradeable, inventoryOptions[5], groundOptions[5],
              inventoryModel, zoom2d, xan2d, yan2d, zan2d, offsetX2d, offsetY2d, resize[3], ambient, contrast, slot, hides[2],
              maleModels[3], femaleModels[3], maleOffset, femaleOffset, maleHeads[2], femaleHeads[2], recolours [[from, to]], params {key: value}.
              "server": {"bonuses": {"Slash att": 82, "Strength": 82} or [14 numbers], "requirements": {"Attack": 70},
              "weapon": {"attackSpeed": 4, "category": 20, "twoHanded": false, "animations": {"stand": 808, "accurate": 1658, ...}} (null = not a weapon),
              "special": "PUNCTURE" or null, "specialEnergy": 25, "slot": 3, "equipmentType": "DEFAULT"}. "dryRun": true only checks.
            - POST /api/item/noted {"id": ...} - makes the noted version.
            - GET /api/item/icon?id=4151&scale=4 - the inventory picture as the game draws it (PNG).
            - GET /api/item/render?id=4151&mode=inventory|worn|player&female=0&yaw=0&pitch=64&size=512 - a 3D picture (PNG).
            - GET /api/model/on-player?model=5409&item=4151&place=replace|weapon|shield|head|helm|body|platebody|legs|hands|feet|cape|neck&female=0&anim=1658&frames=6
              - any model worn by a plain player (in place of the item's worn model, or in a slot), optionally playing an animation (PNG; frames=N for a strip). Writes nothing.
              With &anim=1658 it plays an animation on a player holding it: &frame=N, or &frames=4 for a strip of 4 frames.
            - GET /api/specials - the server's special attacks, their animation and the weapons that already have them.

            ## Models
            - GET /api/models?from=0&limit=200, GET /api/model?id=... (size, colours with face counts, animation support).
            - GET /api/model/render?id=...&yaw=&pitch=&size=&anim=&frame=|frames= (PNG).
            - POST /api/model/edit {"id": ..., "recolour": [[hsl, hsl or "#rrggbb"]], "scale": [x%, y%, z%], "lift": units, "saveAs": "new"|"over"}.

            ## Animations
            - GET /api/anims?from=&limit=&skeletal=1&category=&q= lists animations with a name and a category (Weapons, Player,
              Emotes, Skilling, NPCs, Pets, Objects, Graphics, Unknown). q searches names ("whip", "graardor attack") or ids.
              GET /api/anim?id=... (name, category, everything that uses it, frames and delays, which NPCs and items use it).
            - POST /api/anim/name {"id": 1658, "name": "..."} renames one ("name": null puts the worked-out name back).
            - POST /api/anims/rebuild-names works every name out again from the cache and the server folder (about 30 s).
            - GET /api/anim/render?id=...&on=npc:ID|model:ID|item:ID&frame=N|frames=4 (PNG).
            - POST /api/anim/timing {"id": ..., "delays": [...]} or {"allDelays": 4}; "frames": ["group:file", ...] reorders/copies frames.
              Skeletal (2021+) animations keep their timing in their keyframes and can't be changed this way.
            - GET /api/npc?id=... - an NPC's models and animations.

            Yaw is 0-2047 like the game (0 = facing you, 512 = a quarter turn). One client cycle is 20 ms; a game tick is 30 cycles.
        """.trimIndent()
    }
}

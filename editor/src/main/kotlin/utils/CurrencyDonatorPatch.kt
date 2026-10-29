package utils

import cache.loaders.ModelLoader
import com.displee.cache.CacheLibrary

/**
 * GadScape's currency and donator items, written into a build 241 cache (the server's ids:
 * com.gadscape.game.item.CustomItemId; the server reads names from the cache and examines from
 * server/core/src/main/resources/gadscape/items/currency.json):
 *
 *  - Blood tokens: item 13307 (the old Blood money) renamed, with the Platinum token's look (13204 and its stack
 *    pictures 3985/3987/3989/3991 at 2/3/4/5, on 13307's own stack ids 13308-13311) recoloured blood red: every face
 *    to the hue of 0xb00000, saturation 7, lightness x 0.62. Done with recolour entries on the platinum models, no
 *    new models.
 *  - Blood card (50000, 1,000 tokens, stackable): the Code key card (30316); grey faces (saturation <= 3) red
 *    (saturation 7, lightness x 0.8), the rest gold (hue of 0xd4af37, saturation 6, lightness x 1.4 + 25).
 *  - Donator rank tickets (50001-50006, option Redeem): the Trouver parchment (24187, its own recolours first) with
 *    its faces of hue 28-46 and saturation >= 3 in the rank's yell colour (white: saturation 0, lightness x 1.35 + 15).
 *  - Donator pin ($1, stackable, options Redeem / Combine, 50007) and bonds $10-$1000 (Redeem, 50008-50013): the
 *    Old school bond (13190) with its green gem (hue 17-23) recoloured (white: saturation 0, lightness x 1.5 + 20).
 *
 * Idempotent: every item is built from the stock items it copies (which this never changes) and written only when
 * the cache holds something else, so running it again changes nothing. Close the server, client and editor that use
 * the cache first.
 *
 *     java -cp <editor fat jar> utils.CurrencyDonatorPatch <cache folder>
 */
object CurrencyDonatorPatch {

    private const val CONFIGS = 2
    private const val ITEMS = 10
    private const val MODELS = 7

    const val BLOOD_TOKENS = 13307
    /** 13307's stack pictures at 2, 3, 4 and 5 tokens (its old ones, 13308-13311, re-used). */
    private val TOKEN_STACKS = intArrayOf(13308, 13309, 13310, 13311)
    private val TOKEN_STACK_COUNTS = intArrayOf(2, 3, 4, 5)
    private const val PLATINUM_TOKEN = 13204
    private val PLATINUM_STACKS = intArrayOf(3985, 3987, 3989, 3991)
    private const val CODE_KEY = 30316
    private const val TROUVER_PARCHMENT = 24187
    private const val OLD_SCHOOL_BOND = 13190

    const val BLOOD_CARD = 50000
    const val FIRST_TICKET = 50001
    const val DONATOR_PIN = 50007
    const val FIRST_BOND = 50008

    private const val BLOOD_RED = 0xb00000
    private const val GOLD = 0xd4af37
    private const val WHITE = 0xffffff

    /** The ranks' yell colours (MemberRank), Gad Donator first. */
    private val TICKETS = listOf(
        "Gad Donator ticket" to 0x007e18,
        "Super Gad Donator ticket" to 0xf8e239,
        "Extreme Gad Donator ticket" to WHITE,
        "Legendary Gad Donator ticket" to 0xd80717,
        "Royal Gad Donator ticket" to 0x2188ff,
        "Divine Gad Donator ticket" to 0xb65cff,
    )

    private val BONDS = listOf(
        "Donator bond (\$10)" to 0x2188ff,
        "Donator bond (\$20)" to 0xb65cff,
        "Donator bond (\$50)" to 0xd80717,
        "Donator bond (\$100)" to 0xffd700,
        "Donator bond (\$250)" to WHITE,
        "Donator bond (\$1000)" to 0xff7a00,
    )
    private const val PIN_COLOUR = 0x2fd12f

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 1) {
            println("usage: java -cp <editor jar> utils.CurrencyDonatorPatch <build 241 cache folder>")
            kotlin.system.exitProcess(2)
        }
        val lib = CacheLibrary.create(args[0])
        try {
            println(apply(lib) ?: "Nothing to add: the cache already has the currency and donator items.")
        } finally {
            lib.close()
        }
    }

    /** Writes the items into [lib]. What was done, or null when the cache already had all of it. */
    fun apply(lib: CacheLibrary): String? {
        val rev = ConfigRev.of(lib)
        val models = Models(lib)
        fun item(id: Int): ItemFull = ItemStore.read(lib, id)?.takeIf { it.readOk } ?: error("item $id isn't in the cache (or can't be read)")

        val wanted = ArrayList<ItemFull>()

        // Blood tokens: the platinum token's pictures, blood red
        val red = hsl(BLOOD_RED)
        val toRed = { c: Int -> pack(hue(red), 7, lum(c) * 0.62) }
        val platinum = item(PLATINUM_TOKEN)
        wanted += item(BLOOD_TOKENS).apply {
            name = "Blood tokens"
            stackable = true
            looksLike(platinum, models, toRed)
            val obj = IntArray(10); val co = IntArray(10)
            for (i in TOKEN_STACKS.indices) { obj[i] = TOKEN_STACKS[i]; co[i] = TOKEN_STACK_COUNTS[i] }
            countObj = obj; countCo = co
        }
        for (i in TOKEN_STACKS.indices) {
            val stack = TOKEN_STACKS[i]
            val base = ItemStore.read(lib, stack)?.takeIf { it.readOk } ?: ItemFull(stack)
            wanted += base.apply { looksLike(item(PLATINUM_STACKS[i]), models, toRed) }
        }

        // Blood card: the code key card, red with gold
        val gold = hsl(GOLD)
        wanted += ItemFull(BLOOD_CARD).apply {
            name = "Blood card"
            stackable = true
            tradeable = true
            cost = 1_000_000 // 1,000 tokens of 1,000 coins (the server values it at 1,000 tokens)
            looksLike(item(CODE_KEY), models) { c ->
                if (sat(c) <= 3) pack(hue(red), 7, lum(c) * 0.8) else pack(hue(gold), 6, lum(c) * 1.4 + 25)
            }
        }

        // Donator rank tickets: the Trouver parchment in the rank's colour
        val parchment = item(TROUVER_PARCHMENT)
        for ((i, ticket) in TICKETS.withIndex()) {
            val (name, rgb) = ticket
            wanted += ItemFull(FIRST_TICKET + i).apply {
                this.name = name
                tradeable = true
                inventoryOptions[0] = "Redeem"
                looksLike(parchment, models) { c ->
                    if (hue(c) in 28..46 && sat(c) >= 3) tint(c, rgb, 1.35, 15.0) else null
                }
            }
        }

        // Donator pin and bonds: the Old school bond with its green gem recoloured
        val bond = item(OLD_SCHOOL_BOND)
        val gem = { rgb: Int -> { c: Int -> if (hue(c) in 17..23) tint(c, rgb, 1.5, 20.0) else null } }
        wanted += ItemFull(DONATOR_PIN).apply {
            name = "Donator pin"
            stackable = true
            tradeable = true
            inventoryOptions[0] = "Redeem"
            inventoryOptions[1] = "Combine"
            looksLike(bond, models, gem(PIN_COLOUR))
        }
        for ((i, b) in BONDS.withIndex()) {
            val (name, rgb) = b
            wanted += ItemFull(FIRST_BOND + i).apply {
                this.name = name
                tradeable = true
                inventoryOptions[0] = "Redeem"
                looksLike(bond, models, gem(rgb))
            }
        }

        val written = ArrayList<Int>()
        for (it in wanted) {
            val bytes = ItemStore.checkedBytes(it, rev)
            val current = lib.data(CONFIGS, ITEMS, it.id)
            if (current != null && current.contentEquals(bytes)) continue
            lib.put(CONFIGS, ITEMS, it.id, bytes)
            written.add(it.id)
        }
        if (written.isEmpty()) return null
        lib.index(CONFIGS).update()
        return "Currency and donator items: wrote ${written.size} item(s) (${written.joinToString(", ")})."
    }

    /**
     * Gives this item [source]'s picture: its inventory model, camera and size, with every face colour of the model
     * (after [source]'s own recolours) through [recolour] (null = keep the colour as [source] shows it).
     */
    private fun ItemFull.looksLike(source: ItemFull, models: Models, recolour: (Int) -> Int?) {
        inventoryModel = source.inventoryModel
        zoom2d = source.zoom2d; xan2d = source.xan2d; yan2d = source.yan2d; zan2d = source.zan2d
        offsetX2d = source.offsetX2d; offsetY2d = source.offsetY2d
        resizeX = source.resizeX; resizeY = source.resizeY; resizeZ = source.resizeZ
        ambient = source.ambient; contrast = source.contrast
        val from = ArrayList<Int>(); val to = ArrayList<Int>()
        for (c in models.faceColours(source.inventoryModel)) {
            val shown = source.recolorFrom.indexOfFirst { (it.toInt() and 0xFFFF) == c }
                .let { k -> if (k < 0) c else source.recolorTo[k].toInt() and 0xFFFF }
            val target = recolour(shown) ?: shown
            if (target != c) { from.add(c); to.add(target) }
        }
        require(from.size <= 255) { "model ${source.inventoryModel} has too many colours for one item" }
        recolorFrom = ShortArray(from.size) { from[it].toShort() }
        recolorTo = ShortArray(to.size) { to[it].toShort() }
        retextureFrom = source.retextureFrom.copyOf(); retextureTo = source.retextureTo.copyOf()
    }

    /** Reads models' face colours from one cache. */
    private class Models(private val lib: CacheLibrary) {
        private val loader = ModelLoader(lib)

        /** The face colours of model [id], each once, in order. */
        fun faceColours(id: Int): List<Int> {
            val data = lib.data(MODELS, id and 0xFFFF, 0) ?: error("model $id isn't in the cache")
            val md = loader.decode(data, id)
            return (md.faceColors ?: ShortArray(0)).map { it.toInt() and 0xFFFF }.distinct().sorted()
        }
    }

    /** [c] in the colour [rgb] (its hue and saturation, [c]'s lightness); white: saturation 0, lightness x [mul] + [add]. */
    private fun tint(c: Int, rgb: Int, mul: Double, add: Double): Int {
        if (rgb == WHITE) return pack(0, 0, lum(c) * mul + add)
        val t = hsl(rgb)
        return pack(hue(t), sat(t), lum(c).toDouble())
    }

    private fun hue(c: Int) = c shr 10 and 63
    private fun sat(c: Int) = c shr 7 and 7
    private fun lum(c: Int) = c and 127
    private fun pack(hue: Int, sat: Int, lum: Double): Int = (hue and 63 shl 10) or (sat and 7 shl 7) or Math.round(lum).toInt().coerceIn(0, 127)
    private fun pack(hue: Int, sat: Int, lum: Int): Int = pack(hue, sat, lum.toDouble())

    /** [rgb] as the game's 16-bit colour (6 bits hue, 3 saturation, 7 lightness). */
    private fun hsl(rgb: Int): Int {
        val r = (rgb shr 16 and 255) / 256.0
        val g = (rgb shr 8 and 255) / 256.0
        val b = (rgb and 255) / 256.0
        val min = minOf(r, g, b)
        val max = maxOf(r, g, b)
        var h = 0.0
        var s = 0.0
        val l = (min + max) / 2.0
        if (min != max) {
            s = if (l < 0.5) (max - min) / (max + min) else (max - min) / (2.0 - max - min)
            h = when (max) {
                r -> (g - b) / (max - min)
                g -> 2.0 + (b - r) / (max - min)
                else -> 4.0 + (r - g) / (max - min)
            }
        }
        h /= 6.0
        if (h < 0) h += 1.0
        val hue = (h * 64.0).toInt() and 63
        val sat = (s * 8.0).toInt().coerceIn(0, 7)
        val lum = (l * 128.0).toInt().coerceIn(0, 127)
        return (hue shl 10) or (sat shl 7) or lum
    }
}

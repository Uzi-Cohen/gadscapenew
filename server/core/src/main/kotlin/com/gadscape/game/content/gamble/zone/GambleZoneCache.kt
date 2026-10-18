package com.gadscape.game.content.gamble.zone

import com.gadscape.game.content.ui.CachePack
import mgi.tools.jagcached.ArchiveType
import mgi.tools.jagcached.GroupType
import mgi.tools.jagcached.cache.Cache
import mgi.tools.jagcached.cache.File
import mgi.tools.jagcached.cache.Group
import mgi.utilities.ByteBuffer

/**
 * Roat Pkz's ::gamble area, written into the cache at start-up: the two map squares (region 14137 and the edge
 * of 14138, both empty in GadScape's cache), Roat's 6 own models and the 26 objects Roat redefines.
 *
 * Roat reuses OSRS object ids for its own objects (20327 is their dice machine, 19161 their blackjack table, ...),
 * so here those objects get free ids from [FIRST_OBJECT] and the map was rewritten to use them; every other
 * object in the area is a normal OSRS object that GadScape's cache already has. See resources/gadscape/gamble.
 */
object GambleZoneCache : CachePack {

    override val name = "gamble zone"

    private const val DIR = "/gadscape/gamble"

    /** Roat object id -> GadScape object id (resources/gadscape/gamble/objects/<new id>.dat). */
    val OBJECTS = linkedMapOf(
        925 to 60100, 926 to 60101, 927 to 60102, 2095 to 60103, 2654 to 60104, 5554 to 60105, 9328 to 60106,
        10060 to 60107, 10061 to 60108, 15137 to 60109, 16843 to 60110, 16844 to 60111, 16848 to 60112,
        16850 to 60113, 16851 to 60114, 16852 to 60115, 16853 to 60116, 16855 to 60117, 17238 to 60118,
        19161 to 60119, 20327 to 60120, 20328 to 60121, 24875 to 60122, 26142 to 60123, 32668 to 60124,
        41220 to 60125,
    )

    const val ATM = 60106
    const val EXCHANGE_BOOTH_1 = 60107
    const val EXCHANGE_BOOTH_2 = 60108
    const val VIP_REWARDS = 60109
    const val BLACKJACK_TABLE = 60119
    const val DICE_MACHINE = 60120
    const val MINES_MACHINE = 60121
    const val GOLD_CHEST = 60125

    /** Roat's own models, renumbered (62231 table, 62592 mines, 62593/62594 ATM, 62596 dice, 62741 VIP). */
    private val MODELS = 65000..65005

    private val MAP_SQUARES = listOf("55_57", "55_58")

    override fun pack(cache: Cache) {
        val models = cache.getArchive(ArchiveType.MODELS)
        for (id in MODELS) {
            val data = resource("models/$id.dat")
            val group = models.findGroupByID(id)
            if (group == null) models.addGroup(Group(id, File(ByteBuffer(data)))) else group.findFileByID(0).setData(ByteBuffer(data))
        }

        val objects = cache.getArchive(ArchiveType.CONFIGS).findGroupByID(GroupType.OBJECT)
        for (id in OBJECTS.values) {
            val data = resource("objects/$id.dat")
            val file = objects.findFileByID(id)
            if (file == null) objects.addFile(File(id, ByteBuffer(data))) else file.setData(ByteBuffer(data))
        }

        val maps = cache.getArchive(ArchiveType.MAPS)
        for (square in MAP_SQUARES) {
            for (kind in listOf("m", "l")) {
                val name = kind + square
                val data = resource("map/$name.dat")
                val group = maps.findGroupByName(name)
                if (group == null) maps.addGroup(Group(name, 0, File(ByteBuffer(data)))) else group.findFileByID(0).setData(ByteBuffer(data))
            }
        }
    }

    private fun resource(path: String): ByteArray =
        GambleZoneCache::class.java.getResourceAsStream("$DIR/$path")?.use { it.readBytes() } ?: error("missing $DIR/$path")
}

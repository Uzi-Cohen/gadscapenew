package com.gadscape.game.content.ui

import mgi.tools.jagcached.ArchiveType
import mgi.tools.jagcached.cache.Cache
import mgi.tools.jagcached.cache.File
import mgi.tools.jagcached.cache.Group
import mgi.utilities.ByteBuffer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Client scripts GadScape adds or replaces in the cache.
 *
 * - [CHAT_SCRIPT] (73, chat input): the client's own copy of this script posts "Hey, everyone, I just tried to do
 *   something very silly!" instead of sending anything typed as ::bank, so the command never reached the server.
 *   The cache gets the client's copy with that check taken out. The client only uses its built-in copy while the
 *   cache script matches it, so from now on it runs this one, and players don't need a new client.
 * - [SET_GRAPHIC] (args: sprite, component): changes a sprite component's graphic. Hover effects use it, and the
 *   server runs it to change sprites (cards, mine tiles, buttons), since the game protocol has no packet for that.
 * - [SET_COLOUR] (args: colour, component): changes a text's colour, for hover effects.
 */
object ClientScriptsCache : CachePack {

    override val name = "client scripts"

    const val CHAT_SCRIPT = 73
    const val SET_GRAPHIC = 9500
    const val SET_COLOUR = 9501

    private const val ILOAD = 33
    private const val RETURN = 21
    private const val IF_SETGRAPHIC = 2105
    private const val IF_SETCOLOUR = 2101

    override fun pack(cache: Cache) {
        val chat = ClientScriptsCache::class.java.getResourceAsStream("/gadscape/cs2/73.cs2")?.use { it.readBytes() }
            ?: error("missing /gadscape/cs2/73.cs2")
        put(cache, CHAT_SCRIPT, chat)
        put(cache, SET_GRAPHIC, componentSetter(IF_SETGRAPHIC))
        put(cache, SET_COLOUR, componentSetter(IF_SETCOLOUR))
    }

    private fun put(cache: Cache, id: Int, bytes: ByteArray) {
        val archive = cache.getArchive(ArchiveType.CLIENTSCRIPTS)
        val group = archive.findGroupByID(id)
        if (group == null) {
            archive.addGroup(Group(id, File(ByteBuffer(bytes))))
            return
        }
        val file = group.findFileByID(0)
        // setData only marks the cache changed when the bytes differ
        if (file == null) group.addFile(File(0, ByteBuffer(bytes))) else file.setData(ByteBuffer(bytes))
    }

    /** iload 0 (value), iload 1 (component), [opcode], return: for if_setgraphic and if_setcolour. */
    private fun componentSetter(opcode: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeByte(0) // no name
        out.writeShort(ILOAD); out.writeInt(0)
        out.writeShort(ILOAD); out.writeInt(1)
        out.writeShort(opcode); out.writeByte(0)
        out.writeShort(RETURN); out.writeByte(0)
        out.writeInt(4) // instructions
        out.writeShort(2) // local ints
        out.writeShort(0) // local strings
        out.writeShort(2) // int arguments
        out.writeShort(0) // string arguments
        out.writeByte(0) // no switch tables
        out.writeShort(1) // switch table section length
        return bytes.toByteArray()
    }
}

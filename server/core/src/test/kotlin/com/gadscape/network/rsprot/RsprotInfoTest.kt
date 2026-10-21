package com.gadscape.network.rsprot

import com.zenyte.game.world.entity.player.Player
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.rsprot.compression.HuffmanCodec
import net.rsprot.compression.provider.DefaultHuffmanCodecProvider
import net.rsprot.compression.provider.HuffmanCodecProvider
import net.rsprot.crypto.rsa.RsaKeyPair
import net.rsprot.crypto.xtea.XteaKey
import net.rsprot.protocol.api.AbstractNetworkServiceFactory
import net.rsprot.protocol.api.GameConnectionHandler
import net.rsprot.protocol.api.NetworkService
import net.rsprot.protocol.api.js5.Js5GroupProvider
import net.rsprot.protocol.api.login.GameLoginResponseHandler
import net.rsprot.protocol.common.client.OldSchoolClientType
import net.rsprot.protocol.game.outgoing.info.Infos
import net.rsprot.protocol.game.outgoing.info.npcinfo.NpcInfo
import net.rsprot.protocol.game.outgoing.info.npcinfo.NpcInfoPacket
import net.rsprot.protocol.game.outgoing.info.npcinfo.SetNpcUpdateOrigin
import net.rsprot.protocol.game.outgoing.info.playerinfo.PlayerInfoPacket
import net.rsprot.protocol.game.outgoing.info.worldentityinfo.WorldEntityInfoV7Packet
import net.rsprot.protocol.game.outgoing.map.RebuildLoginV2
import net.rsprot.protocol.game.outgoing.worldentity.SetActiveWorldV2
import net.rsprot.protocol.loginprot.incoming.util.AuthenticationType
import net.rsprot.protocol.loginprot.incoming.util.LoginBlock
import net.rsprot.protocol.message.OutgoingGameMessage
import net.rsprot.protocol.message.codec.incoming.GameMessageConsumerRepositoryBuilder
import net.rsprot.protocol.message.codec.incoming.provider.GameMessageConsumerRepositoryProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the rev 241 info path offline: a real rsprot [NetworkService] is built (no port is bound),
 * fake players and an NPC are allocated, the Zenyte-mask mapping of [RsprotExtendedInfo] is applied,
 * `infoProtocols.update()` runs and the packets are checked, in the cycle order [RsprotInfo] uses.
 * The player info packet of the first cycles is decoded with rsprot's own test client
 * ([PlayerInfoTestClient], MIT) to prove the GPI init, movement and appearance block decode cleanly.
 *
 * huffman.dat (test resource) is the OSRS huffman table from rsprot's test resources (MIT).
 */
class RsprotInfoTest {
    private val allocated = mutableListOf<Infos>()

    @AfterEach
    fun releaseInfos() {
        for (infos in allocated) {
            service.infoProtocols.dealloc(infos)
        }
        allocated.clear()
    }

    @Test
    fun `tick is a no-op until the network service exists`() {
        // On the INFO branch alone the stub Rsprot never sets the service; the world cycle must keep running.
        RsprotInfo.tick()
    }

    @Test
    fun `gpi init and appearance decode on the client side`() {
        val local = alloc(LOCAL, 0, 3222, 3218)
        val ext = local.playerInfo.avatar.extendedInfo
        RsprotExtendedInfo.applyAppearance(ext, appearance(name = "Gad", helmet = 1155, chest = 1117, weapon = 1277))

        val client = PlayerInfoTestClient()
        gpiInit(local, LOCAL, client)
        val localPlayer = assertNotNull(client.cachedPlayers[LOCAL])
        assertEquals(3222, localPlayer.coord.x)
        assertEquals(3218, localPlayer.coord.z)

        service.infoProtocols.update()
        decodePlayerInfo(local, client)

        assertEquals("Gad", localPlayer.name)
        assertEquals(3, localPlayer.combatLevel)
        val equipment = localPlayer.equipment
        // Worn objs are 0x800 + id in 241; ident kits 0x100 + kit; hidden wearpos are 0.
        assertEquals(0x800 + 1155, equipment[0], "helmet")
        assertEquals(0x800 + 1277, equipment[3], "weapon")
        assertEquals(0x800 + 1117, equipment[4], "chest")
        assertEquals(0, equipment[6], "arms hidden by a full body")
        assertEquals(0x100 + 36, equipment[7], "legs kit")
        assertEquals(0, equipment[8], "hair hidden by a full helm")
        assertEquals(0x100 + 10, equipment[11], "beard kit")
        assertEquals(0x100 + 0, localPlayer.identKit[8], "hair kit is still sent in the ident kit list")
        assertEquals(listOf(808, 823, 819, 820, 821, 822, 824), localPlayer.baseAnimationSet())
    }

    @Test
    fun `two players and an npc over three cycles`() {
        val first = alloc(LOCAL, 0, 3222, 3218)
        val second = alloc(OTHER, 0, 3224, 3218)
        RsprotExtendedInfo.applyAppearance(first.playerInfo.avatar.extendedInfo, appearance(name = "First"))
        RsprotExtendedInfo.applyAppearance(second.playerInfo.avatar.extendedInfo, appearance(name = "Second"))
        val client = PlayerInfoTestClient()
        gpiInit(first, LOCAL, client)
        gpiInit(second, OTHER, PlayerInfoTestClient())

        val npc = service.npcAvatarFactory.alloc(NPC_INDEX, 3106, 0, 3223, 3221, 0, 6)
        try {
            // Cycle 1: the other player and the npc come into view.
            service.infoProtocols.update()
            assertTrue(OTHER in first.playerInfo.getHighResolutionIndices())
            assertTrue(NPC_INDEX in first.npcInfo.getHighResolutionIndices(NpcInfo.ROOT_WORLD))
            val cycle1 = collect(first)
            assertEquals(
                listOf(
                    SetActiveWorldV2::class,
                    SetNpcUpdateOrigin::class,
                    WorldEntityInfoV7Packet::class,
                    PlayerInfoPacket::class,
                    NpcInfoPacket::class,
                    SetActiveWorldV2::class,
                ),
                cycle1.mapIndexed { i, message ->
                    // NPC info arrives as NpcInfoSmallV6 / NpcInfoLargeV6, both NpcInfoPacket.
                    if (i == 4 && message is NpcInfoPacket) NpcInfoPacket::class else message::class
                },
            )
            // The second player's appearance reaches the first player's client.
            client.decode((cycle1[3] as PlayerInfoPacket).content().copy())
            assertEquals("Second", client.cachedPlayers[OTHER]?.name)
            release(cycle1)
            releaseCycle(second)

            // Cycle 2: every mask the old PlayerInfo/NPCInfo wrote, mapped through RsprotExtendedInfo.
            first.updateRootCoord(0, 3223, 3218) // walk one tile
            val ext = second.playerInfo.avatar.extendedInfo
            RsprotExtendedInfo.applyMoveSpeed(ext, 2)
            RsprotExtendedInfo.applyTempMoveSpeed(ext, 127)
            RsprotExtendedInfo.applySequence(ext, 422, 0)
            RsprotExtendedInfo.applySpotAnim(ext, 86, 0, 92)
            RsprotExtendedInfo.applySay(ext, "Say something long enough to be cut: " + "x".repeat(100))
            RsprotExtendedInfo.applyChat(ext, 15, 2, 1, false, "hello")
            RsprotExtendedInfo.addHit(ext, RsprotExtendedInfo.sourceIndex(true, false, LOCAL), 16, 17, 12, 0)
            RsprotExtendedInfo.addHeadBar(ext, 0, 30, 20, 0, 10)
            RsprotExtendedInfo.addHeadBar(ext, 1, 0, 0, 0, 0x7FFF)
            RsprotExtendedInfo.applyFaceEntity(ext, NPC_INDEX, 0)
            RsprotExtendedInfo.applyExactMove(ext, 2, 0, 30, 0, 0, 60, 0x600)
            RsprotExtendedInfo.applyTinting(ext, 0, 50, 0, 6, 28, 112)

            val npcExt = npc.extendedInfo
            RsprotInfo.moveNpcAvatar(npc, 0, 3224, 3222, teleported = false, crawl = false)
            RsprotExtendedInfo.applySequence(npcExt, 4935, 0)
            RsprotExtendedInfo.applySpotAnim(npcExt, 1, 0, 0)
            RsprotExtendedInfo.applySay(npcExt, "Mooo")
            RsprotExtendedInfo.addHit(npcExt, RsprotExtendedInfo.sourceIndex(true, false, LOCAL), 16, 17, 3, 0)
            RsprotExtendedInfo.addHeadBar(npcExt, 0, 30, 25, 0, 10)
            npc.setId(3107)
            npcExt.setTransmogrification(3107)
            RsprotExtendedInfo.applyCombatLevel(npcExt, 99)
            npcExt.setNameChange("Bessie")
            RsprotExtendedInfo.applyVisibleOps(npcExt, 0b10101)
            RsprotExtendedInfo.applyHeadIcon(npcExt, 440, 2)
            RsprotExtendedInfo.applyBodyModels(npcExt, intArrayOf(23889, 23890))
            RsprotExtendedInfo.applyFaceCoord(npcExt, 3222, 3218)
            RsprotExtendedInfo.applyTinting(npcExt, 0, 50, 0, 6, 28, 112)
            RsprotExtendedInfo.applyExactMove(npcExt, 1, 1, 30, 0, 0, 0, 0)

            service.infoProtocols.update()
            val cycle2 = collect(first)
            assertEquals(6, cycle2.size, "an npc in view keeps its npc info packet")
            assertTrue((cycle2[3] as PlayerInfoPacket).content().readableBytes() > 20)
            assertTrue((cycle2[4] as NpcInfoPacket).content().readableBytes() > 20)
            assertEquals(3224, npc.x())
            assertEquals(3222, npc.z())
            release(cycle2)
            releaseCycle(second)

            // Cycle 3: the npc jumps away (teleport) and leaves the first player's view.
            RsprotInfo.moveNpcAvatar(npc, 0, 3300, 3300, teleported = true, crawl = false)
            service.infoProtocols.update()
            val cycle3 = collect(first)
            assertFalse(NPC_INDEX in first.npcInfo.getHighResolutionIndices(NpcInfo.ROOT_WORLD))
            release(cycle3)
            releaseCycle(second)
        } finally {
            service.npcAvatarFactory.release(npc)
        }
    }

    @Test
    fun `npc steps follow the tile delta`() {
        val npc = service.npcAvatarFactory.alloc(NPC_INDEX + 1, 3106, 0, 3200, 3200, 0, 0)
        try {
            RsprotInfo.moveNpcAvatar(npc, 0, 3201, 3200, teleported = false, crawl = false)
            assertEquals(3201, npc.x())
            RsprotInfo.moveNpcAvatar(npc, 0, 3203, 3201, teleported = false, crawl = true)
            assertEquals(3203, npc.x())
            assertEquals(3201, npc.z())
            RsprotInfo.moveNpcAvatar(npc, 1, 3203, 3201, teleported = false, crawl = false)
            assertEquals(1, npc.level())
            RsprotInfo.moveNpcAvatar(npc, 1, 3210, 3210, teleported = false, crawl = false)
            assertEquals(3210, npc.x())
            assertEquals(3210, npc.z())
            // Out-of-map coordinates are ignored instead of throwing.
            RsprotInfo.moveNpcAvatar(npc, 1, 20000, 3210, teleported = true, crawl = false)
            assertEquals(3210, npc.x())
        } finally {
            service.npcAvatarFactory.release(npc)
        }
    }

    @Test
    fun `out of range values are clamped instead of throwing`() {
        val infos = alloc(LOCAL, 0, 3222, 3218)
        val ext = infos.playerInfo.avatar.extendedInfo
        val data =
            RsprotExtendedInfo.AppearanceData(
                bodyType = 1,
                pronoun = 1,
                skullIcon = 300,
                overheadIcon = -5,
                transmogId = 70_000,
                wornObjs = IntArray(12) { 70_000 },
                wornHide2 = IntArray(12) { 99 },
                wornHide3 = IntArray(12) { -7 },
                identKits = IntArray(7) { 5000 },
                colours = intArrayOf(-1, 300, 2, 3, 4),
                baseAnimationSet = IntArray(7) { 100_000 },
                name = "Clamp",
                combatLevel = 400,
                skillLevel = -1,
                hidden = false,
                beforeName = "b".repeat(300),
                afterName = "",
                afterCombatLevel = "",
            )
        RsprotExtendedInfo.applyAppearance(ext, data)
        RsprotExtendedInfo.addHit(ext, -1, 99_999, -99, 1_000_000, -3)
        RsprotExtendedInfo.addHeadBar(ext, 99_999, 999, -1, -1, 99_999)
        RsprotExtendedInfo.applyExactMove(ext, 500, -500, -1, 0, 0, 70_000, 5000)
        RsprotExtendedInfo.applyTinting(ext, 70_000, 10, -1, 999, 1, 2)
        RsprotExtendedInfo.applySequence(ext, 70_000, -1)
        RsprotExtendedInfo.applyFaceEntity(ext, 32768 + 5000, 0)
        RsprotExtendedInfo.applyTempMoveSpeed(ext, 9)
        gpiInit(infos, LOCAL, PlayerInfoTestClient())
        service.infoProtocols.update()
        val packets = collect(infos)
        assertTrue(packets.any { it is PlayerInfoPacket })
        release(packets)
    }

    // ------------------------------------------------------------------ helpers

    private fun alloc(index: Int, level: Int, x: Int, z: Int): Infos {
        val infos = service.infoProtocols.alloc(index, OldSchoolClientType.DESKTOP)
        allocated += infos
        infos.updateRootCoord(level, x, z)
        infos.updateRootBuildAreaCenteredOnPlayer(x, z)
        return infos
    }

    /** What the login does: RebuildLoginV2 carries the GPI init block. */
    private fun gpiInit(infos: Infos, index: Int, client: PlayerInfoTestClient) {
        val rebuild = RebuildLoginV2(3222 shr 3, 3218 shr 3, -1, infos.playerInfo)
        try {
            client.gpiInit(index, rebuild.gpiInitBlock.copy())
        } finally {
            rebuild.safeRelease()
        }
    }

    private fun decodePlayerInfo(infos: Infos, client: PlayerInfoTestClient) {
        val packets = collect(infos)
        val playerInfo = packets.filterIsInstance<PlayerInfoPacket>().single()
        val content = playerInfo.content().copy()
        client.decode(content)
        release(packets)
    }

    private fun collect(infos: Infos): List<OutgoingGameMessage> {
        val packets = infos.getPackets()
        val root = packets.rootWorldInfoPackets
        assertTrue(root.playerInfo.isSuccess, "player info: ${root.playerInfo.exceptionOrNull()?.cause}")
        assertTrue(root.npcInfo.isSuccess, "npc info: ${root.npcInfo.exceptionOrNull()?.cause}")
        assertTrue(root.worldEntityInfo.isSuccess, "world entity info: ${root.worldEntityInfo.exceptionOrNull()?.cause}")
        val out = mutableListOf<OutgoingGameMessage>()
        RsprotInfo.sendInfoPackets(packets) { out += it }
        return out
    }

    private fun releaseCycle(infos: Infos) {
        RsprotInfo.releaseAll(infos.getPackets())
    }

    private fun release(messages: List<OutgoingGameMessage>) {
        for (message in messages) {
            message.safeRelease()
        }
    }

    private fun appearance(
        name: String,
        helmet: Int = -1,
        chest: Int = -1,
        weapon: Int = -1,
    ): RsprotExtendedInfo.AppearanceData {
        val worn = IntArray(12) { -1 }
        val hide2 = IntArray(12) { -1 }
        worn[0] = helmet
        worn[3] = weapon
        worn[4] = chest
        if (helmet != -1) hide2[0] = 8 // a full helm hides the hair
        if (chest != -1) hide2[4] = 6 // a full body hides the arms
        return RsprotExtendedInfo.AppearanceData(
            bodyType = 0,
            pronoun = 0,
            skullIcon = -1,
            overheadIcon = -1,
            transmogId = -1,
            wornObjs = worn,
            wornHide2 = hide2,
            wornHide3 = IntArray(12) { -1 },
            // Zenyte's default male look: hair, beard, torso, arms, hands, legs, feet
            identKits = intArrayOf(0, 10, 18, 26, 33, 36, 42),
            colours = intArrayOf(0, 3, 2, 0, 0),
            baseAnimationSet = intArrayOf(808, 823, 819, 820, 821, 822, 824),
            name = name,
            combatLevel = 3,
            skillLevel = 0,
            hidden = false,
            beforeName = "",
            afterName = "",
            afterCombatLevel = "",
        )
    }

    private fun PlayerInfoTestClient.Companion.Player.baseAnimationSet(): List<Int> =
        listOf(readyAnim, turnAnim, walkAnim, walkAnimBack, walkAnimLeft, walkAnimRight, runAnim)

    companion object {
        private const val LOCAL = 1
        private const val OTHER = 2
        private const val NPC_INDEX = 5

        lateinit var service: NetworkService<Player>

        @JvmStatic
        @BeforeAll
        fun buildService() {
            // Building the service does not bind any port; only start() would.
            service = TestNetworkServiceFactory().build()
        }
    }

    private class TestNetworkServiceFactory : AbstractNetworkServiceFactory<Player>() {
        override val ports: List<Int> = listOf(0)
        override val supportedClientTypes: List<OldSchoolClientType> = listOf(OldSchoolClientType.DESKTOP)

        override fun getRsaKeyPair(): RsaKeyPair {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(1024)
            val key = generator.generateKeyPair().private as RSAPrivateKey
            return RsaKeyPair(key.privateExponent, key.modulus)
        }

        override fun getHuffmanCodecProvider(): HuffmanCodecProvider {
            val bytes = checkNotNull(RsprotInfoTest::class.java.getResourceAsStream("huffman.dat")).readBytes()
            return DefaultHuffmanCodecProvider(HuffmanCodec.create(Unpooled.wrappedBuffer(bytes)))
        }

        override fun getJs5GroupProvider(): Js5GroupProvider = Js5GroupProvider { _: Int, _: Int -> null as ByteBuf? }

        override fun getGameMessageConsumerRepositoryProvider(): GameMessageConsumerRepositoryProvider<Player> =
            GameMessageConsumerRepositoryProvider { GameMessageConsumerRepositoryBuilder<Player>().build() }

        override fun getGameConnectionHandler(): GameConnectionHandler<Player> =
            object : GameConnectionHandler<Player> {
                override fun onLogin(
                    responseHandler: GameLoginResponseHandler<Player>,
                    block: LoginBlock<AuthenticationType>,
                ) = Unit

                override fun onReconnect(
                    responseHandler: GameLoginResponseHandler<Player>,
                    block: LoginBlock<XteaKey>,
                ) = Unit
            }
    }
}

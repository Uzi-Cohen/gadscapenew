package com.gadscape.network.rsprot

import com.gadscape.game.world.entity.player.pvmArenaAppearanceBeardOffset
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.HitBar
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.ForceMovement
import com.zenyte.game.world.entity.masks.RenderAnimation
import com.zenyte.game.world.entity.masks.RenderType
import com.zenyte.game.world.entity.masks.UpdateFlag
import com.zenyte.game.world.entity.masks.UpdateFlags
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.PlayerSkulls
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentType
import io.netty.buffer.ByteBuf
import mgi.types.config.items.ItemDefinitions
import mgi.types.config.npcs.NPCDefinitions
import net.rsprot.buffer.bitbuffer.toBitBuf
import net.rsprot.protocol.api.NetworkService
import net.rsprot.protocol.common.client.OldSchoolClientType
import net.rsprot.protocol.game.outgoing.info.InfoPackets
import net.rsprot.protocol.game.outgoing.info.Infos
import com.gadscape.game.world.entity.npc.MultiHeadIconNpc
import com.gadscape.game.world.entity.npc.NpcHeadIcons
import net.rsprot.protocol.game.outgoing.info.npcinfo.NpcAvatar
import net.rsprot.protocol.game.outgoing.info.npcinfo.NpcAvatarExtendedInfo
import net.rsprot.protocol.game.outgoing.info.npcinfo.NpcAvatarFactory
import net.rsprot.protocol.game.outgoing.info.npcinfo.NpcInfo
import net.rsprot.protocol.game.outgoing.info.npcinfo.NpcInfoPacket
import net.rsprot.protocol.game.outgoing.info.playerinfo.PlayerAvatarExtendedInfo
import net.rsprot.protocol.game.outgoing.info.playerinfo.PlayerInfo
import net.rsprot.protocol.game.outgoing.info.util.PacketResult
import net.rsprot.protocol.game.outgoing.info.util.isEmpty
import net.rsprot.protocol.game.outgoing.map.RebuildLoginV2
import net.rsprot.protocol.game.outgoing.misc.client.ServerTickEnd
import net.rsprot.protocol.message.OutgoingGameMessage
import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Player info, NPC info and world entity info for revision 241, built by rsprot
 * (MIT, https://github.com/blurite/rsprot) instead of the old hand-written PlayerInfo/NPCInfo.
 *
 * The cycle follows rsprot's README and the way OpenRune-Server drives it
 * (ISC, https://github.com/OpenRune/OpenRune-Server, api/net/.../rsprot/RspCycle.kt and NetworkScript.kt):
 * 1. during the cycle the game changes entities and flags Zenyte's [UpdateFlags] as before;
 * 2. [tick] runs once per world cycle after every player's `processEntityUpdate` (so map rebuilds,
 *    var and zone packets are already queued), copies coords, build area and the flagged masks into the
 *    rsprot avatars, runs `infoProtocols.update()` and then hands every player its info packets
 *    in the order the 241 client expects (set active world, npc update origin, world entity info,
 *    player info, npc info, set active world);
 * 3. WorldThread resets Zenyte's masks afterwards, as before.
 *
 * Contract with the NET track:
 * - NET calls [login] when a player enters the world (after it has an index and coords),
 *   then sends [rebuildLogin] (or calls [writeAbsolutePlayerPositions] into its own login rebuild),
 *   and calls [logout] when the player leaves the world.
 * - NET installs [sink] so the info packets reach the player's rsprot session. Until it is set,
 *   packets are released and dropped (with a warning), so nothing leaks.
 */
object RsprotInfo {

    private val log = LoggerFactory.getLogger(RsprotInfo::class.java)

    /**
     * Where the info packets go. Return true if the message was queued (the sink then owns it),
     * false to let RsprotInfo release it.
     */
    fun interface Sink {
        fun send(player: Player, message: OutgoingGameMessage): Boolean
    }

    @JvmStatic
    @Volatile
    var sink: Sink? = null

    private const val PLAYER_CAPACITY = 2048
    private const val NPC_CAPACITY = 65535

    /** Zenyte's default NPC view distance; rsprot's default render distance is the same 15 tiles. */
    private const val DEFAULT_VIEW_DISTANCE = 15
    private const val DEFAULT_ZONE_SEARCH_RADIUS = 3

    private const val HELMET = 0
    private const val CHEST = 4
    private const val ARMS = 6
    private const val LEGS = 7
    private const val HAIR = 8
    private const val FEET = 10
    private const val JAW = 11

    /** Wearpos that can carry a worn obj (rings and ammo are not rendered). */
    private val VISIBLE_WEARPOS = intArrayOf(0, 1, 2, 3, 4, 5, 7, 9, 10)

    private class PlayerState(
        val player: Player,
        val infos: Infos,
    ) {
        /** True once the GPI init block (RebuildLogin) has been written for the current info state. */
        var gpiInitialised = false

        /** True if packets were computed (and dropped) before the GPI init: the state must be reset first. */
        var computedBeforeInit = false
        var buildAreaKey = Int.MIN_VALUE
        var moveSpeed = Int.MIN_VALUE
        var faceEntity = -1
        var direction = Int.MIN_VALUE
        var viewDistance = Int.MIN_VALUE
        var heatmap = false
        var hidden = false
    }

    private class NpcState(
        val npc: NPC,
        val avatar: NpcAvatar,
    ) {
        var seen = 0L
        var id = -1
        var inaccessible = false
        var combatLevel = -1
        var name: String? = null
        var ops = 31
        var overheadGroup = -1
        var overheadIndex = -1
        /** Head icon slots 0..7 as last sent, for NPCs with [MultiHeadIconNpc] (E13); null until first used. */
        var headIconGroups: IntArray? = null
        var headIconIndices: IntArray? = null
        var headIconVersion = -1
        var faceEntity = -1
        var direction = -1
    }

    private val players = arrayOfNulls<PlayerState>(PLAYER_CAPACITY)
    private val npcs = arrayOfNulls<NpcState>(NPC_CAPACITY)
    private var cycle = 0L
    private var lastSinkWarning = 0L

    // ================================================================== contract

    /**
     * Allocates the player's player info, NPC info and world entity info (rsprot allocates all three
     * together; world entity info stays empty as GadScape has no world entities).
     */
    @JvmStatic
    fun login(player: Player) {
        val index = player.index
        require(index in 1 until PLAYER_CAPACITY) { "Player index out of range for rsprot: $index" }
        players[index]?.let { release(index, it) }
        val infos = Rsprot.service.infoProtocols.alloc(index, OldSchoolClientType.DESKTOP)
        val state = PlayerState(player, infos)
        players[index] = state
        infos.updateRootCoord(player.plane, player.x, player.y)
        syncBuildArea(state)
        syncViewDistance(state)
        // Persistent blocks that new observers receive: appearance, move speed and facing.
        val ext = infos.playerInfo.avatar.extendedInfo
        RsprotExtendedInfo.applyAppearance(ext, buildAppearance(player))
        state.moveSpeed = player.lastMovementType
        RsprotExtendedInfo.applyMoveSpeed(ext, state.moveSpeed)
        syncFace(state, ext, player.updateFlags)
    }

    /** Releases the player's infos. Safe to call twice or for a player that never logged in. */
    @JvmStatic
    fun logout(player: Player) {
        val index = player.index
        if (index !in 1 until PLAYER_CAPACITY) return
        val state = players[index] ?: return
        if (state.player !== player) return
        release(index, state)
    }

    /** Builds and sends this cycle's info packets for every logged-in player. Called once per world cycle. */
    @JvmStatic
    fun tick() {
        val service = serviceOrNull() ?: return
        cycle++
        syncNpcs(service.npcAvatarFactory)
        for (index in 1 until PLAYER_CAPACITY) {
            val state = players[index] ?: continue
            try {
                if (!state.gpiInitialised && index in state.infos.playerInfo.getHighResolutionIndices()) {
                    // The GPI init was written straight from playerInfo (e.g. NET built RebuildLoginV2 itself).
                    state.gpiInitialised = true
                }
                syncPlayer(state)
            } catch (e: Exception) {
                log.error("Failed to update rsprot avatar for {}", state.player, e)
            }
        }
        service.infoProtocols.update()
        for (index in 1 until PLAYER_CAPACITY) {
            val state = players[index] ?: continue
            try {
                flush(state)
            } catch (e: Exception) {
                log.error("Failed to send info packets to {}", state.player, e)
            }
        }
    }

    // ================================================================== helpers for NET

    /** The rsprot infos of a logged-in player, or null. */
    @JvmStatic
    fun infos(player: Player): Infos? = players.getOrNull(player.index)?.takeIf { it.player === player }?.infos

    /** The rsprot player info of a logged-in player, or null (RebuildLoginV2 needs it). */
    @JvmStatic
    fun playerInfo(player: Player): PlayerInfo? = infos(player)?.playerInfo

    /** The NPCs the player's client currently has in view (replaces the old NPCInfo.getLocalNPCs()). */
    @JvmStatic
    fun localNpcs(player: Player): List<NPC> {
        val npcInfo = infos(player)?.npcInfo ?: return emptyList()
        val indices = npcInfo.getHighResolutionIndicesOrNull(NpcInfo.ROOT_WORLD) ?: return emptyList()
        val world = World.getNPCs()
        return indices.mapNotNull { world.get(it) }
    }

    /**
     * The REBUILD_LOGIN message for a player that has just been passed to [login].
     * It carries the GPI init block, so it must be the first rebuild the client receives,
     * and it must be created before the next [tick].
     */
    @JvmStatic
    fun rebuildLogin(player: Player): RebuildLoginV2 {
        val state = checkNotNull(stateOf(player)) { "RsprotInfo.login was not called for $player" }
        prepareGpiInit(state)
        // worldArea: OpenRune-Server sends -1 here; GadScape has no world areas.
        val message = RebuildLoginV2(player.x shr 3, player.y shr 3, -1, state.infos.playerInfo)
        state.gpiInitialised = true
        return message
    }

    /**
     * Writes the GPI init block (own coord in 30 bits, then 18 bits for every other index) into [buffer].
     * Used by a login rebuild that is encoded by hand. Without rsprot infos for the player (no
     * service yet) the same layout is written from the world state, so the old login keeps working.
     */
    @JvmStatic
    fun writeAbsolutePlayerPositions(player: Player, buffer: ByteBuf) {
        val state = stateOf(player)
        if (state != null) {
            prepareGpiInit(state)
            state.infos.playerInfo.handleAbsolutePlayerPositions(buffer)
            state.gpiInitialised = true
            return
        }
        buffer.toBitBuf().use { bits ->
            bits.pBits(30, player.location.positionHash)
            val all = World.getPlayers()
            for (index in 1 until PLAYER_CAPACITY) {
                if (index == player.index) continue
                bits.pBits(18, all.getDirect(index)?.location18BitHash ?: 0)
            }
        }
    }

    /**
     * Resets the info state of a player for a reconnect (LoginResponse.ReconnectOk requires it).
     * After this, a new GPI init block must be written ([rebuildLogin] / [writeAbsolutePlayerPositions]).
     */
    @JvmStatic
    fun onReconnect(player: Player) {
        val state = stateOf(player) ?: return
        resetInfoState(state)
        state.gpiInitialised = false
        state.computedBeforeInit = false
    }

    // ================================================================== players

    private fun serviceOrNull(): NetworkService<Player>? =
        try {
            Rsprot.service
        } catch (e: UninitializedPropertyAccessException) {
            null
        }

    private fun stateOf(player: Player): PlayerState? = players.getOrNull(player.index)?.takeIf { it.player === player }

    private fun release(index: Int, state: PlayerState) {
        players[index] = null
        try {
            Rsprot.service.infoProtocols.dealloc(state.infos)
        } catch (e: Exception) {
            log.error("Failed to release rsprot infos for {}", state.player, e)
        }
    }

    private fun prepareGpiInit(state: PlayerState) {
        val player = state.player
        if (state.computedBeforeInit || state.gpiInitialised) {
            resetInfoState(state)
            state.computedBeforeInit = false
        }
        state.infos.updateRootCoord(player.plane, player.x, player.y)
    }

    private fun resetInfoState(state: PlayerState) {
        state.infos.playerInfo.onReconnect()
        state.infos.npcInfo.onReconnect()
        state.infos.worldEntityInfo.onReconnect()
    }

    private fun syncPlayer(state: PlayerState) {
        val player = state.player
        val infos = state.infos
        val avatar = infos.playerInfo.avatar
        val ext = avatar.extendedInfo

        // Hidden (admin invisibility) or leaving the world: not sent to anyone, as the old PlayerInfo did.
        val hidden = player.isHidden || player.isFinished
        if (hidden != state.hidden) {
            avatar.hidden = hidden
            state.hidden = hidden
        }
        syncViewDistance(state)
        infos.updateRootCoord(player.plane, player.x, player.y)
        syncBuildArea(state)

        val flags = player.updateFlags
        val moveSpeed = player.lastMovementType
        if (moveSpeed != state.moveSpeed) {
            RsprotExtendedInfo.applyMoveSpeed(ext, moveSpeed)
            state.moveSpeed = moveSpeed
        }
        if (flags.get(UpdateFlag.TEMPORARY_MOVEMENT_TYPE)) {
            val speed =
                when {
                    flags.get(UpdateFlag.FORCE_MOVEMENT) -> 1
                    player.isTeleported -> 127
                    player.runDirection != -1 -> 2
                    else -> 1
                }
            RsprotExtendedInfo.applyTempMoveSpeed(ext, speed)
        }
        if (flags.get(UpdateFlag.APPEARANCE) || flags.get(UpdateFlag.NAMETAG)) {
            RsprotExtendedInfo.applyAppearance(ext, buildAppearance(player))
        }
        if (flags.get(UpdateFlag.ANIMATION)) {
            val animation = player.animation ?: Animation.STOP
            RsprotExtendedInfo.applySequence(ext, animation.id, animation.delay)
        }
        if (flags.get(UpdateFlag.GRAPHICS)) {
            val graphics = player.graphics
            // Graphics restricted to some viewers (PrivateMask) are not supported per observer by rsprot.
            if (graphics == null) {
                RsprotExtendedInfo.applySpotAnim(ext, -1, 0, 0)
            } else if (graphics.viewers == null) {
                RsprotExtendedInfo.applySpotAnim(ext, graphics.id, graphics.delay, graphics.height)
            }
        }
        if (flags.get(UpdateFlag.FORCED_CHAT)) {
            RsprotExtendedInfo.applySay(ext, player.forceTalk?.text ?: "")
        }
        if (flags.get(UpdateFlag.CHAT)) {
            val message = player.chatMessage
            val text = message?.chatText
            if (text != null) {
                val effects = message.effects
                RsprotExtendedInfo.applyChat(
                    ext,
                    effects ushr 8 and 0xFF,
                    effects and 0xFF,
                    chatPlayerType(player),
                    message.isAutotyper,
                    text,
                    message.pattern,
                )
            }
        }
        if (flags.get(UpdateFlag.HIT)) {
            for (hit in player.nextHits.take(255)) {
                val splat = hit.appliedSplat
                RsprotExtendedInfo.addHit(ext, sourceIndex(hit.source), splat.id, splat.dynamicId, hit.damage, hit.delay)
            }
            for (bar in player.hitBars.take(255)) {
                addHeadBar(ext, bar)
            }
        }
        if (flags.get(UpdateFlag.FORCE_MOVEMENT)) {
            val move = exactMove(player, player.forceMovement)
            RsprotExtendedInfo.applyExactMove(ext, move[0], move[1], move[2], move[3], move[4], move[5], move[6])
        }
        if (flags.get(UpdateFlag.TINTING)) {
            val tinting = player.tinting
            if (tinting != null) {
                val viewers = tinting.viewers
                if (viewers == null) {
                    RsprotExtendedInfo.applyTinting(
                        ext,
                        tinting.delay,
                        tinting.duration,
                        tinting.hue,
                        tinting.saturation,
                        tinting.luminance,
                        tinting.opacity,
                    )
                } else {
                    for (viewer in viewers) {
                        val viewerInfo = viewer?.let { stateOf(it) }?.infos?.playerInfo ?: continue
                        val start = RsprotExtendedInfo.u16(tinting.delay)
                        val end = RsprotExtendedInfo.u16(tinting.delay + tinting.duration).coerceAtLeast(start)
                        ext.setSpecificTinting(
                            start,
                            end,
                            RsprotExtendedInfo.u8(tinting.hue),
                            RsprotExtendedInfo.u8(tinting.saturation),
                            RsprotExtendedInfo.u8(tinting.luminance),
                            RsprotExtendedInfo.u8(tinting.opacity),
                            viewerInfo,
                        )
                    }
                }
            }
        }
        syncFace(state, ext, flags)
    }

    /**
     * Facing. Zenyte keeps a face target (FACE_ENTITY) and a face direction (FACE_COORDINATE) apart; 241 has
     * one face block. The old code re-sent the face target every cycle, so the target wins over a face
     * direction while it is set, which is what the 211 client showed.
     */
    private fun syncFace(state: PlayerState, ext: PlayerAvatarExtendedInfo, flags: UpdateFlags) {
        val player = state.player
        val faceEntity = player.faceEntity
        val direction = player.direction
        if (flags.get(UpdateFlag.FACE_ENTITY) || faceEntity != state.faceEntity) {
            state.faceEntity = faceEntity
            if (faceEntity >= 0) {
                RsprotExtendedInfo.applyFaceEntity(ext, faceEntity, direction)
            } else if (!flags.get(UpdateFlag.FACE_COORDINATE)) {
                ext.resetFacing()
            }
        }
        if (flags.get(UpdateFlag.FACE_COORDINATE) && faceEntity < 0) {
            RsprotExtendedInfo.applyFaceAngle(ext, direction)
        } else if (direction != state.direction) {
            // New observers see the player under its current angle.
            ext.syncAngle(RsprotExtendedInfo.angle(direction))
        }
        state.direction = direction
    }

    /**
     * Keeps rsprot's build area equal to the one the client was sent (REBUILD_NORMAL/REGION use the
     * zone of the tile the map was last loaded from, minus 6 zones, 13x13 zones).
     */
    private fun syncBuildArea(state: PlayerState) {
        val player = state.player
        val tile = player.lastLoadedMapRegionTile
        val centreX = tile?.x ?: player.x
        val centreY = tile?.y ?: player.y
        val key = (centreX ushr 3) or ((centreY ushr 3) shl 16)
        if (key == state.buildAreaKey) return
        state.buildAreaKey = key
        state.infos.updateRootBuildAreaCenteredOnPlayer(centreX, centreY)
    }

    /**
     * Zenyte's view distance (15, raised by cutscenes and the admin command) becomes rsprot's player resize
     * range and NPC render distance; heatmap mode shows every player. rsprot shrinks the player range by itself
     * in crowds, which replaces the old PlayerInfo.resize().
     */
    private fun syncViewDistance(state: PlayerState) {
        val player = state.player
        val distance = player.viewDistance
        val heatmap = player.isHeatmap
        if (distance == state.viewDistance && heatmap == state.heatmap) return
        state.viewDistance = distance
        state.heatmap = heatmap
        val avatar = state.infos.playerInfo.avatar
        if (heatmap) {
            avatar.forceResizeRange(Int.MAX_VALUE)
        } else {
            avatar.setPreferredResizeRange(distance.coerceIn(1, 104))
        }
        val npcInfo = state.infos.npcInfo
        if (distance <= DEFAULT_VIEW_DISTANCE) {
            npcInfo.resetRenderDistance()
            npcInfo.setZoneSearchRadius(DEFAULT_ZONE_SEARCH_RADIUS)
        } else {
            npcInfo.setRenderDistance(distance)
            npcInfo.setZoneSearchRadius(max(DEFAULT_ZONE_SEARCH_RADIUS, (distance + 7) / 8))
        }
    }

    private fun flush(state: PlayerState) {
        val packets = state.infos.getPackets()
        val sink = this.sink
        if (!state.gpiInitialised || sink == null) {
            // Nothing may reach the client before its GPI init; without a sink there is no client to reach.
            releaseAll(packets)
            if (!state.gpiInitialised) {
                state.computedBeforeInit = true
            } else {
                warnMissingSink()
            }
            return
        }
        val root = packets.rootWorldInfoPackets
        val failure =
            root.worldEntityInfo.exceptionOrNull()
                ?: root.playerInfo.exceptionOrNull()
                ?: root.npcInfo.exceptionOrNull()
        if (failure != null) {
            // Info packets are one big state machine: a failed one cannot be recovered, so the player is kicked.
            log.error("Info packet failed for {}, logging out", state.player, failure)
            releaseAll(packets)
            state.player.logout(true)
            return
        }
        val player = state.player
        sendInfoPackets(packets) { message -> send(sink, player, message) }
        // Last packet of the cycle, as OSRS: the 241 client counts ground item timers on it and RuneLite fires GameTick.
        send(sink, player, ServerTickEnd)
    }

    /**
     * Hands out one player's info packets of this cycle in the order the 241 client expects
     * (rsprot README): root world first (set active world, npc update origin, world entity info,
     * player info, npc info), then any world entity (none in GadScape), then the root world is made
     * active again. Empty NPC info packets are not sent (as OSRS) and are released here.
     * Every packet result must have succeeded (see [flush]).
     */
    internal fun sendInfoPackets(packets: InfoPackets, send: (OutgoingGameMessage) -> Unit) {
        val root = packets.rootWorldInfoPackets
        send(root.activeWorld)
        send(root.npcUpdateOrigin)
        root.worldEntityInfo.getOrNull()?.let(send)
        root.playerInfo.getOrNull()?.let(send)
        sendNpcInfo(root.npcInfo, send)
        for (world in packets.activeWorlds) {
            send(world.activeWorld)
            if (world.npcInfo.isEmpty()) {
                world.npcInfo.getOrNull()?.safeRelease()
            } else {
                send(world.npcUpdateOrigin)
                world.npcInfo.getOrNull()?.let(send)
            }
        }
        send(root.activeWorld)
    }

    private fun send(sink: Sink, player: Player, message: OutgoingGameMessage) {
        val queued =
            try {
                sink.send(player, message)
            } catch (e: Exception) {
                log.error("Info sink failed for {}", player, e)
                false
            }
        if (!queued) {
            message.safeRelease()
        }
    }

    /** OSRS drops an NPC info packet with nothing in it; it still has to be released. */
    private fun sendNpcInfo(result: PacketResult<NpcInfoPacket>, send: (OutgoingGameMessage) -> Unit) {
        if (result.isEmpty()) {
            result.getOrNull()?.safeRelease()
        } else {
            result.getOrNull()?.let(send)
        }
    }

    /** Releases every info packet of this cycle without sending it. */
    internal fun releaseAll(packets: InfoPackets) {
        val root = packets.rootWorldInfoPackets
        root.worldEntityInfo.getOrNull()?.safeRelease()
        root.playerInfo.getOrNull()?.safeRelease()
        root.npcInfo.getOrNull()?.safeRelease()
        for (world in packets.activeWorlds) {
            world.npcInfo.getOrNull()?.safeRelease()
        }
    }

    private fun warnMissingSink() {
        val now = System.currentTimeMillis()
        if (now - lastSinkWarning < 60_000) return
        lastSinkWarning = now
        log.warn("RsprotInfo.sink is not set: player/npc info packets are computed but dropped")
    }

    /** Resolves Zenyte's Appearance into the values of the 241 appearance block (see the old Appearance.writeAppearanceData). */
    internal fun buildAppearance(player: Player): RsprotExtendedInfo.AppearanceData {
        val appearance = player.appearance
        val npcId = appearance.npcId
        val npcDefinitions = if (npcId >= 0) NPCDefinitions.get(npcId) else null
        val worn = IntArray(12) { -1 }
        val hide2 = IntArray(12) { -1 }
        val hide3 = IntArray(12) { -1 }
        val kits = IntArray(7) { -1 }
        val empty = appearance.isInvisible || appearance.isHideEquipment
        if (!empty) {
            val looks = appearance.appearance
            for (slot in 0 until 7) {
                kits[slot] = looks?.getOrNull(slot)?.toInt() ?: -1
            }
        }
        if (npcDefinitions == null && !empty) {
            for (wearpos in VISIBLE_WEARPOS) {
                worn[wearpos] = appearance.getWornItemId(wearpos)
            }
            val chest = itemDefinitions(worn[CHEST])
            if (chest != null && (chest.equipmentType == EquipmentType.FULL_BODY || chest.hidesInCache(ARMS))) {
                hide2[CHEST] = ARMS
            }
            val legs = itemDefinitions(worn[LEGS])
            if (legs != null && legs.equipmentType == EquipmentType.FULL_LEGS) {
                hide2[LEGS] = FEET
            }
            var hideHair = false
            var hideJaw = false
            if (worn[HELMET] != -1) {
                val helmet = itemDefinitions(worn[HELMET])
                val type = helmet?.equipmentType ?: EquipmentType.DEFAULT
                hideHair = !(type == EquipmentType.DEFAULT && helmet?.hidesInCache(HAIR) != true)
                hideJaw = type == EquipmentType.FULL_MASK || helmet?.hidesInCache(JAW) == true
            }
            val beardIcon = player.pvmArenaAppearanceBeardOffset
            if (beardIcon > 0) {
                worn[JAW] = if (hideJaw) beardIcon else appearance.getBeardIconWithAppearanceOffset(beardIcon)
                hideJaw = false
            }
            if (hideHair) hide2[HELMET] = HAIR
            if (hideJaw) {
                if (hide2[HELMET] == -1) hide2[HELMET] = JAW else hide3[HELMET] = JAW
            }
        }
        val render: RenderType = npcDefinitions ?: appearance.renderAnimation ?: RenderAnimation.DEFAULT_RENDER
        val colours = appearance.colours
        val nametags = player.nametags
        return RsprotExtendedInfo.AppearanceData(
            bodyType = if (appearance.isMale) 0 else 1,
            pronoun = if (appearance.isMale) 0 else 1,
            skullIcon = PlayerSkulls.getSkull(player),
            overheadIcon = appearance.headIcon.toInt(),
            transmogId = if (npcDefinitions != null) npcId else -1,
            wornObjs = worn,
            wornHide2 = hide2,
            wornHide3 = hide3,
            identKits = kits,
            colours = IntArray(5) { colours?.getOrNull(it)?.toInt()?.and(0xFF) ?: 0 },
            baseAnimationSet =
                intArrayOf(
                    render.stand,
                    render.standTurn,
                    render.walk,
                    render.rotate180,
                    render.rotate90,
                    render.rotate270,
                    render.run,
                ),
            // The vanilla 241 client has no <title> tag support: the plain display name is sent and the crowns
            // the 211 client showed from getTitleName() go in front of it as <img> tags (name extra 0, which the
            // client draws before the name in the right-click menu).
            name = player.name ?: "",
            combatLevel = player.skills.combatLevel,
            skillLevel = 0,
            hidden = appearance.isInvisible,
            beforeName = crownTags(player) + (nametags?.getOrNull(0) ?: ""),
            afterName = nametags?.getOrNull(1) ?: "",
            afterCombatLevel = nametags?.getOrNull(2) ?: "",
        )
    }

    private fun itemDefinitions(id: Int): ItemDefinitions? = if (id < 0) null else ItemDefinitions.get(id)

    /** The player's crowns (rank, game mode, member) as <img=N> tags, in the order of Player.getTitleName(). */
    internal fun crownTags(player: Player): String {
        val tags = StringBuilder()
        for (crown in arrayOf(player.privilege?.crown(), player.gameModeCrown, player.memberCrown)) {
            if (crown != null && crown.id != -1) tags.append(crown.crownTag)
        }
        return tags.toString()
    }

    /**
     * The 241 PlayerType id for the public chat icon: the rank crown (as Player.getRankIcon() picks it), else the
     * game mode crown, else the member crown, whichever the 241 client can draw (see RsprotExtendedInfo.chatPlayerType).
     */
    internal fun chatPlayerType(player: Player): Int =
        RsprotExtendedInfo.chatPlayerType(
            player.rankIcon - 1,
            player.gameModeCrown?.id ?: -1,
            player.memberCrown?.id ?: -1,
        )

    private fun sourceIndex(source: Entity?): Int =
        when (source) {
            is Player -> RsprotExtendedInfo.sourceIndex(isPlayer = true, isNpc = false, index = source.index)
            is NPC -> RsprotExtendedInfo.sourceIndex(isPlayer = false, isNpc = true, index = source.index)
            else -> -1
        }

    private fun addHeadBar(ext: PlayerAvatarExtendedInfo, bar: HitBar) {
        RsprotExtendedInfo.addHeadBar(
            ext,
            bar.type,
            bar.percentage,
            bar.interpolatePercentage(),
            bar.delay,
            bar.interpolateTime(),
        )
    }

    /** dx1, dz1, delay1, dx2, dz2, delay2, direction, relative to the entity's current tile (as the old mask). */
    private fun exactMove(entity: Entity, movement: ForceMovement?): IntArray {
        val move = movement ?: ForceMovement(entity.location, 1, 0)
        val x = entity.x
        val y = entity.y
        val first = move.toFirstTile
        val second = move.toSecondTile
        return intArrayOf(
            if (first == null) 0 else first.x - x,
            if (first == null) 0 else first.y - y,
            move.firstTileTicketDelay,
            if (second == null) 0 else second.x - x,
            if (second == null) 0 else second.y - y,
            move.secondTileTicketDelay,
            move.direction,
        )
    }

    // ================================================================== npcs

    /**
     * One rsprot avatar per NPC in the world list, allocated when the NPC first shows up there and released
     * when it leaves it. Dead/despawned NPCs that stay in the list (isFinished) are marked inaccessible.
     */
    private fun syncNpcs(factory: NpcAvatarFactory) {
        val cycle = this.cycle
        for (npc in World.getNPCs()) {
            if (npc == null) continue
            val index = npc.index
            if (index !in 0 until NPC_CAPACITY) continue
            var state = npcs[index]
            if (state != null && state.npc !== npc) {
                releaseNpc(factory, index, state)
                state = null
            }
            if (state == null) {
                state =
                    try {
                        allocNpc(factory, npc)
                    } catch (e: Exception) {
                        log.error("Failed to allocate rsprot avatar for {}", npc, e)
                        null
                    } ?: continue
                npcs[index] = state
            }
            state.seen = cycle
            try {
                syncNpc(state)
            } catch (e: Exception) {
                log.error("Failed to update rsprot avatar for {}", npc, e)
            }
        }
        for (index in 0 until NPC_CAPACITY) {
            val state = npcs[index] ?: continue
            if (state.seen != cycle) {
                releaseNpc(factory, index, state)
            }
        }
    }

    private fun validCoord(level: Int, x: Int, z: Int): Boolean = level in 0..3 && x in 0..16383 && z in 0..16383

    private fun allocNpc(factory: NpcAvatarFactory, npc: NPC): NpcState? {
        if (npc.location == null) return null
        val level = npc.plane
        val x = npc.x
        val z = npc.y
        if (!validCoord(level, x, z)) return null
        val id = npc.id
        if (id !in 0..0xFFFF) return null
        val avatar = factory.alloc(npc.index, id, level, x, z, 0, npc.roundedDirection.coerceIn(0, 7))
        val state = NpcState(npc, avatar)
        state.id = id
        val ext = avatar.extendedInfo
        // Persistent blocks; rsprot replays them to every new observer.
        state.combatLevel = npc.combatLevelChange
        if (state.combatLevel != -1) RsprotExtendedInfo.applyCombatLevel(ext, state.combatLevel)
        state.name = npc.nameChange
        if (state.name != null) ext.setNameChange(state.name)
        state.ops = npc.optionMask and 0x1F
        if (state.ops != 31) RsprotExtendedInfo.applyVisibleOps(ext, state.ops)
        val overhead = npc.overhead
        if (npc is MultiHeadIconNpc) {
            syncHeadIcons(state, ext, npc, overhead?.archive ?: -1, overhead?.sprite ?: -1)
        } else if (overhead != null) {
            state.overheadGroup = overhead.archive
            state.overheadIndex = overhead.sprite
            RsprotExtendedInfo.applyHeadIcon(ext, overhead.archive, overhead.sprite)
        }
        val overrides = npc.overrides
        if (overrides != null && !overrides.empty()) {
            RsprotExtendedInfo.applyBodyModels(ext, overrides.override(npc.definitions.models))
        }
        state.faceEntity = npc.faceEntity
        if (state.faceEntity >= 0) RsprotExtendedInfo.applyFaceEntity(ext, state.faceEntity, npc.direction)
        state.inaccessible = npc.isFinished
        if (state.inaccessible) avatar.setInaccessible(true)
        return state
    }

    /**
     * Head icon slots 0..7 of a [MultiHeadIconNpc] (E13): slot 0 is the holder's slot 0, or the classic
     * `NPC.getOverhead()` while that is empty; slots 1..7 come from the holder. Only changed slots are sent.
     */
    private fun syncHeadIcons(
        state: NpcState,
        ext: NpcAvatarExtendedInfo,
        npc: MultiHeadIconNpc,
        overheadGroup: Int,
        overheadIndex: Int,
    ) {
        val icons = npc.headIcons
        if (icons.version == state.headIconVersion && overheadGroup == state.overheadGroup &&
            overheadIndex == state.overheadIndex) return
        val groups = state.headIconGroups ?: IntArray(RsprotExtendedInfo.HEAD_ICON_SLOTS) { -1 }.also { state.headIconGroups = it }
        val indices = state.headIconIndices ?: IntArray(RsprotExtendedInfo.HEAD_ICON_SLOTS) { -1 }.also { state.headIconIndices = it }
        for (slot in 0 until RsprotExtendedInfo.HEAD_ICON_SLOTS) {
            var group = if (slot < NpcHeadIcons.SLOTS) icons.group(slot) else -1
            var index = if (slot < NpcHeadIcons.SLOTS) icons.index(slot) else -1
            if (slot == 0 && (group < 0 || index < 0)) {
                group = overheadGroup
                index = overheadIndex
            }
            if (group < 0 || index < 0) {
                group = -1
                index = -1
            }
            if (group == groups[slot] && index == indices[slot]) continue
            RsprotExtendedInfo.applyHeadIcon(ext, slot, group, index)
            groups[slot] = group
            indices[slot] = index
        }
        state.headIconVersion = icons.version
        state.overheadGroup = overheadGroup
        state.overheadIndex = overheadIndex
    }

    private fun releaseNpc(factory: NpcAvatarFactory, index: Int, state: NpcState) {
        npcs[index] = null
        try {
            factory.release(state.avatar)
        } catch (e: Exception) {
            log.error("Failed to release rsprot avatar for {}", state.npc, e)
        }
    }

    private fun syncNpc(state: NpcState) {
        val npc = state.npc
        val avatar = state.avatar
        val ext = avatar.extendedInfo

        val inaccessible = npc.isFinished
        if (inaccessible != state.inaccessible) {
            avatar.setInaccessible(inaccessible)
            state.inaccessible = inaccessible
        }
        moveNpc(state)

        // New observers (and re-adds after a teleport) get the avatar's direction; 211 wrote the NPC's current direction on every add.
        val direction = npc.roundedDirection.coerceIn(0, 7)
        if (direction != state.direction) {
            avatar.setDirection(direction)
            state.direction = direction
        }

        val flags = npc.updateFlags
        if (flags.get(UpdateFlag.TRANSFORMATION)) {
            val id = npc.nextTransformation
            if (id in 0..0xFFFF) {
                avatar.setId(id)
                ext.setTransmogrification(id)
                state.id = id
            }
        } else if (npc.id != state.id && npc.id in 0..0xFFFF) {
            // Id changed without a transformation mask: only new observers see it, as with the old NPCInfo.
            avatar.setId(npc.id)
            state.id = npc.id
        }

        val combatLevel = npc.combatLevelChange
        if (combatLevel != state.combatLevel) {
            RsprotExtendedInfo.applyCombatLevel(ext, combatLevel)
            state.combatLevel = combatLevel
        }
        val name = npc.nameChange
        if (name != state.name) {
            ext.setNameChange(name)
            state.name = name
        }
        val ops = npc.optionMask and 0x1F
        if (ops != state.ops) {
            RsprotExtendedInfo.applyVisibleOps(ext, ops)
            state.ops = ops
        }
        val overhead = npc.overhead
        val overheadGroup = overhead?.archive ?: -1
        val overheadIndex = overhead?.sprite ?: -1
        if (npc is MultiHeadIconNpc) {
            syncHeadIcons(state, ext, npc, overheadGroup, overheadIndex)
        } else if (overheadGroup != state.overheadGroup || overheadIndex != state.overheadIndex) {
            RsprotExtendedInfo.applyHeadIcon(ext, overheadGroup, overheadIndex)
            state.overheadGroup = overheadGroup
            state.overheadIndex = overheadIndex
        }
        if (flags.get(UpdateFlag.MODEL_OVERRIDE)) {
            val overrides = npc.overrides
            val models =
                if (overrides == null || overrides.empty()) null else overrides.override(npc.definitions.models)
            RsprotExtendedInfo.applyBodyModels(ext, models)
        }

        if (!flags.isUpdateRequired && npc.faceEntity == state.faceEntity) return

        if (flags.get(UpdateFlag.ANIMATION)) {
            val animation = npc.animation ?: Animation.STOP
            RsprotExtendedInfo.applySequence(ext, animation.id, animation.delay)
        }
        if (flags.get(UpdateFlag.GRAPHICS)) {
            val graphics = npc.graphics
            if (graphics == null) {
                RsprotExtendedInfo.applySpotAnim(ext, -1, 0, 0)
            } else {
                RsprotExtendedInfo.applySpotAnim(ext, graphics.id, graphics.delay, graphics.height)
            }
        }
        if (flags.get(UpdateFlag.FORCED_CHAT)) {
            RsprotExtendedInfo.applySay(ext, npc.forceTalk?.text ?: "")
        }
        if (flags.get(UpdateFlag.HIT)) {
            for (hit in npc.nextHits.take(255)) {
                val splat = hit.appliedSplat
                RsprotExtendedInfo.addHit(ext, sourceIndex(hit.source), splat.id, splat.dynamicId, hit.damage, hit.delay)
            }
            for (bar in npc.hitBars.take(255)) {
                RsprotExtendedInfo.addHeadBar(
                    ext,
                    bar.type,
                    bar.percentage,
                    bar.interpolatePercentage(),
                    bar.delay,
                    bar.interpolateTime(),
                )
            }
        }
        if (flags.get(UpdateFlag.FORCE_MOVEMENT)) {
            val move = exactMove(npc, npc.forceMovement)
            RsprotExtendedInfo.applyExactMove(ext, move[0], move[1], move[2], move[3], move[4], move[5], move[6])
        }
        if (flags.get(UpdateFlag.TINTING)) {
            val tinting = npc.tinting
            if (tinting != null) {
                RsprotExtendedInfo.applyTinting(
                    ext,
                    tinting.delay,
                    tinting.duration,
                    tinting.hue,
                    tinting.saturation,
                    tinting.luminance,
                    tinting.opacity,
                )
            }
        }
        val faceEntity = npc.faceEntity
        if (flags.get(UpdateFlag.FACE_ENTITY) || faceEntity != state.faceEntity) {
            state.faceEntity = faceEntity
            if (faceEntity >= 0) {
                RsprotExtendedInfo.applyFaceEntity(ext, faceEntity, npc.direction)
            } else if (!flags.get(UpdateFlag.FACE_COORDINATE)) {
                ext.resetFacing()
            }
        }
        if (flags.get(UpdateFlag.FACE_COORDINATE) && faceEntity < 0) {
            val tile = npc.faceLocation ?: npc.location
            RsprotExtendedInfo.applyFaceCoord(ext, tile.x, tile.y)
        }
    }

    /**
     * Moves the avatar to the NPC's tile: one or two steps are sent as walk/crawl/run, anything else
     * (or a Zenyte teleport) as a teleport jump, which the client shows as remove + re-add, like the old NPCInfo.
     */
    private fun moveNpc(state: NpcState) {
        val npc = state.npc
        moveNpcAvatar(state.avatar, npc.plane, npc.x, npc.y, npc.isTeleported, npc.crawlDirection != -1)
    }

    internal fun moveNpcAvatar(avatar: NpcAvatar, level: Int, x: Int, z: Int, teleported: Boolean, crawl: Boolean) {
        if (!validCoord(level, x, z)) return
        val dx = x - avatar.x()
        val dz = z - avatar.z()
        if (dx == 0 && dz == 0 && level == avatar.level()) return
        if (teleported || level != avatar.level() || abs(dx) > 2 || abs(dz) > 2) {
            avatar.teleport(level, x, z, true)
            return
        }
        val stepX = dx.sign
        val stepZ = dz.sign
        step(avatar, stepX, stepZ, crawl)
        val restX = dx - stepX
        val restZ = dz - stepZ
        if (restX != 0 || restZ != 0) {
            step(avatar, restX, restZ, crawl)
        }
    }

    private fun step(avatar: NpcAvatar, dx: Int, dz: Int, crawl: Boolean) {
        if (crawl) avatar.crawl(dx, dz) else avatar.walk(dx, dz)
    }
}

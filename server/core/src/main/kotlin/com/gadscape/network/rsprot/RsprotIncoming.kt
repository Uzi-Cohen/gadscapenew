package com.gadscape.network.rsprot

import com.gadscape.game.util.HuffmanManager
import com.zenyte.game.packet.`in`.ClientProtEvent
import com.zenyte.game.packet.`in`.event.*
import com.zenyte.game.world.entity.masks.ChatMessage
import com.zenyte.game.world.entity.player.Player
import net.rsprot.protocol.game.incoming.buttons.If1Button
import net.rsprot.protocol.game.incoming.buttons.If3Button
import net.rsprot.protocol.game.incoming.buttons.IfButtonD
import net.rsprot.protocol.game.incoming.buttons.IfButtonT
import net.rsprot.protocol.game.incoming.buttons.IfSubOp
import net.rsprot.protocol.game.incoming.events.EventAppletFocus
import net.rsprot.protocol.game.incoming.events.EventCameraPosition
import net.rsprot.protocol.game.incoming.events.EventKeyboard
import net.rsprot.protocol.game.incoming.events.EventMouseClickV1
import net.rsprot.protocol.game.incoming.events.EventMouseClickV2
import net.rsprot.protocol.game.incoming.events.EventMouseMove
import net.rsprot.protocol.game.incoming.events.EventNativeMouseMove
import net.rsprot.protocol.game.incoming.friendchat.FriendChatJoinLeave
import net.rsprot.protocol.game.incoming.friendchat.FriendChatKick
import net.rsprot.protocol.game.incoming.friendchat.FriendChatSetRank
import net.rsprot.protocol.game.incoming.locs.OpLoc6
import net.rsprot.protocol.game.incoming.locs.OpLocT
import net.rsprot.protocol.game.incoming.locs.OpLocV2
import net.rsprot.protocol.game.incoming.messaging.MessagePrivate
import net.rsprot.protocol.game.incoming.messaging.MessagePublic
import net.rsprot.protocol.game.incoming.misc.client.ConnectionTelemetry
import net.rsprot.protocol.game.incoming.misc.client.Idle
import net.rsprot.protocol.game.incoming.misc.client.MapBuildComplete
import net.rsprot.protocol.game.incoming.misc.client.NoTimeout
import net.rsprot.protocol.game.incoming.misc.client.SendPingReply
import net.rsprot.protocol.game.incoming.misc.client.WindowStatus
import net.rsprot.protocol.game.incoming.misc.user.BugReport
import net.rsprot.protocol.game.incoming.misc.user.ClickWorldMap
import net.rsprot.protocol.game.incoming.misc.user.ClientCheat
import net.rsprot.protocol.game.incoming.misc.user.CloseModal
import net.rsprot.protocol.game.incoming.misc.user.ExitFreecam
import net.rsprot.protocol.game.incoming.misc.user.MoveGameClick
import net.rsprot.protocol.game.incoming.misc.user.MoveMinimapClick
import net.rsprot.protocol.game.incoming.misc.user.SendSnapshot
import net.rsprot.protocol.game.incoming.misc.user.SetChatFilterSettings
import net.rsprot.protocol.game.incoming.misc.user.Teleport
import net.rsprot.protocol.game.incoming.npcs.OpNpc6
import net.rsprot.protocol.game.incoming.npcs.OpNpcT
import net.rsprot.protocol.game.incoming.npcs.OpNpcV2
import net.rsprot.protocol.game.incoming.objs.OpObj6
import net.rsprot.protocol.game.incoming.objs.OpObjT
import net.rsprot.protocol.game.incoming.objs.OpObjV2
import net.rsprot.protocol.game.incoming.players.OpPlayer
import net.rsprot.protocol.game.incoming.players.OpPlayerT
import net.rsprot.protocol.game.incoming.resumed.ResumePCountDialog
import net.rsprot.protocol.game.incoming.resumed.ResumePNameDialog
import net.rsprot.protocol.game.incoming.resumed.ResumePObjDialog
import net.rsprot.protocol.game.incoming.resumed.ResumePStringDialog
import net.rsprot.protocol.game.incoming.resumed.ResumePauseButton
import net.rsprot.protocol.game.incoming.social.FriendListAdd
import net.rsprot.protocol.game.incoming.social.FriendListDel
import net.rsprot.protocol.game.incoming.social.IgnoreListAdd
import net.rsprot.protocol.game.incoming.social.IgnoreListDel
import net.rsprot.protocol.message.IncomingGameMessage
import net.rsprot.protocol.message.codec.incoming.GameMessageConsumerRepositoryBuilder
import net.rsprot.protocol.message.codec.incoming.MessageConsumer
import net.rsprot.protocol.message.codec.incoming.provider.DefaultGameMessageConsumerRepositoryProvider
import org.slf4j.LoggerFactory

/**
 * Rev-241 client messages (decoded by rsprot) -> the rev-211 [ClientProtEvent]s the game already handles.
 *
 * rsprot calls these consumers from [RsprotSession.process], i.e. on the world thread at the point
 * where the rev-211 `WorldSubHandler` drained its queue, so every event runs exactly where it used to.
 *
 * Rev 241 changes that are folded back into the old events:
 *  - IF_BUTTONX (op 1..32) -> If3ButtonEvent(option = op); IF_SUBOP -> the same event with its parent op
 *    (the sub-option index is not known to the rev-211 handlers, see docs/rev241-net.md);
 *  - OPNPC/OPLOC/OPOBJ _V2 carry op + subop -> old events with op (subop ignored);
 *  - OPxxxT now covers both "spell on X" and "item on X": selectedObj != -1 -> the old *U (use item) event,
 *    otherwise the old *T (target) event;
 *  - IF1_BUTTON (component only) -> OpModelEvent, as the rev-211 BUTTON_CLICK packet did.
 */
object RsprotIncoming {

    private val logger = LoggerFactory.getLogger(RsprotIncoming::class.java)

    @JvmStatic
    fun provider(): DefaultGameMessageConsumerRepositoryProvider<Player> {
        val builder = GameMessageConsumerRepositoryBuilder<Player>()

        // --- client/system ---------------------------------------------------------------------------
        builder.on<MapBuildComplete> { _, _ -> MapBuildCompleteEvent() }
        builder.on<WindowStatus> { _, m -> WindowStatusEvent(m.windowMode, m.frameWidth, m.frameHeight) }
        builder.on<NoTimeout> { _, _ -> NoTimeOutEvent() }
        builder.on<ConnectionTelemetry> { _, m ->
            LoginStatisticsEvent(m.connectionLostDuration, m.loginDuration, m.clientState, m.loginCount, 0, 0, 0)
        }
        builder.on<SendPingReply> { _, m ->
            val sent = (m.value1.toLong() shl 32) or (m.value2.toLong() and 0xFFFF_FFFFL)
            PingStatisticsEvent(m.gcPercentTime, m.fps, System.nanoTime() - sent)
        }
        builder.on<EventAppletFocus> { _, m -> EventAppletFocusEvent(m.inFocus) }
        builder.on<Idle> { player, _ ->
            // rev 211 ignored repeated idle packets within one tick
            val session = RsprotSession.of(player)
            if (session != null) {
                if (session.idleEventThisTick) return@on null
                session.idleEventThisTick = true
            }
            EventMouseIdleEvent()
        }
        builder.on<EventMouseMove> { _, _ -> EventMouseMoveEvent() }
        builder.on<EventNativeMouseMove> { _, _ -> EventMouseMoveEvent() }
        builder.on<EventMouseClickV1> { _, m ->
            EventMouseClickEvent(if (m.rightClick) 1 else 0, m.lastTransmittedMouseClick, m.x, m.y)
        }
        builder.on<EventMouseClickV2> { _, m ->
            EventMouseClickEvent(if (m.rightClick) 1 else 0, m.lastTransmittedMouseClick, m.x, m.y)
        }
        builder.on<EventCameraPosition> { _, m -> EventCameraPosEvent(m.angleX, m.angleY) }
        builder.on<EventKeyboard> { _, m ->
            val keys = m.keysPressed
            val key = if (keys.length > 0) keys.getJagexKey(keys.length - 1) else -1
            EventKeyboardEvent(key, m.lastTransmittedKeyPress)
        }

        // --- interfaces --------------------------------------------------------------------------------
        builder.on<If3Button> { _, m -> If3ButtonEvent(m.interfaceId, m.componentId, m.sub, m.obj, m.op) }
        builder.on<IfSubOp> { _, m ->
            logger.debug("IF_SUBOP {}:{} op {} subop {} handled as op {}", m.interfaceId, m.componentId, m.op, m.subop, m.op)
            If3ButtonEvent(m.interfaceId, m.componentId, m.sub, m.obj, m.op)
        }
        builder.on<If1Button> { _, m -> OpModelEvent(m.interfaceId, m.componentId) }
        builder.on<IfButtonD> { _, m ->
            IfButtonDEvent(
                m.selectedInterfaceId, m.selectedComponentId, m.targetInterfaceId, m.targetComponentId,
                m.selectedSub, m.targetSub,
            )
        }
        builder.on<IfButtonT> { _, m ->
            IfButtonTEvent(
                m.selectedInterfaceId, m.selectedComponentId, m.targetInterfaceId, m.targetComponentId,
                m.selectedSub, m.targetSub, m.selectedObj, m.targetObj,
            )
        }
        builder.on<CloseModal> { _, _ -> CloseModalEvent() }
        builder.on<ResumePauseButton> { _, m -> ResumePauseButtonEvent(m.interfaceId, m.componentId, m.sub) }
        builder.on<ResumePCountDialog> { _, m -> ResumePCountDialogEvent(m.count) }
        builder.on<ResumePNameDialog> { _, m -> ResumePNameDialogEvent(m.name) }
        builder.on<ResumePStringDialog> { _, m -> ResumePStringDialogEvent(m.string) }
        builder.on<ResumePObjDialog> { _, m -> ResumePObjDialogEvent(m.obj) }

        // --- movement / world map ----------------------------------------------------------------------
        builder.on<MoveGameClick> { _, m -> MoveGameClickEvent(m.keyCombination, m.x, m.z) }
        builder.on<MoveMinimapClick> { _, m -> MoveMinimapClickEvent(m.keyCombination, m.x, m.z) }
        builder.on<Teleport> { _, m -> ClickWorldMapEvent(m.x, m.z, m.level) }
        builder.on<ClickWorldMap> { _, m -> DoubleClickWorldMapEvent((m.level shl 28) or (m.x shl 14) or m.z) }
        builder.on<ExitFreecam> { _, _ -> FreeCamResetEvent() }

        // --- entities ----------------------------------------------------------------------------------
        builder.on<OpNpcV2> { _, m -> OpNpcEvent(m.index, m.op, m.controlKey) }
        builder.on<OpNpc6> { _, m -> OpNpcExamineEvent(m.id) }
        builder.on<OpNpcT> { _, m ->
            if (m.selectedObj != -1)
                OpNpcUEvent(m.selectedInterfaceId, m.selectedComponentId, m.selectedSub, m.selectedObj, m.index, m.controlKey)
            else
                OpNpcTEvent(m.selectedInterfaceId, m.selectedComponentId, m.index, m.controlKey, m.selectedObj, m.selectedSub)
        }
        builder.on<OpLocV2> { _, m -> OpLocEvent(m.id, m.x, m.z, m.op, m.controlKey) }
        builder.on<OpLoc6> { _, m -> OpLocExamineEvent(m.id) }
        builder.on<OpLocT> { _, m ->
            if (m.selectedObj != -1)
                OpLocUEvent(m.selectedInterfaceId, m.selectedComponentId, m.selectedSub, m.selectedObj, m.id, m.x, m.z, m.controlKey)
            else
                OpLocTEvent(m.selectedInterfaceId, m.selectedComponentId, m.selectedSub, m.id, m.x, m.z, m.controlKey)
        }
        builder.on<OpObjV2> { _, m -> OpObjEvent(m.id, m.x, m.z, m.op, m.controlKey) }
        builder.on<OpObj6> { _, m -> OpObjEvent(m.id, m.x, m.z, 6, false) }
        builder.on<OpObjT> { _, m ->
            if (m.selectedObj != -1)
                OpObjUEvent(m.selectedInterfaceId, m.selectedComponentId, m.selectedSub, m.selectedObj, m.id, m.x, m.z, m.controlKey)
            else
                InterfaceOnFloorItemEvent(m.selectedInterfaceId, m.selectedComponentId, m.id, m.x, m.z)
        }
        builder.on<OpPlayer> { _, m -> OpPlayerEvent(m.index, m.op, m.controlKey) }
        builder.on<OpPlayerT> { _, m ->
            if (m.selectedObj != -1)
                OpPlayerUEvent(m.index, m.selectedSub, m.selectedObj, m.selectedCombinedId, if (m.controlKey) 1 else 0)
            else
                OpPlayerTEvent(m.selectedInterfaceId, m.selectedComponentId, m.index, m.controlKey, m.selectedObj, m.selectedSub)
        }

        // --- chat / social -----------------------------------------------------------------------------
        builder.on<MessagePublic> { _, m -> MessagePublicEvent(m.type, m.colour, m.effect, m.message, m.clanType) }
        builder.on<ClientCheat> { _, m -> CommandEvent(m.command.lowercase()) }
        builder.on<MessagePrivate> { _, m ->
            val huffman = HuffmanManager.encodeHuffmanBuf(m.message)
            MessagePrivateEvent(m.name, ChatMessage(huffman, m.message, 0, false))
        }
        builder.on<FriendListAdd> { _, m -> FriendListAddEvent(m.name) }
        builder.on<FriendListDel> { _, m -> FriendListDelEvent(m.name) }
        builder.on<IgnoreListAdd> { _, m -> IgnoreListAddEvent(m.name) }
        builder.on<IgnoreListDel> { _, m -> IgnoreListDelEvent(m.name) }
        builder.on<FriendChatJoinLeave> { _, m -> ClanJoinChatLeaveChatEvent(m.name ?: "") }
        builder.on<FriendChatSetRank> { _, m -> FriendSetRankEvent(m.name, m.rank) }
        builder.on<FriendChatKick> { _, m -> ClanKickUserEvent(m.name) }
        builder.on<SetChatFilterSettings> { _, m ->
            ChatSetModeEvent(m.publicChatFilter, m.privateChatFilter, m.tradeChatFilter)
        }
        builder.on<BugReport> { _, m -> BugReportEvent(m.instructions, m.description, m.type) }
        builder.on<SendSnapshot> { _, m -> PlayerReportEvent(m.name, m.ruleId, m.mute) }

        return DefaultGameMessageConsumerRepositoryProvider(builder.build())
    }

    /** Runs a rev-211 event exactly like the old WorldSubHandler did. */
    @JvmStatic
    fun dispatch(player: Player, event: ClientProtEvent) {
        try {
            event.use {
                event.handleEvent(player)
                event.logChecked(player)
            }
        } catch (e: Throwable) {
            logger.error("Failed to handle {} for {}", event.javaClass.simpleName, player, e)
        }
    }

    private inline fun <reified T : IncomingGameMessage> GameMessageConsumerRepositoryBuilder<Player>.on(
        crossinline convert: (Player, T) -> ClientProtEvent?,
    ) {
        addListener(T::class.java, MessageConsumer<Player, T> { player, message ->
            val event = convert(player, message) ?: return@MessageConsumer
            dispatch(player, event)
        })
    }
}

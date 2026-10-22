package com.gadscape.network.rsprot

import net.rsprot.protocol.game.outgoing.info.npcinfo.NpcAvatarExtendedInfo
import net.rsprot.protocol.game.outgoing.info.playerinfo.PlayerAvatarExtendedInfo

/**
 * Plain-value bridge between the old Zenyte update masks and rsprot's rev 241 extended info blocks.
 *
 * Every function here takes primitive values (no [com.zenyte.game.world.entity.player.Player] or
 * [com.zenyte.game.world.entity.npc.NPC]), clamps them into the ranges rsprot accepts and writes them
 * to the avatar. rsprot throws on out-of-range input, and one exception would otherwise abort the
 * info update for that entity, so every value is clamped or dropped here first.
 *
 * Which old mask maps to which block:
 * - AppearanceMask, NametagMask -> [applyAppearance] (241 carries the name extras inside appearance)
 * - AnimationMask -> setSequence; GraphicsMask -> setSpotAnim slot 0 (the old code had one spotanim only)
 * - HitMask -> addHitMark + addHeadBar/removeHeadBar
 * - ForceChatMask -> setSay; ChatMask -> setChat
 * - FaceEntityMask / FaceLocationMask -> setFacePlayer/setFaceNpc/setFaceAngle/setFaceCoordGrid/resetFacing
 * - MovementMask -> setMoveSpeed; TemporaryMovementMask -> setTempMoveSpeed
 * - ForceMovementMask -> setExactMove; TintingMask -> setTinting
 * - NPC only: TransformationMask -> setTransmogrification, CombatLevelChangeMask -> setCombatLevelChange,
 *   NameChangeMask -> setNameChange, HideOptionsMask -> setVisibleOps, NpcOverheadMask -> setHeadIconChange,
 *   ModelOverrideMask -> setBodyCustomisation
 *
 * Values follow the old 211 encoders exactly where they still mean the same thing in 241
 * (see server/docs/rev241-info.md for what is approximated).
 */
internal object RsprotExtendedInfo {

    /** Zenyte encodes a face target as npc index, or player index + this offset. */
    const val ZENYTE_PLAYER_FACE_OFFSET: Int = 32768

    /** rsprot encodes a hit/headbar source as npc index, or player index + this offset. */
    const val RSPROT_PLAYER_SOURCE_OFFSET: Int = 0x10000

    private const val MAX_PLAYER_SAY = 80
    private const val MAX_NPC_SAY = 256
    private const val MAX_CHAT = 80
    private const val SMART_MAX = 0x7FFF
    private const val HEADBAR_REMOVED = 0x7FFF
    private const val MAX_OBJ = 0xFFFF - 0x800
    private const val MAX_IDENT_KIT = 0x800 - 0x100 - 1

    /**
     * The whole appearance block must fit in 255 bytes. Without obj customisations the fixed part is
     * at most 81 bytes, so a 64-char name and three 32-char name extras still fit (81 + 64 + 96 = 241).
     */
    private const val MAX_NAME = 64
    private const val MAX_NAME_EXTRA = 32

    /** Everything the 241 appearance block needs, already resolved from Zenyte's Appearance. */
    class AppearanceData(
        val bodyType: Int,
        val pronoun: Int,
        val skullIcon: Int,
        val overheadIcon: Int,
        /** NPC id the player is transformed into, or -1. */
        val transmogId: Int,
        /** Obj id per wearpos 0..11, or -1. */
        val wornObjs: IntArray,
        /** Wearpos hidden by the obj in that wearpos (secondary), or -1. */
        val wornHide2: IntArray,
        /** Wearpos hidden by the obj in that wearpos (tertiary), or -1. */
        val wornHide3: IntArray,
        /** Ident kit per ident slot 0..6 (hair, beard, body, arms, gloves, legs, boots), or -1. */
        val identKits: IntArray,
        val colours: IntArray,
        /** ready, turn, walk, walkBack, walkLeft, walkRight, run (the old 211 order: stand, standTurn, walk, rotate180, rotate90, rotate270, run). */
        val baseAnimationSet: IntArray,
        val name: String,
        val combatLevel: Int,
        val skillLevel: Int,
        val hidden: Boolean,
        val beforeName: String,
        val afterName: String,
        val afterCombatLevel: String,
    )

    // ------------------------------------------------------------------ clamping helpers

    fun idOrMinusOne(value: Int, max: Int = 0xFFFF): Int = if (value in 0..max) value else -1

    /** The low byte, as the old encoders wrote Java bytes (colours, hue/saturation, mod icon). */
    fun u8(value: Int): Int = value and 0xFF

    /** Clamped into 0..255 (levels, head bar fills). */
    fun ubyte(value: Int): Int = value.coerceIn(0, 0xFF)

    fun u16(value: Int): Int = value.coerceIn(0, 0xFFFF)

    fun smart(value: Int): Int = value.coerceIn(0, SMART_MAX)

    fun signedByte(value: Int): Int = value.coerceIn(-128, 127)

    fun angle(value: Int): Int = value and 0x7FF

    fun limit(text: String, max: Int): String = if (text.length <= max) text else text.substring(0, max)

    /** Converts a Zenyte hit/headbar source (player index, npc index, or none) into rsprot's source index. */
    fun sourceIndex(isPlayer: Boolean, isNpc: Boolean, index: Int): Int =
        when {
            isPlayer && index in 1..2047 -> index + RSPROT_PLAYER_SOURCE_OFFSET
            isNpc && index in 0..0xFFFF -> index
            else -> -1
        }

    // ------------------------------------------------------------------ players

    fun applyAppearance(ext: PlayerAvatarExtendedInfo, data: AppearanceData) {
        ext.setBodyType(ubyte(data.bodyType))
        ext.setPronoun(ubyte(data.pronoun))
        ext.setSkullIcon(idOrMinusOne(data.skullIcon, 0xFF))
        ext.setOverheadIcon(idOrMinusOne(data.overheadIcon, 0xFF))
        ext.setTransmogrification(idOrMinusOne(data.transmogId))
        for (wearpos in 0 until 12) {
            val id = idOrMinusOne(data.wornObjs.getOrElse(wearpos) { -1 }, MAX_OBJ)
            val hide2 = idOrMinusOne(data.wornHide2.getOrElse(wearpos) { -1 }, 11)
            val hide3 = idOrMinusOne(data.wornHide3.getOrElse(wearpos) { -1 }, 11)
            // rsprot only stores the hidden wearpos when the obj id changes, so clear the slot first;
            // otherwise a different hide (e.g. the PvM arena beard) on the same helmet would be ignored.
            ext.setWornObj(wearpos, -1, -1, -1)
            if (id != -1) {
                ext.setWornObj(wearpos, id, hide2, hide3)
            }
        }
        for (slot in 0 until 7) {
            ext.setIdentKit(slot, idOrMinusOne(data.identKits.getOrElse(slot) { -1 }, MAX_IDENT_KIT))
        }
        for (slot in 0 until 5) {
            ext.setColour(slot, u8(data.colours.getOrElse(slot) { 0 }))
        }
        val bas = data.baseAnimationSet
        ext.setBaseAnimationSet(
            idOrMinusOne(bas.getOrElse(0) { -1 }),
            idOrMinusOne(bas.getOrElse(1) { -1 }),
            idOrMinusOne(bas.getOrElse(2) { -1 }),
            idOrMinusOne(bas.getOrElse(3) { -1 }),
            idOrMinusOne(bas.getOrElse(4) { -1 }),
            idOrMinusOne(bas.getOrElse(5) { -1 }),
            idOrMinusOne(bas.getOrElse(6) { -1 }),
        )
        val name = limit(data.name, MAX_NAME)
        ext.setName(name)
        ext.setCombatLevel(ubyte(data.combatLevel))
        ext.setSkillLevel(u16(data.skillLevel))
        ext.setHidden(data.hidden)
        val afterName = limit(data.afterName, MAX_NAME_EXTRA)
        val afterCombatLevel = limit(data.afterCombatLevel, MAX_NAME_EXTRA)
        // beforeName carries the crown <img> tags plus nametag 0, so it gets whatever the other strings leave
        // of the name + extras budget (64 + 3 * 32), at least 32 chars.
        val beforeNameBudget = MAX_NAME + 3 * MAX_NAME_EXTRA - name.length - afterName.length - afterCombatLevel.length
        ext.setNameExtras(
            limit(data.beforeName, beforeNameBudget.coerceAtLeast(MAX_NAME_EXTRA)),
            afterName,
            afterCombatLevel,
        )
    }

    fun applySequence(ext: PlayerAvatarExtendedInfo, id: Int, delay: Int) =
        ext.setSequence(idOrMinusOne(id), u16(delay))

    fun applySpotAnim(ext: PlayerAvatarExtendedInfo, id: Int, delay: Int, height: Int) =
        ext.setSpotAnim(0, idOrMinusOne(id), u16(delay), u16(height))

    fun applySay(ext: PlayerAvatarExtendedInfo, text: String) = ext.setSay(limit(text, MAX_PLAYER_SAY))

    /**
     * Public chat. [colour] and [effect] are the two bytes of Zenyte's `ChatMessage.effects`
     * (`colour << 8 | effect`). Pattern colours (13-20) need [pattern] (colour - 12 colour codes, as the
     * sender's client sent them); without a matching pattern they fall back to yellow.
     * [playerType] is the 241 client's PlayerType id (0 = no icon), see [chatPlayerType].
     */
    fun applyChat(
        ext: PlayerAvatarExtendedInfo,
        colour: Int,
        effect: Int,
        playerType: Int,
        autotyper: Boolean,
        text: String,
        pattern: ByteArray? = null,
    ) {
        val patterned = colour in 13..20 && pattern != null && pattern.size == colour - 12
        val safeColour = if (colour in 0..12 || patterned) colour else 0
        val safeType = if (playerType in PLAYER_TYPE_MOD_ICONS.indices) playerType else 0
        ext.setChat(safeColour, u8(effect), safeType, autotyper, limit(text, MAX_CHAT), if (patterned) pattern else null)
    }

    /**
     * The 241 client's PlayerType table (id -> mod icon), read from the 1.13.3 client: the chat
     * "mod icon" byte is a PlayerType id, and the client draws that type's mod icon. Ids outside it
     * make the client fail on the chat block, so only these are sent.
     */
    private val PLAYER_TYPE_MOD_ICONS = intArrayOf(-1, 0, 1, 2, 3, 10, 22, 41, 42, 43, 44, 45, 46, 47, 48, 49, 52, 55)

    /**
     * The PlayerType id for the first of [modIcons] (mod_icons image ids, -1 = none) that a 241
     * PlayerType draws, or 0 (no icon). The 211 GadScape client drew any crown; the vanilla 241 client
     * only draws the PlayerType icons in public chat.
     */
    fun chatPlayerType(vararg modIcons: Int): Int {
        for (icon in modIcons) {
            if (icon < 0) continue
            val type = PLAYER_TYPE_MOD_ICONS.indexOf(icon)
            if (type > 0) return type
        }
        return 0
    }

    /**
     * One hitsplat. The old mask showed the tinted id ([tintedType]) to the receiver and to whoever dealt the hit,
     * and the darkened id ([darkType]) to everyone else; rsprot does the same per observer.
     */
    fun addHit(
        ext: PlayerAvatarExtendedInfo,
        sourceIndex: Int,
        tintedType: Int,
        darkType: Int,
        damage: Int,
        delay: Int,
    ) {
        val tinted = tintedType.coerceIn(-1, 0x7FFD)
        val dark = darkType.coerceIn(-1, 0x7FFD)
        ext.addHitMark(sourceIndex, tinted, tinted, dark, smart(damage), smart(delay))
    }

    /**
     * One head bar, from Zenyte's HitBar: type, percentage = start fill, interpolatePercentage = end fill,
     * delay = start time, interpolateTime = end time (32767 = remove the bar).
     */
    fun addHeadBar(
        ext: PlayerAvatarExtendedInfo,
        type: Int,
        startFill: Int,
        endFill: Int,
        startTime: Int,
        endTime: Int,
    ) {
        val id = smart(type)
        if (endTime == HEADBAR_REMOVED) {
            ext.removeHeadBar(id)
            return
        }
        val end = endTime.coerceIn(0, HEADBAR_REMOVED - 1)
        ext.addHeadBar(
            sourceIndex = -1,
            selfType = id,
            otherType = id,
            startFill = ubyte(startFill),
            endFill = ubyte(if (end > 0) endFill else startFill),
            startTime = smart(startTime),
            endTime = end,
        )
    }

    /** Face target in Zenyte's encoding: -1 none, npc index, or player index + 32768. */
    fun applyFaceEntity(ext: PlayerAvatarExtendedInfo, zenyteFaceEntity: Int, fallbackAngle: Int) {
        when {
            zenyteFaceEntity < 0 || zenyteFaceEntity == 0xFFFF -> ext.resetFacing()
            zenyteFaceEntity >= ZENYTE_PLAYER_FACE_OFFSET -> {
                val index = zenyteFaceEntity - ZENYTE_PLAYER_FACE_OFFSET
                if (index in 1..2047) {
                    ext.setFacePlayer(index, false, 0, angle(fallbackAngle))
                } else {
                    ext.resetFacing()
                }
            }
            else -> ext.setFaceNpc(zenyteFaceEntity, false, 0, angle(fallbackAngle))
        }
    }

    fun applyFaceAngle(ext: PlayerAvatarExtendedInfo, angle: Int) = ext.setFaceAngle(angle(angle))

    /** 0 crawl, 1 walk, 2 run (the old MOVEMENT_TYPE byte). */
    fun applyMoveSpeed(ext: PlayerAvatarExtendedInfo, speed: Int) = ext.setMoveSpeed(speed.coerceIn(-1, 2))

    /** 1 walk, 2 run, 127 teleport (the old TEMPORARY_MOVEMENT_TYPE byte). */
    fun applyTempMoveSpeed(ext: PlayerAvatarExtendedInfo, speed: Int) =
        ext.setTempMoveSpeed(if (speed == 127) 127 else speed.coerceIn(-1, 2))

    fun applyExactMove(
        ext: PlayerAvatarExtendedInfo,
        deltaX1: Int,
        deltaZ1: Int,
        delay1: Int,
        deltaX2: Int,
        deltaZ2: Int,
        delay2: Int,
        direction: Int,
    ) = ext.setExactMove(
        signedByte(deltaX1),
        signedByte(deltaZ1),
        u16(delay1),
        signedByte(deltaX2),
        signedByte(deltaZ2),
        u16(delay2),
        angle(direction),
    )

    /** Zenyte Tinting: delay = start, delay + duration = end (as the old TintingMask wrote it). */
    fun applyTinting(
        ext: PlayerAvatarExtendedInfo,
        delay: Int,
        duration: Int,
        hue: Int,
        saturation: Int,
        luminance: Int,
        opacity: Int,
    ) {
        val start = u16(delay)
        val end = u16(delay + duration).coerceAtLeast(start)
        ext.setTinting(start, end, u8(hue), u8(saturation), u8(luminance), u8(opacity))
    }

    // ------------------------------------------------------------------ npcs

    fun applySequence(ext: NpcAvatarExtendedInfo, id: Int, delay: Int) =
        ext.setSequence(idOrMinusOne(id), u16(delay))

    fun applySpotAnim(ext: NpcAvatarExtendedInfo, id: Int, delay: Int, height: Int) =
        ext.setSpotAnim(0, idOrMinusOne(id), u16(delay), u16(height))

    fun applySay(ext: NpcAvatarExtendedInfo, text: String) = ext.setSay(limit(text, MAX_NPC_SAY))

    /** NPC hits: the source sees the tinted id, everyone else the darkened id (as the old NPC HitMask did). */
    fun addHit(
        ext: NpcAvatarExtendedInfo,
        sourceIndex: Int,
        tintedType: Int,
        darkType: Int,
        damage: Int,
        delay: Int,
    ) {
        val tinted = tintedType.coerceIn(-1, 0x7FFD)
        val dark = darkType.coerceIn(-1, 0x7FFD)
        ext.addHitMark(sourceIndex, tinted, dark, smart(damage), smart(delay))
    }

    fun addHeadBar(
        ext: NpcAvatarExtendedInfo,
        type: Int,
        startFill: Int,
        endFill: Int,
        startTime: Int,
        endTime: Int,
    ) {
        val id = smart(type)
        if (endTime == HEADBAR_REMOVED) {
            ext.removeHeadBar(id)
            return
        }
        val end = endTime.coerceIn(0, HEADBAR_REMOVED - 1)
        ext.addHeadBar(
            sourceIndex = -1,
            sourceType = id,
            otherType = id,
            startFill = ubyte(startFill),
            endFill = ubyte(if (end > 0) endFill else startFill),
            startTime = smart(startTime),
            endTime = end,
        )
    }

    fun applyFaceEntity(ext: NpcAvatarExtendedInfo, zenyteFaceEntity: Int, fallbackAngle: Int) {
        when {
            zenyteFaceEntity < 0 || zenyteFaceEntity == 0xFFFF -> ext.resetFacing()
            zenyteFaceEntity >= ZENYTE_PLAYER_FACE_OFFSET -> {
                val index = zenyteFaceEntity - ZENYTE_PLAYER_FACE_OFFSET
                if (index in 1..2047) {
                    ext.setFacePlayer(index, false, 0, angle(fallbackAngle))
                } else {
                    ext.resetFacing()
                }
            }
            else -> ext.setFaceNpc(zenyteFaceEntity, false, 0, angle(fallbackAngle))
        }
    }

    /** NPC face coordinate: the old mask sent the centre of one tile. */
    fun applyFaceCoord(ext: NpcAvatarExtendedInfo, x: Int, z: Int) {
        if (x !in 0..16383 || z !in 0..16383) return
        ext.setFaceCoordGrid(x, z, false, 0)
    }

    fun applyExactMove(
        ext: NpcAvatarExtendedInfo,
        deltaX1: Int,
        deltaZ1: Int,
        delay1: Int,
        deltaX2: Int,
        deltaZ2: Int,
        delay2: Int,
        direction: Int,
    ) = ext.setExactMove(
        signedByte(deltaX1),
        signedByte(deltaZ1),
        u16(delay1),
        signedByte(deltaX2),
        signedByte(deltaZ2),
        u16(delay2),
        angle(direction),
    )

    fun applyTinting(
        ext: NpcAvatarExtendedInfo,
        delay: Int,
        duration: Int,
        hue: Int,
        saturation: Int,
        luminance: Int,
        opacity: Int,
    ) {
        val start = u16(delay)
        val end = u16(delay + duration).coerceAtLeast(start)
        ext.setTinting(start, end, u8(hue), u8(saturation), u8(luminance), u8(opacity))
    }

    /** NpcOverheadMask: one head icon in slot 0, or reset it. */
    fun applyHeadIcon(ext: NpcAvatarExtendedInfo, group: Int, index: Int) = applyHeadIcon(ext, 0, group, index)

    /** Number of head icon slots in the 241 HEADICON_CUSTOMISATION block. */
    const val HEAD_ICON_SLOTS = 8

    /**
     * One head icon slot (0..7, E13): sprite [index] of sprite [group], or reset the slot when [group] or [index]
     * is negative. The client draws every filled slot. Out-of-range slots are ignored.
     */
    fun applyHeadIcon(ext: NpcAvatarExtendedInfo, slot: Int, group: Int, index: Int) {
        if (slot !in 0 until HEAD_ICON_SLOTS) return
        if (group < 0 || index < 0) {
            ext.resetHeadIcon(slot)
        } else {
            ext.setHeadIconChange(slot, group, idOrMinusOne(index))
        }
    }

    /** ModelOverrideMask: replace the NPC's models, or reset to the type's own models. */
    fun applyBodyModels(ext: NpcAvatarExtendedInfo, models: IntArray?) {
        if (models == null || models.isEmpty()) {
            ext.resetBodyCustomisations()
        } else {
            ext.setBodyCustomisation(models.take(255).toList(), emptyList(), emptyList())
        }
    }

    /**
     * CombatLevelChangeMask (E12): the combat level the client shows for the NPC instead of its type's level;
     * -1 removes the override. Set it with `NPC.setCombatLevelChange(level)` (e.g. Moia 1064, Ocellus P3 1601);
     * the block is persistent, so observers that see the NPC later get it too. Levels above 65535 are clamped.
     */
    fun applyCombatLevel(ext: NpcAvatarExtendedInfo, level: Int) =
        ext.setCombatLevelChange(if (level < 0) -1 else level.coerceAtMost(0xFFFF))

    /** HideOptionsMask: the 5 low bits say which of op1..op5 are shown. */
    fun applyVisibleOps(ext: NpcAvatarExtendedInfo, mask: Int) = ext.setVisibleOps(mask and 0x1F)
}

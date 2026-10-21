package mgi.types.config.items

import mgi.tools.jagcached.CacheFormat
import mgi.utilities.ByteBuffer

/**
 * Item (obj) config decoder for the rev-211 and build-241 caches.
 *
 * The build-241 opcodes were ported from RuneLite's ItemLoader (net.runelite.cache.definitions.loaders.ItemLoader,
 * RuneLite master 330e650):
 *
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
object ItemDefinitionsDecoding {

    @JvmStatic
    fun ItemDefinitions.decodeOpcode(buffer: ByteBuffer, opcode: Int) {
        when (opcode) {
            1 -> inventoryModelId = buffer.readUnsignedShort()
            2 -> {
                name = buffer.readString()
                lowercaseName = name.lowercase()
            }
            3 -> examine = buffer.readString()
            4 -> zoom = buffer.readUnsignedShort()
            5 -> modelPitch = buffer.readUnsignedShort()
            6 -> modelRoll = buffer.readUnsignedShort()
            7 -> offsetX = buffer.readShort()
            8 -> offsetY = buffer.readShort()
            9 -> buffer.readString()
            11 -> isStackable = 1
            12 -> price = buffer.readInt()
            13 -> wearPos1 = buffer.readByte().toInt()
            14 -> wearPos2 = buffer.readByte().toInt()
            15 -> cacheTradeable = false // build 241
            16 -> isMembers = true
            23 -> {
                primaryMaleModel = buffer.readUnsignedShort()
                maleOffset = buffer.readUnsignedByte()
            }
            24 -> secondaryMaleModel = buffer.readUnsignedShort()
            25 -> {
                primaryFemaleModel = buffer.readUnsignedShort()
                femaleOffset = buffer.readUnsignedByte()
            }
            26 -> secondaryFemaleModel = buffer.readUnsignedShort()
            27 -> wearPos3 = buffer.readByte().toInt()
            in 30..34 -> groundOptions[opcode - 30] = buffer.readGroundOption()
            in 35..39 -> inventoryOptions[opcode - 35] = buffer.readString()
            40 -> {
                val amount = buffer.readUnsignedByte()
                originalColours = ShortArray(amount)
                replacementColours = ShortArray(amount)
                for (index in 0 until amount) {
                    originalColours[index] = buffer.readUnsignedShort().toShort()
                    replacementColours[index] = buffer.readUnsignedShort().toShort()
                }
            }
            41 -> {
                val amount = buffer.readUnsignedByte()
                originalTextureIds = ShortArray(amount)
                replacementTextureIds = ShortArray(amount)
                for (index in 0 until amount) {
                    originalTextureIds[index] = buffer.readUnsignedShort().toShort()
                    replacementTextureIds[index] = buffer.readUnsignedShort().toShort()
                }
            }
            42 -> shiftClickIndex = buffer.readByte().toInt()
            43 -> readSubOps(buffer)
            // build 241: 4-byte model ids replace opcodes 1, 23-26, 78, 79 and 90-93
            44 -> inventoryModelId = buffer.readInt()
            45 -> {
                primaryMaleModel = buffer.readInt()
                maleOffset = buffer.readUnsignedByte()
            }
            46 -> secondaryMaleModel = buffer.readInt()
            47 -> tertiaryMaleModel = buffer.readInt()
            48 -> {
                primaryFemaleModel = buffer.readInt()
                femaleOffset = buffer.readUnsignedByte()
            }
            49 -> secondaryFemaleModel = buffer.readInt()
            50 -> tertiaryFemaleModel = buffer.readInt()
            51 -> primaryMaleHeadModelId = buffer.readInt()
            52 -> secondaryMaleHeadModelId = buffer.readInt()
            53 -> primaryFemaleHeadModelId = buffer.readInt()
            54 -> secondaryFemaleHeadModelId = buffer.readInt()
            65 -> grandExchange = true
            75 -> cacheWeight = buffer.readShort()
            78 -> tertiaryMaleModel = buffer.readUnsignedShort()
            79 -> tertiaryFemaleModel = buffer.readUnsignedShort()
            90 -> primaryMaleHeadModelId = buffer.readUnsignedShort()
            91 -> primaryFemaleHeadModelId = buffer.readUnsignedShort()
            92 -> secondaryMaleHeadModelId = buffer.readUnsignedShort()
            93 -> secondaryFemaleHeadModelId = buffer.readUnsignedShort()
            94 -> category = buffer.readUnsignedShort() // category
            95 -> modelYaw = buffer.readUnsignedShort()
            97 -> notedId = buffer.readUnsignedShort()
            98 -> notedTemplate = buffer.readUnsignedShort()
            in 100..109 -> {
                if (stackIds == null) {
                    stackIds = IntArray(10)
                    stackAmounts = IntArray(10)
                }
                stackIds[opcode - 100] = buffer.readUnsignedShort()
                stackAmounts[opcode - 100] = buffer.readUnsignedShort()
            }
            110 -> resizeX = buffer.readUnsignedShort()
            111 -> resizeY = buffer.readUnsignedShort()
            112 -> resizeZ = buffer.readUnsignedShort()
            113 -> ambient = buffer.readByte().toInt()
            114 -> contrast = buffer.readByte().toInt() * 5
            115 -> teamId = buffer.readUnsignedByte()
            139 -> bindId = buffer.readUnsignedShort()
            140 -> bindTemplateId = buffer.readUnsignedShort()
            148 -> placeholderId = buffer.readUnsignedShort()
            149 -> placeholderTemplate = buffer.readUnsignedShort()
            160 -> isStackable = 2 // build 241; not "stackable" for isStackable()
            161 -> {
                val length = buffer.readUnsignedShort()
                holdingWhitelist = IntArray(length) { buffer.readUnsignedShort() }
            }
            200 -> readSubOp(buffer)
            201 -> readConditionalOp(buffer)
            202 -> readConditionalSubOp(buffer)
            249 -> parameters = buffer.readParameters()
            251 -> bronzeman = true
            else -> throw CacheFormat.unknownOpcode("item", id, opcode)
        }
    }

    private fun readSubOps(buffer: ByteBuffer) {
        val opId: Int = buffer.readUnsignedByte()
        while (true) {
            val subopId: Int = buffer.readUnsignedByte() - 1
            if (subopId == -1) {
                break
            }
            val op: String = buffer.readString()
        }

    }

    /**
     * Shared by items (200), NPCs (251) and objects (100): op index, sub-op id, text.
     */
    @JvmStatic
    fun readSubOp(buffer: ByteBuffer) {
        buffer.readUnsignedByte() // op index
        buffer.readUnsignedByte() // sub-op id
        buffer.readString()
    }

    /**
     * Shared by items (201), NPCs (252) and objects (101): op index, varp, varbit, min, max, text.
     */
    @JvmStatic
    fun readConditionalOp(buffer: ByteBuffer) {
        buffer.readUnsignedByte() // op index
        buffer.readUnsignedShort() // varp
        buffer.readUnsignedShort() // varbit
        buffer.readInt() // min
        buffer.readInt() // max
        buffer.readString()
    }

    /**
     * Shared by items (202), NPCs (253) and objects (102): op index, sub-op id (short), varp, varbit, min, max, text.
     */
    @JvmStatic
    fun readConditionalSubOp(buffer: ByteBuffer) {
        buffer.readUnsignedByte() // op index
        buffer.readUnsignedShort() // sub-op id
        buffer.readUnsignedShort() // varp
        buffer.readUnsignedShort() // varbit
        buffer.readInt() // min
        buffer.readInt() // max
        buffer.readString()
    }

    private fun ByteBuffer.readGroundOption() = readString()
        .takeIf { !it.equals("Hidde", ignoreCase = true) }

}

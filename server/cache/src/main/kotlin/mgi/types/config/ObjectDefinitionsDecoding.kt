package mgi.types.config


import mgi.tools.jagcached.CacheFormat
import mgi.types.config.items.ItemDefinitionsDecoding
import mgi.utilities.ByteBuffer

/**
 * Object (loc) config decoder for the rev-211 and build-241 caches.
 *
 * The build-241 opcodes (6, 7, 42, 90-96, 100-102) were ported from RuneLite's ObjectLoader
 * (net.runelite.cache.definitions.loaders.ObjectLoader, RuneLite master 330e650):
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
object ObjectDefinitionsDecoding {
    @JvmStatic
    fun ObjectDefinitions.decodeOpcode(buffer: ByteBuffer, opcode: Int, oldVersion: Boolean) {
        when (opcode) {
            1 -> {
                val size = buffer.readUnsignedByte()
                if(size > 0) {
                    types = IntArray(size)
                    models = IntArray(size)
                    for (i in 0 until size) {
                        models[i] = buffer.readUnsignedShort()
                        types[i] = buffer.readUnsignedByte()
                    }
                }
            }
            2 -> name = buffer.readString()
            5 -> {
                val size = buffer.readUnsignedByte()
                if(size > 0) {
                    types = null
                    models = IntArray(size)
                    for (i in 0 until size) {
                        models[i] = buffer.readUnsignedShort()
                    }
                }
            }
            6 -> { // build 241: 4-byte model ids (replaces 1)
                val size = buffer.readUnsignedByte()
                if (size > 0) {
                    types = IntArray(size)
                    models = IntArray(size)
                    for (i in 0 until size) {
                        models[i] = buffer.readInt()
                        types[i] = buffer.readUnsignedByte()
                    }
                }
            }
            7 -> { // build 241: 4-byte model ids (replaces 5)
                val size = buffer.readUnsignedByte()
                if (size > 0) {
                    types = null
                    models = IntArray(size)
                    for (i in 0 until size) {
                        models[i] = buffer.readInt()
                    }
                }
            }
            14 -> sizeX = buffer.readUnsignedByte()
            15 -> sizeY = buffer.readUnsignedByte()
            17 -> { clipType = 0; isProjectileClip = false }
            18 -> isProjectileClip = false
            19 -> optionsInvisible = buffer.readUnsignedByte()
            21 -> contouredGround = 0
            22 -> nonFlatShading = true
            23 -> modelClipped = true
            24 -> animationId = buffer.readUnsignedShortNo65535()
            27 -> clipType = 1
            28 -> decorDisplacement = buffer.readUnsignedByte()
            29 -> ambient = buffer.readByte().toInt()
            in 30..34 -> {
                options[opcode - 30] = buffer.readString()
                if (options[opcode - 30].equals("Hidden", ignoreCase = true)) {
                    options[opcode - 30] = null
                }
            }
            39 -> contrast = buffer.readByte() * 25
            40 -> {
                val size = buffer.readUnsignedByte()
                modelColours = IntArray(size)
                replacementColours = IntArray(size)
                for (count in 0 until size) {
                    modelColours[count] = buffer.readUnsignedShort()
                    replacementColours[count] = buffer.readUnsignedShort()
                }
            }
            41 -> {
                val size = buffer.readUnsignedByte()
                modelTexture = ShortArray(size)
                replacementTexture = ShortArray(size)
                for (count in 0 until size) {
                    modelTexture[count] = buffer.readUnsignedShort().toShort()
                    replacementTexture[count] = buffer.readUnsignedShort().toShort()
                }
            }
            42 -> buffer.readUnsignedShort() // full recolour
            61 -> buffer.readUnsignedShort()
            62 -> isRotated = true
            64 -> clipped = false
            65 -> modelSizeX = buffer.readUnsignedShort()
            66 -> modelSizeHeight = buffer.readUnsignedShort()
            67 -> modelSizeY = buffer.readUnsignedShort()
            68 -> mapSceneId = buffer.readUnsignedShort()
            69 -> accessBlockFlag = buffer.readUnsignedByte()
            70 -> offsetX = buffer.readUnsignedShort()
            71 -> offsetHeight = buffer.readUnsignedShort()
            72 -> offsetY = buffer.readUnsignedShort()
            73 -> obstructsGround = true
            74 -> hollow = true
            75 -> supportItems = buffer.readUnsignedByte()
            77, 92 -> {
                varbit = buffer.readUnsignedShortNo65535()
                varp = buffer.readUnsignedShortNo65535()
                finalTransformation = -1
                if (opcode == 92) {
                    finalTransformation = buffer.readUnsignedShortNo65535()
                }
                val size = buffer.readUnsignedByte()
                transformedIds = IntArray(size + 2)
                for (index in 0..size) {
                    transformedIds[index] = buffer.readUnsignedShortNo65535()
                }
                transformedIds[size + 1] = finalTransformation
                return
            }
            78 -> {
                ambientSoundId = buffer.readUnsignedShort()
                ambientSoundDistance = buffer.readUnsignedByte()
                if(!oldVersion)
                    buffer.readUnsignedByte()
            }
            79 -> {
                anInt456 = buffer.readUnsignedShort()
                anInt457 = buffer.readUnsignedShort()
                ambientSoundDistance = buffer.readUnsignedByte()
                if(!oldVersion)
                    buffer.readUnsignedByte()
                val size = buffer.readUnsignedByte()
                anIntArray100 = IntArray(size)
                for (count in 0 until size) {
                    anIntArray100[count] = buffer.readUnsignedShort()
                }
            }
            81 -> contouredGround = buffer.readUnsignedByte() * 256
            82 -> mapIconId = buffer.readUnsignedShort()
            89 -> return
            90 -> return // defer animation change
            91 -> buffer.readUnsignedByte() // sound distance fade curve
            93 -> { // sound fade in curve + duration, fade out curve + duration
                buffer.readUnsignedByte()
                buffer.readUnsignedShort()
                buffer.readUnsignedByte()
                buffer.readUnsignedShort()
            }
            94 -> return // flag (RuneLite "unknown1")
            95 -> buffer.readUnsignedByte() // sound visibility
            96 -> buffer.readUnsignedByte() // raise
            100 -> ItemDefinitionsDecoding.readSubOp(buffer)
            101 -> ItemDefinitionsDecoding.readConditionalOp(buffer)
            102 -> ItemDefinitionsDecoding.readConditionalSubOp(buffer)
            249 -> parameters = buffer.readParameters()
            else -> throw CacheFormat.unknownOpcode("object", id, opcode)
        }
    }
}
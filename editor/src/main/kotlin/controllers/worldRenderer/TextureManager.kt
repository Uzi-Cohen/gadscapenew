package controllers.worldRenderer

import controllers.worldRenderer.helpers.GLC
import controllers.worldRenderer.helpers.Gl
/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
import cache.definitions.TextureDefinition
import cache.loaders.SpriteLoader
import cache.loaders.TextureLoader
import com.google.inject.Inject
import controllers.worldRenderer.helpers.GLUtil
import java.nio.ByteBuffer

class TextureManager @Inject constructor(
    private val spriteLoader: SpriteLoader,
    private val textureLoader: TextureLoader
) {
    fun initTextureArray(gl: Gl): Int {
        if (!allTexturesLoaded()) {
            return -1
        }
        val textures: Array<TextureDefinition?> = textureLoader.getAll()
        val textureArrayId: Int = GLUtil.glGenTexture(gl)
        gl.glBindTexture(GLC.GL_TEXTURE_2D_ARRAY, textureArrayId)
        gl.glTexStorage3D(
            GLC.GL_TEXTURE_2D_ARRAY,
            1,
            GLC.GL_RGBA8,
            TEXTURE_SIZE,
            TEXTURE_SIZE,
            textures.size + (try { utils.HdFloors.images.size } catch (e: Throwable) { 0 })
        )
        gl.glTexParameteri(GLC.GL_TEXTURE_2D_ARRAY, GLC.GL_TEXTURE_MIN_FILTER, GLC.GL_NEAREST)
        gl.glTexParameteri(GLC.GL_TEXTURE_2D_ARRAY, GLC.GL_TEXTURE_MAG_FILTER, GLC.GL_NEAREST)
        gl.glTexParameteri(GLC.GL_TEXTURE_2D_ARRAY, GLC.GL_TEXTURE_WRAP_S, GLC.GL_CLAMP_TO_EDGE)

        // Set brightness to 1.0d to upload unmodified textures to GPU
//		double save = textureProvider.getBrightness();
//		textureProvider.setBrightness(1.0d);
        updateTextures(gl, textureArrayId)
        try { uploadHdTextures(gl, textures.size) } catch (e: Throwable) { e.printStackTrace() }

//		textureProvider.setBrightness(save);
        gl.glActiveTexture(GLC.GL_TEXTURE1)
        gl.glBindTexture(GLC.GL_TEXTURE_2D_ARRAY, textureArrayId)
        gl.glActiveTexture(GLC.GL_TEXTURE0)
        return textureArrayId
    }

    /** 117 HD's floor textures (View > 117 HD floors), in the layers after the cache's own. */
    private fun uploadHdTextures(gl: Gl, first: Int) {
        utils.HdFloors.images.forEachIndexed { i, name ->
            val img = try {
                TextureManager::class.java.getResourceAsStream("/hd/textures/$name.png")?.use { javax.imageio.ImageIO.read(it) }
            } catch (e: Exception) { null } ?: return@forEachIndexed
            // HD's floor textures are all 128 x 128 (no Graphics2D scaling: it can start AWT, which upsets JavaFX)
            if (img.width != TEXTURE_SIZE || img.height != TEXTURE_SIZE) return@forEachIndexed
            val scaled = img
            val pixels = ByteArray(TEXTURE_SIZE * TEXTURE_SIZE * 4)
            var p = 0
            for (y in 0 until TEXTURE_SIZE) for (x in 0 until TEXTURE_SIZE) {
                val argb = scaled.getRGB(x, y)
                pixels[p++] = (argb shr 16).toByte(); pixels[p++] = (argb shr 8).toByte()
                pixels[p++] = argb.toByte(); pixels[p++] = (argb ushr 24).toByte()
            }
            gl.glTexSubImage3D(GLC.GL_TEXTURE_2D_ARRAY, 0, 0, 0, first + i, TEXTURE_SIZE, TEXTURE_SIZE, 1,
                GLC.GL_RGBA, GLC.GL_UNSIGNED_BYTE, ByteBuffer.wrap(pixels))
        }
    }

    fun freeTextureArray(gl: Gl, textureArrayId: Int) {
        GLUtil.glDeleteTexture(gl, textureArrayId)
    }

    /**
     * Check if all textures have been loaded and cached yet.
     *
     * @param textureProvider
     * @return
     */
    private fun allTexturesLoaded(): Boolean {
        val textures: Array<TextureDefinition?> = textureLoader.getAll()
        if (textures.isEmpty()) {
            return false
        }
        for (textureId in textures.indices) {
            val texture: TextureDefinition = textures[textureId]?: continue
            val loaded = texture.loadPixels(0.8, 128, spriteLoader)
            if (!loaded) {
                return false
            }
        }
        return true
    }

    private fun updateTextures(gl: Gl, textureArrayId: Int) {
        val textures: Array<TextureDefinition?> = textureLoader.getAll()
        gl.glBindTexture(GLC.GL_TEXTURE_2D_ARRAY, textureArrayId)
        var cnt = 0
        for (textureId in textures.indices) {
            val texture: TextureDefinition = textures[textureId]?: continue
            val srcPixels: IntArray = textureLoader.get(textureId)?.pixels ?: continue  // this can't happen
            ++cnt
            if (srcPixels.size != TEXTURE_SIZE * TEXTURE_SIZE) {
                // The texture storage is 128x128 bytes, and will only work correctly with the
                // 128x128 textures from high detail mode
                continue
            }
            val pixels = convertPixels(
                srcPixels,
                TEXTURE_SIZE,
                TEXTURE_SIZE,
                TEXTURE_SIZE,
                TEXTURE_SIZE
            )
            val pixelBuffer = ByteBuffer.wrap(pixels)
            gl.glTexSubImage3D(
                GLC.GL_TEXTURE_2D_ARRAY,
                0,
                0,
                0,
                textureId,
                TEXTURE_SIZE,
                TEXTURE_SIZE,
                1,
                GLC.GL_RGBA,
                GLC.GL_UNSIGNED_BYTE,
                pixelBuffer
            )
        }
    }

    /**
     * Animate the given texture
     *
     * @param texture
     * @param diff    Number of elapsed client ticks since last animation
     */
//    fun animate(texture: TextureDefinition, diff: Int) {
//        val pixels: IntArray = texture.pixels ?: return
//        val animationSpeed: Int = texture.getAnimationSpeed()
//        val uvdiff =
//            if (pixels.size == 4096) PERC_64 else PERC_128
//        var u: Float = texture.getU()
//        var v: Float = texture.getV()
//        val offset = animationSpeed * diff
//        val d = offset.toFloat() * uvdiff
//        when (texture.getAnimationDirection()) {
//            1 -> {
//                v -= d
//                if (v < 0f) {
//                    v += 1f
//                }
//            }
//            3 -> {
//                v += d
//                if (v > 1f) {
//                    v -= 1f
//                }
//            }
//            2 -> {
//                u -= d
//                if (u < 0f) {
//                    u += 1f
//                }
//            }
//            4 -> {
//                u += d
//                if (u > 1f) {
//                    u -= 1f
//                }
//            }
//            else -> return
//        }
//        texture.setU(u)
//        texture.setV(v)
//    }

    companion object {
        private const val PERC_64 = 1f / 64f
        private const val PERC_128 = 1f / 128f
        private const val TEXTURE_SIZE = 128
        private fun convertPixels(
            srcPixels: IntArray,
            width: Int,
            height: Int,
            textureWidth: Int,
            textureHeight: Int
        ): ByteArray {
            val pixels = ByteArray(textureWidth * textureHeight * 4)
            var pixelIdx = 0
            var srcPixelIdx = 0
            val offset = (textureWidth - width) * 4
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val rgb = srcPixels[srcPixelIdx++]
                    if (rgb != 0) {
                        pixels[pixelIdx++] = (rgb shr 16).toByte()
                        pixels[pixelIdx++] = (rgb shr 8).toByte()
                        pixels[pixelIdx++] = rgb.toByte()
                        pixels[pixelIdx++] = (-1).toByte()
                    } else {
                        pixelIdx += 4
                    }
                }
                pixelIdx += offset
            }
            return pixels
        }
    }
}
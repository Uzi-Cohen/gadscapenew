package com.gadscape.cache_tool.packing.custom

import mgi.types.draw.sprite.SpriteGroupDefinitions
import java.io.File
import javax.imageio.ImageIO

/**
 * Packs head icons for gadscape.
 *
 * @author Stan van der Bend
 */
object GadScapeCustomHeadIconsPacker {

    @JvmStatic
    fun pack() {
        // pack into pk head icons
        SpriteGroupDefinitions.get(439).apply {
            val sprite = ImageIO.read(File("assets/sprites/head_icons/grey_skull.png"))
            setImage(20, sprite)
            encode()
            pack()
        }
    }
}

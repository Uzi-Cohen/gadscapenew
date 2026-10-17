package com.gadscape.cache_tool.packing.custom

import com.gadscape.cache_tool.packing.assets
import com.gadscape.cache_tool.packing.assetsBase
import mgi.tools.parser.TypeParser

internal object GadScapeCustomCS2Packer {

	@JvmStatic
	fun pack() = assets("assets/osnr/custom_cs2/") {
		TypeParser.packClientScript(id, bytes)
	}

}

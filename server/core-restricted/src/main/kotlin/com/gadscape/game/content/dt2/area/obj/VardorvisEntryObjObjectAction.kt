package com.gadscape.game.content.dt2.area.obj

import com.gadscape.game.content.dt2.area.VardorvisInstance
import com.gadscape.scripts.`object`.actions.ObjectActionScript

class VardorvisEntryObjObjectAction : ObjectActionScript() {
    init {
        48745 {
            if(option == "Enter") {
                val instance = VardorvisInstance.createInstance(player)
                instance.constructRegion()
            }
        }
    }
}
package com.gadscape.game.content.dt2.area.obj

import com.gadscape.game.content.dt2.area.LeviathanInstance
import com.gadscape.scripts.`object`.actions.ObjectActionScript

class LeviathanHandholdEntryObjectAction : ObjectActionScript() {
    init {
        47593 {
            if(option == "Climb") {
                val instance = LeviathanInstance.createInstance(player)
                instance.constructRegion()
            }
        }
    }
}
package com.gadscape.content.group_ironman.widget

import com.gadscape.scripts.interfaces.InterfaceScript
import com.zenyte.game.GameInterface

class SettingsGimOptionsInterface : InterfaceScript() {
    init {
        GameInterface.SETTINGS_GIM_OPTIONS {
            "Back"(5) {
                GameInterface.SETTINGS_GIM.open(player)
            }
        }
    }
}

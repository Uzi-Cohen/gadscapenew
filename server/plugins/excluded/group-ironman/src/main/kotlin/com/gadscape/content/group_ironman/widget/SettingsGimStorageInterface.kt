package com.gadscape.content.group_ironman.widget

import com.gadscape.content.group_ironman.getUnlockedSpaces
import com.gadscape.content.group_ironman.player.finalisedIronmanGroup
import com.gadscape.scripts.interfaces.InterfaceScript
import com.zenyte.game.GameInterface
import com.zenyte.game.model.ui.PaneType
import com.zenyte.game.util.component

/** shared_bank (724) popup layer the storage-unlocks screen opens into: 241 724:29 "popup" (211: 47). */
private const val SHARED_BANK_POPUP = 29

class SettingsGimStorageInterface : InterfaceScript() {
    init {
        GameInterface.SETTINGS_GIM_STORAGE {
            "Close"(8) {
                if (player.interfaceHandler.isPresent(GameInterface.SETTINGS_GIM)) {
                    player.packetDispatcher.closeInterface(730 component 21)
                } else {
                    player.packetDispatcher.closeInterface(724 component SHARED_BANK_POPUP)
                }
            }
            opened {
                val group = finalisedIronmanGroup?: return@opened
                varManager.sendVarInstant(261, group.tasksCompleted[0])
                varManager.sendVarInstant(262, group.tasksCompleted[1])
                varManager.sendVarInstant(263, group.getUnlockedSpaces())
                varManager.sendVarInstant(264, 200)
                varManager.sendVarInstant(265, 1)//how many weeks until storage unlocks
                if (interfaceHandler.isPresent(GameInterface.SETTINGS_GIM)) {
                    interfaceHandler.sendInterface(id, 21, PaneType.IRON_GROUP_SETTINGS, true)
                    packetDispatcher.sendClientScript(3127, 730 component 21, 730 component 1, 1)
                } else {
                    interfaceHandler.sendInterface(id, SHARED_BANK_POPUP, PaneType.IRON_BANK, true)
                    packetDispatcher.sendClientScript(3127, 724 component SHARED_BANK_POPUP, 724 component 1, 1)
                }
            }
        }
    }
}

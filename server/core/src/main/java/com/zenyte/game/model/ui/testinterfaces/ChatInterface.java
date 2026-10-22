package com.zenyte.game.model.ui.testinterfaces;

import com.zenyte.game.GameConstants;
import com.zenyte.game.GameInterface;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.util.AccessMask;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.Setting;

/**
 * @author Tommeh | 27-10-2018 | 19:33
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public class ChatInterface extends Interface {

    @Override
    protected void attach() {
        put(7, "Set game filter");
        put(18, "Set private filter");
        put(19, "Set channel filter");
        put(23, "Set clan filter");
        put(31, "Report button");
    }

    @Override
    public void open(Player player) {
        player.getInterfaceHandler().sendInterface(getInterface());
        // GadScape's Report button opens the teleports. On 211 every op of 162:31 was transmitted (click mask 30); 241
        // transmits only some of them, so the ops are enabled here.
        player.getPacketDispatcher().sendComponentSettings(getInterface(), getComponent("Report button"), -1, -1,
                AccessMask.CLICK_OP1, AccessMask.CLICK_OP2, AccessMask.CLICK_OP3, AccessMask.CLICK_OP4);
    }

    @Override
    protected void build() {
        bind("Set game filter", (player, slotId, itemId, option) -> {
            if (option == 2) {
                player.getSettings().toggleSetting(Setting.GAME_FILTER);
            }
        });
        bind("Set private filter", (player, slotId, itemId, option) -> player.getSocialManager().updateStatus());
        bind("Set channel filter", (player, slotId, itemId, option) -> {
            if (option >= 3 && option <= 5) {
                player.getSettings().setSetting(Setting.CHANNEL_FILTER, option - 3);
            }
        });
        bind("Set clan filter", (player, slotId, itemId, option) -> {
            if (option >= 3 && option <= 5) {
                player.getSettings().setSetting(Setting.CLAN_FILTER, option - 3);
            }
        });
        bind("Report button", (player, slotId, itemId, option) -> {
            GameInterface.TELEPORTS.open(player);
        });
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.CHAT;
    }
}

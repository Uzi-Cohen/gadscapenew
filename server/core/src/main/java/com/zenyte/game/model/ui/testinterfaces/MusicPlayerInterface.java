package com.zenyte.game.model.ui.testinterfaces;

import com.zenyte.game.GameInterface;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.util.AccessMask;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.Setting;
import mgi.types.config.enums.EnumDefinitions;

/**
 * @author Kris | 24/10/2018 15:46
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class MusicPlayerInterface extends Interface {

    private static final int SONGS_ENUM = 812;

    @Override
    protected void attach() {
        // Build 241 music (239) was redesigned (area/shuffle/single modes, playlists); ids matched by name.
        // TODO rev241: the jukebox slot order now comes from the music dbtable, not enum 812 (empty in 241),
        // so slotId -> song index below is only right while the server's music list matches the 241 order.
        put(11, "Play song"); // jukebox (211: 5)
        put(4, "Song name"); // now_playing_text (211: 8)
        put(14, "Auto"); // area: "Area Mode" (211: 9 "Auto")
        put(16, "Manual"); // single: "Single Mode" (211: 11 "Manual")
        put(13, "Loop"); // TODO rev241: no loop button in 241 (13 is the controls layer); left on the 211 id
    }

    @Override
    public void open(Player player) {
        player.getInterfaceHandler().sendInterface(getInterface());
        player.getPacketDispatcher().sendComponentSettings(getInterface(), getComponent("Play song"), 0,
                EnumDefinitions.get(SONGS_ENUM).getSize(), AccessMask.CLICK_OP1, AccessMask.CLICK_OP2);
    }

    @Override
    protected void build() {
        bind("Auto", player -> {
            player.getSettings().setSetting(Setting.AUTO_MUSIC, 1);
            player.getPacketDispatcher().sendComponentText(getInterface(), getComponent("Song name"), "AUTO");
        });
        bind("Manual", player -> {
            player.getSettings().setSetting(Setting.AUTO_MUSIC, 0);
            player.getPacketDispatcher().sendComponentText(getInterface(), getComponent("Song name"), "MANUAL");
        });
        bind("Loop", player -> {
            player.getSettings().toggleSetting(Setting.LOOP_MUSIC);
            player.sendMessage("Music looping now " + (player.getBooleanSetting(Setting.LOOP_MUSIC) ? "enabled." : "disabled."));
        });
        bind("Play song", (player, slotId, itemId, option) -> {
            if (option == 1) {
                if (!player.getMusic().play(slotId + 1)) {
                    player.sendMessage("You have not unlocked this piece of music yet!");
                }
            } else if (option == 2) {
                player.getMusic().sendUnlockHint(slotId + 1);
            }
        });
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.MUSIC_TAB;
    }
}

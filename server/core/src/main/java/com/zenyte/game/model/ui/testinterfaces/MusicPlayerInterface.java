package com.zenyte.game.model.ui.testinterfaces;

import com.gadscape.game.content.kits.KitsTab;
import com.gadscape.game.content.kits.PremadeKits;
import com.zenyte.game.GameInterface;
import com.zenyte.game.model.music.MusicSlots;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.util.AccessMask;
import com.zenyte.game.world.entity.player.MusicHandler;
import com.zenyte.game.world.entity.player.Player;

/**
 * The music tab, build 241 music (239).
 * <p>
 * 241 redesigned the tab: the 211 Auto / Manual / Loop buttons became Area / Shuffle / Single Mode (varp 18 musicplay,
 * the client highlights the mode itself with scripts 315/318) plus Skip Track in shuffle mode, and the jukebox (child
 * {@code i} = the {@code i}-th visible track of the music db table, see {@link MusicSlots}) gained playlists. 211 ids
 * in comments.
 *
 * @author Kris | 24/10/2018 15:46
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class MusicPlayerInterface extends Interface {

    @Override
    protected void attach() {
        put(11, "Play song"); // jukebox (211: 5); ops 1 Play, 2 Unlock hint, 3-5 Add to / Remove from playlist 1-3
        put(4, "Song name"); // now_playing_text (211: 8); ops 2 Unlock hint, 3-5 playlists (script 3936)
        put(14, "Area mode"); // area (211: 9 "Auto")
        put(15, "Shuffle mode"); // shuffle (new in 241)
        put(16, "Single mode"); // single (211: 11 "Manual")
        put(17, "Skip track"); // skip (new in 241)
        // 211's Loop button (13) has no 241 counterpart: Single mode repeats the chosen track (MusicHandler).
    }

    @Override
    public void open(Player player) {
        // The Premade Kits tab takes this slot when the cache has it (KitsPatch); music keeps playing by area.
        if (PremadeKits.INSTANCE.getAvailable()) {
            KitsTab.INSTANCE.open(player);
            return;
        }
        player.getInterfaceHandler().sendInterface(getInterface());
        player.getPacketDispatcher().sendComponentSettings(getInterface(), getComponent("Play song"), 0,
                MusicSlots.get().size(), AccessMask.CLICK_OP1, AccessMask.CLICK_OP2, AccessMask.CLICK_OP3,
                AccessMask.CLICK_OP4, AccessMask.CLICK_OP5);
    }

    @Override
    protected void build() {
        bind("Area mode", player -> player.getMusic().setMode(MusicHandler.MODE_AREA));
        bind("Shuffle mode", player -> player.getMusic().setMode(MusicHandler.MODE_SHUFFLE));
        bind("Single mode", player -> player.getMusic().setMode(MusicHandler.MODE_SINGLE));
        bind("Skip track", player -> {
            if (player.getMusic().getMode() == MusicHandler.MODE_SHUFFLE) {
                player.getMusic().playShuffleTrack();
            }
        });
        bind("Play song", (player, slotId, itemId, option) -> {
            final int slot = slotId + 1;
            if (option == 1) {
                if (!player.getMusic().play(slot)) {
                    player.sendMessage("You have not unlocked this piece of music yet!");
                }
            } else if (option == 2) {
                player.getMusic().sendUnlockHint(slot);
            } else if (option >= 3 && option <= 2 + MusicHandler.PLAYLISTS) {
                player.getMusic().togglePlaylist(option - 2, slot);
            }
        });
        bind("Song name", (player, slotId, itemId, option) -> {
            final int slot = player.getMusic().getCurrentSlot();
            if (slot == -1) {
                return;
            }
            if (option == 2) {
                player.getMusic().sendUnlockHint(slot);
            } else if (option >= 3 && option <= 2 + MusicHandler.PLAYLISTS) {
                player.getMusic().togglePlaylist(option - 2, slot);
            }
        });
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.MUSIC_TAB;
    }
}

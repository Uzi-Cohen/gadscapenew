package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.GameConstants;
import com.zenyte.game.content.clans.ClanManager;
import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import mgi.utilities.StringFormatUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * @author Tommeh | 28 jul. 2018 | 18:55:03
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class UpdateFriendList implements GamePacketEncoder {

    private final Player player;
    private final List<FriendEntry> friends;

    public UpdateFriendList(final Player player, final List<FriendEntry> friends) {
        this.player = player;
        this.friends = friends;
    }

    @Override
    public void log(@NotNull final Player player) {
        log(player, "");
    }

    @Override
    public OutgoingGameMessage toMessage() {
        final java.util.ArrayList<net.rsprot.protocol.game.outgoing.social.UpdateFriendList.Friend> entries = new java.util.ArrayList<>(friends.size());
        for (final UpdateFriendList.FriendEntry entry : friends) {
            final String username = entry.username;
            final String displayname = StringFormatUtil.formatString(entry.username);
            final Optional<Player> friend = World.getPlayer(username);
            int world = 0;
            if (friend.isPresent()) {
                final Player friendPlayer = friend.get();
                if (!friendPlayer.isFinished() && player.getSocialManager().isVisible(friendPlayer)) {
                    world = GameConstants.WORLD_PROFILE.getNumber();
                }
            }
            final int rank = ClanManager.getRank(player, username).getId();
            if (world > 0) {
                entries.add(new net.rsprot.protocol.game.outgoing.social.UpdateFriendList.OnlineFriend(entry.added, displayname, "", world, rank, 0, "", "", 0, 0));
            } else {
                entries.add(new net.rsprot.protocol.game.outgoing.social.UpdateFriendList.OfflineFriend(entry.added, displayname, "", rank, 0, ""));
            }
        }
        return new net.rsprot.protocol.game.outgoing.social.UpdateFriendList(entries);
    }


    public static final class FriendEntry {
        private final String username;
        private final boolean added;

        public FriendEntry(String username, boolean added) {
            this.username = username;
            this.added = added;
        }
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}

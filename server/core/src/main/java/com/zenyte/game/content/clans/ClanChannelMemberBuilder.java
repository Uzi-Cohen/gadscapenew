package com.zenyte.game.content.clans;

import com.zenyte.game.GameConstants;
import com.zenyte.game.world.entity.player.Player;
import mgi.utilities.StringFormatUtil;
import net.rsprot.protocol.game.outgoing.friendchat.UpdateFriendChatChannelSingleUser;
import net.rsprot.protocol.message.OutgoingGameMessage;

import java.util.Map;

/**
 * @author Kris | 28/01/2019 16:00
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public final class ClanChannelMemberBuilder extends ClanChannelBuilder {

    ClanChannelMemberBuilder(final Player player, final Player clanOwner, final boolean add,
                             final ClanChannel channel) {
        super(channel, clanOwner);
        this.player = player;
        this.add = add;
    }

    private final Player player;
    private final boolean add;

    @Override
    protected OutgoingGameMessage build(ClanChannel channel, Player clanOwner) {
        final Map<String, ClanRank> rankedMembers = channel.getRankedMembers();
        final int world = GameConstants.WORLD_PROFILE.getNumber();
        if (add) {
            final int rank = getRank(rankedMembers.get(StringFormatUtil.formatUsername(player.getUsername())), player, clanOwner);
            return new UpdateFriendChatChannelSingleUser(
                    new UpdateFriendChatChannelSingleUser.AddedFriendChatUser(player.getName(), world, rank, ""));
        }
        return new UpdateFriendChatChannelSingleUser(
                new UpdateFriendChatChannelSingleUser.RemovedFriendChatUser(player.getName(), world));
    }

}

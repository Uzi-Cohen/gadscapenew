package com.zenyte.game.content.clans;

import com.zenyte.game.GameConstants;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.utils.TextUtils;
import net.rsprot.protocol.game.outgoing.friendchat.UpdateFriendChatChannelFull;
import net.rsprot.protocol.game.outgoing.friendchat.UpdateFriendChatChannelFullV2;
import net.rsprot.protocol.message.OutgoingGameMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Kris | 28/01/2019 15:24
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public final class ClanChannelFullBuilder extends ClanChannelBuilder {

    ClanChannelFullBuilder(final ClanChannel channel, final Player clanOwner) {
        super(channel, clanOwner);
    }

    @Override
    protected OutgoingGameMessage build(ClanChannel channel, Player clanOwner) {
        final Set<Player> members = channel.getMembers();
        final Map<String, ClanRank> rankedMembers = channel.getRankedMembers();
        final List<UpdateFriendChatChannelFull.FriendChatEntry> entries = new ArrayList<>(members.size());
        for (final Player member : members) {
            entries.add(new UpdateFriendChatChannelFull.FriendChatEntry(member.getName(),
                    GameConstants.WORLD_PROFILE.getNumber(),
                    getRank(rankedMembers.get(member.getUsername()), member, clanOwner), ""));
        }
        return new UpdateFriendChatChannelFullV2(new UpdateFriendChatChannelFullV2.JoinUpdate(channel.getOwner(),
                TextUtils.stringToLong(channel.getPrefix()), channel.getKickRank().getId() - 1, entries));
    }

}

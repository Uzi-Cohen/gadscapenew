package com.zenyte.game.content.clans;

import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege;
import net.rsprot.protocol.message.OutgoingGameMessage;

/**
 * Builds a friends-chat channel update. Since rev 241 the bytes are written by rsprot: {@link #build()}
 * creates the rsprot message, {@link #toMessage()} returns it.
 *
 * @author Kris | 28/01/2019 15:56
 * @author Jire
 */
public abstract class ClanChannelBuilder implements AutoCloseable {

    private final ClanChannel channel;
    private final Player clanOwner;
    private OutgoingGameMessage message;

    ClanChannelBuilder(final ClanChannel channel, final Player clanOwner) {
        this.channel = channel;
        this.clanOwner = clanOwner;
    }

    protected int getRank(final ClanRank rank, final Player member, final Player owner) {
        if (owner.getUsername().equals(member.getUsername())) {
            return 7;
        } else if (member.getPrivilege().eligibleTo(PlayerPrivilege.ADMINISTRATOR)) {
            return 127;
        } else if (rank != null) {
            if (rank == ClanRank.FRIENDS) {
                return owner.getSocialManager().containsFriend(member.getUsername()) ? 0 : -1;
            }
            return rank.getId();
        }
        return owner.getSocialManager().containsFriend(member.getUsername()) ? 0 : -1;
    }

    public final void build() {
        message = build(channel, clanOwner);
    }

    protected abstract OutgoingGameMessage build(ClanChannel channel, Player clanOwner);

    /** The rsprot message created by {@link #build()}. */
    public OutgoingGameMessage toMessage() {
        if (message == null) {
            build();
        }
        return message;
    }

    public ClanChannel getChannel() {
        return channel;
    }

    /** Kept for the call sites; rsprot messages are not reference counted. */
    public void retain() {
    }

    @Override
    public void close() {
    }

}

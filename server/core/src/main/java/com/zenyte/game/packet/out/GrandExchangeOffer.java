package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.content.grandexchange.ExchangeOffer;
import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:15:48
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class GrandExchangeOffer implements GamePacketEncoder {
    @Override
    public void log(@NotNull final Player player) {
        log(player, "Slot: " + offer.getSlot() + ", stage: " + offer.getStage() + ", id: " + offer.getItem().getId() + ", item amount: " + offer.getItem().getAmount() + ", price: " + offer.getPrice() + ", total amount: " + offer.getAmount() + ", total price: " + offer.getTotalPrice());
    }

    private final ExchangeOffer offer;

    public GrandExchangeOffer(ExchangeOffer offer) {
        this.offer = offer;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.misc.player.UpdateStockMarketSlotV2(offer.getSlot(),
                new net.rsprot.protocol.game.outgoing.misc.player.UpdateStockMarketSlotV2.SetStockMarketSlot(offer.getStage(), offer.getItem().getId(),
                        offer.getPrice(), offer.getItem().getAmount(), offer.getAmount(), offer.getTotalPrice()));
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}

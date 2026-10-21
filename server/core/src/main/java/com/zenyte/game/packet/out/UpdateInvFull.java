package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;
import net.rsprot.protocol.common.game.outgoing.inv.InventoryObject;

import com.zenyte.game.item.Item;
import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.Container;
import com.zenyte.game.world.entity.player.container.ItemContainer;
import com.zenyte.game.world.entity.player.container.impl.ContainerType;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:57:47
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server
 *      profile</a>}
 */
public final class UpdateInvFull implements GamePacketEncoder {

	private int key;
	private int interfaceId;
	private int componentId;
	private ItemContainer container;
	private Container newContainer;
	private ContainerType type;

	@Override
	public void log(@NotNull final Player player) {
		if (type == null) {
			return;
		}
		log(player, "Type: " + type.getName());
	}

	public UpdateInvFull(final int key, final int interfaceId, final int componentId, final ItemContainer items) {
		this.interfaceId = interfaceId;
		this.componentId = componentId;
		this.key = key;
		container = items;
	}

	public UpdateInvFull(final int key, final int interfaceId, final int componentId, final Container items) {
		this.interfaceId = interfaceId;
		this.componentId = componentId;
		this.key = key;
		newContainer = items;
	}

	public UpdateInvFull(final Container items) {
		newContainer = items;
	}

	public UpdateInvFull(final Container items, final ContainerType type) {
		newContainer = items;
		this.type = type;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		if (newContainer != null) {
		    final ContainerType type = this.type == null ? newContainer.getType() : this.type;
		    final int combinedId;
		    final int inventoryId;
		    if (key > 0) {
		        combinedId = interfaceId << 16 | componentId;
		        inventoryId = key;
		    } else {
		        combinedId = type.getInterfaceId() << 16 | type.getComponentId();
		        inventoryId = type.getId();
		    }
		    final Container items = newContainer;
		    final net.rsprot.protocol.game.outgoing.inv.UpdateInvFull message = new net.rsprot.protocol.game.outgoing.inv.UpdateInvFull(combinedId, inventoryId, items.getContainerSize(), slot -> {
		        final Item item = items.get(slot);
		        return item == null ? InventoryObject.NULL : InventoryObject.pack(item.getId(), item.getAmount());
		    });
		    Container.AWAITING_RESET_CONTAINERS.add(newContainer);
		    return message;
		}
		final Item[] items = container.getItems();
		return new net.rsprot.protocol.game.outgoing.inv.UpdateInvFull(interfaceId << 16 | componentId, key, items.length, slot -> {
		    final Item item = items[slot];
		    return item == null ? InventoryObject.NULL : InventoryObject.pack(item.getId(), item.getAmount());
		});
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}

}

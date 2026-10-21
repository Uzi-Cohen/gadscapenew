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
 * @author Tommeh | 28 jul. 2018 | 18:59:29
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class UpdateInvPartial implements GamePacketEncoder {

	private int key;
	private int interfaceId;
	private int componentId;
	private int[] slots;
	private ItemContainer items;
	private Container container;

	@Override
	public void log(@NotNull final Player player) {
		if (container == null) {
			return;
		}
		log(player, "Type: " + container.getType().getName());
	}

	public UpdateInvPartial(final int key, final int interfaceId, final int componentId, final ItemContainer items, final int... slots) {
		this.interfaceId = interfaceId;
		this.componentId = componentId;
		this.key = key;
		this.slots = slots;
		this.items = items;
	}

	public UpdateInvPartial(final Container container) {
		this.container = container;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		if (container != null) {
		    final ContainerType type = container.getType();
		    final Container items = container;
		    final java.util.List<Integer> modified = new java.util.ArrayList<>(container.getModifiedSlots());
		    final net.rsprot.protocol.game.outgoing.inv.UpdateInvPartial message = new net.rsprot.protocol.game.outgoing.inv.UpdateInvPartial(type.getInterfaceId() << 16 | type.getComponentId(), type.getId(),
		            new net.rsprot.protocol.game.outgoing.inv.UpdateInvPartial.IndexedObjectProvider(modified.iterator()) {
		                @Override
		                public long provide(final int slot) {
		                    final Item item = items.get(slot);
		                    return item == null ? InventoryObject.pack(slot, -1, 0) : InventoryObject.pack(slot, item.getId(), item.getAmount());
		                }
		            });
		    Container.AWAITING_RESET_CONTAINERS.add(container);
		    return message;
		}
		final Item[] array = items.getItems();
		final java.util.List<Integer> valid = new java.util.ArrayList<>(slots.length);
		for (final int slotId : slots) {
		    if (slotId >= 0 && slotId < array.length) {
		        valid.add(slotId);
		    }
		}
		return new net.rsprot.protocol.game.outgoing.inv.UpdateInvPartial(interfaceId << 16 | componentId, key,
		        new net.rsprot.protocol.game.outgoing.inv.UpdateInvPartial.IndexedObjectProvider(valid.iterator()) {
		            @Override
		            public long provide(final int slot) {
		                final Item item = array[slot];
		                return item == null ? InventoryObject.pack(slot, -1, 0) : InventoryObject.pack(slot, item.getId(), item.getAmount());
		            }
		        });
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}

}

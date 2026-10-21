package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.Position;
import com.zenyte.game.world.Projectile;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:31:35
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class MapProjAnim implements GamePacketEncoder {

	private final Player player;
	private final Projectile projectile;
	private final Position fromPosition;
	private final Position target;
	private final int duration;
	private final int offset;

	@Override
	public void log(@NotNull final Player player) {
		final Location fromTile = fromPosition.getPosition();
		final Location toTile = target.getPosition();
		this.log(player, "Id: " + projectile.getGraphicsId() + ", height: " + projectile.getStartHeight() + " -> " + projectile.getEndHeight() + ", delay: " + projectile.getDelay() + ", angle: " + projectile.getAngle() + ", duration: " + projectile.getDuration() + ", offset: " + projectile.getDistanceOffset() + ", multiplier: " + projectile.getMultiplier() + ", tile: [" + fromTile.getX() + ", " + fromTile.getY() + ", " + fromTile.getPlane() + "] -> [" + toTile.getX() + ", " + toTile.getY() + ", " + toTile.getPlane() + "]");
	}

	public MapProjAnim(final Player player, final Position senderTile, final Position receiverObject, final Projectile projectile, final int duration, final int offset) {
		this.player = player;
		this.projectile = projectile;
		target = receiverObject;
		fromPosition = senderTile;
		this.duration = duration;
		this.offset = offset;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		final Location from = fromPosition.getPosition();
		final Location to = target.getPosition();
		final int delay = projectile.getDelay();
		final int projSpeed = duration != Integer.MIN_VALUE ? duration : projectile.getProjectileDuration(from, target);
		final int index = target instanceof Player ? (-(((Player) target).getIndex() + 1)) : target instanceof NPC ? (((NPC) target).getIndex() + 1) : 0;
		// rev 241: heights are no longer multiplied by 4 client-side and the end coord is absolute.
		return new net.rsprot.protocol.game.outgoing.zone.payload.MapProjAnimV2(projectile.getGraphicsId(), projectile.getStartHeight() * 4, projectile.getEndHeight() * 4,
		        delay, projSpeed, projectile.getAngle(), offset, 0, index, from.getX() & 7, from.getY() & 7,
		        to.getX(), to.getY(), from.getPlane());
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}

}

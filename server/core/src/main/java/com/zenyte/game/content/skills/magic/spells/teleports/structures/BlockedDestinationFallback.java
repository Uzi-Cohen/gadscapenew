package com.zenyte.game.content.skills.magic.spells.teleports.structures;

import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.pathfinding.Flags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Landing tile for a randomised teleport whose destination tile is blocked (a map update put scenery on it, or it was
 * never walkable).
 * <p>
 * Only a tile in the destination's own open area is used: the area is flood-filled on foot from the destination's open
 * sides (a free neighbour with no wall between it and the destination; for a diagonal neighbour, no wall at the shared
 * corner), never crossing a wall, fence, gate, door or blocked tile. The closest tile of that area within {@link #SEARCH_RADIUS} is returned. Nothing is returned when no
 * side is open, when the open area is a small pocket (fewer than {@link #MIN_AREA} tiles), or when the blocked tile
 * splits two separate areas (a pillar in a corridor, a rock in a cave wall), since the intended side can't be told.
 * The caller then fails the teleport as before. Each blocked destination is logged once so the data can be fixed.
 */
public final class BlockedDestinationFallback {
	private static final Logger log = LoggerFactory.getLogger(BlockedDestinationFallback.class);

	/** Maximum distance (Chebyshev) between the blocked destination and the landing tile. */
	public static final int SEARCH_RADIUS = 4;
	/** The flood fill looks a little further so an area that bends outside the search square is still one area. */
	private static final int FLOOD_RADIUS = SEARCH_RADIUS + 4;
	private static final int GRID = FLOOD_RADIUS * 2 + 1;
	/** Smallest open area accepted; smaller ones are pockets a player could be stuck in. */
	public static final int MIN_AREA = 4;

	/** North, east, south, west, then the diagonals north-east, south-east, south-west, north-west (seeds only). */
	private static final int[] DX = {0, 1, 0, -1, 1, 1, -1, -1};
	private static final int[] DY = {1, 0, -1, 0, 1, -1, -1, 1};
	/** Wall flag on the "from" tile for a step in direction i (north, east, south, west). */
	private static final int[] WALL_OUT = {Flags.WALL_NORTH, Flags.WALL_EAST, Flags.WALL_SOUTH, Flags.WALL_WEST};
	/** Wall flag on the "to" tile for the same step. */
	private static final int[] WALL_IN = {Flags.WALL_SOUTH, Flags.WALL_WEST, Flags.WALL_NORTH, Flags.WALL_EAST};

	private static final Set<Integer> reported = ConcurrentHashMap.newKeySet();

	private BlockedDestinationFallback() {
	}

	/**
	 * The landing tile for {@code destination}: the destination itself when it is free, the closest tile of its open area
	 * when it is blocked, or {@code null} when there is no safe tile (see the class comment).
	 */
	public static Location find(final Location destination, final int size) {
		final int plane = destination.getPlane();
		final int baseX = destination.getX() - FLOOD_RADIUS;
		final int baseY = destination.getY() - FLOOD_RADIUS;
		for (int cx = 0; cx <= 1; cx++) {
			for (int cy = 0; cy <= 1; cy++) {
				final int x = baseX + cx * (GRID - 1);
				final int y = baseY + cy * (GRID - 1);
				World.loadRegion((x >> 6) << 8 | y >> 6);
			}
		}
		if (World.isFloorFree(destination, size)) {
			return destination;
		}
		final int[][] component = new int[GRID][GRID];
		final List<List<int[]>> areas = new ArrayList<>(4);
		final int centre = FLOOD_RADIUS;
		for (int dir = 0; dir < DX.length; dir++) {
			final int sx = centre + DX[dir];
			final int sy = centre + DY[dir];
			if (component[sx][sy] != 0) continue;
			final boolean open = dir < 4
					? canStep(plane, baseX, baseY, centre, centre, dir, size)
					: openDiagonal(plane, destination.getX(), destination.getY(), DX[dir], DY[dir], size);
			if (!open) continue;
			final List<int[]> area = flood(plane, baseX, baseY, sx, sy, areas.size() + 1, component, size);
			if (area.size() >= MIN_AREA) {
				areas.add(area);
			} else {
				// Mark pockets with a negative id so a later side doesn't count them again.
				for (final int[] tile : area) component[tile[0]][tile[1]] = -1;
			}
		}
		Location result = null;
		if (areas.size() == 1) {
			int best = Integer.MAX_VALUE;
			for (final int[] tile : areas.get(0)) {
				final int dx = tile[0] - centre;
				final int dy = tile[1] - centre;
				final int chebyshev = Math.max(Math.abs(dx), Math.abs(dy));
				if (chebyshev > SEARCH_RADIUS) continue;
				final int score = chebyshev * 1000 + dx * dx + dy * dy;
				if (score < best) {
					best = score;
					result = new Location(baseX + tile[0], baseY + tile[1], plane);
				}
			}
		}
		if (reported.add(Location.hash(destination.getX(), destination.getY(), plane))) {
			if (result != null) {
				log.warn("Teleport destination {} is blocked; landing on {} instead. Move the destination.", text(destination), text(result));
			} else {
				log.warn("Teleport destination {} is blocked and has no single open area next to it ({} found); the teleport fails. Move the destination.",
						text(destination), areas.size());
			}
		}
		return result;
	}

	private static String text(final Location tile) {
		return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
	}

	private static List<int[]> flood(final int plane, final int baseX, final int baseY, final int startX, final int startY, final int id,
									 final int[][] component, final int size) {
		final List<int[]> area = new ArrayList<>();
		final ArrayDeque<int[]> queue = new ArrayDeque<>();
		component[startX][startY] = id;
		queue.add(new int[] {startX, startY});
		while (!queue.isEmpty()) {
			final int[] tile = queue.poll();
			area.add(tile);
			for (int dir = 0; dir < 4; dir++) {
				final int nx = tile[0] + DX[dir];
				final int ny = tile[1] + DY[dir];
				if (nx < 0 || ny < 0 || nx >= GRID || ny >= GRID || component[nx][ny] != 0) continue;
				if (!canStep(plane, baseX, baseY, tile[0], tile[1], dir, size)) continue;
				component[nx][ny] = id;
				queue.add(new int[] {nx, ny});
			}
		}
		return area;
	}

	/**
	 * Whether the diagonal neighbour (x + dx, y + dy) is free and on the same side of every wall as (x, y): none of the four
	 * wall segments meeting at their shared corner is present, and neither tile has a diagonal wall piece at that corner.
	 */
	private static boolean openDiagonal(final int plane, final int x, final int y, final int dx, final int dy, final int size) {
		final int tx = x + dx;
		final int ty = y + dy;
		final int xOut = dx > 0 ? Flags.WALL_EAST : Flags.WALL_WEST;
		final int xIn = dx > 0 ? Flags.WALL_WEST : Flags.WALL_EAST;
		final int yOut = dy > 0 ? Flags.WALL_NORTH : Flags.WALL_SOUTH;
		final int yIn = dy > 0 ? Flags.WALL_SOUTH : Flags.WALL_NORTH;
		final int cornerOut = dx > 0 ? (dy > 0 ? Flags.CORNER_NORTH_EAST : Flags.CORNER_SOUTH_EAST) : (dy > 0 ? Flags.CORNER_NORTH_WEST : Flags.CORNER_SOUTH_WEST);
		final int cornerIn = dx > 0 ? (dy > 0 ? Flags.CORNER_SOUTH_WEST : Flags.CORNER_NORTH_WEST) : (dy > 0 ? Flags.CORNER_SOUTH_EAST : Flags.CORNER_NORTH_EAST);
		if ((World.getMask(plane, x, y) & (xOut | yOut | cornerOut)) != 0) return false;
		if ((World.getMask(plane, tx, ty) & (xIn | yIn | cornerIn)) != 0) return false;
		// The other two segments at the corner: between the x-neighbour and the diagonal tile, and the y-neighbour and it.
		if ((World.getMask(plane, tx, y) & yOut) != 0) return false;
		if ((World.getMask(plane, x, ty) & xOut) != 0) return false;
		return World.isFloorFree(plane, tx, ty, size);
	}

	/** Whether a player can walk one tile in direction {@code dir} from grid tile (gx, gy): no wall between, target free. */
	private static boolean canStep(final int plane, final int baseX, final int baseY, final int gx, final int gy, final int dir, final int size) {
		final int fromX = baseX + gx;
		final int fromY = baseY + gy;
		final int toX = fromX + DX[dir];
		final int toY = fromY + DY[dir];
		if ((World.getMask(plane, fromX, fromY) & WALL_OUT[dir]) != 0) return false;
		if ((World.getMask(plane, toX, toY) & WALL_IN[dir]) != 0) return false;
		return World.isFloorFree(plane, toX, toY, size);
	}
}

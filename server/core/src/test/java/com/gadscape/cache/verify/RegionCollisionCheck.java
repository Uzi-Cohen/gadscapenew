package com.gadscape.cache.verify;

import com.zenyte.CacheManager;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.pathfinding.Flags;
import com.zenyte.game.world.region.Region;
import com.zenyte.game.world.region.RegionMap;
import mgi.tools.jagcached.CacheFormat;
import mgi.tools.jagcached.MapFiles;
import mgi.tools.jagcached.cache.Cache;
import mgi.types.config.ObjectDefinitions;
import mgi.utilities.ByteBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds collision for every map square of a cache with the server's own code ({@link Region#loadRegionMapOrThrow()},
 * {@link RegionMap}) and checks it:
 * <ul>
 *     <li>no region throws</li>
 *     <li>every blocking type-10/11 loc (object definition with a clip type) has the OBJECT flag on its tile
 *     (on its level, or one level lower under a bridge flag), i.e. terrain, locs and object definitions line up</li>
 *     <li>a few well-known tiles: the Lumbridge spawn tile is walkable</li>
 * </ul>
 * Run with {@code ./gradlew :core:verifyRegions --offline -Dgadscape.cache.dir=/path/to/cache241}.
 */
public final class RegionCollisionCheck {

    public static void main(final String[] args) throws Exception {
        final String dir = args.length > 0 ? args[0] : System.getProperty(CacheManager.CACHE_DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            System.err.println("Usage: -D" + CacheManager.CACHE_DIR_PROPERTY + "=<cache dir>");
            System.exit(2);
        }
        CacheFormat.setStrict(true);
        final long start = System.nanoTime();
        final Cache cache = CacheManager.loadCacheFiles(dir, true);
        new ObjectDefinitions().load();
        System.out.println("Cache " + dir + ", format revision " + CacheFormat.revision() + ", "
                + ObjectDefinitions.definitions.length + " object definitions");

        final List<Integer> regionIds = new ArrayList<>();
        for (int id = 0; id < 0x10000; id++) {
            if (MapFiles.hasRegion(cache, id)) regionIds.add(id);
        }
        int failed = 0;
        for (int id : regionIds) {
            try {
                final Region region = World.getRegion(id);
                region.loadRegionMapOrThrow();
                region.setLoadStage(2);
            } catch (Throwable e) {
                failed++;
                if (failed <= 20) {
                    System.out.println("ERROR region " + id + ": " + e);
                    e.printStackTrace(System.out);
                }
            }
        }

        long tiles = 0, floorBlocked = 0, objectTiles = 0, wallTiles = 0, regionsWithMap = 0;
        for (int id : regionIds) {
            final RegionMap map = World.getRegion(id).getRegionMap(false);
            if (map == null) continue;
            regionsWithMap++;
            final int[][][] masks = map.getMasks();
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 64; x++) {
                    for (int y = 0; y < 64; y++) {
                        final int mask = masks[z][x][y];
                        tiles++;
                        if ((mask & Flags.FLOOR_DECORATION) != 0) floorBlocked++;
                        if ((mask & Flags.OBJECT) != 0) objectTiles++;
                        if ((mask & (Flags.WALL_NORTH | Flags.WALL_EAST | Flags.WALL_SOUTH | Flags.WALL_WEST)) != 0) wallTiles++;
                    }
                }
            }
        }

        // every blocking type 10/11 loc must have set the OBJECT flag on its own tile
        long checkedLocs = 0, missingFlag = 0;
        final List<String> examples = new ArrayList<>();
        for (int id : regionIds) {
            final ByteBuffer locs = MapFiles.locs(cache, id);
            final RegionMap map = World.getRegion(id).getRegionMap(false);
            if (locs == null || map == null) continue;
            final int[][][] masks = map.getMasks();
            int objectId = -1;
            int incr;
            while ((incr = locs.readHugeSmart()) != 0) {
                objectId += incr;
                int location = 0;
                int incr2;
                while ((incr2 = locs.readUnsignedSmart()) != 0) {
                    location += incr2 - 1;
                    final int data = locs.readUnsignedByte();
                    final int type = data >> 2;
                    final int plane = location >> 12;
                    final int x = location >> 6 & 63;
                    final int y = location & 63;
                    if (plane > 3 || (type != 10 && type != 11)) continue;
                    final ObjectDefinitions def = ObjectDefinitions.get(objectId);
                    if (def == null || def.getClipType() == 0) continue;
                    checkedLocs++;
                    final boolean flagged = (masks[plane][x][y] & Flags.OBJECT) != 0
                            || (plane > 0 && (masks[plane - 1][x][y] & Flags.OBJECT) != 0);
                    if (!flagged) {
                        missingFlag++;
                        if (examples.size() < 10) {
                            examples.add("loc " + objectId + " type " + type + " at region " + id + " " + plane + "," + x + "," + y);
                        }
                    }
                }
            }
        }

        // Lumbridge spawn tile
        final Region lumbridge = World.getRegion((3222 >> 6) << 8 | (3218 >> 6));
        final int spawnMask = lumbridge.getMask(0, 3222, 3218);

        System.out.printf("Regions: %d with map files, %d loaded, %d failed%n", regionIds.size(), regionsWithMap, failed);
        System.out.printf("Tiles: %,d; blocked floor %,d; object-blocked %,d; with walls %,d%n", tiles, floorBlocked, objectTiles, wallTiles);
        System.out.printf("Blocking type-10/11 locs checked: %,d, without OBJECT flag on their tile: %,d%n", checkedLocs, missingFlag);
        examples.forEach(e -> System.out.println("  " + e));
        System.out.printf("Lumbridge spawn (3222, 3218, 0) collision mask: 0x%x%n", spawnMask);
        final boolean ok = failed == 0 && missingFlag == 0 && spawnMask == 0 && regionsWithMap == regionIds.size();
        System.out.printf("Finished in %.1f s%n", (System.nanoTime() - start) / 1e9);
        System.out.println(ok ? "RESULT: OK" : "RESULT: FAILED");
        System.exit(ok ? 0 : 1);
    }
}

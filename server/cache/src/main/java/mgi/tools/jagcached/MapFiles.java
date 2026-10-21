package mgi.tools.jagcached;

import com.zenyte.game.world.region.XTEALoader;
import mgi.tools.jagcached.cache.Archive;
import mgi.tools.jagcached.cache.Cache;
import mgi.tools.jagcached.cache.File;
import mgi.tools.jagcached.cache.Group;
import mgi.utilities.ByteBuffer;

/**
 * Finds the terrain and loc data of a 64x64 map square (region) in the maps archive (5), in either layout:
 * <ul>
 *     <li>rev 211 (GadScape's cache): named groups "m{x}_{y}" (terrain, file 0) and "l{x}_{y}" (locs, file 0,
 *     XTEA-encrypted with the keys from {@link XTEALoader})</li>
 *     <li>rev 237+ (the build-241 cache): no names, no XTEA; group id = region id = (x &lt;&lt; 8) | y, with
 *     file 0 = terrain ("m"), 1 = locs ("l"), 2 = environment ("e": lighting, fog, sky...), 3 = "t" and 4 = "w"
 *     (not decoded by anyone yet). Group {@value #WORLD_AREA_GROUP} (square 98,199) is not a map square: its files
 *     are world-area definitions. RuneLite's RegionLoader, OpenRune's GameMapDecoder and zwyz's osrs-cache unpacker
 *     agree; the server only needs files 0 and 1 (see server/cache/REV241.md).</li>
 * </ul>
 * The tile and loc encodings are the same in both revisions (u16 attribute opcodes, u16 overlay ids; smart-delta
 * loc lists). Build 241 appends one flags byte to the terrain: bit 0 means a fifth "underwater" tile level follows
 * (only square 64,73 has one in build 241). The server keeps building collision from levels 0-3 only, and locs on
 * level 4 (coordinate bits above 12) are skipped by Region/DynamicRegion.
 * <p>
 * Returned buffers are fresh views (position 0) so callers on different threads do not share a read position.
 */
public final class MapFiles {

    public static final int TERRAIN_FILE = 0;
    public static final int LOCS_FILE = 1;
    public static final int ENVIRONMENT_FILE = 2;
    /**
     * Group (98 &lt;&lt; 8) | 199 of a region-keyed maps archive holds world-area definitions, not a map square.
     */
    public static final int WORLD_AREA_GROUP = 98 << 8 | 199;

    private MapFiles() {
    }

    public static Archive maps(final Cache cache) {
        return cache.getArchive(ArchiveType.MAPS);
    }

    /**
     * True when the maps archive is keyed by region id (rev 237+).
     */
    public static boolean keyedByRegion(final Cache cache) {
        return CacheFormat.mapsKeyedByRegion(maps(cache));
    }

    /**
     * Whether the cache has terrain for this region, without decompressing anything.
     */
    public static boolean hasRegion(final Cache cache, final int regionId) {
        final Archive maps = maps(cache);
        if (CacheFormat.mapsKeyedByRegion(maps)) {
            return regionId != WORLD_AREA_GROUP && maps.containsGroup(regionId);
        }
        return maps.containsGroupNamed(terrainName(regionId));
    }

    /**
     * Terrain bytes of a region, or null when the region has no map.
     */
    public static ByteBuffer terrain(final Cache cache, final int regionId) {
        final Archive maps = maps(cache);
        if (CacheFormat.mapsKeyedByRegion(maps)) {
            return regionId == WORLD_AREA_GROUP ? null : file(maps.findGroupByID(regionId), TERRAIN_FILE);
        }
        return file(maps.findGroupByName(terrainName(regionId)), 0);
    }

    /**
     * Loc (object) bytes of a region, or null when the region has none (or, in rev 211, cannot be decrypted).
     */
    public static ByteBuffer locs(final Cache cache, final int regionId) {
        final Archive maps = maps(cache);
        if (CacheFormat.mapsKeyedByRegion(maps)) {
            return regionId == WORLD_AREA_GROUP ? null : file(maps.findGroupByID(regionId), LOCS_FILE);
        }
        return file(maps.findGroupByName(locsName(regionId), XTEALoader.getXTEAs(regionId)), 0);
    }

    public static String terrainName(final int regionId) {
        return "m" + (regionId >> 8) + "_" + (regionId & 255);
    }

    public static String locsName(final int regionId) {
        return "l" + (regionId >> 8) + "_" + (regionId & 255);
    }

    private static ByteBuffer file(final Group group, final int fileId) {
        if (group == null) {
            return null;
        }
        final File file = group.findFileByID(fileId);
        if (file == null || file.getData() == null || file.getData().getBuffer().length == 0) {
            return null;
        }
        return new ByteBuffer(file.getData().getBuffer());
    }
}

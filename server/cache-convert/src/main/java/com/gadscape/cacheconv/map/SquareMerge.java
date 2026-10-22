package com.gadscape.cacheconv.map;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Three-way merge of a GadScape custom square onto BASE's square (audit MAP-2 fix 4), tile by tile: the tiles the
 * user changed relative to the square the user's cache started from (rev 225 or vanilla 211; masks computed by the
 * classification, {@link AllowList#masks}) come from the user, every other tile from BASE, so Jagex's 241 changes
 * outside the user's edits are kept. A tile is index level * 4096 + x * 64 + y (the order of the terrain file and the
 * packed position of a placement).
 * <ul>
 *   <li>terrain: the user's tile bytes where the terrain mask is set, BASE's elsewhere, then BASE's trailing flags
 *       byte and underwater data;</li>
 *   <li>locs: the user's placements on tiles in either mask (the user's design there, including a terrain edit),
 *       BASE's placements on every other tile.</li>
 * </ul>
 */
public final class SquareMerge {
    private SquareMerge() {}

    public record Result(byte[] terrain, LocFile locs, int userTiles, int userPlacements, int basePlacements, int baseDropped) {}

    /** BASE placements on user tiles that are not also user placements there (241 content the user's edit replaces). */
    public static Result merge(byte[] user211, LocFile userLocs, byte[] base241, LocFile baseLocs, BitSet terrainMask, BitSet locMask) {
        int[] ou = Terrain.offsets(user211), ob = Terrain.offsets(base241);
        if (ou == null || ob == null) throw new IllegalArgumentException("terrain does not parse");
        ByteArrayOutputStream t = new ByteArrayOutputStream(base241.length + 64);
        int userTiles = 0;
        for (int i = 0; i < 16384; i++) {
            if (terrainMask.get(i)) { t.write(user211, ou[i], ou[i + 1] - ou[i]); userTiles++; }
            else t.write(base241, ob[i], ob[i + 1] - ob[i]);
        }
        t.write(base241, ob[16384], base241.length - ob[16384]);
        BitSet userTile = (BitSet) terrainMask.clone();
        userTile.or(locMask);
        List<LocFile.Loc> out = new ArrayList<>();
        int fromBase = 0, dropped = 0, fromUser = 0;
        for (LocFile.Loc l : baseLocs.locs) {
            if (!userTile.get(l.pos())) { out.add(l); fromBase++; }
            else if (!LocFile.contains(userLocs, l.id(), l.pos(), l.attr())) dropped++;
        }
        for (LocFile.Loc l : userLocs.locs) if (userTile.get(l.pos())) { out.add(l); fromUser++; }
        return new Result(t.toByteArray(), new LocFile(out), userTiles, fromUser, fromBase, dropped);
    }
}

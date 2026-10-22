package com.gadscape.qa;

import com.zenyte.game.GameConstants;
import com.zenyte.game.util.Direction;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TEST BUILD ONLY: walk-around preview of The Fractured Archive (Raids 4) on a cache that carries its 2746 world
 * content. Teleports to the entrance, lobby, every room and the vault, and can place each boss in its room as a
 * display-only NPC (the raid itself is not implemented yet). Usable on development worlds, or by developers.
 */
public final class ArchivePreview {

    private static final Logger log = LoggerFactory.getLogger(ArchivePreview.class);

    private static final Map<String, Location> PLACES = new LinkedHashMap<>();

    static {
        PLACES.put("entrance", new Location(4063, 4557, 0));
        PLACES.put("lobby", new Location(4126, 5508, 0));
        PLACES.put("guardian", new Location(4127, 5590, 0));
        PLACES.put("ezekiel", new Location(4063, 5532, 0));
        PLACES.put("bork", new Location(4186, 5599, 0));
        PLACES.put("moia", new Location(4066, 5663, 0));
        PLACES.put("dreadnoth", new Location(4185, 5663, 0));
        PLACES.put("dagonhai", new Location(4072, 5601, 0));
        PLACES.put("xarth", new Location(4184, 5535, 0));
        PLACES.put("ocellus", new Location(4127, 5664, 1));
        PLACES.put("vault", new Location(4127, 5775, 2));
    }

    /** Boss display spawns: npc id, south-west tile, facing. */
    private static final Object[][] BOSSES = {
            {16657, new Location(4125, 5597, 0), Direction.SOUTH},  // Inert Guardian
            {16652, new Location(4062, 5534, 0), Direction.SOUTH},  // Ezekiel
            {16640, new Location(4194, 5597, 0), Direction.WEST},   // Bork
            {16641, new Location(4190, 5598, 0), Direction.WEST},   // The Smorks
            {16667, new Location(4062, 5662, 0), Direction.EAST},   // Moia
            {16665, new Location(4189, 5661, 0), Direction.WEST},   // Dreadnoth the Jellied
            {16643, new Location(4069, 5599, 0), Direction.EAST},   // Dagon'hai Elder
            {16645, new Location(4062, 5602, 0), Direction.EAST},   // Dagon'hai Monk
            {16647, new Location(4062, 5596, 0), Direction.EAST},   // Dagon'hai Monk
            {16684, new Location(4190, 5534, 0), Direction.WEST},   // Xarth
            {16673, new Location(4126, 5668, 1), Direction.SOUTH},  // Ocellus
    };

    private static final List<NPC> spawned = new ArrayList<>();

    private ArchivePreview() {
    }

    public static void handle(final Player p, final String[] args) {
        if (!GameConstants.WORLD_PROFILE.isDevelopment() && !p.getPrivilege().inherits(PlayerPrivilege.DEVELOPER)) {
            p.sendMessage("The Fractured Archive preview is only available on test worlds.");
            return;
        }
        final String what = args.length == 0 ? "entrance" : args[0].toLowerCase(Locale.ROOT);
        if (what.equals("bosses")) {
            spawnBosses(p);
            return;
        }
        final Location to = PLACES.get(what);
        if (to == null) {
            p.sendMessage("Usage: ::archive [" + String.join("|", PLACES.keySet()) + "|bosses]");
            return;
        }
        p.setLocation(new Location(to));
        p.sendMessage("The Fractured Archive (preview): " + what + ". Bosses do not fight yet; ::archive bosses places them in their rooms.");
    }

    private static void spawnBosses(final Player p) {
        for (final NPC n : spawned) {
            if (!n.isFinished()) n.finish();
        }
        spawned.clear();
        int ok = 0;
        for (final Object[] b : BOSSES) {
            final int id = (int) b[0];
            try {
                final NPC n = new NPC(id, new Location((Location) b[1]), (Direction) b[2], 0) {
                    @Override
                    public boolean canAttack(final Player source) {
                        source.sendMessage("Preview only: this boss can't fight yet.");
                        return false;
                    }

                    @Override
                    public boolean isAttackable() {
                        return false;
                    }
                };
                n.setSpawned(true);
                n.spawn();
                spawned.add(n);
                ok++;
            } catch (final Throwable e) {
                log.error("Fractured Archive preview: could not spawn npc {}", id, e);
            }
        }
        p.sendMessage("Placed " + ok + " of " + BOSSES.length + " Fractured Archive bosses in their rooms (display only).");
    }
}

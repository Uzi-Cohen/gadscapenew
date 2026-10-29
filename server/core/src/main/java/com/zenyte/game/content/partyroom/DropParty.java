package com.zenyte.game.content.partyroom;

import com.zenyte.game.task.WorldTask;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.Container;
import com.zenyte.game.world.entity.player.container.ContainerPolicy;
import com.zenyte.game.world.entity.player.container.impl.ContainerType;
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The staff drop party (::dropparty, Managers and Sr. Developers): its own chest, separate from the Falador one, and
 * balloons that carry that chest's items, dropped on the free floor tiles around wherever the staff member stood when
 * they pressed Party. Popping and rolling work as in the party room ({@link FaladorPartyRoom#pop},
 * {@link FaladorPartyRoom#roll(PartyBalloon, Container, Runnable)}).
 */
public final class DropParty {
    /**
     * How far from the staff member the balloons land.
     */
    private static final int RADIUS = 8;
    /**
     * Balloons queued by one press of Party, as a pull of the party room lever.
     */
    private static final int BALLOONS_PER_PARTY = 200;

    /**
     * The staff chest; shown in the chest's inventory 91 while the staff copy of the chest is open.
     */
    private static final Container container = new Container(ContainerPolicy.ALWAYS_STACK, ContainerType.FALADOR_PARTY_CHEST_PUBLIC_DEPOSIT, Optional.empty());
    private static final Set<String> viewingPlayers = new ObjectOpenHashSet<>();
    private static final Int2ObjectMap<PartyBalloon> balloons = new Int2ObjectOpenHashMap<>();
    private static Location centre;
    private static int queuedBalloons;
    private static boolean running;

    private DropParty() {
    }

    /**
     * Managers (DEVELOPER) and Sr. Developers only.
     */
    public static boolean mayUse(@NotNull final Player player) {
        final PlayerPrivilege privilege = player.getPrivilege();
        return privilege == PlayerPrivilege.DEVELOPER || privilege == PlayerPrivilege.TRUE_DEVELOPER;
    }

    @NotNull
    static Container getContainer() {
        return container;
    }

    static void startViewing(@NotNull final Player player) {
        viewingPlayers.add(player.getUsername());
    }

    static void stopViewing(@NotNull final Player player) {
        viewingPlayers.remove(player.getUsername());
    }

    static void refreshViewers() {
        viewingPlayers.forEach(username -> World.getPlayer(username).ifPresent(container::refresh));
    }

    static void roll(@NotNull final PartyBalloon balloon) {
        FaladorPartyRoom.roll(balloon, container, DropParty::refreshViewers);
    }

    /**
     * Party: balloons start dropping around {@code player} and keep coming until the chest is empty or the queue runs out.
     */
    static void start(@NotNull final Player player) {
        if (container.isEmpty()) {
            player.sendMessage("The staff chest is empty.");
            return;
        }
        centre = new Location(player.getLocation());
        queuedBalloons = BALLOONS_PER_PARTY;
        player.sendMessage("The drop party has begun!");
        if (running) {
            return;
        }
        running = true;
        WorldTasksManager.schedule(new WorldTask() {
            @Override
            public void run() {
                if (!balloons.isEmpty()) {
                    balloons.int2ObjectEntrySet().removeIf(entry -> !entry.getValue().alive());
                }
                if (container.isEmpty()) {
                    queuedBalloons = 0;
                }
                if (queuedBalloons > 0) {
                    spawnBalloons();
                }
                if (queuedBalloons <= 0 && balloons.isEmpty()) {
                    running = false;
                    stop();
                }
            }
        }, 0, 0);
    }

    private static void spawnBalloons() {
        final List<Location> free = new ObjectArrayList<>();
        int tiles = 0;
        for (int x = centre.getX() - RADIUS; x <= centre.getX() + RADIUS; x++) {
            for (int y = centre.getY() - RADIUS; y <= centre.getY() + RADIUS; y++) {
                final Location tile = new Location(x, y, centre.getPlane());
                if (!World.isFloorFree(tile, 1) || World.getRegion(tile.getRegionId()).containsObjectWithEqualSlot(0, x & 63, y & 63, 10)) {
                    continue;
                }
                tiles++;
                if (!balloons.containsKey(tile.getPositionHash())) {
                    free.add(tile);
                }
            }
        }
        if (tiles == 0) {
            queuedBalloons = 0;
            return;
        }
        // as the party room: fewer new balloons the fuller the floor is, none above 85% of it
        final float capacity = (float) balloons.size() / (tiles * 0.85F);
        if (capacity >= 1) {
            return;
        }
        final int amount = Math.min(Utils.random(3 - (int) (capacity * 4), 5 - (int) (capacity * 4)), queuedBalloons);
        for (int i = 0; i < amount && !free.isEmpty(); i++) {
            final Location tile = free.remove(Utils.random(free.size() - 1));
            final PartyBalloon balloon = new PartyBalloon(Utils.random(115, 122), tile.getX(), tile.getY(), tile.getPlane(), true);
            balloon.alive();
            balloons.put(balloon.getPositionHash(), balloon);
            queuedBalloons--;
        }
    }
}

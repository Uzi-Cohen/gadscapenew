package com.gadscape.tools.soak;

import com.gadscape.game.world.WorldEvent;
import com.gadscape.game.world.WorldHooks;
import com.gadscape.game.world.entity.player.FakePlayer;
import com.google.common.eventbus.Subscribe;
import com.zenyte.game.item.Item;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.World;
import com.zenyte.game.world.WorldThread;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.UpdateFlag;
import com.zenyte.game.world.entity.player.GameCommands;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.action.combat.PlayerCombat;
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege;
import com.zenyte.plugins.events.ServerLaunchEvent;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-process soak / load test (CRIT-07). Spawns server-side bot players (FakePlayer: no network, never saved) that
 * walk around and fight each other where PvP is allowed, and records world-cycle times so the owner can see how the
 * world thread copes before a PvP launch. Nothing happens unless a developer runs the command.
 * <pre>
 * ::soak 300 [radius] [fight|walk]   spawn 300 bots around you (radius default 15, fight default)
 * ::soak stats                       tick p50/p95/p99/max, players, NPCs, heap, GC since the start
 * ::soak stop                        remove all bots and print the final report
 * </pre>
 * Bots copy your equipment and have 99 combat stats; they are healed every few ticks so nobody dies (no loot spam).
 * Also log a line every minute while bots exist ("soak:" in the server log). What it does not measure: network
 * bandwidth and rsprot encoding for real clients (bots have no session) - pair it with a few real clients standing in
 * the crowd. For realistic numbers run the server with -Dnet.rsprot.protocol.internal.development=false.
 */
public final class SoakTest {
    private static final Logger log = LoggerFactory.getLogger(SoakTest.class);
    private static final int WINDOW = 6000; // last hour of ticks
    private static final long[] cycleMicros = new long[WINDOW];
    private static int samples;
    private static int cursor;
    private static final List<Bot> bots = new ArrayList<>();
    private static long startGcCount;
    private static long startGcMillis;
    private static int ticks;

    private record Bot(FakePlayer player, Location anchor, int radius, boolean fight) {
    }

    private SoakTest() {
    }

    @Subscribe
    public static void onServerLaunch(final ServerLaunchEvent event) {
        final WorldHooks hooks = event.getWorldThread().getHooks();
        hooks.register(WorldEvent.Tick.class, tick -> onTick());
        new GameCommands.Command(PlayerPrivilege.TRUE_DEVELOPER, "soak",
                "Load test bots: ::soak <count> [radius] [fight|walk], ::soak stats, ::soak stop", (player, args) -> {
            if (args.length == 0) {
                player.sendMessage("::soak <count> [radius] [fight|walk] | ::soak stats | ::soak stop");
                return;
            }
            switch (args[0].toLowerCase()) {
                case "stop" -> {
                    report(player);
                    stop();
                    player.sendMessage("Soak bots removed.");
                }
                case "stats" -> report(player);
                default -> {
                    final int count = Math.max(1, Math.min(2000, Integer.parseInt(args[0])));
                    final int radius = args.length > 1 ? Math.max(2, Integer.parseInt(args[1])) : 15;
                    final boolean fight = args.length <= 2 || !args[2].equalsIgnoreCase("walk");
                    start(player, count, radius, fight);
                }
            }
        });
    }

    private static void start(final Player owner, final int count, final int radius, final boolean fight) {
        if (bots.isEmpty()) {
            Arrays.fill(cycleMicros, 0);
            samples = 0;
            cursor = 0;
            ticks = 0;
            startGcCount = gcCount();
            startGcMillis = gcMillis();
        }
        final Location centre = new Location(owner.getLocation());
        for (int i = 0; i < count; i++) {
            final Location tile = randomTile(centre, radius);
            final FakePlayer bot = new FakePlayer("soak" + (bots.size() + 1));
            bot.setDefaultSettings();
            bot.forceLocation(tile);
            World.addPlayer(bot);
            bot.loadMapRegions(true);
            bot.setLastLoadedMapRegionTile(new Location(tile));
            bot.afterLoadMapRegions();
            bot.putBooleanAttribute("registered", true);
            for (int skill = 0; skill <= 6; skill++) {
                bot.getSkills().setSkill(skill, 99, 13034431.0);
            }
            for (final Int2ObjectMap.Entry<Item> entry : owner.getEquipment().getContainer().getItems().int2ObjectEntrySet()) {
                final Item item = entry.getValue();
                if (item != null) {
                    bot.getEquipment().set(entry.getIntKey(), new Item(item.getId(), item.getAmount(), item.getAttributesCopy()));
                }
            }
            bot.setInitialized(true);
            bot.getCombatDefinitions().refresh();
            bot.getBonuses().update();
            bot.getUpdateFlags().flag(UpdateFlag.APPEARANCE);
            bots.add(new Bot(bot, centre, radius, fight));
        }
        owner.sendMessage("Spawned " + count + " soak bots (" + bots.size() + " total, " + (fight ? "fighting" : "walking") + ").");
        log.info("soak: spawned {} bots at {} radius {} fight {}", count, centre, radius, fight);
    }

    private static void stop() {
        for (final Bot bot : bots) {
            try {
                World.removePlayer(bot.player());
            } catch (final Exception e) {
                log.warn("Could not remove soak bot {}", bot.player().getUsername(), e);
            }
        }
        bots.clear();
    }

    private static void onTick() {
        final long last = WorldThread.LAST_CYCLE_NANOS;
        if (bots.isEmpty()) {
            return;
        }
        cycleMicros[cursor] = last / 1000L;
        cursor = (cursor + 1) % WINDOW;
        samples = Math.min(WINDOW, samples + 1);
        ticks++;
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        for (final Bot bot : bots) {
            final FakePlayer player = bot.player();
            if (player.isNulled() || player.isFinished()) continue;
            try {
                if (ticks % 5 == 0) {
                    player.heal(99);
                }
                if (bot.fight() && player.isCanPvp() && !player.isUnderCombat() && random.nextInt(10) == 0) {
                    final Player target = nearbyBot(player);
                    if (target != null) {
                        PlayerCombat.attackEntity(player, target, null);
                        continue;
                    }
                }
                if (!player.isUnderCombat() && !player.hasWalkSteps() && random.nextInt(8) == 0) {
                    final Location tile = randomTile(bot.anchor(), bot.radius());
                    player.addWalkSteps(tile.getX(), tile.getY(), 25, true);
                }
                if (random.nextInt(200) == 0) {
                    player.setForceTalk(new com.zenyte.game.world.entity.ForceTalk("Soak test " + Utils.random(999)));
                }
            } catch (final Exception e) {
                log.debug("soak bot tick failed", e);
            }
        }
        if (ticks % 100 == 0) {
            log.info("soak: {}", summary());
        }
    }

    private static Player nearbyBot(final Player player) {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 6; attempt++) {
            final Player other = bots.get(random.nextInt(bots.size())).player();
            if (other != player && !other.isNulled() && other.isCanPvp() && other.getPlane() == player.getPlane()
                    && player.getLocation().withinDistance(other.getLocation(), 8)) {
                return other;
            }
        }
        return null;
    }

    private static Location randomTile(final Location centre, final int radius) {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        return new Location(centre.getX() + random.nextInt(-radius, radius + 1), centre.getY() + random.nextInt(-radius, radius + 1), centre.getPlane());
    }

    private static void report(final Player player) {
        final String text = summary();
        player.sendMessage(text);
        log.info("soak: {}", text);
    }

    private static String summary() {
        final long[] sorted = Arrays.copyOf(cycleMicros, samples);
        Arrays.sort(sorted);
        final Runtime runtime = Runtime.getRuntime();
        final long heapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        return String.format("bots=%d players=%d npcs=%d ticks=%d cycle ms p50=%.1f p95=%.1f p99=%.1f max=%.1f (budget 600) heap=%dMB gc=%d (%dms)",
                bots.size(), World.getPlayers().size(), World.getNPCs().size(), samples,
                pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), sorted.length == 0 ? 0D : sorted[sorted.length - 1] / 1000D,
                heapMb, gcCount() - startGcCount, gcMillis() - startGcMillis);
    }

    private static double pct(final long[] sorted, final int percentile) {
        if (sorted.length == 0) return 0D;
        final int index = Math.min(sorted.length - 1, (int) Math.ceil(percentile / 100D * sorted.length) - 1);
        return sorted[Math.max(0, index)] / 1000D;
    }

    private static long gcCount() {
        long total = 0;
        for (final GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, bean.getCollectionCount());
        }
        return total;
    }

    private static long gcMillis() {
        long total = 0;
        for (final GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, bean.getCollectionTime());
        }
        return total;
    }
}

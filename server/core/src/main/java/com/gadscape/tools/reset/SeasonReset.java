package com.gadscape.tools.reset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Offline season / PvP reset for GadScape (PVP-30). It NEVER runs by itself: the owner runs it by hand with the game
 * server stopped. Default is a dry run that only prints what it would do; {@code --apply} makes the changes after
 * archiving the whole data directory.
 *
 * <pre>
 * java -cp server.jar:libs/* com.gadscape.tools.reset.SeasonReset --data run/data --mode pvp|economy|full [--apply]
 *      [--season "Season 2"] [--port 43594] [--archive DIR]
 * </pre>
 *
 * Modes (each includes the one before it):
 * <ul>
 *     <li><b>pvp</b>: every character keeps skills, items and progress, but loses PvP stats and PvP currency:
 *     wilderness/PvP kills, deaths and streaks, Bounty Hunter points/kills/deaths/streak/skips/last target, black
 *     skull, duel wins/losses, the killstreak (anti-farm) log, loot key statistics, and the PvP hiscores (the "PVP"
 *     category of every characters-hiscores file); blood money, BH emblems and target teleports are removed from every
 *     item container. Unclaimed loot is real loot, not PvP currency, so loot keys and the old 211 chest loot
 *     (lootkeySettings.currentItemsInChest) are KEPT; only PvP currency inside them is removed (a key left empty is
 *     removed). The loot key enchantment itself is kept.</li>
 *     <li><b>economy</b>: pvp + every item container is emptied (inventory, worn, bank, pouches, looting bag,
 *     storage, gravestone/Death's office, seed vault, gauntlet storage, ...), every loot key and the old chest loot
 *     are deleted with their contents, and Grand Exchange history; global GE
 *     offers, middleman trades, the ToB global chest and the well are reset. Skills, quests, diaries, collection log,
 *     ranks and settings stay.</li>
 *     <li><b>full</b>: complete wipe of all player data: characters, hiscores, hardware ids, GE offers (prices are
 *     kept), clans, group ironman groups, middleman, well, contests, polls, raid/ToB/Nex/Nightmare/Inferno records,
 *     invited players. Game data (items, maps, enums, drops, ...) is untouched.</li>
 * </ul>
 * Website accounts, donations and votes live in the website database and are NOT touched (reset them there).
 * After --apply a {@code season.json} marker with the season name and time is written into the data directory.
 */
public final class SeasonReset {

    enum Mode { PVP, ECONOMY, FULL }

    /** Persistent attributes holding PvP statistics (see PlayerAttributes.kt, WildernessArea, Killstreaks, Duel). */
    static final List<String> PVP_ATTRIBUTES = List.of(
            "WildernessKills", "WildernessDeaths", "pvp-kills", "pvp-deaths", "current-killstreak",
            "bountyHunterPoints", "bountyHunterKills", "bountyHunterDeaths", "bountyHunterKillstreak",
            "bountyHunterSkipCount", "bountyHunterLastTarget", "blackSkulled", "DuelsWon", "DuelsLost");

    /** PvP currency / PvP-only items removed in every mode (also from inside loot keys and the old chest loot). */
    static final Set<Integer> PVP_ITEMS = new HashSet<>(List.of(
            13307, // blood money
            12746, 12748, 12749, 12750, 12751, 12752, 12753, 12754, 12755, 12756, // mysterious emblems (old BH)
            24277, 24279, 24281, 24283, 24285, // mysterious emblems (2020)
            24565, 24567, 24569, 24571, 24573, 24575, 24577, 24579, 24581, 24583, // antique emblems (BH2020)
            24336 // target teleport
    ));

    /**
     * Loot keys (wildy_loot_key0..4). pvp mode keeps them (they hold real, unclaimed loot) and only strips PvP
     * currency from their contents; economy mode deletes them with their contents.
     */
    static final Set<Integer> LOOT_KEYS = new HashSet<>(List.of(26651, 26652, 26653, 26654, 26655));
    /** Loot key item attribute holding the loot as a JSON string (LootkeyConstants.LOOT_KEY_ITEM_LOOT_ITEMS_ATTR). */
    static final String LOOT_KEY_ITEMS_ATTR = "loot-key-loot-items";

    /** Hiscores category keys (HiscoresCategory names) cleared in pvp and economy modes. */
    static final List<String> PVP_HISCORE_CATEGORIES = List.of("PVP");

    /** Character keys whose "container.items" are emptied in economy mode. */
    static final List<String> ECONOMY_CONTAINERS = List.of(
            "inventory", "equipment", "bank", "lootingBag", "runePouch", "secondaryRunePouch", "seedBox", "herbSack",
            "bonePouch", "dragonhidePouch", "gemBag", "retrievalService", "privateStorage", "gravestone", "seedVault",
            "storageRoom");

    /** Data-dir entries deleted in full mode (paths relative to --data). Directories are emptied, files deleted. */
    static final List<String> FULL_WIPE = List.of(
            "characters", "characters-hiscores", "characters-hardware", "grandexchange/offers.json",
            "grandexchange/banned_offers.json", "clans.json", "gim", "middleman", "well.json", "contests", "polls",
            "raids/scores.json", "tob", "tob_statistics.json", "nex_statistics.json", "nightmare_statistics.json",
            "inferno completions.json", "invitedplayers.json");

    private final Path data;
    private final Mode mode;
    private final boolean apply;
    private final String season;
    private final int port;
    private final Path archiveRoot;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    private int changedFiles;

    private SeasonReset(Path data, Mode mode, boolean apply, String season, int port, Path archiveRoot) {
        this.data = data;
        this.mode = mode;
        this.apply = apply;
        this.season = season;
        this.port = port;
        this.archiveRoot = archiveRoot;
    }

    public static void main(String[] args) throws Exception {
        Path data = null;
        Mode mode = null;
        boolean apply = false;
        String season = "New season";
        int port = 43594;
        Path archive = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data" -> data = Paths.get(args[++i]);
                case "--mode" -> mode = Mode.valueOf(args[++i].toUpperCase(Locale.ROOT));
                case "--apply" -> apply = true;
                case "--season" -> season = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--archive" -> archive = Paths.get(args[++i]);
                default -> usage("Unknown argument: " + args[i]);
            }
        }
        if (data == null || mode == null) usage("--data and --mode are required.");
        if (!Files.isDirectory(data.resolve("characters")) && mode != Mode.FULL) {
            usage("No characters/ folder in " + data.toAbsolutePath() + " - is --data the server's data directory?");
        }
        if (archive == null) archive = data.toAbsolutePath().getParent().resolve("reset-archives");
        new SeasonReset(data.toAbsolutePath(), mode, apply, season, port, archive).run();
    }

    private static void usage(String error) {
        System.err.println(error);
        System.err.println("usage: SeasonReset --data <server data dir> --mode pvp|economy|full [--apply] [--season NAME] [--port 43594] [--archive DIR]");
        System.exit(2);
    }

    private void run() throws IOException {
        System.out.println("GadScape season reset: mode=" + mode + (apply ? " (APPLY)" : " (dry run - nothing is changed; add --apply)"));
        System.out.println("Data directory: " + data);
        if (apply) {
            if (serverRunning()) {
                System.err.println("A game server is listening on port " + port + ". Stop it first (players would save over the reset).");
                System.exit(1);
            }
            final Path archive = archiveRoot.resolve("data-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            System.out.println("Archiving " + data + " -> " + archive + " ...");
            copyTree(data, archive);
            System.out.println("Archive complete.");
        }
        if (mode == Mode.FULL) {
            fullWipe();
        } else {
            resetCharacters();
            resetPvpHiscores();
            if (mode == Mode.ECONOMY) {
                resetGlobalEconomy();
            }
        }
        if (apply) {
            final JsonObject marker = new JsonObject();
            marker.addProperty("season", season);
            marker.addProperty("mode", mode.name());
            marker.addProperty("resetAt", LocalDateTime.now().toString());
            Files.writeString(data.resolve("season.json"), gson.toJson(marker), StandardCharsets.UTF_8);
        }
        System.out.println((apply ? "Done. " : "Dry run done. ") + changedFiles + " file(s) " + (apply ? "changed" : "would change") + ".");
        System.out.println("Remember: website accounts, donations and votes are reset in the website database, not here.");
    }

    private boolean serverRunning() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ characters */

    private void resetCharacters() throws IOException {
        final Path dir = data.resolve("characters");
        try (Stream<Path> files = Files.list(dir)) {
            for (final Path file : (Iterable<Path>) files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted()::iterator) {
                final JsonObject root;
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    root = JsonParser.parseReader(reader).getAsJsonObject();
                } catch (Exception e) {
                    System.err.println("  SKIP unreadable " + file.getFileName() + ": " + e.getMessage());
                    continue;
                }
                final List<String> changes = new ArrayList<>();
                resetPvp(root, changes);
                if (mode == Mode.ECONOMY) {
                    resetEconomy(root, changes);
                }
                if (changes.isEmpty()) continue;
                changedFiles++;
                System.out.println("  " + file.getFileName() + ": " + String.join(", ", changes));
                if (apply) {
                    final Path tmp = file.resolveSibling(file.getFileName() + ".reset.tmp");
                    try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                        gson.toJson(root, writer);
                    }
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
            }
        }
    }

    private void resetPvp(final JsonObject root, final List<String> changes) {
        final JsonObject attributes = root.has("attributes") && root.get("attributes").isJsonObject() ? root.getAsJsonObject("attributes") : null;
        if (attributes != null) {
            int removed = 0;
            for (final String key : PVP_ATTRIBUTES) {
                if (attributes.remove(key) != null) removed++;
            }
            if (removed > 0) changes.add(removed + " PvP stats");
        }
        if (root.has("killstreakLog")) {
            root.add("killstreakLog", JsonParser.parseString("{\"entries\":[]}"));
            changes.add("killstreak log");
        }
        if (root.has("lootkeySettings") && root.get("lootkeySettings").isJsonObject()) {
            final JsonObject settings = root.getAsJsonObject("lootkeySettings");
            settings.addProperty("keysClaimed", 0);
            settings.addProperty("totalValueClaimed", 0);
            settings.addProperty("destroyedValue", 0);
            final JsonElement chest = settings.get("currentItemsInChest");
            if (chest != null && chest.isJsonObject() && chest.getAsJsonObject().size() > 0) {
                if (mode == Mode.ECONOMY) {
                    changes.add("old chest loot deleted (" + chest.getAsJsonObject().size() + " stacks)");
                    settings.remove("currentItemsInChest");
                } else {
                    final int stripped = removeItems(chest.getAsJsonObject(), PVP_ITEMS);
                    changes.add("old chest loot kept (" + chest.getAsJsonObject().size() + " stacks"
                            + (stripped > 0 ? ", " + stripped + " PvP stacks removed" : "") + ")");
                }
            }
            changes.add("loot key stats");
        }
        final int[] removedItems = {0};
        final int[] keys = {0, 0, 0}; // kept, emptied+removed, deleted (economy)
        forEachContainer(root, items -> {
            removedItems[0] += removeItems(items, PVP_ITEMS);
            if (mode == Mode.ECONOMY) {
                keys[2] += removeItems(items, LOOT_KEYS);
            } else {
                stripLootKeys(items, keys);
            }
        });
        if (removedItems[0] > 0) changes.add(removedItems[0] + " PvP item stacks");
        if (keys[0] > 0) changes.add(keys[0] + " loot key(s) kept with their loot");
        if (keys[1] > 0) changes.add(keys[1] + " loot key(s) that only held PvP currency removed");
        if (keys[2] > 0) changes.add(keys[2] + " loot key(s) deleted with their loot");
    }

    /**
     * pvp mode: removes PvP currency from inside every loot key in this container. A key whose loot is now empty is
     * removed; any other key is kept (its stored value may read a little high until the chest next rewrites it).
     */
    private void stripLootKeys(final JsonObject items, final int[] keys) {
        for (final String slot : new ArrayList<>(items.keySet())) {
            final JsonElement element = items.get(slot);
            if (element == null || !element.isJsonObject()) continue;
            final JsonObject item = element.getAsJsonObject();
            if (!item.has("id") || !LOOT_KEYS.contains(item.get("id").getAsInt())) continue;
            final JsonObject attributes = item.has("attributes") && item.get("attributes").isJsonObject()
                    ? item.getAsJsonObject("attributes") : null;
            final JsonElement raw = attributes == null ? null : attributes.get(LOOT_KEY_ITEMS_ATTR);
            JsonObject loot = null;
            try {
                if (raw != null && raw.isJsonPrimitive()) {
                    final JsonElement parsed = JsonParser.parseString(raw.getAsString());
                    loot = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
                } else if (raw != null && raw.isJsonObject()) {
                    loot = raw.getAsJsonObject();
                }
            } catch (Exception e) {
                System.err.println("  unreadable loot key contents in slot " + slot + " kept as is: " + e.getMessage());
                keys[0]++;
                continue;
            }
            if (loot == null) {
                keys[0]++;
                continue;
            }
            final int before = loot.size();
            removeItems(loot, PVP_ITEMS);
            if (loot.size() == 0 && before > 0) {
                items.remove(slot);
                keys[1]++;
                continue;
            }
            if (loot.size() != before) {
                attributes.addProperty(LOOT_KEY_ITEMS_ATTR, loot.toString());
            }
            keys[0]++;
        }
    }

    private void resetEconomy(final JsonObject root, final List<String> changes) {
        int cleared = 0;
        for (final String key : ECONOMY_CONTAINERS) {
            final JsonObject items = containerItems(root.get(key));
            if (items != null && items.size() > 0) {
                cleared += items.size();
                for (final String slot : new ArrayList<>(items.keySet())) items.remove(slot);
            }
        }
        if (root.has("bank") && root.get("bank").isJsonObject()) {
            final JsonObject bank = root.getAsJsonObject("bank");
            if (bank.has("tabSizes") && bank.get("tabSizes").isJsonArray()) {
                final JsonArray sizes = new JsonArray();
                for (int i = 0; i < bank.getAsJsonArray("tabSizes").size(); i++) sizes.add(0);
                bank.add("tabSizes", sizes);
            }
        }
        if (root.has("gravestone") && root.get("gravestone").isJsonObject()) {
            root.getAsJsonObject("gravestone").addProperty("coinsInCoffer", 0);
        }
        if (root.has("gauntletItemStorage") && root.get("gauntletItemStorage").isJsonObject()) {
            final JsonObject storage = root.getAsJsonObject("gauntletItemStorage");
            for (final String key : List.of("inventory", "equipment")) {
                if (storage.has(key) && storage.get(key).isJsonObject() && storage.getAsJsonObject(key).has("items")) {
                    storage.getAsJsonObject(key).add("items", new JsonObject());
                }
            }
        }
        if (root.has("grandExchange")) {
            root.add("grandExchange", JsonParser.parseString("{\"history\":[]}"));
        }
        if (root.has("magicStorageUnit") && root.get("magicStorageUnit").isJsonObject()) {
            root.getAsJsonObject("magicStorageUnit").add("storedSets", new JsonObject());
        }
        if (root.has("stash")) {
            root.add("stash", JsonParser.parseString("{\"stashes\":{}}"));
        }
        if (cleared > 0) changes.add(cleared + " item stacks (economy)");
    }

    private interface ItemsVisitor {
        void visit(JsonObject items);
    }

    /** Visits every {"container": {"items": {...}}} object in the character (and nested ones). */
    private static void forEachContainer(final JsonElement element, final ItemsVisitor visitor) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) {
            final JsonObject object = element.getAsJsonObject();
            if (object.has("items") && object.get("items").isJsonObject() && object.has("type")) {
                visitor.visit(object.getAsJsonObject("items"));
            }
            for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (entry.getKey().equals("collectionLog")) continue; // the log records items obtained; never edited
                forEachContainer(entry.getValue(), visitor);
            }
        } else if (element.isJsonArray()) {
            for (final JsonElement child : element.getAsJsonArray()) forEachContainer(child, visitor);
        }
    }

    private static JsonObject containerItems(final JsonElement holder) {
        if (holder == null || !holder.isJsonObject()) return null;
        final JsonObject object = holder.getAsJsonObject();
        if (!object.has("container") || !object.get("container").isJsonObject()) return null;
        final JsonObject container = object.getAsJsonObject("container");
        return container.has("items") && container.get("items").isJsonObject() ? container.getAsJsonObject("items") : null;
    }

    private static int removeItems(final JsonObject items, final Set<Integer> ids) {
        int removed = 0;
        for (final String slot : new ArrayList<>(items.keySet())) {
            final JsonElement item = items.get(slot);
            if (item != null && item.isJsonObject() && item.getAsJsonObject().has("id")
                    && ids.contains(item.getAsJsonObject().get("id").getAsInt())) {
                items.remove(slot);
                removed++;
            }
        }
        return removed;
    }

    /* ------------------------------------------------------------------ hiscores */

    /**
     * pvp and economy modes: clears the PvP category of every data/characters-hiscores/&lt;mode&gt;/*.json file. The
     * server only rewrites a player's PVP entry when they have at least one kill (HiscoresCategory.PVP), so without this
     * last season's kills and deaths would stay on the leaderboard.
     */
    private void resetPvpHiscores() throws IOException {
        final Path dir = data.resolve("characters-hiscores");
        if (!Files.isDirectory(dir)) return;
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir, 2)) {
            walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
        }
        for (final Path file : files) {
            final JsonElement parsed;
            try {
                parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (Exception e) {
                System.err.println("  SKIP unreadable hiscores " + dir.relativize(file) + ": " + e.getMessage());
                continue;
            }
            if (!parsed.isJsonObject()) continue;
            final JsonObject root = parsed.getAsJsonObject();
            final List<String> cleared = new ArrayList<>();
            for (final String category : PVP_HISCORE_CATEGORIES) {
                final JsonElement entries = root.get(category);
                if (entries == null || entries.isJsonObject() && entries.getAsJsonObject().size() == 0) continue;
                root.add(category, new JsonObject());
                cleared.add(category);
            }
            if (cleared.isEmpty()) continue;
            changedFiles++;
            System.out.println("  characters-hiscores/" + dir.relativize(file) + ": " + String.join(", ", cleared) + " cleared");
            if (apply) {
                final Path tmp = file.resolveSibling(file.getFileName() + ".reset.tmp");
                Files.writeString(tmp, gson.toJson(root), StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }

    /* ------------------------------------------------------------------ global files */

    private void resetGlobalEconomy() throws IOException {
        writeIfExists("grandexchange/offers.json", "[]");
        writeIfExists("grandexchange/banned_offers.json", "[]");
        writeIfExists("middleman/confirmed_middleman_trades.json", "[]");
        writeIfExists("middleman/handled_middleman_trades.json", "[]");
        writeIfExists("tob/playerTobGlobalChest.json", "{}");
        final Path well = data.resolve("well.json");
        if (Files.exists(well)) {
            final JsonElement root = JsonParser.parseString(Files.readString(well, StandardCharsets.UTF_8));
            if (root.isJsonArray()) {
                for (final JsonElement perk : root.getAsJsonArray()) {
                    if (!perk.isJsonObject()) continue;
                    perk.getAsJsonObject().addProperty("coins", 0);
                    perk.getAsJsonObject().add("contributions", new JsonArray());
                }
                changedFiles++;
                System.out.println("  well.json: all perk coffers emptied");
                if (apply) Files.writeString(well, gson.toJson(root), StandardCharsets.UTF_8);
            }
        }
    }

    private void writeIfExists(final String relative, final String content) throws IOException {
        final Path file = data.resolve(relative);
        // Only plain files (the ToB plugin creates data/tob/playerTobGlobalChest.json as a directory when it is missing).
        if (!Files.isRegularFile(file)) return;
        changedFiles++;
        System.out.println("  " + relative + ": reset to " + content);
        if (apply) Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void fullWipe() throws IOException {
        for (final String relative : FULL_WIPE) {
            final Path path = data.resolve(relative);
            if (!Files.exists(path)) continue;
            if (Files.isDirectory(path)) {
                final List<Path> entries = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(p -> !p.equals(path)).forEach(entries::add);
                }
                // keep the directory structure (sub folders such as characters-hiscores/regular), delete files
                final long fileCount = entries.stream().filter(Files::isRegularFile).count();
                changedFiles += (int) fileCount;
                System.out.println("  " + relative + "/: delete " + fileCount + " file(s)");
                if (apply) {
                    for (final Path entry : entries) {
                        if (Files.isRegularFile(entry)) Files.delete(entry);
                    }
                }
            } else {
                changedFiles++;
                System.out.println("  " + relative + ": delete");
                if (apply) Files.delete(path);
            }
        }
    }

    private static void copyTree(final Path source, final Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName() != null && dir.getFileName().toString().equals("logs") && !dir.equals(source)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

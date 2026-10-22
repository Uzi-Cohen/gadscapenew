package com.zenyte.game.content.skills.construction.objects.portalnexus;

import com.zenyte.game.content.achievementdiary.DiaryReward;
import com.zenyte.game.content.achievementdiary.DiaryUtil;
import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.FurnitureData;
import com.zenyte.game.content.skills.construction.ObjectInteraction;
import com.zenyte.game.content.skills.construction.RoomReference;
import com.zenyte.game.content.skills.construction.constants.Furniture;
import com.zenyte.game.content.skills.construction.constants.FurnitureSpace;
import com.zenyte.game.content.skills.magic.spells.teleports.SpellbookTeleport;
import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.util.Colour;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.game.world.object.WorldObject;
import com.zenyte.game.world.region.area.wilderness.WildernessArea;
import com.zenyte.plugins.dialogue.OptionsMenuD;

import java.util.ArrayList;
import java.util.List;

/**
 * Portal Nexus room (CON-8), after the OSRS wiki "Portal Nexus":
 * <ul>
 * <li>marble (72, 4 destinations), gilded (82, 8) and crystalline (92, 41) nexus; the gilded and crystalline ones are made
 * with "Upgrade", keeping the destinations;</li>
 * <li>a destination costs 1,000 casts of the spell's runes (plain runes from the inventory only) and the spell's unboosted
 * Magic level; removing one doesn't refund;</li>
 * <li>left-click goes to the chosen default destination (the object changes to the matching cache variant), "Teleport Menu"
 * lists all of them; guests can use the nexus too;</li>
 * <li>mounted Xeric's talisman and digsite pendant in the amulet spaces, with their teleports.</li>
 * </ul>
 * GadScape has no nexus interfaces wired up, so the menus are dialogues.
 */
public final class PortalNexus implements ObjectInteraction {

    /** Nexus teleport destinations. {@code variant} is the marble object with this left-click destination, or -1. */
    public enum Destination {
        VARROCK("Varrock", SpellbookTeleport.VARROCK_TELEPORT, 33354, 18),
        GRAND_EXCHANGE("Grand Exchange", SpellbookTeleport.GRAND_EXCHANGE, 33355, 18),
        LUMBRIDGE("Lumbridge", SpellbookTeleport.LUMBRIDGE_TELEPORT, 33356, 18),
        FALADOR("Falador", SpellbookTeleport.FALADOR_TELEPORT, 33357, 18),
        CAMELOT("Camelot", SpellbookTeleport.CAMELOT_TELEPORT, 33358, 18),
        SEERS_VILLAGE("Seers' Village", SpellbookTeleport.SEERS_TELEPORT, 33359, 18),
        ARDOUGNE("East Ardougne", SpellbookTeleport.ARDOUGNE_TELEPORT, 33360, 18),
        WATCHTOWER("Watchtower", SpellbookTeleport.WATCHTOWER_TELEPORT, 33361, 18),
        YANILLE("Yanille", SpellbookTeleport.YANILLE_TELEPORT, 33362, 18),
        SENNTISTEN("Senntisten", SpellbookTeleport.SENNTISTEN_TELEPORT, 33363, 18),
        MARIM("Marim", SpellbookTeleport.APE_ATOLL_TELEPORT_REG, 33364, 18),
        KHARYRLL("Kharyrll", SpellbookTeleport.KHARYRLL_TELEPORT, 33365, 18),
        LUNAR_ISLE("Lunar Isle", SpellbookTeleport.MOONCLAN_TELEPORT, 33366, 18),
        GREAT_KOUREND("Great Kourend", SpellbookTeleport.KOUREND_CASTLE_TELEPORT, 33367, 18),
        WATERBIRTH_ISLAND("Waterbirth Island", SpellbookTeleport.WATERBIRTH_TELEPORT, 33368, 18),
        FISHING_GUILD("Fishing Guild", SpellbookTeleport.FISHING_GUILD_TELEPORT, 33369, 18),
        ANNAKARL("Annakarl", SpellbookTeleport.ANNAKARL_TELEPORT, 33370, 18),
        CATHERBY("Catherby", SpellbookTeleport.CATHERBY_TELEPORT, 33423, 3),
        GHORROCK("Ghorrock", SpellbookTeleport.GHORROCK_TELEPORT, 33424, 3),
        CARRALLANGAR("Carrallangar", SpellbookTeleport.CARRALLANGAR_TELEPORT, 33425, 3),
        DRAYNOR_MANOR("Draynor Manor", SpellbookTeleport.DRAYNOR_MANOR_TELEPORT, -1, 0),
        BATTLEFRONT("Battlefront", SpellbookTeleport.BATTLEFRONT_TELEPORT, -1, 0),
        MIND_ALTAR("Mind Altar", SpellbookTeleport.MIND_ALTAR_TELEPORT, -1, 0),
        SALVE_GRAVEYARD("Salve Graveyard", SpellbookTeleport.SALVE_GRAVEYARD_TELEPORT, -1, 0),
        FENKENSTRAINS_CASTLE("Fenkenstrain's Castle", SpellbookTeleport.FENKENSTRAINS_CASTLE_TELEPORT, -1, 0),
        WEST_ARDOUGNE("West Ardougne", SpellbookTeleport.WEST_ARDOUGNE_TELEPORT, -1, 0),
        TROLLHEIM("Trollheim", SpellbookTeleport.TROLLHEIM_TELEPORT, -1, 0),
        HARMONY_ISLAND("Harmony Island", SpellbookTeleport.HARMONY_ISLAND_TELEPORT, -1, 0),
        CEMETERY("Forgotten Cemetery", SpellbookTeleport.CEMETERY_TELEPORT, -1, 0),
        OURANIA("Ourania Cave", SpellbookTeleport.OURANIA_TELEPORT, -1, 0),
        LASSAR("Lassar", SpellbookTeleport.LASSAR_TELEPORT, -1, 0),
        BARBARIAN_OUTPOST("Barbarian Outpost", SpellbookTeleport.BARBARIAN_TELEPORT, -1, 0),
        PORT_KHAZARD("Port Khazard", SpellbookTeleport.KHAZARD_TELEPORT, -1, 0),
        DAREEYAK("Dareeyak", SpellbookTeleport.DAREEYAK_TELEPORT, -1, 0),
        BARROWS("Barrows", SpellbookTeleport.BARROWS_TELEPORT, -1, 0),
        ICE_PLATEAU("Ice Plateau", SpellbookTeleport.ICE_PLATEAU_TELEPORT, -1, 0),
        APE_ATOLL_DUNGEON("Ape Atoll Dungeon", SpellbookTeleport.APE_ATOLL_TELEPORT, -1, 0);

        public static final Destination[] VALUES = values();

        private final String title;
        private final SpellbookTeleport spell;
        private final int marbleVariant;
        private final int tierStep;

        Destination(final String title, final SpellbookTeleport spell, final int marbleVariant, final int tierStep) {
            this.title = title;
            this.spell = spell;
            this.marbleVariant = marbleVariant;
            this.tierStep = tierStep;
        }

        public String getTitle() {
            return title;
        }

        public SpellbookTeleport getSpell() {
            return spell;
        }

        /** Object id of the nexus of a tier (0 marble, 1 gilded, 2 crystalline) with this left-click destination, or -1. */
        public int variant(final int tier) {
            return marbleVariant == -1 ? -1 : marbleVariant + tier * tierStep;
        }

        public static Destination byName(final String name) {
            for (final Destination destination : VALUES) {
                if (destination.name().equals(name)) {
                    return destination;
                }
            }
            return null;
        }
    }

    public static final int[] BASE_NEXUS = new int[] { 33408, 33409, 33410 };
    public static final int[] CAPACITY = new int[] { 4, 8, 41 };
    private static final int XERIC_BASE = 33419;
    private static final int DIGSITE_BASE = 33420;
    private static final String[] XERIC_NAMES = new String[] { "Xeric's Look-out", "Xeric's Glade", "Xeric's Inferno", "Xeric's Heart", "Xeric's Honour" };
    private static final Location[] XERIC_TILES = new Location[] { new Location(1579, 3531, 0), new Location(1776, 3504, 0), new Location(1503, 3815, 0), new Location(1643, 3671, 0), new Location(1254, 3559, 0) };
    private static final String[] DIGSITE_NAMES = new String[] { "Digsite", "Fossil Island", "Lithkren Dungeon" };
    private static final Location[] DIGSITE_TILES = new Location[] { new Location(3340, 3445, 0), new Location(3763, 3870, 1), new Location(3547, 10456, 0) };

    /* --------------------------------------------------------------------------------------------------------------------
     * Object ids
     * ------------------------------------------------------------------------------------------------------------------ */

    public static int tierOf(final Furniture furniture) {
        return furniture == Furniture.GILDED_PORTAL_NEXUS ? 1 : furniture == Furniture.CRYSTALLINE_PORTAL_NEXUS ? 2 : 0;
    }

    /** The nexus object for the house's tier and left-click destination. */
    public static int nexusObject(final Construction house, final Furniture furniture) {
        final int tier = tierOf(furniture);
        final Destination def = Destination.byName(house.getNexusDefault());
        if (def != null && house.getNexusDestinations().contains(def.name())) {
            final int variant = def.variant(tier);
            // 241 caches dropped the per-destination nexus objects (the base nexus has a "Teleport" option instead).
            if (variant != -1 && isNexusDefinition(variant)) {
                return variant;
            }
        }
        return BASE_NEXUS[tier];
    }

    private static boolean isNexusDefinition(final int id) {
        final mgi.types.config.ObjectDefinitions defs = mgi.types.config.ObjectDefinitions.get(id);
        return defs != null && "Portal Nexus".equals(defs.getName());
    }

    public static int xericObject(final Construction house) {
        final int index = house.getXericDefault();
        return index >= 1 && index <= 5 ? 33410 + index : XERIC_BASE;
    }

    public static int digsiteObject(final Construction house) {
        final int index = house.getDigsiteDefault();
        return index >= 1 && index <= 3 ? 33415 + index : DIGSITE_BASE;
    }

    private static boolean isNexusObject(final int id) {
        return id >= 33354 && id <= 33410 || id >= 33423 && id <= 33431;
    }

    private static boolean isXericObject(final int id) {
        return id >= 33411 && id <= 33415 || id == XERIC_BASE;
    }

    private static boolean isDigsiteObject(final int id) {
        return id >= 33416 && id <= 33418 || id == DIGSITE_BASE;
    }

    /** Whether a built object id is one of the left-click variants of the given furniture (for removing it). */
    public static boolean isVariantOf(final Furniture furniture, final int objectId) {
        switch (furniture) {
            case MARBLE_PORTAL_NEXUS:
            case GILDED_PORTAL_NEXUS:
            case CRYSTALLINE_PORTAL_NEXUS:
                return isNexusObject(objectId);
            case MOUNTED_XERICS_TALISMAN:
                return isXericObject(objectId);
            case MOUNTED_DIGSITE_PENDANT:
                return isDigsiteObject(objectId);
            default:
                return false;
        }
    }

    /** Gilded and crystalline nexuses are upgrades of the one before them, not built on an empty space. */
    public static boolean checkBuild(final Player player, final Furniture furniture) {
        if (furniture == Furniture.GILDED_PORTAL_NEXUS || furniture == Furniture.CRYSTALLINE_PORTAL_NEXUS) {
            player.sendMessage("Build a marble portal nexus first; it can then be upgraded with its \"Upgrade\" option.");
            return false;
        }
        return true;
    }

    /* --------------------------------------------------------------------------------------------------------------------
     * Interaction
     * ------------------------------------------------------------------------------------------------------------------ */

    @Override
    public void handleObjectAction(final Player player, final Construction construction, final RoomReference reference, final WorldObject object, final int optionId, final String option) {
        if (reference == null) {
            return;
        }
        final int id = object.getId();
        if (isXericObject(id) || isDigsiteObject(id)) {
            handleJewellery(player, construction, reference, object, optionId, option, isXericObject(id));
            return;
        }
        final FurnitureData data = reference.getFurniture(FurnitureSpace.PORTAL_NEXUS_SPACE);
        if (data == null) {
            return;
        }
        if (optionId == 1) {
            final Destination def = Destination.byName(construction.getNexusDefault());
            if (def == null || !construction.getNexusDestinations().contains(def.name())) {
                openTeleportMenu(player, construction);
                return;
            }
            teleport(player, def);
        } else if (option.equalsIgnoreCase("Teleport Menu")) {
            openTeleportMenu(player, construction);
        } else if (option.equalsIgnoreCase("Configuration")) {
            if (!construction.isOwner(player)) {
                player.sendMessage("Only the owner of the house can configure the portal nexus.");
                return;
            }
            player.getDialogueManager().start(new ConfigurationDialogue(player, construction, reference, data));
        } else if (option.equalsIgnoreCase("Upgrade")) {
            if (!construction.isOwner(player)) {
                player.sendMessage("Only the owner of the house can upgrade the portal nexus.");
                return;
            }
            upgrade(player, construction, reference, data);
        }
    }

    private static List<Destination> destinations(final Construction house) {
        final List<Destination> list = new ArrayList<>();
        for (final String name : house.getNexusDestinations()) {
            final Destination destination = Destination.byName(name);
            if (destination != null) {
                list.add(destination);
            }
        }
        return list;
    }

    private static void openTeleportMenu(final Player player, final Construction house) {
        final List<Destination> list = destinations(house);
        if (list.isEmpty()) {
            player.sendMessage(house.isOwner(player) ? "Your portal nexus has no destinations yet; add some with its \"Configuration\" option." : "This portal nexus has no destinations yet.");
            return;
        }
        final String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = list.get(i).getTitle();
        }
        player.getDialogueManager().start(new OptionsMenuD(player, "Portal Nexus", names) {
            @Override
            public void handleClick(final int slotId) {
                if (slotId >= 0 && slotId < list.size()) {
                    teleport(player, list.get(slotId));
                }
            }

            @Override
            public boolean cancelOption() {
                return true;
            }
        });
    }

    private static void teleport(final Player player, final Destination destination) {
        final Location tile = destination.getSpell().getDestination();
        if (tile == null) {
            return;
        }
        if (WildernessArea.isWithinWilderness(tile.getX(), tile.getY())) {
            player.getDialogueManager().start(new Dialogue(player) {
                @Override
                public void buildDialogue() {
                    plain("This portal leads into the " + Colour.RED.wrap("Wilderness") + ".<br>Are you sure you wish to continue?");
                    options("Teleport into the Wilderness?", new DialogueOption("Yes.", () -> enter(player, tile)), new DialogueOption("No."));
                }
            });
            return;
        }
        enter(player, tile);
    }

    private static void enter(final Player player, final Location tile) {
        if (player.isLocked()) {
            return;
        }
        player.setLocation(new Location(tile));
    }

    /* --------------------------------------------------------------------------------------------------------------------
     * Configuration
     * ------------------------------------------------------------------------------------------------------------------ */

    private static final class ConfigurationDialogue extends Dialogue {
        private final Construction house;
        private final RoomReference reference;
        private final FurnitureData data;

        ConfigurationDialogue(final Player player, final Construction house, final RoomReference reference, final FurnitureData data) {
            super(player);
            this.house = house;
            this.reference = reference;
            this.data = data;
        }

        @Override
        public void buildDialogue() {
            final int tier = tierOf(data.getFurniture());
            options("Portal nexus: " + house.getNexusDestinations().size() + "/" + CAPACITY[tier] + " destinations",
                    new DialogueOption("Add a destination", () -> addMenu(player, house, tier)),
                    new DialogueOption("Remove a destination", () -> removeMenu(player, house, reference, data)),
                    new DialogueOption("Set the left-click destination", () -> defaultMenu(player, house, reference, data)),
                    new DialogueOption("Nothing."));
        }
    }

    private static void addMenu(final Player player, final Construction house, final int tier) {
        if (house.getNexusDestinations().size() >= CAPACITY[tier]) {
            player.sendMessage("Your portal nexus can't hold any more destinations" + (tier < 2 ? "; upgrade it to add more." : "."));
            return;
        }
        final List<Destination> available = new ArrayList<>();
        for (final Destination destination : Destination.VALUES) {
            if (!house.getNexusDestinations().contains(destination.name())) {
                available.add(destination);
            }
        }
        final String[] names = new String[available.size()];
        for (int i = 0; i < names.length; i++) {
            final Destination destination = available.get(i);
            names[i] = destination.getTitle() + " (" + destination.getSpell().getLevel() + " Magic)";
        }
        player.getDialogueManager().start(new OptionsMenuD(player, "Add which destination?", names) {
            @Override
            public void handleClick(final int slotId) {
                if (slotId >= 0 && slotId < available.size()) {
                    add(player, house, tier, available.get(slotId));
                }
            }

            @Override
            public boolean cancelOption() {
                return true;
            }
        });
    }

    private static void add(final Player player, final Construction house, final int tier, final Destination destination) {
        if (house.getNexusDestinations().size() >= CAPACITY[tier] || house.getNexusDestinations().contains(destination.name())) {
            return;
        }
        final SpellbookTeleport spell = destination.getSpell();
        if (player.getSkills().getLevelForXp(SkillConstants.MAGIC) < spell.getLevel()) {
            player.sendMessage("You need a Magic level of " + spell.getLevel() + " to add " + destination.getTitle() + " to the portal nexus.");
            return;
        }
        if (destination == Destination.GRAND_EXCHANGE && !DiaryUtil.eligibleFor(DiaryReward.VARROCK_ARMOUR2, player)
                || destination == Destination.SEERS_VILLAGE && !DiaryUtil.eligibleFor(DiaryReward.KANDARIN_HEADGEAR3, player)
                || destination == Destination.YANILLE && !DiaryUtil.eligibleFor(DiaryReward.ARDOUGNE_CLOAK3, player)) {
            player.sendMessage("You haven't unlocked that teleport yet.");
            return;
        }
        final Item[] runes = spell.getRunes();
        final List<Item> cost = new ArrayList<>();
        for (final Item rune : runes) {
            if (rune == null) {
                continue;
            }
            cost.add(new Item(rune.getId(), rune.getAmount() * 1000));
        }
        for (final Item item : cost) {
            if (player.getInventory().getAmountOf(item.getId()) < item.getAmount()) {
                final StringBuilder builder = new StringBuilder();
                for (final Item i : cost) {
                    builder.append(String.format("%,d", i.getAmount())).append(" x ").append(i.getName().toLowerCase()).append(", ");
                }
                builder.setLength(Math.max(0, builder.length() - 2));
                player.sendMessage("You need " + builder + " in your inventory to add that destination.");
                return;
            }
        }
        for (final Item item : cost) {
            player.getInventory().deleteItem(item.getId(), item.getAmount());
        }
        house.getNexusDestinations().add(destination.name());
        if (house.getNexusDefault() == null && destination.variant(0) != -1) {
            house.setNexusDefault(destination.name());
        }
        player.sendMessage(destination.getTitle() + " has been added to your portal nexus.");
        refreshNexus(player, house);
    }

    private static void removeMenu(final Player player, final Construction house, final RoomReference reference, final FurnitureData data) {
        final List<Destination> list = destinations(house);
        if (list.isEmpty()) {
            player.sendMessage("Your portal nexus has no destinations.");
            return;
        }
        final String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = list.get(i).getTitle();
        }
        player.getDialogueManager().start(new OptionsMenuD(player, "Remove which destination? (no refund)", names) {
            @Override
            public void handleClick(final int slotId) {
                if (slotId < 0 || slotId >= list.size()) {
                    return;
                }
                final Destination destination = list.get(slotId);
                house.getNexusDestinations().remove(destination.name());
                if (destination.name().equals(house.getNexusDefault())) {
                    house.setNexusDefault(null);
                }
                player.sendMessage(destination.getTitle() + " has been removed from your portal nexus.");
                refreshNexus(player, house);
            }

            @Override
            public boolean cancelOption() {
                return true;
            }
        });
    }

    private static void defaultMenu(final Player player, final Construction house, final RoomReference reference, final FurnitureData data) {
        final List<Destination> list = new ArrayList<>();
        for (final Destination destination : destinations(house)) {
            if (destination.variant(0) != -1) {
                list.add(destination);
            }
        }
        if (list.isEmpty()) {
            player.sendMessage("None of your destinations can be used as the left-click option.");
            return;
        }
        final String[] names = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).getTitle();
        }
        names[list.size()] = "None";
        player.getDialogueManager().start(new OptionsMenuD(player, "Left-click destination", names) {
            @Override
            public void handleClick(final int slotId) {
                house.setNexusDefault(slotId >= 0 && slotId < list.size() ? list.get(slotId).name() : null);
                refreshNexus(player, house);
            }

            @Override
            public boolean cancelOption() {
                return true;
            }
        });
    }

    private static void upgrade(final Player player, final Construction house, final RoomReference reference, final FurnitureData data) {
        final Furniture current = data.getFurniture();
        final Furniture next = current == Furniture.MARBLE_PORTAL_NEXUS ? Furniture.GILDED_PORTAL_NEXUS : current == Furniture.GILDED_PORTAL_NEXUS ? Furniture.CRYSTALLINE_PORTAL_NEXUS : null;
        if (next == null) {
            player.sendMessage("Your portal nexus can't be upgraded any further.");
            return;
        }
        if (player.getSkills().getLevelForXp(SkillConstants.CONSTRUCTION) < next.getLevel()) {
            player.sendMessage("You need a Construction level of " + next.getLevel() + " to upgrade the portal nexus.");
            return;
        }
        if (!player.getInventory().containsItem(ItemId.HAMMER, 1) && !player.getInventory().containsItem(ItemId.IMCANDO_HAMMER, 1)) {
            player.sendMessage("You need a hammer to upgrade the portal nexus.");
            return;
        }
        for (final Item material : next.getMaterials()) {
            if (player.getInventory().getAmountOf(material.getId()) < material.getAmount()) {
                player.sendMessage("You need " + material.getAmount() + " x " + material.getName().toLowerCase() + " to upgrade the portal nexus.");
                return;
            }
        }
        for (final Item material : next.getMaterials()) {
            player.getInventory().deleteItem(material.getId(), material.getAmount());
        }
        data.setFurniture(next);
        player.getSkills().addXp(SkillConstants.CONSTRUCTION, next.getXp());
        player.setAnimation(com.zenyte.game.content.skills.construction.ConstructionConstants.REG_ANIM);
        player.sendMessage("You upgrade the portal nexus.");
        refreshNexus(player, house);
    }

    /** Respawns every nexus of the house with the object for its tier and left-click destination. */
    private static void refreshNexus(final Player player, final Construction house) {
        if (!house.isLoaded()) {
            return;
        }
        for (final RoomReference room : house.getReferences()) {
            final FurnitureData data = room.getFurniture(FurnitureSpace.PORTAL_NEXUS_SPACE);
            if (data == null) {
                continue;
            }
            final Location tile = house.getRoomTile(room, data.getLocation().getX(), data.getLocation().getY());
            World.spawnObject(new WorldObject(nexusObject(house, data.getFurniture()), data.getType(), data.getRotation(), tile));
        }
    }

    /* --------------------------------------------------------------------------------------------------------------------
     * Mounted Xeric's talisman / digsite pendant
     * ------------------------------------------------------------------------------------------------------------------ */

    private static void handleJewellery(final Player player, final Construction house, final RoomReference reference, final WorldObject object, final int optionId, final String option, final boolean xeric) {
        final String[] names = xeric ? XERIC_NAMES : DIGSITE_NAMES;
        final Location[] tiles = xeric ? XERIC_TILES : DIGSITE_TILES;
        if (option.equalsIgnoreCase("Configuration")) {
            if (!house.isOwner(player)) {
                player.sendMessage("Only the owner of the house can do that.");
                return;
            }
            final String[] options = new String[names.length + 1];
            System.arraycopy(names, 0, options, 0, names.length);
            options[names.length] = "None";
            player.getDialogueManager().start(new OptionsMenuD(player, "Left-click destination", options) {
                @Override
                public void handleClick(final int slotId) {
                    final int value = slotId >= 0 && slotId < names.length ? slotId + 1 : 0;
                    if (xeric) {
                        house.setXericDefault(value);
                    } else {
                        house.setDigsiteDefault(value);
                    }
                    World.spawnObject(new WorldObject(xeric ? xericObject(house) : digsiteObject(house), object.getType(), object.getRotation(), object));
                }

                @Override
                public boolean cancelOption() {
                    return true;
                }
            });
            return;
        }
        if (optionId == 1) {
            final int index = (xeric ? house.getXericDefault() : house.getDigsiteDefault()) - 1;
            if (index >= 0 && index < tiles.length) {
                jewelleryTeleport(player, xeric, index, tiles[index]);
                return;
            }
        }
        player.getDialogueManager().start(new OptionsMenuD(player, xeric ? "Xeric's talisman" : "Digsite pendant", names) {
            @Override
            public void handleClick(final int slotId) {
                if (slotId >= 0 && slotId < tiles.length) {
                    jewelleryTeleport(player, xeric, slotId, tiles[slotId]);
                }
            }

            @Override
            public boolean cancelOption() {
                return true;
            }
        });
    }

    private static void jewelleryTeleport(final Player player, final boolean xeric, final int index, final Location tile) {
        if (xeric && index == 4 && !player.getAttributes().containsKey("xeric's honour")) {
            player.sendMessage("The talisman does not have the power to take you there.");
            return;
        }
        enter(player, tile);
    }

    @Override
    public Object[] getObjects() {
        final List<Object> ids = new ArrayList<>();
        for (int id = 33354; id <= 33420; id++) {
            ids.add(id);
        }
        for (int id = 33423; id <= 33431; id++) {
            ids.add(id);
        }
        return ids.toArray();
    }
}

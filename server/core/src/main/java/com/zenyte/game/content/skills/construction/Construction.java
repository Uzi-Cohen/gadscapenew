package com.zenyte.game.content.skills.construction;

import com.google.gson.annotations.Expose;
import com.zenyte.game.content.achievementdiary.diaries.ArdougneDiary;
import com.zenyte.game.content.skills.construction.constants.Furniture;
import com.zenyte.game.content.skills.construction.constants.FurnitureSpace;
import com.zenyte.game.content.skills.construction.constants.House;
import com.zenyte.game.content.skills.construction.constants.RoomType;
import com.zenyte.game.content.skills.construction.costume.*;
import com.zenyte.game.content.skills.construction.dialogue.BuildStaircaseD;
import com.zenyte.game.content.skills.construction.servant.ServantManager;
import com.zenyte.game.item.Item;
import com.zenyte.game.model.MinimapState;
import com.zenyte.game.model.ui.InterfacePosition;
import com.zenyte.game.task.WorldTask;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.util.DirectionUtil;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity._Location;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.zenyte.game.world.entity.player.container.impl.Inventory;
import com.zenyte.game.world.object.ObjectId;
import com.zenyte.game.world.object.WorldObject;
import com.zenyte.game.world.region.Region;
import com.zenyte.game.world.region.dynamicregion.AllocatedArea;
import com.zenyte.game.world.region.dynamicregion.CoordinateUtilities;
import com.zenyte.game.world.region.dynamicregion.MapBuilder;
import com.zenyte.plugins.dialogue.PlainChat;
import com.zenyte.plugins.dialogue.RoomCreationD;
import com.zenyte.plugins.dialogue.RoomRemovingD;
import mgi.types.config.ObjectDefinitions;
import mgi.types.config.items.ItemDefinitions;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.zenyte.game.content.skills.construction.ConstructionConstants.*;

/**
 * A player-owned house. One instance per player (the owner); guests share the owner's instance while they visit.
 * <p>
 * Coordinates: a room's {@code x}/{@code y} are "room coordinates", which include {@link #yardOffset} so that rooms keep their
 * place when the yard grows with the Construction level. The world chunk of a room is
 * {@code chunkX + room.x - yardOffset} (see {@link #roomChunkX(int)}); every tile lookup goes through {@link #getRoomBase} /
 * {@link #getRoomTile} so it works for any instance alignment ({@link MapBuilder} instances start 13 chunks into a region, so
 * the old "% 64" arithmetic pointed at the wrong tiles).
 *
 * @author Tommeh 13 nov. 2017 : 22:36:26
 * @author Kris | 21. nov 2017 : 2:32.54
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public final class Construction {

    private static final Logger log = LoggerFactory.getLogger(Construction.class);

    /**
     * Temporary attribute with the house a player is inside right now (their own or a friend's). {@link Player#getCurrentHouse()}
     * reads the same key.
     */
    public static final String VISITING_ATTR = "VisitingHouse";

    /* House options tab (interface 370, GadScape's 211 layout) varbits. */
    public static final int BUILDING_MODE_VARBIT = 2176;
    /** 0 = teleport inside (on), 1 = teleport outside (off). */
    public static final int TELEPORT_INSIDE_VARBIT = 4744;
    public static final int DEFAULT_BUILDING_MODE_VARBIT = 14670;
    /** 0 = doors closed, 1 = open, 2 = no doors. */
    public static final int DOORS_VARBIT = 6269;
    public static final int HOUSE_OPTIONS_INTERFACE = 370;
    public static final int ROOM_COUNT_COMPONENT = 23;

    public static final int DOORS_CLOSED = 0;
    public static final int DOORS_OPEN = 1;
    public static final int DOORS_NONE = 2;

    /** Ticks before an instance that the house moved away from is wiped (players need a tick to leave it). */
    private static final int OLD_AREA_DESTROY_DELAY = 3;
    /** Ticks after which a player waiting for the house map to load is unlocked anyway. */
    private static final int LOAD_TIMEOUT_TICKS = 25;
    private static final Animation DEATH_ANIMATION = new Animation(836);

    @Expose
    private final List<RoomReference> references = new ArrayList<RoomReference>();

    private final transient Map<Player, Integer> catsOnBlanket = new HashMap<Player, Integer>();

    @Expose
    private final List<Integer> displayJars = new ArrayList<Integer>();

    @Expose
    private final ArmourCase armourCase = new ArmourCase();

    @Expose
    private final FancyDressBox fancyDressBox = new FancyDressBox();

    @Expose
    private final ToyBox toyBox = new ToyBox();

    @Expose
    private final MagicWardrobe magicWardrobe = new MagicWardrobe();

    @Expose
    private final CapeRack capeRack = new CapeRack();

    @Expose
    private final TreasureChest treasureChest = new TreasureChest();

    private final transient Player player;

    private transient boolean buildingMode;

    /** Building mode chosen in the house options while outside the house; used by the next house teleport. */
    private transient Boolean nextBuildingMode;

    private transient int chunkX;

    private transient int chunkY;

    private transient int yardSpace;

    private transient int yardOffset;

    @Expose
    private int decoration;

    @Expose
    private House house = House.RIMMINGTON;

    private transient HouseViewer houseViewer;

    @Expose
    private TipJar tipJar;

    private transient Location portal;

    @Expose
    private int servantsCash;

    /* House options (persisted; false/0 defaults match OSRS: teleport inside, doors closed, no default building mode). */
    @Expose
    private boolean teleportOutside;

    @Expose
    private boolean defaultBuildingMode;

    @Expose
    private int doorMode;

    @Expose
    private ServantManager.ServantData servantData;

    /* Portal Nexus room: destinations (PortalNexus.Destination names), left-click destination, mounted jewellery defaults. */
    @Expose
    private List<String> nexusDestinations = new ArrayList<>();

    @Expose
    private String nexusDefault;

    @Expose
    private int xericDefault;

    @Expose
    private int digsiteDefault;

    /** Pets (item ids) kept in the menagerie's pet house. */
    @Expose
    private List<Integer> menageriePets = new ArrayList<>();

    private final transient List<com.zenyte.game.world.entity.npc.NPC> roamingPets = new ArrayList<>();

    /** Games room NPCs (Jacky Jester, elemental balance) by furniture tile hash: at most one per piece of furniture. */
    private final transient Map<Integer, com.zenyte.game.world.entity.npc.NPC> furnitureNpcs = new HashMap<>();

    private transient AllocatedArea allocatedArea;

    /** Whether the owner is inside the loaded instance. */
    private transient boolean ownerInside;

    /** Guests currently inside the owner's instance. */
    private final transient Set<Player> guests = new LinkedHashSet<>();

    /** Exit portal "Lock": no new guests may enter while locked. */
    private transient boolean locked;

    private final transient ServantManager servantManager;

    private final transient com.zenyte.game.content.skills.construction.combat.HouseCombat combat;

    public Construction(final Player player) {
        this.player = player;
        houseViewer = new HouseViewer(player);
        tipJar = new TipJar(player);
        servantManager = new ServantManager(this);
        combat = new com.zenyte.game.content.skills.construction.combat.HouseCombat(this);
    }

    public final void setFields(final Construction construction) {
        if (construction.displayJars != null) {
            displayJars.addAll(construction.displayJars);
        }
        if (construction.armourCase != null) {
            armourCase.getItems().putAll(construction.armourCase.getItems());
        }
        player.getConstruction().getArmourCase().setPlayer(player);
        if (construction.fancyDressBox != null) {
            fancyDressBox.getItems().putAll(construction.fancyDressBox.getItems());
        }
        fancyDressBox.setPlayer(player);
        if (construction.toyBox != null) {
            toyBox.getItems().putAll(construction.toyBox.getItems());
        }
        toyBox.setPlayer(player);
        if (construction.capeRack != null) {
            capeRack.getItems().putAll(construction.capeRack.getItems());
        }
        capeRack.setPlayer(player);
        if (construction.magicWardrobe != null) {
            magicWardrobe.getItems().putAll(construction.magicWardrobe.getItems());
        }
        magicWardrobe.setPlayer(player);
        if (construction.treasureChest != null) {
            treasureChest.getItems().putAll(construction.treasureChest.getItems());
        }
        treasureChest.setPlayer(player);
        if (construction.house != null) {
            house = construction.house;
        }
        decoration = construction.decoration;
        servantsCash = construction.servantsCash;
        teleportOutside = construction.teleportOutside;
        defaultBuildingMode = construction.defaultBuildingMode;
        doorMode = construction.doorMode < DOORS_CLOSED || construction.doorMode > DOORS_NONE ? DOORS_CLOSED : construction.doorMode;
        servantData = construction.servantData;
        if (construction.nexusDestinations != null) {
            nexusDestinations.addAll(construction.nexusDestinations);
        }
        nexusDefault = construction.nexusDefault;
        xericDefault = construction.xericDefault;
        digsiteDefault = construction.digsiteDefault;
        if (construction.menageriePets != null) {
            menageriePets.addAll(construction.menageriePets);
        }
        if (construction.tipJar != null) {
            tipJar.set(construction.tipJar);
        }
        if (construction.getReferences() != null) {
            for (final RoomReference roomReference : construction.getReferences()) {
                if (roomReference == null || roomReference.getRoom() == null) {
                    continue;
                }
                if (roomReference.getFurnitureData() == null) {
                    roomReference.setFurnitureData(new ArrayList<>());
                }
                roomReference.getFurnitureData().removeIf(data -> data == null || data.getFurniture() == null || data.getSpace() == null || data.getLocation() == null);
                references.add(roomReference);
            }
        }
    }

    /**
     * The house the player is standing in: a friend's house while visiting, otherwise their own.
     */
    public static Construction current(final Player player) {
        final Construction visiting = player.getCurrentHouse();
        return visiting != null ? visiting : player.getConstruction();
    }

    public void refreshHouseOptions() {
        final boolean building = ownerInside ? buildingMode : nextBuildingMode != null ? nextBuildingMode : defaultBuildingMode;
        player.getVarManager().sendBit(BUILDING_MODE_VARBIT, building ? 1 : 0);
        player.getVarManager().sendBit(TELEPORT_INSIDE_VARBIT, teleportOutside ? 1 : 0);
        player.getVarManager().sendBit(DEFAULT_BUILDING_MODE_VARBIT, defaultBuildingMode ? 1 : 0);
        player.getVarManager().sendBit(DOORS_VARBIT, doorMode);
        sendRoomCount();
    }

    public void sendRoomCount() {
        player.getPacketDispatcher().sendComponentText(HOUSE_OPTIONS_INTERFACE, ROOM_COUNT_COMPONENT, "Number of rooms: " + getAmountOfRooms());
    }

    /* ------------------------------------------------------------------------------------------------------------------------
     * Coordinates
     * ---------------------------------------------------------------------------------------------------------------------- */

    /** World chunk x of the room at room coordinate {@code roomX}. */
    public int roomChunkX(final int roomX) {
        return chunkX + roomX - yardOffset;
    }

    /** World chunk y of the room at room coordinate {@code roomY}. */
    public int roomChunkY(final int roomY) {
        return chunkY + roomY - yardOffset;
    }

    /** South-west tile of a room in the current instance. */
    public Location getRoomBase(final RoomReference room) {
        return new Location(roomChunkX(room.getX()) << 3, roomChunkY(room.getY()) << 3, room.getPlane());
    }

    /** A tile of a room in the current instance, from coordinates inside the room's chunk (0-7). */
    public Location getRoomTile(final RoomReference room, final int localX, final int localY) {
        return new Location((roomChunkX(room.getX()) << 3) + localX, (roomChunkY(room.getY()) << 3) + localY, room.getPlane());
    }

    /** The objects the house template put on a room tile (hotspots, walls, windows), unaffected by built furniture. */
    private WorldObject[] getTemplateObjects(final Location tile) {
        final Region region = World.getRegion(tile.getRegionId(), true);
        return region.getObjects(tile.getPlane(), tile.getXInRegion(), tile.getYInRegion());
    }

    /**
     * The player's position as a tile in room coordinates (house-relative, including the yard offset), so that it can be
     * mapped back into a rebuilt instance by {@link #enterHouse(boolean, Location)}.
     */
    public Location getRelationalSpawnTile() {
        final int xInChunk = player.getLocation().getXInChunk();
        final int yInChunk = player.getLocation().getYInChunk();
        final int roomX = player.getLocation().getChunkX() - chunkX + yardOffset;
        final int roomY = player.getLocation().getChunkY() - chunkY + yardOffset;
        return new Location(roomX * 8 + xInChunk, roomY * 8 + yInChunk, player.getPlane());
    }

    private Location getWorldTile(final int mapX, final int mapY, final int plane) {
        return new Location(chunkX * 8 + mapX, chunkY * 8 + mapY, plane);
    }

    /**
     * Where players arrive: next to the exit portal, or the middle of the yard when the house has no exit portal.
     */
    public Location getPortalTile() {
        if (portal != null) {
            return new Location(portal);
        }
        final Location center = getCenter();
        return getWorldTile(center.getX(), center.getY(), 1);
    }

    public boolean contains(final Location location) {
        return allocatedArea != null && location != null && allocatedArea.inside(location);
    }

    /* ------------------------------------------------------------------------------------------------------------------------
     * Entering, leaving and guests
     * ---------------------------------------------------------------------------------------------------------------------- */

    public boolean isLoaded() {
        return allocatedArea != null;
    }

    public boolean isOwnerInside() {
        return ownerInside;
    }

    public boolean isOwner(final Player p) {
        return p == player;
    }

    public Set<Player> getGuests() {
        return guests;
    }

    public boolean hasGuests() {
        guests.removeIf(guest -> guest == null || guest.isFinished() || guest.getCurrentHouse() != this);
        return !guests.isEmpty();
    }

    /**
     * Enters the house through a portal or a teleport, building mode chosen by the house options.
     */
    public boolean enterHouseByTeleport() {
        final boolean building = nextBuildingMode != null ? nextBuildingMode : defaultBuildingMode;
        if (building && hasGuests()) {
            return enterHouse(false, null);
        }
        return enterHouse(building, null);
    }

    public void enterHouse(final boolean building) {
        enterHouse(building, null);
    }

    /**
     * Enters (and, when needed, rebuilds) the owner's house.
     *
     * @param building  whether to enter in building mode.
     * @param spawnTile where to place the owner, in room coordinates (see {@link #getRelationalSpawnTile()}), or null for the
     *                  exit portal.
     */
    public boolean enterHouse(final boolean building, final Location spawnTile) {
        if (house == null) {
            house = House.RIMMINGTON;
        }
        if (building && hasGuests()) {
            player.sendMessage("You can't turn on building mode while you have guests in your house.");
            return false;
        }
        // The house is up (the owner is inside, or guests stayed after the owner left): join it instead of rebuilding.
        if (!building && spawnTile == null && isLoaded() && !buildingMode) {
            join(player);
            return true;
        }
        return build(building, spawnTile);
    }

    private boolean build(final boolean building, Location spawnTile) {
        final AllocatedArea previous = allocatedArea;
        // State to restore when building the new instance fails before anyone was moved into it.
        final boolean previousBuildingMode = buildingMode;
        final Boolean previousNextBuildingMode = nextBuildingMode;
        final Location previousPortal = portal;
        final int previousChunkX = chunkX;
        final int previousChunkY = chunkY;
        final int previousYardSpace = yardSpace;
        final int previousYardOffset = yardOffset;
        boolean moved = false;
        final AllocatedArea area;
        try {
            area = MapBuilder.findEmptyChunk(generateYardSpace(), generateYardSpace());
        } catch (Exception e) {
            log.error("", e);
            player.sendMessage("Your house could not be loaded right now, please try again.");
            return false;
        }
        try {
            if (house == House.YANILLE) {
                player.getAchievementDiaries().update(ArdougneDiary.ENTER_YOUR_POH);
            }
            if (spawnTile != null && !isInsideHouseBounds(spawnTile)) {
                spawnTile = null;
            }
            buildingMode = building;
            nextBuildingMode = null;
            portal = null;
            yardSpace = generateYardSpace();
            yardOffset = generateYardOffset();
            player.getTemporaryAttributes().put("yard", yardOffset);
            if (references.isEmpty()) {
                final RoomReference garden = new RoomReference(RoomType.GARDEN, 4, 4, 1, 1);
                references.add(0, garden);
                garden.getFurnitureData().add(new FurnitureData(FurnitureSpace.CENTREPIECE_SPACE, Furniture.EXIT_PORTAL, new Location(3, 3, 1), 10, 1));
            }
            allocatedArea = area;
            chunkX = allocatedArea.getChunkX();
            chunkY = allocatedArea.getChunkY();
            createLandscape();
            createRooms();
            fillRooms();
            if (!buildingMode) {
                addDoors();
            }
            final Location destination = spawnTile != null
                    ? getWorldTile(spawnTile.getX() - yardOffset * 8, spawnTile.getY() - yardOffset * 8, spawnTile.getPlane())
                    : getPortalTile();
            ownerInside = true;
            moved = true;
            enterInstance(player, destination);
            for (final Player guest : new ArrayList<>(guests)) {
                if (guest == null || guest.isFinished()) {
                    guests.remove(guest);
                    continue;
                }
                enterInstance(guest, getPortalTile());
            }
            if (previous != null && previous != allocatedArea) {
                scheduleDestroy(previous);
            }
            combat.onHouseLoaded();
            finishFurnitureNpcs();
            roamingPets.clear();
            com.zenyte.game.content.skills.construction.objects.menagerie.PetHouse.spawnPets(this);
            servantManager.onHouseLoaded();
            refreshHouseOptions();
            return true;
        } catch (Exception e) {
            log.error("", e);
            if (moved) {
                // The players are in the new instance already; only an extra (monsters, pets, servant) failed.
                return true;
            }
            // Nobody was moved: drop the half-built instance and keep the previous one (if any) as it was.
            allocatedArea = previous;
            buildingMode = previousBuildingMode;
            nextBuildingMode = previousNextBuildingMode;
            portal = previousPortal;
            chunkX = previousChunkX;
            chunkY = previousChunkY;
            yardSpace = previousYardSpace;
            yardOffset = previousYardOffset;
            player.getTemporaryAttributes().put("yard", yardOffset);
            if (area != previous) {
                try {
                    MapBuilder.destroy(area);
                } catch (Exception destroyError) {
                    log.error("", destroyError);
                }
            }
            player.unlock();
            player.sendMessage("Your house could not be loaded right now, please try again.");
            return false;
        }
    }

    /** Whether a tile in room coordinates lies inside the yard of the house being built. */
    private boolean isInsideHouseBounds(final Location roomTile) {
        final int space = generateYardSpace();
        final int offset = (10 - space) / 2;
        final int roomX = (roomTile.getX() >> 3) - offset;
        final int roomY = (roomTile.getY() >> 3) - offset;
        return roomTile.getX() >= 0 && roomTile.getY() >= 0 && roomX >= 0 && roomY >= 0 && roomX < space && roomY < space
                && roomTile.getPlane() >= 0 && roomTile.getPlane() <= 3;
    }

    /** Puts a player (the owner returning to a house guests kept open, or a guest) into the loaded instance at the portal. */
    private void join(final Player p) {
        if (p == player) {
            ownerInside = true;
        } else {
            guests.add(p);
        }
        enterInstance(p, getPortalTile());
        if (p == player) {
            servantManager.onHouseLoaded();
            refreshHouseOptions();
        }
    }

    private void enterInstance(final Player p, final Location destination) {
        final Construction previous = p.getCurrentHouse();
        if (previous != null && previous != this) {
            // Straight from another house (e.g. a guest teleporting home): that house loses its occupant.
            previous.onLeft(p, false);
        }
        p.getTemporaryAttributes().put(VISITING_ATTR, this);
        if (!(p.getControllerManager().getController() instanceof ConstructionController)) {
            p.getControllerManager().startController(new ConstructionController());
        }
        p.getInterfaceHandler().sendInterface(InterfacePosition.OVERLAY, 71);
        p.getPacketDispatcher().sendMinimapState(MinimapState.MAP_DISABLED);
        p.setLocation(destination);
        p.lock();
        final int regionId = destination.getRegionId();
        WorldTasksManager.schedule(new WorldTask() {
            private int ticks;

            @Override
            public void run() {
                if (p.isFinished()) {
                    stop();
                    return;
                }
                ticks++;
                if (ticks < LOAD_TIMEOUT_TICKS) {
                    final Region region = World.getRegion(regionId);
                    if (ticks < 2 || p.isLoadingRegion() || region == null || region.getLoadStage() != 2) {
                        return;
                    }
                }
                p.unlock();
                p.getInterfaceHandler().closeInterface(InterfacePosition.OVERLAY);
                p.getPacketDispatcher().sendSoundEffect(ENTER_SOUND);
                p.getPacketDispatcher().sendMinimapState(MinimapState.ENABLED);
                stop();
            }
        }, 0, 0);
    }

    /**
     * Lets another player in, as long as the owner is home and not building.
     */
    public void enterAsGuest(final Player guest) {
        if (guest == player) {
            enterHouseByTeleport();
            return;
        }
        if (player.isFinished() || !isLoaded() || !ownerInside) {
            guest.sendMessage("That player is not at home.");
            return;
        }
        if (player.getSocialManager().getIgnores().contains(com.zenyte.game.world.entity.player.SocialManager.formatName(guest))) {
            guest.sendMessage("That player is not at home.");
            return;
        }
        if (buildingMode) {
            guest.sendMessage("That player is in building mode.");
            return;
        }
        if (locked) {
            guest.sendMessage("That player's house is locked.");
            return;
        }
        join(guest);
    }

    /** Owner or guest leaves through the exit portal (or a command): placed outside the house's portal. */
    public void leaveHouse() {
        exit(player);
    }

    public void exit(final Player p) {
        final Location outside = new Location(house.getLocation());
        onLeft(p, true);
        p.setLocation(outside);
    }

    /** Removes every guest from the house. */
    public void expelGuests(final String message) {
        for (final Player guest : new ArrayList<>(guests)) {
            if (guest == null) {
                continue;
            }
            if (message != null && !guest.isFinished()) {
                guest.sendMessage(message);
            }
            if (guest.getCurrentHouse() == this) {
                exit(guest);
            }
            guests.remove(guest);
        }
        closeIfEmpty();
    }

    /**
     * Bookkeeping for a player who is no longer in the house (left, teleported out, logged out or died).
     *
     * @param stopController whether to stop their {@link ConstructionController} here (not when the controller manager is
     *                       already removing it).
     */
    public void onLeft(final Player p, final boolean stopController) {
        if (p.getTemporaryAttributes().get(VISITING_ATTR) == this) {
            p.getTemporaryAttributes().remove(VISITING_ATTR);
        }
        p.getTemporaryAttributes().remove("FurnitureSpace");
        p.getTemporaryAttributes().remove("RoomCreation");
        if (catsOnBlanket.remove(p) != null && p.getFollower() != null) {
            p.getFollower().unlock();
        }
        if (p == player) {
            ownerInside = false;
            servantManager.onOwnerLeft();
        } else {
            guests.remove(p);
        }
        if (stopController && p.getControllerManager().getController() instanceof ConstructionController) {
            p.getControllerManager().forceStop();
        }
        closeIfEmpty();
    }

    /** Called by the controller when a player inside the house logs out. */
    public void onLogout(final Player p) {
        p.forceLocation(new Location(house.getLocation()));
        if (p == player) {
            expelGuests("The owner of the house has left.");
        }
        onLeft(p, false);
    }

    /**
     * Safe death inside a house (combat ring, dungeon, PvP worlds): no items are lost, the player is placed outside the house
     * portal with their stats restored.
     */
    public void onDeath(final Player p) {
        p.lock();
        p.stopAll();
        WorldTasksManager.schedule(new WorldTask() {
            private int ticks;

            @Override
            public void run() {
                if (p.isFinished()) {
                    stop();
                    return;
                }
                if (ticks == 0) {
                    p.setAnimation(DEATH_ANIMATION);
                } else if (ticks == 3) {
                    p.sendMessage("Oh dear, you are dead!");
                    p.reset();
                    p.setAnimation(Animation.STOP);
                    p.blockIncomingHits();
                    if (p.getCurrentHouse() == Construction.this) {
                        // Losing a ring fight puts you next to the ring (OSRS: "teleported to another spot in the room").
                        final Location ringExit = combat.ringAt(p.getLocation()) != null ? combat.ringExit(p.getLocation()) : null;
                        if (ringExit != null) {
                            p.setLocation(ringExit);
                        } else {
                            exit(p);
                        }
                    }
                } else if (ticks == 4) {
                    p.unlock();
                    p.getAppearance().resetRenderAnimation();
                    stop();
                    return;
                }
                ticks++;
            }
        }, 0, 1);
    }

    private void closeIfEmpty() {
        if (allocatedArea == null || ownerInside || hasGuests()) {
            return;
        }
        final AllocatedArea area = allocatedArea;
        allocatedArea = null;
        buildingMode = false;
        locked = false;
        servantManager.onHouseClosed();
        combat.onHouseClosed();
        finishFurnitureNpcs();
        roamingPets.clear();
        scheduleDestroy(area);
    }

    private void finishFurnitureNpcs() {
        final List<com.zenyte.game.world.entity.npc.NPC> npcs = new ArrayList<>(furnitureNpcs.values());
        furnitureNpcs.clear();
        for (final com.zenyte.game.world.entity.npc.NPC npc : npcs) {
            if (npc != null && !npc.isFinished()) {
                npc.finish();
            }
        }
    }

    private void scheduleDestroy(final AllocatedArea area) {
        WorldTasksManager.schedule(() -> {
            if (area == allocatedArea) {
                return;
            }
            try {
                MapBuilder.destroy(area);
            } catch (Exception e) {
                log.error("", e);
            }
        }, OLD_AREA_DESTROY_DELAY);
    }

    /**
     * House options "Building mode": rebuilds the house in the other mode when the owner is inside, otherwise remembers the
     * choice for the next house teleport.
     */
    public void setBuildingModeOption(final boolean building) {
        if (ownerInside && player.getCurrentHouse() == this && contains(player.getLocation())) {
            if (building == buildingMode) {
                refreshHouseOptions();
                return;
            }
            if (building && hasGuests()) {
                player.sendMessage("You can't turn on building mode while you have guests in your house.");
                refreshHouseOptions();
                return;
            }
            enterHouse(building, getRelationalSpawnTile());
            return;
        }
        nextBuildingMode = building;
        refreshHouseOptions();
    }

    public void setDoorModeOption(final int mode) {
        if (mode == doorMode || mode < DOORS_CLOSED || mode > DOORS_NONE) {
            refreshHouseOptions();
            return;
        }
        doorMode = mode;
        refreshHouseOptions();
        // Doors are only placed outside building mode; rebuild so the change shows straight away.
        if (ownerInside && !buildingMode && player.getCurrentHouse() == this && contains(player.getLocation())) {
            build(false, getRelationalSpawnTile());
        }
    }

    public void toggleLock() {
        locked = !locked;
        player.sendMessage(locked ? "Your house is now locked; no more guests can enter." : "Your house is now unlocked; guests can enter again.");
    }

    public void leaveHouse(final Player p) {
        if (p.getCurrentHouse() == this) {
            exit(p);
        }
    }

    /* ------------------------------------------------------------------------------------------------------------------------
     * Map construction
     * ---------------------------------------------------------------------------------------------------------------------- */

    private void createLandscape() {
        for (int x = 0; x < yardSpace; x++) {
            for (int y = 0; y < yardSpace; y++) {
                try {
                    MapBuilder.copySquare(allocatedArea, 1, RoomType.DUNGEON_LAND.getChunkX(), RoomType.DUNGEON_LAND.getChunkY(), decoration == 2 ? 2 : decoration == 3 ? 3 : 0, chunkX + x, chunkY + y, 0, 0);
                    MapBuilder.copySquare(allocatedArea, 1, RoomType.LAND.getChunkX(), RoomType.LAND.getChunkY(), decoration == 2 ? 2 : decoration == 3 ? 3 : 0, chunkX + x, chunkY + y, 1, 0);
                } catch (Exception e) {
                    log.error("", e);
                }
            }
        }
    }

    private void createRooms() {
        for (final RoomReference reference : references) {
            if (reference == null) {
                continue;
            }
            addRoof(reference);
            try {
                MapBuilder.copySquare(allocatedArea, 1, reference.getRoom().getChunkX() + (decoration >= 4 ? 8 : 0), reference.getRoom().getChunkY(), decoration & 3, roomChunkX(reference.getX()), roomChunkY(reference.getY()), reference.getPlane(), reference.getRotation());
            } catch (Exception e) {
                log.error("", e);
            }
        }
    }

    private void fillRooms() {
        for (final RoomReference reference : references) {
            if (reference == null) {
                continue;
            }
            for (final FurnitureData data : new ArrayList<>(reference.getFurnitureData())) {
                if (data == null) {
                    continue;
                }
                refreshObject(reference, data, false, false);
            }
            refreshWindows(reference);
        }
    }

    private static boolean isOutdoor(final RoomType room) {
        return room == RoomType.FORMAL_GARDEN || room == RoomType.GARDEN || room == RoomType.MENAGERIE_OUTDOORS || room == RoomType.SUPERIOR_GARDEN;
    }

    private void addDoors() {
        if (doorMode == DOORS_NONE) {
            return;
        }
        for (final RoomReference room : references) {
            if (room == null || isOutdoor(room.getRoom())) {
                continue;
            }
            final Location base = getRoomBase(room);
            for (int i = 0; i < DOOR_POSITIONS.length; i++) {
                final int[] coords = DOOR_POSITIONS[i];
                final int x = coords[0];
                final int y = coords[1];
                final int rotation = coords[2];
                final int rx = x == 0 ? -1 : x == 7 ? 1 : 0;
                final int ry = y == 0 ? -1 : y == 7 ? 1 : 0;
                final int offset = decoration == 0 ? (i % 2 == 0 ? 0 : 1) : (i % 2 != 0 ? 0 : 1);
                final WorldObject o = new WorldObject(DOOR_IDS[decoration] + offset, 0, rotation, base.getX() + x, base.getY() + y, room.getPlane());
                final RoomReference ref = getReference(room.getX() + rx, room.getY() + ry, room.getPlane());
                if (ref != null && !isOutdoor(ref.getRoom())) {
                    continue;
                }
                if (!World.isTileFree(o, 1)) {
                    continue;
                }
                if (doorMode == DOORS_OPEN) {
                    adjustDoor(o, x, y);
                }
                World.spawnObject(o);
            }
        }
    }

    /**
     * Adjusts the door's location and opens it when the render doors open option is enabled.
     *
     * @param door object to adjust.
     */
    private void adjustDoor(final WorldObject door, final int x, final int y) {
        if (y == 0 || y == 7) {
            door.moveLocation(0, y == 7 ? 1 : -1, 0);
            if (x == 3) {
                door.setRotation(0);
            } else if (x == 4) {
                door.setRotation(2);
            }
        } else {
            door.moveLocation(x == 7 ? 1 : -1, 0, 0);
            if (y == 3) {
                door.setRotation(3);
            } else if (y == 4) {
                door.setRotation(1);
            }
        }
        door.setId(door.getId() + 2);
    }

    private void addRoof(final RoomReference reference) {
        final RoomType room = reference.getRoom();
        if (isOutdoor(room)) {
            return;
        }
        for (final RoomReference ref : references) {
            if (ref.getX() == reference.getX() && ref.getY() == reference.getY() && ref.getPlane() == reference.getPlane() + 1) {
                return;
            }
        }
        if (reference.getPlane() > 0) {
            try {
                if (decoration < 4)
                    MapBuilder.copySquare(allocatedArea, 1, RoomType.ROOFS_A.getChunkX(), RoomType.ROOFS_A.getChunkY(), decoration, roomChunkX(reference.getX()), roomChunkY(reference.getY()), reference.getPlane() + 1, reference.getRotation());
                else
                    MapBuilder.copySquare(allocatedArea, 1, RoomType.ROOFS_B.getChunkX(), RoomType.ROOFS_B.getChunkY(), decoration, roomChunkX(reference.getX()), roomChunkY(reference.getY()), reference.getPlane() + 1, reference.getRotation());
            } catch (Exception e) {
                log.error("", e);
            }
        }
    }

    /**
     * Removes a room of this house (door hotspot, staircase or dungeon entrance "Remove-room"). Only the owner may, standing in
     * the house in building mode (guests can't be inside then), so the saved layout and the live instance never disagree.
     */
    public void removeRoom(final Player p, final RoomReference reference) {
        if (p != player || !ownerInside || player.getCurrentHouse() != this || !contains(player.getLocation())) {
            p.sendMessage("You can only do that in your own house.");
            return;
        }
        if (!buildingMode || hasGuests()) {
            p.sendMessage("You can only do that in building mode.");
            return;
        }
        boolean found = false;
        for (final RoomReference room : references) {
            if (room == reference) {
                found = true;
                break;
            }
        }
        if (!found) {
            return;
        }
        references.remove(reference);
        enterHouse(true, getRelationalSpawnTile());
    }

    public void removeObject(final RoomReference reference, final WorldObject object) {
        for (final FurnitureData furniture : new ArrayList<>(reference.getFurnitureData())) {
            final boolean variant = com.zenyte.game.content.skills.construction.objects.portalnexus.PortalNexus.isVariantOf(furniture.getFurniture(), object.getId());
            for (final int i : furniture.getFurniture().getObjectIds()) {
                if ((PohIds.resolve(i) == object.getId() || variant) && object.getXInChunk() == furniture.getLocation().getX() && object.getYInChunk() == furniture.getLocation().getY()) {
                    refreshObject(reference, furniture, true, true);
                    reference.getFurnitureData().remove(furniture);
                    if (furniture.getSpace() == FurnitureSpace.QUEST_RUG_SPACE || furniture.getSpace() == FurnitureSpace.SKILL_RUG_SPACE) {
                        enterHouse(true, getRelationalSpawnTile());
                    }
                    return;
                }
            }
        }
    }

    public void sendRoomCreationMenu(final WorldObject object) {
        final int xInChunk = player.getLocation().getXInChunk();
        final int yInChunk = player.getLocation().getYInChunk();
        int roomX = player.getLocation().getChunkX() - chunkX + yardOffset;
        int roomY = player.getLocation().getChunkY() - chunkY + yardOffset;
        if (xInChunk == 7) {
            roomX += 1;
        } else if (xInChunk == 0) {
            roomX -= 1;
        } else if (yInChunk == 7) {
            roomY += 1;
        } else if (yInChunk == 0) {
            roomY -= 1;
        }
        final RoomReference reference = getReference(roomX, roomY, object.getPlane());
        if (reference != null) {
            player.getDialogueManager().start(new RoomRemovingD(player, this, reference));
        } else {
            if (object.getPlane() == 2) {
                final RoomReference bottomRef = getReference(roomX, roomY, 1);
                final RoomType room = bottomRef == null ? null : bottomRef.getRoom();
                if (bottomRef == null || isOutdoor(room)) {
                    player.sendMessage("You can't build a room that would have no room to support it.");
                    return;
                }
            }
            player.getInterfaceHandler().sendInterface(InterfacePosition.CENTRAL, 212);
            player.getTemporaryAttributes().put("RoomCreation", new int[] { roomX, roomY, object.getPlane() });
            player.setCloseInterfacesEvent(() -> player.getTemporaryAttributes().remove("RoomCreation"));
        }
    }

    public void buildFurniture(final int slot) {
        if (player.getTemporaryAttributes().get("FurnitureSpace") == null || !(player.getTemporaryAttributes().get("FurnitureSpace") instanceof Object[])) {
            return;
        }
        final Object[] data = (Object[]) player.getTemporaryAttributes().get("FurnitureSpace");
        final Location loc = (Location) data[4];
        final int roomX = loc.getChunkX() - chunkX + yardOffset;
        final int roomY = loc.getChunkY() - chunkY + yardOffset;
        final RoomReference reference = getReference(roomX, roomY, player.getPlane());
        if (reference == null) {
            return;
        }
        final Location chunkCoords = (Location) data[1];
        final Location objectCoords = getRoomTile(reference, chunkCoords.getX(), chunkCoords.getY());
        final FurnitureSpace space = (FurnitureSpace) data[0];
        if (slot < 0 || slot >= space.getFurnitures().length) {
            return;
        }
        final int type = (int) data[2];
        final int rotation = (int) data[3];
        final Furniture furniture = space.getFurnitures()[slot];
        if (player.getSkills().getLevel(SkillConstants.CONSTRUCTION) < furniture.getLevel()) {
            player.sendMessage("You need a Construction level of " + furniture.getLevel() + " to build that.");
            return;
        }
        if (!com.zenyte.game.content.skills.construction.objects.portalnexus.PortalNexus.checkBuild(player, furniture)) {
            return;
        }
        if (!hasMaterials(furniture)) {
            return;
        }
        player.getInterfaceHandler().closeInterface(InterfacePosition.CENTRAL);
        final WorldObject object = new WorldObject(PohIds.resolve(furniture.getObjectId()), type, rotation, objectCoords);
        final FurnitureData furnitureData = new FurnitureData(space, furniture, chunkCoords, type, rotation);
        if (player.getPlane() >= 1 && (space == FurnitureSpace.QUEST_HALL_STAIRCASE || space == FurnitureSpace.SKILL_HALL_STAIRCASE)) {
            player.getTemporaryAttributes().put("constStaircase", object);
            player.getTemporaryAttributes().put("constReference", reference);
            player.getTemporaryAttributes().put("constSlot", slot);
            player.getTemporaryAttributes().put("constFurnData", furnitureData);
            player.getDialogueManager().start(new BuildStaircaseD(player, object, reference, furnitureData, slot));
            return;
        }
        build(object, furnitureData, reference, space);
    }

    /**
     * The materials are taken when the piece is finished, so check up front that all of them are there. Pieces made of regular
     * planks also need nails (one per plank, bent nails cost extra swings).
     */
    private boolean hasMaterials(final Furniture furniture) {
        if (furniture.getNails() > 0 && (getBestNail(player.getInventory()) >> 16) <= 0) {
            player.sendMessage("You need some nails to build that.");
            return false;
        }
        for (final Item material : furniture.getMaterials()) {
            // Nails are used while hammering; another piece of furniture in the recipe is the one being upgraded.
            if (material == null || ArrayUtils.contains(NAILS, material.getId()) || Furniture.DISPLAYED_MAP.containsKey(material.getId())) {
                continue;
            }
            if (material.getId() == WATERING_CAN.getId()) {
                if (!hasWateringCan()) {
                    player.sendMessage("You need a watering can with some water in it to plant that.");
                    return false;
                }
                continue;
            }
            if (player.getInventory().getAmountOf(material.getId()) < material.getAmount()) {
                player.sendMessage("You don't have the materials needed to build that.");
                return false;
            }
        }
        return true;
    }

    private boolean hasWateringCan() {
        for (int id = 5333; id <= 5340; id++) {
            if (player.getInventory().containsItem(id, 1)) {
                return true;
            }
        }
        return player.getInventory().containsItem(13353, 1);
    }

    private void build(final WorldObject object, final FurnitureData data, final RoomReference reference, final FurnitureSpace space) {
        final Furniture furniture = data.getFurniture();
        final int nails = furniture.getNails();
        final int ticks = furniture.getTicks();
        player.lock();
        final Inventory inventory = player.getInventory();
        final boolean water = furniture.isWateringCan();
        final int count = hammer(water ? -1 : object.getType(), nails, inventory, water ? 2 : nails != -1 ? nails : Utils.random(ticks, ticks + 3));
        WorldTasksManager.schedule(new WorldTask() {

            private int amount = count > 10 ? 10 : count;

            private boolean stop;

            @Override
            public void run() {
                if (!stop && amount > 0) {
                    amount = hammer(water ? -1 : object.getType(), nails, inventory, amount);
                }
                if (amount <= 0) {
                    if (!stop) {
                        stop = true;
                        return;
                    }
                    removeMaterials(furniture);
                    player.getSkills().addXp(SkillConstants.CONSTRUCTION, furniture.getXp());
                    if (water) {
                        player.getSkills().addXp(SkillConstants.FARMING, furniture.getXp());
                    }
                    World.spawnObject(object);
                    reference.getFurnitureData().add(data);
                    refreshObject(reference, data, false, true);
                    player.unlock();
                    if (space == FurnitureSpace.QUEST_RUG_SPACE || space == FurnitureSpace.SKILL_RUG_SPACE) {
                        enterHouse(true, getRelationalSpawnTile());
                    }
                    stop();
                }
            }
        }, 1, 1);
    }

    /** Takes the materials of a finished piece (nails are used up while hammering; the watering can is a tool). */
    private void removeMaterials(final Furniture furniture) {
        for (final Item material : furniture.getMaterials()) {
            if (material == null || material.getId() == WATERING_CAN.getId() || ArrayUtils.contains(NAILS, material.getId()) || Furniture.DISPLAYED_MAP.containsKey(material.getId())) {
                continue;
            }
            player.getInventory().deleteItem(material.getId(), material.getAmount());
        }
    }

    private int hammer(final int type, final int nails, final Inventory inventory, int count) {
        if (type == 22) {
            player.setAnimation(FLOOR_ANIM);
        } else if (type == 5) {
            player.setAnimation(SHELF_ANIM);
        } else if (type == -1) {
            player.setAnimation(WATERING_ANIM);
        } else {
            player.setAnimation(REG_ANIM);
        }
        if (nails > 0) {
            final int nail = getBestNail(inventory);
            final int id = nail & 16383;
            final boolean stop = nail >> 16 <= 0;
            if (id > 0) {
                inventory.deleteItem(id, 1);
            }
            final int index = ArrayUtils.indexOf(NAILS, id);
            final int chance = Math.max(index * 10, 5);
            if (stop) {
                count = 0;
            } else if (Utils.random(100) >= chance) {
                count--;
            }
        } else {
            count--;
        }
        return count;
    }

    private int getBestNail(final Inventory inventory) {
        int nail = 0;
        long nails = 0;
        for (int i = 27; i >= 0; i--) {
            final Item item = inventory.getItem(i);
            if (item == null) {
                continue;
            }
            final int id = item.getId();
            if (ArrayUtils.contains(NAILS, id)) {
                nails += item.getAmount();
                final int index = ArrayUtils.indexOf(NAILS, id);
                if (index == -1) {
                    continue;
                }
                final int currentIndex = ArrayUtils.indexOf(NAILS, nail);
                if (currentIndex == -1 || currentIndex > index) {
                    nail = NAILS[index];
                }
            }
        }
        if (nails > 16383) {
            nails = 16383;
        }
        return (int) (nail | nails << 16);
    }

    public void buildStaircase(final boolean up, final WorldObject object, final RoomReference ref, final FurnitureData furnitureData, final int slotId) {
        if (object == null || ref == null || furnitureData == null) {
            return;
        }
        if (up) {
            final RoomReference above = this.getReference(ref.getX(), ref.getY(), ref.getPlane() + 1);
            if (above != null) {
                if ((above.getRoom() != RoomType.SKILL_HALL_DS && above.getRoom() != RoomType.QUEST_HALL_DS && above.getRoom() != RoomType.SKILL_HALL && above.getRoom() != RoomType.QUEST_HALL) || ref.getRotation() != above.getRotation()) {
                    player.sendMessage("You cannot connect stairs to the room above.");
                    return;
                }
            }
            ref.getFurnitureData().add(furnitureData);
            enterHouse(true, getRelationalSpawnTile());
        } else {
            final RoomReference below = this.getReference(ref.getX(), ref.getY(), ref.getPlane() - 1);
            if (below != null) {
                if ((below.getRoom() != RoomType.SKILL_HALL_DS && below.getRoom() != RoomType.QUEST_HALL_DS && below.getRoom() != RoomType.SKILL_HALL && below.getRoom() != RoomType.QUEST_HALL && below.getRoom() != RoomType.DUNGEON_STAIRS_ROOM) || ref.getRotation() != below.getRotation()) {
                    player.sendMessage("You cannot connect stairs to the room below.");
                    return;
                }
            }
            final RoomType room = ref.getRoom() == RoomType.SKILL_HALL ? RoomType.SKILL_HALL_DS : ref.getRoom() == RoomType.QUEST_HALL ? RoomType.QUEST_HALL_DS : null;
            if (room == null) {
                return;
            }
            final FurnitureSpace space = room == RoomType.SKILL_HALL_DS ? FurnitureSpace.SKILL_HALL_STAIRCASE_DS : room == RoomType.QUEST_HALL_DS ? FurnitureSpace.QUEST_HALL_STAIRCASE_DS : null;
            if (space == null) {
                return;
            }
            final FurnitureData furniture = new FurnitureData(space, space.getFurnitures()[slotId], furnitureData.getLocation(), furnitureData.getType(), furnitureData.getRotation());
            ref.setRoom(room);
            ref.getFurnitureData().add(furniture);
            enterHouse(true, getRelationalSpawnTile());
        }
    }

    public void removeStaircase(final RoomReference ref, final WorldObject object) {
        removeObject(ref, object);
        final RoomType room = ref.getRoom() == RoomType.SKILL_HALL_DS ? RoomType.SKILL_HALL : ref.getRoom() == RoomType.QUEST_HALL_DS ? RoomType.QUEST_HALL : ref.getRoom();
        if (ref.getRoom() == RoomType.SKILL_HALL || ref.getRoom() == RoomType.SKILL_HALL_DS || ref.getRoom() == RoomType.QUEST_HALL || ref.getRoom() == RoomType.QUEST_HALL_DS || ref.getRoom() == RoomType.DUNGEON_STAIRS_ROOM) {
            if (ref.getPlane() < 2) {
                final RoomReference above = this.getReference(ref.getX(), ref.getY(), ref.getPlane() + 1);
                if (above != null) {
                    final FurnitureData staircase = above.getStaircase();
                    if (staircase != null) {
                        if (staircase.getFurniture().name().contains("_DS")) {
                            above.getFurnitureData().remove(staircase);
                        }
                    }
                }
            }
            if (ref.getPlane() > 0) {
                final RoomReference below = this.getReference(ref.getX(), ref.getY(), ref.getPlane() - 1);
                if (below != null) {
                    final FurnitureData staircase = below.getStaircase();
                    if (staircase != null) {
                        if (!staircase.getFurniture().name().contains("_DS")) {
                            below.getFurnitureData().remove(staircase);
                        }
                    }
                }
            }
        }
        if (room == null) {
            return;
        }
        ref.setRoom(room);
        enterHouse(true, getRelationalSpawnTile());
    }

    /**
     * Transforms the walls where windows are in the house into the real windows, not the transparent crap.
     *
     * @return window id of the respective decoration.
     */
    private int getWindow() {
        switch(decoration) {
            case 0:
                return 13099;
            case 1:
                return 13091;
            case 2:
                return 13005;
            case 3:
                return 13112;
            case 4:
                return 10816;
            case 5:
                return 13117;
            default:
                return 27083;
        }
    }

    /**
     * A separate method to refresh the windows of a built room (for when there are no objects built)
     *
     * @param room room to refresh.
     */
    private void refreshWindows(final RoomReference room) {
        final Location base = getRoomBase(room);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                final WorldObject[] objects = getTemplateObjects(base.transform(x, y, 0));
                if (objects == null) {
                    continue;
                }
                for (final WorldObject object : objects) {
                    if (object == null) {
                        continue;
                    }
                    if (object.getId() == ObjectId.WINDOW_13830) {
                        final WorldObject o = new WorldObject(object);
                        o.setId(getWindow());
                        World.spawnObject(o);
                        continue;
                    }
                    if (!buildingMode) {
                        removeHotspot(object);
                    }
                }
            }
        }
    }

    private void removeHotspot(final WorldObject object) {
        if (object.getDefinitions().containsOption(4, "Build") && World.containsObjectWithId(object, object.getId())) {
            if (object.getDefinitions().getName().equals("Window space")) {
                World.spawnObject(new WorldObject(WALL_IDS[decoration], object.getType(), object.getRotation(), object.getX(), object.getY(), object.getPlane()));
            } else {
                World.removeObject(object);
            }
        }
    }

    /**
     * Refreshes a certain built hotspot.
     */
    private void refreshObject(final RoomReference room, final FurnitureData data, final boolean remove, final boolean adjustingFurniture) {
        if (data.getSpace() == FurnitureSpace.QUEST_HALL_STAIRCASE || data.getSpace() == FurnitureSpace.QUEST_HALL_STAIRCASE_DS || data.getSpace() == FurnitureSpace.SKILL_HALL_STAIRCASE || data.getSpace() == FurnitureSpace.SKILL_HALL_STAIRCASE_DS) {
            refreshHalls(room, true);
        } else if (data.getSpace() == FurnitureSpace.QUEST_RUG_SPACE || data.getSpace() == FurnitureSpace.SKILL_RUG_SPACE) {
            refreshHalls(room, false);
        }
        refreshObjectsInChunk(room, data, remove, adjustingFurniture);
    }

    /**
     * Refreshes all the tied objects in a specific chunk.
     */
    private void refreshObjectsInChunk(final RoomReference room, final FurnitureData data, final boolean remove, final boolean adjustingFurniture) {
        if (data.getSpace() == null || data.getFurniture() == null) {
            return;
        }
        final Location base = getRoomBase(room);
        int refreshType = 0;
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                final WorldObject[] objects = getTemplateObjects(base.transform(x, y, 0));
                if (objects == null) {
                    continue;
                }
                for (final WorldObject object : objects) {
                    if (object == null) {
                        continue;
                    }
                    final int[] spotIds = data.getSpace().getSpotIds();
                    for (int i = 0; i < spotIds.length; i++) {
                        if (object.getId() != spotIds[i]) {
                            continue;
                        }
                        if (remove) {
                            final WorldObject o = new WorldObject(object);
                            o.setId(spotIds[i]);
                            World.spawnObject(o);
                        } else {
                            final WorldObject o = new WorldObject(object);
                            final Furniture furniture = data.getFurniture();
                            final int[] objectIds = furniture.getObjectIds();
                            o.setId(PohIds.resolve(objectIds[Math.min(i, objectIds.length - 1)]));
                            if (furniture.getAction() != null) {
                                furniture.getAction().onRefresh(this, room, data, o, !adjustingFurniture);
                            }
                            if (adjustingFurniture) {
                                if (furniture.getRefreshType() == 2 && refreshType != 2) {
                                    refreshType = 2;
                                } else if (furniture.getRefreshType() == 1 && refreshType != 2) {
                                    refreshType = 1;
                                }
                            }
                            if (o.getId() <= 0) {
                                if (!buildingMode) {
                                    World.removeObject(o);
                                }
                            } else {
                                World.spawnObject(o);
                            }
                            if (o.getId() == EXIT_PORTAL && portal == null) {
                                portal = new Location(o.getX() - 1, o.getY() - 1, o.getPlane());
                            }
                        }
                    }
                }
            }
        }
        if (refreshType == 1) {
            for (final FurnitureData dat : new ArrayList<>(room.getFurnitureData())) {
                if (dat == null) {
                    continue;
                }
                refreshObject(room, dat, false, false);
            }
        } else if (refreshType == 2) {
            enterHouse(true, getRelationalSpawnTile());
        }
    }

    private void refreshHalls(final RoomReference room, final boolean rug) {
        final Location base = getRoomBase(room);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                final WorldObject[] objects = getTemplateObjects(base.transform(x, y, 0));
                if (objects == null) {
                    continue;
                }
                for (final WorldObject object : objects) {
                    if (object == null) {
                        continue;
                    }
                    if (rug && object.getDefinitions().getName().equals("Rug space") || !rug && object.getDefinitions().getName().equals("Stair Space")) {
                        World.removeObject(object);
                    }
                }
            }
        }
    }

    public void sendFurnitureCreationMenu(final WorldObject object) {
        final FurnitureSpace space = FurnitureSpace.getSpaceByObject(object);
        if (space == null) {
            player.sendMessage("Nothing can be built here yet.");
            return;
        }
        player.getInterfaceHandler().sendInterface(InterfacePosition.CENTRAL, 458);
        final List<String> args = new ArrayList<String>();
        for (final Furniture furniture : space.getFurnitures()) {
            final StringBuilder materials = new StringBuilder(ItemDefinitions.get(furniture.getItemId()).getName() + "|");
            for (final Item item : furniture.getMaterials()) {
                if (item == null) {
                    continue;
                }
                materials.append(item.getDefinitions().getName()).append(": ").append(item.getAmount()).append("<br>");
            }
            args.add(materials.toString());
        }
        for (int i = 0; i < space.getFurnitures().length; i++) {
            player.getPacketDispatcher().sendClientScript(1404, i + 1, space.getFurnitures()[i].getItemId(), space.getFurnitures()[i].getLevel(), args.get(i), 1);
        }
        player.getPacketDispatcher().sendClientScript(1406, space.getFurnitures().length, 0);
        final Object[] data = new Object[] { space, new Location(object.getXInChunk(), object.getYInChunk(), object.getPlane()), object.getType(), object.getRotation(), object };
        player.getTemporaryAttributes().put("FurnitureSpace", data);
    }

    public void createRoom(final RoomReference reference) {
        final RoomType room = reference.getRoom();
        if (player.getSkills().getLevelForXp(SkillConstants.CONSTRUCTION) < room.getLevel()) {
            player.getDialogueManager().start(new PlainChat(player, "You need a Construction level of " + room.getLevel() + " to build that room."));
            return;
        }
        if (player.getInventory().getAmountOf(995) < room.getPrice()) {
            player.sendMessage("You can't afford to build that room.");
            return;
        }
        references.add(reference);
        if (room == RoomType.SKILL_HALL || room == RoomType.QUEST_HALL || room == RoomType.SKILL_HALL_DS || room == RoomType.QUEST_HALL_DS) {
            if (reference.getPlane() == 1) {
                final RoomReference below = this.getReference(reference.getX(), reference.getY(), reference.getPlane() - 1);
                if (below != null && below.getRotation() == reference.getRotation()) {
                    final RoomType bRoom = below.getRoom();
                    if (bRoom == RoomType.SKILL_HALL || bRoom == RoomType.QUEST_HALL) {
                        final FurnitureData staircase = below.getStaircase();
                        if (staircase != null) {
                            reference.setRoom(room == RoomType.SKILL_HALL ? RoomType.SKILL_HALL_DS : RoomType.QUEST_HALL_DS);
                            final String name = staircase.getFurniture().name();
                            final FurnitureData aboveStaircase = new FurnitureData(room == RoomType.QUEST_HALL ? FurnitureSpace.QUEST_HALL_STAIRCASE_DS : FurnitureSpace.SKILL_HALL_STAIRCASE_DS, Furniture.valueOf(Furniture.class, name + "_DS"), new Location(staircase.getLocation().getX(), staircase.getLocation().getY(), staircase.getLocation().getPlane() + 1), staircase.getType(), staircase.getRotation());
                            reference.getFurnitureData().add(aboveStaircase);
                        }
                    }
                }
            }
        }
        player.getInventory().deleteItem(new Item(995, room.getPrice()));
        player.getTemporaryAttributes().remove("CreatingRoom");
        enterHouse(true, getRelationalSpawnTile());
        sendRoomCount();
    }

    public void createRoom(final int slot) {
        final int[] location = (int[]) player.getTemporaryAttributes().get("RoomCreation");
        final RoomType room = RoomType.getRoomBySlot(slot);
        if (room == null || location == null) {
            if (location != null && room == null) {
                player.sendMessage("That room can't be built yet.");
            }
            return;
        }
        final int[] offsetLocation = new int[] { location[0] - yardOffset, location[1] - yardOffset };
        if (offsetLocation[0] < 0 || offsetLocation[1] < 0 || offsetLocation[0] >= yardSpace || offsetLocation[1] >= yardSpace) {
            player.sendMessage("You can't place a room here.");
            player.getInterfaceHandler().closeInterface(InterfacePosition.CENTRAL);
            return;
        }
        if (player.getPlane() == 0) {
            if (isOutdoor(room)) {
                player.getInterfaceHandler().closeInterface(InterfacePosition.CENTRAL);
                player.getDialogueManager().start(new PlainChat(player, "This room can only be built on the ground."));
                return;
            }
        } else {
            if (room == RoomType.DUNGEON_CORRIDOR || room == RoomType.DUNGEON_JUNCTION || room == RoomType.DUNGEON_STAIRS_ROOM || room == RoomType.OUBLIETTE) {
                player.getInterfaceHandler().closeInterface(InterfacePosition.CENTRAL);
                player.getDialogueManager().start(new PlainChat(player, "This room can only be built at the bottom."));
                return;
            }
            if (player.getPlane() == 2 && isOutdoor(room)) {
                player.getInterfaceHandler().closeInterface(InterfacePosition.CENTRAL);
                player.getDialogueManager().start(new PlainChat(player, "This room can only be built on the ground."));
                return;
            }
        }
        if (player.getSkills().getLevelForXp(SkillConstants.CONSTRUCTION) < room.getLevel()) {
            player.getDialogueManager().start(new PlainChat(player, "You need a Construction level of " + room.getLevel() + " to build that room."));
            player.getInterfaceHandler().closeInterface(InterfacePosition.CENTRAL);
            return;
        }
        if (player.getInventory().getAmountOf(995) < room.getPrice()) {
            player.sendMessage("You can't afford to build that room.");
            return;
        }
        if (room == RoomType.PORTAL_NEXUS && !getRooms(RoomType.PORTAL_NEXUS).isEmpty()) {
            player.getInterfaceHandler().closeInterface(InterfacePosition.CENTRAL);
            player.getDialogueManager().start(new PlainChat(player, "You can only have one Portal Nexus room in your house."));
            return;
        }
        player.getInterfaceHandler().closeInterface(InterfacePosition.CENTRAL);
        player.getDialogueManager().start(new RoomCreationD(player, new RoomReference(room, location[0], location[1], location[2], 0)));
    }

    public void roomPreview(final RoomReference reference, final boolean remove) {
        final int realChunkX = reference.getRoom().getChunkX() + (decoration >= 4 ? 8 : 0);
        final int realChunkY = reference.getRoom().getChunkY();
        final Location base = getRoomBase(reference);
        final int boundX = base.getX();
        final int boundY = base.getY();
        final Region region = World.getRegion(_Location.getRegionId(realChunkX << 3, realChunkY << 3), true);
        if (reference.getPlane() == 0) {
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    final WorldObject empty = new WorldObject(-1, 10, reference.getRotation(), boundX + x, boundY + y, reference.getPlane());
                    if (remove) {
                        World.removeObject(empty);
                    } else {
                        World.spawnObject(empty);
                    }
                }
            }
        }
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                final WorldObject[] objects = region.getObjects(decoration & 3, (realChunkX * 8 + x) & 63, (realChunkY * 8 + y) & 63);
                if (objects != null) {
                    for (final WorldObject object : objects) {
                        if (object == null) {
                            continue;
                        }
                        final ObjectDefinitions defs = object.getDefinitions();
                        if (object.getType() == 22) {
                            continue;
                        }
                        if (reference.getPlane() == 0 || defs.containsOption(4, "Build")) {
                            final WorldObject previewObject = new WorldObject(object);
                            final int[] coords = CoordinateUtilities.translate(x, y, reference.getRotation(), defs.getSizeX(), defs.getSizeY(), object.getRotation());
                            previewObject.setLocation(new Location(boundX + coords[0], boundY + coords[1], reference.getPlane()));
                            previewObject.setRotation((object.getRotation() + reference.getRotation()) & 3);
                            if (remove) {
                                World.removeObject(previewObject);
                            } else {
                                World.spawnObject(previewObject);
                            }
                        }
                    }
                }
            }
        }
    }

    public int getAmountOfRooms() {
        return references == null ? 1 : Math.max(1, references.size());
    }

    /**
     * Gets the offset caused by different size yards. Size of the yard is dependant on player's house size.
     *
     * @return chunk offset by yard size.
     */
    private int generateYardOffset() {
        return (10 - yardSpace) / 2;
    }

    public RoomReference getReference(final int positionX, final int positionY, final int plane) {
        for (final RoomReference reference : references) {
            if (reference.getX() == positionX && reference.getY() == positionY && reference.getPlane() == plane) {
                return reference;
            }
        }
        return null;
    }

    public RoomReference getReference(final Location tile) {
        final int roomX = tile.getChunkX() - chunkX + yardOffset;
        final int roomY = tile.getChunkY() - chunkY + yardOffset;
        return getReference(roomX, roomY, tile.getPlane());
    }

    /** Every player inside the house right now (the owner when home, and the guests). */
    public List<Player> getOccupants() {
        final List<Player> list = new ArrayList<>();
        if (ownerInside && player.getCurrentHouse() == this) {
            list.add(player);
        }
        for (final Player guest : guests) {
            if (guest != null && !guest.isFinished() && guest.getCurrentHouse() == this) {
                list.add(guest);
            }
        }
        return list;
    }

    /** World tiles of a room where the template has one of the hotspots of a furniture space. */
    public List<Location> getSpotTiles(final RoomReference room, final FurnitureSpace space) {
        final List<Location> tiles = new ArrayList<>();
        final Location base = getRoomBase(room);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                final Location tile = base.transform(x, y, 0);
                final WorldObject[] objects = getTemplateObjects(tile);
                if (objects == null) {
                    continue;
                }
                for (final WorldObject object : objects) {
                    if (object != null && ArrayUtils.contains(space.getSpotIds(), object.getId())) {
                        tiles.add(new Location(object.getX(), object.getY(), object.getPlane()));
                    }
                }
            }
        }
        return tiles;
    }

    public com.zenyte.game.content.skills.construction.combat.HouseCombat getCombat() {
        return combat;
    }

    /** Rooms of a type. */
    public List<RoomReference> getRooms(final RoomType type) {
        final List<RoomReference> list = new ArrayList<>();
        for (final RoomReference reference : references) {
            if (reference != null && reference.getRoom() == type) {
                list.add(reference);
            }
        }
        return list;
    }

    private int generateYardSpace() {
        final int level = player.getSkills().getLevelForXp(SkillConstants.CONSTRUCTION);
        return level >= 75 ? 9 : 5 + (int) (level / 15.0F);
    }

    public Location getCenter() {
        switch(generateYardSpace()) {
            case 5:
            case 6:
                return SMALL_CENTER;
            case 7:
            case 8:
                return MEDIUM_CENTER;
            default:
                return LARGE_CENTER;
        }
    }

    public List<RoomReference> getReferences() {
        return references;
    }

    public Map<Player, Integer> getCatsOnBlanket() {
        return catsOnBlanket;
    }

    public List<Integer> getDisplayJars() {
        return displayJars;
    }

    public ArmourCase getArmourCase() {
        return armourCase;
    }

    public FancyDressBox getFancyDressBox() {
        return fancyDressBox;
    }

    public ToyBox getToyBox() {
        return toyBox;
    }

    public MagicWardrobe getMagicWardrobe() {
        return magicWardrobe;
    }

    public CapeRack getCapeRack() {
        return capeRack;
    }

    public TreasureChest getTreasureChest() {
        return treasureChest;
    }

    public Player getPlayer() {
        return player;
    }

    public boolean isBuildingMode() {
        return buildingMode;
    }

    public void setBuildingMode(boolean buildingMode) {
        setBuildingModeOption(buildingMode);
    }

    public boolean isRenderDoorsOpen() {
        return doorMode == DOORS_OPEN;
    }

    public void setRenderDoorsOpen(boolean renderDoorsOpen) {
        setDoorModeOption(renderDoorsOpen ? DOORS_OPEN : DOORS_CLOSED);
    }

    public int getDoorMode() {
        return doorMode;
    }

    public boolean isTeleportInside() {
        return !teleportOutside;
    }

    public void setTeleportInside(boolean teleportInside) {
        this.teleportOutside = !teleportInside;
        refreshHouseOptions();
    }

    public boolean isDefaultBuildingMode() {
        return defaultBuildingMode;
    }

    public void setDefaultBuildingMode(final boolean defaultBuildingMode) {
        this.defaultBuildingMode = defaultBuildingMode;
        refreshHouseOptions();
    }

    public boolean isLocked() {
        return locked;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkY() {
        return chunkY;
    }

    public int getYardSpace() {
        return yardSpace;
    }

    public int getYardOffset() {
        return yardOffset;
    }

    public AllocatedArea getAllocatedArea() {
        return allocatedArea;
    }

    public int getDecoration() {
        return decoration;
    }

    public void setDecoration(int decoration) {
        this.decoration = decoration;
    }

    public House getHouse() {
        return house;
    }

    public void setHouse(House house) {
        this.house = house;
    }

    public HouseViewer getHouseViewer() {
        return houseViewer;
    }

    public void setHouseViewer(HouseViewer houseViewer) {
        this.houseViewer = houseViewer;
    }

    public TipJar getTipJar() {
        return tipJar;
    }

    public void setTipJar(TipJar tipJar) {
        this.tipJar = tipJar;
    }

    public int getServantsCash() {
        return servantsCash;
    }

    public void setServantsCash(int servantsCash) {
        this.servantsCash = servantsCash;
    }

    public List<String> getNexusDestinations() {
        return nexusDestinations;
    }

    public String getNexusDefault() {
        return nexusDefault;
    }

    public void setNexusDefault(final String nexusDefault) {
        this.nexusDefault = nexusDefault;
    }

    public int getXericDefault() {
        return xericDefault;
    }

    public void setXericDefault(final int xericDefault) {
        this.xericDefault = xericDefault;
    }

    public int getDigsiteDefault() {
        return digsiteDefault;
    }

    public void setDigsiteDefault(final int digsiteDefault) {
        this.digsiteDefault = digsiteDefault;
    }

    public List<Integer> getMenageriePets() {
        return menageriePets;
    }

    public Map<Integer, com.zenyte.game.world.entity.npc.NPC> getFurnitureNpcs() {
        return furnitureNpcs;
    }

    public List<com.zenyte.game.world.entity.npc.NPC> getRoamingPets() {
        return roamingPets;
    }

    public ServantManager getServantManager() {
        return servantManager;
    }

    public ServantManager.ServantData getServantData() {
        return servantData;
    }

    public void setServantData(final ServantManager.ServantData servantData) {
        this.servantData = servantData;
    }
}

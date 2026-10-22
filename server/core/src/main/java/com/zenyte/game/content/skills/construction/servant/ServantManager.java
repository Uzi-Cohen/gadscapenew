package com.zenyte.game.content.skills.construction.servant;

import com.google.gson.annotations.Expose;
import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.Plank;
import com.zenyte.game.content.skills.construction.RoomReference;
import com.zenyte.game.content.skills.construction.constants.FurnitureSpace;
import com.zenyte.game.content.skills.construction.constants.RoomType;
import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.task.WorldTask;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.Graphics;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.game.world.entity.player.container.ContainerResult;
import com.zenyte.game.world.entity.player.container.RequestResult;
import mgi.types.config.items.ItemDefinitions;

/**
 * Servants and butlers (CON-6), after the OSRS wiki "Servant" page:
 * <ul>
 * <li>hired at the Servants' Guild in East Ardougne (talk to the servant or the Chief servant), with the Construction level,
 * the hiring fee and at least two bedrooms that each have a bed;</li>
 * <li>called with a bell-pull or the house options "Call Servant" button;</li>
 * <li>fetch materials from the bank, take items to the bank, unnote items and (cook and up) take logs to the sawmill, each
 * trip taking the servant's trip time and carrying up to its capacity;</li>
 * <li>a wage (equal to the hiring fee) every 8 trips, taken from the servant's moneybag first, then from the inventory;</li>
 * <li>items a servant still holds go to the bank when the owner is not in the house when it returns.</li>
 * </ul>
 */
public final class ServantManager {

    /** Persistent part, stored with the house. */
    public static final class ServantData {
        @Expose
        private ServantType type;
        @Expose
        private int tripsSincePayment;
        /** Last task, for "Repeat last task": 1 fetch, 2 sawmill. */
        @Expose
        private int lastTask;
        @Expose
        private int lastItem;
        @Expose
        private int lastAmount;

        public ServantData(final ServantType type) {
            this.type = type;
        }

        public ServantType getType() {
            return type;
        }
    }

    /** Items a servant fetches from the bank (OSRS list). */
    public static final int[] BANK_FETCHABLE = new int[] {
            ItemId.PLANK, ItemId.OAK_PLANK, ItemId.TEAK_PLANK, ItemId.MAHOGANY_PLANK, ItemId.SOFT_CLAY, ItemId.LIMESTONE_BRICK,
            ItemId.STEEL_BAR, ItemId.BOLT_OF_CLOTH, ItemId.GOLD_LEAF_8784, ItemId.MARBLE_BLOCK, ItemId.MAGIC_STONE_8788
    };

    private static final int TASK_FETCH = 1;
    private static final int TASK_SAWMILL = 2;
    private static final Graphics DEPART_GFX = new Graphics(110);

    private final Construction house;
    private transient ServantNPC npc;
    private transient boolean onTrip;

    public ServantManager(final Construction house) {
        this.house = house;
    }

    private Player owner() {
        return house.getPlayer();
    }

    public ServantData data() {
        return house.getServantData();
    }

    public ServantType getType() {
        final ServantData data = data();
        return data == null ? null : data.type;
    }

    public boolean isOnTrip() {
        return onTrip;
    }

    /* --------------------------------------------------------------------------------------------------------------------
     * Hiring
     * ------------------------------------------------------------------------------------------------------------------ */

    /** Number of bedrooms with a bed in them; a servant needs two. */
    public int countBedroomsWithBeds() {
        int count = 0;
        for (final RoomReference room : house.getRooms(RoomType.BEDROOM)) {
            if (room.getFurniture(FurnitureSpace.BED_SPACE) != null) {
                count++;
            }
        }
        return count;
    }

    /** Whether a dining room has a bell-pull (needed for the house options "Call Servant" button). */
    public boolean hasBellPull() {
        for (final RoomReference room : house.getRooms(RoomType.DINING_ROOM)) {
            if (room.getFurniture(FurnitureSpace.BELL_PULL_SPACE) != null) {
                return true;
            }
        }
        return false;
    }

    public static void sendGuildVarbit(final Player player) {
        final ServantData data = player.getConstruction().getServantData();
        player.getVarManager().sendBit(ServantType.GUILD_VARBIT, data == null || data.type == null ? 0 : data.type.getGuildVarbitValue());
    }

    /**
     * @return a reason the player can't hire the servant, or null when they can.
     */
    public String canHire(final ServantType type) {
        final Player player = owner();
        if (getType() != null) {
            return "You already have a servant. You'll need to fire " + getType().getName().toLowerCase() + " first.";
        }
        if (player.getSkills().getLevel(SkillConstants.CONSTRUCTION) < type.getLevel()) {
            return "You need a Construction level of " + type.getLevel() + " to hire " + type.getName().toLowerCase() + ".";
        }
        if (countBedroomsWithBeds() < 2) {
            return "You need at least two bedrooms with beds in your house to hire a servant.";
        }
        if (player.getInventory().getAmountOf(ItemId.COINS_995) < type.getWage()) {
            return "You need " + type.getWage() + " coins to hire " + type.getName().toLowerCase() + ".";
        }
        return null;
    }

    public boolean hire(final ServantType type) {
        final String reason = canHire(type);
        if (reason != null) {
            owner().sendMessage(reason);
            return false;
        }
        owner().getInventory().deleteItem(ItemId.COINS_995, type.getWage());
        house.setServantData(new ServantData(type));
        sendGuildVarbit(owner());
        owner().sendMessage("You hire " + type.getName().toLowerCase() + ". " + (type == ServantType.RICK ? "He" : type == ServantType.MAID || type == ServantType.COOK ? "She" : "He") + " will be waiting in your house.");
        if (house.isLoaded() && house.isOwnerInside()) {
            onHouseLoaded();
        }
        return true;
    }

    public void fire() {
        final ServantType type = getType();
        if (type == null) {
            return;
        }
        house.setServantData(null);
        removeNpc();
        sendGuildVarbit(owner());
        owner().sendMessage("You fire " + type.getName().toLowerCase() + ".");
    }

    /* --------------------------------------------------------------------------------------------------------------------
     * House lifecycle
     * ------------------------------------------------------------------------------------------------------------------ */

    public void onHouseLoaded() {
        final ServantType type = getType();
        if (type == null || onTrip || !house.isLoaded()) {
            return;
        }
        if (countBedroomsWithBeds() < 2) {
            removeNpc();
            return;
        }
        if (npc != null && !npc.isFinished() && house.contains(npc.getLocation())) {
            return;
        }
        spawnNpc(house.getPortalTile());
    }

    public void onOwnerLeft() {
        // The servant stays in the house for the guests; it is wiped with the instance.
    }

    public void onHouseClosed() {
        removeNpc();
    }

    private void spawnNpc(final Location tile) {
        removeNpc();
        final ServantType type = getType();
        if (type == null || !house.isLoaded()) {
            return;
        }
        npc = new ServantNPC(type, tile, house);
        npc.spawn();
    }

    private void removeNpc() {
        if (npc != null) {
            if (!npc.isFinished()) {
                npc.finish();
            }
            npc = null;
        }
    }

    public ServantNPC getNpc() {
        return npc;
    }

    /**
     * Bell-pull / "Call Servant": the servant comes to the owner and asks what to do.
     */
    public void call(final Player player) {
        final ServantType type = getType();
        if (type == null) {
            player.sendMessage("You don't have a servant. You can hire one at the Servants' Guild in East Ardougne.");
            return;
        }
        if (player != owner() || !house.isLoaded() || player.getCurrentHouse() != house) {
            player.sendMessage("You can only call your servant inside your own house.");
            return;
        }
        if (countBedroomsWithBeds() < 2) {
            player.sendMessage("Your servant needs two bedrooms with beds to stay in your house.");
            return;
        }
        if (onTrip) {
            player.sendMessage("Your servant is out on an errand; they will be back soon.");
            return;
        }
        final Location tile = freeTileNear(player.getLocation());
        if (npc == null || npc.isFinished() || !house.contains(npc.getLocation())) {
            spawnNpc(tile);
        } else {
            npc.setLocation(tile);
        }
        if (npc != null) {
            npc.setFaceEntity(player);
            player.getDialogueManager().start(new ServantDialogue(player, npc, this));
        }
    }

    private static Location freeTileNear(final Location location) {
        for (final int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { -1, -1 }, { 1, -1 }, { -1, 1 } }) {
            final Location tile = location.transform(d[0], d[1], 0);
            if (World.isFloorFree(tile, 1)) {
                return tile;
            }
        }
        return new Location(location);
    }

    /* --------------------------------------------------------------------------------------------------------------------
     * Tasks
     * ------------------------------------------------------------------------------------------------------------------ */

    /**
     * Takes the wage when 8 trips have been paid for. Money comes from the servant's moneybag first, then the inventory.
     */
    private boolean payIfDue(final Player player) {
        final ServantData data = data();
        if (data.tripsSincePayment < ServantType.TRIPS_PER_WAGE) {
            return true;
        }
        final int wage = data.type.getWage();
        if (house.getServantsCash() >= wage) {
            house.setServantsCash(house.getServantsCash() - wage);
        } else if (player.getInventory().getAmountOf(ItemId.COINS_995) >= wage) {
            player.getInventory().deleteItem(ItemId.COINS_995, wage);
        } else {
            player.getDialogueManager().start(new Dialogue(player, npc) {
                @Override
                public void buildDialogue() {
                    npc("Excuse me, but before I can continue working you must pay my fee of " + wage + " coins.");
                }
            });
            return false;
        }
        data.tripsSincePayment = 0;
        player.sendMessage("Your servant takes their wage of " + wage + " coins.");
        return true;
    }

    private boolean ready(final Player player) {
        if (getType() == null || player != owner()) {
            return false;
        }
        if (onTrip) {
            player.sendMessage("Your servant is already out on an errand.");
            return false;
        }
        return payIfDue(player);
    }

    /** Fetches up to the servant's capacity of an item from the bank. */
    public void fetchFromBank(final Player player, final int itemId, final int requested) {
        if (!ready(player)) {
            return;
        }
        final int inBank = player.getBank().getAmountOf(itemId);
        if (inBank <= 0) {
            player.sendMessage("You don't have any " + ItemDefinitions.getOrThrow(itemId).getName().toLowerCase() + " in your bank.");
            return;
        }
        final int amount = Math.min(Math.min(requested, getType().getCapacity()), inBank);
        final ContainerResult result = player.getBank().remove(new Item(itemId, amount));
        final int taken = result.getSucceededAmount();
        if (taken <= 0) {
            return;
        }
        final ServantData data = data();
        data.lastTask = TASK_FETCH;
        data.lastItem = itemId;
        data.lastAmount = requested;
        startTrip(player, new Item(itemId, taken), "Very good, I shall return with your items shortly.");
    }

    /** Takes inventory items (noted or not) to the bank. */
    public void takeToBank(final Player player, final Item item) {
        if (!ready(player)) {
            return;
        }
        final int amount = Math.min(getType().getCapacity(), player.getInventory().getAmountOf(item.getId()));
        if (amount <= 0) {
            return;
        }
        player.getInventory().deleteItem(item.getId(), amount);
        final Item carried = new Item(item.getId(), amount);
        startTrip(player, null, "Very well, I shall take that to the bank for you.", () -> deposit(player, carried));
    }

    /** Returns unnoted items for a noted stack. */
    public void unnote(final Player player, final Item noted) {
        if (!ready(player)) {
            return;
        }
        final ItemDefinitions defs = noted.getDefinitions();
        if (defs == null || !defs.isNoted()) {
            return;
        }
        final int amount = Math.min(getType().getCapacity(), player.getInventory().getAmountOf(noted.getId()));
        if (amount <= 0) {
            return;
        }
        player.getInventory().deleteItem(noted.getId(), amount);
        startTrip(player, new Item(defs.getUnnotedOrDefault(), amount), "Very well, I shall return with those unnoted shortly.");
    }

    /** Cook, butler and demon butler: logs (noted or not) to planks at the sawmill, paying the usual sawmill fee. */
    public void sawmill(final Player player, final Item logs) {
        if (getType() == null || !getType().canUseSawmill()) {
            player.sendMessage("Your servant can't take logs to the sawmill.");
            return;
        }
        final int unnotedId = logs.getDefinitions() == null ? logs.getId() : logs.getDefinitions().getUnnotedOrDefault();
        Plank plank = null;
        for (final Plank p : Plank.values) {
            if (p.getBase().getId() == unnotedId) {
                plank = p;
            }
        }
        if (plank == null) {
            player.sendMessage("The sawmill can't make planks out of that.");
            return;
        }
        final int owned = player.getInventory().getAmountOf(logs.getId());
        final int coins = player.getInventory().getAmountOf(ItemId.COINS_995);
        final int amount = Math.min(Math.min(getType().getCapacity(), owned), coins / plank.getCost());
        if (amount <= 0) {
            player.sendMessage("You need " + plank.getCost() + " coins per plank to pay the sawmill.");
            return;
        }
        if (!ready(player)) {
            return;
        }
        player.getInventory().deleteItem(logs.getId(), amount);
        player.getInventory().deleteItem(ItemId.COINS_995, amount * plank.getCost());
        final ServantData data = data();
        data.lastTask = TASK_SAWMILL;
        data.lastItem = logs.getId();
        data.lastAmount = amount;
        startTrip(player, new Item(plank.getProduct().getId(), amount), "Very well, I shall take those logs to the sawmill.");
    }

    public String describeLastTask() {
        final ServantData data = data();
        if (data == null || data.lastTask == 0 || data.lastItem <= 0) {
            return null;
        }
        final String name = ItemDefinitions.getOrThrow(data.lastItem).getName();
        return data.lastTask == TASK_FETCH ? "Fetch " + data.lastAmount + " x " + name : "Sawmill " + name;
    }

    public void repeatLastTask(final Player player) {
        final ServantData data = data();
        if (data == null || data.lastTask == 0) {
            player.sendMessage("Your servant hasn't done anything for you yet.");
            return;
        }
        if (data.lastTask == TASK_FETCH) {
            fetchFromBank(player, data.lastItem, data.lastAmount);
        } else if (data.lastTask == TASK_SAWMILL) {
            if (!player.getInventory().containsItem(data.lastItem, 1)) {
                player.sendMessage("You don't have any more of those logs with you.");
                return;
            }
            sawmill(player, new Item(data.lastItem));
        }
    }

    private void startTrip(final Player player, final Item delivery, final String line) {
        startTrip(player, delivery, line, null);
    }

    /**
     * The servant leaves for {@link ServantType#getTripTicks()} ticks, then hands over {@code delivery} (overflow and absent
     * owners: to the bank) and runs {@code onReturn}.
     */
    private void startTrip(final Player player, final Item delivery, final String line, final Runnable onReturn) {
        final ServantType type = getType();
        data().tripsSincePayment++;
        onTrip = true;
        if (npc != null && !npc.isFinished()) {
            npc.setForceTalk(line);
            npc.setGraphics(DEPART_GFX);
        }
        WorldTasksManager.schedule(this::removeNpc, 1);
        WorldTasksManager.schedule(new WorldTask() {
            @Override
            public void run() {
                onTrip = false;
                final boolean home = !player.isFinished() && house.isLoaded() && house.isOwnerInside() && player.getCurrentHouse() == house;
                if (onReturn != null) {
                    onReturn.run();
                }
                if (delivery != null) {
                    if (home) {
                        deliver(player, delivery);
                    } else {
                        deposit(player, delivery);
                    }
                }
                if (home && type == getType()) {
                    spawnNpc(freeTileNear(player.getLocation()));
                    if (npc != null) {
                        npc.setFaceEntity(player);
                        npc.setForceTalk("I have returned, " + (player.getAppearance().isMale() ? "sir" : "madam") + ".");
                    }
                }
            }
        }, type.getTripTicks());
    }

    private void deliver(final Player player, final Item item) {
        final ItemDefinitions defs = item.getDefinitions();
        final boolean stackable = defs != null && defs.isStackable();
        final int fits = stackable ? item.getAmount() : Math.min(item.getAmount(), player.getInventory().getFreeSlots());
        if (fits > 0) {
            player.getInventory().addItem(new Item(item.getId(), fits));
        }
        if (fits < item.getAmount()) {
            deposit(player, new Item(item.getId(), item.getAmount() - fits));
            player.sendMessage("Your servant puts the items you had no room for in your bank.");
        }
    }

    private void deposit(final Player player, final Item item) {
        final ContainerResult result = player.getBank().add(item);
        if (result.getResult() != RequestResult.SUCCESS || result.getSucceededAmount() < item.getAmount()) {
            final int left = item.getAmount() - Math.max(0, result.getSucceededAmount());
            if (left > 0) {
                player.getInventory().addOrDrop(new Item(item.getId(), left));
                player.sendMessage("Your bank is full, so your servant gives the items back to you.");
            }
        } else if (!player.isFinished()) {
            player.sendMessage("Your servant has put " + item.getAmount() + " x " + item.getName().toLowerCase() + " in your bank.");
        }
    }

    /* --------------------------------------------------------------------------------------------------------------------
     * Dialogues
     * ------------------------------------------------------------------------------------------------------------------ */

    /** Talking to the hired servant in the house. */
    public static final class ServantDialogue extends Dialogue {
        private final ServantManager manager;

        public ServantDialogue(final Player player, final ServantNPC npc, final ServantManager manager) {
            super(player, npc);
            this.manager = manager;
        }

        @Override
        public void buildDialogue() {
            final ServantType type = manager.getType();
            if (type == null || player != manager.owner()) {
                npc("Welcome to my employer's house. Please make yourself at home.");
                return;
            }
            final String last = manager.describeLastTask();
            npc("Yes, " + (player.getAppearance().isMale() ? "sir" : "madam") + "? What can I do for you?");
            options(TITLE,
                    new DialogueOption(last == null ? "Repeat last task" : "Repeat last task (" + last + ")", () -> manager.repeatLastTask(player)),
                    new DialogueOption("Fetch from bank", () -> openFetchMenu(player, manager)),
                    new DialogueOption(type.canUseSawmill() ? "Take something to the bank or sawmill" : "Take something to the bank", () -> setKey(20)),
                    new DialogueOption("You're fired!", () -> setKey(30)),
                    new DialogueOption("Nothing, thank you."));
            npc(20, "Use the items you want me to take on me, " + (type.canUseSawmill() ? "logs go to the sawmill and " : "") + "noted items can also be unnoted.");
            options(30, "Fire your servant?", new DialogueOption("Yes, you're fired.", manager::fire), new DialogueOption("No, stay."));
        }
    }

    static void openFetchMenu(final Player player, final ServantManager manager) {
        final String[] names = new String[BANK_FETCHABLE.length];
        for (int i = 0; i < BANK_FETCHABLE.length; i++) {
            names[i] = ItemDefinitions.getOrThrow(BANK_FETCHABLE[i]).getName() + " (" + player.getBank().getAmountOf(BANK_FETCHABLE[i]) + " in bank)";
        }
        player.getDialogueManager().start(new com.zenyte.plugins.dialogue.OptionsMenuD(player, "What should your servant fetch?", names) {
            @Override
            public void handleClick(final int slotId) {
                if (slotId < 0 || slotId >= BANK_FETCHABLE.length) {
                    return;
                }
                final int itemId = BANK_FETCHABLE[slotId];
                final int capacity = manager.getType() == null ? 0 : manager.getType().getCapacity();
                player.sendInputInt("How many? (1-" + capacity + ")", value -> manager.fetchFromBank(player, itemId, Math.max(1, Math.min(capacity, value))));
            }

            @Override
            public boolean cancelOption() {
                return true;
            }
        });
    }

    /** Using an inventory item on the hired servant. */
    public void onItemUsed(final Player player, final Item item) {
        if (player != owner() || getType() == null) {
            player.sendMessage("That servant doesn't work for you.");
            return;
        }
        final ItemDefinitions defs = item.getDefinitions();
        final boolean noted = defs != null && defs.isNoted();
        final int unnoted = defs == null ? item.getId() : defs.getUnnotedOrDefault();
        boolean logs = false;
        for (final Plank plank : Plank.values) {
            if (plank.getBase().getId() == unnoted) {
                logs = true;
            }
        }
        final boolean sawmill = logs && getType().canUseSawmill();
        if (!noted && !sawmill) {
            if (item.getId() == ItemId.COINS_995) {
                player.sendMessage("Put coins for your servant's wages in the servant's moneybag.");
                return;
            }
            takeToBank(player, item);
            return;
        }
        final ServantManager manager = this;
        player.getDialogueManager().start(new Dialogue(player, npc) {
            @Override
            public void buildDialogue() {
                if (sawmill && noted) {
                    options("What should your servant do?", new DialogueOption("Take to the sawmill", () -> manager.sawmill(player, item)),
                            new DialogueOption("Unnote", () -> manager.unnote(player, item)), new DialogueOption("Take to the bank", () -> manager.takeToBank(player, item)));
                } else if (sawmill) {
                    options("What should your servant do?", new DialogueOption("Take to the sawmill", () -> manager.sawmill(player, item)),
                            new DialogueOption("Take to the bank", () -> manager.takeToBank(player, item)));
                } else {
                    options("What should your servant do?", new DialogueOption("Unnote", () -> manager.unnote(player, item)),
                            new DialogueOption("Take to the bank", () -> manager.takeToBank(player, item)));
                }
            }
        });
    }
}

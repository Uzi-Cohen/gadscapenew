package com.zenyte.game.content.skills.construction.servant;

import com.google.common.eventbus.Subscribe;
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
import com.zenyte.game.world.entity.player.privilege.GameMode;
import com.zenyte.plugins.events.LoginEvent;
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
 * The items a servant carries are saved with the house ({@link ServantData} "pending" fields) from the moment they leave the
 * bank or the inventory until they are handed over, so logging out (or a crash) mid-trip loses nothing: the trip of a
 * logged-out owner does nothing, and the items are handed over at the next login. Ultimate ironmen can't send the servant to
 * the bank or the sawmill (OSRS wiki "Ultimate Ironman Mode"), only unnote; bank tasks need the bank PIN entered.
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
        /** Item the servant is carrying (0 = nothing): saved so that a logout or crash mid-trip loses nothing. */
        @Expose
        private int pendingItem;
        @Expose
        private int pendingAmount;
        /** Whether the carried items are going to the bank (take-to-bank) rather than back to the owner. */
        @Expose
        private boolean pendingToBank;

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
        // Whatever the servant is carrying is handed over before they go (inventory, then bank).
        handOver(owner(), true);
        final ServantData data = data();
        if (data != null && data.pendingItem > 0 && data.pendingAmount > 0) {
            owner().sendMessage("Make some room for the items your servant is holding before you fire them.");
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
        final ServantData data = data();
        if (data.pendingItem > 0 && data.pendingAmount > 0) {
            // Left over from an earlier trip (no room then, or saved mid-trip): hand it over first.
            handOver(player, isHome(player) && !data.pendingToBank);
            if (data.pendingItem > 0 && data.pendingAmount > 0) {
                player.sendMessage("Your servant is still holding " + data.pendingAmount + " x " + ItemDefinitions.getOrThrow(data.pendingItem).getName().toLowerCase() + " for you; make some room first.");
                return false;
            }
        }
        return payIfDue(player);
    }

    /** Bank and sawmill tasks: not for ultimate ironmen; the bank ones also need the bank PIN entered this session. */
    private static boolean canUseBank(final Player player, final boolean bank) {
        if (player.getGameMode() == GameMode.ULTIMATE_IRON_MAN) {
            player.sendMessage("As an Ultimate Iron Man, you can't send your servant to the " + (bank ? "bank." : "sawmill."));
            return false;
        }
        if (bank && player.getBankPin().getPin() != -1 && !player.getBankPin().isUnlocked()) {
            player.sendMessage("You need to enter your bank PIN at a bank before your servant can use your bank.");
            return false;
        }
        return true;
    }

    private boolean isHome(final Player player) {
        return !player.isFinished() && house.isLoaded() && house.isOwnerInside() && player.getCurrentHouse() == house;
    }

    /** Fetches up to the servant's capacity of an item from the bank. */
    public void fetchFromBank(final Player player, final int itemId, final int requested) {
        if (!canUseBank(player, true) || !ready(player)) {
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
        startTrip(player, new Item(itemId, taken), false, "Very good, I shall return with your items shortly.");
    }

    /** Takes inventory items (noted or not) to the bank. */
    public void takeToBank(final Player player, final Item item) {
        if (!canUseBank(player, true) || !ready(player)) {
            return;
        }
        final int amount = Math.min(getType().getCapacity(), player.getInventory().getAmountOf(item.getId()));
        if (amount <= 0) {
            return;
        }
        final int removed = player.getInventory().deleteItem(item.getId(), amount).getSucceededAmount();
        if (removed <= 0) {
            return;
        }
        startTrip(player, new Item(item.getId(), removed), true, "Very well, I shall take that to the bank for you.");
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
        final int removed = player.getInventory().deleteItem(noted.getId(), amount).getSucceededAmount();
        if (removed <= 0) {
            return;
        }
        startTrip(player, new Item(defs.getUnnotedOrDefault(), removed), false, "Very well, I shall return with those unnoted shortly.");
    }

    /** Cook, butler and demon butler: logs (noted or not) to planks at the sawmill, paying the usual sawmill fee. */
    public void sawmill(final Player player, final Item logs) {
        if (getType() == null || !getType().canUseSawmill()) {
            player.sendMessage("Your servant can't take logs to the sawmill.");
            return;
        }
        if (!canUseBank(player, false)) {
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
        final int removed = player.getInventory().deleteItem(logs.getId(), amount).getSucceededAmount();
        if (removed <= 0) {
            return;
        }
        player.getInventory().deleteItem(ItemId.COINS_995, removed * plank.getCost());
        final ServantData data = data();
        data.lastTask = TASK_SAWMILL;
        data.lastItem = logs.getId();
        data.lastAmount = removed;
        startTrip(player, new Item(plank.getProduct().getId(), removed), false, "Very well, I shall take those logs to the sawmill.");
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

    /**
     * The servant leaves for {@link ServantType#getTripTicks()} ticks carrying {@code carried} (saved as pending at once), then
     * hands it over: to the owner when they are home (overflow to the bank), otherwise, or for take-to-bank trips, to the
     * bank. A trip whose owner logged out does nothing; the saved items are handed over at the next login.
     */
    private void startTrip(final Player player, final Item carried, final boolean toBank, final String line) {
        final ServantType type = getType();
        final ServantData data = data();
        data.tripsSincePayment++;
        if (carried != null && carried.getAmount() > 0) {
            data.pendingItem = carried.getId();
            data.pendingAmount = carried.getAmount();
            data.pendingToBank = toBank;
        }
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
                // Logged out: this Player is gone; the items are saved with the house and handed over at the next login.
                // Fired meanwhile: fire() already handed them over.
                if (player.isFinished() || data != data()) {
                    return;
                }
                final boolean home = isHome(player);
                handOver(player, home && !data.pendingToBank);
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

    /**
     * Hands over what the servant is carrying. {@code toOwner}: inventory first, the rest to the bank; otherwise the bank first,
     * the rest to the inventory. Ultimate ironmen never use the bank: what doesn't fit comes back noted. Whatever fits nowhere
     * stays with the servant (saved) and is handed over the next time the servant is used, called, or the owner logs in.
     */
    private void handOver(final Player player, final boolean toOwner) {
        final ServantData data = data();
        if (data == null || data.pendingItem <= 0 || data.pendingAmount <= 0) {
            return;
        }
        final int id = data.pendingItem;
        final boolean toBank = data.pendingToBank;
        int left = data.pendingAmount;
        data.pendingItem = 0;
        data.pendingAmount = 0;
        data.pendingToBank = false;
        final boolean bankAllowed = player.getGameMode() != GameMode.ULTIMATE_IRON_MAN;
        int banked = 0;
        int carried = 0;
        if (toOwner || !bankAllowed) {
            int notAdded = addToInventory(player, id, left);
            carried += left - notAdded;
            left = notAdded;
            if (left > 0 && bankAllowed) {
                notAdded = addToBank(player, id, left);
                banked += left - notAdded;
                left = notAdded;
            }
            if (left > 0 && !bankAllowed) {
                final ItemDefinitions defs = ItemDefinitions.get(id);
                final int notedId = defs == null ? id : defs.getNotedOrDefault();
                if (notedId != id) {
                    notAdded = addToInventory(player, notedId, left);
                    carried += left - notAdded;
                    left = notAdded;
                }
            }
        } else {
            int notAdded = addToBank(player, id, left);
            banked += left - notAdded;
            left = notAdded;
            if (left > 0) {
                notAdded = addToInventory(player, id, left);
                carried += left - notAdded;
                left = notAdded;
            }
        }
        if (left > 0) {
            data.pendingItem = id;
            data.pendingAmount = left;
            data.pendingToBank = toBank;
        }
        if (player.isFinished()) {
            return;
        }
        final String name = ItemDefinitions.getOrThrow(id).getName().toLowerCase();
        if (banked > 0) {
            player.sendMessage(toOwner && carried > 0 ? "Your servant puts the items you had no room for in your bank."
                    : "Your servant has put " + banked + " x " + name + " in your bank.");
        }
        if (!toOwner && carried > 0) {
            player.sendMessage(bankAllowed ? "Your bank is full, so your servant gives the items back to you." : "Your servant hands you " + carried + " x " + name + ".");
        }
        if (left > 0) {
            player.sendMessage("Your servant is still holding " + left + " x " + name + " for you; make some room and call them.");
        }
    }

    /** Adds as many as fit (stackable, or one per free slot); returns how many didn't fit. */
    private static int addToInventory(final Player player, final int id, final int amount) {
        final ItemDefinitions defs = ItemDefinitions.get(id);
        final boolean stackable = defs != null && (defs.isStackable() || defs.isNoted());
        final int fits = stackable ? amount : Math.min(amount, player.getInventory().getFreeSlots());
        if (fits <= 0) {
            return amount;
        }
        final ContainerResult result = player.getInventory().addItem(new Item(id, fits));
        return amount - Math.max(0, Math.min(fits, result.getSucceededAmount()));
    }

    /** Banks as many as fit; returns how many didn't. */
    private static int addToBank(final Player player, final int id, final int amount) {
        final ContainerResult result = player.getBank().add(new Item(id, amount));
        return amount - Math.max(0, Math.min(amount, result.getSucceededAmount()));
    }

    /**
     * Login: items the servant was carrying when the owner logged out (or the server stopped) are handed over now, to the bank
     * (ultimate ironmen: the inventory).
     */
    @Subscribe
    public static void onLogin(final LoginEvent event) {
        final Player player = event.getPlayer();
        final Construction construction = player.getConstruction();
        if (construction == null || construction.getServantData() == null) {
            return;
        }
        final ServantData data = construction.getServantData();
        if (data.pendingItem <= 0 || data.pendingAmount <= 0) {
            return;
        }
        construction.getServantManager().handOver(player, false);
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
        if (!canUseBank(player, true)) {
            return;
        }
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

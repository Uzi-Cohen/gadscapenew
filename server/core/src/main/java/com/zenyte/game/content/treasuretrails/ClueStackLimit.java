package com.zenyte.game.content.treasuretrails;

import com.zenyte.game.item.Item;
import com.zenyte.game.util.Colour;
import com.zenyte.game.world.entity.player.Player;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;

/**
 * OSRS "stackable clues" (2 July 2025): scroll boxes stack, and a player can hold a limited number of clue scrolls and
 * scroll boxes of each tier. The limit starts at 2 and every scroll case raises it by 1, up to 5:
 * a minor and a major scroll case per tier (earned by completing a number of that tier's treasure trails) and the Mimic
 * scroll case (earned by defeating the Mimic once), which raises every tier.
 * <p>
 * Clue scrolls and scroll boxes in the inventory and the bank count towards the limit. Drops still appear; they cannot
 * be picked up above the limit (excess boxes of a picked up stack are destroyed), and rewards that would hand out a box
 * above the limit are not given ("sneaking suspicion" message).
 *
 * @see <a href="https://oldschool.runescape.wiki/w/Scroll_case">Scroll case</a>
 */
public final class ClueStackLimit {

    public static final int BASE_LIMIT = 2;
    public static final int MAX_LIMIT = 5;

    private ClueStackLimit() {
    }

    public enum ScrollCase {
        MINOR_BEGINNER(30902, ClueLevel.BEGINNER, 50),
        MAJOR_BEGINNER(30904, ClueLevel.BEGINNER, 100),
        MINOR_EASY(30906, ClueLevel.EASY, 100),
        MAJOR_EASY(30908, ClueLevel.EASY, 200),
        MINOR_MEDIUM(30910, ClueLevel.MEDIUM, 100),
        MAJOR_MEDIUM(30912, ClueLevel.MEDIUM, 250),
        MINOR_HARD(30914, ClueLevel.HARD, 50),
        MAJOR_HARD(30916, ClueLevel.HARD, 150),
        MINOR_ELITE(30918, ClueLevel.ELITE, 50),
        MAJOR_ELITE(30920, ClueLevel.ELITE, 150),
        MINOR_MASTER(30922, ClueLevel.MASTER, 25),
        MAJOR_MASTER(30924, ClueLevel.MASTER, 75),
        /**
         * Raises the limit of every tier; earned by defeating the Mimic.
         */
        MIMIC(30926, null, -1);

        private static final ScrollCase[] values = values();
        private static final Int2ObjectMap<ScrollCase> byItem = new Int2ObjectOpenHashMap<>();

        static {
            for (final ScrollCase value : values) {
                byItem.put(value.itemId, value);
            }
        }

        private final int itemId;
        @Nullable
        private final ClueLevel tier;
        private final int completions;

        ScrollCase(final int itemId, @Nullable final ClueLevel tier, final int completions) {
            this.itemId = itemId;
            this.tier = tier;
            this.completions = completions;
        }

        @Nullable
        public static ScrollCase get(final int itemId) {
            return byItem.get(itemId);
        }

        public static int[] itemIds() {
            final int[] ids = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                ids[i] = values[i].itemId;
            }
            return ids;
        }

        public int getItemId() {
            return itemId;
        }

        @Nullable
        public ClueLevel getTier() {
            return tier;
        }

        public int getCompletions() {
            return completions;
        }

        private String attribute() {
            return "scroll case opened " + name().toLowerCase();
        }

        public boolean isOpened(@NotNull final Player player) {
            return player.getNumericAttribute(attribute()).intValue() == 1;
        }

        /**
         * Opens the case for the player: its limit increase applies from now on.
         *
         * @return false if the player had already opened this case.
         */
        public boolean open(@NotNull final Player player) {
            if (isOpened(player)) {
                return false;
            }
            player.addAttribute(attribute(), 1);
            return true;
        }
    }

    /**
     * @return how many clue scrolls and scroll boxes of the tier the player may hold.
     */
    public static int getLimit(@NotNull final Player player, @NotNull final ClueLevel tier) {
        int limit = BASE_LIMIT;
        for (final ScrollCase scrollCase : ScrollCase.values) {
            if ((scrollCase.tier == null || scrollCase.tier == tier) && scrollCase.isOpened(player)) {
                limit++;
            }
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static int amountOf(@NotNull final Player player, final int itemId) {
        if (itemId == -1) {
            return 0;
        }
        return player.getInventory().getAmountOf(itemId) + player.getBank().getAmountOf(itemId);
    }

    /**
     * @return how many clue scrolls and scroll boxes of the tier the player holds (inventory and bank).
     */
    public static int getHeld(@NotNull final Player player, @NotNull final ClueItem type) {
        return amountOf(player, type.getScrollBox()) + Math.min(1, amountOf(player, type.getClue()));
    }

    /**
     * @return how many more clue scrolls or scroll boxes of the tier the player can receive.
     */
    public static int getRemaining(@NotNull final Player player, @NotNull final ClueItem type) {
        return Math.max(0, getLimit(player, type.getLevel()) - getHeld(player, type));
    }

    public static boolean canReceive(@NotNull final Player player, @NotNull final ClueItem type) {
        return getRemaining(player, type) > 0;
    }

    @Nullable
    public static ClueItem getBoxType(final int itemId) {
        final ClueItem type = ClueItem.getMap().get(itemId);
        return type != null && type.getScrollBox() == itemId ? type : null;
    }

    private static String tierName(@NotNull final ClueItem type) {
        return type.getLevel().toString().toLowerCase();
    }

    public static void sendSuspicion(@NotNull final Player player, @NotNull final ClueItem type) {
        player.sendMessage(Colour.RED.wrap("You have a sneaking suspicion that you would have received a " + tierName(type) + " scroll box, but you have too many clues of that tier."));
    }

    /**
     * Gives the player a scroll box of the type if the limit allows it, or sends the "sneaking suspicion" message.
     * Other content that hands out scroll boxes (implings, thieving, raids) can use this too.
     *
     * @return whether the box was given.
     */
    public static boolean award(@NotNull final Player player, @NotNull final ClueItem type) {
        if (!canReceive(player, type)) {
            sendSuspicion(player, type);
            return false;
        }
        player.getInventory().addOrDrop(new Item(type.getScrollBox()));
        return true;
    }

    /**
     * Removes scroll boxes the player cannot hold from a reward list (e.g. the master scroll box rolled from a casket).
     */
    public static void filterRewards(@NotNull final Player player, @NotNull final List<Item> loot) {
        final Iterator<Item> iterator = loot.iterator();
        while (iterator.hasNext()) {
            final Item item = iterator.next();
            final ClueItem type = getBoxType(item.getId());
            if (type != null && !canReceive(player, type)) {
                iterator.remove();
                sendSuspicion(player, type);
            }
        }
    }

    /**
     * Destroys scroll boxes of the tier in the inventory beyond the player's limit (after a stack was picked up).
     */
    public static void destroyExcess(@NotNull final Player player, @NotNull final ClueItem type) {
        final int excess = getHeld(player, type) - getLimit(player, type.getLevel());
        if (excess <= 0) {
            return;
        }
        final int removed = player.getInventory().deleteItem(type.getScrollBox(), Math.min(excess, player.getInventory().getAmountOf(type.getScrollBox()))).getSucceededAmount();
        if (removed == 1) {
            player.sendMessage("One of your scroll boxes was destroyed as you have too many of that tier.");
        } else if (removed > 1) {
            player.sendMessage("Some of your scroll boxes were destroyed as you have too many of that tier.");
        }
    }

    /**
     * Hands out the scroll cases a completed treasure trail has earned: a case comes with the reward once the tier's
     * completion count reaches its threshold, and again with every later reward until it is opened.
     */
    public static void onTrailCompleted(@NotNull final Player player, @NotNull final ClueLevel tier, final int completions) {
        for (final ScrollCase scrollCase : ScrollCase.values) {
            if (scrollCase.tier != tier || completions < scrollCase.completions || scrollCase.isOpened(player)) {
                continue;
            }
            giveCase(player, scrollCase);
        }
    }

    /**
     * Hands out the Mimic scroll case after the Mimic was defeated.
     */
    public static void onMimicDefeated(@NotNull final Player player) {
        if (!ScrollCase.MIMIC.isOpened(player)) {
            giveCase(player, ScrollCase.MIMIC);
        }
    }

    private static void giveCase(@NotNull final Player player, @NotNull final ScrollCase scrollCase) {
        if (player.containsItem(scrollCase.itemId)) {
            return;
        }
        final Item item = new Item(scrollCase.itemId);
        player.getInventory().addOrDrop(item);
        player.sendMessage(Colour.RED.wrap("You have been given a " + item.getName().toLowerCase() + ". Open it to hold more clues."));
    }
}

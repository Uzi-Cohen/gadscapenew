package com.zenyte.game.content.lootkeys;

import com.zenyte.game.content.consumables.Consumable;
import com.zenyte.game.item.Item;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.UpdateFlag;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.Container;
import com.zenyte.game.world.entity.player.container.ContainerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;

import static com.zenyte.game.content.lootkeys.LootkeyConstants.*;

/**
 * Loot key rules on death (OSRS, oldschool.runescape.wiki/w/Loot_key):
 * <ul>
 *     <li>A killer with keys enabled gets one key holding the loot (max 28 stacks; what does not fit, filtered food
 *     and items at/above the valuables threshold drop to the floor as usual). No loot, no key.</li>
 *     <li>A killer already holding 5 keys gets no key: "You have reached the limit of 5 loot keys." and the loot drops.</li>
 *     <li>Keys carried by the victim go to the killer after a PvP death even if the killer has not enabled keys.
 *     Anything that would take the killer past 5 keys is destroyed (lowest value first); if the killer's new key is
 *     the one that does not fit, its loot drops to the floor instead. A killer with keys enabled always removes one
 *     key from a 5-key victim. After a PvM death, or when the killer is dead/gone, the victim's keys are destroyed.</li>
 *     <li>A key that does not fit in the inventory lands on the floor, visible only to the killer for 2 minutes.</li>
 * </ul>
 */
public final class LootkeyDeath {
    private static final Logger log = LoggerFactory.getLogger(LootkeyDeath.class);

    private LootkeyDeath() {
    }

    /**
     * @param victim       the player who died.
     * @param killer       the entity credited with the kill (may be null, an NPC or the victim).
     * @param lostToKiller the items the victim drops for the killer; modified in place (keys and keyed loot removed).
     * @param tile         where the victim died.
     */
    public static void process(final Player victim, final Entity killer, final List<Item> lostToKiller, final Location tile) {
        try {
            final List<Item> victimKeys = new ArrayList<>();
            lostToKiller.removeIf(item -> {
                if (item != null && isLootKey(item.getId())) {
                    victimKeys.add(item);
                    return true;
                }
                return false;
            });
            final Player receiver = killer instanceof Player && killer != victim ? (Player) killer : null;
            if (receiver == null || receiver.isDead() || receiver.isFinished() || receiver.isNulled()) {
                if (!victimKeys.isEmpty()) {
                    victim.sendMessage("<col=ef1020>" + (victimKeys.size() == 1 ? "Your loot key was" : "Your loot keys were")
                            + " destroyed when you died.</col>");
                }
                return;
            }
            final int held = LootKeys.countKeys(receiver);
            Item newKey = null;
            final LootkeySettings settings = receiver.getLootkeySettings();
            final boolean keysEnabled = settings != null && settings.isUnlocked() && settings.isEnabled();
            if (keysEnabled && !lostToKiller.isEmpty()) {
                if (held >= MAX_KEYS) {
                    receiver.sendMessage("You have reached the limit of " + MAX_KEYS + " loot keys.");
                } else {
                    newKey = buildKey(settings, lostToKiller);
                }
            }
            victimKeys.sort(Comparator.comparingLong(LootKeys::storedValue).reversed());
            int destroyed = 0;
            /*
             * OSRS: "defeating an opponent that has 5 keys in the inventory with the loot keys enabled will always cause
             * one key to be removed, even if the killer has none" (the new loot key pushes the total past 5). When no
             * new key could be made (nothing else to loot), the rule still applies: the lowest-value key is removed.
             */
            if (keysEnabled && newKey == null && victimKeys.size() >= MAX_KEYS) {
                victimKeys.remove(victimKeys.size() - 1);
                destroyed++;
            }
            final List<Item> candidates = new ArrayList<>(victimKeys);
            if (newKey != null) {
                candidates.add(newKey);
            }
            if (candidates.isEmpty() && destroyed == 0) {
                return;
            }
            // The most valuable keys are kept. Keys taken from the victim that do not fit are destroyed; when the new
            // key does not fit, its loot goes back to the drop list and lands on the floor for the killer (as when the
            // killer already holds 5 keys), so the victim's inventory is never deleted.
            candidates.sort(Comparator.comparingLong(LootKeys::storedValue).reversed());
            int capacity = Math.max(0, MAX_KEYS - held);
            int transferred = 0;
            boolean receivedNew = false;
            boolean newKeyDropped = false;
            for (final Item key : candidates) {
                if (capacity > 0 && LootKeys.give(receiver, key, tile)) {
                    capacity--;
                    if (key == newKey) {
                        receivedNew = true;
                    } else {
                        transferred++;
                    }
                } else if (key == newKey) {
                    returnLoot(newKey, lostToKiller);
                    newKeyDropped = true;
                } else {
                    destroyed++;
                }
            }
            if (receivedNew) {
                receiver.sendMessage("<col=ef1020>" + victim.getName() + "'s loot has been stored in a loot key worth approximately "
                        + Utils.formatNumberWithCommas(LootKeys.storedValue(newKey)) + " coins.</col>");
            }
            if (transferred > 0) {
                receiver.sendMessage("<col=ef1020>You take " + (transferred == 1 ? "a loot key" : transferred + " loot keys") + " from "
                        + victim.getName() + ".</col>");
            }
            if (newKeyDropped) {
                receiver.sendMessage("<col=ef1020>You have reached the limit of " + MAX_KEYS + " loot keys, so " + victim.getName()
                        + "'s loot has dropped to the floor.</col>");
            }
            if (destroyed > 0) {
                receiver.sendMessage("<col=ef1020>You can only carry " + MAX_KEYS + " loot keys, so " + destroyed
                        + (destroyed == 1 ? " key was" : " keys were") + " destroyed.</col>");
            }
            receiver.getUpdateFlags().flag(UpdateFlag.APPEARANCE);
        } catch (final Exception e) {
            // Never let loot key handling break a death; whatever is still in lostToKiller drops as normal.
            log.error("Loot key death handling failed for {}", victim.getUsername(), e);
        }
    }

    /** Puts a new key's loot back into the drop list (the key did not fit), so it drops to the floor for the killer. */
    private static void returnLoot(final Item key, final List<Item> lostToKiller) {
        for (final Item item : LootKeys.readContents(key).getItems().values()) {
            if (item != null && item.getAmount() > 0) {
                lostToKiller.add(item);
            }
        }
    }

    /**
     * Moves the eligible loot into a new key container. Items are only taken out of {@code lostToKiller} once they are
     * actually in the key; anything past 28 stacks stays in the list and drops to the floor.
     */
    private static Item buildKey(final LootkeySettings settings, final List<Item> lostToKiller) {
        final Container contents = LootKeys.newContainer();
        final ListIterator<Item> iterator = lostToKiller.listIterator();
        while (iterator.hasNext()) {
            final Item item = iterator.next();
            if (item == null || item.getAmount() <= 0 || item.getDefinitions() == null) continue;
            final int id = item.getId();
            final boolean consumable = Consumable.gourdDrinks.containsKey(id)
                    || Consumable.consumables.containsKey(id)
                    || Consumable.food.containsKey(id);
            if (consumable) {
                if (settings.isDropFood()) continue;
            } else if (settings.isDropValuables() && LootKeys.itemValue(item) >= settings.getThreshold()) {
                continue;
            }
            final ContainerResult result = contents.add(item);
            final int moved = result.getSucceededAmount();
            if (moved >= item.getAmount()) {
                iterator.remove();
            } else if (moved > 0) {
                iterator.set(new Item(id, item.getAmount() - moved, item.getAttributesCopy()));
            }
        }
        if (contents.getSize() == 0) {
            return null;
        }
        final Item key = new Item(LOOT_KEY_ITEM_ID, 1);
        LootKeys.writeContents(key, contents);
        return key;
    }
}

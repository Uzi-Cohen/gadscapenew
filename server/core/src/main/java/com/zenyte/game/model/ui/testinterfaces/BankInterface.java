package com.zenyte.game.model.ui.testinterfaces;

import com.gadscape.game.world.entity.player.container.impl.BankUtil;
import com.zenyte.game.GameInterface;
import com.zenyte.game.item.Item;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.model.ui.InterfaceHandler;
import com.zenyte.game.model.ui.InterfacePosition;
import com.zenyte.game.model.ui.SwitchPlugin;
import com.zenyte.game.packet.PacketDispatcher;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.VarManager;
import com.zenyte.game.world.entity.player.container.Container;
import com.zenyte.game.world.entity.player.container.impl.ContainerType;
import com.zenyte.game.world.entity.player.container.impl.bank.Bank;
import com.zenyte.game.world.entity.player.container.impl.bank.BankSetting;
import com.zenyte.game.world.entity.player.privilege.GameMode;
import com.zenyte.game.world.region.area.plugins.TempPlayerStatePlugin;
import com.zenyte.plugins.dialogue.PlainChat;
import kotlin.Unit;
import mgi.types.config.InventoryDefinitions;
import mgi.types.config.enums.Enums;
import mgi.utilities.CollectionUtils;

import java.util.Optional;

import static com.zenyte.game.GameInterface.BANK;
import static com.zenyte.game.util.AccessMask.*;

/**
 * @author Kris | 19/10/2018 22:58
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class BankInterface extends Interface implements SwitchPlugin {
    public static final int VAR_LAST_DEPOSIT_AMOUNT = 3960;
    public static final int VAR_CURRENT_TAB = 4150;
    private static final int VAR_DISPLAY_TYPE = 4170;
    private static final int CAPACITY_TOOLTIP_SCRIPT = 1495;
    private static final int INPUT_SCREEN_CLOSE_SCRIPT = 101;
    private static final int BANK_SEARCH_TYPE = 11;

    @Override
    protected void attach() {
        // Build 241 bankmain (12) ids, matched by component names, the bankmain_init argument order and the
        // menu hook scripts (517/1428/1270/2207/2211); 211 ids in the comments. In 241 the separate
        // Swap/Insert and Item/Note buttons became single toggles (swap_insert, note).
        put(125, "Group storage"); // gim_storage (211: 8)
        put(23, "Swap mode"); // swap_insert toggle (211: 17 "Swap", 19 "Insert")
        put(25, "Withdraw as Note"); // note toggle (211: 24 "Note", 22 "Item")
        put(29, "First option amount 1"); // quantity1 (211: 28)
        put(31, "First option amount 5"); // quantity5 (211: 30)
        put(33, "First option amount 10"); // quantity10 (211: 32)
        put(35, "First option amount X"); // quantityx (211: 34)
        put(37, "First option amount All"); // quantityall (211: 36)
        put(40, "Always set placeholders"); // placeholder (211: 38)
        put(42, "Search"); // search (211: 40)
        put(47, "Deposit inventory"); // depositinv (211: 42)
        put(49, "Deposit equipment"); // depositworn (211: 44)
        put(10, "Interact with tab"); // tabs (211: 11)
        put(129, "Tab display"); // tabdisplay_select_1, script517 (211: 50)
        put(131, "Show bank tutorial"); // banktut_toggle (211: 52)
        put(132, "Inventory item options"); // sideops_toggle (211: 51)
        put(135, "Deposit worn items button"); // depositworn_toggle (211: 54)
        put(134, "Deposit inventory button"); // depositinv_toggle (211: 55)
        put(140, "Release all placeholders"); // release_placeholders, script1270 (211: 56)
        put(143, "Set bank filler amount to 1"); // bank_filler_1 (211: 58)
        put(145, "Set bank filler amount to 10"); // bank_filler_10 (211: 60)
        put(147, "Set bank filler amount to 50"); // bank_filler_50 (211: 62)
        put(149, "Set bank filler amount to X"); // bank_filler_x (211: 64)
        put(151, "Set bank filler amount to All"); // bank_filler_all (211: 66)
        put(153, "Add bank fillers"); // bank_filler_confirm, script2211 (211: 68)
        put(130, "Toggle incinerator"); // incinerator_toggle, script1428 (211: 53)
        put(8, "Bank size"); // capacity (211: 9)
        put(7, "Bank size tooltip 1"); // capacity_layer
        put(108, "Bank size tooltip 2"); // bank_highlight (211: 115)
        put(12, "Interact with item"); // items (211: 13)
        put(55, "Incinerate item"); // incinerator_confirm (211: 47)
        put(4, "Show tutorial"); // bank_tut
        put(9999, "Preset Manager"); // custom GadScape component (211 cache: 124); absent on the vanilla 241 cache, 241 124 is popup_button_in
        Enums.BANK_EQUIPMENT_TAB_SLOT_MAP.getValues().forEach((slot, component) -> put(component & 0xFFFF, "Unequip slot " + slot));
    }

    @Override
    public void open(Player player) {
        if (player.getGameMode().equals(GameMode.ULTIMATE_IRON_MAN)) {
            player.getDialogueManager().start(new PlainChat(player, "As an Ultimate Iron Man, you cannot use the bank."));
            return;
        }

        if (TempPlayerStatePlugin.enableTempState(player, TempPlayerStatePlugin.StateType.INVENTORY)) {
            player.sendMessage("Cannot open the bank right now.");
            return;
        }

        if (player.getDuel() != null)
            return;

        if (player.getBankPin().requiresVerification(player)) return;

        final Bank bank = player.getBank();
        final PacketDispatcher dispatcher = player.getPacketDispatcher();
        final int interfaceId = getInterface().getId();
        final VarManager varManager = player.getVarManager();
        final InterfaceHandler interfaceHandler = player.getInterfaceHandler();
        final InventoryDefinitions bankInventory = InventoryDefinitions.get(ContainerType.BANK.getId());
        assert bankInventory != null;
        final int bankSize = bankInventory.getSize();
        interfaceHandler.closeInterface(InterfacePosition.SINGLE_TAB);
        interfaceHandler.closeInterface(InterfacePosition.CENTRAL);
        varManager.sendBit(VAR_CURRENT_TAB, (bank.getCurrentTab() + 1) % 10);
        varManager.sendBit(VAR_DISPLAY_TYPE, bank.getDisplayType());
        varManager.sendBit(VAR_LAST_DEPOSIT_AMOUNT, bank.getLastDepositAmount());
        dispatcher.sendClientScript(CAPACITY_TOOLTIP_SCRIPT, "Members' capacity: " + (bank.getContainer().getContainerSize()), getComponent("Bank size tooltip 1") | (interfaceId << 16), getComponent("Bank size tooltip 2") | (interfaceId << 16));
        dispatcher.sendComponentSettings(BANK, getComponent("Interact with item"), 0, bankSize, CLICK_OP1, CLICK_OP2, CLICK_OP3, CLICK_OP4, CLICK_OP5, CLICK_OP6, CLICK_OP7, CLICK_OP8, CLICK_OP9, CLICK_OP10, DRAG_DEPTH2, DRAG_TARGETABLE);
        // Slots after the bank's capacity are the tab drop targets (211: 1209-1227 with 1200 slots; 241 has 1410).
        dispatcher.sendComponentSettings(BANK, getComponent("Interact with item"), bankSize + 18, bankSize + 27, DRAG_TARGETABLE);
        dispatcher.sendComponentSettings(BANK, getComponent("Interact with item"), bankSize + 9, bankSize + 17, CLICK_OP1);
        dispatcher.sendComponentSettings(BANK, getComponent("Tab display"), 0, 4, CLICK_OP1);
        dispatcher.sendComponentSettings(BANK, getComponent("Interact with tab"), 10, 10, CLICK_OP1, CLICK_OP7, DRAG_TARGETABLE);
        dispatcher.sendComponentSettings(BANK, getComponent("Interact with tab"), 11, 19, CLICK_OP1, CLICK_OP6, CLICK_OP7, DRAG_DEPTH1, DRAG_TARGETABLE);
        dispatcher.sendComponentSettings(BANK, getComponent("Incinerate item"), 1, bankSize + 1, CLICK_OP1);
        dispatcher.sendComponentSettings(BANK, getComponent("Always set placeholders"), 0, 3, CLICK_OP1);
        dispatcher.sendComponentText(BANK, getComponent("Bank size"), bank.getContainer().getContainerSize());
        dispatcher.sendComponentVisibility(BANK, getComponent("Show tutorial"), true);
        interfaceHandler.sendInterface(BANK);
        bank.revalidate();
        bank.toggleSetting(BankSetting.WITHDRAW_MODE, false);
        BankSetting.update(player);
        bank.getContainer().setFullUpdate(true);
        bank.getContainer().refresh(player);
        player.getLootingBag().refresh();
        GameInterface.BANK_INVENTORY.open(player);
        player.getBonuses().update();
        bank.onOpen(player);
    }

    @Override
    public void close(final Player player, final Optional<GameInterface> replacement) {
        player.getPacketDispatcher().sendClientScript(INPUT_SCREEN_CLOSE_SCRIPT, BANK_SEARCH_TYPE);
        player.getBank().onClose(player);
    }

    @Override
    protected void build() {
        bind("Group storage", GameInterface.STORAGE_GIM::open);
        bind("Deposit worn items button", player -> player.getBank().toggleSetting(BankSetting.DEPOSIT_WORN_ITEMS, player.getBank().getSetting(BankSetting.DEPOSIT_WORN_ITEMS) == 0));
        bind("Deposit inventory button", player -> player.getBank().toggleSetting(BankSetting.DEPOSIT_INVENTOY_ITEMS, player.getBank().getSetting(BankSetting.DEPOSIT_INVENTOY_ITEMS) == 0));
        bind("Toggle incinerator", player -> player.getBank().toggleSetting(BankSetting.INCINERATOR, player.getBank().getSetting(BankSetting.INCINERATOR) == 0));
        bind("Always set placeholders", player -> player.getBank().toggleSetting(BankSetting.ALWAYS_PLACEHOLDER, player.getBank().getSetting(BankSetting.ALWAYS_PLACEHOLDER) == 0));
        bind("Incinerate item", (player, slotId, itemId, option) -> player.getBank().incinerate(slotId, itemId));
        bind("Release all placeholders", player -> player.getBank().releasePlaceholders(-1));
        bind("Tab display", (player, slotId, itemId, option) -> player.getBank().setDisplayType(slotId));
        // 241: one toggle button each (211 had separate Item/Note and Swap/Insert buttons).
        bind("Withdraw as Note", player -> player.getBank().toggleSetting(BankSetting.WITHDRAW_MODE, player.getBank().getSetting(BankSetting.WITHDRAW_MODE) == 0));
        bind("Swap mode", player -> player.getBank().toggleSetting(BankSetting.REARRANGE_MODE, player.getBank().getSetting(BankSetting.REARRANGE_MODE) == 0));
        bind("Search", player -> player.getBank().setCurrentTab(Bank.MAIN_TAB));
        bind("Set bank filler amount to 1", player -> player.getTemporaryAttributes().put("bankFillerAmount", 1));
        bind("Set bank filler amount to 10", player -> player.getTemporaryAttributes().put("bankFillerAmount", 10));
        bind("Set bank filler amount to 50", player -> player.getTemporaryAttributes().put("bankFillerAmount", 50));
        bind("Set bank filler amount to All", player -> player.getTemporaryAttributes().put("bankFillerAmount", Integer.MAX_VALUE));
        bind("Set bank filler amount to X", player -> player.sendInputInt("Set bank fillers amount:", a -> player.getTemporaryAttributes().put("bankFillerAmount", a)));
        bind("Add bank fillers", player -> player.getBank().addFillers(player.getNumericTemporaryAttributeOrDefault("bankFillerAmount", Integer.MAX_VALUE).intValue()));
        bind("First option amount 1", player -> player.getBank().setQuantity(Bank.QuantitySelector.ONE));
        bind("First option amount 5", player -> player.getBank().setQuantity(Bank.QuantitySelector.FIVE));
        bind("First option amount 10", player -> player.getBank().setQuantity(Bank.QuantitySelector.TEN));
        bind("First option amount X", (player, slotId, itemId, option) -> {
            if (option == 2) {
                player.sendInputInt("Set quantity selector amount:", a -> {
                    final int value = Math.max(a, 1);
                    player.getBank().setLastDepositAmount(value);
                    player.getVarManager().sendBit(VAR_LAST_DEPOSIT_AMOUNT, player.getBank().getLastDepositAmount());
                });
                if (player.getBank().getLastDepositAmount() == 0) {
                    player.getVarManager().sendBit(VAR_LAST_DEPOSIT_AMOUNT, 1);
                }
            }
            player.getBank().setQuantity(Bank.QuantitySelector.X);
        });
        bind("First option amount All", player -> player.getBank().setQuantity(Bank.QuantitySelector.ALL));
        bind("Deposit equipment", (player, slotId, itemId, option) -> {
            if (player.getTemporaryAttributes().containsKey("viewing another bank")) {
                return;
            }
            final Container container = player.getEquipment().getContainer();
            if (container.getSize() == 0) {
                player.sendMessage("You're not wearing anything.");
                return;
            }
            BankUtil.safeDeposit(player, container);
            player.getEquipment().refreshAll();
            player.getCombatDefinitions().setAutocastSpell(null);
            player.getCombatDefinitions().refresh();
            if (container.getSize() != 0) {
                player.sendMessage("Not enough space in your bank.");
            }
        });
        bind("Deposit inventory", (player, slotId, itemId, option) -> {
            tryDepositInventory(player, "deposit");
        });
        bind("Interact with tab", (player, slotId, itemId, option) -> {
            BankUtil.safePermutation(player, bank -> {
                if (option == 1) {
                    final int size = bank.getTabSize(slotId == 10 ? 9 : slotId - 11);
                    if (size == 0) {
                        player.sendMessage("Drag an item here to create a new tab.");
                    }
                    bank.setCurrentTab(slotId == 10 ? 9 : slotId - 11);
                } else if (option == 6) {
                    bank.collapseTab(bank.getTabFromSlot(slotId));
                } else if (option == 7) {
                    final int tab = bank.getTabFromSlot(slotId);
                    if (tab < 0 || tab > Bank.MAIN_TAB) {
                        return Unit.INSTANCE;
                    }
                    bank.releasePlaceholders(tab);
                }
                return Unit.INSTANCE;
            });

        });
        bind("Interact with item", (player, slotId, itemId, option) -> {
            final Item item = player.getBank().get(slotId);
            if (item == null) {
                return;
            }
            final int id = item.getId();
            if (id != itemId) {
                return;
            }
            final BankInterface.ItemOption op = ItemOption.of(option);
            if (op.is(ItemOption.EXAMINE)) {
                player.sendMessage(item.getDefinitions().getExamine());
                return;
            }
            if (id == 20594) {
                BankUtil.safePermutation(player, bank -> {
                    if (op.is(ItemOption.CLEAR_ALL)) {
                        bank.removeFillers(-1, Integer.MAX_VALUE);
                    } else if (op.is(ItemOption.CLEAR)) {
                        bank.removeFillers(slotId, 1);
                    } else {
                        throw new RuntimeException("No other options available on bank fillers, player: " + player.getName());
                    }
                    return Unit.INSTANCE;
                });
            } else if (op.is(ItemOption.WITHDRAW_X)) {
                player.sendInputInt("How many would you like to withdraw?", value -> {
                    BankUtil.safePermutation(player, bank -> {
                        final int itemAmount = bank.getAmountOf(id);
                        bank.setLastDepositAmount(value);
                        player.getVarManager().sendBit(VAR_LAST_DEPOSIT_AMOUNT, value);
                        bank.withdraw(player, player.getInventory().getContainer(), slotId, Math.min(itemAmount, value), false);
                        return Unit.INSTANCE;
                    });
                });
            } else {
                BankUtil.safePermutation(player, bank -> {
                    final int itemAmount = bank.getAmountOf(id);
                    ItemOption itemOption = op;
                    if (itemAmount == 0 && op.is(ItemOption.CLEAR_ALL))
                        itemOption = ItemOption.PLACEHOLDER;
                    final int amount = itemOption.getAmount(player, itemAmount);
                    bank.withdraw(player, player.getInventory().getContainer(), slotId, Math.min(itemAmount, amount), itemOption.is(ItemOption.PLACEHOLDER));
                    return Unit.INSTANCE;
                });
            }
        });
        bind("Interact with item", "Interact with item", ((player, fromSlot, toSlot) -> {
            BankUtil.safePermutation(player, bank -> {
                int lastSlot = Container.getSize(ContainerType.BANK) + 18;
                if (toSlot >= lastSlot) {
                    final int tabId = toSlot == lastSlot ? 9 : (toSlot - (lastSlot + 1));
                    bank.switchItem(Bank.BankSwitchType.ITEM_TO_END_OF_TAB, fromSlot, tabId);
                } else {
                    bank.switchItem(Bank.BankSwitchType.ITEM_TO_ITEM, fromSlot, toSlot);
                }
                return Unit.INSTANCE;
            });
        }));
        bind("Interact with item", "Interact with tab", ((player, fromSlot, toSlot) ->
                BankUtil.safePermutation(player, bank -> {
                    bank.switchItem(Bank.BankSwitchType.ITEM_TO_TAB, fromSlot, toSlot);
                    return Unit.INSTANCE;
                })));
        bind("Interact with tab", "Interact with tab", ((player, fromSlot, toSlot) ->
                BankUtil.safePermutation(player, bank -> {
                    player.getBank().switchItem(Bank.BankSwitchType.TAB_TO_TAB, fromSlot, toSlot);
                    return Unit.INSTANCE;
                })));

        bind("Preset Manager", player -> {
            if (player.getTemporaryAttributes().containsKey("viewing another bank")) {
                return;
            }
            player.getInterfaceHandler().closeInterfaces();
            GameInterface.PRESET_MANAGER.open(player);
        });
        Enums.BANK_EQUIPMENT_TAB_SLOT_MAP.getValues().forEach((slot, component)
                -> bind("Unequip slot " + slot, (player, slotId, itemId, optionId)
                -> {
            player.getEquipment().unequipItem(slot);
            BankInventoryInterface.transmitSlotVarp(player);
        }));
    }

    public static void tryDepositInventory(Player player, String action) {
        tryDepositInventory(player.getBank(), player, action);
    }

    public static void tryDepositInventory(Bank bank, Player player, String action) {
        if (player.getTemporaryAttributes().containsKey("viewing another bank")) {
            return;
        }
        final Container container = player.getInventory().getContainer();
        if (container.getSize() == 0) {
            player.sendMessage("Your have nothing to "+action+".");
            return;
        }
        BankUtil.safeDeposit(bank, player, container);
        player.getInventory().refreshAll();
        player.getCombatDefinitions().refresh();
        if (container.getSize() != 0) {
            player.sendMessage("Not enough space in your bank.");
        }
    }

    @Override
    public GameInterface getInterface() {
        return BANK;
    }


    public enum ItemOption {
        WITHDRAW_1_OR_SELECTED(1, (player, itemAmount) -> player.getBank().getCurrentQuantity()),
        WITHDRAW_1_ALT(2, (player, itemAmount) -> 1),
        WITHDRAW_5(3, (player, itemAmount) -> 5),
        WITHDRAW_10(4, (player, itemAmount) -> 10),
        WITHDRAW_LAST_AMOUNT(5, (player, itemAmount) -> player.getBank().getLastDepositAmount()),
        WITHDRAW_X(6), WITHDRAW_ALL(7, (player, itemAmount) -> itemAmount),
        WITHDRAW_ALL_BUT_1(8, (player, itemAmount) -> itemAmount - 1), CLEAR(7),
        CLEAR_ALL(8),
        PLACEHOLDER(9, (player, itemAmount) -> itemAmount),
        EXAMINE(10);

        private static final ItemOption[] values = values();
        private final int optionId;
        private final ParametrizedIntFunction<Player> function;

        ItemOption(final int optionId) {
            this(optionId, (player, itemAmount) -> {
                throw new IllegalArgumentException("Invalid constant.");
            });
        }

        ItemOption(final int optionId, final ParametrizedIntFunction<Player> function) {
            this.optionId = optionId;
            this.function = function;
        }

        /**
         * Gets the ItemOption constant for the input integer option id.
         *
         * @param option the option id.
         * @return the ItemOption constant.
         */
        public static final ItemOption of(final int option) {
            final BankInterface.ItemOption constant = CollectionUtils.findMatching(values, value -> value.optionId == option);
            if (constant == null) {
                throw new IllegalArgumentException("Option cannot be " + option + ".");
            }
            return constant;
        }

        /**
         * Whether the input enum constant is identical to this option-wise, necessary because
         * {@code SkeletonEnum#equals(final Object other)} is final, thus preventing us from overriding it,
         * and because the options {@code CLEAR_ALL} and {@code WITHDRAW_ALL_BUT_1} are identical
         * option id wise.
         *
         * @param other the other constant to compare against.
         * @return whether the constants are identical option id wise.
         */
        public boolean is(final ItemOption other) {
            return other.optionId == optionId;
        }

        /**
         * Gets the amount of the constant variable.
         *
         * @param player the player argument, necessary to get a dynamic amount.
         * @return the respective amount of the constant.
         */
        public int getAmount(final Player player, final int itemAmount) {
            return function.getInt(player, itemAmount);
        }

        /**
         * A primitive-returning generic int function, used to prevent overhead created by autoboxing and unboxing.
         *
         * @param <K> generic parameter.
         */
        @FunctionalInterface
        private interface ParametrizedIntFunction<K> {
            int getInt(final K k, final int itemAmount);
        }
    }
}

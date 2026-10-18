package com.zenyte.game.model.ui.testinterfaces;

import com.gadscape.game.content.kits.KitsTabCache;
import com.zenyte.game.GameInterface;
import com.zenyte.game.content.preset.Preset;
import com.zenyte.game.content.preset.PresetManager;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.util.Colour;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.game.world.entity.player.privilege.GameMode;
import com.zenyte.game.world.region.area.wilderness.WildernessArea;

/**
 * The Kits tab, which sits where the music player was (like Roat Pkz). It lists the player's gear presets;
 * right-click a kit to load, overwrite, rename, delete or make it the default. The interface itself is added
 * to the cache by {@link KitsTabCache} at start-up. The class keeps its old name so it stays registered.
 */
public class MusicPlayerInterface extends Interface {

    private static final String PAGE_KEY = "kits_tab_page";
    private static final int MAX_NAME = 20;

    @Override
    protected void attach() {
        put(KitsTabCache.SAVE_BUTTON, "Save");
        for (int row = 0; row < KitsTabCache.ROWS; row++) {
            put(KitsTabCache.FIRST_ROW + row, "Kit " + row);
        }
        put(KitsTabCache.PREV, "Previous");
        put(KitsTabCache.NEXT, "Next");
        put(KitsTabCache.PAGE, "Page");
        put(KitsTabCache.COUNT, "Count");
        // MusicHandler still writes the song name here; it goes to a hidden line.
        put(KitsTabCache.DUMMY, "Song name");
    }

    @Override
    public void open(Player player) {
        player.getInterfaceHandler().sendInterface(getInterface());
        refresh(player);
    }

    public static void refresh(Player player) {
        final PresetManager presets = player.getPresetManager();
        final int total = presets.getTotalPresets();
        final int pages = Math.max(1, (total + KitsTabCache.ROWS - 1) / KitsTabCache.ROWS);
        final int page = Math.min(page(player), pages - 1);
        player.getTemporaryAttributes().put(PAGE_KEY, page);
        final GameInterface tab = GameInterface.MUSIC_TAB;
        for (int row = 0; row < KitsTabCache.ROWS; row++) {
            final int index = page * KitsTabCache.ROWS + row;
            final Preset preset = presets.getPreset(index);
            final int component = KitsTabCache.FIRST_ROW + row;
            if (preset == null) {
                player.getPacketDispatcher().sendComponentVisibility(tab, component, true);
                continue;
            }
            String name = (index + 1) + ". " + preset.getName();
            if (index == presets.getDefaultPreset()) {
                name += " <col=ffffff>(default)</col>";
            }
            if (!preset.isAvailable()) {
                name = "<col=808080>" + name + " (locked)</col>";
            }
            player.getPacketDispatcher().sendComponentText(tab, component, name);
            player.getPacketDispatcher().sendComponentVisibility(tab, component, false);
        }
        player.getPacketDispatcher().sendComponentText(tab, KitsTabCache.PAGE, (page + 1) + "/" + pages);
        player.getPacketDispatcher().sendComponentText(tab, KitsTabCache.COUNT,
                total == 0 ? "No kits yet. Gear up, then save it." : total + " / " + presets.getMaximumPresets() + " kits");
    }

    private static int page(Player player) {
        final Object page = player.getTemporaryAttributes().get(PAGE_KEY);
        return page instanceof Integer ? (Integer) page : 0;
    }

    private static boolean canUse(Player player) {
        if (player.getGameMode() == GameMode.ULTIMATE_IRON_MAN) {
            player.sendMessage("Ultimate Iron Men can't use kits.");
            return false;
        }
        if (!player.isStaff() && WildernessArea.isWithinWilderness(player.getLocation())) {
            player.sendMessage("You can't use kits in the Wilderness.");
            return false;
        }
        if (!player.isStaff() && player.isUnderCombat()) {
            player.sendMessage("You can't use kits while you're in combat.");
            return false;
        }
        if (player.isLocked() || player.isDead()) {
            return false;
        }
        return true;
    }

    private static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        final String cleaned = name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (cleaned.isEmpty() || cleaned.length() > MAX_NAME) {
            return null;
        }
        return cleaned;
    }

    @Override
    protected void build() {
        bind("Save", player -> {
            if (!canUse(player)) {
                return;
            }
            final PresetManager presets = player.getPresetManager();
            if (presets.getTotalPresets() >= presets.getMaximumPresets()) {
                player.sendMessage("You have no free kit slots. Donator ranks give more.");
                return;
            }
            player.sendInputString("Name this kit:", input -> {
                final String name = cleanName(input);
                if (name == null) {
                    player.sendMessage("Kit names are 1 to " + MAX_NAME + " letters, numbers or spaces.");
                    return;
                }
                if (presets.getTotalPresets() >= presets.getMaximumPresets()) {
                    return;
                }
                presets.addPreset(presets.getTotalPresets(), name);
                player.sendMessage("Saved your gear as the kit " + Colour.RS_PURPLE.wrap(name) + ".");
                refresh(player);
            });
        });
        for (int row = 0; row < KitsTabCache.ROWS; row++) {
            final int r = row;
            bind("Kit " + row, (player, slotId, itemId, option) -> kitOption(player, page(player) * KitsTabCache.ROWS + r, option));
        }
        bind("Previous", player -> {
            player.getTemporaryAttributes().put(PAGE_KEY, Math.max(0, page(player) - 1));
            refresh(player);
        });
        bind("Next", player -> {
            player.getTemporaryAttributes().put(PAGE_KEY, page(player) + 1);
            refresh(player);
        });
    }

    private static void kitOption(Player player, int index, int option) {
        final PresetManager presets = player.getPresetManager();
        final Preset preset = presets.getPreset(index);
        if (preset == null) {
            return;
        }
        switch (option) {
            case 1 -> {
                if (canUse(player)) {
                    PresetManagerInterface.load(player, index);
                }
            }
            case 2 -> {
                if (!canUse(player)) {
                    return;
                }
                if (!preset.isAvailable()) {
                    player.sendMessage("That kit is locked.");
                    return;
                }
                player.getDialogueManager().start(new Dialogue(player) {
                    @Override
                    public void buildDialogue() {
                        options("Overwrite " + preset.getName() + " with your current gear?",
                                new DialogueOption("Yes.", () -> {
                                    if (presets.getPreset(index) != preset) {
                                        return;
                                    }
                                    presets.setPreset(index, new Preset(preset.getName(), player));
                                    player.sendMessage("Kit " + Colour.RS_PURPLE.wrap(preset.getName()) + " now holds your current gear.");
                                    refresh(player);
                                }), new DialogueOption("No."));
                    }
                });
            }
            case 3 -> player.sendInputString("Rename " + preset.getName() + ":", input -> {
                final String name = cleanName(input);
                if (name == null) {
                    player.sendMessage("Kit names are 1 to " + MAX_NAME + " letters, numbers or spaces.");
                    return;
                }
                if (presets.getPreset(index) != preset) {
                    return;
                }
                preset.rename(name);
                refresh(player);
            });
            case 4 -> player.getDialogueManager().start(new Dialogue(player) {
                @Override
                public void buildDialogue() {
                    options("Delete the kit " + preset.getName() + "?", new DialogueOption("Yes.", () -> {
                        if (presets.getPreset(index) != preset) {
                            return;
                        }
                        presets.getPresets().remove(index);
                        if (index <= presets.getDefaultPreset()) {
                            presets.setDefaultPreset(presets.getDefaultPreset() == index ? 0 : presets.getDefaultPreset() - 1);
                        }
                        presets.revalidatePresets();
                        player.sendMessage("Kit " + Colour.RS_PURPLE.wrap(preset.getName()) + " deleted.");
                        refresh(player);
                    }), new DialogueOption("No."));
                }
            });
            case 5 -> {
                if (!preset.isAvailable()) {
                    player.sendMessage("Locked kits can't be the default.");
                    return;
                }
                presets.setDefaultPreset(index);
                refresh(player);
            }
            default -> {
            }
        }
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.MUSIC_TAB;
    }
}

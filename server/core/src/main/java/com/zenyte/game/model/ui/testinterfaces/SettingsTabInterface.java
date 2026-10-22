package com.zenyte.game.model.ui.testinterfaces;

import com.zenyte.game.GameInterface;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.model.ui.PaneType;
import com.zenyte.game.model.ui.testinterfaces.advancedsettings.SettingVariables;
import com.zenyte.game.packet.PacketDispatcher;
import com.zenyte.game.util.AccessMask;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.Setting;

import static com.zenyte.game.GameInterface.HOUSE_OPTIONS_TAB;
import static com.zenyte.game.model.ui.testinterfaces.advancedsettings.SettingsInterface.SIDE_PANELS_VARBIT_ID;

/**
 * @author Kris | 24/10/2018 12:32
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class SettingsTabInterface extends Interface {

    @Override
    protected void attach() {
        // Build 241 settings_side (116) ids, matched by component names and hook-script arguments; 211 ids in comments.
        put(5, "PK Skull Prevention"); // skull_prevention
        put(122, "Sound Effect Volume"); // sound_bobble_container (211: 55)
        put(23, "Brightness"); // brightness_bobble_container
        put(40, "Game Client Layout"); // display_dynamic_setting_1_buttons (211: 84); 241 116:27 onLoad 7990 -> 7992 builds the same struct-2732 dropdown into 116:40 as 211's 440 did into 116:84
        put(39, "NPC attack options"); // attack_priority_npc_buttons (211: 82)
        put(38, "Player attack options"); // attack_priority_player_buttons (211: 81)
        put(136, "Area Sound Volume"); // areasounds_bobble_container (211: 69)
        put(108, "Music Volume"); // music_bobble_container (211: 41)
        put(29, "Accept aid"); // acceptaid (211: 72)
        put(30, "Toggle run"); // runmode (211: 73)
        put(31, "House options"); // houseoptions (211: 74)
        put(33, "Open bond pouch"); // bondoptions (211: 76)
        put(32, "All Settings"); // settings_open (211: 75)
        put(110, "Mute Music"); // music_icon (211: 30)
        put(124, "Mute Sound Effects"); // sound_icon (211: 44)
        put(138, "Mute Area Sounds"); // areasound_icon (211: 58)
        // 211 116:71 "Toggle unlock message" has no counterpart in 241 settings_side (the option lives in the settings
        // interface 134); the server never bound it. 241's master volume (83/94/96), the volume sliders and mute icons and
        // the tab buttons 58/66/67 are client-side (scripts 7099/3933-3935 -> 9232/9238 set the varps themselves).
        put(43, "Disable zooming with scroll wheel"); // zoom_toggle (211: 87)
    }

    @Override
    public void open(Player player) {
        final PacketDispatcher dispatcher = player.getPacketDispatcher();
        player.getInterfaceHandler().sendInterface(getInterface());
        dispatcher.sendComponentSettings(getInterface(), getComponent("Sound Effect Volume"), 0, 21, AccessMask.CLICK_OP1);
        dispatcher.sendComponentSettings(getInterface(), getComponent("Brightness"), 0, 21, AccessMask.CLICK_OP1);
        dispatcher.sendComponentSettings(getInterface(), getComponent("Game Client Layout"), 1, 3, AccessMask.CLICK_OP1);
        dispatcher.sendComponentSettings(getInterface(), getComponent("NPC attack options"), 1, 4, AccessMask.CLICK_OP1);
        dispatcher.sendComponentSettings(getInterface(), getComponent("Player attack options"), 1, 5, AccessMask.CLICK_OP1);
        dispatcher.sendComponentSettings(getInterface(), getComponent("Area Sound Volume"), 0, 21, AccessMask.CLICK_OP1);
        dispatcher.sendComponentSettings(getInterface(), getComponent("Music Volume"), 0, 21, AccessMask.CLICK_OP1);
    }

    @Override
    protected void build() {
        bind("PK Skull Prevention", player -> player.getVarManager().flipBit(SettingVariables.PK_SKULL_PREVENTION_VARBIT_ID));
        bind("All Settings", GameInterface.ADVANCED_SETTINGS::open);
        bind("Sound Effect Volume", (player, slotId, itemId, option) -> setSoundEffectVolume(player, slotId * 5));
        bind("Music Volume", (player, slotId, itemId, option) -> setMusicVolume(player, slotId * 5));
        bind("Area Sound Volume", (player, slotId, itemId, option) -> setAreaSoundVolume(player, slotId * 5));
        bind("Player attack options", (player, slotId, itemId, option) -> player.getVarManager().sendVar(SettingVariables.PLAYER_ATTACK_OPTIONS_VARP_ID, slotId - 1));
        bind("NPC attack options", (player, slotId, itemId, option) -> player.getVarManager().sendVar(SettingVariables.NPC_ATTACK_OPTIONS_VARP_ID, slotId - 1));
        bind("Accept aid", player -> {
            if (player.isIronman()) {
                player.getVarManager().sendBit(SettingVariables.ACCEPT_AID_VARBIT_ID, 0);
                player.sendMessage("Ironmen cannot accept aid from other players.");
                return;
            }
            player.getVarManager().flipBit(SettingVariables.ACCEPT_AID_VARBIT_ID);
        });
        bind("Toggle run", player -> {
            player.getInterfaceHandler().closeInterfaces();
            player.setRun(!player.isRun());
        });
        bind("House options", HOUSE_OPTIONS_TAB::open);
        bind("Brightness", (player, slotId, itemId, option) -> player.getVarManager().sendVar(SettingVariables.SCREEN_BRIGHTNESS_VARP_ID, slotId * 5));
        bind("Game Client Layout", (player, slotId, itemId, option) -> {
            if (slotId == 1) {
                if (player.getInterfaceHandler().getPane() != PaneType.FIXED) {
                    player.getInterfaceHandler().sendPane(player.getInterfaceHandler().getPane(), PaneType.FIXED);
                }
            } else if (slotId == 2) {
                if (player.getInterfaceHandler().getPane() != PaneType.RESIZABLE) {
                    player.getInterfaceHandler().sendPane(player.getInterfaceHandler().getPane(), PaneType.RESIZABLE);
                    player.getVarManager().sendBitInstant(SIDE_PANELS_VARBIT_ID, 0);
                }
            } else if (slotId == 3) {
                if (player.getInterfaceHandler().getPane() != PaneType.SIDE_PANELS) {
                    player.getInterfaceHandler().sendPane(player.getInterfaceHandler().getPane(), PaneType.SIDE_PANELS);
                    player.getVarManager().sendBitInstant(SIDE_PANELS_VARBIT_ID, 1);
                }
            }
        });
    }

    private void setMusicVolume(final Player player, final int volume) {
        if (volume != 0) {
            if (player.getVarManager().getValue(SettingVariables.MUSIC_VOLUME_VARP_ID) == 0) {
                player.getMusic().restartCurrent();
            }
        }
        player.getVarManager().sendVarInstant(SettingVariables.MUSIC_VOLUME_VARP_ID, volume);
    }

    private void setSoundEffectVolume(final Player player, final int volume) {
        player.getVarManager().sendVarInstant(SettingVariables.SOUND_EFFECT_VOLUME_VARP_ID, volume);
    }

    private void setAreaSoundVolume(final Player player, final int volume) {
        player.getVarManager().sendVarInstant(SettingVariables.AREA_SOUND_VOLUME_VARP_ID, volume);
    }

    @Override
    public boolean isInterruptedOnLock() {
        return false;
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.SETTINGS;
    }
}

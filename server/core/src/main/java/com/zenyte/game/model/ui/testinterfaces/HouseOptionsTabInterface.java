package com.zenyte.game.model.ui.testinterfaces;

import com.zenyte.game.GameInterface;
import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.world.entity.player.Player;

/**
 * House options (interface 370), GadScape's 211 layout: viewer 1, building mode 5/6, teleport inside 8/9, default building mode
 * 11/12, doors closed 14/15, open 16/17, none 18/19, expel guests 20, leave house 21, call servant 22, room count text 23.
 * The client already flips the teleport-inside/default-building/doors varbits itself; the server mirrors them so later varbit
 * updates on the same varps don't undo the choice.
 *
 * @author Tommeh | 28-10-2018 | 19:26
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public class HouseOptionsTabInterface extends Interface {

    @Override
    protected void attach() {
        put(1, "Open house viewer");
        put(5, "Building mode ON");
        put(6, "Building mode OFF");
        put(8, "Teleport inside ON");
        put(9, "Teleport inside OFF");
        put(11, "Default building mode ON");
        put(12, "Default building mode OFF");
        put(14, "Render doors closed");
        put(15, "Render doors closed text");
        put(16, "Render doors open");
        put(17, "Render doors open text");
        put(18, "Render no doors");
        put(19, "Render no doors text");
        put(20, "Expel guests");
        put(21, "Leave house");
        put(22, "Call servant");
        put(23, "Number of rooms");
    }

    @Override
    public void open(Player player) {
        if (player.isUnderCombat()) {
            player.sendMessage("You can't do this while in combat.");
            return;
        }
        player.getInterfaceHandler().sendInterface(getInterface());
        player.getConstruction().refreshHouseOptions();
    }

    @Override
    protected void build() {
        bind("Open house viewer", player -> {
            final Construction house = player.getConstruction();
            if (house.hasGuests()) {
                player.sendMessage("You can't use the house viewer while you have guests in your house.");
                return;
            }
            house.getHouseViewer().openHouseViewer();
        });
        bind("Building mode ON", player -> player.getConstruction().setBuildingModeOption(true));
        bind("Building mode OFF", player -> player.getConstruction().setBuildingModeOption(false));
        bind("Teleport inside ON", player -> player.getConstruction().setTeleportInside(true));
        bind("Teleport inside OFF", player -> player.getConstruction().setTeleportInside(false));
        bind("Default building mode ON", player -> player.getConstruction().setDefaultBuildingMode(true));
        bind("Default building mode OFF", player -> player.getConstruction().setDefaultBuildingMode(false));
        bind("Render doors closed", player -> player.getConstruction().setDoorModeOption(Construction.DOORS_CLOSED));
        bind("Render doors closed text", player -> player.getConstruction().setDoorModeOption(Construction.DOORS_CLOSED));
        bind("Render doors open", player -> player.getConstruction().setDoorModeOption(Construction.DOORS_OPEN));
        bind("Render doors open text", player -> player.getConstruction().setDoorModeOption(Construction.DOORS_OPEN));
        bind("Render no doors", player -> player.getConstruction().setDoorModeOption(Construction.DOORS_NONE));
        bind("Render no doors text", player -> player.getConstruction().setDoorModeOption(Construction.DOORS_NONE));
        bind("Expel guests", player -> {
            final Construction house = player.getConstruction();
            if (!house.hasGuests()) {
                player.sendMessage("You don't have any guests in your house.");
                return;
            }
            house.expelGuests("You have been expelled from the house.");
            player.sendMessage("You expel your guests.");
        });
        bind("Leave house", player -> {
            final Construction house = player.getCurrentHouse();
            if (house == null) {
                player.sendMessage("You're not in a house.");
                return;
            }
            house.exit(player);
        });
        bind("Call servant", player -> {
            final com.zenyte.game.content.skills.construction.servant.ServantManager servants = player.getConstruction().getServantManager();
            if (servants.getType() != null && !servants.hasBellPull()) {
                player.sendMessage("You need a bell-pull in your dining room to call your servant from here.");
                return;
            }
            servants.call(player);
        });
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.HOUSE_OPTIONS_TAB;
    }
}

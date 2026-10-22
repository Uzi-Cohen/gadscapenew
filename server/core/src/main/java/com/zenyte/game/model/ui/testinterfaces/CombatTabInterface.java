package com.zenyte.game.model.ui.testinterfaces;

import com.zenyte.game.GameInterface;
import com.zenyte.game.content.minigame.duelarena.Duel;
import com.zenyte.game.content.minigame.duelarena.DuelSetting;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.world.entity.player.Player;

/**
 * @author Kris | 24/10/2018 14:03
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class CombatTabInterface extends Interface {
    @Override
    protected void attach() {
        put(4, "Attack style 1");
        put(8, "Attack style 2");
        put(12, "Attack style 3");
        put(16, "Attack style 4");
        put(21, "Defensive autocast");
        put(26, "Autocast");
        put(30, "Auto retaliate");
        put(36, "Special attack");
        // 2746 (241) only: the main-hand / off-hand special bars shown when the weapon and the shield (Rondache) both
        // have a special. 211's 593 has components 0-42, so these ids clash with nothing; the 211 -> 241 remap must leave
        // them as they are (special_attack_mainhand 57, special_attack_offhand 67).
        put(57, "Special attack main hand");
        put(67, "Special attack off hand");
    }

    @Override
    public void open(Player player) {
        player.getInterfaceHandler().sendInterface(getInterface());
        player.getCombatDefinitions().refresh();
    }

    @Override
    public boolean isInterruptedOnLock() {
        return false;
    }

    @Override
    protected void build() {
        bind("Attack style 1", player -> {
            player.getCombatDefinitions().setStyle(0);
            player.getCombatDefinitions().setAutocastSpell(null);
        });
        bind("Attack style 2", player -> {
            player.getCombatDefinitions().setStyle(1);
            player.getCombatDefinitions().setAutocastSpell(null);
        });
        bind("Attack style 3", player -> {
            player.getCombatDefinitions().setStyle(2);
            player.getCombatDefinitions().setAutocastSpell(null);
        });
        bind("Attack style 4", player -> {
            player.getCombatDefinitions().setAutocastSpell(null);
            player.getCombatDefinitions().setStyle(3);
            player.getCombatDefinitions().refresh();
        });
        bind("Defensive autocast", player -> {
            if (player.isLocked()) {
                return;
            }
            player.getCombatDefinitions().setDefensiveAutocast(true);
            GameInterface.AUTOCAST_TAB.open(player);
        });
        bind("Autocast", player -> {
            if (player.isLocked()) {
                return;
            }
            player.getCombatDefinitions().setDefensiveAutocast(false);
            GameInterface.AUTOCAST_TAB.open(player);
        });
        bind("Auto retaliate", player -> {
            if (player.isLocked()) {
                return;
            }
            player.getCombatDefinitions().setAutoRetaliate(!player.getCombatDefinitions().isAutoRetaliate());
        });
        bind("Special attack", player -> toggleSpecialBar(player, 0));
        bind("Special attack main hand", player -> toggleSpecialBar(player, 1));
        bind("Special attack off hand", player -> toggleSpecialBar(player, 2));
    }

    /** Special attack bars: 0 = the single bar, 1 = the main-hand bar, 2 = the off-hand (Rondache) bar. */
    private static void toggleSpecialBar(final Player player, final int bar) {
        if (player.isLocked()) {
            return;
        }
        final Duel duel = player.getDuel();
        if (duel != null && duel.hasRule(DuelSetting.NO_SPECIAL_ATTACK) && duel.inDuel()) {
            player.sendMessage("Use of special attacks has been turned off for this duel.");
            return;
        }
        player.getCombatDefinitions().toggleSpecialBar(bar);
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.COMBAT_TAB;
    }
}

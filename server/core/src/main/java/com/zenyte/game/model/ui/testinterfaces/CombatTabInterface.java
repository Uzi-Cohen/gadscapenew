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
        // Build 241 combat_interface (593) ids; 211 ids in the comments.
        put(6, "Attack style 1"); // 211: 4
        put(10, "Attack style 2"); // 211: 8
        put(14, "Attack style 3"); // 211: 12
        put(18, "Attack style 4"); // 211: 16
        put(23, "Defensive autocast"); // 211: 21
        put(28, "Autocast"); // 211: 26
        put(32, "Auto retaliate"); // 211: 30
        put(41, "Special attack"); // 211: 36 (special_attack, inside the hidden sp_attackbar)
        put(57, "Special attack main hand"); // 241 special_attack_mainhand (new bar)
        put(67, "Special attack off hand"); // 241 special_attack_offhand (new bar)
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
        final BasicHandler special = player -> {
            if (player.isLocked()) {
                return;
            }
            final Duel duel = player.getDuel();
            if (duel != null && duel.hasRule(DuelSetting.NO_SPECIAL_ATTACK) && duel.inDuel()) {
                player.sendMessage("Use of special attacks has been turned off for this duel.");
                return;
            }
            player.getCombatDefinitions().setSpecial(!player.getCombatDefinitions().isUsingSpecial(), false);
        };
        bind("Special attack", special);
        bind("Special attack main hand", special);
        bind("Special attack off hand", special);
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.COMBAT_TAB;
    }
}

package com.zenyte.game.content.skills.magic.lecterns;

import com.zenyte.game.GameInterface;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.world.entity.player.Player;

/**
 * The regular (home) lectern. On 211 it had its own screen, interface 79; build 241 reuses 79 for another interface
 * and draws every lectern with teletabs_craft_if (403), so this opens {@link ArceuusLecternInterface} listing the
 * standard-spellbook tablets (enum {@value ArceuusLecternInterface#REGULAR_TABLETS_ENUM}).
 *
 * @author Kris | 03/09/2019 07:30
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class RegularLecternInterface extends Interface {

    @Override
    protected void attach() {
    }

    @Override
    public void open(final Player player) {
        ArceuusLecternInterface.open(player, ArceuusLecternInterface.REGULAR_TABLETS_ENUM, 0);
    }

    @Override
    protected void build() {
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.REGULAR_LECTERN;
    }

}

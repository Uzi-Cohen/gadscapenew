package com.zenyte.game.model.ui.testinterfaces;

import com.zenyte.game.GameInterface;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.model.ui.InterfaceHandler;
import com.zenyte.game.world.entity.player.Player;

/**
 * @author Tommeh | 12/05/2019 | 18:24
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>
 */
public class JournalHeaderTabInterface extends Interface {

    @Override
    protected void attach() {
        // Build 241 side_journal (629) tab buttons (cacheV keeps BASE's 629: Summary, Quest List, Achievement Diaries,
        // Adventure Paths, Leagues); GadScape-211 ids in the comments. GadScape's "Game Noticeboard" tab (211: 18, the
        // removed Kourend Favour slot) has no 241 counterpart: it takes the Adventure Paths slot (GadScape has no
        // adventure paths), as in the 629/3178 port plan of ui-merge/NOTES.md; "Server Events" is the Leagues slot.
        put(2, "Character Summary"); // summary_list (211: 3)
        put(10, "Quests"); // quest_list (211: 8)
        put(18, "Achievement Diary"); // task_list (211: 13)
        put(26, "Game Noticeboard"); // adventurepath_list slot (211: 18, no 241 counterpart)
        put(34, "Server Events"); // league_list (211: 28)
    }

    @Override
    public void open(Player player) {
        player.getInterfaceHandler().sendInterface(this);
    }

    @Override
    protected void build() {
        bind("Quests", player -> player.getInterfaceHandler().setJournal(InterfaceHandler.Journal.QUEST_TAB));
        bind("Achievement Diary", player -> player.getInterfaceHandler().setJournal(InterfaceHandler.Journal.ACHIEVEMENT_DIARIES));
        bind("Character Summary", player -> player.getInterfaceHandler().setJournal(InterfaceHandler.Journal.CHARACTER_SUMMARY));
        bind("Game Noticeboard", player -> player.getInterfaceHandler().setJournal(InterfaceHandler.Journal.GAME_NOTICEBOARD));
        bind("Server Events", player -> player.getInterfaceHandler().setJournal(InterfaceHandler.Journal.SERVER_EVENTS));
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.JOURNAL_HEADER_TAB;
    }
}

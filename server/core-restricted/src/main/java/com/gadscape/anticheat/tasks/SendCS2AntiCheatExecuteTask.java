package com.gadscape.anticheat.tasks;

import com.gadscape.anticheat.GadScapeAntiCheatEngine;
import com.gadscape.game.content.commands.DeveloperCommands;
import com.zenyte.game.task.WorldTask;
import com.zenyte.game.world.entity.player.Player;

public class SendCS2AntiCheatExecuteTask implements WorldTask {
    private final Player player;

    public SendCS2AntiCheatExecuteTask(Player p) {
        this.player = p;
    }

    @Override
    public void run() {
        if(!DeveloperCommands.INSTANCE.getAnticheatEnabled())
            return;
        player.getPacketDispatcher().sendClientScript(GadScapeAntiCheatEngine.CS2_CALL_INSPECTION);
    }
}
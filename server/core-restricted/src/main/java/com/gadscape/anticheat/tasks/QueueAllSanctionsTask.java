package com.gadscape.anticheat.tasks;

import com.gadscape.anticheat.GadScapeAntiCheatEngine;
import com.gadscape.game.content.commands.DeveloperCommands;
import com.zenyte.game.task.WorldTask;
import com.zenyte.game.task.WorldTasksManager;

public class QueueAllSanctionsTask implements WorldTask {
    @Override
    public void run() {
        if(!DeveloperCommands.INSTANCE.getAnticheatEnabled())
            return;
        GadScapeAntiCheatEngine.getEngine().getSanctionQueue().forEach((player, cmd) -> {
            WorldTasksManager.schedule(new ApplyAutoSanctionTask(player, cmd), 0, -1);
        });
    }
}

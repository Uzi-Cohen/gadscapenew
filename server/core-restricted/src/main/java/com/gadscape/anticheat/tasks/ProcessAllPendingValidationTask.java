package com.gadscape.anticheat.tasks;

import com.gadscape.anticheat.GadScapeAntiCheatEngine;
import com.gadscape.game.content.commands.DeveloperCommands;
import com.zenyte.game.task.WorldTask;
import com.zenyte.game.task.WorldTasksManager;

public class ProcessAllPendingValidationTask implements WorldTask {

    @Override
    public void run() {
        if(!DeveloperCommands.INSTANCE.getAnticheatEnabled())
            return;
        GadScapeAntiCheatEngine.getEngine().getPendingValidationPlayers().forEach(it -> {
            WorldTasksManager.schedule(new SendCS2AntiCheatSetTask(it), 0, -1);
            GadScapeAntiCheatEngine.getEngine().addPlayerToAwaitingResponse(it);
            WorldTasksManager.schedule(new SendCS2AntiCheatExecuteTask(it), 6, -1);
        });
        GadScapeAntiCheatEngine.getEngine().clearPendingValidation();
    }
}

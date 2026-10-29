package com.zenyte.game.content.partyroom;

import com.zenyte.game.util.Direction;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.SoundEffect;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.pathfinding.events.player.TileEvent;
import com.zenyte.game.world.entity.pathfinding.strategy.TileStrategy;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.game.world.object.ObjectAction;
import com.zenyte.game.world.object.ObjectId;
import com.zenyte.game.world.object.WorldObject;
import com.zenyte.utils.TimeUnit;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 26/12/2019
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class PartyRoomLever implements ObjectAction {

    @Override
    public void handleObjectAction(Player player, WorldObject object, String name, int optionId, String option) {
        player.faceDirection(Direction.EAST);
        if (FaladorPartyRoom.getPartyRoom().getVariables().isDisabled()) {
            player.sendMessage("The party room has been disabled.");
            return;
        }
        player.getDialogueManager().start(new Dialogue(player) {

            @Override
            public void buildDialogue() {
                options("Drop the chest's items in balloons?", new DialogueOption("Yes.", () -> {
                    final PartyRoomVariables variables = FaladorPartyRoom.getPartyRoom().getVariables();
                    if (variables.isDisabled()) {
                        player.sendMessage("The party room has been disabled.");
                        return;
                    }
                    if (!player.getPrivilege().eligibleTo(variables.getMinimumPrivilegeToDropBalloons())) {
                        player.sendMessage("You are not eligible to drop the balloons right now.");
                        return;
                    }
                    pullLever(player, object);
                    variables.pull();
                    final long seconds = TimeUnit.TICKS.toSeconds(variables.getCountdown());
                    player.sendMessage("You pull the lever. The balloons will begin to drop in " + seconds + " seconds.");
                }), new DialogueOption("No."));
            }
        });
    }

    @Override
    public int getDelay() {
        return 1;
    }

    private final void pullLever(@NotNull final Player player, @NotNull final WorldObject object) {
        player.setAnimation(new Animation(6933));
        World.sendObjectAnimation(object, new Animation(6934));
        player.sendSound(new SoundEffect(2400));
    }

    @Override
    public void handle(final Player player, final WorldObject object, final String name, final int optionId, final String option) {
        player.setRouteEvent(new TileEvent(player, new TileStrategy(object), getRunnable(player, object, name, optionId, option), getDelay()));
    }

    @Override
    public Object[] getObjects() {
        return new Object[] { ObjectId.LEVER_26194 };
    }
}

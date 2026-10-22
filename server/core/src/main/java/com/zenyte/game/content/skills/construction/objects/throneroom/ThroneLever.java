package com.zenyte.game.content.skills.construction.objects.throneroom;

import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.ObjectInteraction;
import com.zenyte.game.content.skills.construction.RoomReference;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.game.world.object.WorldObject;

/**
 * Throne room levers (13672-13674): "Pull" springs the floor trap, "Challenge-mode" switches the dungeon's challenge mode and
 * dungeon PvP. Owner only.
 */
public final class ThroneLever implements ObjectInteraction {

    private static final Animation PULL = new Animation(3611);

    @Override
    public void handleObjectAction(final Player player, final Construction construction, final RoomReference reference, final WorldObject object, final int optionId, final String option) {
        if (!construction.isOwner(player)) {
            player.sendMessage("Only the owner of the house can use the lever.");
            return;
        }
        if (option.equalsIgnoreCase("Pull")) {
            player.setAnimation(PULL);
            World.sendObjectAnimation(object, new Animation(3612));
            construction.getCombat().pullLever(player, reference);
            return;
        }
        if (option.equalsIgnoreCase("Challenge-mode")) {
            player.getDialogueManager().start(new Dialogue(player) {
                @Override
                public void buildDialogue() {
                    options("Dungeon settings",
                            new DialogueOption("Turn challenge mode " + (construction.getCombat().isChallengeMode() ? "off" : "on"), () -> construction.getCombat().toggleChallengeMode(player)),
                            new DialogueOption("Turn dungeon PvP " + (construction.getCombat().isDungeonPvp() ? "off" : "on"), () -> construction.getCombat().toggleDungeonPvp(player)),
                            new DialogueOption("Nothing."));
                }
            });
        }
    }

    @Override
    public Object[] getObjects() {
        return new Object[] { 13672, 13673, 13674 };
    }
}

package com.zenyte.game.content.skills.construction.objects.gamesroom;

import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.ObjectInteraction;
import com.zenyte.game.content.skills.construction.RoomReference;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.util.Direction;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.object.WorldObject;

/**
 * Games room furniture (CON-9): the hoop and stick and the dartboard (throw for a score, shown to everyone in the room),
 * Jacky Jester (pops out and tells a joke), the elemental balance (summons the balance, which can be banished) and the
 * attack stone.
 */
public final class GamesRoom implements ObjectInteraction {

    private static final Animation THROW_HOOP = new Animation(3602);
    private static final Animation THROW_DART = new Animation(3605);
    private static final Animation JESTER_OUT = new Animation(3592);
    public static final int JESTER_NPC = 155;
    public static final int ELEMENTAL_BALANCE_NPC = 3370;
    private static final String[] JOKES = new String[] {
            "Why did the skeleton not go to the party? He had no body to go with!",
            "What do you call a goblin with a carrot in each ear? Anything you like, he can't hear you!",
            "Why did the chicken cross the Wilderness? It didn't, the PKers got it.",
            "How many dwarves does it take to change a lantern? None, they mine in the dark!",
            "Why do imps never pay for anything? They're always short-changed!"
    };

    @Override
    public void handleObjectAction(final Player player, final Construction construction, final RoomReference reference, final WorldObject object, final int optionId, final String option) {
        final int id = object.getId();
        if (id == 13398 || id == 13399) {
            player.setAnimation(THROW_HOOP);
            player.lock(2);
            WorldTasksManager.schedule(() -> {
                if (Utils.random(2) == 0) {
                    player.setForceTalk("Got it!");
                    player.sendMessage("The hoop lands neatly over the stick.");
                } else {
                    player.sendMessage("The hoop misses the stick.");
                }
            }, 2);
        } else if (id == 13400) {
            player.setAnimation(THROW_DART);
            player.lock(2);
            WorldTasksManager.schedule(() -> {
                final int roll = Utils.random(99);
                final int score = roll < 5 ? 50 : roll < 15 ? 25 : roll < 30 ? 3 * (1 + Utils.random(19)) : roll < 50 ? 2 * (1 + Utils.random(19)) : roll < 90 ? 1 + Utils.random(19) : 0;
                player.setForceTalk(score == 0 ? "Missed!" : score == 50 ? "Bullseye!" : "I scored " + score + "!");
            }, 2);
        } else if (id == 13390 || id == 13391) {
            if (spawnTemporary(construction, JESTER_NPC, object, JOKES[Utils.random(JOKES.length - 1)])) {
                World.sendObjectAnimation(object, JESTER_OUT);
            } else {
                player.sendMessage("Jacky Jester is already out of his box.");
            }
        } else if (id >= 13395 && id <= 13397) {
            if (spawnTemporary(construction, ELEMENTAL_BALANCE_NPC, object, null)) {
                player.sendMessage("The elemental balance appears. Cast elemental spells at it to keep it in balance.");
            } else {
                player.sendMessage("The elemental balance is already out.");
            }
        } else if (id >= 13392 && id <= 13394) {
            player.sendMessage("You set up the attack stone. Strike it to test your strength!");
            player.setForceTalk("Hyah!");
        }
    }

    /**
     * Puts an NPC next to the furniture for a while: at most one per piece of furniture (tracked by the house, removed with
     * it), spawned so it never respawns.
     *
     * @return false when this furniture's NPC is still out.
     */
    private static boolean spawnTemporary(final Construction house, final int npcId, final WorldObject object, final String line) {
        final int key = object.getPositionHash();
        final NPC existing = house.getFurnitureNpcs().get(key);
        if (existing != null && !existing.isFinished()) {
            return false;
        }
        final Location tile = new Location(object.getX(), object.getY() - 1, object.getPlane());
        final NPC npc = new NPC(npcId, World.isFloorFree(tile, 1) ? tile : new Location(object), Direction.NORTH, 0, true);
        npc.spawn();
        house.getFurnitureNpcs().put(key, npc);
        if (line != null) {
            WorldTasksManager.schedule(() -> {
                if (!npc.isFinished()) {
                    npc.setForceTalk(line);
                }
            }, 1);
        }
        WorldTasksManager.schedule(() -> {
            house.getFurnitureNpcs().remove(key, npc);
            if (!npc.isFinished()) {
                npc.finish();
            }
        }, npcId == JESTER_NPC ? 10 : 200);
        return true;
    }

    @Override
    public Object[] getObjects() {
        return new Object[] { 13390, 13391, 13392, 13393, 13394, 13395, 13396, 13397, 13398, 13399, 13400 };
    }
}

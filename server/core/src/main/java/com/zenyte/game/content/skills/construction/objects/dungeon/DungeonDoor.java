package com.zenyte.game.content.skills.construction.objects.dungeon;

import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.ObjectInteraction;
import com.zenyte.game.content.skills.construction.RoomReference;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.zenyte.game.world.object.WorldObject;

/**
 * Dungeon doors (oak 13344/13345, steel-plated 13346/13347, marble 13348/13349; open versions +6). The owner opens them, and
 * so does anyone while challenge mode is off; otherwise guests pick the lock (Thieving) or force it (Strength), harder for
 * the better doors (OSRS wiki "Dungeon (Construction)").
 */
public final class DungeonDoor implements ObjectInteraction {

    private static final Animation PICK = new Animation(3692);
    private static final Animation FORCE = new Animation(3693);
    private static final int OPEN_TICKS = 10;

    @Override
    public void handleObjectAction(final Player player, final Construction construction, final RoomReference reference, final WorldObject object, final int optionId, final String option) {
        final int id = object.getId();
        if (id >= 13350) {
            if (option.equalsIgnoreCase("Close")) {
                World.spawnObject(new WorldObject(id - 6, object.getType(), object.getRotation(), object));
            }
            return;
        }
        final boolean free = construction.isOwner(player) || !construction.getCombat().isChallengeMode();
        if (option.equalsIgnoreCase("Open")) {
            if (!free) {
                player.sendMessage("The door is locked. You'll have to pick the lock or force it open.");
                return;
            }
            open(object);
            return;
        }
        final boolean pick = option.equalsIgnoreCase("Pick-lock");
        if (!pick && !option.equalsIgnoreCase("Force")) {
            return;
        }
        if (free) {
            open(object);
            return;
        }
        final int difficulty = id <= 13345 ? 20 : id <= 13347 ? 40 : 60;
        final int level = player.getSkills().getLevel(pick ? SkillConstants.THIEVING : SkillConstants.STRENGTH);
        player.lock(2);
        player.setAnimation(pick ? PICK : FORCE);
        final int chance = Math.max(5, Math.min(95, 50 + (level - difficulty) * 2));
        if (Utils.random(99) < chance) {
            player.sendMessage(pick ? "You pick the lock." : "You force the door open.");
            player.getSkills().addXp(pick ? SkillConstants.THIEVING : SkillConstants.STRENGTH, difficulty / 2.0);
            open(object);
        } else {
            player.sendMessage(pick ? "You fail to pick the lock." : "You fail to force the door open.");
        }
    }

    private static void open(final WorldObject door) {
        World.spawnTemporaryObject(new WorldObject(door.getId() + 6, door.getType(), door.getRotation(), door), door, OPEN_TICKS);
    }

    @Override
    public Object[] getObjects() {
        return new Object[] { 13344, 13345, 13346, 13347, 13348, 13349, 13350, 13351, 13352, 13353, 13354, 13355 };
    }
}

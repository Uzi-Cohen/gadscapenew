package com.zenyte.game.content.skills.construction.combat;

import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.FurnitureData;
import com.zenyte.game.content.skills.construction.RoomReference;
import com.zenyte.game.content.skills.construction.constants.Furniture;
import com.zenyte.game.content.skills.construction.constants.FurnitureSpace;
import com.zenyte.game.content.skills.construction.constants.RoomType;
import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.util.Direction;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.World;
import com.zenyte.game.world.WorldThread;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.masks.Hit;
import com.zenyte.game.world.entity.masks.HitType;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.zenyte.game.world.object.WorldObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Fighting in a house (CON-9): combat room rings, the dungeon's challenge mode (guards, treasure monsters, traps) and dungeon
 * PvP, and the throne room lever.
 * <ul>
 * <li>Rings: players inside the same ring can fight each other ("Fight"); boxing ring unarmed or with boxing gloves only,
 * fencing ring melee only, combat ring anything. Nothing is lost on death (see {@link Construction#onDeath}).</li>
 * <li>Challenge mode (throne room lever "Challenge-mode", owner only): the built guards and treasure room monsters come alive
 * as aggressive NPCs and traps go off; off again turns them back into furniture. Dungeon PvP lets everyone in the dungeon
 * fight.</li>
 * <li>Lever "Pull": drops everyone standing on a throne room trapdoor into the oubliette below, or holds them in the cage.</li>
 * </ul>
 */
public final class HouseCombat {

    private static final String TRAP_TICK_ATTR = "poh_trap_tick";
    private static final Animation TRAP_HIT = new Animation(3626);

    private final Construction house;
    private final List<NPC> monsters = new ArrayList<>();
    private boolean challengeMode;
    private boolean dungeonPvp;

    public HouseCombat(final Construction house) {
        this.house = house;
    }

    public boolean isChallengeMode() {
        return challengeMode;
    }

    public boolean isDungeonPvp() {
        return dungeonPvp;
    }

    /** POH dungeon guard / treasure room monster NPC for a piece of furniture, or -1. */
    public static int monsterNpc(final Furniture furniture) {
        switch (furniture) {
            case SKELETON_GUARD:
                return 130;
            case GUARD_DOG:
                return 131;
            case HOBGOBLIN_GUARD:
                return 132;
            case TROLL_GUARD:
                return 133;
            case HUGE_SPIDER:
                return 134;
            case HELLHOUND:
                return 135;
            case BABY_RED_DRAGON:
                return 137;
            case KALPHITE_SOLDIER:
                return 138;
            case STEEL_DRAGON:
                return 139;
            case DAGANNOTH:
                return 140;
            case TOK_XIL:
                return 141;
            case DEMON:
                return 142;
            case RUNE_DRAGON:
                return 8031;
            default:
                return -1;
        }
    }

    /* --------------------------------------------------------------------------------------------------------------------
     * Challenge mode
     * ------------------------------------------------------------------------------------------------------------------ */

    public void toggleChallengeMode(final Player player) {
        if (house.isBuildingMode()) {
            player.sendMessage("You can't use challenge mode while in building mode.");
            return;
        }
        challengeMode = !challengeMode;
        if (challengeMode) {
            spawnMonsters();
            player.sendMessage("Challenge mode is now <col=ff0000>on</col>: the dungeon's guards, monsters and traps are active.");
        } else {
            despawnMonsters();
            player.sendMessage("Challenge mode is now off.");
        }
    }

    public void toggleDungeonPvp(final Player player) {
        dungeonPvp = !dungeonPvp;
        player.sendMessage(dungeonPvp ? "Players can now fight each other in your dungeon." : "Players can no longer fight each other in your dungeon.");
    }

    public void onHouseLoaded() {
        monsters.clear();
        if (challengeMode && !house.isBuildingMode()) {
            spawnMonsters();
        } else if (house.isBuildingMode()) {
            challengeMode = false;
            dungeonPvp = false;
        }
    }

    public void onHouseClosed() {
        monsters.clear();
        challengeMode = false;
        dungeonPvp = false;
    }

    private void spawnMonsters() {
        despawnMonsters();
        for (final RoomReference room : house.getReferences()) {
            for (final FurnitureData data : room.getFurnitureData()) {
                final int npcId = monsterNpc(data.getFurniture());
                if (npcId == -1) {
                    continue;
                }
                final Location tile = house.getRoomTile(room, data.getLocation().getX(), data.getLocation().getY());
                World.removeObject(new WorldObject(data.getFurniture().getObjectId(), data.getType(), data.getRotation(), tile));
                final NPC npc = new NPC(npcId, tile, Direction.SOUTH, 3);
                npc.setForceAggressive(true);
                npc.spawn();
                monsters.add(npc);
            }
        }
    }

    private void despawnMonsters() {
        for (final NPC npc : monsters) {
            if (npc != null && !npc.isFinished()) {
                npc.finish();
            }
        }
        monsters.clear();
        if (!house.isLoaded()) {
            return;
        }
        for (final RoomReference room : house.getReferences()) {
            for (final FurnitureData data : room.getFurnitureData()) {
                if (monsterNpc(data.getFurniture()) == -1) {
                    continue;
                }
                final Location tile = house.getRoomTile(room, data.getLocation().getX(), data.getLocation().getY());
                World.spawnObject(new WorldObject(data.getFurniture().getObjectId(), data.getType(), data.getRotation(), tile));
            }
        }
    }

    /* --------------------------------------------------------------------------------------------------------------------
     * Rings and dungeon PvP
     * ------------------------------------------------------------------------------------------------------------------ */

    /** The ring (boxing, fencing or combat ring) whose fighting area contains the tile, or null. */
    public Furniture ringAt(final Location tile) {
        final RoomReference room = house.getReference(tile);
        if (room == null || room.getRoom() != RoomType.COMBAT_ROOM) {
            return null;
        }
        final FurnitureData ring = room.getFurniture(FurnitureSpace.COMBAT_RING_SPACE);
        if (ring == null) {
            return null;
        }
        final Furniture furniture = ring.getFurniture();
        if (furniture != Furniture.BOXING_RING && furniture != Furniture.FENCING_RING && furniture != Furniture.COMBAT_RING) {
            return null;
        }
        final int x = tile.getXInChunk();
        final int y = tile.getYInChunk();
        return x >= 2 && x <= 5 && y >= 2 && y <= 5 ? furniture : null;
    }

    private boolean inDungeonPvp(final Location tile) {
        return dungeonPvp && !house.isBuildingMode() && tile.getPlane() == 0 && house.getReference(tile) != null;
    }

    public boolean canFightAt(final Location tile) {
        return !house.isBuildingMode() && (ringAt(tile) != null || inDungeonPvp(tile));
    }

    /** Tile just outside the ring of the combat room the player stands in (respawn after losing a fight). */
    public Location ringExit(final Location tile) {
        final RoomReference room = house.getReference(tile);
        return room == null ? null : house.getRoomTile(room, 1, 3);
    }

    /**
     * Whether a player may hit another player with the given style ("Melee", "Ranged", "Magic").
     */
    public boolean checkCombat(final Player attacker, final Player target, final String style) {
        final Furniture ring = ringAt(attacker.getLocation());
        final Furniture targetRing = ringAt(target.getLocation());
        if (ring != null || targetRing != null) {
            if (ring == null || targetRing == null || house.getReference(attacker.getLocation()) != house.getReference(target.getLocation())) {
                attacker.sendMessage("You can only fight players who are in the same ring as you.");
                return false;
            }
            if (ring == Furniture.BOXING_RING) {
                final Item weapon = attacker.getWeapon();
                final boolean gloves = weapon == null || weapon.getId() == ItemId.BOXING_GLOVES || weapon.getId() == ItemId.BOXING_GLOVES_7673;
                if (!"Melee".equals(style) || !gloves) {
                    attacker.sendMessage("Only boxing is allowed in a boxing ring: fight unarmed or with boxing gloves.");
                    return false;
                }
            } else if (ring == Furniture.FENCING_RING && !"Melee".equals(style)) {
                attacker.sendMessage("Only melee is allowed in a fencing ring.");
                return false;
            }
            return true;
        }
        if (inDungeonPvp(attacker.getLocation()) && inDungeonPvp(target.getLocation())) {
            return true;
        }
        attacker.sendMessage("You can't attack that player here.");
        return false;
    }

    /* --------------------------------------------------------------------------------------------------------------------
     * Traps
     * ------------------------------------------------------------------------------------------------------------------ */

    public void onPlayerTick(final Player player) {
        if (!challengeMode || house.isBuildingMode() || player.getPlane() != 0) {
            return;
        }
        final RoomReference room = house.getReference(player.getLocation());
        if (room == null) {
            return;
        }
        for (final FurnitureData data : room.getFurnitureData()) {
            if (data.getSpace() != FurnitureSpace.TRAP_SPACE && data.getSpace() != FurnitureSpace.TRAP_SPACE2) {
                continue;
            }
            final Location tile = house.getRoomTile(room, data.getLocation().getX(), data.getLocation().getY());
            if (tile.getX() != player.getX() || tile.getY() != player.getY()) {
                continue;
            }
            final long now = WorldThread.WORLD_CYCLE;
            if (player.getNumericTemporaryAttribute(TRAP_TICK_ATTR).longValue() > now) {
                return;
            }
            player.getTemporaryAttributes().put(TRAP_TICK_ATTR, now + 5);
            trigger(player, data.getFurniture());
            return;
        }
    }

    private void trigger(final Player player, final Furniture trap) {
        final int agility = player.getSkills().getLevel(SkillConstants.AGILITY);
        if (Utils.random(99) < agility / 3) {
            player.sendMessage("You nimbly avoid the " + trap.toString().toLowerCase() + ".");
            return;
        }
        switch (trap) {
            case SPIKE_TRAP:
                player.setAnimation(TRAP_HIT);
                player.applyHit(new Hit(Utils.random(3, 8), HitType.REGULAR));
                player.sendMessage("Spikes shoot up from the floor!");
                break;
            case MAN_TRAP:
                player.setAnimation(TRAP_HIT);
                player.applyHit(new Hit(Utils.random(9, 12), HitType.REGULAR));
                player.sendMessage("A man trap snaps shut on your leg!");
                break;
            case TANGLE_VINE:
                player.freeze(8);
                player.sendMessage("A vine tangles itself around your legs!");
                break;
            case MARBLE_TRAP: {
                final int level = player.getSkills().getLevel(SkillConstants.AGILITY);
                player.getSkills().setLevel(SkillConstants.AGILITY, Math.max(0, level - Math.max(1, level / 10)));
                player.sendMessage("A marble block crashes down, leaving you dazed and less agile.");
                break;
            }
            case TELEPORT_TRAP: {
                final RoomReference oubliette = firstOubliette();
                if (oubliette == null) {
                    player.sendMessage("The teleport trap fizzles.");
                    break;
                }
                player.sendMessage("The floor glows and you find yourself somewhere else!");
                final Location tile = house.getRoomTile(oubliette, 3, 3);
                WorldTasksManager.schedule(() -> player.setLocation(tile), 1);
                break;
            }
            default:
                break;
        }
    }

    private RoomReference firstOubliette() {
        for (final RoomReference room : house.getReferences()) {
            if (room.getRoom() == RoomType.OUBLIETTE) {
                return room;
            }
        }
        return null;
    }

    /* --------------------------------------------------------------------------------------------------------------------
     * Throne room lever
     * ------------------------------------------------------------------------------------------------------------------ */

    /**
     * "Pull": sets off the throne room floor trap under everyone standing on it (but the owner).
     */
    public void pullLever(final Player owner, final RoomReference throneRoom) {
        final FurnitureData floor = throneRoom.getFurniture(FurnitureSpace.THRONE_FLOOR);
        if (floor == null || floor.getFurniture() == Furniture.FLOOR_DECORATION) {
            owner.sendMessage("Nothing happens; there's no trap built in the floor.");
            return;
        }
        final List<Location> tiles = house.getSpotTiles(throneRoom, FurnitureSpace.THRONE_FLOOR);
        final RoomReference below = house.getReference(throneRoom.getX(), throneRoom.getY(), throneRoom.getPlane() - 1);
        int caught = 0;
        for (final Player victim : house.getOccupants()) {
            if (victim == owner || victim.getPlane() != throneRoom.getPlane()) {
                continue;
            }
            boolean onTrap = false;
            for (final Location tile : tiles) {
                if (tile.getX() == victim.getX() && tile.getY() == victim.getY()) {
                    onTrap = true;
                    break;
                }
            }
            if (!onTrap) {
                continue;
            }
            caught++;
            if (floor.getFurniture() == Furniture.TRAPDOOR) {
                if (below == null || below.getRoom() != RoomType.OUBLIETTE) {
                    continue;
                }
                victim.sendMessage("The floor opens beneath you and you fall into the oubliette!");
                final Location drop = new Location(victim.getX(), victim.getY(), below.getPlane());
                WorldTasksManager.schedule(() -> victim.setLocation(drop), 1);
            } else {
                victim.freeze(floor.getFurniture() == Furniture.GREATER_MAGIC_CAGE ? 30 : floor.getFurniture() == Furniture.LESSER_MAGIC_CAGE ? 20 : 15);
                victim.sendMessage("A cage springs up around you!");
            }
        }
        owner.sendMessage(caught == 0 ? "You pull the lever, but nobody is standing on the trap." : "You pull the lever.");
    }
}

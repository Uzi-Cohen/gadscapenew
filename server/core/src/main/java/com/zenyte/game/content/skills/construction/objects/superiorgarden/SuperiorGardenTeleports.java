package com.zenyte.game.content.skills.construction.objects.superiorgarden;

import com.zenyte.game.content.achievementdiary.DiaryReward;
import com.zenyte.game.content.achievementdiary.DiaryUtil;
import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.ObjectInteraction;
import com.zenyte.game.content.skills.construction.RoomReference;
import com.zenyte.game.item.Item;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.masks.Graphics;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.game.world.entity.player.variables.TickVariable;
import com.zenyte.game.world.object.FairyRing;
import com.zenyte.game.world.object.SpiritTree;
import com.zenyte.game.world.object.WorldObject;
import com.zenyte.plugins.dialogue.ItemChat;
import com.zenyte.plugins.dialogue.OptionsMenuD;
import com.zenyte.plugins.interfaces.FairyRingCombination;
import com.zenyte.plugins.interfaces.FairyRingLog;
import com.zenyte.plugins.object.WildernessObeliskOA;

/**
 * Superior garden teleport space (CON-9): spirit tree (29227), fairy ring (29228, a multi-loc showing the last code),
 * spiritual fairy tree (29422) and obelisk (31554). Guests may use them too.
 * <p>
 * Spirit tree: every spirit tree destination. Fairy ring: Zanaris, the code interface and the last destination, through the
 * normal fairy ring system. Obelisk (OSRS wiki "Obelisk (Construction)"): warns, then teleports the player alone and at once
 * to a random Wilderness obelisk, or to a chosen one with the hard Wilderness diary.
 */
public final class SuperiorGardenTeleports implements ObjectInteraction {

    public static final int SPIRIT_TREE = 29227;
    public static final int FAIRY_RING = 29228;
    /** In GadScape's 211 cache a multi-loc (showing 29422+ by the last fairy ring code), in 241 the tree itself. */
    public static final int SPIRITUAL_FAIRY_TREE = 29229;
    public static final int OBELISK = 31554;

    private static final Animation SPIRIT_TREE_ANIM = new Animation(828, 15);
    private static final Animation OBELISK_ANIM = new Animation(3945);
    private static final Graphics OBELISK_GFX = new Graphics(661);
    /** Persistent attribute: index into {@link WildernessObeliskOA#CENTER_OBELISK_TILES} chosen for the house obelisk. */
    private static final String OBELISK_DESTINATION = "poh_obelisk_destination";
    /** Obelisk names by {@link WildernessObeliskOA#CENTER_OBELISK_TILES} index. */
    private static final String[] OBELISK_NAMES = new String[] { "Level 50 Wilderness", "Level 19 Wilderness", "Level 13 Wilderness", "Level 35 Wilderness", "Level 27 Wilderness", "Level 44 Wilderness" };
    /** Menu order (by level). */
    private static final int[] OBELISK_ORDER = new int[] { 2, 1, 4, 3, 5, 0 };

    @Override
    public void handleObjectAction(final Player player, final Construction construction, final RoomReference reference, final WorldObject object, final int optionId, final String option) {
        final int id = object.getId();
        if (id == SPIRIT_TREE && option.equals("Travel") || id == SPIRITUAL_FAIRY_TREE && option.equals("Tree")) {
            player.getDialogueManager().start(new HouseSpiritTreeMenu(player));
            return;
        }
        if (id == FAIRY_RING || id == SPIRITUAL_FAIRY_TREE) {
            if (option.equals("Zanaris") || option.equals("Ring-Zanaris")) {
                FairyRing.handle(player, object, FairyRing.codes.get("BKS"));
            } else if (option.equals("Configure") || option.equals("Ring-configure") || option.equals("Favourites")) {
                FairyRingCombination.open(player, object);
                FairyRingLog.open(player);
            } else if (option.startsWith("Last-destination") || option.startsWith("Ring-last-destination")) {
                final Object last = player.getNumericAttribute("lastFairyRing");
                if (!(last instanceof Integer) || (int) last == 0) {
                    player.sendFilteredMessage("You haven't used the fairy ring teleportation system yet.");
                    return;
                }
                FairyRing.handle(player, object, FairyRing.getRing((int) last));
            }
            return;
        }
        if (id == OBELISK) {
            handleObelisk(player, option);
        }
    }

    private static void handleObelisk(final Player player, final String option) {
        if (option.equals("Set destination")) {
            if (!DiaryUtil.eligibleFor(DiaryReward.WILDERNESS_SWORD3, player)) {
                player.sendMessage("You need to complete the hard Wilderness diary to choose the obelisk's destination.");
                return;
            }
            final String[] names = new String[OBELISK_ORDER.length];
            for (int i = 0; i < OBELISK_ORDER.length; i++) {
                names[i] = OBELISK_NAMES[OBELISK_ORDER[i]];
            }
            player.getDialogueManager().start(new OptionsMenuD(player, "Choose the obelisk's destination", names) {
                @Override
                public void handleClick(final int slotId) {
                    if (slotId < 0 || slotId >= OBELISK_ORDER.length) {
                        return;
                    }
                    player.addAttribute(OBELISK_DESTINATION, OBELISK_ORDER[slotId] + 1);
                    player.sendMessage("Your obelisk will now teleport you to the " + OBELISK_NAMES[OBELISK_ORDER[slotId]] + " obelisk.");
                }

                @Override
                public boolean cancelOption() {
                    return true;
                }
            });
            return;
        }
        final Location[] tiles = WildernessObeliskOA.CENTER_OBELISK_TILES;
        final int destination;
        if (option.equals("Teleport to destination")) {
            if (!DiaryUtil.eligibleFor(DiaryReward.WILDERNESS_SWORD3, player)) {
                player.sendMessage("You need to complete the hard Wilderness diary to choose the obelisk's destination.");
                return;
            }
            final int chosen = player.getNumericAttribute(OBELISK_DESTINATION).intValue() - 1;
            if (chosen < 0 || chosen >= tiles.length) {
                player.sendMessage("You need to set a destination first.");
                return;
            }
            destination = chosen;
        } else {
            destination = Utils.random(tiles.length - 1);
        }
        player.getDialogueManager().start(new Dialogue(player) {
            @Override
            public void buildDialogue() {
                plain("The obelisk will teleport you into the <col=ff0000>Wilderness</col> (" + OBELISK_NAMES[destination] + ").<br>Are you sure you wish to continue?");
                options("Teleport into the Wilderness?", new DialogueOption("Yes.", () -> obeliskTeleport(player, tiles[destination])), new DialogueOption("No."));
            }
        });
    }

    private static void obeliskTeleport(final Player player, final Location center) {
        if (player.isLocked() || player.isDead()) {
            return;
        }
        if (player.getVariables().getTime(TickVariable.TELEBLOCK) > 0) {
            player.sendMessage("A magical force prevents you from teleporting.");
            return;
        }
        player.lock(3);
        player.setAnimation(OBELISK_ANIM);
        player.setGraphics(OBELISK_GFX);
        WorldTasksManager.schedule(() -> {
            player.setAnimation(Animation.STOP);
            player.setLocation(center.transform(Utils.random(-1, 1), Utils.random(-1, 1), 0));
            player.sendMessage("Ancient magic teleports you to a place within the wilderness!");
        }, 2);
    }

    /** Spirit tree menu for the house tree: every destination, no "current tree". */
    private static final class HouseSpiritTreeMenu extends OptionsMenuD {
        HouseSpiritTreeMenu(final Player player) {
            super(player, "Spirit Tree Locations", SpiritTree.getAvailableOptions());
        }

        @Override
        public void handleClick(final int slotId) {
            final SpiritTree destination = SpiritTree.get(slotId);
            final Location location = destination.getLocation();
            if (location == null) {
                return;
            }
            player.lock(2);
            player.setAnimation(SPIRIT_TREE_ANIM);
            player.getDialogueManager().start(new ItemChat(player, new Item(6063), "You place your hands on the dry though bark of the<br> spirit tree, and feel a surge of energy run through<br> your veins."));
            WorldTasksManager.schedule(() -> player.setLocation(location), 1);
        }

        @Override
        public boolean cancelOption() {
            return true;
        }
    }

    @Override
    public Object[] getObjects() {
        return new Object[] { SPIRIT_TREE, FAIRY_RING, SPIRITUAL_FAIRY_TREE, OBELISK };
    }
}

package com.zenyte.game.content.skills.construction.objects.menagerie;

import com.zenyte.game.content.follower.Pet;
import com.zenyte.game.content.follower.PetWrapper;
import com.zenyte.game.content.follower.impl.BossPet;
import com.zenyte.game.content.follower.impl.MiscPet;
import com.zenyte.game.content.follower.impl.SkillingPet;
import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.FurnitureData;
import com.zenyte.game.content.skills.construction.ObjectInteraction;
import com.zenyte.game.content.skills.construction.RoomReference;
import com.zenyte.game.content.skills.construction.constants.Furniture;
import com.zenyte.game.content.skills.construction.constants.FurnitureSpace;
import com.zenyte.game.content.skills.construction.constants.RoomType;
import com.zenyte.game.item.Item;
import com.zenyte.game.model.item.ItemOnObjectAction;
import com.zenyte.game.util.Direction;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.zenyte.game.world.object.WorldObject;
import com.zenyte.plugins.dialogue.OptionsMenuD;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import mgi.types.config.items.ItemDefinitions;

import java.util.ArrayList;
import java.util.List;

/**
 * Menagerie pet house (CON-9): pets used on the pet house are kept in the house and roam the menagerie rooms; "View" lists
 * them and hands one back; "Upgrade" builds the next pet house over the current one (oak, teak, mahogany, consecrated,
 * desecrated, nature).
 * <p>
 * A stored pet still counts as owned: {@code Player#containsItem} looks in {@link Construction#getMenageriePets()}, so pet
 * insurance can't reclaim it and the pet's drop roll gives the "funny feeling" message instead of a second copy.
 */
public final class PetHouse implements ObjectInteraction, ItemOnObjectAction {

    private static final Furniture[] TIERS = new Furniture[] { Furniture.OAK_HOUSE, Furniture.TEAK_HOUSE, Furniture.MAHOGANY_HOUSE, Furniture.CONSECRATED_HOUSE, Furniture.DESECRATED_HOUSE, Furniture.NATURE_HOUSE };

    @Override
    public void handleObjectAction(final Player player, final Construction construction, final RoomReference reference, final WorldObject object, final int optionId, final String option) {
        if (!construction.isOwner(player)) {
            player.sendMessage("Only the owner of the house can use the pet house.");
            return;
        }
        if (option.equalsIgnoreCase("View")) {
            view(player, construction);
        } else if (option.equalsIgnoreCase("Upgrade")) {
            upgrade(player, construction, reference, object);
        }
    }

    private static void view(final Player player, final Construction house) {
        final List<Integer> pets = house.getMenageriePets();
        if (pets.isEmpty()) {
            player.sendMessage("There are no pets in your menagerie. Use a pet on the pet house to keep it here.");
            return;
        }
        final String[] names = new String[pets.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = ItemDefinitions.getOrThrow(pets.get(i)).getName();
        }
        player.getDialogueManager().start(new OptionsMenuD(player, "Take which pet back?", names) {
            @Override
            public void handleClick(final int slotId) {
                if (slotId < 0 || slotId >= pets.size()) {
                    return;
                }
                if (!player.getInventory().hasFreeSlots()) {
                    player.sendMessage("You need some free inventory space to take a pet.");
                    return;
                }
                final int itemId = pets.remove(slotId);
                player.getInventory().addItem(new Item(itemId));
                player.sendMessage("You take your " + ItemDefinitions.getOrThrow(itemId).getName().toLowerCase() + " out of the menagerie.");
                spawnPets(house);
            }

            @Override
            public boolean cancelOption() {
                return true;
            }
        });
    }

    private static void upgrade(final Player player, final Construction house, final RoomReference reference, final WorldObject object) {
        final FurnitureData data = reference.getFurniture(FurnitureSpace.PET_HOUSE_SPACE);
        if (data == null) {
            return;
        }
        int index = -1;
        for (int i = 0; i < TIERS.length; i++) {
            if (TIERS[i] == data.getFurniture()) {
                index = i;
            }
        }
        if (index == -1 || index + 1 >= TIERS.length) {
            player.sendMessage("Your pet house can't be upgraded any further.");
            return;
        }
        final Furniture next = TIERS[index + 1];
        if (player.getSkills().getLevelForXp(SkillConstants.CONSTRUCTION) < next.getLevel()) {
            player.sendMessage("You need a Construction level of " + next.getLevel() + " to upgrade your pet house.");
            return;
        }
        final List<Item> cost = new ArrayList<>();
        for (final Item material : next.getMaterials()) {
            // The previous pet house is part of the recipe; it is the one being upgraded.
            if (material != null && !Furniture.DISPLAYED_MAP.containsKey(material.getId())) {
                cost.add(material);
            }
        }
        for (final Item material : cost) {
            if (player.getInventory().getAmountOf(material.getId()) < material.getAmount()) {
                player.sendMessage("You need " + material.getAmount() + " x " + material.getName().toLowerCase() + " to upgrade your pet house.");
                return;
            }
        }
        for (final Item material : cost) {
            player.getInventory().deleteItem(material.getId(), material.getAmount());
        }
        data.setFurniture(next);
        player.getSkills().addXp(SkillConstants.CONSTRUCTION, next.getXp());
        player.setAnimation(com.zenyte.game.content.skills.construction.ConstructionConstants.REG_ANIM);
        World.spawnObject(new WorldObject(next.getObjectId(), object.getType(), object.getRotation(), object));
        player.sendMessage("You upgrade your pet house.");
    }

    @Override
    public void handleItemOnObjectAction(final Player player, final Item item, final int slot, final WorldObject object) {
        final Construction house = player.getCurrentHouse();
        if (house == null || !house.isOwner(player)) {
            player.sendMessage("You can only keep pets in your own house.");
            return;
        }
        final Pet pet = PetWrapper.getByItem(item.getId());
        if (pet == null) {
            player.sendMessage("Only pets can live in the pet house.");
            return;
        }
        if (house.getMenageriePets().contains(item.getId())) {
            player.sendMessage("That pet already lives in your menagerie.");
            return;
        }
        player.getInventory().deleteItem(slot, item);
        house.getMenageriePets().add(item.getId());
        player.sendMessage("Your " + item.getName().toLowerCase() + " now lives in your menagerie.");
        spawnPets(house);
    }

    @Override
    public Object[] getItems() {
        final IntOpenHashSet ids = new IntOpenHashSet();
        for (final BossPet pet : BossPet.VALUES) {
            ids.add(pet.itemId());
        }
        for (final SkillingPet pet : SkillingPet.VALUES) {
            ids.add(pet.itemId());
        }
        for (final MiscPet pet : MiscPet.VALUES) {
            ids.add(pet.itemId());
        }
        final List<Object> list = new ArrayList<>();
        for (final int id : ids) {
            list.add(id);
        }
        return list.toArray();
    }

    /**
     * (Re)spawns the stored pets roaming the menagerie rooms (only when the house has a pet house). Called when the house
     * loads and when the pets change.
     */
    public static void spawnPets(final Construction house) {
        for (final NPC npc : house.getRoamingPets()) {
            if (npc != null && !npc.isFinished()) {
                npc.finish();
            }
        }
        house.getRoamingPets().clear();
        if (!house.isLoaded() || house.isBuildingMode() || house.getMenageriePets().isEmpty()) {
            return;
        }
        final List<RoomReference> rooms = new ArrayList<>();
        boolean petHouse = false;
        for (final RoomReference room : house.getReferences()) {
            if (room.getRoom() == RoomType.MENAGERIE_INDOORS || room.getRoom() == RoomType.MENAGERIE_OUTDOORS) {
                rooms.add(room);
                if (room.getFurniture(FurnitureSpace.PET_HOUSE_SPACE) != null) {
                    petHouse = true;
                }
            }
        }
        if (!petHouse || rooms.isEmpty()) {
            return;
        }
        int index = 0;
        for (final int itemId : house.getMenageriePets()) {
            final Pet pet = PetWrapper.getByItem(itemId);
            if (pet == null) {
                continue;
            }
            final RoomReference room = rooms.get(index++ % rooms.size());
            final Location tile = house.getRoomTile(room, 2 + Utils.random(3), 2 + Utils.random(3));
            // Spawned (no respawn task): the house removes and re-creates these itself.
            final NPC npc = new NPC(pet.petId(), tile, Direction.SOUTH, 3, true);
            npc.spawn();
            house.getRoamingPets().add(npc);
        }
    }

    @Override
    public Object[] getObjects() {
        return new Object[] { 26297, 26298, 26299, 26830, 26831, 26832 };
    }
}

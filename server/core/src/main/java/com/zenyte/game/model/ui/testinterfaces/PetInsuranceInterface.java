package com.zenyte.game.model.ui.testinterfaces;

import com.zenyte.game.GameInterface;
import com.zenyte.game.content.achievementdiary.diaries.ArdougneDiary;
import com.zenyte.game.content.follower.InsurableVariablePet;
import com.zenyte.game.content.follower.Pet;
import com.zenyte.game.content.follower.PetInsurance;
import com.zenyte.game.content.follower.PetWrapper;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.util.AccessMask;
import com.zenyte.game.util.ItemUtil;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.game.world.entity.player.dialogue.impl.NPCChat;
import com.zenyte.game.world.entity.player.privilege.MemberRank;
import mgi.types.config.enums.EnumDefinitions;
import mgi.types.config.enums.IntEnum;
import mgi.types.config.npcs.NPCDefinitions;
import mgi.utilities.StringFormatUtil;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Tommeh | 24-11-2018 | 13:50
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
@SuppressWarnings("unused")
public class PetInsuranceInterface extends Interface {

    private static final Logger log = Logger.getLogger(PetInsuranceInterface.class.getName());
    private static final int PET_WORDS = 3;

    @Override
    protected void attach() {
        put(11, "Reclaim Insured Pet");
    }

    @Override
    public void open(Player player) {
        // Script 6126 (build 241): pet bitsets of 3 words x 31 bits (enum 985 index i -> word i / 31, bit i % 31),
        // insured then owned, then 9 more ints (211: 2 + 2 words and 7 ints).
        final int[] insureValue = new int[PET_WORDS];
        final int[] existingPetsValue = new int[PET_WORDS];
        final IntEnum e = EnumDefinitions.getIntEnum(985);
        loop:
        for (final Pet pet : player.getPetInsurance().getInsuredPets()) {
            if (pet == null) {
                continue;
            }
            OptionalInt p = e.getKey(pet.itemId());
            final int bit = p.isPresent() && p.getAsInt() >= 0 && p.getAsInt() < PET_WORDS * 31 ? p.getAsInt() : -1;
            if (bit != -1) {
                insureValue[bit / 31] |= 1 << (bit % 31);
            }
            if (pet.hasPet(player)) {
                if (bit != -1) {
                    existingPetsValue[bit / 31] |= 1 << (bit % 31);
                }
                if (bit == -1) {
                    log.log(Level.WARNING, "Insured pet that doesn't exist in an enum? " + pet, new Throwable());
                }
                continue;
            }
            final InsurableVariablePet variablePet = InsurableVariablePet.getPet(pet.itemId());
            if (variablePet != null) {
                for (final Integer variableId : variablePet.getIds()) {
                    final Pet alternativePet = PetWrapper.getByItem(variableId);
                    if (alternativePet == null) {
                        continue;
                    }
                    if (alternativePet.hasPet(player)) {
                        if (bit != -1) {
                            existingPetsValue[bit / 31] |= 1 << (bit % 31);
                        }
                        continue loop;
                    }
                }
            }
        }

        player.getInterfaceHandler().sendInterface(getInterface());
        player.getPacketDispatcher().sendClientScript(6126, insureValue[0], insureValue[1], insureValue[2], existingPetsValue[0], existingPetsValue[1], existingPetsValue[2], 0, 0, 0, 0, 0, 0, 0, 0, 0);
        player.getPacketDispatcher().sendComponentSettings(getInterface(), getComponent("Reclaim Insured Pet"), 0, 400, AccessMask.CLICK_OP1, AccessMask.CLICK_OP10);
        player.getAchievementDiaries().update(ArdougneDiary.CHECK_INSURED_PETS);
    }

    @Override
    protected void build() {
        bind("Reclaim Insured Pet", (player, slotId, itemId, option) -> {
            if (option == 10) {
                ItemUtil.sendItemExamine(player, itemId);
                return;
            }

            final PetInsurance insurance = player.getPetInsurance();
            final Pet pet = PetWrapper.getByItem(itemId);
            if (pet == null || !insurance.isInsured(pet)) {
                return;
            }
            if (pet.hasPet(player)) {
                return;
            }
            final InsurableVariablePet variablePet = InsurableVariablePet.getPet(pet.itemId());
            if (variablePet != null) {
                for (final Integer variableId : variablePet.getIds()) {
                    final Pet alternativePet = PetWrapper.getByItem(variableId);
                    if (alternativePet == null) {
                        continue;
                    }
                    if (alternativePet.hasPet(player)) {
                        return;
                    }
                }
            }
            if (!player.getMemberRank().equalToOrGreaterThan(MemberRank.RESPECTED)) {
                if (!player.getInventory().containsItem(PetInsurance.RECLAIM_PRICE)) {
                    player.getDialogueManager().start(new NPCChat(player, 5906, "I'm afraid you don't have enough gold to reclaim the <col=00080>" + Objects.requireNonNull(NPCDefinitions.get(pet.petId())).getName() + "</col> pet."));
                    return;
                }
            }
            player.getDialogueManager().start(new Dialogue(player) {
                @Override
                public void buildDialogue() {
                    options("Would you like to reclaim the <col=00080>" + NPCDefinitions.getOrThrow(pet.petId()).getName() + "</col> pet for " + (player.getMemberRank().equalToOrGreaterThan(MemberRank.RESPECTED) ? "free?" : (StringFormatUtil.format(PetInsurance.RECLAIM_PRICE.getAmount()) + " coins?")), "Yes.", "Nevermind.").onOptionOne(() -> {
                        if (!player.getInventory().checkSpace()) {
                            setKey(5);
                        } else {
                            setKey(10);
                            if (!player.getMemberRank().equalToOrGreaterThan(MemberRank.RESPECTED)) {
                                player.getInventory().deleteItem(PetInsurance.RECLAIM_PRICE);
                            }
                            player.getInventory().addItem(pet.itemId(), 1);
                            GameInterface.PET_INSURANCE.open(player);
                        }
                    }).onOptionTwo(() -> setKey(15));
                    npc(5906, "I'm afraid you don't have enough inventory space.", 5);
                    npc(5906, "Here's your pet!", 10);
                    player(15, "Nevermind.");
                }
            });
        });
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.PET_INSURANCE;
    }
}

package com.gadscape.plugins.interfaces.characterdesign

import com.gadscape.scripts.interfaces.InterfaceScript
import com.zenyte.game.GameInterface
import com.zenyte.game.world.entity.masks.UpdateFlag
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.privilege.MemberRank
import mgi.types.config.identitykit.IdentityKitDefinitions

/**
 * @author Kris | 14/06/2022
 */
class CharacterDesignerInterface : InterfaceScript() {
    // Component ids are rev 241 (interface 679 "player_design"; 211 used 3 lower, 6 lower for confirm).
    init {
        GameInterface.CHARACTER_DESIGN {
            "Previous head"(15) { player.changeDesign(CharacterCreatorDesign.Head, false) }
            "Next head"(16) { player.changeDesign(CharacterCreatorDesign.Head, true) }
            "Previous jaw"(19) { player.changeDesign(CharacterCreatorDesign.Jaw, false) }
            "Next jaw"(20) { player.changeDesign(CharacterCreatorDesign.Jaw, true) }
            "Previous torso"(23) { player.changeDesign(CharacterCreatorDesign.Torso, false) }
            "Next torso"(24) { player.changeDesign(CharacterCreatorDesign.Torso, true) }
            "Previous arms"(27) { player.changeDesign(CharacterCreatorDesign.Arms, false) }
            "Next arms"(28) { player.changeDesign(CharacterCreatorDesign.Arms, true) }
            "Previous hands"(31) { player.changeDesign(CharacterCreatorDesign.Hands, false) }
            "Next hands"(32) { player.changeDesign(CharacterCreatorDesign.Hands, true) }
            "Previous legs"(35) { player.changeDesign(CharacterCreatorDesign.Legs, false) }
            "Next legs"(36) { player.changeDesign(CharacterCreatorDesign.Legs, true) }
            "Previous feet"(39) { player.changeDesign(CharacterCreatorDesign.Feet, false) }
            "Next feet"(40) { player.changeDesign(CharacterCreatorDesign.Feet, true) }

            "Previous hair colour"(46) { player.changeColour(CharacterCreatorColour.Hair, false) }
            "Next hair colour"(47) { player.changeColour(CharacterCreatorColour.Hair, true) }
            "Previous torso colour"(50) { player.changeColour(CharacterCreatorColour.Torso, false) }
            "Next torso colour"(51) { player.changeColour(CharacterCreatorColour.Torso, true) }
            "Previous legs colour"(54) { player.changeColour(CharacterCreatorColour.Legs, false) }
            "Next legs colour"(55) { player.changeColour(CharacterCreatorColour.Legs, true) }
            "Previous feet colour"(58) { player.changeColour(CharacterCreatorColour.Feet, false) }
            "Next feet colour"(59) { player.changeColour(CharacterCreatorColour.Feet, true) }
            "Previous skin colour"(62) { player.changeColour(CharacterCreatorColour.Skin, false) }
            "Next skin colour"(63) { player.changeColour(CharacterCreatorColour.Skin, true) }

            "Male"(68) { player.changeGender(true) }
            "Female"(69) { player.changeGender(false) }
            "Confirm"(74) { player.confirm() }
            opened {
                sendInterface()
            }
        }
    }
}

fun Player.changeDesign(design: CharacterCreatorDesign, next: Boolean) {
    if (design == CharacterCreatorDesign.Jaw && !appearance.isMale) return
    val kits = IdentityKitDefinitions.definitions
    var current = appearance.appearance[design.id].toInt()
    val genderOffset = if (appearance.isMale) 0 else 7
    var nextKit: IdentityKitDefinitions?
    do {
        if (next) {
            current++
            if (current >= kits.size) current = 0
        } else {
            current--
            if (current < 0) current = kits.size - 1
        }
        nextKit = IdentityKitDefinitions.get(current)
        val partId = nextKit?.bodyPartId
    } while (nextKit == null || !nextKit.isSelectable || partId == null || (design.id + genderOffset) != partId)
    appearance.appearance[design.id] = nextKit.id.toShort()
    updateFlags.flag(UpdateFlag.APPEARANCE)
}

fun Player.changeColour(colour: CharacterCreatorColour, next: Boolean) {
    var current = appearance.colours[colour.id].toInt()
    var found: Boolean
    do {
        if (next) {
            current++
            if (current >= colour.count) current = 0
        } else {
            current--
            if (current < 0) current = colour.count - 1
        }
        val skinColour = SkinColour[current]
        val hasSkinColourUnlocked = skinColour != null && memberRank.equalToOrGreaterThan(skinColour.rank)
        found = colour != CharacterCreatorColour.Skin || current < 8 || hasSkinColourUnlocked
    } while (!found)
    appearance.colours[colour.id] = current.toByte()
    updateFlags.flag(UpdateFlag.APPEARANCE)
}

fun Player.changeGender(male: Boolean) {
    appearance.isMale = male
    varManager.sendBitInstant(14021, if (male) 0 else 1)
    for (design in enumValues<CharacterCreatorDesign>()) {
        if (design == CharacterCreatorDesign.Jaw) {
            appearance.appearance[design.id] = if (male) 10 else 1000
            continue
        }
        val parts = if (!male) design.maleParts else design.femaleParts
        val oppositeParts = if (!male) design.femaleParts else design.maleParts
        val current = IdentityKitDefinitions.get(appearance.appearance[design.id].toInt())
        val index = parts.indexOf(current)
        val next = oppositeParts.getOrNull(index) ?: oppositeParts.first()
        appearance.appearance[design.id] = next.id.toShort()
    }
    updateFlags.flag(UpdateFlag.APPEARANCE)
}

fun Player.confirm() = interfaceHandler.closeInterfaces()

enum class CharacterCreatorDesign(val id: Int) {
    Head(0),
    Jaw(1),
    Torso(2),
    Arms(3),
    Hands(4),
    Legs(5),
    Feet(6);
    private val filteredConfigValues: List<IdentityKitDefinitions> get() = IdentityKitDefinitions.definitions.filterNot { !it.isSelectable }
    val maleParts = filteredConfigValues.filter { it.bodyPartId == id }
    val femaleParts = filteredConfigValues.filter { it.bodyPartId == id + 7 }
}

enum class CharacterCreatorColour(val id: Int, val count: Int) {
    Hair(0, 25),
    Torso(1, 29),
    Legs(2, 29),
    Feet(3, 6),
    Skin(4, 13),
}

private enum class SkinColour(val index: Int, val rank: MemberRank) {
    BLACK(9, MemberRank.SUPER),
    WHITE(10, MemberRank.SUPER),
    GREEN(8, MemberRank.EXTREME),
    TURQOISE(11, MemberRank.LEGENDARY),
    PURPLE(12, MemberRank.ROYAL);

    companion object {
        private val all: Array<SkinColour> = values()
        private val map: MutableMap<Int, SkinColour> = HashMap(all.size)

        init {
            for (colour in all) {
                map[colour.index] = colour
            }
        }

        operator fun get(index: Int): SkinColour? {
            return map[index]
        }
    }
}

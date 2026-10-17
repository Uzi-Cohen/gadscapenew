package com.gadscape.game.content.elven.item

import com.gadscape.game.content.crystal.CrystalRecipe
import com.gadscape.game.content.elven.canMake
import com.gadscape.game.content.elven.produce
import com.gadscape.game.world.WorldEvent
import com.zenyte.game.world.entity.SoundEffect
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.player.Action
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.SkillConstants
import com.zenyte.game.world.entity.player.dialogue.dialogue

/**
 * Represents an [Action] for creating [amount] [crystal item(s)][CrystalRecipe.productItemId]
 * according to the argued [recipe].
 *
 * @author Stan van der Bend
 */
class MakeCrystalItemAction(
    private val recipe: CrystalRecipe,
    private val amount: Int,
) : Action() {

    private var remaining = amount

    override fun start() =
        player.canMake(recipe) && recipe.hasMaterials(player)

    override fun process() =
        remaining > 0 && recipe.hasMaterials(player)

    override fun processWithDelay(): Int {
        if (!recipe.hasMaterials(player))
            return -1
        player.run {
            if (recipe.deleteMaterials(player)) {
                animation = Animation(8459)
                sendSound(SoundEffect(2256, 1, 8))
                val product = recipe.produce()
                inventory.addItem(product)
                skills.addXp(SkillConstants.CRAFTING, recipe.craftingExperience.toDouble())
                skills.addXp(SkillConstants.SMITHING, recipe.smithingExperience.toDouble())
                dialogue {
                    item(product, "With the help of the crystal bowl, " +
                            "you sing a beautiful song and shape the crystals")
                }
            }
        }
        remaining--
        return 1
    }
}

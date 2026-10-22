package com.gadscape.game.content.combat

import com.zenyte.game.item.ItemId.*
import com.zenyte.game.world.entity.player.Player


object CombatUtility {
    @JvmStatic fun hasBountyHunterWeapon(player: Player) : Boolean {
        when(player.weapon?.id) {
            VESTAS_LONGSWORD_BH,
            VESTAS_SPEAR_BH,
            STATIUSS_WARHAMMER_BH,
            MORRIGANS_THROWING_AXE_BH,
            MORRIGANS_JAVELIN_BH,
            ZURIELS_STAFF_BH -> return true
        }
        return false
    }

    @JvmStatic fun hasBHImbuedWeapon(player: Player) : Boolean {
        when(player.weapon?.id) {
            DARK_BOW_BH,
            BARRELCHEST_ANCHOR_BH,
            DRAGON_MACE_BH,
            DRAGON_LONGSWORD_BH,
            ABYSSAL_DAGGER_BH -> return true
        }
        return false
    }

    @JvmStatic fun hasBHKittedWeapon(player: Player) : Boolean {
        when(player.weapon?.id) {
            ELDER_MAUL_OR,
            HEAVY_BALLISTA_OR,
            DRAGON_2H_SWORD_CR,
            DRAGON_BATTLEAXE_CR,
            DRAGON_CLAWS_CR,
            DRAGON_CROSSBOW_CR,
            DRAGON_DAGGER_CR,
            DRAGON_DAGGER_PCR, DRAGON_DAGGER_PCR_28023, DRAGON_DAGGER_PCR_28025, // poisoned (cr) daggers
            DRAGON_HALBERD_CR,
            DRAGON_LONGSWORD_CR,
            DRAGON_MACE_CR,
            DRAGON_SCIMITAR_CR,
            DRAGON_SPEAR_CR,
            DRAGON_SPEAR_PCR, DRAGON_SPEAR_PCR_28045, DRAGON_SPEAR_PCR_28047, // poisoned (cr) spears
            DRAGON_SWORD_CR,
            DRAGON_WARHAMMER_CR, -> return true
        }
        return false
    }
}
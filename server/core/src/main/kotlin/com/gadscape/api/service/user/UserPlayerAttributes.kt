package com.gadscape.api.service.user

import com.gadscape.api.model.User
import com.gadscape.api.util.toMemberRank
import com.gadscape.api.util.toPrivilege
import com.gadscape.game.content.donator.DonatorRanks
import com.zenyte.game.util.Colour
import com.zenyte.game.world.entity.player.Player


fun Player.onSetUser(value: User?) {
    playerInformation.userIdentifier = value?.id?.toInt()?:-1
    // the saved rank: a YouTuber's Divine perks (Player.getMemberRank) must not be written into it
    val previousRank = storedMemberRank
    // ranks never go down: the store API's rank only ever raises the saved one
    value?.memberRank?.toMemberRank()?.let { memberRank = DonatorRanks.upgraded(previousRank, it) }
    val previousPrivilege = privilege
    privilege = value?.privilege?.toPrivilege() ?: previousPrivilege
    if (isLoggedIn) {
        sendDeveloperMessage(if (value == null) "User set to null, oh no?" else "User updated")
        try {
            if (previousRank != storedMemberRank && attributes.remove("HOME_TELEPORT") != null)
                sendMessage(Colour.RED.wrap("Your saved home teleport was removed because of the rank change! Talk to Squire to set it again."))
            updateDonationInfoOnPlayerDetailsTab()
        } catch(ignored: Exception) {
            // user offline
        }
    }
}

/**
 * The total amount of dollars the player has spent in the store.
 */
val Player.storeTotalSpent get() = user?.totalSpent ?: 0

/**
 * The amount of spendable store credits the player has.
 */
val Player.storeCredits get() = user?.storeCredits ?: 0

/**
 * Whether the player has two-factor authentication enabled.
 */
val Player.twoFactorEnabled get() = user?.twoFactorEnabled ?: false

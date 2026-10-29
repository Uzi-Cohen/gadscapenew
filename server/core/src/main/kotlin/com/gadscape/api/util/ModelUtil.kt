package com.gadscape.api.util

import com.gadscape.api.model.ApiMemberRank
import com.gadscape.api.model.ApiPrivilege
import com.zenyte.game.world.entity.player.privilege.MemberRank
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege

/** The old store API's rank -> the donator rank its dollar amount buys now. */
internal fun ApiMemberRank.toMemberRank() = when(this) {
    ApiMemberRank.AMASCUT -> MemberRank.AMASCUT
    else -> MemberRank.forTotalCents(requiredDonatedAmount * 100L)
}

internal fun ApiPrivilege.toPrivilege() = when(this) {
    ApiPrivilege.PLAYER -> PlayerPrivilege.PLAYER
    ApiPrivilege.YOUTUBER -> PlayerPrivilege.YOUTUBER
    ApiPrivilege.MEMBER -> PlayerPrivilege.MEMBER
    ApiPrivilege.FORUM_MODERATOR -> PlayerPrivilege.FORUM_MODERATOR
    ApiPrivilege.SUPPORT -> PlayerPrivilege.SUPPORT
    ApiPrivilege.MODERATOR -> PlayerPrivilege.MODERATOR
    ApiPrivilege.SENIOR_MODERATOR -> PlayerPrivilege.SENIOR_MODERATOR
    ApiPrivilege.ADMINISTRATOR -> PlayerPrivilege.ADMINISTRATOR
    ApiPrivilege.DEVELOPER -> PlayerPrivilege.DEVELOPER
    ApiPrivilege.HIDDEN_ADMINISTRATOR -> PlayerPrivilege.HIDDEN_ADMINISTRATOR
    ApiPrivilege.TRUE_DEVELOPER -> PlayerPrivilege.TRUE_DEVELOPER
}

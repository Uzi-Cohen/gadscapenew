package com.gadscape.api.facade

import com.gadscape.api.model.Skill
import com.gadscape.api.responses.UserUpdateHiScoresResponse

interface HiscoresFacade {

    suspend fun updateHiscores(userId: Long, skills: List<Skill>): UserUpdateHiScoresResponse
}

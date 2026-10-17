package com.gadscape.api.facade

import com.gadscape.api.model.Sanction
import com.gadscape.api.model.SanctionLevel
import com.gadscape.api.responses.SanctionRevokeResponse
import com.gadscape.api.responses.SanctionSubmitResponse

interface SanctionFacade {

    suspend fun submitSanction(sanction: Sanction) : SanctionSubmitResponse

    suspend fun revokeSanction(sanctionId: Long, level: SanctionLevel) : SanctionRevokeResponse
}

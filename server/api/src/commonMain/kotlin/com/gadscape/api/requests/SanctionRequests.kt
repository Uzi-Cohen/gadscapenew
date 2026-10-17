package com.gadscape.api.requests

import com.gadscape.api.model.Sanction
import kotlinx.serialization.Serializable

@Serializable
data class SanctionSubmit(val sanction: Sanction)

@Serializable
data class SanctionRevoke(val sanction: Sanction)

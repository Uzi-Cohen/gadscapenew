package com.gadscape.api.model

import kotlinx.serialization.Serializable

@Serializable
data class GameWorldCredentials(val username: String, val password: String)

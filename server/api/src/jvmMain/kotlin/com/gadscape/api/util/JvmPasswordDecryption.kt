package com.gadscape.api.util

import com.gadscape.api.ApiConstants
import com.gadscape.api.requests.UserLoginRequest


suspend fun UserLoginRequest.decryptPassword() =
    AES.decrypt(password, ApiConstants.AES_SECRET)

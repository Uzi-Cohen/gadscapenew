package com.gadscape.network.login

import com.gadscape.crypto.StreamCipher

/**
 * @author Jire
 */
data class LoginCiphers(
    val encode: StreamCipher,
    val decode: StreamCipher
)
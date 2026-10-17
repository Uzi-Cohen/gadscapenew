package com.gadscape.crypto

object NopStreamCipher : StreamCipher {
    override fun nextInt() = 0
}
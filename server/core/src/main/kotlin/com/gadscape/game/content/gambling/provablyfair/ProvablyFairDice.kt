package com.gadscape.game.content.gambling.provablyfair

/*
 * Ported from https://github.com/roatpkz/rsps-provably-fair (MIT License, Copyright Roat Pkz).
 *
 * Deterministic 0.00-100.00 roll from SHA-256("clientSeed:serverSeed:nonce"): the first 4 hash
 * bytes read as an unsigned 32-bit int, taken mod 10001, divided by 100. Same inputs always
 * produce the same roll, so anyone can recompute it once the server seed is revealed.
 */

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ProvablyFairDice {

    @JvmStatic
    fun rollPercentage(clientSeed: String, serverSeed: String, nonce: Int): Double =
        computeScaled(clientSeed, serverSeed, nonce) / 100.0

    private fun computeScaled(clientSeed: String, serverSeed: String, nonce: Int): Int {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$clientSeed:$serverSeed:$nonce".toByteArray(StandardCharsets.UTF_8))

        val unsigned = ((hash[0].toLong() and 0xFF) shl 24) or
                ((hash[1].toLong() and 0xFF) shl 16) or
                ((hash[2].toLong() and 0xFF) shl 8) or
                (hash[3].toLong() and 0xFF)

        return (unsigned % 10_001).toInt()
    }
}

package com.gadscape.game.content.gambling.provablyfair

/*
 * Ported from the rsps-provably-fair project (MIT License; copyright notice in THIRD_PARTY_NOTICES.md).
 *
 * Deterministically places mineCount mines across a 5x5 (25 cell) grid: a Fisher-Yates-style
 * swap-with-last draw over a 0-24 bag, where each draw index comes from
 * SHA-256("clientSeed:serverSeed:nonce:counter") mod (cells remaining).
 */

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.BitSet

object ProvablyFairMines {

    const val SLOTS = 25

    fun generateLayout(clientSeed: String, serverSeed: String, nonce: Int, mineCount: Int): BitSet {
        require(mineCount in 1 until SLOTS) { "mineCount must be 1-24" }

        val bag = IntArray(SLOTS) { it }
        val mines = BitSet(SLOTS)
        val sha256 = MessageDigest.getInstance("SHA-256")

        var remaining = SLOTS
        var ctr = 0L
        var toPlace = mineCount

        while (toPlace-- > 0) {
            val h = sha256.digest("$clientSeed:$serverSeed:$nonce:${ctr++}".toByteArray(StandardCharsets.UTF_8))
            var rand = 0L
            for (i in 0 until 8) rand = (rand shl 8) or (h[i].toLong() and 0xFF)

            val idx = java.lang.Long.remainderUnsigned(rand, remaining.toLong()).toInt()
            val cell = bag[idx]
            mines.set(cell)

            remaining--
            bag[idx] = bag[remaining]
        }
        return mines
    }
}

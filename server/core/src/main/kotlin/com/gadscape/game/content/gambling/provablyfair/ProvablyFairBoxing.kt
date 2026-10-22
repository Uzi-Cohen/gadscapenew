package com.gadscape.game.content.gambling.provablyfair

/*
 * Ported from the rsps-provably-fair project (MIT License; copyright notice in THIRD_PARTY_NOTICES.md).
 *
 * Deterministic 0-16 hit value from SHA-256("p1Seed:p2Seed:serverSeed:hitNo"), first 8 hash
 * bytes read as an unsigned 64-bit long, taken mod 17. hitNo alternates by player per turn so
 * the whole bout's sequence of hits can be replayed once the server seed is revealed.
 */

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ProvablyFairBoxing {

    const val MAX_HIT_VALUE = 16

    fun hit(p1Seed: String, p2Seed: String, serverSeed: String, hitNo: Long): Int {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$p1Seed:$p2Seed:$serverSeed:$hitNo".toByteArray(StandardCharsets.UTF_8))
        var rand = 0L
        for (i in 0 until 8) rand = (rand shl 8) or (hash[i].toLong() and 0xFF)
        return java.lang.Long.remainderUnsigned(rand, (MAX_HIT_VALUE + 1).toLong()).toInt()
    }

    /** playerIndex1or2: 1 or 2. turn: 0-based round counter. */
    fun hitForPlayer(playerIndex1or2: Int, turn: Int, p1Seed: String, p2Seed: String, serverSeed: String): Int {
        val hitNo = turn.toLong() * 2 + if (playerIndex1or2 == 1) 0 else 1
        return hit(p1Seed, p2Seed, serverSeed, hitNo)
    }
}

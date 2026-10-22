package com.gadscape.game.content.gambling.provablyfair

/*
 * Provably fair seed/hash utilities.
 *
 * Ported from the rsps-provably-fair project (MIT License; copyright notice in THIRD_PARTY_NOTICES.md).
 * Algorithm unchanged: SHA-256 commit/reveal over "clientSeed:serverSeed[:nonce]".
 */

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.BitSet

object ProvablyFair {

    private val RNG = SecureRandom()

    private val ALPHANUM = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-").toCharArray()

    private const val SEED_LEN = 16

    private val ALLOWED = BitSet(128).apply {
        for (c in ALPHANUM) set(c.code)
    }

    /** SHA-256 of [s], lowercase hex. Used to publish the server seed hash before play, and to prove it after. */
    @JvmStatic
    fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(64)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** A fresh, cryptographically random 16-character seed. */
    @JvmStatic
    fun randomSeed(): String {
        val buf = CharArray(SEED_LEN)
        for (i in 0 until SEED_LEN) buf[i] = ALPHANUM[RNG.nextInt(ALPHANUM.size)]
        return String(buf)
    }

    /** A client seed is only valid if non-empty and drawn from the same alphanumeric+hyphen alphabet. */
    @JvmStatic
    fun isSeedValid(seed: String?): Boolean {
        if (seed.isNullOrEmpty()) return false
        for (c in seed) {
            if (c.code >= ALLOWED.size() || !ALLOWED.get(c.code)) return false
        }
        return true
    }

    /**
     * General-purpose deterministic integer in [0, bound), derived the same way as
     * [ProvablyFairDice]: SHA-256("clientSeed:serverSeed:nonce"), first 4 bytes read as an
     * unsigned 32-bit int, taken mod [bound]. Use a distinct nonce per independent draw within
     * a round (e.g. each card dealt, each flower planted, each mine tile) so the whole sequence
     * can be replayed and verified once the server seed is revealed.
     */
    @JvmStatic
    fun randomInt(clientSeed: String, serverSeed: String, nonce: Int, bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$clientSeed:$serverSeed:$nonce".toByteArray(StandardCharsets.UTF_8))
        val unsigned = ((hash[0].toLong() and 0xFF) shl 24) or
                ((hash[1].toLong() and 0xFF) shl 16) or
                ((hash[2].toLong() and 0xFF) shl 8) or
                (hash[3].toLong() and 0xFF)
        return (unsigned % bound).toInt()
    }
}

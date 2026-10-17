package com.gadscape.crypto

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import java.math.BigInteger

object Rsa {

    private lateinit var EXPONENT: BigInteger
    private lateinit var MODULUS: BigInteger

    /**
     * Must be called once at startup with the private exponent + modulus from the
     * world profile (worlds.yml) before any login packet is decoded.
     *
     * The matching public modulus lives client-side in
     * gadscape-client-main/runelite-client/.../RuneLite.java's MODULUS
     * constant (NOT runescape-client's class70.java field896, which looks like
     * the real thing but is dead - the injector builds the shipped client from
     * a vanilla dependency jar and nothing mixes that field in). Both sides
     * must be regenerated and updated together.
     */
    fun init(privateExponent: String, modulus: String) {
        EXPONENT = BigInteger(privateExponent, 36)
        MODULUS = BigInteger(modulus, 36)
    }

    fun ByteBuf.toBigInteger(): BigInteger {
        val bytes = ByteBufUtil.getBytes(this, readerIndex(), readableBytes(), false)
        return BigInteger(bytes)
    }

    fun BigInteger.toByteBuf(): ByteBuf {
        return Unpooled.wrappedBuffer(toByteArray())
    }

    fun ByteBuf.rsa(): ByteBuf {
        return toBigInteger().modPow(EXPONENT, MODULUS).toByteBuf()
    }

}
package com.gadscape.network.rsprot

import net.rsprot.crypto.rsa.RsaKeyPair
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Loads the login RSA private key from a file (system property `gadscape.rsa.private`).
 *
 * Accepted formats (first match wins):
 *  - PEM `-----BEGIN PRIVATE KEY-----` (PKCS#8) or `-----BEGIN RSA PRIVATE KEY-----` (PKCS#1);
 *  - `key: value` / `key=value` lines as in worlds.yml: `rsaPrivateExponent` + `rsaModulus`
 *    (also `privateExponent`/`exponent`/`d` and `modulus`/`n`). Values are base 36 like Rsa.kt reads,
 *    unless prefixed with `0x` or the key ends in `Hex` (then base 16);
 *  - two bare lines: private exponent then modulus, base 36.
 * If the path is a directory, the first key-looking file inside it is used.
 */
object RsprotRsa {

    @JvmStatic
    fun load(path: Path): RsaKeyPair {
        val file = if (Files.isDirectory(path)) findKeyFile(path) else path
        val text = Files.readString(file)
        val pair = parse(text) ?: error("Could not read an RSA private key from $file")
        require(pair.modulus.bitLength() >= 512) { "RSA modulus in $file is too small (${pair.modulus.bitLength()} bits)" }
        return pair
    }

    private fun findKeyFile(dir: Path): Path {
        val preferred = listOf("private.key", "rsa-private.pem", "private.pem", "rsa.key", "test-rsa.yml", "rsa.yml")
        for (name in preferred) {
            val candidate = dir.resolve(name)
            if (Files.isRegularFile(candidate)) return candidate
        }
        Files.list(dir).use { stream ->
            return stream.filter { Files.isRegularFile(it) }
                .filter { p ->
                    val n = p.fileName.toString().lowercase()
                    (n.endsWith(".pem") || n.endsWith(".key") || n.endsWith(".yml") || n.endsWith(".txt")) &&
                        !n.contains("pub")
                }
                .sorted()
                .findFirst()
                .orElseThrow { IllegalStateException("No RSA private key file in $dir") }
        }
    }

    @JvmStatic
    fun parse(text: String): RsaKeyPair? {
        if (text.contains("-----BEGIN")) return parsePem(text)
        val values = HashMap<String, String>()
        val bare = ArrayList<String>()
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val sep = line.indexOfFirst { it == ':' || it == '=' }
            if (sep > 0) {
                values[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim().trim('"', '\'')
            } else {
                bare += line
            }
        }
        fun lookup(vararg names: String): BigInteger? {
            for (name in names) {
                val hexValue = values[name.lowercase() + "hex"]
                if (hexValue != null) return BigInteger(hexValue.removePrefix("0x"), 16)
                val value = values[name.lowercase()] ?: continue
                return if (value.startsWith("0x")) BigInteger(value.substring(2), 16) else BigInteger(value, 36)
            }
            return null
        }
        val exponent = lookup("rsaPrivateExponent", "privateExponent", "exponent", "d")
        val modulus = lookup("rsaModulus", "modulus", "n")
        if (exponent != null && modulus != null) return RsaKeyPair(exponent, modulus)
        if (bare.size >= 2) return RsaKeyPair(BigInteger(bare[0], 36), BigInteger(bare[1], 36))
        return null
    }

    private fun parsePem(text: String): RsaKeyPair {
        val pkcs1 = text.contains("BEGIN RSA PRIVATE KEY")
        val body = text.lines()
            .filterNot { it.startsWith("-----") }
            .joinToString("") { it.trim() }
        val der = Base64.getDecoder().decode(body)
        if (pkcs1) {
            // RSAPrivateKey ::= SEQUENCE { version, modulus, publicExponent, privateExponent, ... }
            val reader = DerReader(der)
            reader.enterSequence()
            reader.readInteger() // version
            val modulus = reader.readInteger()
            reader.readInteger() // public exponent
            val privateExponent = reader.readInteger()
            return RsaKeyPair(privateExponent, modulus)
        }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
        return RsaKeyPair(key.privateExponent, key.modulus)
    }

    private class DerReader(private val data: ByteArray) {
        private var pos = 0

        fun enterSequence() {
            check(data[pos++].toInt() == 0x30) { "Expected DER SEQUENCE" }
            readLength()
        }

        fun readInteger(): BigInteger {
            check(data[pos++].toInt() == 0x02) { "Expected DER INTEGER" }
            val length = readLength()
            val value = BigInteger(data.copyOfRange(pos, pos + length))
            pos += length
            return value
        }

        private fun readLength(): Int {
            val first = data[pos++].toInt() and 0xFF
            if (first < 0x80) return first
            var length = 0
            repeat(first and 0x7F) { length = (length shl 8) or (data[pos++].toInt() and 0xFF) }
            return length
        }
    }
}

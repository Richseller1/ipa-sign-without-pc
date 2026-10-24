package com.aistudio.iossideloadbridge.app.auth

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SRP-6a (Secure Remote Password) cryptographic engine tailored for Apple's GrandSlam Authentication (GSA).
 * Follows RFC 5054 2048-bit prime group with SHA-256 hashing and Apple's PBKDF2 s2k/s2k_fo password derivation.
 *
 * References:
 * - RFC 5054: Using the Secure Remote Password (SRP) Protocol for TLS Authentication
 * - rileytestut/AltSign (ALTAppleAPI+Authentication.m)
 * - SideStore/RustSign (src/request.rs, rustcrypto/srp)
 */
object SrpCrypto {

    // RFC 5054 2048-bit safe prime N (256 bytes)
    val N_HEX = (
        "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
        "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
        "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
        "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
        "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
        "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
        "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
        "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73"
    )

    val N: BigInteger = BigInteger(N_HEX, 16)
    val G: BigInteger = BigInteger.valueOf(2)
    const val KEY_SIZE_BYTES = 256 // 2048 bits

    private val secureRandom = SecureRandom()

    /**
     * Precomputed multiplier k = SHA256(N || pad(g))
     */
    val K_MULTIPLIER: BigInteger by lazy {
        val nBytes = toPaddedByteArray(N, KEY_SIZE_BYTES)
        val gBytes = toPaddedByteArray(G, KEY_SIZE_BYTES)
        val digest = sha256(nBytes + gBytes)
        BigInteger(1, digest)
    }

    /**
     * Generates a random client ephemeral keypair (a, A = g^a mod N).
     * @return Pair(private 'a', public 'A')
     */
    fun generateClientEphemeral(): Pair<BigInteger, BigInteger> {
        val aBytes = ByteArray(32)
        var a: BigInteger
        var A: BigInteger
        do {
            secureRandom.nextBytes(aBytes)
            a = BigInteger(1, aBytes)
            A = G.modPow(a, N)
        } while (A.mod(N) == BigInteger.ZERO)
        return Pair(a, A)
    }

    /**
     * Computes the scrambling parameter u = SHA256(pad(A) || pad(B))
     */
    fun computeU(A: BigInteger, B: BigInteger): BigInteger {
        val aBytes = toPaddedByteArray(A, KEY_SIZE_BYTES)
        val bBytes = toPaddedByteArray(B, KEY_SIZE_BYTES)
        val uHash = sha256(aBytes + bBytes)
        val u = BigInteger(1, uHash)
        if (u == BigInteger.ZERO) {
            throw IllegalArgumentException("SRP-6a safety check failed: u == 0")
        }
        return u
    }

    /**
     * Pure byte-level PBKDF2-HMAC-SHA256 for a single 32-byte block (256 bits).
     * This avoids any char[]-to-byte encoding corruption in Java's SecretKeyFactory
     * when passing raw binary password hashes (s2k).
     */
    fun pbkdf2HmacSha256(passwordBytes: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))

        // Block 1: salt || 0x00 0x00 0x00 0x01
        val saltWithBlock = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, saltWithBlock, 0, salt.size)
        saltWithBlock[salt.size + 3] = 1

        var u = mac.doFinal(saltWithBlock)
        val result = u.copyOf()
        for (i in 2..iterations) {
            u = mac.doFinal(u)
            for (j in 0 until 32) {
                result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return result
    }

    /**
     * Derives Apple's password key using PBKDF2-HMAC-SHA256:
     * - "s2k_fo": SHA256(password) formatted as lowercase hex string (ASCII), then PBKDF2.
     * - "s2k": raw 32 bytes of SHA256(password), then PBKDF2.
     */
    fun derivePasswordKey(password: String, salt: ByteArray, iterations: Int, protocol: String): ByteArray {
        val pwHashBytes = sha256(password.toByteArray(StandardCharsets.UTF_8))
        val pwInput = if (protocol.equals("s2k_fo", ignoreCase = true)) {
            bytesToHex(pwHashBytes).lowercase().toByteArray(StandardCharsets.US_ASCII)
        } else {
            pwHashBytes
        }
        return pbkdf2HmacSha256(pwInput, salt, iterations.coerceAtLeast(1))
    }

    /**
     * Derives password integer x according to SideStore/RustSign and RFC 5054 with noUsernameInX:
     * identityHash = SHA256(":" || passwordKey)
     * x = SHA256(salt || identityHash)
     */
    fun deriveX(passwordKey: ByteArray, salt: ByteArray): BigInteger {
        val colonByte = byteArrayOf(':'.code.toByte())
        val identityHash = sha256(colonByte + passwordKey)
        val xBytes = sha256(salt + identityHash)
        return BigInteger(1, xBytes)
    }

    /**
     * Direct derivation fallback for x = BigInteger(1, passwordKey)
     * (used in some CoreCrypto variants).
     */
    fun deriveXDirect(passwordKey: ByteArray): BigInteger {
        return BigInteger(1, passwordKey)
    }

    /**
     * Computes the premaster secret S and derives the session key K = SHA256(pad(S)):
     * v = g^x mod N
     * S = (B - k*v)^(a + u*x) mod N
     * K = SHA256(pad(S))
     */
    fun computeSessionKey(
        a: BigInteger,
        A: BigInteger,
        B: BigInteger,
        u: BigInteger,
        x: BigInteger
    ): Pair<BigInteger, ByteArray> {
        if (B.mod(N) == BigInteger.ZERO) {
            throw IllegalArgumentException("SRP-6a safety check failed: B mod N == 0")
        }

        val v = G.modPow(x, N)
        val kv = K_MULTIPLIER.multiply(v).mod(N)
        var base = B.subtract(kv).mod(N)
        if (base.signum() < 0) {
            base = base.add(N)
        }

        val exp = a.add(u.multiply(x))
        val S = base.modPow(exp, N)

        val sBytes = toPaddedByteArray(S, KEY_SIZE_BYTES)
        val K = sha256(sBytes)
        return Pair(S, K)
    }

    /**
     * Computes the client verification proof M1 according to RFC 5054 / pysrp / RustSign:
     * HNxorg = SHA256(pad(N)) XOR SHA256(pad(g))
     * H_I = SHA256(username)
     * M1 = SHA256( HNxorg || H_I || salt || pad(A) || pad(B) || K )
     */
    fun computeM1(
        username: String,
        salt: ByteArray,
        A: BigInteger,
        B: BigInteger,
        K: ByteArray
    ): ByteArray {
        val nBytes = toPaddedByteArray(N, KEY_SIZE_BYTES)
        val gBytes = toPaddedByteArray(G, KEY_SIZE_BYTES)

        val hN = sha256(nBytes)
        val hg = sha256(gBytes)

        val hNxorg = ByteArray(hN.size) { i ->
            (hN[i].toInt() xor hg[i].toInt()).toByte()
        }

        val hI = sha256(username.toByteArray(StandardCharsets.UTF_8))
        val aBytes = toPaddedByteArray(A, KEY_SIZE_BYTES)
        val bBytes = toPaddedByteArray(B, KEY_SIZE_BYTES)

        val m1Input = hNxorg + hI + salt + aBytes + bBytes + K
        return sha256(m1Input)
    }

    /**
     * Computes the expected server verification proof M2 = SHA256(pad(A) || M1 || K)
     */
    fun computeExpectedM2(A: BigInteger, M1: ByteArray, K: ByteArray): ByteArray {
        val aBytes = toPaddedByteArray(A, KEY_SIZE_BYTES)
        return sha256(aBytes + M1 + K)
    }

    /**
     * Decrypts Apple's Server Provided Data (spd) using AES-256-CBC with PKCS5/7 padding.
     * Keys derived from SRP session key K:
     * extraDataKey = HMAC-SHA256(K, "extra data key:")
     * extraDataIv = HMAC-SHA256(K, "extra data iv:")[0..16]
     */
    fun decryptSpd(K: ByteArray, spd: ByteArray): ByteArray {
        val extraDataKey = hmacSha256(K, "extra data key:".toByteArray(StandardCharsets.UTF_8))
        val extraDataIv = hmacSha256(K, "extra data iv:".toByteArray(StandardCharsets.UTF_8)).copyOfRange(0, 16)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(extraDataKey, "AES"), IvParameterSpec(extraDataIv))
        return cipher.doFinal(spd)
    }

    /**
     * Decrypts an AES-GCM encrypted token from Apple's GSA 'et' field.
     */
    fun decryptDataGcm(sk: ByteArray, encryptedData: ByteArray): ByteArray? {
        if (encryptedData.size < 35) return null
        // Header check: "XYZ"
        if (encryptedData[0] != 'X'.code.toByte() ||
            encryptedData[1] != 'Y'.code.toByte() ||
            encryptedData[2] != 'Z'.code.toByte()
        ) {
            return null
        }
        val iv = encryptedData.copyOfRange(3, 19) // 16 bytes IV
        val ciphertextAndTag = encryptedData.copyOfRange(19, encryptedData.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sk, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(encryptedData.copyOfRange(0, 3))
        return cipher.doFinal(ciphertextAndTag)
    }

    /**
     * Encrypts plaintext using AES-GCM with 'XYZ' header as AAD, for testing and roundtrip validation.
     */
    fun encryptDataGcm(sk: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sk, "AES"), GCMParameterSpec(128, iv))
        val header = "XYZ".toByteArray(StandardCharsets.UTF_8)
        cipher.updateAAD(header)
        val ciphertextAndTag = cipher.doFinal(plaintext)
        return header + iv + ciphertextAndTag
    }

    /**
     * Computes the HMAC-SHA256 checksum for the GSA 'apptokens' request.
     * key: sk (session key bytes from spd)
     * data: "apptokens" || adsid || app_1 || app_2 || ...
     */
    fun computeAppTokensChecksum(sk: ByteArray, adsid: String, apps: List<String>): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sk, "HmacSHA256"))
        mac.update("apptokens".toByteArray(StandardCharsets.UTF_8))
        mac.update(adsid.toByteArray(StandardCharsets.UTF_8))
        for (app in apps) {
            mac.update(app.toByteArray(StandardCharsets.UTF_8))
        }
        return mac.doFinal()
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }

    fun toPaddedByteArray(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        if (raw.size == length) return raw
        if (raw.size > length) {
            // Strip leading zero sign byte if present
            if (raw[0] == 0.toByte() && raw.size == length + 1) {
                return raw.copyOfRange(1, raw.size)
            }
            throw IllegalArgumentException("Value length ${raw.size} exceeds target length $length")
        }
        val padded = ByteArray(length)
        System.arraycopy(raw, 0, padded, length - raw.size, raw.size)
        return padded
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}

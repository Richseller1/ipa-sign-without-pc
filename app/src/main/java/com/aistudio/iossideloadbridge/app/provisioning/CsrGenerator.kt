package com.aistudio.iossideloadbridge.app.provisioning

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64 as JavaBase64

/**
 * Result container for generated RSA-2048 key pair and matching PKCS#10 Certificate Signing Request (CSR).
 */
data class GeneratedKeyAndCsr(
    val keyPair: KeyPair,
    val privateKeyPem: String,
    val publicKeyPem: String,
    val csrPem: String,
    val csrDer: ByteArray
)

/**
 * Generates 2048-bit RSA key pairs and PKCS#10 Certificate Signing Requests (CSR) entirely on-device
 * using standard Android/Java cryptography (java.security.KeyPairGenerator and java.security.Signature)
 * and a lightweight RFC 2986 ASN.1 DER serializer.
 *
 * Subject DN matches AltSign / Xcode convention:
 * C=US, ST=CA, L=Los Angeles, O=AltSign, CN=AltSign
 */
object CsrGenerator {

    private const val KEY_SIZE = 2048

    fun generate(
        country: String = "US",
        state: String = "CA",
        locality: String = "Los Angeles",
        organization: String = "SideloadBridge",
        commonName: String = "SideloadBridge Developer"
    ): GeneratedKeyAndCsr {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(KEY_SIZE)
        val keyPair = keyGen.generateKeyPair()

        val csrDer = buildCsrDer(keyPair, country, state, locality, organization, commonName)
        val csrPem = toPem("CERTIFICATE REQUEST", csrDer)
        val pkcs1Der = extractPkcs1FromPkcs8(keyPair.private.encoded)
        val privateKeyPem = toPem("RSA PRIVATE KEY", pkcs1Der)
        val publicKeyPem = toPem("PUBLIC KEY", keyPair.public.encoded)

        return GeneratedKeyAndCsr(
            keyPair = keyPair,
            privateKeyPem = privateKeyPem,
            publicKeyPem = publicKeyPem,
            csrPem = csrPem,
            csrDer = csrDer
        )
    }

    /**
     * Unwraps PKCS#1 RSAPrivateKey bytes from PKCS#8 PrivateKeyInfo.
     */
    fun extractPkcs1FromPkcs8(pkcs8Bytes: ByteArray): ByteArray {
        var idx = 0
        if (pkcs8Bytes.isEmpty() || pkcs8Bytes[idx] != 0x30.toByte()) return pkcs8Bytes
        idx++
        if (pkcs8Bytes[idx].toInt() and 0x80 != 0) {
            val numLenBytes = pkcs8Bytes[idx].toInt() and 0x7F
            idx += 1 + numLenBytes
        } else {
            idx++
        }
        // Skip version INTEGER (0x02, 0x01, 0x00)
        if (idx + 2 < pkcs8Bytes.size && pkcs8Bytes[idx] == 0x02.toByte()) {
            val vLen = pkcs8Bytes[idx + 1].toInt() and 0xFF
            idx += 2 + vLen
        }
        // Skip AlgorithmIdentifier SEQUENCE (0x30, len, ...)
        if (idx + 1 < pkcs8Bytes.size && pkcs8Bytes[idx] == 0x30.toByte()) {
            idx++
            if (pkcs8Bytes[idx].toInt() and 0x80 != 0) {
                val numLenBytes = pkcs8Bytes[idx].toInt() and 0x7F
                var l = 0
                for (b in 1..numLenBytes) {
                    l = (l shl 8) or (pkcs8Bytes[idx + b].toInt() and 0xFF)
                }
                idx += 1 + numLenBytes + l
            } else {
                val aLen = pkcs8Bytes[idx].toInt() and 0xFF
                idx += 1 + aLen
            }
        }
        // Now at OCTET STRING (0x04, len, RSAPrivateKey bytes)
        if (idx + 1 < pkcs8Bytes.size && pkcs8Bytes[idx] == 0x04.toByte()) {
            idx++
            val dataLen: Int
            if (pkcs8Bytes[idx].toInt() and 0x80 != 0) {
                val numLenBytes = pkcs8Bytes[idx].toInt() and 0x7F
                var l = 0
                for (b in 1..numLenBytes) {
                    l = (l shl 8) or (pkcs8Bytes[idx + b].toInt() and 0xFF)
                }
                idx += 1 + numLenBytes
                dataLen = l
            } else {
                dataLen = pkcs8Bytes[idx].toInt() and 0xFF
                idx++
            }
            if (idx + dataLen <= pkcs8Bytes.size) {
                return pkcs8Bytes.copyOfRange(idx, idx + dataLen)
            }
        }
        return pkcs8Bytes
    }

    fun toPem(type: String, der: ByteArray): String {
        val b64 = try {
            JavaBase64.getEncoder().encodeToString(der)
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP)
        }
        val sb = StringBuilder()
        sb.append("-----BEGIN ").append(type).append("-----\n")
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + 64, b64.length)
            sb.append(b64.substring(i, end)).append("\n")
            i += 64
        }
        sb.append("-----END ").append(type).append("-----\n")
        return sb.toString()
    }

    private fun derLen(length: Int): ByteArray {
        if (length < 128) return byteArrayOf(length.toByte())
        val bytes = mutableListOf<Byte>()
        var l = length
        while (l > 0) {
            bytes.add((l and 0xFF).toByte())
            l = l ushr 8
        }
        val res = ByteArray(bytes.size + 1)
        res[0] = (0x80 or bytes.size).toByte()
        for (idx in bytes.indices) {
            res[1 + idx] = bytes[bytes.size - 1 - idx]
        }
        return res
    }

    private fun derSeq(payload: ByteArray): ByteArray {
        return byteArrayOf(0x30.toByte()) + derLen(payload.size) + payload
    }

    private fun derSet(payload: ByteArray): ByteArray {
        return byteArrayOf(0x31.toByte()) + derLen(payload.size) + payload
    }

    private fun derOid(oidStr: String): ByteArray {
        val parts = oidStr.split(".").map { it.toInt() }
        val out = mutableListOf<Int>()
        out.add(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            var p = parts[i]
            val stack = mutableListOf<Int>()
            stack.add(p and 0x7F)
            p = p ushr 7
            while (p > 0) {
                stack.add(0x80 or (p and 0x7F))
                p = p ushr 7
            }
            out.addAll(stack.reversed())
        }
        val oidBytes = out.map { it.toByte() }.toByteArray()
        return byteArrayOf(0x06.toByte()) + derLen(oidBytes.size) + oidBytes
    }

    private fun derUtf8(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x0C.toByte()) + derLen(bytes.size) + bytes
    }

    private fun derPrintable(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x13.toByte()) + derLen(bytes.size) + bytes
    }

    private fun makeRdn(oid: String, value: String, isPrintable: Boolean = false): ByteArray {
        val valDer = if (isPrintable) derPrintable(value) else derUtf8(value)
        val pair = derSeq(derOid(oid) + valDer)
        return derSet(pair)
    }

    fun buildCsrDer(
        keyPair: KeyPair,
        country: String,
        state: String,
        locality: String,
        organization: String,
        commonName: String
    ): ByteArray {
        val subject = derSeq(
            makeRdn("2.5.4.6", country, isPrintable = true) +
            makeRdn("2.5.4.8", state) +
            makeRdn("2.5.4.7", locality) +
            makeRdn("2.5.4.10", organization) +
            makeRdn("2.5.4.3", commonName)
        )

        // keyPair.public.encoded is standard X.509 SubjectPublicKeyInfo in DER format
        val spki = keyPair.public.encoded

        // CertificationRequestInfo: version 0 (INTEGER), subject, spki, attributes [0] (empty: 0xA0, 0x00)
        val cri = derSeq(
            byteArrayOf(0x02, 0x01, 0x00) +
            subject +
            spki +
            byteArrayOf(0xA0.toByte(), 0x00)
        )

        // Sign with SHA1withRSA matching AltSign ALTCertificateRequest.m (EVP_sha1)
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(keyPair.private)
        signer.update(cri)
        val signature = signer.sign()

        // sha1WithRSAEncryption: 1.2.840.113549.1.1.5 with NULL parameter (0x05, 0x00)
        val sigAlg = derSeq(derOid("1.2.840.113549.1.1.5") + byteArrayOf(0x05, 0x00))

        // BIT STRING: 0x00 unused bits + signature
        val sigBits = byteArrayOf(0x03) + derLen(signature.size + 1) + byteArrayOf(0x00) + signature

        return derSeq(cri + sigAlg + sigBits)
    }
}

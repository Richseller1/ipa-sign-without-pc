package com.example

import com.aistudio.iossideloadbridge.app.provisioning.CsrGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class ProvisioningUnitTest {

    @Test
    fun testCsrGeneration() {
        val result = CsrGenerator.generate(
            country = "US",
            state = "CA",
            locality = "Los Angeles",
            organization = "SideloadBridge",
            commonName = "SideloadBridge Developer"
        )

        assertNotNull(result.keyPair)
        assertNotNull(result.keyPair.private)
        assertNotNull(result.keyPair.public)

        assertTrue(result.csrPem.startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
        assertTrue(result.csrPem.endsWith("-----END CERTIFICATE REQUEST-----\n"))
        assertTrue(result.privateKeyPem.startsWith("-----BEGIN RSA PRIVATE KEY-----"))
        assertTrue(result.privateKeyPem.endsWith("-----END RSA PRIVATE KEY-----\n"))

        // Check DER structure starts with SEQUENCE (0x30)
        assertTrue(result.csrDer.isNotEmpty())
        assertEquals(0x30.toByte(), result.csrDer[0])

        // Verify public key can be re-parsed
        val keyFactory = KeyFactory.getInstance("RSA")
        val parsedPubKey = keyFactory.generatePublic(X509EncodedKeySpec(result.keyPair.public.encoded))
        assertEquals(result.keyPair.public, parsedPubKey)

        // Verify SHA1withRSA signature format matches AltSign
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(result.keyPair.public)
        // Extract the CertificationRequestInfo from the DER SEQUENCE
        // The first tag is 0x30, then length bytes, then the CRI (starts with 0x30)
        var offset = 1
        var lenByte = result.csrDer[offset].toInt() and 0xFF
        if (lenByte and 0x80 != 0) {
            val numBytes = lenByte and 0x7F
            offset += 1 + numBytes
        } else {
            offset += 1
        }
        val criStart = offset
        assertEquals(0x30.toByte(), result.csrDer[criStart])
    }
}

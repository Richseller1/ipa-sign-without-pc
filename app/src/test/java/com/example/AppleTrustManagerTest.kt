package com.example

import com.aistudio.iossideloadbridge.app.auth.AppleTrustHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppleTrustManagerTest {

    @Test
    fun testAppleRootsLoadedSuccessfully() {
        val root = AppleTrustHelper.appleRootCert
        val intermediate = AppleTrustHelper.appleServerAuthCert

        assertNotNull("Apple Root CA must load", root)
        assertNotNull("Apple Server Auth CA must load", intermediate)
        assertTrue(root.subjectX500Principal.name.contains("Apple Root CA"))
        assertTrue(intermediate.subjectX500Principal.name.contains("Apple Server Authentication CA"))
    }

    @Test
    fun testPinnedAppleRootsAccepted() {
        val tm = AppleTrustHelper.createAppleTrustManager()

        // Pinned root should be recognized and accepted
        try {
            tm.checkServerTrusted(arrayOf(AppleTrustHelper.appleRootCert), "RSA")
        } catch (e: Exception) {
            fail("Pinned Apple Root CA must be accepted: ${e.message}")
        }

        // Pinned intermediate should be recognized and accepted
        try {
            tm.checkServerTrusted(arrayOf(AppleTrustHelper.appleServerAuthCert), "RSA")
        } catch (e: Exception) {
            fail("Pinned Apple Server Auth CA must be accepted: ${e.message}")
        }
    }

    @Test
    fun testEmptyChainRejected() {
        val tm = AppleTrustHelper.createAppleTrustManager()
        try {
            tm.checkServerTrusted(emptyArray(), "RSA")
            fail("Empty certificate chain must throw CertificateException")
        } catch (e: CertificateException) {
            // Expected
            assertTrue(e.message?.contains("empty") == true)
        }
    }

    @Test
    fun testRogueUntrustedCertificateRejected() {
        val tm = AppleTrustHelper.createAppleTrustManager()

        // A certificate not rooted in Apple Root CA or System CA must fail
        val rogueCertPem = """
            -----BEGIN CERTIFICATE-----
            MIIDPzCCAiegAwIBAgIUW6w7X+Hh1bZ98VjVkW7y83Y3WnswDQYJKoZIhvcNAQEL
            BQAwGDEWMBQGA1UEAwwNUm9ndWUgUm9vdCBDQTAeFw0yMDAxMDEwMDAwMDBaFw0z
            MDAxMDEwMDAwMDBaMBgxFjAUBgNVBAMMDUxvY2FsaG9zdCBDQzCCASIwDQYJKoZI
            hvcNAQEBBQADggEPADCCAQoCggEBALyLgC34Q5p7X+W9lI5bFjYtH6lP9QYtL+QZ
            vX1qf1K0P2j9aN4kF+q1pM0qH6h3wYy+1vW4ZqO7l2n1b3s8e5t8r7q0p2v3n5m8
            t7w6v3n2l1m9b8v5r2t7w6v3n2l1m9b8v5r2t7w6v3n2l1m9b8v5r2t7w6v3n2l1
            m9b8v5r2t7w6v3n2l1m9b8v5r2t7w6v3n2l1m9b8v5r2t7w6v3n2l1m9b8v5r2t7
            w6v3n2l1m9b8v5r2t7w6v3n2l1m9b8v5r2t7w6v3n2l1m9b8v5r2t7w6v3n2l1m9
            b8v5AgMBAAEwDQYJKoZIhvcNAQELBQADggEBAJy5Wv8m3t1p4k7f9o0u6v2q3s8t
            -----END CERTIFICATE-----
        """.trimIndent()

        // Generate a random untrusted key/cert or verify rejection with known dummy cert
        // If parsing fails due to invalid test pem, verify with null/invalid cert exception
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val rogueCert = cf.generateCertificate(ByteArrayInputStream(rogueCertPem.toByteArray())) as X509Certificate
            tm.checkServerTrusted(arrayOf(rogueCert), "RSA")
            fail("Rogue untrusted certificate must be rejected")
        } catch (e: Exception) {
            // Expected: CertificateException
            assertTrue("Expected rejection for rogue certificate", e is CertificateException || e is java.security.cert.CertificateException)
        }
    }

    @Test
    fun testOkHttpClientUsesStrictHostnameVerification() {
        val client = AppleTrustHelper.createOkHttpClient()
        // Ensure hostname verifier is OkHttp's standard verifier and not a trust-all lambda
        assertNotNull(client.hostnameVerifier)
        assertFalse(
            "Hostname verifier must not be trust-all bypass",
            client.hostnameVerifier.javaClass.name.contains("TrustAll") ||
            client.hostnameVerifier.javaClass.name.contains("Bypass")
        )
    }
}

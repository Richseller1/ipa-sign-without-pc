package com.example

import com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionUnitTest {

    @Test
    fun testPasswordRedacted() {
        val input = "login payload: password: SuperSecretPassword123, email: user@example.com"
        val output = AppDiagnostics.redactSecrets(input)
        assertFalse("Password must be redacted", output.contains("SuperSecretPassword123"))
        assertTrue("Mask string must be present", output.contains("password: ***") || output.contains("***"))
    }

    @Test
    fun testEmailRedacted() {
        val input = "Target account: john.appleseed@icloud.com"
        val output = AppDiagnostics.redactSecrets(input)
        assertFalse("Full email must not appear", output.contains("john.appleseed@icloud.com"))
        assertTrue("Email prefix and domain preserved with mask", output.contains("j***@icloud.com"))
    }

    @Test
    fun testAuthTokenRedacted() {
        val input = "Received token: authToken=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0"
        val output = AppDiagnostics.redactSecrets(input)
        assertFalse("Auth token must be redacted", output.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertTrue("Token placeholder present", output.contains("authToken=***"))
    }

    @Test
    fun testTwoFactorCodeRedacted() {
        val input = "User submitted code: 654321 for 2FA"
        val output = AppDiagnostics.redactSecrets(input)
        assertFalse("2FA code must be redacted", output.contains("654321"))
        assertTrue("Masked 2FA code present", output.contains("code=******"))
    }

    @Test
    fun testPrivateKeyRedacted() {
        val fakeKey = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEA0s7wV+Z6s5k...FAKEKEYDATA...
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        val input = "Generated private key:\n$fakeKey"
        val output = AppDiagnostics.redactSecrets(input)
        assertFalse("Private key data must be redacted", output.contains("FAKEKEYDATA"))
        assertTrue("Redaction tag present", output.contains("[REDACTED_PRIVATE_KEY]"))
    }
}

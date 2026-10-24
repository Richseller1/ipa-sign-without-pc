package com.example

import com.aistudio.iossideloadbridge.app.anisette.AnisetteHeaders
import com.aistudio.iossideloadbridge.app.auth.AppleAuthClient
import com.aistudio.iossideloadbridge.app.auth.ApplePlist
import com.aistudio.iossideloadbridge.app.auth.SrpCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SrpAuthUnitTest {

    @Test
    fun testSrpParameters() {
        assertEquals(2048, SrpCrypto.N.bitLength())
        assertEquals(BigInteger.valueOf(2), SrpCrypto.G)
        assertNotNull(SrpCrypto.K_MULTIPLIER)
        assertTrue(SrpCrypto.K_MULTIPLIER > BigInteger.ZERO)

        val (a, A) = SrpCrypto.generateClientEphemeral()
        assertTrue(a > BigInteger.ZERO)
        assertTrue(A > BigInteger.ZERO)
        assertTrue(A < SrpCrypto.N)

        val aPadded = SrpCrypto.toPaddedByteArray(A, 256)
        assertEquals(256, aPadded.size)
    }

    @Test
    fun testSrpMathMutualDerivation() {
        val username = "testuser@example.com"
        val password = "MySecurePassword123!"
        val salt = ByteArray(16) { it.toByte() }
        val iterations = 20000

        // 1. Password verifier generation (server side)
        val pwKey = SrpCrypto.derivePasswordKey(password, salt, iterations, "s2k_fo")
        val x = SrpCrypto.deriveX(pwKey, salt)
        assertTrue(x > BigInteger.ZERO)
        val v = SrpCrypto.G.modPow(x, SrpCrypto.N)

        // 2. Client generates ephemeral (a, A)
        val (a, A) = SrpCrypto.generateClientEphemeral()

        // 3. Server generates ephemeral (b, B = k*v + g^b mod N)
        val (b, _) = SrpCrypto.generateClientEphemeral()
        val gb = SrpCrypto.G.modPow(b, SrpCrypto.N)
        val kv = SrpCrypto.K_MULTIPLIER.multiply(v).mod(SrpCrypto.N)
        val B = kv.add(gb).mod(SrpCrypto.N)

        // 4. Client computes u, S, K, M1
        val u = SrpCrypto.computeU(A, B)
        val (clientS, clientK) = SrpCrypto.computeSessionKey(a, A, B, u, x)
        val clientM1 = SrpCrypto.computeM1(username, salt, A, B, clientK)

        // 5. Server computes u, S, K, and verifies M1
        val serverU = SrpCrypto.computeU(A, B)
        assertEquals(u, serverU)

        // Server premaster secret S = (A * v^u)^b mod N
        val vu = v.modPow(serverU, SrpCrypto.N)
        val serverBase = A.multiply(vu).mod(SrpCrypto.N)
        val serverS = serverBase.modPow(b, SrpCrypto.N)
        assertEquals(clientS, serverS)

        val serverK = SrpCrypto.sha256(SrpCrypto.toPaddedByteArray(serverS, 256))
        assertArrayEquals(clientK, serverK)

        val serverM1 = SrpCrypto.computeM1(username, salt, A, B, serverK)
        assertArrayEquals(clientM1, serverM1)

        // 6. Verify M2
        val expectedM2 = SrpCrypto.computeExpectedM2(A, clientM1, clientK)
        val serverM2 = SrpCrypto.computeExpectedM2(A, serverM1, serverK)
        assertArrayEquals(expectedM2, serverM2)
    }

    @Test
    fun testPlistSerializationAndParsing() {
        val inputDict = mapOf(
            "Header" to mapOf("Version" to "1.0.1"),
            "Request" to mapOf(
                "o" to "init",
                "u" to "developer@icloud.com",
                "i" to 20000L,
                "A2k" to ByteArray(32) { 0x42 },
                "ps" to listOf("s2k", "s2k_fo")
            )
        )

        val xml = ApplePlist.toXml(inputDict)
        assertTrue(xml.contains("<key>Version</key>"))
        assertTrue(xml.contains("<string>1.0.1</string>"))
        assertTrue(xml.contains("<key>o</key>"))
        assertTrue(xml.contains("<string>init</string>"))
        assertTrue(xml.contains("<key>A2k</key>"))
        assertTrue(xml.contains("<data>"))

        val parsed = ApplePlist.fromXml(xml)
        @Suppress("UNCHECKED_CAST")
        val header = parsed["Header"] as Map<String, Any?>
        assertEquals("1.0.1", header["Version"])

        @Suppress("UNCHECKED_CAST")
        val request = parsed["Request"] as Map<String, Any?>
        assertEquals("init", request["o"])
        assertEquals("developer@icloud.com", request["u"])
        assertEquals(20000L, request["i"])

        val a2k = request["A2k"] as ByteArray
        assertEquals(32, a2k.size)
        assertEquals(0x42.toByte(), a2k[0])

        @Suppress("UNCHECKED_CAST")
        val ps = request["ps"] as List<String>
        assertEquals(2, ps.size)
        assertEquals("s2k", ps[0])
        assertEquals("s2k_fo", ps[1])
    }

    @Test
    fun testDryRunInspectionDoesNotNetwork() {
        val client = AppleAuthClient()
        val mockAnisette = AnisetteHeaders(
            serverUsed = "https://ani.sidestore.io",
            clientTime = "2026-09-29T07:00:00Z",
            md = "AAAABQAAABB30QfOnYCfXpUazSB8gNSoAAAABA==",
            mdM = "oDdRLkOeDdIv0Tx3TXOmO9bKoEGYBn4ji9I7yzn7yFNlP1z8bHWun3IHwC25ezTyE42njUHYMXNI6HcR",
            mdRinfo = "17106176",
            mdLu = "4F7DAFD6363ABB4ED69758B25B6F6CBE60957B26E3B4BD2989B3F7060E48D77E",
            srlNo = "0",
            clientInfo = "<MacBookPro13,2> <macOS;13.1;22C65> <com.apple.AuthKit/1 (com.apple.akd/1.0)>",
            timeZone = "UTC",
            locale = "en_US",
            deviceId = "C2411E45-708C-49BE-89EC-4D1D7219991F"
        )

        val inspection = client.inspectSignInRequest("developer@example.com", mockAnisette)

        assertEquals("https://gsa.apple.com/grandslam/GsService2", inspection.endpoint)
        assertEquals("text/x-xml-plist", inspection.httpHeaders["Content-Type"])
        assertEquals("*/*", inspection.httpHeaders["Accept"])
        assertEquals("Xcode", inspection.httpHeaders["User-Agent"])
        assertEquals(mockAnisette.clientInfo, inspection.httpHeaders["X-MMe-Client-Info"])
        org.junit.Assert.assertFalse(inspection.httpHeaders.containsKey("X-Apple-I-MD"))
        assertTrue(inspection.xmlPayload.contains("<key>X-Apple-I-MD</key>"))
        assertTrue(inspection.xmlPayload.contains("<key>X-Apple-I-MD-RINFO</key>\n            <integer>17106176</integer>"))
        assertTrue(inspection.xmlPayload.contains("<key>A2k</key>"))
        assertTrue(inspection.xmlPayload.contains("<key>o</key>"))
        assertTrue(inspection.xmlPayload.contains("<string>init</string>"))
        assertTrue(inspection.xmlPayload.contains("developer@example.com"))
        assertEquals(512, inspection.srpAHex.length) // 256 bytes * 2
    }

    @Test
    fun testPlistLongAndBooleanSerialization() {
        val dict = mapOf(
            "X-Apple-I-MD-RINFO" to 17106176L,
            "boolTrue" to true,
            "boolFalse" to false
        )
        val xml = ApplePlist.toXml(dict)
        assertTrue(xml.contains("<key>X-Apple-I-MD-RINFO</key>\n    <integer>17106176</integer>"))
        assertTrue(xml.contains("<key>boolTrue</key>\n    <true/>"))
        assertTrue(xml.contains("<key>boolFalse</key>\n    <false/>"))

        val parsed = ApplePlist.fromXml(xml)
        assertEquals(17106176L, parsed["X-Apple-I-MD-RINFO"])
        assertEquals(true, parsed["boolTrue"])
        assertEquals(false, parsed["boolFalse"])
    }

    @Test
    fun testAppTokensChecksumCalculation() {
        val sk = ByteArray(32) { (it * 3).toByte() }
        val adsid = "12345678-ABCD-EF01-2345-6789ABCDEF01"
        val apps = listOf("com.apple.gs.xcode.auth")

        val checksum1 = SrpCrypto.computeAppTokensChecksum(sk, adsid, apps)
        assertNotNull(checksum1)
        assertEquals(32, checksum1.size)

        // Deterministic check
        val checksum2 = SrpCrypto.computeAppTokensChecksum(sk, adsid, apps)
        assertArrayEquals(checksum1, checksum2)

        // Different adsid produces different checksum
        val checksumDiffAdsid = SrpCrypto.computeAppTokensChecksum(sk, "other-adsid", apps)
        assertTrue(!checksum1.contentEquals(checksumDiffAdsid))

        // Different app produces different checksum
        val checksumDiffApp = SrpCrypto.computeAppTokensChecksum(sk, adsid, listOf("com.apple.gs.other.auth"))
        assertTrue(!checksum1.contentEquals(checksumDiffApp))
    }

    @Test
    fun testAesGcmEncryptAndDecryptRoundtrip() {
        val sk = ByteArray(32) { (it + 7).toByte() }
        val iv = ByteArray(16) { (it + 13).toByte() }

        val samplePlist = mapOf(
            "t" to mapOf(
                "com.apple.gs.xcode.auth" to mapOf(
                    "token" to "AQAAABBBCCCDDDEEEFFF111222333444555666777888="
                )
            )
        )
        val plaintextXml = ApplePlist.toXml(samplePlist)
        val plaintextBytes = plaintextXml.toByteArray(Charsets.UTF_8)

        val encryptedWithHeader = SrpCrypto.encryptDataGcm(sk, iv, plaintextBytes)
        // Verify header: 3 bytes "XYZ" + 16 bytes IV + ciphertext+tag
        assertEquals('X'.code.toByte(), encryptedWithHeader[0])
        assertEquals('Y'.code.toByte(), encryptedWithHeader[1])
        assertEquals('Z'.code.toByte(), encryptedWithHeader[2])
        assertTrue(encryptedWithHeader.size > 3 + 16)

        // Decrypt using decryptDataGcm
        val decryptedBytes = SrpCrypto.decryptDataGcm(sk, encryptedWithHeader)
        assertNotNull(decryptedBytes)
        assertArrayEquals(plaintextBytes, decryptedBytes)

        // Parse plist and verify token
        val decryptedXml = String(decryptedBytes!!, Charsets.UTF_8)
        val parsed = ApplePlist.fromXml(decryptedXml)
        @Suppress("UNCHECKED_CAST")
        val tDict = parsed["t"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val xcodeDict = tDict["com.apple.gs.xcode.auth"] as Map<String, Any?>
        assertEquals("AQAAABBBCCCDDDEEEFFF111222333444555666777888=", xcodeDict["token"])

        // Corrupted header fails
        val corruptedHeader = encryptedWithHeader.copyOf()
        corruptedHeader[0] = 'A'.code.toByte()
        org.junit.Assert.assertNull(SrpCrypto.decryptDataGcm(sk, corruptedHeader))

        // Corrupted tag fails (AEADBadTagException handled internally or thrown)
        val corruptedTag = encryptedWithHeader.copyOf()
        corruptedTag[corruptedTag.size - 1] = (corruptedTag[corruptedTag.size - 1] + 1).toByte()
        var tagFailed = false
        try {
            val res = SrpCrypto.decryptDataGcm(sk, corruptedTag)
            if (res == null) tagFailed = true
        } catch (_: Exception) {
            tagFailed = true
        }
        assertTrue("Corrupted ciphertext/tag must fail decryption", tagFailed)
    }

    @Test
    fun testSpdDecryptionRoundtrip() {
        val K = ByteArray(32) { (it * 5).toByte() }

        val extraDataKey = SrpCrypto.hmacSha256(K, "extra data key:".toByteArray(Charsets.UTF_8))
        val extraDataIv = SrpCrypto.hmacSha256(K, "extra data iv:".toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)

        val sampleSpdPlist = mapOf(
            "adsid" to "SAMPLE-ADSID-12345",
            "GsIdmsToken" to "SAMPLE-IDMS-TOKEN-ABCDE",
            "c" to "SAMPLE-COOKIE",
            "sk" to ByteArray(32) { 0x55 }
        )
        val plaintextXml = ApplePlist.toXml(sampleSpdPlist)
        val plaintextBytes = plaintextXml.toByteArray(Charsets.UTF_8)

        // Encrypt with AES-256-CBC
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(extraDataKey, "AES"),
            javax.crypto.spec.IvParameterSpec(extraDataIv)
        )
        val encryptedSpd = cipher.doFinal(plaintextBytes)

        // Decrypt with decryptSpd
        val decryptedSpd = SrpCrypto.decryptSpd(K, encryptedSpd)
        assertArrayEquals(plaintextBytes, decryptedSpd)

        val parsed = ApplePlist.fromXml(String(decryptedSpd, Charsets.UTF_8))
        assertEquals("SAMPLE-ADSID-12345", parsed["adsid"])
        assertEquals("SAMPLE-IDMS-TOKEN-ABCDE", parsed["GsIdmsToken"])
        assertEquals("SAMPLE-COOKIE", parsed["c"])
    }

    @Test
    fun testParserWithResponseWrapper() {
        // (a) A reply with a Response wrapper (e.g. standard GrandSlam SRP complete/init)
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Response</key>
                <dict>
                    <key>Status</key>
                    <dict>
                        <key>ec</key>
                        <integer>0</integer>
                        <key>em</key>
                        <string></string>
                    </dict>
                    <key>et</key>
                    <string>SAMPLE_ENCRYPTED_TOKEN_WRAPPED</string>
                </dict>
            </dict>
            </plist>
        """.trimIndent()

        val parsed = ApplePlist.fromXml(xml)
        val status = com.aistudio.iossideloadbridge.app.auth.AppleResponseHelper.parseStatus(parsed, 200)

        assertTrue("HTTP 200 with ec=0 inside Response wrapper must be marked success", status.isSuccess)
        assertEquals(0, status.errorCode)
        assertTrue(status.hasStatus)
        assertEquals("SAMPLE_ENCRYPTED_TOKEN_WRAPPED", status.activeDict["et"])
    }

    @Test
    fun testParserWithoutResponseWrapper() {
        // (b) A reply without a Response wrapper (e.g. validate or apptokens without Response)
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Status</key>
                <dict>
                    <key>ec</key>
                    <integer>0</integer>
                </dict>
                <key>et</key>
                <string>SAMPLE_ENCRYPTED_TOKEN_UNWRAPPED</string>
            </dict>
            </plist>
        """.trimIndent()

        val parsed = ApplePlist.fromXml(xml)
        val status = com.aistudio.iossideloadbridge.app.auth.AppleResponseHelper.parseStatus(parsed, 200)

        assertTrue("HTTP 200 with ec=0 without Response wrapper must be marked success", status.isSuccess)
        assertEquals(0, status.errorCode)
        assertTrue(status.hasStatus)
        assertEquals("SAMPLE_ENCRYPTED_TOKEN_UNWRAPPED", status.activeDict["et"])
    }

    @Test
    fun testParserWithoutStatusDictAtAll() {
        // Successful validate/apptokens that has no Status dictionary at all
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
                <key>et</key>
                <string>SAMPLE_ENCRYPTED_TOKEN_NO_STATUS</string>
            </dict>
            </plist>
        """.trimIndent()

        val parsed = ApplePlist.fromXml(xml)
        val status = com.aistudio.iossideloadbridge.app.auth.AppleResponseHelper.parseStatus(parsed, 200)

        assertTrue("HTTP 200 with absent Status/ec must be marked success", status.isSuccess)
        org.junit.Assert.assertNull(status.errorCode)
        assertEquals("SAMPLE_ENCRYPTED_TOKEN_NO_STATUS", status.activeDict["et"])
    }

    @Test
    fun testParserWithNonzeroEc() {
        // (c) A reply with a nonzero ec (e.g. -20101 bad credentials or -21669 2FA required)
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Response</key>
                <dict>
                    <key>Status</key>
                    <dict>
                        <key>ec</key>
                        <integer>-20101</integer>
                        <key>em</key>
                        <string>Your Apple ID or password was incorrect.</string>
                    </dict>
                </dict>
            </dict>
            </plist>
        """.trimIndent()

        val parsed = ApplePlist.fromXml(xml)
        val status = com.aistudio.iossideloadbridge.app.auth.AppleResponseHelper.parseStatus(parsed, 200)

        org.junit.Assert.assertFalse("Non-zero ec must be marked failure", status.isSuccess)
        assertEquals(-20101, status.errorCode)
        assertEquals("Your Apple ID or password was incorrect.", status.errorMessage)

        val formattedError = com.aistudio.iossideloadbridge.app.auth.AppleResponseHelper.formatErrorMessage("apptokens", status)
        assertTrue(formattedError.contains("ec=-20101"))
        assertTrue(formattedError.contains("Your Apple ID or password was incorrect."))
    }

    @Test
    fun testParserNoStatusFormattingTask4() {
        // Task 4: Never show "ec=null, em=null". Say "no Status in response, keys=[...]"
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
                <key>unknownKeyA</key>
                <string>valA</string>
                <key>unknownKeyB</key>
                <string>valB</string>
            </dict>
            </plist>
        """.trimIndent()

        val parsed = ApplePlist.fromXml(xml)
        val status = com.aistudio.iossideloadbridge.app.auth.AppleResponseHelper.parseStatus(parsed, 500)

        org.junit.Assert.assertFalse(status.isSuccess)
        val formattedError = com.aistudio.iossideloadbridge.app.auth.AppleResponseHelper.formatErrorMessage("apptokens", status)
        assertEquals("[apptokens] HTTP 500: no Status in response, keys=[unknownKeyA, unknownKeyB]", formattedError)
    }

    @Test
    fun testStatusAuExtraction() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
                <key>Response</key>
                <dict>
                    <key>Status</key>
                    <dict>
                        <key>au</key>
                        <string>trustedDeviceSecondaryAuth</string>
                        <key>ec</key>
                        <integer>0</integer>
                    </dict>
                </dict>
            </dict>
            </plist>
        """.trimIndent()

        val parsed = ApplePlist.fromXml(xml)
        val status = com.aistudio.iossideloadbridge.app.auth.AppleResponseHelper.parseStatus(parsed, 200)

        assertTrue(status.isSuccess)
        assertNotNull(status.statusDict)
        assertEquals("trustedDeviceSecondaryAuth", status.statusDict?.get("au"))
    }
}

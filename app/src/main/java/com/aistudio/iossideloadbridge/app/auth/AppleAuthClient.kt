package com.aistudio.iossideloadbridge.app.auth

import android.util.Log
import com.aistudio.iossideloadbridge.app.anisette.AnisetteClient
import com.aistudio.iossideloadbridge.app.anisette.AnisetteHeaders
import com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import java.util.Base64 as JavaBase64

private fun base64Encode(bytes: ByteArray): String {
    return try {
        JavaBase64.getEncoder().encodeToString(bytes)
    } catch (_: Throwable) {
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
}

/**
 * Encapsulates the context required to resume an authentication attempt across the 2FA challenge.
 */
data class SignInContext(
    val appleId: String,
    val password: String = "",
    val cookie: String,
    val srpA: BigInteger,
    val srpB: BigInteger,
    val srpK: ByteArray,
    val srpM1: ByteArray,
    val anisetteHeaders: AnisetteHeaders,
    val adsid: String = "",
    val idmsToken: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SignInContext
        return appleId == other.appleId && cookie == other.cookie && adsid == other.adsid
    }

    override fun hashCode(): Int {
        var result = appleId.hashCode()
        result = 31 * result + cookie.hashCode()
        result = 31 * result + adsid.hashCode()
        return result
    }

    override fun toString(): String {
        return "SignInContext(appleId='$appleId', hasPassword=${password.isNotEmpty()}, hasCookie=${cookie.isNotEmpty()}, hasSrpK=${srpK.isNotEmpty()}, hasAdsid=${adsid.isNotEmpty()}, hasIdmsToken=${idmsToken.isNotEmpty()})"
    }
}

sealed class SignInState {
    data class Success(val session: AppleAuthSession) : SignInState()
    data class NeedsTwoFactor(
        val context: SignInContext,
        val promptMessage: String,
        val phoneNumberHint: String? = null
    ) : SignInState()
    data class Failure(
        val errorCode: String,
        val errorMessage: String,
        val rawResponse: String? = null
    ) : SignInState()
}

data class DryRunInspection(
    val endpoint: String,
    val httpHeaders: Map<String, String>,
    val xmlPayload: String,
    val srpAHex: String
)

/**
 * Real Apple GrandSlam Authentication (GSA) client implementing SRP-6a and 2FA code submission.
 *
 * References:
 * - rileytestut/AltSign: ALTAppleAPI+Authentication.m
 * - SideStore/RustSign: src/request.rs, rustcrypto/srp
 */
class AppleAuthClient(
    private val timeoutSeconds: Long = 15L,
    private val anisetteClient: AnisetteClient = AnisetteClient()
) {
    companion object {
        private const val TAG = "AppleAuthClient"
        const val GSA_ENDPOINT = "https://gsa.apple.com/grandslam/GsService2"
        const val GSA_VALIDATE_ENDPOINT = "https://gsa.apple.com/grandslam/GsService2/validate"
        const val GSA_TRUSTED_DEVICE_ENDPOINT = "https://gsa.apple.com/auth/verify/trusteddevice"
        const val USER_AGENT = "akd/1.0 CFNetwork/978.0.7 Darwin/18.7.0"
        private val PLIST_MEDIA_TYPE = "text/x-xml-plist; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient = AppleTrustHelper.createOkHttpClient(timeoutSeconds)

    private fun buildCpdMap(anisetteHeaders: AnisetteHeaders): Map<String, Any?> = buildMap {
        put("bootstrap", true)
        put("icscrec", true)
        put("pbe", false)
        put("prkgen", true)
        put("svct", "iCloud")
        put("loc", anisetteHeaders.locale.ifEmpty { java.util.Locale.getDefault().toString() })
        putAll(anisetteHeaders.toCpdMap())
    }

    /**
     * Safe dry-run inspection: builds and formats the exact initial SRP authentication request
     * WITHOUT sending any network traffic to Apple's servers.
     */
    fun inspectSignInRequest(appleId: String, anisetteHeaders: AnisetteHeaders): DryRunInspection {
        val normalizedAppleId = appleId.trim().lowercase(java.util.Locale.US)
        val (_, A) = SrpCrypto.generateClientEphemeral()
        val aBytes = SrpCrypto.toPaddedByteArray(A, SrpCrypto.KEY_SIZE_BYTES)

        val cpdMap = buildCpdMap(anisetteHeaders)

        val requestDict = mapOf(
            "A2k" to aBytes,
            "o" to "init",
            "ps" to listOf("s2k", "s2k_fo"),
            "u" to normalizedAppleId,
            "cpd" to cpdMap
        )

        val rootPlist = mapOf(
            "Header" to mapOf("Version" to "1.0.1"),
            "Request" to requestDict
        )

        val xmlPayload = ApplePlist.toXml(rootPlist)

        val (effectiveClientInfo, effectiveUserAgent, _) =
            AppDiagnostics.resolveDeviceDescription(anisetteHeaders.clientInfo, USER_AGENT)

        val httpHeaders = mapOf(
            "Content-Type" to "text/x-xml-plist",
            "Accept" to "*/*",
            "User-Agent" to effectiveUserAgent,
            "X-MMe-Client-Info" to effectiveClientInfo
        )

        return DryRunInspection(
            endpoint = GSA_ENDPOINT,
            httpHeaders = httpHeaders,
            xmlPayload = xmlPayload,
            srpAHex = SrpCrypto.bytesToHex(aBytes)
        )
    }

    /**
     * Executes the full SRP-6a authentication handshake with Apple's GSA servers.
     */
    suspend fun signIn(
        appleId: String,
        password: String,
        anisetteHeaders: AnisetteHeaders
    ): Result<SignInState> = withContext(Dispatchers.IO) {
        try {
            val normalizedAppleId = appleId.trim().lowercase(java.util.Locale.US)
            val (effectiveClientInfo, effectiveUserAgent, profileName) =
                AppDiagnostics.resolveDeviceDescription(anisetteHeaders.clientInfo, USER_AGENT)
            val anisetteHost = AnisetteClient.extractHostName(anisetteHeaders.serverUsed)

            AppDiagnostics.logAuthTrace(AppDiagnostics.redactSecrets("Starting GSA SRP-6a login (step: init)..."))

            // ==========================================
            // Step 1: Initial SRP Request (o: "init")
            // ==========================================
            val (a, A) = SrpCrypto.generateClientEphemeral()
            val aBytes = SrpCrypto.toPaddedByteArray(A, SrpCrypto.KEY_SIZE_BYTES)
            val cpdMap = buildCpdMap(anisetteHeaders)

            val cpdDescriptor = cpdMap.entries.sortedBy { it.key }.joinToString(", ") { (k, v) ->
                val typeName = when (v) {
                    is String -> "string"
                    is Long, is Int, is Short, is Byte -> "integer"
                    is Boolean -> "boolean"
                    is ByteArray -> "data"
                    is Map<*, *> -> "dict"
                    is List<*> -> "array"
                    null -> "null"
                    else -> v.javaClass.simpleName
                }
                "$k: $typeName"
            }
            AppDiagnostics.logAuthTrace("cpd keys & plist types: [$cpdDescriptor]")

            val initPayload = ApplePlist.toXml(
                mapOf(
                    "Header" to mapOf("Version" to "1.0.1"),
                    "Request" to mapOf(
                        "A2k" to aBytes,
                        "o" to "init",
                        "ps" to listOf("s2k", "s2k_fo"),
                        "u" to normalizedAppleId,
                        "cpd" to cpdMap
                    )
                )
            )

            val initHttpRequest = buildGsaRequest(
                url = GSA_ENDPOINT,
                xmlPayload = initPayload,
                clientInfo = effectiveClientInfo,
                userAgent = effectiveUserAgent
            )

            if (AppDiagnostics.advancedDiagnosticsEnabled) {
                AppDiagnostics.logAdvancedAuthTrace(
                    "init device profile: profile=$profileName, anisetteHost=$anisetteHost, User-Agent=\"$effectiveUserAgent\", X-MMe-Client-Info=\"$effectiveClientInfo\""
                )
                val allHeaderNames = initHttpRequest.headers.names().toList()
                val safeHeadersSummary = allHeaderNames.joinToString(", ") { name ->
                    if (name.equals("Content-Type", ignoreCase = true) ||
                        name.equals("Accept", ignoreCase = true) ||
                        name.equals("User-Agent", ignoreCase = true)
                    ) {
                        "$name=\"${initHttpRequest.header(name)}\""
                    } else {
                        "$name=(name-only)"
                    }
                }
                AppDiagnostics.logAdvancedAuthTrace(
                    "init outgoing HTTP: url=${initHttpRequest.url}, method=${initHttpRequest.method}, headerNames=$allHeaderNames, headers=[$safeHeadersSummary]"
                )

                val parsedOutgoingInit = ApplePlist.fromXml(initPayload)
                fun plistTypeOf(v: Any?): String = when (v) {
                    is String -> "string"
                    is Long, is Int, is Short, is Byte -> "integer"
                    is Boolean -> "boolean"
                    is ByteArray -> "data"
                    is Map<*, *> -> "dict"
                    is List<*> -> "array"
                    null -> "null"
                    else -> v.javaClass.simpleName
                }
                val rootOuterTypes = parsedOutgoingInit.entries.sortedBy { it.key }.joinToString(", ") { (k, v) -> "$k: ${plistTypeOf(v)}" }
                @Suppress("UNCHECKED_CAST")
                val reqDict = parsedOutgoingInit["Request"] as? Map<String, Any?> ?: emptyMap()
                val reqOuterTypes = reqDict.entries.sortedBy { it.key }.joinToString(", ") { (k, v) -> "$k: ${plistTypeOf(v)}" }
                AppDiagnostics.logAdvancedAuthTrace(
                    "init outgoing outer plist keys & types: root=[$rootOuterTypes], Request=[$reqOuterTypes]"
                )

                val ct = anisetteHeaders.clientTime
                val ctState = if (ct.isEmpty()) "empty" else "non-empty"
                val ctMask = ct.replace(Regex("\\d"), "N")
                val ctFormat = when {
                    ct.isEmpty() -> "empty"
                    Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$").matches(ct) -> "ISO8601-UTC-sec ($ctMask)"
                    Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z$").matches(ct) -> "ISO8601-UTC-frac ($ctMask)"
                    Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}$").matches(ct) -> "ISO8601-offset ($ctMask)"
                    else -> "pattern($ctMask)"
                }
                fun fieldSummary(v: String): String = "${if (v.isEmpty()) "empty" else "non-empty"} (len=${v.length})"
                val anisetteFieldsSummary = listOf(
                    "X-Apple-I-Client-Time=$ctState (len=${ct.length}, format=$ctFormat)",
                    "X-Apple-I-MD=${fieldSummary(anisetteHeaders.md)}",
                    "X-Apple-I-MD-M=${fieldSummary(anisetteHeaders.mdM)}",
                    "X-Apple-I-MD-RINFO=${fieldSummary(anisetteHeaders.mdRinfo)}",
                    "X-Apple-I-MD-LU=${fieldSummary(anisetteHeaders.mdLu)}",
                    "X-Apple-I-SRL-NO=${fieldSummary(anisetteHeaders.srlNo)}",
                    "X-MMe-Client-Info=${fieldSummary(effectiveClientInfo)}",
                    "X-Apple-I-TimeZone=${fieldSummary(anisetteHeaders.timeZone)}",
                    "X-Apple-Locale=${fieldSummary(anisetteHeaders.locale)}",
                    "X-Mme-Device-Id=${fieldSummary(anisetteHeaders.deviceId)}"
                ).joinToString(", ")
                AppDiagnostics.logAdvancedAuthTrace(
                    "init outgoing anisette fields: [$anisetteFieldsSummary]"
                )

                val a2kFirstIsZero = aBytes.isNotEmpty() && aBytes[0] == 0.toByte()
                AppDiagnostics.logAdvancedAuthTrace(
                    "init outgoing A2k: byteLength=${aBytes.size}, firstByteIs0x00=$a2kFirstIsZero"
                )
            }

            AppDiagnostics.logAuthTrace("init request sent")
            val initHttpResponse = executeHttpRequest(initHttpRequest)
            AppDiagnostics.logAuthTrace("init response received: HTTP ${initHttpResponse.code}")

            val trimmedInitBody = initHttpResponse.body.trimStart()
            val looksLikePlist = trimmedInitBody.contains("<plist", ignoreCase = true) ||
                trimmedInitBody.contains("<dict", ignoreCase = true)
            val initParsed: Map<String, Any?> = if (looksLikePlist) {
                try {
                    ApplePlist.fromXml(initHttpResponse.body)
                } catch (_: Throwable) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }
            val isNonPlistInit = !looksLikePlist || initParsed.isEmpty()
            if (isNonPlistInit) {
                val first80 = AppDiagnostics.redactSecrets(
                    initHttpResponse.body.replace(Regex("\\s+"), " ").trim().take(80)
                )
                AppDiagnostics.logAuthTrace(
                    "non-plist response: HTTP ${initHttpResponse.code}, first80=\"$first80\""
                )
            }

            val initStatus = AppleResponseHelper.parseStatus(initParsed, initHttpResponse.code)
            if (AppDiagnostics.advancedDiagnosticsEnabled) {
                val topLevelResponseKeys = initParsed.keys.sorted()
                @Suppress("UNCHECKED_CAST")
                val innerResponseKeys = (initParsed["Response"] as? Map<String, Any?>)?.keys?.sorted() ?: emptyList()
                val ecResultStr = if (isNonPlistInit) "non-plist" else (initStatus.rawErrorCodeStr ?: "0")
                AppDiagnostics.logAdvancedAuthTrace(
                    "init response summary: profile=$profileName, anisetteHost=$anisetteHost, HTTP ${initHttpResponse.code}, ec=$ecResultStr, topLevelPlistKeys=$topLevelResponseKeys, responseDictKeys=$innerResponseKeys"
                )
            }
            if (isNonPlistInit || !initStatus.isSuccess) {
                return@withContext parseGsaError(
                    parsed = initParsed,
                    rawXml = initHttpResponse.body,
                    httpCode = initHttpResponse.code,
                    stepName = "init",
                    isNonPlist = isNonPlistInit
                )
            }
            val activeInitDict = initStatus.activeDict

            // Extract server SRP parameters
            val spProtocol = (activeInitDict["sp"] ?: "s2k_fo").toString()
            val salt = activeInitDict["s"] as? ByteArray
                ?: return@withContext Result.success(
                    SignInState.Failure("ProtocolError", "Missing salt 's' in GSA init response (keys=${initStatus.activeKeys})", initHttpResponse.body)
                )
            val bBytes = activeInitDict["B"] as? ByteArray
                ?: return@withContext Result.success(
                    SignInState.Failure("ProtocolError", "Missing server public key 'B' in GSA init response (keys=${initStatus.activeKeys})", initHttpResponse.body)
                )
            val cookie = (activeInitDict["c"] ?: "").toString()
            val iterations = (activeInitDict["i"] as? Long)?.toInt() ?: 20000

            val B = BigInteger(1, bBytes)
            AppDiagnostics.logAuthTrace("init ok: HTTP ${initHttpResponse.code}, protocol=$spProtocol, salt=${salt.size}B, cookie=${cookie.isNotEmpty()}")

            // ==========================================
            // Step 2: Compute SRP-6a Cryptographic Proofs
            // ==========================================
            val passwordKey = SrpCrypto.derivePasswordKey(password, salt, iterations, spProtocol)
            val x = SrpCrypto.deriveX(passwordKey, salt)
            val u = SrpCrypto.computeU(A, B)
            val (_, K) = SrpCrypto.computeSessionKey(a, A, B, u, x)
            val M1 = SrpCrypto.computeM1(normalizedAppleId, salt, A, B, K)
            val expectedM2 = SrpCrypto.computeExpectedM2(A, M1, K)
            AppDiagnostics.logAuthTrace("SRP proofs computed (M1 computed)")

            // ==========================================
            // Step 3: Complete Request (o: "complete")
            // ==========================================
            val completePayload = ApplePlist.toXml(
                mapOf(
                    "Header" to mapOf("Version" to "1.0.1"),
                    "Request" to mapOf(
                        "c" to cookie,
                        "M1" to M1,
                        "o" to "complete",
                        "u" to normalizedAppleId,
                        "cpd" to cpdMap
                    )
                )
            )

            val completeHttpRequest = buildGsaRequest(
                url = GSA_ENDPOINT,
                xmlPayload = completePayload,
                clientInfo = effectiveClientInfo,
                userAgent = effectiveUserAgent
            )
            AppDiagnostics.logAuthTrace("complete request sent")
            val completeHttpResponse = executeHttpRequest(completeHttpRequest)

            val completeParsed = ApplePlist.fromXml(completeHttpResponse.body)
            val completeStatus = AppleResponseHelper.parseStatus(completeParsed, completeHttpResponse.code)
            val activeResponseDict = completeStatus.activeDict

            val statusAu = completeStatus.statusDict?.get("au")?.toString()
            val activeAu = activeResponseDict["au"]?.toString()
            val (authType, auLocation) = when {
                !statusAu.isNullOrEmpty() -> Pair(statusAu, "Status")
                !activeAu.isNullOrEmpty() -> Pair(activeAu, "Response")
                else -> Pair("", "not found")
            }
            AppDiagnostics.logAuthTrace("au location: $auLocation, value=$authType")
            val statusCode = completeStatus.errorCode ?: 0

            AppDiagnostics.logAuthTrace("complete response received: HTTP ${completeHttpResponse.code}, au=$authType, ec=$statusCode")

            // Check if Two-Factor Authentication is required
            val is2FaRequired = authType == "trustedDeviceSecondaryAuth" ||
                statusCode == -21669 ||
                activeResponseDict.containsKey("trustedDevices") ||
                activeResponseDict.containsKey("trustedPhoneNumbers")

            AppDiagnostics.logAuthTrace("2FA required: $is2FaRequired")

            if (!completeStatus.isSuccess && !is2FaRequired) {
                AppDiagnostics.logAuthTrace("complete error: HTTP ${completeHttpResponse.code}")
                return@withContext parseGsaError(completeParsed, completeHttpResponse.body, completeHttpResponse.code, "complete")
            }
            AppDiagnostics.logAuthTrace("complete ok: HTTP ${completeHttpResponse.code}")

            // Verify server proof M2 if provided
            val serverM2 = (activeResponseDict["M2"] ?: completeParsed["M2"]) as? ByteArray
            if (serverM2 != null) {
                if (!serverM2.contentEquals(expectedM2)) {
                    AppDiagnostics.logAuthTrace("M2 mismatch! Server verification proof failed.")
                    return@withContext Result.success(
                        SignInState.Failure("M2Mismatch", "Server verification proof (M2) mismatch. Authentication aborted.")
                    )
                }
                AppDiagnostics.logAuthTrace("M2 ok: server proof verified")
            } else {
                AppDiagnostics.logAuthTrace("M2 ok: server proof omitted by Apple")
            }

            if (is2FaRequired) {
                var preAdsid = ""
                var preIdmsToken = ""
                val preSpdRaw = activeResponseDict["spd"] ?: completeParsed["spd"]
                val preSpdBytes: ByteArray? = when (preSpdRaw) {
                    is ByteArray -> preSpdRaw
                    is String -> try { JavaBase64.getDecoder().decode(preSpdRaw.replace("\\s+".toRegex(), "")) } catch (_: Exception) { null }
                    else -> null
                }
                if (preSpdBytes != null) {
                    try {
                        val decryptedPre = SrpCrypto.decryptSpd(K, preSpdBytes)
                        val preDict = ApplePlist.fromXml(String(decryptedPre, Charsets.UTF_8))
                        preAdsid = (preDict["adsid"] ?: "").toString()
                        preIdmsToken = (preDict["GsIdmsToken"] ?: "").toString()
                        AppDiagnostics.logAuthTrace("Pre-2FA spd decrypted: adsid present=${preAdsid.isNotEmpty()}, idmsToken present=${preIdmsToken.isNotEmpty()}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decrypt spd before 2FA: ${e.message}")
                        AppDiagnostics.logAuthTrace("Pre-2FA spd decryption failed: ${e.message}")
                    }
                } else {
                    AppDiagnostics.logAuthTrace("Pre-2FA spd absent in response")
                }

                // X-Apple-Identity-Token is built only from adsid + GsIdmsToken that came from the decrypted spd
                val respCookie = (activeResponseDict["c"] ?: completeParsed["c"] ?: cookie).toString()

                AppDiagnostics.logAuthTrace("Pre-2FA identity: adsid present=${preAdsid.isNotEmpty()}, idmsToken present=${preIdmsToken.isNotEmpty()}")

                val context = SignInContext(
                    appleId = normalizedAppleId,
                    password = password,
                    cookie = respCookie,
                    srpA = A,
                    srpB = B,
                    srpK = K,
                    srpM1 = M1,
                    anisetteHeaders = anisetteHeaders,
                    adsid = preAdsid,
                    idmsToken = preIdmsToken
                )

                // Requirement 2: Re-use the SAME anisetteHeaders passed into signIn
                val triggerResult = triggerTwoFactorVerification(context)
                if (triggerResult.isFailure) {
                    val triggerErr = triggerResult.exceptionOrNull()?.message
                        ?: "Failed to send 2FA verification code to trusted device"
                    AppDiagnostics.logAuthTrace("trusteddevice trigger error: $triggerErr")
                    return@withContext Result.success(
                        SignInState.Failure("TrustedDeviceTriggerFailed", triggerErr)
                    )
                }

                val prompt = "Two-factor authentication required. Enter the 6-digit verification code sent to your trusted Apple devices."
                AppDiagnostics.logAuthTrace("2FA challenge received (au=$authType, ec=$statusCode)")
                return@withContext Result.success(SignInState.NeedsTwoFactor(context, prompt))
            }

            // ========================================================
            // Step A: Decrypt spd (Server Provided Data) with SRP key K
            // ========================================================
            val spdRaw = activeResponseDict["spd"] ?: completeParsed["spd"]
            if (spdRaw == null) {
                AppDiagnostics.logAuthTrace("spd present: false (absent in complete response)")
                return@withContext Result.success(
                    SignInState.Failure("MissingSpd", "Authentication complete response missing 'spd' payload.", completeHttpResponse.body)
                )
            }

            val spdBytes: ByteArray = when (spdRaw) {
                is ByteArray -> spdRaw
                is String -> try {
                    JavaBase64.getDecoder().decode(spdRaw.replace("\\s+".toRegex(), ""))
                } catch (e: Exception) {
                    AppDiagnostics.logAuthTrace("spd base64 decode failed: ${e.message}")
                    return@withContext Result.success(SignInState.Failure("SpdDecodeError", "Failed to decode base64 'spd': ${e.message}"))
                }
                else -> return@withContext Result.success(SignInState.Failure("SpdFormatError", "Invalid 'spd' data format."))
            }
            AppDiagnostics.logAuthTrace("spd present: true (len=${spdBytes.size}B)")

            val decryptedSpd: ByteArray = try {
                SrpCrypto.decryptSpd(K, spdBytes)
            } catch (e: Exception) {
                AppDiagnostics.logAuthTrace("spd decrypted: failed with key K: ${e.message}")
                return@withContext Result.success(SignInState.Failure("SpdDecryptFailed", "Failed to decrypt 'spd' with session key K: ${e.message}"))
            }
            AppDiagnostics.logAuthTrace("spd decrypted ok (${decryptedSpd.size} bytes)")

            val spdDict = ApplePlist.fromXml(String(decryptedSpd, Charsets.UTF_8))
            val adsid = (spdDict["adsid"] ?: "").toString()
            val idmsToken = (spdDict["GsIdmsToken"] ?: "").toString()
            val spdCRaw = spdDict["c"]
            if (spdCRaw !is ByteArray) {
                val typeName = spdCRaw?.javaClass?.name ?: "null"
                val err = "spd cookie c missing or not data, type=$typeName"
                AppDiagnostics.logAuthTrace(err)
                return@withContext Result.success(SignInState.Failure("InvalidSpdCookie", err))
            }
            val spdCookieBytes: ByteArray = spdCRaw
            val skRaw = spdDict["sk"]
            val skBytes: ByteArray? = when (skRaw) {
                is ByteArray -> skRaw
                is String -> try { JavaBase64.getDecoder().decode(skRaw.trim()) } catch (_: Exception) { null }
                else -> null
            }

            AppDiagnostics.logAuthTrace("adsid present: ${adsid.isNotEmpty()} (len=${adsid.length})")
            AppDiagnostics.logAuthTrace("GsIdmsToken present: ${idmsToken.isNotEmpty()} (len=${idmsToken.length})")
            AppDiagnostics.logAuthTrace("sk present: ${skBytes != null} (len=${skBytes?.size ?: 0}B)")

            if (adsid.isEmpty()) {
                AppDiagnostics.logAuthTrace("adsid missing in decrypted spd")
                return@withContext Result.success(SignInState.Failure("MissingAdsid", "Decrypted spd missing 'adsid'."))
            }
            if (idmsToken.isEmpty()) {
                AppDiagnostics.logAuthTrace("GsIdmsToken missing in decrypted spd")
                return@withContext Result.success(SignInState.Failure("MissingIdmsToken", "Decrypted spd missing 'GsIdmsToken'."))
            }
            if (skBytes == null || skBytes.isEmpty()) {
                AppDiagnostics.logAuthTrace("sk session key missing in decrypted spd")
                return@withContext Result.success(SignInState.Failure("MissingSk", "Decrypted spd missing session key 'sk'."))
            }

            // ========================================================
            // Step B: Send apptokens request to GsService2
            // ========================================================
            val checksum = SrpCrypto.computeAppTokensChecksum(skBytes, adsid, listOf("com.apple.gs.xcode.auth"))
            val cpdTypesLog = cpdMap.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}: ${it.value?.javaClass?.simpleName ?: "null"}" }
            AppDiagnostics.logAuthTrace("spd['c'] type=${spdCRaw.javaClass.simpleName}, length=${spdCookieBytes.size}B")
            AppDiagnostics.logAuthTrace("cpd keys: $cpdTypesLog")

            val apptokensPayload = ApplePlist.toXml(
                mapOf(
                    "Header" to mapOf("Version" to "1.0.1"),
                    "Request" to mapOf(
                        "o" to "apptokens",
                        "u" to adsid,
                        "app" to listOf("com.apple.gs.xcode.auth"),
                        "c" to spdCookieBytes,
                        "t" to idmsToken,
                        "cpd" to cpdMap,
                        "checksum" to checksum
                    )
                )
            )

            val apptokensHttpRequest = buildGsaRequest(
                url = GSA_ENDPOINT,
                xmlPayload = apptokensPayload,
                clientInfo = effectiveClientInfo,
                userAgent = effectiveUserAgent
            )
            val apptokensHttpResponse = executeHttpRequest(apptokensHttpRequest)
            AppDiagnostics.logAuthTrace("apptokens HTTP status: ${apptokensHttpResponse.code} ${apptokensHttpResponse.message}")

            val apptokensParsed = ApplePlist.fromXml(apptokensHttpResponse.body)
            val apptokensStatus = AppleResponseHelper.parseStatus(apptokensParsed, apptokensHttpResponse.code)

            val activeApptokensDict = apptokensStatus.activeDict
            @Suppress("UNCHECKED_CAST")
            val responseDictKeys = (apptokensParsed["Response"] as? Map<String, Any?>)?.keys?.sorted()
            @Suppress("UNCHECKED_CAST")
            val tDictKeys = (apptokensParsed["t"] as? Map<String, Any?>)?.keys?.sorted()

            val etRaw = activeApptokensDict["et"] ?: apptokensParsed["et"]
            val etLen = when (etRaw) {
                is ByteArray -> etRaw.size
                is String -> try { JavaBase64.getDecoder().decode(etRaw.replace("\\s+".toRegex(), "")).size } catch (_: Exception) { etRaw.length }
                else -> 0
            }
            val etPresentStr = if (etRaw != null) "yes ($etLen)" else "no"

            val responseKeysLog = if (responseDictKeys != null) ", responseKeys=$responseDictKeys" else ""
            val tKeysLog = if (tDictKeys != null) ", tKeys=$tDictKeys" else ""
            AppDiagnostics.logAuthTrace("apptokens response: HTTP ${apptokensHttpResponse.code}, length=${apptokensHttpResponse.body.length}, keys=${apptokensStatus.topLevelKeys}$responseKeysLog$tKeysLog, et present: $etPresentStr")

            // Check if Apple returned an explicit error status or non-2xx HTTP code
            if (!apptokensStatus.isSuccess) {
                val classified = AppleResponseHelper.classifyError("apptokens", apptokensStatus)
                val errDesc = classified.toCombinedDisplayString()
                AppDiagnostics.logAuthTrace("apptokens error: $errDesc")
                return@withContext Result.success(
                    SignInState.Failure(apptokensStatus.rawErrorCodeStr ?: "AuthError", errDesc, apptokensHttpResponse.body)
                )
            }

            // ========================================================
            // Step C: Decrypt 'et' with AES-GCM and session key sk
            // ========================================================
            if (etRaw == null) {
                AppDiagnostics.logAuthTrace("et present: false (absent in apptokens response, keys=${apptokensStatus.topLevelKeys})")
                val noEtDesc = "[apptokens] HTTP ${apptokensHttpResponse.code}: missing 'et' field in response, keys=${apptokensStatus.topLevelKeys}"
                return@withContext Result.success(
                    SignInState.Failure("MissingEt", noEtDesc, apptokensHttpResponse.body)
                )
            }

            val etBytes: ByteArray = when (etRaw) {
                is ByteArray -> etRaw
                is String -> try {
                    JavaBase64.getDecoder().decode(etRaw.replace("\\s+".toRegex(), ""))
                } catch (e: Exception) {
                    val reason = "base64 decode failed (${e.message})"
                    AppDiagnostics.logAuthTrace("et decryption failed: reason=$reason")
                    return@withContext Result.success(SignInState.Failure("EtDecodeFailed", "[apptokens] $reason"))
                }
                else -> {
                    val reason = "invalid 'et' data format (${etRaw.javaClass.simpleName})"
                    AppDiagnostics.logAuthTrace("et decryption failed: reason=$reason")
                    return@withContext Result.success(SignInState.Failure("EtFormatError", "[apptokens] $reason"))
                }
            }

            if (etBytes.size < 35) {
                val reason = "size (length=${etBytes.size} < 35)"
                AppDiagnostics.logAuthTrace("et decryption failed: reason=$reason")
                return@withContext Result.success(SignInState.Failure("EtSizeTooSmall", "[apptokens] et decryption failed: $reason"))
            }

            if (etBytes[0] != 'X'.code.toByte() || etBytes[1] != 'Y'.code.toByte() || etBytes[2] != 'Z'.code.toByte()) {
                val reason = "header mismatch (expected XYZ header)"
                AppDiagnostics.logAuthTrace("et decryption failed: reason=$reason")
                return@withContext Result.success(SignInState.Failure("EtHeaderMismatch", "[apptokens] et decryption failed: $reason"))
            }

            val decryptedEt: ByteArray = try {
                SrpCrypto.decryptDataGcm(skBytes, etBytes)
                    ?: throw javax.crypto.AEADBadTagException("tag mismatch")
            } catch (e: javax.crypto.AEADBadTagException) {
                val reason = "tag mismatch (AEAD tag verification failed)"
                AppDiagnostics.logAuthTrace("et decryption failed: reason=$reason")
                return@withContext Result.success(SignInState.Failure("EtTagMismatch", "[apptokens] et decryption failed: $reason"))
            } catch (e: Exception) {
                val reason = if (e.message?.contains("tag", ignoreCase = true) == true) "tag mismatch" else "crypto exception (${e.javaClass.simpleName})"
                AppDiagnostics.logAuthTrace("et decryption failed: reason=$reason")
                return@withContext Result.success(SignInState.Failure("EtDecryptFailed", "[apptokens] et decryption failed: $reason"))
            }

            AppDiagnostics.logAuthTrace("et decrypted ok (${decryptedEt.size} bytes)")

            val etPlist = try {
                ApplePlist.fromXml(String(decryptedEt, Charsets.UTF_8))
            } catch (e: Exception) {
                val reason = "plist parse (failed to parse decrypted XML: ${e.message})"
                AppDiagnostics.logAuthTrace("et decryption failed: reason=$reason")
                return@withContext Result.success(SignInState.Failure("EtPlistParseFailed", "[apptokens] et decryption failed: $reason"))
            }

            if (etPlist.isEmpty()) {
                val reason = "plist parse (decrypted plist was empty)"
                AppDiagnostics.logAuthTrace("et decryption failed: reason=$reason")
                return@withContext Result.success(SignInState.Failure("EtPlistEmpty", "[apptokens] et decryption failed: $reason"))
            }
            @Suppress("UNCHECKED_CAST")
            val tDict = (etPlist["t"] as? Map<String, Any?>) ?: etPlist
            val xcodeRaw = tDict["com.apple.gs.xcode.auth"] ?: etPlist["com.apple.gs.xcode.auth"]

            val xcodeToken: String = when (xcodeRaw) {
                is String -> xcodeRaw
                is ByteArray -> base64Encode(xcodeRaw)
                is Map<*, *> -> {
                    val inner = xcodeRaw["token"] ?: xcodeRaw["t"] ?: xcodeRaw.values.firstOrNull()
                    when (inner) {
                        is String -> inner
                        is ByteArray -> base64Encode(inner)
                        else -> inner?.toString() ?: ""
                    }
                }
                else -> ""
            }

            AppDiagnostics.logAuthTrace("xcode token present: ${xcodeToken.isNotEmpty()} (len=${xcodeToken.length})")
            if (xcodeToken.isEmpty()) {
                AppDiagnostics.logAuthTrace("xcode token absent in decrypted dictionary")
                return@withContext Result.success(
                    SignInState.Failure("MissingXcodeToken", "com.apple.gs.xcode.auth token not found in decrypted tokens dictionary.")
                )
            }

            val tokensMap = mutableMapOf<String, String>()
            tokensMap["com.apple.gs.xcode.auth"] = xcodeToken
            tDict.forEach { (k, v) ->
                if (k != "com.apple.gs.xcode.auth") {
                    val s = when (v) {
                        is String -> v
                        is ByteArray -> base64Encode(v)
                        is Map<*, *> -> (v["token"] ?: v.values.firstOrNull())?.toString() ?: ""
                        else -> v?.toString() ?: ""
                    }
                    if (s.isNotEmpty()) tokensMap[k] = s
                }
            }

            val session = AppleAuthSession(
                appleId = normalizedAppleId,
                dsid = adsid,
                adsid = adsid,
                pet = xcodeToken,
                tokens = tokensMap
            )
            AppDiagnostics.logAuthTrace(AppDiagnostics.redactSecrets("Session ready: tokensCount=${tokensMap.size}"))
            Result.success(SignInState.Success(session))

        } catch (e: Exception) {
            AppDiagnostics.logAuthTrace("GSA sign-in failed with exception: ${e.message}")
            Log.e(TAG, "GSA sign-in failed with exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Sends an explicit GET request to Apple's trusted device trigger endpoint
     * (https://gsa.apple.com/auth/verify/trusteddevice) to dispatch the 2FA push notification.
     */
    suspend fun triggerTwoFactorVerification(
        context: SignInContext
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            AppDiagnostics.logAuthTrace("trusteddevice request sent")
            // Requirement 2: Re-use the exact same anisetteHeaders from context (one single fetch per sign-in attempt)
            val anisette = context.anisetteHeaders

            val identityToken = if (context.adsid.isNotEmpty() && context.idmsToken.isNotEmpty()) {
                val raw = "${context.adsid}:${context.idmsToken}"
                base64Encode(raw.toByteArray(Charsets.UTF_8))
            } else {
                AppDiagnostics.logAuthTrace("identity token absent")
                ""
            }

            val requestBuilder = Request.Builder()
                .url(GSA_TRUSTED_DEVICE_ENDPOINT)
                .get()
                // Field-by-field parity with AltSign's ALTAppleAPI+Authentication.m:
                .header("Content-Type", "text/x-xml-plist")
                .header("User-Agent", "Xcode")
                .header("Accept", "text/x-xml-plist")
                .header("Accept-Language", "en-us")
                .header("X-Apple-App-Info", "com.apple.gs.xcode.auth")
                .header("X-Xcode-Version", "11.2 (11B41)")
                .header("X-MMe-Client-Info", anisette.clientInfo)
                .apply {
                    if (identityToken.isNotEmpty()) {
                        header("X-Apple-Identity-Token", identityToken)
                    }
                    anisette.toHeaderMap().forEach { (k, v) -> header(k, v) }
                }

            val builtRequest = requestBuilder.build()
            val headerNames = builtRequest.headers.names().sorted().joinToString(", ")
            AppDiagnostics.logAuthTrace("trusteddevice request headers: $headerNames")

            val httpResponse = executeHttpRequest(builtRequest)
            AppDiagnostics.logAuthTrace("trusteddevice HTTP status: ${httpResponse.code}")

            if (httpResponse.code !in 200..299) {
                val parsed = try { ApplePlist.fromXml(httpResponse.body) } catch (_: Exception) { null }
                val errorMsg = if (parsed != null) {
                    @Suppress("UNCHECKED_CAST")
                    val statusDict = (parsed["Response"] as? Map<String, Any?>)?.get("Status") as? Map<String, Any?>
                        ?: parsed["Status"] as? Map<String, Any?>
                    statusDict?.get("em")?.toString()
                } else null

                val finalError = errorMsg
                    ?: "Apple rejected 2FA verification push request (HTTP ${httpResponse.code}: ${httpResponse.message})"
                return@withContext Result.failure(IOException(finalError))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            AppDiagnostics.logAuthTrace("trusteddevice request failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Submits the 6-digit two-factor authentication code to Apple's validation endpoint via GET,
     * and on success re-runs the full SRP login so the second pass obtains spd and apptokens.
     */
    suspend fun submitTwoFactorCode(
        code: String,
        context: SignInContext
    ): Result<SignInState> = withContext(Dispatchers.IO) {
        try {
            AppDiagnostics.logAuthTrace("Submitting 2FA code to $GSA_VALIDATE_ENDPOINT (GET)...")

            // Requirement 2: Re-use the exact same anisetteHeaders from context (one single fetch per sign-in attempt)
            val anisette = context.anisetteHeaders
            val cpdMap = anisette.toHeaderMap()
            val identityToken = if (context.adsid.isNotEmpty() && context.idmsToken.isNotEmpty()) {
                val raw = "${context.adsid}:${context.idmsToken}"
                base64Encode(raw.toByteArray(Charsets.UTF_8))
            } else {
                AppDiagnostics.logAuthTrace("identity token absent")
                ""
            }

            val idTokenSent = identityToken.isNotEmpty()
            val idTokenSentStr = if (idTokenSent) "yes" else "no"

            val requestBuilder = Request.Builder()
                .url(GSA_VALIDATE_ENDPOINT)
                .get()
                // Field-by-field parity with AltSign's validate implementation:
                .header("Content-Type", "text/x-xml-plist")
                .header("User-Agent", "Xcode")
                .header("Accept", "text/x-xml-plist")
                .header("Accept-Language", "en-us")
                .header("X-Apple-App-Info", "com.apple.gs.xcode.auth")
                .header("X-Xcode-Version", "11.2 (11B41)")
                .header("X-MMe-Client-Info", anisette.clientInfo)
                .header("security-code", code.trim())
                .apply {
                    if (identityToken.isNotEmpty()) {
                        header("X-Apple-Identity-Token", identityToken)
                    }
                    cpdMap.forEach { (k, v) -> header(k, v) }
                }

            val builtRequest = requestBuilder.build()
            val headerNames = builtRequest.headers.names().sorted().joinToString(", ")
            AppDiagnostics.logAuthTrace("validate request headers: $headerNames (X-Apple-Identity-Token sent=$idTokenSentStr)")

            val validateHttpResponse = executeHttpRequest(builtRequest)
            AppDiagnostics.logAuthTrace("2FA validate HTTP status: ${validateHttpResponse.code} ${validateHttpResponse.message}")
            val parsed = ApplePlist.fromXml(validateHttpResponse.body)
            val validateStatus = AppleResponseHelper.parseStatus(parsed, validateHttpResponse.code)
            AppDiagnostics.logAuthTrace("2FA validate response: length=${validateHttpResponse.body.length}, keys=${validateStatus.topLevelKeys}")

            if (!validateStatus.isSuccess) {
                val failureDesc = if (validateStatus.errorCode == -21669) {
                    "[validate request] HTTP ${validateHttpResponse.code}, ec=-21669 (identityToken sent=$idTokenSentStr): Incorrect verification code"
                } else {
                    val rawErr = AppleResponseHelper.formatErrorMessage("validate request", validateStatus)
                    "$rawErr (identityToken sent=$idTokenSentStr)"
                }
                AppDiagnostics.logAuthTrace("2FA validate error: $failureDesc")
                return@withContext Result.success(
                    SignInState.Failure(validateStatus.rawErrorCodeStr ?: "AuthError", failureDesc, validateHttpResponse.body)
                )
            }

            AppDiagnostics.logAuthTrace("2FA code accepted: [validate request] ok (HTTP ${validateHttpResponse.code}, ec=${validateStatus.rawErrorCodeStr ?: "null"}, identityToken sent=$idTokenSentStr). Starting second SRP pass...")

            // Re-run the full SRP login (steps A to C) now that 2FA is validated, reusing the SAME anisette headers!
            val secondPassResult = signIn(context.appleId, context.password, context.anisetteHeaders)
            val resultState = secondPassResult.getOrNull()
            val stateName = when (resultState) {
                is SignInState.Success -> "Success"
                is SignInState.Failure -> "Failure(${resultState.errorCode})"
                is SignInState.NeedsTwoFactor -> "NeedsTwoFactor"
                null -> "Exception(${secondPassResult.exceptionOrNull()?.message})"
            }
            AppDiagnostics.logAuthTrace("Second SRP pass ended: result=$stateName")

            when (val state = resultState) {
                is SignInState.Success -> {
                    secondPassResult
                }
                is SignInState.Failure -> {
                    val detailedMsg = "[second SRP pass after validate] ${state.errorMessage} (validate identityToken sent=$idTokenSentStr)"
                    Result.success(SignInState.Failure(state.errorCode, detailedMsg, state.rawResponse))
                }
                is SignInState.NeedsTwoFactor -> {
                    val detailedMsg = "[second SRP pass after validate] Unexpectedly required 2FA again (validate identityToken sent=$idTokenSentStr)"
                    Result.success(SignInState.Failure("-21669", detailedMsg))
                }
                null -> secondPassResult
            }

        } catch (e: Exception) {
            AppDiagnostics.logAuthTrace("2FA validation threw exception: ${e.message}")
            Log.e(TAG, "2FA validation failed with exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun buildGsaRequest(
        url: String,
        xmlPayload: String,
        clientInfo: String,
        userAgent: String = USER_AGENT
    ): Request {
        return Request.Builder()
            .url(url)
            .header("Content-Type", "text/x-xml-plist")
            .header("Accept", "*/*")
            .header("User-Agent", userAgent)
            .header("X-MMe-Client-Info", clientInfo)
            .post(xmlPayload.toRequestBody(PLIST_MEDIA_TYPE))
            .build()
    }

    private data class GsaHttpResponse(
        val code: Int,
        val message: String,
        val body: String
    )

    private fun executeHttpRequest(request: Request): GsaHttpResponse {
        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                return GsaHttpResponse(response.code, response.message, body)
            }
        } catch (e: Exception) {
            val diag = mutableListOf<String>()
            diag.add("[TLS DIAG] GrandSlam request failed for host: ${request.url.host}")
            diag.add("[TLS DIAG] Exception: ${e.javaClass.name}: ${e.message}")
            if (AppleTrustHelper.lastTlsDiagnostic.isNotEmpty()) {
                diag.addAll(AppleTrustHelper.lastTlsDiagnostic)
            } else {
                diag.add("[TLS DIAG] No peer certificates captured (handshake failed before trust check)")
            }
            AppleTrustHelper.lastErrorDiagnostic = diag
            Log.e(TAG, "[TLS DIAG] ${diag.joinToString("\n")}", e)
            throw e
        }
    }

    private fun extractSession(
        appleId: String,
        responseDict: Map<String, Any?>,
        fallbackAdsid: String = "",
        fallbackIdmsToken: String = ""
    ): AppleAuthSession {
        @Suppress("UNCHECKED_CAST")
        val spDict = responseDict["sp"] as? Map<String, Any?> ?: responseDict

        var dsid = (spDict["dsid"] ?: responseDict["dsid"] ?: "").toString()
        var adsid = (spDict["adsid"] ?: responseDict["adsid"] ?: "").toString()
        if (adsid.isEmpty()) adsid = fallbackAdsid
        if (dsid.isEmpty()) dsid = adsid

        val petRaw = spDict["pet"] ?: responseDict["pet"]
        var petStr = when (petRaw) {
            is ByteArray -> base64Encode(petRaw)
            is String -> petRaw
            else -> ""
        }
        if (petStr.isEmpty()) petStr = fallbackIdmsToken

        val tokensMap = mutableMapOf<String, String>()
        @Suppress("UNCHECKED_CAST")
        val tokensDict = (spDict["tokens"] ?: responseDict["tokens"]) as? Map<String, Any?>
        tokensDict?.forEach { (k, v) ->
            tokensMap[k] = when (v) {
                is ByteArray -> base64Encode(v)
                else -> v.toString()
            }
        }

        // If pet is still empty, fallback to Xcode token or AppleID token from tokensMap
        if (petStr.isEmpty()) {
            petStr = tokensMap["com.apple.gs.xcode.auth"]
                ?: tokensMap["com.apple.gs.appleid.auth"]
                ?: tokensMap.values.firstOrNull()
                ?: ""
        }

        return AppleAuthSession(
            appleId = appleId,
            dsid = dsid,
            adsid = adsid,
            pet = petStr,
            tokens = tokensMap
        )
    }

    private fun parseGsaError(
        parsed: Map<String, Any?>,
        rawXml: String,
        httpCode: Int = 0,
        stepName: String = "",
        isNonPlist: Boolean = false
    ): Result<SignInState> {
        val status = AppleResponseHelper.parseStatus(parsed, httpCode)
        val classified = AppleResponseHelper.classifyError(stepName, status, isNonPlist = isNonPlist)
        val errorDesc = classified.toCombinedDisplayString()
        val errorCode = status.rawErrorCodeStr ?: if (isNonPlist) "HTTP_$httpCode" else "AuthError"

        Log.w(TAG, AppDiagnostics.redactSecrets("GSA returned error: step=$stepName, error=$errorDesc"))
        AppDiagnostics.logAuthTrace("GSA error: $errorDesc")
        return Result.success(SignInState.Failure(errorCode, errorDesc, rawXml))
    }
}

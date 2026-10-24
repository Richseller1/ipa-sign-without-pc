package com.aistudio.iossideloadbridge.app.provisioning

import android.util.Log
import com.aistudio.iossideloadbridge.app.anisette.AnisetteHeaders
import com.aistudio.iossideloadbridge.app.auth.AppleAuthSession
import com.aistudio.iossideloadbridge.app.auth.ApplePlist
import com.aistudio.iossideloadbridge.app.auth.AppleTrustHelper
import com.aistudio.iossideloadbridge.app.auth.DryRunInspection
import com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.Base64 as JavaBase64

data class DeveloperTeam(
    val teamId: String,
    val name: String,
    val status: String = "active",
    val type: String = "Personal",
    val isFreeAccount: Boolean = true
)

data class DeveloperCertificate(
    val certificateId: String,
    val name: String,
    val serialNumber: String,
    val certDer: ByteArray,
    val certPem: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeveloperCertificate
        return certificateId == other.certificateId && serialNumber == other.serialNumber
    }

    override fun hashCode(): Int {
        var result = certificateId.hashCode()
        result = 31 * result + serialNumber.hashCode()
        return result
    }
}

data class DeveloperAppId(
    val appIdId: String,
    val identifier: String,
    val name: String
)

data class DeveloperProvisioningProfile(
    val profileId: String,
    val name: String,
    val expirationDate: String,
    val profileBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeveloperProvisioningProfile
        return profileId == other.profileId && name == other.name
    }

    override fun hashCode(): Int {
        var result = profileId.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }
}

/**
 * Client for Apple Developer services ("developerservices2.apple.com/services/QH65B2/").
 * Implements team listing, certificate signing request submission, App ID registration,
 * and provisioning profile download matching AltSign / AltStore.
 */
class AppleDeveloperClient(
    private val timeoutSeconds: Long = 20L
) {
    companion object {
        private const val TAG = "AppleDeveloperClient"
        const val BASE_SERVICES_URL = "https://developerservices2.apple.com/services/QH65B2/"
        const val CLIENT_ID = "XABBG36SBA"
        const val PROTOCOL_VERSION = "QH65B2"
        private val PLIST_MEDIA_TYPE = "text/x-xml-plist; charset=utf-8".toMediaType()

        private fun base64Encode(bytes: ByteArray): String {
            return try {
                JavaBase64.getEncoder().encodeToString(bytes)
            } catch (_: Throwable) {
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        }
    }

    private val httpClient: OkHttpClient = AppleTrustHelper.createOkHttpClient(timeoutSeconds)

    /**
     * Fetches all developer teams associated with this authenticated Apple ID.
     * Free Apple IDs typically have exactly one personal team ("First Last (Personal Team)").
     */
    suspend fun fetchTeams(
        session: AppleAuthSession,
        anisette: AnisetteHeaders
    ): Result<List<DeveloperTeam>> = withContext(Dispatchers.IO) {
        try {
            AppDiagnostics.logAuthTrace("Fetching developer teams for ${session.appleId}... (xcode token present=${session.pet.isNotEmpty()})")
            val responseDict = executeAction(
                actionPath = "listTeams.action",
                parameters = emptyMap(),
                session = session,
                team = null,
                anisette = anisette
            )

            @Suppress("UNCHECKED_CAST")
            val teamsList = responseDict["teams"] as? List<Map<String, Any?>>
                ?: run {
                    val err = "No teams array in listTeams response: ${responseDict["responseStatus"] ?: responseDict}"
                    AppDiagnostics.logAuthTrace("listTeams failed: $err")
                    return@withContext Result.failure(IOException(err))
                }

            val teams = teamsList.mapNotNull { item ->
                val teamId = item["teamId"]?.toString() ?: return@mapNotNull null
                val name = item["name"]?.toString() ?: "Developer Team ($teamId)"
                val status = item["status"]?.toString() ?: "active"
                val type = item["type"]?.toString() ?: "Personal"
                val isFree = type.equals("Personal", ignoreCase = true) ||
                    name.contains("Personal Team", ignoreCase = true)
                DeveloperTeam(
                    teamId = teamId,
                    name = name,
                    status = status,
                    type = type,
                    isFreeAccount = isFree
                )
            }

            AppDiagnostics.logAuthTrace("teams fetch result: ok (${teams.size} teams found)")
            Log.i(TAG, "Found ${teams.size} teams for ${session.appleId}")
            Result.success(teams)
        } catch (e: Exception) {
            AppDiagnostics.logAuthTrace("teams fetch result: failed with error: ${e.message}")
            Log.e(TAG, "Failed to fetch teams: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Submits a CSR to Apple Developer services to create or download an iOS Development Certificate.
     */
    suspend fun requestCertificate(
        session: AppleAuthSession,
        team: DeveloperTeam,
        csrPem: String,
        machineName: String = "AltSign",
        anisette: AnisetteHeaders
    ): Result<DeveloperCertificate> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Submitting development CSR to team ${team.teamId} (${team.name})...")
            val params = mapOf(
                "csrContent" to csrPem,
                "machineId" to UUID.randomUUID().toString(),
                "machineName" to machineName
            )

            val responseDict = executeAction(
                actionPath = "ios/submitDevelopmentCSR.action",
                parameters = params,
                session = session,
                team = team,
                anisette = anisette
            )

            @Suppress("UNCHECKED_CAST")
            val certRequest = responseDict["certRequest"] as? Map<String, Any?>
                ?: return@withContext Result.failure(IOException("Missing certRequest in response"))

            @Suppress("UNCHECKED_CAST")
            val certDict = certRequest["certificate"] as? Map<String, Any?>
                ?: certRequest

            val certId = (certDict["certificateId"] ?: certRequest["certRequestId"] ?: UUID.randomUUID().toString()).toString()
            val certName = (certDict["name"] ?: "Apple Development Certificate").toString()
            val serialNumber = (certDict["serialNumber"] ?: "").toString()

            val rawContent = certDict["certContent"] ?: certRequest["certContent"]
            val certBytes: ByteArray = when (rawContent) {
                is ByteArray -> rawContent
                is String -> try {
                    JavaBase64.getDecoder().decode(rawContent.trim())
                } catch (_: Exception) {
                    rawContent.toByteArray(Charsets.UTF_8)
                }
                else -> ByteArray(0)
            }

            val certPem = CsrGenerator.toPem("CERTIFICATE", certBytes)

            Log.i(TAG, "Successfully received certificate: $certName ($certId), serial: $serialNumber")
            Result.success(
                DeveloperCertificate(
                    certificateId = certId,
                    name = certName,
                    serialNumber = serialNumber,
                    certDer = certBytes,
                    certPem = certPem
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request certificate: ${e.message}", e)
            Result.failure(e)
        }
    }

    data class RegisteredDevice(
        val deviceId: String,
        val deviceNumber: String,
        val name: String,
        val status: String
    )

    /**
     * Lists registered development devices for this team.
     */
    suspend fun listDevices(
        session: AppleAuthSession,
        team: DeveloperTeam,
        anisette: AnisetteHeaders
    ): Result<List<RegisteredDevice>> = withContext(Dispatchers.IO) {
        try {
            val responseDict = executeAction(
                actionPath = "ios/listDevices.action",
                parameters = emptyMap(),
                session = session,
                team = team,
                anisette = anisette
            )

            @Suppress("UNCHECKED_CAST")
            val devicesList = responseDict["devices"] as? List<Map<String, Any?>> ?: emptyList()
            val devices = devicesList.mapNotNull { item ->
                val id = item["deviceId"]?.toString() ?: return@mapNotNull null
                val number = item["deviceNumber"]?.toString() ?: return@mapNotNull null
                val name = item["name"]?.toString() ?: "iOS Device"
                val status = item["status"]?.toString() ?: "c"
                RegisteredDevice(deviceId = id, deviceNumber = number, name = name, status = status)
            }
            Log.i(TAG, "Found ${devices.size} existing devices for team ${team.teamId}")
            Result.success(devices)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list devices: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Registers a device UDID with the developer team if not already registered.
     */
    suspend fun registerDevice(
        session: AppleAuthSession,
        team: DeveloperTeam,
        deviceName: String,
        udid: String,
        anisette: AnisetteHeaders
    ): Result<RegisteredDevice> = withContext(Dispatchers.IO) {
        try {
            val existingResult = listDevices(session, team, anisette)
            if (existingResult.isSuccess) {
                val existing = existingResult.getOrNull()?.find {
                    it.deviceNumber.equals(udid, ignoreCase = true)
                }
                if (existing != null) {
                    Log.i(TAG, "Device $udid already registered on team as ${existing.deviceId}")
                    return@withContext Result.success(existing)
                }
            }

            Log.i(TAG, "Registering device: name=$deviceName, udid=$udid...")
            val params = mapOf(
                "deviceNumber" to udid,
                "name" to deviceName
            )
            val responseDict = executeAction(
                actionPath = "ios/addDevice.action",
                parameters = params,
                session = session,
                team = team,
                anisette = anisette
            )

            @Suppress("UNCHECKED_CAST")
            val deviceDict = responseDict["device"] as? Map<String, Any?>
                ?: return@withContext Result.failure(IOException("Missing device in addDevice response"))

            val deviceId = deviceDict["deviceId"]?.toString()
                ?: return@withContext Result.failure(IOException("Missing deviceId in response"))
            val deviceNumber = deviceDict["deviceNumber"]?.toString() ?: udid
            val name = deviceDict["name"]?.toString() ?: deviceName
            val status = deviceDict["status"]?.toString() ?: "c"

            Log.i(TAG, "Successfully registered device: $name ($deviceNumber) -> ID $deviceId")
            Result.success(RegisteredDevice(deviceId = deviceId, deviceNumber = deviceNumber, name = name, status = status))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register device: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Lists existing App IDs registered for this developer team.
     */
    suspend fun listAppIds(
        session: AppleAuthSession,
        team: DeveloperTeam,
        anisette: AnisetteHeaders
    ): Result<List<DeveloperAppId>> = withContext(Dispatchers.IO) {
        try {
            val responseDict = executeAction(
                actionPath = "ios/listAppIds.action",
                parameters = emptyMap(),
                session = session,
                team = team,
                anisette = anisette
            )

            @Suppress("UNCHECKED_CAST")
            val appIdsList = responseDict["appIds"] as? List<Map<String, Any?>> ?: emptyList()

            val appIds = appIdsList.mapNotNull { item ->
                val id = item["appIdId"]?.toString() ?: return@mapNotNull null
                val identifier = item["identifier"]?.toString() ?: return@mapNotNull null
                val name = item["name"]?.toString() ?: identifier
                DeveloperAppId(appIdId = id, identifier = identifier, name = name)
            }

            Log.i(TAG, "Found ${appIds.size} existing App IDs for team ${team.teamId}")
            Result.success(appIds)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list App IDs: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Registers a new App ID for this bundle identifier if not already existing.
     */
    suspend fun registerAppId(
        session: AppleAuthSession,
        team: DeveloperTeam,
        bundleId: String,
        appName: String,
        anisette: AnisetteHeaders
    ): Result<DeveloperAppId> = withContext(Dispatchers.IO) {
        try {
            // First check if already registered
            val existingResult = listAppIds(session, team, anisette)
            if (existingResult.isSuccess) {
                val existing = existingResult.getOrNull()?.find {
                    it.identifier.equals(bundleId, ignoreCase = true)
                }
                if (existing != null) {
                    Log.i(TAG, "App ID for bundle $bundleId already registered as ${existing.appIdId}")
                    return@withContext Result.success(existing)
                }
            }

            // Sanitize name: alphanumeric characters only
            val sanitizedName = appName.filter { it.isLetterOrDigit() || it.isWhitespace() }.ifBlank { "SideloadApp" }

            Log.i(TAG, "Registering new App ID: identifier=$bundleId, name=$sanitizedName...")
            val params = mapOf(
                "identifier" to bundleId,
                "name" to sanitizedName
            )

            val responseDict = executeAction(
                actionPath = "ios/addAppId.action",
                parameters = params,
                session = session,
                team = team,
                anisette = anisette
            )

            @Suppress("UNCHECKED_CAST")
            val appIdDict = responseDict["appId"] as? Map<String, Any?>
                ?: return@withContext Result.failure(IOException("Missing appId in addAppId response"))

            val appIdId = appIdDict["appIdId"]?.toString()
                ?: return@withContext Result.failure(IOException("Missing appIdId in response"))
            val identifier = appIdDict["identifier"]?.toString() ?: bundleId
            val name = appIdDict["name"]?.toString() ?: sanitizedName

            Log.i(TAG, "Successfully registered App ID: $identifier ($appIdId)")
            Result.success(DeveloperAppId(appIdId = appIdId, identifier = identifier, name = name))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register App ID: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads the iOS Team Provisioning Profile (.mobileprovision) for this App ID.
     */
    suspend fun requestProvisioningProfile(
        session: AppleAuthSession,
        team: DeveloperTeam,
        appId: DeveloperAppId,
        anisette: AnisetteHeaders
    ): Result<DeveloperProvisioningProfile> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Downloading team provisioning profile for App ID: ${appId.identifier} (${appId.appIdId})...")
            val params = mapOf(
                "appIdId" to appId.appIdId
            )

            val responseDict = executeAction(
                actionPath = "ios/downloadTeamProvisioningProfile.action",
                parameters = params,
                session = session,
                team = team,
                anisette = anisette
            )

            @Suppress("UNCHECKED_CAST")
            val profileDict = responseDict["provisioningProfile"] as? Map<String, Any?>
                ?: return@withContext Result.failure(IOException("Missing provisioningProfile in download response"))

            val profileId = (profileDict["provisioningProfileId"] ?: UUID.randomUUID().toString()).toString()
            val profileName = (profileDict["name"] ?: "iOS Team Provisioning Profile: ${appId.identifier}").toString()
            val expirationDate = (profileDict["expirationDate"] ?: "7 days").toString()

            val rawData = profileDict["encodedProfile"]
            val profileBytes: ByteArray = when (rawData) {
                is ByteArray -> rawData
                is String -> try {
                    JavaBase64.getDecoder().decode(rawData.trim())
                } catch (_: Exception) {
                    rawData.toByteArray(Charsets.UTF_8)
                }
                else -> ByteArray(0)
            }

            if (profileBytes.isEmpty()) {
                return@withContext Result.failure(IOException("Received empty encodedProfile from Apple Developer services"))
            }

            Log.i(TAG, "Successfully downloaded provisioning profile: $profileName (${profileBytes.size} bytes), expires: $expirationDate")
            Result.success(
                DeveloperProvisioningProfile(
                    profileId = profileId,
                    name = profileName,
                    expirationDate = expirationDate,
                    profileBytes = profileBytes
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download provisioning profile: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Executes the end-to-end provisioning pipeline:
     * 1. Team resolution
     * 2. Local 2048-bit RSA key pair & PKCS#10 CSR generation
     * 3. Development Certificate request
     * 4. Device (UDID) registration
     * 5. App ID registration
     * 6. Team Provisioning Profile download
     */
    suspend fun executeFullProvisioning(
        session: AppleAuthSession,
        team: DeveloperTeam,
        bundleId: String,
        appName: String,
        anisette: AnisetteHeaders,
        deviceUdid: String? = null,
        deviceName: String = "iOS Device",
        onProgress: (stepIndex: Int, message: String) -> Unit
    ): Result<ProvisionedIdentity> = withContext(Dispatchers.IO) {
        try {
            // Stage 1: Local RSA Keypair & CSR
            onProgress(1, "Generating 2048-bit RSA key pair & CSR on-device...")
            val generatedKeys = CsrGenerator.generate(
                country = "US",
                state = "CA",
                locality = "Los Angeles",
                organization = "AltSign",
                commonName = "AltSign"
            )

            // Stage 2: Submit CSR for Certificate
            onProgress(2, "Submitting CSR to Apple Developer service (${team.name})...")
            val certResult = requestCertificate(
                session = session,
                team = team,
                csrPem = generatedKeys.csrPem,
                machineName = "AltSign",
                anisette = anisette
            )
            val certificate = certResult.getOrThrow()

            // Stage 3: Register Device UDID if provided
            if (!deviceUdid.isNullOrBlank()) {
                onProgress(3, "Registering device UDID (${deviceUdid.take(8)}...) with team...")
                val devResult = registerDevice(
                    session = session,
                    team = team,
                    deviceName = deviceName,
                    udid = deviceUdid,
                    anisette = anisette
                )
                if (devResult.isFailure) {
                    Log.w(TAG, "Device registration warning: ${devResult.exceptionOrNull()?.message}")
                }
            }

            // Stage 4: Register App ID
            onProgress(4, "Registering App ID for bundle '$bundleId'...")
            val appIdResult = registerAppId(
                session = session,
                team = team,
                bundleId = bundleId,
                appName = appName,
                anisette = anisette
            )
            val appId = appIdResult.getOrThrow()

            // Stage 5: Download Provisioning Profile
            onProgress(5, "Downloading Team Provisioning Profile (.mobileprovision)...")
            val profileResult = requestProvisioningProfile(
                session = session,
                team = team,
                appId = appId,
                anisette = anisette
            )
            val profile = profileResult.getOrThrow()

            onProgress(6, "Provisioning complete! Profile: ${profile.name} (${profile.profileBytes.size} bytes)")

            val identity = ProvisionedIdentity(
                teamId = team.teamId,
                teamName = team.name,
                bundleId = bundleId,
                certificateId = certificate.certificateId,
                certificateName = certificate.name,
                serialNumber = certificate.serialNumber,
                certificatePem = certificate.certPem,
                privateKeyPem = generatedKeys.privateKeyPem,
                profileName = profile.name,
                profileExpiration = profile.expirationDate,
                profileBytes = profile.profileBytes,
                isFreeAccount = team.isFreeAccount
            )

            Result.success(identity)
        } catch (e: Exception) {
            Log.e(TAG, "Provisioning pipeline failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Inspects the prepared request for dry-run inspection without contacting Apple.
     */
    fun inspectProvisioningRequest(
        session: AppleAuthSession,
        team: DeveloperTeam,
        bundleId: String,
        anisette: AnisetteHeaders
    ): DryRunInspection {
        val dummyCsr = "-----BEGIN CERTIFICATE REQUEST-----\nMIIBCgKCAQEA...\n-----END CERTIFICATE REQUEST-----"
        val params = mapOf(
            "clientId" to CLIENT_ID,
            "protocolVersion" to PROTOCOL_VERSION,
            "requestId" to UUID.randomUUID().toString().uppercase(),
            "teamId" to team.teamId,
            "csrContent" to dummyCsr,
            "machineId" to UUID.randomUUID().toString(),
            "machineName" to "AltSign"
        )

        val xmlPayload = ApplePlist.toXml(params)
        val url = "${BASE_SERVICES_URL}ios/submitDevelopmentCSR.action?clientId=$CLIENT_ID"

        val xcodeToken = session.tokens["com.apple.gs.xcode.auth"] ?: session.pet
        val identityId = session.adsid.ifEmpty { session.dsid }

        val headers = mutableMapOf(
            "Content-Type" to "text/x-xml-plist",
            "User-Agent" to "Xcode",
            "Accept" to "text/x-xml-plist",
            "Accept-Language" to "en-us",
            "X-Apple-App-Info" to "com.apple.gs.xcode.auth",
            "X-Xcode-Version" to "11.2 (11B41)",
            "X-Apple-I-Identity-Id" to identityId,
            "X-Apple-GS-Token" to xcodeToken
        ).apply {
            putAll(anisette.toHeaderMap())
        }

        return DryRunInspection(
            endpoint = url,
            httpHeaders = headers,
            xmlPayload = xmlPayload,
            srpAHex = "(Provisioning request: CSR + Team ID + Anisette headers)"
        )
    }

    private fun executeAction(
        actionPath: String,
        parameters: Map<String, Any?>,
        session: AppleAuthSession,
        team: DeveloperTeam?,
        anisette: AnisetteHeaders
    ): Map<String, Any?> {
        val xcodeToken = session.tokens["com.apple.gs.xcode.auth"] ?: session.pet
        if (xcodeToken.isBlank()) {
            throw IllegalStateException("Xcode authentication token (X-Apple-GS-Token) is missing from the session. Please sign out and sign in again.")
        }
        val identityId = session.adsid.ifEmpty { session.dsid }

        val url = "${BASE_SERVICES_URL}${actionPath}?clientId=$CLIENT_ID"

        val bodyMap = mutableMapOf<String, Any?>(
            "clientId" to CLIENT_ID,
            "protocolVersion" to PROTOCOL_VERSION,
            "requestId" to UUID.randomUUID().toString().uppercase()
        )
        if (team != null) {
            bodyMap["teamId"] = team.teamId
        }
        bodyMap.putAll(parameters)

        val xmlPayload = ApplePlist.toXml(bodyMap)

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "text/x-xml-plist")
            .header("Accept", "text/x-xml-plist")
            .header("User-Agent", "Xcode")
            .header("Accept-Language", "en-us")
            .header("X-Apple-App-Info", "com.apple.gs.xcode.auth")
            .header("X-Xcode-Version", "11.2 (11B41)")
            .header("X-Apple-I-Identity-Id", identityId)
            .header("X-Apple-GS-Token", xcodeToken)
            .header("X-MMe-Client-Info", anisette.clientInfo)
            .apply {
                anisette.toHeaderMap().forEach { (k, v) -> header(k, v) }
            }
            .post(xmlPayload.toRequestBody(PLIST_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful && responseBody.isBlank()) {
                throw IOException("Apple Developer service returned HTTP ${response.code} ${response.message}")
            }

            val parsed = ApplePlist.fromXml(responseBody)
            val devStatus = com.aistudio.iossideloadbridge.app.auth.AppleResponseHelper.parseStatus(parsed, response.code)

            if (!devStatus.isSuccess) {
                val resultCode = devStatus.errorCode ?: -1
                val userString = devStatus.errorMessage
                    ?: getDeveloperErrorMessage(resultCode)
                throw AppleDeveloperException(resultCode, userString, responseBody)
            }

            return devStatus.activeDict
        }
    }

    private fun getDeveloperErrorMessage(resultCode: Int): String {
        return when (resultCode) {
            1100 -> "Your session has expired. Please sign out and sign in again to refresh your tokens."
            3250, 3251, 3252 -> "You already have a current iOS Development certificate on this account, or the CSR was rejected. Free Apple accounts are limited to 1-2 active development certificates. You can revoke older certificates in Xcode / Apple Developer portal."
            9120 -> "Maximum App ID limit reached. Free Apple IDs are limited to 3 active App IDs at a time. Please wait 7 days for older App IDs to expire, or remove one in Xcode."
            9401 -> "The requested bundle identifier is already registered or unavailable."
            9412 -> "Invalid bundle identifier format."
            8201 -> "The specified App ID does not exist."
            else -> "Apple Developer service error ($resultCode)"
        }
    }
}

class AppleDeveloperException(
    val resultCode: Int,
    override val message: String,
    val rawXml: String
) : IOException("Apple Developer Error ($resultCode): $message")

package com.aistudio.iossideloadbridge.app.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.aistudio.iossideloadbridge.app.anisette.AnisetteClient
import com.aistudio.iossideloadbridge.app.auth.AppleTrustHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

object AppDiagnostics {
    private const val TAG = "AppDiagnostics"
    private const val LOG_FILE_NAME = "diagnostics.log"
    private const val CRASH_FILE_NAME = "last_crash.txt"

    const val BUILD_ID = "bld-20261025-init-diag-v14"
    const val GIT_COMMIT = "740d752d"

    @Volatile
    var advancedDiagnosticsEnabled: Boolean = false

    @Volatile
    var selectedAnisetteHost: String? = null

    // ---- Authentication Diagnostic Trace ----
    private val authTraceList = java.util.Collections.synchronizedList(mutableListOf<String>())

    fun logAuthTrace(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val safe = redactSecrets(message)
        val entry = "[$timestamp] $safe"
        Log.i(TAG, "[AUTH-TRACE] $safe")
        authTraceList.add(entry)
        if (authTraceList.size > 200) {
            authTraceList.removeAt(0)
        }
    }

    fun logAdvancedAuthTrace(message: String) {
        if (!advancedDiagnosticsEnabled) return
        val safe = redactSecrets(message)
        logAuthTrace("[ADV-DIAG] $safe")
    }

    fun getAuthTrace(): List<String> {
        return synchronized(authTraceList) { authTraceList.toList() }
    }

    fun clearAuthTrace() {
        authTraceList.clear()
    }

    // ---- 1. Redaction ----
    fun redactSecrets(input: String): String {
        var s = input
        // Mask private keys
        s = s.replace(Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""), "[REDACTED_PRIVATE_KEY]")
        // Mask passwords
        s = s.replace(Regex("""(?i)\b(password)\b[\"']?\s*[:=]\s*[\"']?[^\s\"',;]+"""), "$1: ***")
        // Mask auth tokens, session IDs & device/anisette identifiers
        s = s.replace(Regex("""(?i)\b(authToken|idmsToken|adsid|dsid|pet|passwordToken|X-Apple-GS-Token|X-Apple-I-Identity-Id|deviceId|X-Mme-Device-Id|X-Apple-I-MD|X-Apple-I-MD-M|X-Apple-I-MD-LU)\b[\"']?\s*[:=]\s*[\"']?[A-Za-z0-9+/=._-]+"""), "$1=***")
        s = s.replace(Regex("""(?i)\b(token)\b[\"']?\s*=\s*[\"']?[A-Za-z0-9+/=._-]+"""), "$1=***")
        // Mask 6-digit 2FA codes
        s = s.replace(Regex("""(?i)\b(code|twoFactorCode)\b[\"']?\s*[:=]\s*[\"']?\d{6}"""), "$1=******")
        // Mask email addresses / account names (e.g. a***@domain.com)
        s = s.replace(Regex("""\b([A-Za-z0-9_.+-])[A-Za-z0-9_.+-]*@([A-Za-z0-9-]+\.[A-Za-z0-9-.]+)\b"""), "$1***@$2")
        // Mask full UUID device IDs (8-4-4-4-12 hex)
        s = s.replace(Regex("""\b([0-9a-fA-F]{4})[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""), "$1****-****-****-****-************")
        // Mask SRP keys & large byte arrays
        s = s.replace(Regex("""(?i)\b(srpA|srpB|srpK|srpM1|salt)\b[\"']?\s*[:=]\s*[\"']?[A-Za-z0-9+/=._-]{16,}"""), "$1=***")
        return s
    }

    // ---- 2. Persistent Logging ----
    fun appendLog(context: Context, message: String) {
        try {
            val safeMessage = redactSecrets(message)
            val file = File(context.filesDir, LOG_FILE_NAME)
            file.appendText("$safeMessage\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append log to file: ${e.message}")
        }
    }

    fun readSavedLogs(context: Context, maxLines: Int = 100): List<String> {
        return try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (!file.exists()) emptyList()
            else file.readLines().takeLast(maxLines)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearSavedLogs(context: Context) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    // ---- 3. Uncaught Exception Handler ----
    fun installCrashHandler(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()

                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val crashReport = "CRASH at $time on thread '${thread.name}':\n$stackTrace\n"

                val crashFile = File(context.filesDir, CRASH_FILE_NAME)
                crashFile.writeText(crashReport)

                appendLog(context, "[CRASH] $crashReport")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record crash: ${e.message}")
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun getLastCrash(context: Context): String? {
        return try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (file.exists() && file.length() > 0) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    fun clearLastCrash(context: Context) {
        try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    // ---- 4. Native Logcat Reader ----
    fun readNativeLogcat(maxLines: Int = 80): List<String> {
        val logs = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "logcat", "-d", "-v", "time",
                "usbmuxd-android-bridge:V",
                "usbmuxd:V",
                "libusbmuxd:V",
                "libimobiledevice:V",
                "libusb:V",
                "AppDiagnostics:V",
                "*:S"
            ))
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { logs.add(redactSecrets(it)) }
                }
            }
        } catch (e: Exception) {
            logs.add("[DIAG] Could not read logcat: ${e.message}")
        }
        return logs.takeLast(maxLines)
    }

    // ---- 5. Run Self-Tests ----
    suspend fun runSelfTests(
        context: Context,
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        onLog("==========================================")
        onLog("=== RUNNING IN-APP SELF-TESTS (PHASE 2) ===")
        onLog("==========================================")
        onLog("Build ID: $BUILD_ID | Commit: $GIT_COMMIT")

        // Test A: Native libraries loading
        val libsToTest = listOf(
            "crypto",
            "ssl",
            "usb1.0",
            "plist-2.0",
            "imobiledevice-glue-1.0",
            "usbmuxd-2.0",
            "imobiledevice-1.0",
            "signer_native",
            "usbmuxd_native"
        )
        onLog("--- Test A: Native Library Loading (${libsToTest.size} libraries) ---")
        for (lib in libsToTest) {
            try {
                System.loadLibrary(lib)
                onLog("[SELF-TEST][PASS] Native library loaded: lib$lib.so")
            } catch (e: UnsatisfiedLinkError) {
                onLog("[SELF-TEST][FAIL] Native library failed: lib$lib.so -> ${e.message}")
            } catch (e: Throwable) {
                onLog("[SELF-TEST][FAIL] Native library error: lib$lib.so -> ${e.message}")
            }
        }

        // Test B: CPU ABI & USB Host Hardware
        onLog("--- Test B: CPU ABI & USB Host Feature ---")
        val supportedAbis = Build.SUPPORTED_ABIS.joinToString(", ")
        onLog("[SELF-TEST] Supported ABIs: $supportedAbis")
        val hasUsbHost = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        if (hasUsbHost) {
            onLog("[SELF-TEST][PASS] android.hardware.usb.host feature: PRESENT")
        } else {
            onLog("[SELF-TEST][FAIL] android.hardware.usb.host feature: NOT REPORTED")
        }

        // Test C: TLS probe to https://gsa.apple.com (GET / with NO credentials)
        onLog("--- Test C: TLS Probes to https://gsa.apple.com ---")

        // Probe C.1: Using our Composite TrustManager (AppleTrustHelper)
        onLog("[SELF-TEST] Probe 1: Testing gsa.apple.com with AppleTrustHelper...")
        try {
            val client = AppleTrustHelper.createOkHttpClient(timeoutSeconds = 10L)
            val request = Request.Builder()
                .url("https://gsa.apple.com/grandslam/GsService2")
                .header("User-Agent", "akd/1.0 CFNetwork/978.0.7 Darwin/18.7.0")
                .build()
            client.newCall(request).execute().use { response ->
                onLog("[SELF-TEST][PASS] AppleTrustHelper TLS Handshake succeeded! HTTP status: ${response.code}")
            }
        } catch (e: Exception) {
            onLog("[SELF-TEST][FAIL] AppleTrustHelper probe failed: ${e.javaClass.name}: ${e.message}")
            AppleTrustHelper.lastTlsDiagnostic.forEach { onLog(it) }
        }

        // Probe C.2: Using Plain Android Default Trust Store (Expect failure if Apple Root CA missing/rejected)
        onLog("[SELF-TEST] Probe 2: Testing gsa.apple.com with plain Android system trust store...")
        try {
            val plainClient = OkHttpClient.Builder()
                .connectTimeout(10L, TimeUnit.SECONDS)
                .readTimeout(10L, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("https://gsa.apple.com/grandslam/GsService2")
                .header("User-Agent", "akd/1.0 CFNetwork/978.0.7 Darwin/18.7.0")
                .build()
            plainClient.newCall(request).execute().use { response ->
                onLog("[SELF-TEST] System trust store TLS handshake succeeded (HTTP ${response.code})")
            }
        } catch (e: Exception) {
            onLog("[SELF-TEST][EXPECTED_OR_NOT] System trust store rejected gsa.apple.com: ${e.javaClass.name}: ${e.message}")
        }

        // Test D: Anisette fetch probe
        onLog("--- Test D: Anisette Server Connectivity ---")
        try {
            val anisetteClient = AnisetteClient(timeoutSeconds = 8L)
            val result = anisetteClient.fetchAnisetteHeaders()
            result.onSuccess {
                onLog("[SELF-TEST][PASS] Anisette fetch succeeded from active server! (Headers received: OTP, MID, MachineID, LocalUDID, Locale - all redacted)")
            }.onFailure { err ->
                onLog("[SELF-TEST][FAIL] Anisette fetch failed: ${err.javaClass.name}: ${err.message}")
            }
        } catch (e: Exception) {
            onLog("[SELF-TEST][FAIL] Anisette probe error: ${e.message}")
        }

        onLog("==========================================")
        onLog("=== SELF-TESTS COMPLETED               ===")
        onLog("==========================================")
    }

    // ---- 6. Read-Only USB Scan ----
    fun scanUsbReadOnly(
        context: Context,
        usbManager: UsbManager,
        onLog: (String) -> Unit
    ) {
        onLog("==========================================")
        onLog("=== USB SCAN (READ-ONLY, NO CLAIM)     ===")
        onLog("==========================================")
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            onLog("[USB SCAN] No USB devices currently attached to this Android host.")
            return
        }

        onLog("[USB SCAN] Found ${deviceList.size} USB device(s):")
        deviceList.values.forEachIndexed { idx, device ->
            val isApple = device.vendorId == 0x05ac
            val vidHex = "0x" + Integer.toHexString(device.vendorId).uppercase()
            val pidHex = "0x" + Integer.toHexString(device.productId).uppercase()
            val hasPerm = usbManager.hasPermission(device)

            onLog("[USB SCAN] Device #$idx: ${device.productName ?: device.deviceName} (VID=$vidHex, PID=$pidHex, Apple=$isApple, Permission=$hasPerm)")
            onLog("[USB SCAN]   Configurations count: ${device.configurationCount}")

            if (!hasPerm) {
                onLog("[USB SCAN]   Permission NOT held. Tap 'Grant' or reconnect to view raw descriptors.")
                return@forEachIndexed
            }

            // Read descriptors and configurations WITHOUT claiming
            val conn = usbManager.openDevice(device)
            if (conn != null) {
                try {
                    val rawDesc = conn.rawDescriptors
                    val hexDesc = rawDesc?.joinToString("") { "%02X".format(it) } ?: "null"
                    val shortHex = if (hexDesc.length > 64) hexDesc.take(64) + "... [${rawDesc?.size ?: 0} bytes total]" else hexDesc
                    onLog("[USB SCAN]   RawDescriptors (hex): $shortHex")

                    // Active configuration via standard GET_CONFIGURATION (bmRequestType=0x80, bRequest=8)
                    val activeConfBuf = ByteArray(1)
                    val getConfRes = conn.controlTransfer(0x80, 8, 0, 0, activeConfBuf, 1, 1000)
                    val activeConfId = if (getConfRes == 1) (activeConfBuf[0].toInt() and 0xFF) else -1
                    onLog("[USB SCAN]   Active bConfigurationValue: ${if (activeConfId >= 0) activeConfId else "Unknown ($getConfRes)"}")

                    // Walk all configurations and interfaces
                    for (i in 0 until device.configurationCount) {
                        val config = device.getConfiguration(i)
                        onLog("[USB SCAN]   Config #$i (bConfigurationValue=${config.id}, name='${config.name ?: ""}', interfaces=${config.interfaceCount}):")
                        for (j in 0 until config.interfaceCount) {
                            val intf = config.getInterface(j)
                            val isUsbmux = intf.interfaceClass == 255 && intf.interfaceSubclass == 254 && intf.interfaceProtocol == 2
                            onLog("[USB SCAN]     Interface #$j (id=${intf.id}, class=${intf.interfaceClass}, subclass=${intf.interfaceSubclass}, proto=${intf.interfaceProtocol}, endpoints=${intf.endpointCount}${if (isUsbmux) " [USBMUX INTERFACE]" else ""})")
                            for (e in 0 until intf.endpointCount) {
                                val ep = intf.getEndpoint(e)
                                val dir = if (ep.direction == 0) "OUT" else "IN"
                                val type = when (ep.type) {
                                    0 -> "CONTROL"
                                    1 -> "ISOCHRONOUS"
                                    2 -> "BULK"
                                    3 -> "INTERRUPT"
                                    else -> "UNKNOWN"
                                }
                                onLog("[USB SCAN]       Endpoint #$e: addr=0x${Integer.toHexString(ep.address)}, dir=$dir, type=$type, maxPacket=${ep.maxPacketSize}")
                            }
                        }
                    }
                    onLog("[USB SCAN]   NOTE: Neither claimInterface nor setConfiguration was called (read-only scan).")
                } catch (e: Exception) {
                    onLog("[USB SCAN]   Error reading descriptors: ${e.message}")
                } finally {
                    conn.close()
                }
            } else {
                onLog("[USB SCAN]   openDevice returned null despite permission.")
            }
        }
    }
}

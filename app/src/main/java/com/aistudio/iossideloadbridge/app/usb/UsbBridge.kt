package com.aistudio.iossideloadbridge.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LockdownDeviceInfo(
    val deviceName: String = "",
    val productVersion: String = "",
    val productType: String = "",
    val udid: String = "",
    val errorCode: Int = 0,
    val errorMessage: String = "",
    val isPaired: Boolean = false,
    val needsTrust: Boolean = false
)

data class InstallProgress(
    val isInstalling: Boolean = false,
    val stage: String = "",
    val percent: Int = 0,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val rawErrorName: String? = null,
    val rawErrorDesc: String? = null,
    val plainErrorMessage: String? = null,
    val isComplete: Boolean = false
)

interface InstallProgressListener {
    fun onProgress(
        stage: String,
        percent: Int,
        bytesTransferred: Long,
        totalBytes: Long,
        errorName: String,
        errorDescription: String
    )
}

data class BridgeStatus(
    val isRunning: Boolean = false,
    val socketName: String = "usbmuxd_ios_bridge",
    val storageDir: String = "",
    val connectedDeviceName: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val isInterfaceClaimed: Boolean = false,
    val fd: Int = -1,
    val lockdownInfo: LockdownDeviceInfo? = null
)

/**
 * Kotlin USB bridge for communicating with connected iOS devices via usbmuxd and libimobiledevice.
 *
 * It requests USB host permission, claims the Apple usbmux interface (class=255, subclass=254, protocol=2),
 * and hands the file descriptor to the native layer where libusb wraps it and usbmuxd serves requests
 * over an abstract Unix domain socket.
 */
class UsbBridge(private val context: Context) {

    companion object {
        private const val TAG = "UsbBridge"
        private const val ACTION_USB_PERMISSION = "com.aistudio.iossideloadbridge.app.USB_PERMISSION"

        // Constants for Apple usbmuxd interface
        const val INTERFACE_CLASS = 255
        const val INTERFACE_SUBCLASS = 254
        const val INTERFACE_PROTOCOL = 2

        const val VID_APPLE = 0x05ac

        init {
            System.loadLibrary("usbmuxd_native")
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var openFd: Int = -1
    private var activeDevice: UsbDevice? = null
    private var autoPollJob: Job? = null

    private val _status = MutableStateFlow(
        BridgeStatus(storageDir = context.filesDir.absolutePath)
    )
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(
        com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.readSavedLogs(context)
    )
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    init {
        com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.installCrashHandler(context)
    }

    fun addLog(message: String) {
        val safeMessage = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(message)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val entry = "[$timestamp] $safeMessage"
        Log.i(TAG, safeMessage)
        com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.appendLog(context, entry)
        _logs.value = (_logs.value + entry).takeLast(100)
    }

    fun clearLogs() {
        _logs.value = emptyList()
        com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.clearSavedLogs(context)
        com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.clearLastCrash(context)
    }

    private val bridgeScope = CoroutineScope(Dispatchers.Main + Job())

    // ---- Native entry points implemented in usb_jni.c, lockdown_jni.c & install_jni.c ----
    private external fun nativeOnDeviceAttached(fd: Int, vendorId: Int, productId: Int): Int
    private external fun nativeOnDeviceDetached(fd: Int)
    private external fun nativeStartDaemon(socketName: String, storageDir: String): Int
    private external fun nativeStopDaemon()
    private external fun nativeGetLockdownDeviceInfo(storageDir: String): LockdownDeviceInfo
    private external fun nativeInstallIpa(storageDir: String, ipaPath: String, callback: Any): Int
    private external fun nativeCancelInstall()

    fun cancelInstall() {
        addLog("Cancelling IPA installation...")
        nativeCancelInstall()
    }

    fun mapInstProxyError(errorName: String, errorDescription: String): String {
        return when (errorName) {
            "ApplicationVerificationFailed" ->
                "iOS rejected the app signature (ApplicationVerificationFailed). The IPA is not signed with a certificate and mobileprovision valid for this device."
            "PackageInspectionFailed" ->
                "Package inspection failed. The IPA archive structure, Info.plist, or Mach-O executable may be invalid or corrupt."
            "MissingBundleExecutable" ->
                "Missing bundle executable: The app binary specified in Info.plist (CFBundleExecutable) was not found."
            "MissingBundleIdentifier" ->
                "Missing bundle identifier: Info.plist does not contain a CFBundleIdentifier."
            "DeviceOSVersionTooLow" ->
                "Device iOS version is too low to run this application."
            "DeviceFamilyNotSupported" ->
                "This device family is not supported by this application."
            "ApplicationAlreadyInstalled" ->
                "A conflicting version of this application is already installed on the device."
            "ContainerCreationFailed" ->
                "iOS failed to create the application sandbox container."
            "EmbeddedProfileInstallFailed" ->
                "Failed to install embedded provisioning profile on device."
            "SeatbeltProfileRemovalFailed" ->
                "Security sandbox violation while installing application."
            "APIInternalError" ->
                "Internal iOS installation daemon error (APIInternalError)."
            "NoDevice" ->
                "No iOS device detected via usbmuxd. Connect an unlocked iPhone."
            "PasswordProtected" ->
                "iPhone is locked with a passcode. Please unlock your iPhone."
            "PairingPending" ->
                "Pairing pending: Tap 'Trust' on your iPhone and enter your passcode."
            "UserDeniedPairing" ->
                "Pairing denied: 'Don't Trust' was selected on iPhone. Reconnect USB cable to retry."
            else -> {
                if (errorDescription.isNotEmpty()) {
                    "Installation error: $errorName ($errorDescription)"
                } else if (errorName.isNotEmpty()) {
                    "Installation error: $errorName"
                } else {
                    "Unknown installation error occurred."
                }
            }
        }
    }

    suspend fun installIpa(
        ipaFile: java.io.File,
        onProgressUpdate: (InstallProgress) -> Unit
    ): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val listener = object : InstallProgressListener {
            override fun onProgress(
                stage: String,
                percent: Int,
                bytesTransferred: Long,
                totalBytes: Long,
                errorName: String,
                errorDescription: String
            ) {
                val isError = stage == "ERROR" || stage == "INSTALL_ERROR"
                val isCancel = stage == "CANCELLED"
                val isComplete = stage == "COMPLETE"
                val plainMsg = if (isError) mapInstProxyError(errorName, errorDescription) else null

                val progress = InstallProgress(
                    isInstalling = !isError && !isCancel && !isComplete,
                    stage = stage,
                    percent = percent,
                    bytesTransferred = bytesTransferred,
                    totalBytes = totalBytes,
                    rawErrorName = errorName.ifEmpty { null },
                    rawErrorDesc = errorDescription.ifEmpty { null },
                    plainErrorMessage = plainMsg,
                    isComplete = isComplete
                )

                onProgressUpdate(progress)

                when {
                    isError -> addLog("Install ERROR: ${plainMsg ?: errorName}")
                    isCancel -> addLog("Install cancelled by user")
                    isComplete -> addLog("Install COMPLETE: Application successfully installed on iOS device!")
                    stage == "UPLOADING" -> {
                        if (percent % 25 == 0 || percent == 100) {
                            addLog("AFC Upload: $percent% (${bytesTransferred / 1024} KB / ${totalBytes / 1024} KB)")
                        }
                    }
                    else -> addLog("Install stage: $stage ($percent%)")
                }
            }
        }

        addLog("Beginning native IPA installation for ${ipaFile.name} (${ipaFile.length() / 1024} KB)...")
        nativeInstallIpa(context.filesDir.absolutePath, ipaFile.absolutePath, listener)
    }

    fun queryLockdownDeviceInfo() {
        autoPollJob?.cancel()
        bridgeScope.launch(Dispatchers.IO) {
            addLog("Attempting lockdown connection via libimobiledevice...")
            // Grace period: allow native usbmuxd daemon to complete USB interface claim and device registration
            delay(1200)
            try {
                var info = nativeGetLockdownDeviceInfo(context.filesDir.absolutePath)
                // If initial attempt returns code -3 (usbmuxd daemon still registering client), retry once after a brief pause
                if (info.errorCode == -3) {
                    delay(1200)
                    info = nativeGetLockdownDeviceInfo(context.filesDir.absolutePath)
                }
                _status.value = _status.value.copy(lockdownInfo = info)
                if (info.errorCode == 0 && info.isPaired) {
                    addLog("Lockdown SUCCESS: '${info.deviceName}' (${info.productType}, iOS ${info.productVersion})")
                    val shortUdid = if (info.udid.length > 16) "${info.udid.take(8)}...${info.udid.takeLast(6)}" else info.udid
                    addLog("Device UDID: $shortUdid")
                } else if (info.needsTrust) {
                    addLog("Lockdown trust required: ${info.errorMessage}")
                    // Automatically poll periodically while waiting for user to unlock passcode or tap Trust
                    startAutoPollForUnlock()
                } else {
                    addLog("Lockdown result: ${info.errorMessage} (code ${info.errorCode})")
                }
            } catch (e: Exception) {
                addLog("Lockdown query error: ${e.message}")
            }
        }
    }

    private fun startAutoPollForUnlock() {
        autoPollJob?.cancel()
        autoPollJob = bridgeScope.launch(Dispatchers.IO) {
            // Poll every 2.5 seconds up to 8 times (~20 seconds total)
            for (attempt in 1..8) {
                delay(2500)
                if (openFd < 0) break // Device detached
                try {
                    val info = nativeGetLockdownDeviceInfo(context.filesDir.absolutePath)
                    _status.value = _status.value.copy(lockdownInfo = info)
                    if (info.errorCode == 0 && info.isPaired) {
                        addLog("Lockdown SUCCESS: '${info.deviceName}' (${info.productType}, iOS ${info.productVersion})")
                        val shortUdid = if (info.udid.length > 16) "${info.udid.take(8)}...${info.udid.takeLast(6)}" else info.udid
                        addLog("Device UDID: $shortUdid")
                        break
                    }
                    if (!info.needsTrust) {
                        break
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    fun safeDeviceName(device: UsbDevice?): String {
        if (device == null) return "Unknown device"
        val prod = try {
            device.productName
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
        return prod ?: try { device.deviceName } catch (_: Throwable) { null } ?: "USB device (VID=0x${Integer.toHexString(device.vendorId)})"
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (ctx == null || intent == null) return
            try {
                if (intent.action != ACTION_USB_PERMISSION) return
                synchronized(this) {
                    val device: UsbDevice? = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    } catch (_: Throwable) { null }
                    val granted = try {
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    } catch (_: Throwable) { false }
                    val devName = safeDeviceName(device)
                    if (granted && device != null) {
                        addLog("USB permission granted for $devName")
                        openDevice(device)
                    } else {
                        addLog("USB permission denied for $devName")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in permissionReceiver: ${t.message}", t)
                addLog("[USB WARN] Permission receiver error: ${t.message}")
            }
        }
    }

    private val attachDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (ctx == null || intent == null) return
            try {
                val action = intent.action ?: return
                when (action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device: UsbDevice? = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Failed to extract EXTRA_DEVICE: ${e.message}")
                            null
                        }
                        if (device != null) {
                            val vid = try { device.vendorId } catch (_: Throwable) { 0 }
                            val isApple = vid == VID_APPLE
                            val devName = safeDeviceName(device)
                            addLog("Device attached: $devName (VID: 0x${Integer.toHexString(vid)}, Apple: $isApple)")
                            if (isApple) {
                                requestPermissionAndOpen(device)
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device: UsbDevice? = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }
                        } catch (_: Throwable) { null }
                        val devName = safeDeviceName(device)
                        addLog("Device detached: $devName")
                        closeCurrentDevice()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error handling USB attach/detach broadcast: ${t.message}", t)
                addLog("[USB WARN] Broadcast error: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** Call once, e.g. from your Application/Activity onCreate(). */
    fun start(abstractSocketName: String = "usbmuxd_ios_bridge") {
        if (_status.value.isRunning) {
            addLog("Daemon already running on socket: @$abstractSocketName")
            return
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        // NOTE: ACTION_USB_DEVICE_ATTACHED and DETACHED are system broadcasts from Android OS.
        // Android 13+ (API 33+) requires RECEIVER_EXPORTED for system broadcasts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(attachDetachReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(attachDetachReceiver, filter)
        }

        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permissionReceiver, permFilter)
        }

        val storagePath = context.filesDir.absolutePath
        val res = nativeStartDaemon(abstractSocketName, storagePath)
        addLog("nativeStartDaemon(@$abstractSocketName, storage=$storagePath) returned $res")

        _status.value = _status.value.copy(
            isRunning = (res == 0),
            socketName = abstractSocketName,
            storageDir = storagePath
        )

        scanAndConnect()
    }

    fun stop() {
        if (!_status.value.isRunning) return
        closeCurrentDevice()
        nativeStopDaemon()
        addLog("Native usbmuxd daemon stopped")

        _status.value = _status.value.copy(
            isRunning = false,
            connectedDeviceName = null,
            vendorId = null,
            productId = null,
            isInterfaceClaimed = false,
            fd = -1
        )

        runCatching { context.unregisterReceiver(attachDetachReceiver) }
        runCatching { context.unregisterReceiver(permissionReceiver) }
    }

    fun scanAndConnect() {
        val appleDevices = usbManager.deviceList.values.filter { it.vendorId == VID_APPLE }
        addLog("Scanning USB devices: found ${usbManager.deviceList.size} total, ${appleDevices.size} Apple device(s)")
        val target = appleDevices.firstOrNull()
        if (target != null) {
            requestPermissionAndOpen(target)
        }
    }

    fun requestPermission(device: UsbDevice) {
        requestPermissionAndOpen(device)
    }

    fun getDeviceList(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    private fun requestPermissionAndOpen(device: UsbDevice) {
        try {
            val devName = safeDeviceName(device)
            if (usbManager.hasPermission(device)) {
                addLog("USB permission already held for $devName")
                openDevice(device)
                return
            }
            addLog("Requesting USB permission for $devName...")
            val permIntent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(
                context, 0, permIntent, flags
            )
            runCatching {
                usbManager.requestPermission(device, pi)
            }.onFailure { reqErr ->
                Log.w(TAG, "usbManager.requestPermission call failed: ${reqErr.message}")
                addLog("[USB WARN] requestPermission failed: ${reqErr.message}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in requestPermissionAndOpen: ${t.message}", t)
            addLog("[USB WARN] Failed to request permission: ${t.message}")
        }
    }

    private fun closeCurrentDevice() {
        autoPollJob?.cancel()
        autoPollJob = null
        if (openFd >= 0) {
            nativeOnDeviceDetached(openFd)
            connection?.close()
            connection = null
            openFd = -1
            activeDevice = null
            _status.value = _status.value.copy(
                connectedDeviceName = null,
                vendorId = null,
                productId = null,
                isInterfaceClaimed = false,
                fd = -1,
                lockdownInfo = null
            )
            addLog("Device connection closed and native state cleaned up")
        }
    }

    private fun openDevice(device: UsbDevice) {
        try {
            val devName = safeDeviceName(device)
            if (openFd >= 0) {
                if (activeDevice?.deviceId == device.deviceId) {
                    addLog("[USB DIAG] Device already active and claimed (FD: $openFd). Skipping duplicate open.")
                    return
                }
                closeCurrentDevice()
            }
            addLog("[USB DIAG] Device discovered: $devName (VID=0x${Integer.toHexString(device.vendorId)}, PID=0x${Integer.toHexString(device.productId)})")
            addLog("[USB DIAG] Enumerating all ${device.configurationCount} USB configuration(s):")
            for (i in 0 until device.configurationCount) {
                val config = device.getConfiguration(i)
                addLog("[USB DIAG]   Configuration #$i: bConfigurationValue=${config.id}, name='${config.name ?: ""}', interfaces=${config.interfaceCount}")
                for (j in 0 until config.interfaceCount) {
                    val intf = config.getInterface(j)
                    addLog("[USB DIAG]     Interface #$j: id=${intf.id}, class=${intf.interfaceClass}, subclass=${intf.interfaceSubclass}, protocol=${intf.interfaceProtocol}, endpointCount=${intf.endpointCount}")
                }
            }

            // Find the usbmux interface (class=255, subclass=254, protocol=2) across all configurations
            var targetConfig: android.hardware.usb.UsbConfiguration? = null
            var targetInterface: UsbInterface? = null
            for (i in 0 until device.configurationCount) {
                val config = device.getConfiguration(i)
                for (j in 0 until config.interfaceCount) {
                    val intf = config.getInterface(j)
                    if (intf.interfaceClass == INTERFACE_CLASS &&
                        intf.interfaceSubclass == INTERFACE_SUBCLASS &&
                        intf.interfaceProtocol == INTERFACE_PROTOCOL
                    ) {
                        targetConfig = config
                        targetInterface = intf
                        break
                    }
                }
                if (targetInterface != null) break
            }

            if (targetInterface == null) {
                // Fallback to active configuration interfaces
                targetInterface = (0 until device.interfaceCount)
                    .map { device.getInterface(it) }
                    .firstOrNull {
                        it.interfaceClass == INTERFACE_CLASS &&
                        it.interfaceSubclass == INTERFACE_SUBCLASS &&
                        it.interfaceProtocol == INTERFACE_PROTOCOL
                    }
            }

            if (targetInterface == null) {
                addLog("[USB DIAG] Note: No usbmux (255/254/2) interface pre-matched by Java in active config. Passing fd to native usbmuxd to inspect all alternate raw configurations...")
            } else {
                addLog("[USB DIAG] Pre-matched usbmux interface in config #${targetConfig?.id ?: "active"}: id=${targetInterface.id}")
            }

            val conn = usbManager.openDevice(device)
            if (conn == null) {
                addLog("usbManager.openDevice() returned null for $devName (check USB permissions)")
                return
            }

            // Diagnostic: which config is active
            val activeConfigBuf = ByteArray(1)
            val getConfRes = conn.controlTransfer(0x80, 8, 0, 0, activeConfigBuf, 1, 1000)
            val initialConfigVal = if (getConfRes == 1) (activeConfigBuf[0].toInt() and 0xFF) else -1
            addLog("[USB DIAG] Initial USB configuration: ${if (initialConfigVal >= 0) "bConfigurationValue=$initialConfigVal" else "unknown (GET_CONFIGURATION returned $getConfRes)"} (active interfaceCount=${device.interfaceCount})")

            // Switch to the target configuration if needed
            var setConfigCalled = false
            if (targetConfig != null && initialConfigVal != targetConfig.id) {
                setConfigCalled = true
                addLog("[USB DIAG] Switching to configuration #${targetConfig.id} (holding usbmux interface)...")
                val setConfigRes = conn.setConfiguration(targetConfig)
                addLog("[USB DIAG] conn.setConfiguration(id=${targetConfig.id}) return value: $setConfigRes")

                // Re-read active configuration
                val newConfigBuf = ByteArray(1)
                val newConfRes = conn.controlTransfer(0x80, 8, 0, 0, newConfigBuf, 1, 1000)
                val newConfigVal = if (newConfRes == 1) (newConfigBuf[0].toInt() and 0xFF) else -1
                addLog("[USB DIAG] Active USB configuration after switch: ${if (newConfigVal >= 0) "bConfigurationValue=$newConfigVal" else "unknown ($newConfRes)"}")
            } else {
                addLog("[USB DIAG] setConfiguration called: $setConfigCalled (target config is already active or null)")
            }

            // Single-Owner USB Architecture: Java passes fd to native usbmuxd
            if (targetInterface != null) {
                addLog("[USB DIAG] Single-owner mode: Java bypassing conn.claimInterface(). Native libusb will claim interface ${targetInterface.id} exclusively.")
            } else {
                addLog("[USB DIAG] Single-owner mode: Native libusb will scan raw configurations and claim the usbmux interface exclusively.")
            }

            connection = conn
            openFd = conn.fileDescriptor
            activeDevice = device
            addLog("USB connection opened (fd=$openFd, vid=0x${Integer.toHexString(device.vendorId)}, pid=0x${Integer.toHexString(device.productId)}). Passing fd to native usbmuxd...")

            val res = nativeOnDeviceAttached(openFd, device.vendorId, device.productId)
            if (res == 0) {
                addLog("Native layer successfully wrapped fd=$openFd with libusb and registered device!")
                _status.value = _status.value.copy(
                    connectedDeviceName = devName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    isInterfaceClaimed = true,
                    fd = openFd
                )
                // Query lockdown device info via libimobiledevice
                queryLockdownDeviceInfo()
            } else {
                addLog("nativeOnDeviceAttached returned error: $res")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error opening USB device: ${t.message}", t)
            addLog("[USB ERROR] Exception opening device: ${t.message}")
        }
    }
}

package com.aistudio.iossideloadbridge.app.provisioning

import android.content.Context
import android.util.Log
import java.io.File

/**
 * High-performance, portable Mach-O code signer for Android.
 *
 * Backed by the cross-compiled C++ `signer_native` library (based on zsign / OpenSSL / minizip),
 * this handles:
 *  - Extracting the input `.ipa` archive
 *  - Parsing the embedded provisioning profile (`.mobileprovision`)
 *  - Extracting entitlements and generating DER entitlements
 *  - Recursively code-signing all embedded dynamic libraries, frameworks, app extensions, and the main Mach-O executable
 *  - Embedding the provisioning profile at `Payload/<App>.app/embedded.mobileprovision`
 *  - Computing the code directory hashes (`CDHash`), Info.plist hash, Requirements, and `CodeResources`
 *  - Generating detached CMS (PKCS#7) signatures with Apple developer certificates and private keys
 *  - Injecting `LC_CODE_SIGNATURE` load commands
 *  - Repackaging into a distribution-ready signed `.ipa`
 */
object IpaSigner {

    private const val TAG = "IpaSigner"

    private var isNativeLoaded = false
    private var nativeLoadError: String? = null

    init {
        try {
            System.loadLibrary("crypto")
            System.loadLibrary("signer_native")
            isNativeLoaded = true
            Log.i(TAG, "Successfully loaded native signing libraries (libcrypto.so, libsigner_native.so)")
        } catch (t: Throwable) {
            nativeLoadError = t.message ?: t.toString()
            Log.e(TAG, "Failed to load libsigner_native.so: $nativeLoadError", t)
        }
    }

    /**
     * Checks whether the native Mach-O signer shared library was successfully loaded.
     */
    fun isAvailable(): Boolean = isNativeLoaded

    /**
     * Retrieves the native library load error, if any.
     */
    fun getLoadError(): String? = nativeLoadError

    /**
     * Signs the IPA archive at [ipaPath] using the specified [certificatePem], [privateKeyPem],
     * and [provisioningProfileData].
     *
     * @param context Android context for resolving cache and temporary directories.
     * @param ipaPath Absolute path to the original, unsigned or re-signing target .ipa file.
     * @param certificatePem PEM-encoded Apple Development certificate.
     * @param privateKeyPem PEM-encoded RSA-2048 private key.
     * @param provisioningProfileData Raw byte array of the .mobileprovision profile.
     * @param outputIpaPath Optional destination path. If null, a new file in cache directory is created.
     * @param bundleId Optional bundle identifier override. If null/empty, extracts from provisioning profile.
     * @param appName Optional display name override. If null/empty, keeps existing CFBundleDisplayName.
     * @return Result containing the absolute path to the newly signed .ipa archive.
     */
    fun signIpa(
        context: Context,
        ipaPath: String,
        certificatePem: String,
        privateKeyPem: String,
        provisioningProfileData: ByteArray,
        outputIpaPath: String? = null,
        bundleId: String? = null,
        appName: String? = null
    ): Result<String> {
        if (!isNativeLoaded) {
            return Result.failure(
                IllegalStateException("Native code signer library is not loaded: $nativeLoadError")
            )
        }

        val ipaFile = File(ipaPath)
        if (!ipaFile.exists() || !ipaFile.canRead()) {
            return Result.failure(IllegalArgumentException("IPA file does not exist or cannot be read: $ipaPath"))
        }

        if (certificatePem.isBlank()) {
            return Result.failure(IllegalArgumentException("Certificate PEM cannot be empty"))
        }

        if (privateKeyPem.isBlank()) {
            return Result.failure(IllegalArgumentException("Private Key PEM cannot be empty"))
        }

        if (provisioningProfileData.isEmpty()) {
            return Result.failure(IllegalArgumentException("Provisioning profile data cannot be empty"))
        }

        val tempDir = File(context.cacheDir, "ipa_signer_work")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }

        val targetOutputPath = outputIpaPath ?: run {
            val signedDir = File(context.filesDir, "signed_apps").apply { mkdirs() }
            val baseName = ipaFile.nameWithoutExtension
            File(signedDir, "${baseName}_signed_${System.currentTimeMillis()}.ipa").absolutePath
        }

        return try {
            val result = nativeSignIpa(
                ipaPath = ipaPath,
                certPem = certificatePem,
                keyPem = privateKeyPem,
                provData = provisioningProfileData,
                outIpaPath = targetOutputPath,
                tempDir = tempDir.absolutePath,
                bundleId = bundleId ?: "",
                appName = appName ?: ""
            )

            if (result.startsWith("ERROR:")) {
                Result.failure(Exception(result.removePrefix("ERROR:").trim()))
            } else {
                val outFile = File(result)
                if (outFile.exists() && outFile.length() > 0) {
                    Result.success(result)
                } else {
                    Result.failure(Exception("Signed output file was not produced at $result"))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception during native IPA signing", t)
            Result.failure(t)
        }
    }

    /**
     * Inspects the code signature status of an .ipa archive or extracted Mach-O binary.
     */
    fun inspectSignature(ipaOrMachoPath: String): Result<String> {
        if (!isNativeLoaded) {
            return Result.failure(
                IllegalStateException("Native code signer library is not loaded: $nativeLoadError")
            )
        }
        val file = File(ipaOrMachoPath)
        if (!file.exists()) {
            return Result.failure(IllegalArgumentException("Target file does not exist: $ipaOrMachoPath"))
        }
        return try {
            val result = nativeInspectSignature(ipaOrMachoPath)
            if (result.startsWith("ERROR:")) {
                Result.failure(Exception(result.removePrefix("ERROR:").trim()))
            } else {
                Result.success(result)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // --- Native JNI declarations ---

    @JvmStatic
    private external fun nativeSignIpa(
        ipaPath: String,
        certPem: String,
        keyPem: String,
        provData: ByteArray,
        outIpaPath: String,
        tempDir: String,
        bundleId: String,
        appName: String
    ): String

    @JvmStatic
    private external fun nativeInspectSignature(
        ipaOrMachoPath: String
    ): String
}

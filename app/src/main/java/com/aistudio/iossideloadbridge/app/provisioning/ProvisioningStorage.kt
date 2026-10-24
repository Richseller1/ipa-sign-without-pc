package com.aistudio.iossideloadbridge.app.provisioning

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64 as JavaBase64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ProvisionedIdentity(
    val teamId: String,
    val teamName: String,
    val bundleId: String,
    val certificateId: String,
    val certificateName: String,
    val serialNumber: String,
    val certificatePem: String,
    val privateKeyPem: String,
    val profileName: String,
    val profileExpiration: String,
    val profileBytes: ByteArray,
    val isFreeAccount: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProvisionedIdentity
        return teamId == other.teamId && bundleId == other.bundleId && certificateId == other.certificateId
    }

    override fun hashCode(): Int {
        var result = teamId.hashCode()
        result = 31 * result + bundleId.hashCode()
        result = 31 * result + certificateId.hashCode()
        return result
    }
}

/**
 * Manages encrypted persistence of the developer signing certificate, RSA private key,
 * and downloaded .mobileprovision profile.
 *
 * Stored encrypted with AES-256-GCM in the app's no-backup directory
 * (`context.noBackupFilesDir/apple_provisioning.enc`), excluded from Android Auto Backup.
 * Master key is managed in hardware-backed Android KeyStore.
 */
class ProvisioningStorage(private val context: Context) {

    companion object {
        private const val TAG = "ProvisioningStorage"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "AppleProvisioningKey_v1"
        private const val FILE_NAME = "apple_provisioning.enc"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128

        private fun base64Encode(bytes: ByteArray): String {
            return try {
                JavaBase64.getEncoder().encodeToString(bytes)
            } catch (_: Throwable) {
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        }

        private fun base64Decode(str: String): ByteArray {
            return try {
                JavaBase64.getDecoder().decode(str)
            } catch (_: Throwable) {
                android.util.Base64.decode(str, android.util.Base64.DEFAULT)
            }
        }
    }

    private val storageFile: File
        get() = File(context.noBackupFilesDir ?: context.filesDir, FILE_NAME)

    private val legacyStorageFile: File
        get() = File(context.filesDir, FILE_NAME)

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun saveIdentity(identity: ProvisionedIdentity) {
        try {
            val json = JSONObject().apply {
                put("teamId", identity.teamId)
                put("teamName", identity.teamName)
                put("bundleId", identity.bundleId)
                put("certificateId", identity.certificateId)
                put("certificateName", identity.certificateName)
                put("serialNumber", identity.serialNumber)
                put("certificatePem", identity.certificatePem)
                put("privateKeyPem", identity.privateKeyPem)
                put("profileName", identity.profileName)
                put("profileExpiration", identity.profileExpiration)
                put("profileBytesB64", base64Encode(identity.profileBytes))
                put("isFreeAccount", identity.isFreeAccount)
                put("createdAt", identity.createdAt)
                remove("password")
            }
            val plaintext = json.toString().toByteArray(Charsets.UTF_8)

            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)

            val encryptedBytes = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, encryptedBytes, 0, iv.size)
            System.arraycopy(ciphertext, 0, encryptedBytes, iv.size, ciphertext.size)

            storageFile.parentFile?.mkdirs()
            storageFile.writeBytes(encryptedBytes)
            if (legacyStorageFile.exists() && legacyStorageFile.absolutePath != storageFile.absolutePath) {
                legacyStorageFile.delete()
            }
            val safeMsg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                "Saved encrypted provisioning identity for bundle ${identity.bundleId} in noBackupFilesDir"
            )
            Log.i(TAG, safeMsg)
        } catch (e: Exception) {
            val errMsg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                "Failed to save encrypted provisioning identity: ${e.message}"
            )
            Log.e(TAG, errMsg)
        }
    }

    fun loadIdentity(): ProvisionedIdentity? {
        val targetFile = when {
            storageFile.exists() -> storageFile
            legacyStorageFile.exists() -> legacyStorageFile
            else -> return null
        }
        return try {
            val encryptedBytes = targetFile.readBytes()
            if (encryptedBytes.size <= GCM_IV_LENGTH) return null

            val iv = ByteArray(GCM_IV_LENGTH)
            System.arraycopy(encryptedBytes, 0, iv, 0, GCM_IV_LENGTH)
            val ciphertext = ByteArray(encryptedBytes.size - GCM_IV_LENGTH)
            System.arraycopy(encryptedBytes, GCM_IV_LENGTH, ciphertext, 0, ciphertext.size)

            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decryptedBytes = cipher.doFinal(ciphertext)

            val json = JSONObject(String(decryptedBytes, Charsets.UTF_8))
            json.remove("password")
            val loaded = ProvisionedIdentity(
                teamId = json.getString("teamId"),
                teamName = json.optString("teamName", ""),
                bundleId = json.getString("bundleId"),
                certificateId = json.optString("certificateId", ""),
                certificateName = json.optString("certificateName", ""),
                serialNumber = json.optString("serialNumber", ""),
                certificatePem = json.getString("certificatePem"),
                privateKeyPem = json.getString("privateKeyPem"),
                profileName = json.optString("profileName", "Team Provisioning Profile"),
                profileExpiration = json.optString("profileExpiration", "7 days"),
                profileBytes = base64Decode(json.optString("profileBytesB64", "")),
                isFreeAccount = json.optBoolean("isFreeAccount", true),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
            if (targetFile.absolutePath != storageFile.absolutePath) {
                saveIdentity(loaded)
                legacyStorageFile.delete()
            }
            loaded
        } catch (e: Exception) {
            val warnMsg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                "Failed to load provisioning identity: ${e.message}. Removing corrupt file."
            )
            Log.w(TAG, warnMsg)
            clearIdentity()
            null
        }
    }

    fun clearIdentity() {
        try {
            if (storageFile.exists()) {
                storageFile.delete()
            }
            if (legacyStorageFile.exists()) {
                legacyStorageFile.delete()
            }
            val msg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                "Cleared stored provisioning identity."
            )
            Log.i(TAG, msg)
        } catch (e: Exception) {
            val warnMsg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                "Failed to delete provisioning file: ${e.message}"
            )
            Log.w(TAG, warnMsg)
        }
    }
}

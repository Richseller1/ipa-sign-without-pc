package com.aistudio.iossideloadbridge.app.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppleAuthSession(
    val appleId: String,
    val dsid: String = "",
    val adsid: String = "",
    val pet: String = "",
    val tokens: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Manages encrypted persistence of Apple authentication sessions.
 * Tokens (including the Password Equivalent Token 'pet') are stored encrypted with AES-256-GCM
 * in the app's no-backup directory (`context.noBackupFilesDir/apple_session.enc`), which Android
 * automatically excludes from Auto Backup and device-to-device transfer.
 * The encryption key is protected inside the hardware-backed Android KeyStore.
 * Account passwords are never stored.
 */
class AuthSessionStorage(private val context: Context) {

    companion object {
        private const val TAG = "AuthSessionStorage"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "AppleAuthSessionKey_v1"
        private const val FILE_NAME = "apple_session.enc"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val sessionFile: File
        get() = File(context.noBackupFilesDir ?: context.filesDir, FILE_NAME)

    private val legacySessionFile: File
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

    /**
     * Saves the authenticated session automatically and securely using Android KeyStore AES-GCM
     * in noBackupFilesDir (excluded from Android backup). Never stores the account password.
     */
    fun saveSession(session: AppleAuthSession) {
        try {
            val json = JSONObject().apply {
                put("appleId", session.appleId)
                put("dsid", session.dsid)
                put("adsid", session.adsid)
                put("pet", session.pet)
                put("createdAt", session.createdAt)
                val tokensObj = JSONObject()
                session.tokens.forEach { (k, v) ->
                    if (!k.equals("password", ignoreCase = true)) {
                        tokensObj.put(k, v)
                    }
                }
                put("tokens", tokensObj)
                remove("password")
            }
            val plaintext = json.toString().toByteArray(Charsets.UTF_8)

            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)

            // Layout on disk: [12 bytes IV] + [Ciphertext + Tag]
            val encryptedBytes = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, encryptedBytes, 0, iv.size)
            System.arraycopy(ciphertext, 0, encryptedBytes, iv.size, ciphertext.size)

            sessionFile.parentFile?.mkdirs()
            sessionFile.writeBytes(encryptedBytes)
            if (legacySessionFile.exists() && legacySessionFile.absolutePath != sessionFile.absolutePath) {
                legacySessionFile.delete()
            }
            val safeMsg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                "Persisted encrypted Apple session for ${session.appleId} in noBackupFilesDir"
            )
            Log.i(TAG, safeMsg)
            com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.logAuthTrace(safeMsg)
        } catch (e: Exception) {
            val errMsg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                "Failed to save encrypted Apple session: ${e.message}"
            )
            Log.e(TAG, errMsg)
        }
    }

    /**
     * Loads and decrypts the persisted session from noBackupFilesDir (or migrates from legacy filesDir).
     */
    fun loadSession(): AppleAuthSession? {
        val targetFile = when {
            sessionFile.exists() -> sessionFile
            legacySessionFile.exists() -> legacySessionFile
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
            val tokensMap = mutableMapOf<String, String>()
            if (json.has("tokens")) {
                val tokensObj = json.getJSONObject("tokens")
                tokensObj.keys().forEach { k ->
                    if (!k.equals("password", ignoreCase = true)) {
                        tokensMap[k] = tokensObj.getString(k)
                    }
                }
            }

            val loaded = AppleAuthSession(
                appleId = json.getString("appleId"),
                dsid = json.optString("dsid", ""),
                adsid = json.optString("adsid", ""),
                pet = json.optString("pet", ""),
                tokens = tokensMap,
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
            if (targetFile.absolutePath != sessionFile.absolutePath) {
                saveSession(loaded)
                legacySessionFile.delete()
            }
            loaded
        } catch (e: Exception) {
            val warnMsg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                "Failed to decrypt saved Apple session: ${e.message}. Removing corrupt file."
            )
            Log.w(TAG, warnMsg)
            clearSession()
            null
        }
    }

    /**
     * Clears and deletes the stored session from disk.
     */
    fun clearSession() {
        try {
            if (sessionFile.exists()) {
                sessionFile.delete()
            }
            if (legacySessionFile.exists()) {
                legacySessionFile.delete()
            }
            val msg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                "Apple authentication session cleared from storage."
            )
            Log.i(TAG, msg)
        } catch (e: Exception) {
            val warnMsg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                "Error deleting session file: ${e.message}"
            )
            Log.w(TAG, warnMsg)
        }
    }
}

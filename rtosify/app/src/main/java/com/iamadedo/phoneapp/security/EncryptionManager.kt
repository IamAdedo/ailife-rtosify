package com.iamadedo.phoneapp.security

import android.content.Context
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException


/**
 * Manages encryption/decryption for WiFi communication using Google Tink.
 * 
 * Key exchange happens during Bluetooth pairing and keys are stored securely
 * in EncryptedSharedPreferences, keyed by Bluetooth MAC address.
 */
class EncryptionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "EncryptionManager"
        private const val KEYSET_NAME = "rtosify_wifi_v2_keyset"
        private const val PREF_FILE_NAME = "rtosify_encryption_keys"
        private const val MASTER_KEY_ALIAS = "rtosify_master_key"
    }

    private var aead: Aead? = null
    private val keysetHandles = mutableMapOf<String, KeysetHandle>()

    init {
        try {
            AeadConfig.register()
            Log.d(TAG, "Tink AEAD config registered")
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Failed to register Tink AEAD config", e)
        }
    }
    
    /**
     * Check if a keyset exists for a specific device.
     */
    fun hasKey(deviceMac: String): Boolean {
        val normalizedMac = deviceMac.uppercase()
        val prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        return prefs.contains("${KEYSET_NAME}_$normalizedMac") || keysetHandles.containsKey(normalizedMac)
    }

    /**
     * Initialize or load encryption keyset for a specific device (by MAC address).
     * This should be called during Bluetooth pairing to generate/exchange keys.
     */
    fun initializeForDevice(deviceMac: String, autoGenerate: Boolean = false): Boolean {
        val normalizedMac = deviceMac.uppercase()
        return try {
            val prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
            var json = prefs.getString("${KEYSET_NAME}_$normalizedMac", null)
            
            if (json == null) {
                if (autoGenerate) {
                    Log.i(TAG, "Generating new keyset for device: $deviceMac")
                    val handle = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)
                    val outputStream = ByteArrayOutputStream()
                    CleartextKeysetHandle.write(handle, JsonKeysetWriter.withOutputStream(outputStream))
                    json = outputStream.toString("UTF-8")
                    prefs.edit().putString("${KEYSET_NAME}_$normalizedMac", json).apply()
                } else {
                    Log.w(TAG, "No encryption key found for device: $deviceMac. Re-pairing required.")
                    return false
                }
            }

            var keysetHandle: KeysetHandle? = null
            try {
                keysetHandle = CleartextKeysetHandle.read(JsonKeysetReader.withString(json))
            } catch (e: Exception) {
                Log.e(TAG, "Malformed keyset for $deviceMac. Re-pairing required.", e)
                return false
            }

            keysetHandles[normalizedMac] = keysetHandle!!
            Log.d(TAG, "Initialized encryption for device: $normalizedMac")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encryption for $normalizedMac", e)
            false
        }
    }

    /**
     * Set the active device for encryption/decryption operations.
     */
    fun setActiveDevice(deviceMac: String): Boolean {
        val normalizedMac = deviceMac.uppercase()
        val keysetHandle = keysetHandles[normalizedMac] ?: run {
            // Try to load existing keyset
            if (!initializeForDevice(normalizedMac)) {
                return false
            }
            keysetHandles[normalizedMac]
        }

        return try {
            aead = keysetHandle?.getPrimitive(Aead::class.java)
            Log.d(TAG, "Set active device: $deviceMac")
            true
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Failed to get AEAD primitive for $deviceMac", e)
            false
        }
    }

    /**
     * Encrypt data for WiFi transmission.
     * 
     * @param plaintext The data to encrypt
     * @param associatedData Optional associated data for authentication (not encrypted)
     * @return Encrypted ciphertext or null if encryption fails
     */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray? {
        val currentAead = aead
        if (currentAead == null) {
            Log.e(TAG, "Cannot encrypt: No active device set")
            return null
        }

        return try {
            currentAead.encrypt(plaintext, associatedData)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Encryption failed", e)
            null
        }
    }

    /**
     * Encrypt data for a specific device.
     */
    fun encryptForDevice(deviceMac: String, plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray? {
        val normalizedMac = deviceMac.uppercase()
        val handle = keysetHandles[normalizedMac]
        if (handle == null) {
            Log.e(TAG, "Cannot encrypt: No keyset for $normalizedMac")
            return null
        }
        
        return try {
            val a = handle.getPrimitive(Aead::class.java)
            a.encrypt(plaintext, associatedData)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Encryption failed for $deviceMac", e)
            null
        }
    }

    /**
     * Decrypt data received over WiFi.
     * 
     * @param ciphertext The encrypted data
     * @param associatedData Optional associated data (must match encryption)
     * @return Decrypted plaintext or null if decryption fails
     */
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray? {
        val currentAead = aead
        if (currentAead == null) {
            Log.e(TAG, "Cannot decrypt: No active device set")
            return null
        }

        return try {
            currentAead.decrypt(ciphertext, associatedData)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }

    /**
     * Decrypt data from a specific device.
     */
    fun decryptForDevice(deviceMac: String, ciphertext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray? {
        val normalizedMac = deviceMac.uppercase()
        val handle = keysetHandles[normalizedMac]
        if (handle == null) {
            Log.e(TAG, "Cannot decrypt: No keyset for $normalizedMac")
            return null
        }
        
        return try {
            val a = handle.getPrimitive(Aead::class.java)
            Log.v(TAG, "Attempting decryption for $normalizedMac, ciphertext size: ${ciphertext.size}")
            a.decrypt(ciphertext, associatedData)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Decryption failed for $normalizedMac. Ciphertext size: ${ciphertext.size}", e)
            null
        }
    }

    /**
     * Remove encryption keys for a specific device.
     * Call this when un-pairing a device.
     */
    fun removeDeviceKeys(deviceMac: String) {
        val normalizedMac = deviceMac.uppercase()
        keysetHandles.remove(normalizedMac)
        
        // Remove from SharedPreferences
        val prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("${KEYSET_NAME}_$normalizedMac").apply()
        
        Log.d(TAG, "Removed encryption keys for device: $normalizedMac")
    }

    /**
     * Export the keyset for a specific device as a Base64 string.
     * Use this to send the key to the other device via Bluetooth.
     */
    fun exportKey(deviceMac: String): String? {
        val normalizedMac = deviceMac.uppercase()
        val keysetHandle = keysetHandles[normalizedMac] ?: run {
            if (!initializeForDevice(normalizedMac, autoGenerate = true)) return null
            keysetHandles[normalizedMac]
        } ?: return null

        return try {
            val outputStream = ByteArrayOutputStream()
            CleartextKeysetHandle.write(keysetHandle, JsonKeysetWriter.withOutputStream(outputStream))
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export key for $deviceMac", e)
            null
        }
    }

    /**
     * Import a keyset for a specific device from a Base64 string.
     * Use this to receive the key from the other device via Bluetooth.
     */
    fun importKey(deviceMac: String, keyData: String): Boolean {
        val normalizedMac = deviceMac.uppercase()
        return try {
            val keyBytes = Base64.decode(keyData, Base64.NO_WRAP)
            val inputStream = ByteArrayInputStream(keyBytes)
            val keysetHandle = CleartextKeysetHandle.read(JsonKeysetReader.withInputStream(inputStream))

            // Save to persistent storage
            val prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
            val jsonStream = ByteArrayOutputStream()
            CleartextKeysetHandle.write(keysetHandle, JsonKeysetWriter.withOutputStream(jsonStream))
            prefs.edit().putString("${KEYSET_NAME}_$normalizedMac", jsonStream.toString("UTF-8")).apply()

            keysetHandles[normalizedMac] = keysetHandle
            Log.d(TAG, "Imported and saved encryption key for device: $normalizedMac")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import key for $normalizedMac", e)
            false
        }
    }
}

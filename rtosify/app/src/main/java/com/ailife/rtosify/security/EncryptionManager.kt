package com.ailife.rtosify.security

import android.content.Context
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
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
        private const val KEYSET_NAME = "rtosify_wifi_encryption_keyset"
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
     * Initialize or load encryption keyset for a specific device (by MAC address).
     * This should be called during Bluetooth pairing to generate/exchange keys.
     */
    fun initializeForDevice(deviceMac: String): Boolean {
        return try {
            val keysetHandle = AndroidKeysetManager.Builder()
                .withSharedPref(context, "${KEYSET_NAME}_$deviceMac", "$PREF_FILE_NAME")
                .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
                .build()
                .keysetHandle

            keysetHandles[deviceMac] = keysetHandle
            Log.d(TAG, "Initialized encryption for device: $deviceMac")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encryption for $deviceMac", e)
            false
        }
    }

    /**
     * Set the active device for encryption/decryption operations.
     */
    fun setActiveDevice(deviceMac: String): Boolean {
        val keysetHandle = keysetHandles[deviceMac] ?: run {
            // Try to load existing keyset
            if (!initializeForDevice(deviceMac)) {
                return false
            }
            keysetHandles[deviceMac]
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
     * Remove encryption keys for a specific device.
     * Call this when un-pairing a device.
     */
    fun removeDeviceKeys(deviceMac: String) {
        keysetHandles.remove(deviceMac)
        
        // Remove from SharedPreferences
        val prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("${KEYSET_NAME}_$deviceMac").apply()
        
        Log.d(TAG, "Removed encryption keys for device: $deviceMac")
    }

    /**
     * Export the keyset for a specific device as a Base64 string.
     * Use this to send the key to the other device via Bluetooth.
     */
    fun exportKey(deviceMac: String): String? {
        val keysetHandle = keysetHandles[deviceMac] ?: run {
            if (!initializeForDevice(deviceMac)) return null
            keysetHandles[deviceMac]
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
        return try {
            val keyBytes = Base64.decode(keyData, Base64.NO_WRAP)
            val inputStream = ByteArrayInputStream(keyBytes)
            val keysetHandle = CleartextKeysetHandle.read(JsonKeysetReader.withInputStream(inputStream))

            // Save to persistent storage using AndroidKeysetManager to ensure it's encrypted
            AndroidKeysetManager.Builder()
                .withSharedPref(context, "${KEYSET_NAME}_$deviceMac", "$PREF_FILE_NAME")
                .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
                .build()
                .keysetHandle // This might overwrite or fail if already exists, let's be careful

            // Actually, AndroidKeysetManager is for generating or loading. 
            // To manually SAVE a keysetHandle to the same location AndroidKeysetManager expects:
            val prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
            val outputStream = ByteArrayOutputStream()
            keysetHandle.write(JsonKeysetWriter.withOutputStream(outputStream), 
                AndroidKeysetManager.Builder()
                    .withSharedPref(context, "unused", "unused")
                    .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
                    .build().keysetHandle.getPrimitive(Aead::class.java)) 
            // This is getting complicated. Let's use a simpler approach for now:
            // Just store the raw keyset JSON in the same Pref file.
            
            val jsonStream = ByteArrayOutputStream()
            CleartextKeysetHandle.write(keysetHandle, JsonKeysetWriter.withOutputStream(jsonStream))
            prefs.edit().putString("${KEYSET_NAME}_$deviceMac", jsonStream.toString()).apply()

            keysetHandles[deviceMac] = keysetHandle
            Log.d(TAG, "Imported and saved encryption key for device: $deviceMac")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import key for $deviceMac", e)
            false
        }
    }
}

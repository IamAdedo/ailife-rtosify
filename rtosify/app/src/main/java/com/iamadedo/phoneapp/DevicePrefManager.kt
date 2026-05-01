package com.iamadedo.phoneapp

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class PairedDevice(val name: String, val mac: String)

class DevicePrefManager(private val context: Context) {

    private val GSON = Gson()
    private val GLOBAL_PREFS = "AppPrefs"
    private val KEY_PAIRED_DEVICES = "paired_devices"
    private val KEY_SELECTED_MAC = "selected_device_mac"
    private val KEY_LAST_MAC = "last_mac" // Legacy compatibility

    fun getGlobalPrefs(): SharedPreferences {
        return context.getSharedPreferences(GLOBAL_PREFS, Context.MODE_PRIVATE)
    }

    fun getDevicePrefs(mac: String): SharedPreferences {
        val normalizedMac = mac.uppercase()
        val fileName = "device_${normalizedMac.replace(":", "")}"
        return context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    fun getActiveDevicePrefs(): SharedPreferences {
        val mac = getSelectedDeviceMac() ?: return getGlobalPrefs()
        return getDevicePrefs(mac)
    }

    fun getSelectedDeviceMac(): String? {
        val prefs = getGlobalPrefs()
        return prefs.getString(KEY_SELECTED_MAC, prefs.getString(KEY_LAST_MAC, null))?.uppercase()
    }

    fun setSelectedDeviceMac(mac: String) {
        val normalizedMac = mac.uppercase()
        android.util.Log.d("DevicePrefManager", "setSelectedDeviceMac: $normalizedMac")
        getGlobalPrefs().edit().putString(KEY_SELECTED_MAC, normalizedMac).commit()
    }

    fun getPairedDevices(): List<PairedDevice> {
        val json = getGlobalPrefs().getString(KEY_PAIRED_DEVICES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PairedDevice>>() {}.type
            val rawList = GSON.fromJson<List<PairedDevice>>(json, type)
            // Normalize and de-duplicate by MAC
            rawList.map { it.copy(mac = it.mac.uppercase()) }
                   .distinctBy { it.mac }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addPairedDevice(name: String, mac: String) {
        val normalizedMac = mac.uppercase()
        val devices = getPairedDevices().toMutableList()
        // remove any existing with same MAC to ensure we update the name if it's different and de-duplicate
        devices.removeAll { it.mac == normalizedMac }
        devices.add(PairedDevice(name, normalizedMac))
        savePairedDevices(devices)
    }

    private fun savePairedDevices(devices: List<PairedDevice>) {
        // Enforce normalization and de-duplication before saving
        val normalizedDevices = devices.map { it.copy(mac = it.mac.uppercase()) }
                                      .distinctBy { it.mac }
        val json = GSON.toJson(normalizedDevices)
        android.util.Log.d("DevicePrefManager", "savePairedDevices: saving ${normalizedDevices.size} devices. JSON length: ${json.length}")
        val success = getGlobalPrefs().edit().putString(KEY_PAIRED_DEVICES, json).commit()
        if (!success) {
            android.util.Log.e("DevicePrefManager", "savePairedDevices: FAILED TO COMMIT TO DISK")
        }
    }

    fun removePairedDevice(mac: String) {
        val normalizedMac = mac.uppercase()
        val devices = getPairedDevices().toMutableList()
        devices.removeAll { it.mac == normalizedMac }
        savePairedDevices(devices)
        if (getSelectedDeviceMac() == normalizedMac) {
            getGlobalPrefs().edit().remove(KEY_SELECTED_MAC).apply()
        }
    }

    fun updateDeviceName(mac: String, newName: String) {
        val normalizedMac = mac.uppercase()
        val devices = getPairedDevices().toMutableList()
        val index = devices.indexOfFirst { it.mac == normalizedMac }
        if (index != -1) {
            val device = devices[index]
            if (device.name != newName) {
                devices[index] = PairedDevice(newName, normalizedMac)
                savePairedDevices(devices)
                android.util.Log.d("DevicePrefManager", "Updated device name for $normalizedMac to $newName")
            }
        } else {
            // Upsert: If not in list, add it
            devices.add(PairedDevice(newName, normalizedMac))
            savePairedDevices(devices)
            android.util.Log.d("DevicePrefManager", "Upserted device name for $normalizedMac as $newName")
        }
    }
}

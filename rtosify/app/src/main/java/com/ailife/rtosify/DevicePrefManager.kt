package com.ailife.rtosify

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
        val fileName = "device_${mac.replace(":", "")}"
        return context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    fun getActiveDevicePrefs(): SharedPreferences {
        val mac = getSelectedDeviceMac() ?: return getGlobalPrefs()
        return getDevicePrefs(mac)
    }

    fun getSelectedDeviceMac(): String? {
        val prefs = getGlobalPrefs()
        return prefs.getString(KEY_SELECTED_MAC, prefs.getString(KEY_LAST_MAC, null))
    }

    fun setSelectedDeviceMac(mac: String) {
        getGlobalPrefs().edit().putString(KEY_SELECTED_MAC, mac).apply()
    }

    fun getPairedDevices(): List<PairedDevice> {
        val json = getGlobalPrefs().getString(KEY_PAIRED_DEVICES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PairedDevice>>() {}.type
            GSON.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addPairedDevice(name: String, mac: String) {
        val devices = getPairedDevices().toMutableList()
        if (devices.none { it.mac == mac }) {
            devices.add(PairedDevice(name, mac))
            savePairedDevices(devices)
        }
    }

    private fun savePairedDevices(devices: List<PairedDevice>) {
        val json = GSON.toJson(devices)
        getGlobalPrefs().edit().putString(KEY_PAIRED_DEVICES, json).apply()
    }

    fun removePairedDevice(mac: String) {
        val devices = getPairedDevices().toMutableList()
        devices.removeAll { it.mac == mac }
        savePairedDevices(devices)
        if (getSelectedDeviceMac() == mac) {
            getGlobalPrefs().edit().remove(KEY_SELECTED_MAC).apply()
        }
    }
}

package com.ailife.rtosifycompanion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * Utility to manage Bluetooth connections and PAN (Personal Area Networking) profile.
 */
class BluetoothPanManager(private val context: Context) {
    companion object {
        private const val TAG = "BluetoothPanManager"
        private const val PAN_PROFILE = 5 // BluetoothProfile.PAN
    }

    private var bluetoothPan: BluetoothProfile? = null
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == PAN_PROFILE) {
                bluetoothPan = proxy
                Log.d(TAG, "Bluetooth PAN profile connected")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == PAN_PROFILE) {
                bluetoothPan = null
                Log.d(TAG, "Bluetooth PAN profile disconnected")
            }
        }
    }

    init {
        bluetoothAdapter?.getProfileProxy(context, profileListener, PAN_PROFILE)
    }

    /**
     * Checks if a device is connected to the PAN profile.
     * Returns true if the device is in CONNECTED state (state == 2).
     */
    @SuppressLint("MissingPermission")
    fun isPanConnected(device: BluetoothDevice): Boolean {
        val pan = bluetoothPan ?: run {
            Log.d(TAG, "PAN profile proxy not available")
            return false
        }

        return try {
            val getConnectionState = findHiddenMethod(pan.javaClass, "getConnectionState",
                BluetoothDevice::class.java)
            if (getConnectionState != null) {
                getConnectionState.isAccessible = true
                val state = getConnectionState.invoke(pan, device) as? Int ?: 0
                // STATE_CONNECTED = 2
                Log.d(TAG, "PAN connection state for ${device.address}: $state")
                state == 2
            } else {
                Log.e(TAG, "Could not find getConnectionState method")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get PAN connection state: ${e.message}")
            false
        }
    }

    /**
     * Searches for a hidden method by name and parameter types recursively through the class hierarchy.
     */
    private fun findHiddenMethod(clazz: Class<*>, methodName: String, vararg params: Class<*>): Method? {
        var currentClass: Class<*>? = clazz
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredMethod(methodName, *params)
            } catch (e: NoSuchMethodException) {
                // Ignore and move to superclass
            }
            currentClass = currentClass.superclass
        }
        return null
    }

    fun close() {
        bluetoothPan?.let {
            bluetoothAdapter?.closeProfileProxy(PAN_PROFILE, it)
        }
    }
}

package com.ailife.rtosify.communication

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Handles mDNS service discovery for finding paired devices on the local network.
 * Service type: _rtosify._tcp.local.
 */
class MdnsDiscovery(private val context: Context) {
    
    companion object {
        private const val TAG = "MdnsDiscovery"
        private const val SERVICE_TYPE = "_rtosify._tcp"
        private const val SERVICE_NAME_PREFIX = "RTOSify"
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    
    private val _discoveredServices = MutableSharedFlow<ServiceInfo>(replay = 1, extraBufferCapacity = 10)
    private val discoveredServicesCache = mutableMapOf<String, ServiceInfo>()
    
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null

    private var isDiscovering = false
    private var isRegistered = false

    data class ServiceInfo(
        val deviceMac: String,
        val deviceName: String,
        val host: String,
        val port: Int
    )

    /**
     * Register this device as an mDNS service so others can discover it.
     * 
     * @param deviceMac The Bluetooth MAC address (used as unique identifier)
     * @param port The TCP port to advertise
     */
    fun registerService(deviceMac: String, deviceName: String, port: Int) {
        if (isRegistered) {
            Log.w(TAG, "Service already registered, unregistering first")
            unregisterService()
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${SERVICE_NAME_PREFIX}_${deviceMac.replace(":", "")}"
            serviceType = SERVICE_TYPE
            setPort(port)
            
            // Store device info in TXT records
            setAttribute("mac", deviceMac)
            setAttribute("name", deviceName)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Service registration failed: $errorCode")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed: $errorCode")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "Service registered: ${serviceInfo?.serviceName}")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "Service unregistered: ${serviceInfo?.serviceName}")
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        isRegistered = true
    }

    private fun unregisterService() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering service", e)
        }
        registrationListener = null
        isRegistered = false
    }

    /**
     * Start discovering mDNS services on the local network.
     */
    fun startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Discovery already running")
            return
        }
        
        // Clear cached services to avoid stale IPs after network changes
        discoveredServicesCache.clear()
        Log.d(TAG, "Cleared mDNS service cache")
        
        isDiscovering = true
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "Discovery started for: $serviceType")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onServiceFound(service: NsdServiceInfo?) {
                Log.d(TAG, "Service found: ${service?.serviceName}")
                service?.let { resolveService(it) }
            }

            override fun onServiceLost(service: NsdServiceInfo?) {
                Log.d(TAG, "Service lost: ${service?.serviceName}")
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }
    /**
     * Resolve a discovered service to get its host and port.
     */
    private fun resolveService(service: NsdServiceInfo) {
        resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode for ${serviceInfo?.serviceName}")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { info ->
                    val mac = info.attributes?.get("mac")?.let { String(it) }
                    val name = info.attributes?.get("name")?.let { String(it) } ?: "Unknown"
                    val host = info.host?.hostAddress
                    val port = info.port

                    if (mac != null && host != null && port > 0) {
                        val resolved = ServiceInfo(mac, name, host, port)
                        Log.d(TAG, "Resolved service: $resolved")
                        
                        // Update cache and emit
                        discoveredServicesCache[mac] = resolved
                        CoroutineScope(Dispatchers.IO).launch {
                            _discoveredServices.emit(resolved)
                        }
                    }
                }
            }
        }

        nsdManager.resolveService(service, resolveListener)
    }

    private fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }
        discoveryListener = null
        isDiscovering = false
    }

    /**
     * Get a flow of discovered services.
     */
    fun getDiscoveredServices(): Flow<ServiceInfo> = _discoveredServices.asSharedFlow()

    /**
     * Stop discovery and unregister service.
     */
    fun stop() {
        stopDiscovery()
        unregisterService()
        resolveListener = null
    }
}

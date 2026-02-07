package com.ailife.rtosifycompanion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.IBinder
import android.preference.PreferenceManager
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import android.graphics.Color
import kotlin.math.abs

class FindDeviceActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private lateinit var tvSignalStrength: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvLastUpdated: TextView
    private lateinit var btnRingDevice: android.widget.ImageButton
    private lateinit var btnOpenMaps: android.widget.ImageButton

    private var isRinging = false
    private var handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastUpdateTimestamp: Long = 0
    private var isFirstRemoteLocationUpdate = true

    private val periodicLocationTask = object : Runnable {
        override fun run() {
            requestManualLocationUpdate()
            handler.postDelayed(this, 1000) 
        }
    }
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateLastUpdatedText()
            handler.postDelayed(this, 1000)
        }
    }

    private var locationManager: LocationManager? = null
    private var watchMarker: Marker? = null
    private var phoneMarker: Marker? = null
    private var watchAccuracyCircle: Polygon? = null
    private var phoneAccuracyCircle: Polygon? = null

    private var watchLocation: Location? = null
    private var phoneLocation: Location? = null
    private var currentRssi: Int = 0

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = false

    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BluetoothService.LocalBinder
            bluetoothService = binder?.getService()
            isBound = true

            // Start local BLE RSSI monitoring on this device
            bluetoothService?.startLocalFindDeviceMonitoring()

            // Request remote device to start sending its location
            bluetoothService?.sendMessage(ProtocolHelper.createFindDeviceLocationRequest(true))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            isBound = false
        }
    }

    private val locationUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_LOCATION_UPDATE -> {
                    val latitude = intent.getDoubleExtra("latitude", 0.0)
                    val longitude = intent.getDoubleExtra("longitude", 0.0)
                    val accuracy = intent.getFloatExtra("accuracy", 0f)
                    val rssi = intent.getIntExtra("rssi", 0)

                    Log.d("FindDeviceActivity", "Received location update (Companion): lat=$latitude, lon=$longitude, acc=$accuracy, rssi=$rssi")

                    phoneLocation = Location("phone").apply {
                        this.latitude = latitude
                        this.longitude = longitude
                    }
                    
                    if (rssi != 0) {
                         currentRssi = rssi
                         updateSignalStrength(rssi)
                    }

                    updatePhoneMarker(latitude, longitude, accuracy)
                    updateDistanceDisplay()
                    lastUpdateTimestamp = System.currentTimeMillis()
                    updateLastUpdatedText()
                }
                ACTION_STOP_RINGING -> {
                    Log.i("FindDeviceActivity", "Stop ringing broadcast received from phone")
                    updateRingingState(false)
                }
                ACTION_STOP_FINDING -> {
                    Log.i("FindDeviceActivity", "Stop finding broadcast received from phone")
                    finish()
                }
            }
        }
    }

    private val bluetoothScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    
                    if (device != null && device.bondState == BluetoothDevice.BOND_BONDED) {
                        currentRssi = rssi
                        Log.d("FindDeviceActivity", "Local scan found bonded device (Companion): RSSI=$rssi")
                        updateSignalStrength(rssi)
                        // Don't cancel, we want continuous updates!
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                     isScanning = false
                     // Continuous scan loop while activity is active
                     if (!isFinishing) {
                         // Small delay to allow other radio ops
                         handler.postDelayed({ startSignalStrengthMonitoring() }, 2000)
                     }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure it shows over lockscreen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Configure OSMDroid
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        setContentView(R.layout.activity_find_device)
        val rootLayout = findViewById<android.view.View>(R.id.rootLayout)
        EdgeToEdgeUtils.applyEdgeToEdge(this, rootLayout)

        initViews()
        setupMap()
        setupBluetooth()
        requestLocationPermissionIfNeeded()
        bindToBluetoothService()
        registerLocationUpdateReceiver()

        btnRingDevice.setOnClickListener {
            toggleRingDevice()
        }

        btnOpenMaps.setOnClickListener {
            openInMaps()
        }

        // Start periodic tasks
        handler.post(periodicLocationTask)
        handler.post(uiUpdateRunnable)
    }

    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        tvSignalStrength = findViewById(R.id.tvSignalStrength)
        tvDistance = findViewById(R.id.tvDistance)
        tvLastUpdated = findViewById(R.id.tvLastUpdated)
        btnRingDevice = findViewById(R.id.btnRingDevice)
        btnOpenMaps = findViewById(R.id.btnOpenMaps)
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)

        // Default center (will be updated with actual location)
        mapView.controller.setCenter(GeoPoint(0.0, 0.0))
    }

    private fun setupBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    private fun requestLocationPermissionIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
             ActivityCompat.requestPermissions(this, permissions.toTypedArray(), LOCATION_PERMISSION_REQUEST)
        } else {
            startLocationTracking()
            startSignalStrengthMonitoring()
        }
    }

    private fun startLocationTracking() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L, // Update every 2 seconds
                5f,     // Minimum 5 meters change
                this
            )

            // Get last known location immediately
            locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                onLocationChanged(it)
            }
        }
    }

    private fun bindToBluetoothService() {
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun registerLocationUpdateReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_LOCATION_UPDATE)
            addAction(ACTION_STOP_RINGING)
            addAction(ACTION_STOP_FINDING)
        }
        registerReceiver(locationUpdateReceiver, filter, RECEIVER_EXPORTED)
        
        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothScanReceiver, btFilter)
    }

    override fun onLocationChanged(location: Location) {
        watchLocation = location

        // Update watch marker
        if (watchMarker == null) {
            watchMarker = Marker(mapView).apply {
                icon = ContextCompat.getDrawable(this@FindDeviceActivity, R.drawable.ic_watch)
                icon.setTint(Color.GREEN) // Tint for visibility (Local is Green)
                title = getString(R.string.find_device_watch_you)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mapView.overlays.add(this)
            }
        }

        watchMarker?.position = GeoPoint(location.latitude, location.longitude)
        
        // Update Watch Accuracy Circle
        if (watchAccuracyCircle == null) {
            watchAccuracyCircle = Polygon().apply {
                fillColor = Color.argb(40, 0, 255, 0) // Semi-transparent green
                strokeColor = Color.GREEN
                strokeWidth = 1f
                mapView.overlays.add(0, this)
            }
        }
        watchAccuracyCircle?.points = Polygon.pointsAsCircle(GeoPoint(location.latitude, location.longitude), location.accuracy.toDouble())

        // Center map on watch location if no phone location yet
        // Only if first update? Or allow following self? 
        // For now, match Phone app behavior: Center if remote unkown.
        if (phoneLocation == null && isFirstRemoteLocationUpdate) {
            mapView.controller.setCenter(GeoPoint(location.latitude, location.longitude))
        }

        mapView.invalidate()

        // Send location update to phone
        bluetoothService?.sendFindDeviceLocationUpdate(
            location.latitude,
            location.longitude,
            location.accuracy,
            currentRssi
        )

        updateDistanceDisplay()
    }

    private fun updatePhoneMarker(latitude: Double, longitude: Double, accuracy: Float) {
        if (phoneMarker == null) {
            phoneMarker = Marker(mapView).apply {
                icon = ContextCompat.getDrawable(this@FindDeviceActivity, R.drawable.ic_phone)
                icon.setTint(Color.BLUE) // Tint for visibility (Remote is Blue)
                title = getString(R.string.device_phone)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mapView.overlays.add(this)
            }
        }

        phoneMarker?.position = GeoPoint(latitude, longitude)
        
        // Update Phone Accuracy Circle
        if (phoneAccuracyCircle == null) {
            phoneAccuracyCircle = Polygon().apply {
                fillColor = Color.argb(40, 0, 0, 255) // Semi-transparent blue
                strokeColor = Color.BLUE
                strokeWidth = 1f
                mapView.overlays.add(0, this)
            }
        }
        phoneAccuracyCircle?.points = Polygon.pointsAsCircle(GeoPoint(latitude, longitude), accuracy.toDouble())

        // Initial center on phone if no watch yet
        // Only auto-zoom/center on the FIRST confirmed update from the phone
        if (isFirstRemoteLocationUpdate && watchLocation != null) {
            watchLocation?.let { watchLoc ->
                val centerLat = (watchLoc.latitude + latitude) / 2
                val centerLon = (watchLoc.longitude + longitude) / 2
                mapView.controller.setCenter(GeoPoint(centerLat, centerLon))

                adjustZoomToShowBothDevices(watchLoc, Location("phone").apply {
                    this.latitude = latitude
                    this.longitude = longitude
                })
            }
            isFirstRemoteLocationUpdate = false
        }
        
        mapView.invalidate()
    }

    private fun adjustZoomToShowBothDevices(loc1: Location, loc2: Location) {
        val distance = loc1.distanceTo(loc2)
        val zoom = when {
            distance > 5000 -> 12.0  // > 5km
            distance > 1000 -> 14.0  // > 1km
            distance > 500 -> 15.0   // > 500m
            distance > 100 -> 16.0   // > 100m
            else -> 17.0             // < 100m
        }
        mapView.controller.setZoom(zoom)
    }

    private fun updateDistanceDisplay() {
        val watch = watchLocation
        val phone = phoneLocation

        if (watch != null && phone != null) {
            val distance = watch.distanceTo(phone)

            // Use RSSI for short distances (< 10m), GPS for longer
            val displayText = if (distance < 10 && abs(currentRssi) < 80) {
                val rssiDistance = estimateDistanceFromRssi(currentRssi)
                getString(R.string.find_device_dist_bt, rssiDistance)
            } else {
                when {
                    distance >= 1000 -> getString(R.string.find_device_dist_gps_km, distance / 1000)
                    else -> getString(R.string.find_device_dist_gps_m, distance)
                }
            }

            tvDistance.text = displayText
        } else {
            tvDistance.text = getString(R.string.find_device_dist_waiting)
        }
    }

    private fun estimateDistanceFromRssi(rssi: Int): String {
        // Rough estimation: RSSI to distance
        // This is approximate and varies by environment
        return when {
            rssi > -50 -> "<1"
            rssi > -60 -> "1-2"
            rssi > -70 -> "2-5"
            rssi > -80 -> "5-10"
            else -> ">10"
        }
    }

    private fun updateLastUpdatedText() {
        if (lastUpdateTimestamp == 0L) {
            tvLastUpdated.text = getString(R.string.status_last_updated_waiting)
            return
        }
        val diffSeconds = (System.currentTimeMillis() - lastUpdateTimestamp) / 1000
        tvLastUpdated.text = getString(R.string.find_device_updated_format, diffSeconds)
    }

    private fun updateSignalStrength(rssi: Int) {
        val strength = when {
            rssi > -50 -> getString(R.string.find_device_rssi_excellent)
            rssi > -60 -> getString(R.string.find_device_rssi_good)
            rssi > -70 -> getString(R.string.find_device_rssi_fair)
            rssi > -80 -> getString(R.string.find_device_rssi_weak)
            else -> getString(R.string.find_device_rssi_very_weak)
        }

        tvSignalStrength.text = getString(R.string.find_device_signal_format, strength, rssi)
    }

    private fun toggleRingDevice() {
        updateRingingState(!isRinging)
    }

    private fun updateRingingState(ringing: Boolean) {
        isRinging = ringing
        bluetoothService?.sendFindPhoneCommand(isRinging)
        
        if (isRinging) {
            btnRingDevice.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, android.R.color.holo_orange_dark)
            )
        } else {
            btnRingDevice.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            )
        }
    }

    private fun requestManualLocationUpdate() {
        // Force an update by sending current (or last known) location
        watchLocation?.let {
            bluetoothService?.sendFindDeviceLocationUpdate(
                it.latitude,
                it.longitude,
                it.accuracy,
                currentRssi
            )
        }
    }

    private fun startSignalStrengthMonitoring() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        
        if (!isScanning) {
            bluetoothAdapter?.startDiscovery()
            isScanning = true
        }
    }

    private fun stopFindingAndFinish() {
        // Stop ringing first if it's active
        if (isRinging) {
            bluetoothService?.sendFindPhoneCommand(false)
        }
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startLocationTracking()
                startSignalStrengthMonitoring()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onProviderEnabled(provider: String) {
        Log.d("FindDeviceActivity", "Location provider enabled: $provider")
        if (provider == LocationManager.GPS_PROVIDER) {
            // Location was enabled, try to start tracking
            startLocationTracking()
        }
    }

    override fun onProviderDisabled(provider: String) {
        Log.d("FindDeviceActivity", "Location provider disabled: $provider")
        if (provider == LocationManager.GPS_PROVIDER) {
            // Show message that location is disabled
            tvDistance.text = getString(R.string.find_device_dist_waiting)
            android.widget.Toast.makeText(
                this,
                R.string.error_location_disabled,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // This method is deprecated but still required for older API levels
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(periodicLocationTask)
        handler.removeCallbacks(uiUpdateRunnable)

        // Stop local BLE RSSI monitoring
        if (isBound) {
            bluetoothService?.stopLocalFindDeviceMonitoring()
        }

        // Tell remote device to stop sending its location
        if (isBound) {
            bluetoothService?.sendMessage(ProtocolHelper.createFindDeviceLocationRequest(false))
        }

        locationManager?.removeUpdates(this)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        try {
            unregisterReceiver(locationUpdateReceiver)
            unregisterReceiver(bluetoothScanReceiver)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                 bluetoothAdapter?.cancelDiscovery()
            }
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    private fun openInMaps() {
        phoneLocation?.let { location ->
            val uri = android.net.Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}(Phone)")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            // intent.setPackage("com.google.android.apps.maps") // Don't force Google Maps, let user choose
            try {
                startActivity(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "No map app found", android.widget.Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            android.widget.Toast.makeText(this, "Location not available yet", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        const val ACTION_LOCATION_UPDATE = "com.ailife.rtosifycompanion.FIND_DEVICE_LOCATION_UPDATE"
        const val ACTION_STOP_RINGING = "com.ailife.rtosifycompanion.STOP_RINGING"
        const val ACTION_STOP_FINDING = "com.ailife.rtosifycompanion.STOP_FINDING"
    }
}

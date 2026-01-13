package com.ailife.rtosify

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
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
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
    private lateinit var btnRingDevice: Button
    private lateinit var btnStopFinding: Button

    private var isRinging = false
    private var handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastUpdateTimestamp: Long = 0
    private var isFirstRemoteLocationUpdate = true

    private val periodicLocationTask = object : Runnable {
        override fun run() {
            requestManualLocationUpdate()
            updateLastUpdatedText()
            handler.postDelayed(this, 1000) // Update UI every second, request loc every 5s is separate? 
            // Actually requestManualLocationUpdate sends update. 
            // Let's separate UI update from network request if we want smoother timer.
            // But for now, keeping existing 5s loop but updating text. 
            // Wait, "Display how long ago is the data refreshed" needs 1s timer.
        }
    }
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateLastUpdatedText()
            handler.postDelayed(this, 1000)
        }
    }

    private var locationManager: LocationManager? = null
    private var phoneMarker: Marker? = null
    private var watchMarker: Marker? = null
    private var phoneAccuracyCircle: Polygon? = null
    private var watchAccuracyCircle: Polygon? = null

    private var phoneLocation: Location? = null
    private var watchLocation: Location? = null
    private var currentRssi: Int = 0

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = false

    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private var findDeviceRingtone: Ringtone? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BluetoothService.LocalBinder
            bluetoothService = binder?.getService()
            isBound = true
            
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

                    Log.d("FindDeviceActivity", "Received location update: lat=$latitude, lon=$longitude, acc=$accuracy, rssi=$rssi")

                    watchLocation = Location("watch").apply {
                        this.latitude = latitude
                        this.longitude = longitude
                    }
                    
                    // Only update RSSI from remote if we don't have a valid local one,
                    // or if the remote one is actually valid (non-zero) and different?
                    // Actually, if we are scanning locally, local is truth. 
                    // Remote RSSI is "Watch's view of Phone". 
                    // Let's only update if local is 0 (not found yet).
                    if (currentRssi == 0 && rssi != 0) {
                         currentRssi = rssi
                         updateSignalStrength(rssi)
                    }

                    updateWatchMarker(latitude, longitude, accuracy)
                    updateDistanceDisplay()
                    lastUpdateTimestamp = System.currentTimeMillis()
                    updateLastUpdatedText()
                }
                ACTION_STOP_RINGING -> {
                    Log.i("FindDeviceActivity", "Stop ringing broadcast received from watch")
                    updateRingingState(false)
                }
                ACTION_STOP_FINDING -> {
                    Log.i("FindDeviceActivity", "Stop finding broadcast received from watch")
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
                        Log.d("FindDeviceActivity", "Local scan found bonded device: RSSI=$rssi")
                        updateSignalStrength(rssi)
                        // Don't cancel, we want continuous updates!
                        // But finding consumes battery. 
                        // Maybe restart scan in ACTION_DISCOVERY_FINISHED instead.
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

        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        setContentView(R.layout.activity_find_device)

        initViews()
        setupMap()
        setupBluetooth()
        requestLocationPermissionIfNeeded()
        bindToBluetoothService()
        registerLocationUpdateReceiver()

        btnRingDevice.setOnClickListener {
            toggleRingDevice()
        }

        btnStopFinding.setOnClickListener {
            stopFindingAndFinish()
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
        btnStopFinding = findViewById(R.id.btnStopFinding)
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
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
                2000L,
                5f,
                this
            )

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
        registerReceiver(bluetoothScanReceiver, btFilter) // Local receiver, no export needed usually but...
        // Actually for system broadcasts it's fine.
    }

    override fun onLocationChanged(location: Location) {
        phoneLocation = location

        if (phoneMarker == null) {
            phoneMarker = Marker(mapView).apply {
                icon = ContextCompat.getDrawable(this@FindDeviceActivity, R.drawable.ic_phone)
                icon.setTint(Color.BLUE) // Tint for visibility
                title = "Phone (You)"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mapView.overlays.add(this)
            }
        }

        phoneMarker?.position = GeoPoint(location.latitude, location.longitude)
        
        // Update Phone Accuracy Circle
        if (phoneAccuracyCircle == null) {
            phoneAccuracyCircle = Polygon().apply {
                fillColor = Color.argb(40, 0, 0, 255) // Semi-transparent blue
                strokeColor = Color.BLUE
                strokeWidth = 1f
                mapView.overlays.add(0, this) // Add at bottom
            }
        }
        phoneAccuracyCircle?.points = Polygon.pointsAsCircle(GeoPoint(location.latitude, location.longitude), location.accuracy.toDouble())

        // Initial center on phone if no watch yet
        if (watchLocation == null && isFirstRemoteLocationUpdate) {
             mapView.controller.setCenter(GeoPoint(location.latitude, location.longitude))
        }

        mapView.invalidate()

        bluetoothService?.sendFindDeviceLocationUpdate(
            location.latitude,
            location.longitude,
            location.accuracy,
            currentRssi
        )
        Log.d("FindDeviceActivity", "Sent own location update: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}, rssi=$currentRssi")

        updateDistanceDisplay()
    }

    private fun updateWatchMarker(latitude: Double, longitude: Double, accuracy: Float) {
        if (watchMarker == null) {
            watchMarker = Marker(mapView).apply {
                icon = ContextCompat.getDrawable(this@FindDeviceActivity, R.drawable.ic_watch)
                icon.setTint(Color.GREEN) // Tint for visibility (Green matches legend) 
                // Wait, legend says green for watch. 
                // Or user said "Red/Blue"? I'll use Green to match XML legend I saw.
                // Re-reading user request: "make the actual pin on the map in a different color then white"
                title = "Watch"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mapView.overlays.add(this)
            }
        }

        watchMarker?.position = GeoPoint(latitude, longitude)
        
        // Update Watch Accuracy Circle
        if (watchAccuracyCircle == null) {
            watchAccuracyCircle = Polygon().apply {
                fillColor = Color.argb(40, 0, 255, 0) // Semi-transparent green
                strokeColor = Color.GREEN
                strokeWidth = 1f
                mapView.overlays.add(0, this) // Add at bottom
            }
        }
        watchAccuracyCircle?.points = Polygon.pointsAsCircle(GeoPoint(latitude, longitude), accuracy.toDouble())
        
        // Only auto-zoom/center on the FIRST confirmed update from the watch
        if (isFirstRemoteLocationUpdate && phoneLocation != null) {
            phoneLocation?.let { phoneLoc ->
                 val centerLat = (latitude + phoneLoc.latitude) / 2
                 val centerLon = (longitude + phoneLoc.longitude) / 2
                 mapView.controller.setCenter(GeoPoint(centerLat, centerLon))
                 
                 val distance = phoneLoc.distanceTo(Location("dummy").apply { 
                     this.latitude = latitude
                     this.longitude = longitude
                 })
                 val zoom = when {
                    distance > 5000 -> 12.0
                    distance > 1000 -> 14.0
                    distance > 500 -> 15.0
                    distance > 100 -> 16.0
                    else -> 17.0
                 }
                 mapView.controller.setZoom(zoom)
            }
            isFirstRemoteLocationUpdate = false
        }
        
        mapView.invalidate()
    }

    private fun updateDistanceDisplay() {
        val phone = phoneLocation
        val watch = watchLocation

        if (phone != null && watch != null) {
            val distance = phone.distanceTo(watch)

            val displayText = if (distance < 10 && abs(currentRssi) < 80) {
                val rssiDistance = estimateDistanceFromRssi(currentRssi)
                "Distance: ~${rssiDistance}m (Bluetooth)"
            } else {
                when {
                    distance >= 1000 -> "Distance: %.2f km (GPS)".format(distance / 1000)
                    else -> "Distance: %.0f m (GPS)".format(distance)
                }
            }

            tvDistance.text = displayText
        } else {
            tvDistance.text = "Distance: Waiting for location..."
        }
    }

    private fun estimateDistanceFromRssi(rssi: Int): String {
        return when {
            rssi > -50 -> "<1"
            rssi > -60 -> "1-2"
            rssi > -70 -> "2-5"
            rssi > -80 -> "5-10"
            else -> ">10"
        }
    }

    private fun updateSignalStrength(rssi: Int) {
        val strength = when {
            rssi > -50 -> "Excellent"
            rssi > -60 -> "Good"
            rssi > -70 -> "Fair"
            rssi > -80 -> "Weak"
            else -> "Very Weak"
        }

        tvSignalStrength.text = "Signal: $strength ($rssi dBm)"
    }

    private fun toggleRingDevice() {
        updateRingingState(!isRinging)
    }

    private fun updateRingingState(ringing: Boolean) {
        isRinging = ringing
        bluetoothService?.sendFindDeviceCommand(isRinging)
        
        if (isRinging) {
            btnRingDevice.text = "Stop Ringing"
            btnRingDevice.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, android.R.color.holo_orange_dark)
            )
        } else {
            btnRingDevice.text = "Ring Watch"
            btnRingDevice.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            )
        }
    }

    private fun updateLastUpdatedText() {
        if (lastUpdateTimestamp == 0L) {
            tvLastUpdated.text = "Last updated: Waiting for data..."
            return
        }
        val diffSeconds = (System.currentTimeMillis() - lastUpdateTimestamp) / 1000
        tvLastUpdated.text = "Last updated: ${diffSeconds}s ago"
    }

    private fun requestManualLocationUpdate() {
        // Force an update by sending current (or last known) location
        phoneLocation?.let {
            bluetoothService?.sendFindDeviceLocationUpdate(
                it.latitude,
                it.longitude,
                it.accuracy,
                currentRssi
            )
        } ?: run {
            // If no location yet, just try to nudge the service/companion
            // (The companion is already sending its location on its own timer/change)
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
            Log.d("FindDeviceActivity", "Started Bluetooth discovery for RSSI")
        }
    }

    private fun stopFindingAndFinish() {
        // Stop ringing first if it's active
        if (isRinging) {
            bluetoothService?.sendFindDeviceCommand(false)
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

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(periodicLocationTask)
        handler.removeCallbacks(uiUpdateRunnable)
        
        // Tell remote device to stop sending its location
        if (isBound) {
            bluetoothService?.sendMessage(ProtocolHelper.createFindDeviceLocationRequest(false))
        }
        
        findDeviceRingtone?.stop()
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

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        const val ACTION_LOCATION_UPDATE = "com.ailife.rtosify.FIND_DEVICE_LOCATION_UPDATE"
        const val ACTION_STOP_RINGING = "com.ailife.rtosify.STOP_RINGING"
        const val ACTION_STOP_FINDING = "com.ailife.rtosify.STOP_FINDING"
    }
}

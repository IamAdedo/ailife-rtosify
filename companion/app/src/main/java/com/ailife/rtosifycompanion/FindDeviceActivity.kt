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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.abs

class FindDeviceActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private lateinit var tvSignalStrength: TextView
    private lateinit var tvDistance: TextView
    private lateinit var btnRingDevice: Button
    private lateinit var btnStopFinding: Button

    private var isRinging = false
    private var handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val periodicLocationTask = object : Runnable {
        override fun run() {
            requestManualLocationUpdate()
            handler.postDelayed(this, 5000) // Repeat every 5 seconds
        }
    }

    private var locationManager: LocationManager? = null
    private var watchMarker: Marker? = null
    private var phoneMarker: Marker? = null

    private var watchLocation: Location? = null
    private var phoneLocation: Location? = null
    private var currentRssi: Int = 0

    private var bluetoothService: BluetoothService? = null
    private var isBound = false

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
                    val rssi = intent.getIntExtra("rssi", 0)

                    phoneLocation = Location("phone").apply {
                        this.latitude = latitude
                        this.longitude = longitude
                    }
                    currentRssi = rssi

                    updatePhoneMarker(latitude, longitude)
                    updateDistanceDisplay()
                    updateSignalStrength(rssi)
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

        initViews()
        setupMap()
        requestLocationPermissionIfNeeded()
        bindToBluetoothService()
        registerLocationUpdateReceiver()

        btnRingDevice.setOnClickListener {
            toggleRingDevice()
        }

        btnStopFinding.setOnClickListener {
            stopFindingAndFinish()
        }

        // Start periodic location requests
        handler.post(periodicLocationTask)
    }

    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        tvSignalStrength = findViewById(R.id.tvSignalStrength)
        tvDistance = findViewById(R.id.tvDistance)
        btnRingDevice = findViewById(R.id.btnRingDevice)
        btnStopFinding = findViewById(R.id.btnStopFinding)
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)

        // Default center (will be updated with actual location)
        mapView.controller.setCenter(GeoPoint(0.0, 0.0))
    }

    private fun requestLocationPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        } else {
            startLocationTracking()
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
    }

    override fun onLocationChanged(location: Location) {
        watchLocation = location

        // Update watch marker
        if (watchMarker == null) {
            watchMarker = Marker(mapView).apply {
                icon = ContextCompat.getDrawable(this@FindDeviceActivity, R.drawable.ic_watch)
                title = "Watch (You)"
                mapView.overlays.add(this)
            }
        }

        watchMarker?.position = GeoPoint(location.latitude, location.longitude)

        // Center map on watch location if no phone location yet
        if (phoneLocation == null) {
            mapView.controller.setCenter(GeoPoint(location.latitude, location.longitude))
        } else {
            // Center between both devices
            phoneLocation?.let { phoneLoc ->
                val centerLat = (location.latitude + phoneLoc.latitude) / 2
                val centerLon = (location.longitude + phoneLoc.longitude) / 2
                mapView.controller.setCenter(GeoPoint(centerLat, centerLon))

                // Adjust zoom to show both markers
                adjustZoomToShowBothDevices(location, phoneLoc)
            }
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

    private fun updatePhoneMarker(latitude: Double, longitude: Double) {
        if (phoneMarker == null) {
            phoneMarker = Marker(mapView).apply {
                icon = ContextCompat.getDrawable(this@FindDeviceActivity, R.drawable.ic_phone)
                title = "Phone"
                mapView.overlays.add(this)
            }
        }

        phoneMarker?.position = GeoPoint(latitude, longitude)
        mapView.invalidate()

        // Center map to show both devices
        watchLocation?.let { watchLoc ->
            val centerLat = (watchLoc.latitude + latitude) / 2
            val centerLon = (watchLoc.longitude + longitude) / 2
            mapView.controller.setCenter(GeoPoint(centerLat, centerLon))

            adjustZoomToShowBothDevices(watchLoc, Location("phone").apply {
                this.latitude = latitude
                this.longitude = longitude
            })
        }
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
        bluetoothService?.sendFindPhoneCommand(isRinging)
        
        if (isRinging) {
            btnRingDevice.text = "Stop Ringing"
            btnRingDevice.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, android.R.color.holo_orange_dark)
            )
        } else {
            btnRingDevice.text = "Ring Phone"
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
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationTracking()
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
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        const val ACTION_LOCATION_UPDATE = "com.ailife.rtosifycompanion.FIND_DEVICE_LOCATION_UPDATE"
        const val ACTION_STOP_RINGING = "com.ailife.rtosifycompanion.STOP_RINGING"
        const val ACTION_STOP_FINDING = "com.ailife.rtosifycompanion.STOP_FINDING"
    }
}

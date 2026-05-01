package com.iamadedo.phoneapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.slider.Slider
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.util.Log

class DynamicIslandBackgroundActivity : AppCompatActivity() {

    private lateinit var dynamicIslandPreview: DynamicIslandView
    private lateinit var btnSelectImage: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var seekBarOpacity: Slider
    private lateinit var tvOpacityValue: TextView
    private lateinit var toggleMode: MaterialButtonToggleGroup

    private val devicePrefManager by lazy { DevicePrefManager(this) }
    private val activePrefs by lazy { devicePrefManager.getActiveDevicePrefs() }

    private var selectedUri: Uri? = null
    private var bluetoothService: BluetoothService? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            saveOriginalImage(uri) // Save a local copy immediately
            loadImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dynamic_island_background)
        
        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        val nestedScrollView = findViewById<View>(R.id.nestedScrollView)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, nestedScrollView)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Bind Service
        val serviceIntent = Intent(this, BluetoothService::class.java)
        bindService(serviceIntent, object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                bluetoothService = (service as? BluetoothService.LocalBinder)?.getService()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                bluetoothService = null
            }
        }, BIND_AUTO_CREATE)

        initViews()
        loadPrefs()
        setupListeners()
    }

    private fun initViews() {
        dynamicIslandPreview = findViewById(R.id.dynamicIslandPreview)
        btnSelectImage = findViewById(R.id.btnSelectImage)
        btnSave = findViewById(R.id.btnSave)
        seekBarOpacity = findViewById(R.id.seekBarOpacity)
        tvOpacityValue = findViewById(R.id.tvOpacityValue)
        toggleMode = findViewById(R.id.toggleMode)

        dynamicIslandPreview.setPreviewMode(true)
    }

    private fun loadPrefs() {
        // Load default or saved dimensions from Watch settings
        val prefs = devicePrefManager.getActiveDevicePrefs()
        val widthDp = prefs.getInt("dynamic_island_width", 150)
        val heightDp = prefs.getInt("dynamic_island_height", 40)
        
        dynamicIslandPreview.updateDimensions(widthDp, heightDp)
        updatePreviewMode(toggleMode.checkedButtonId == R.id.btnModePill)

        // Load persisted background
        val savedBitmap = loadLocalImage()
        if (savedBitmap != null) {
            dynamicIslandPreview.setPreviewImage(savedBitmap)
        }

        // Load persisted opacity
        val opacity = loadOpacity()
        seekBarOpacity.value = opacity.toFloat()
        tvOpacityValue.text = "$opacity%"
        dynamicIslandPreview.setPreviewOpacity((opacity * 255) / 100)
    }

    private fun setupListeners() {
        btnSelectImage.setOnClickListener {
            pickImage.launch("image/*")
        }

        seekBarOpacity.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            val alpha = (progress * 255) / 100
            dynamicIslandPreview.setPreviewOpacity(alpha)
            tvOpacityValue.text = "$progress%"
        }

        btnSave.setOnClickListener {
            saveAndSync()
        }

        // Apply persisted matrix once laid out
        dynamicIslandViewAfterLayout()
    }

    private fun dynamicIslandViewAfterLayout() {
        dynamicIslandPreview.post {
            val matrixValues = loadMatrix()
            if (matrixValues != null) {
                dynamicIslandPreview.setMatrixValues(matrixValues)
            }
        }
    }

    private fun updatePreviewMode(isPill: Boolean) {
        if (isPill) {
            dynamicIslandPreview.showConnectedState("BT") // Show a mockup state
        } else {
             // Show a mock notification for expanded preview
             val mockNotif = NotificationData(
                 packageName = "com.android.systemui",
                 title = getString(R.string.di_bg_preview_user),
                 text = getString(R.string.di_bg_preview_text),
                 key = "preview_key"
             )
             dynamicIslandPreview.showNotification(mockNotif)
        }
    }

    private fun loadImage(uri: Uri) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
            } else {
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            dynamicIslandPreview.setPreviewImage(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.di_bg_toast_load_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAndSync() {
        val progress = seekBarOpacity.value.toInt()
        val alpha = (progress * 255) / 100

        // Image Mode
        val result = dynamicIslandPreview.generateCroppedBitmap()
        if (result == null) {
            Toast.makeText(this, getString(R.string.di_bg_toast_select_first), Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Compress
        val stream = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.WEBP, 90, stream) // Use WebP for efficiency
        val bytes = stream.toByteArray()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        
        if (bluetoothService != null) {
            saveMatrix(dynamicIslandPreview.getMatrixValues())
            saveOpacity(progress)
            activePrefs.edit()
                .putInt("dynamic_island_background_mode", 0)
                .apply()

            // UI Feedback: Change button state
            btnSave.isEnabled = false
            btnSave.text = "Syncing..."
            
            // Send to watch
            Log.d("DynamicIslandSync", "Sending background image, size: ${bytes.size} bytes. Base64 length: ${base64.length}")
            bluetoothService?.sendDynamicIslandBackground(base64, alpha)
            
            // Delay exit to show progress
            Handler(Looper.getMainLooper()).postDelayed({
                if (isDestroyed) return@postDelayed
                Toast.makeText(this, getString(R.string.di_bg_toast_success), Toast.LENGTH_SHORT).show()
                finish()
            }, 1000)
        } else {
            Toast.makeText(this, getString(R.string.di_bg_toast_service_disconnected), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveOriginalImage(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                openFileOutput("di_original.webp", MODE_PRIVATE).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadLocalImage(): Bitmap? {
        return try {
            val file = File(filesDir, "di_original.webp")
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveMatrix(values: FloatArray) {
        val sb = StringBuilder()
        for (i in values.indices) {
            sb.append(values[i])
            if (i < values.size - 1) sb.append(",")
        }
        getSharedPreferences("dynamic_island_prefs", MODE_PRIVATE)
            .edit()
            .putString("matrix", sb.toString())
            .apply()
    }

    private fun loadMatrix(): FloatArray? {
        val s = getSharedPreferences("dynamic_island_prefs", MODE_PRIVATE)
            .getString("matrix", null) ?: return null
        val parts = s.split(",")
        if (parts.size != 9) return null
        val values = FloatArray(9)
        for (i in 0 until 9) {
            values[i] = parts[i].toFloat()
        }
        return values
    }

    private fun saveOpacity(opacity: Int) {
        activePrefs.edit()
            .putInt("dynamic_island_background_opacity", opacity)
            .apply()
    }

    private fun loadOpacity(): Int {
        return activePrefs.getInt("dynamic_island_background_opacity", 100)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

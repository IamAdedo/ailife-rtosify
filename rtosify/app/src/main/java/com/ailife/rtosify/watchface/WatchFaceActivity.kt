package com.ailife.rtosify.watchface

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.ailife.rtosify.BluetoothService
import com.ailife.rtosify.FileTransferData
import com.ailife.rtosify.R
import com.google.android.material.tabs.TabLayout
import java.io.File

class WatchFaceActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    private lateinit var tabLayout: TabLayout
    private lateinit var toolbar: Toolbar
    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    
    private val storeFragment = WatchFaceStoreFragment()
    private val localFragment = LocalWatchFaceFragment()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            bluetoothService?.callback = this@WatchFaceActivity
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            bluetoothService = null
        }
    }

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleImportedUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch_face)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.wf_title)

        tabLayout = findViewById(R.id.tabLayout)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                switchFragment(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        switchFragment(0)
        
        bindService(Intent(this, BluetoothService::class.java), connection, Context.BIND_AUTO_CREATE)

        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(connection)
        unregisterReceiver(downloadReceiver)
    }

    private fun switchFragment(position: Int) {
        val fragment = if (position == 0) storeFragment else localFragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    fun downloadWatchFace(wf: WatchFace) {
        val request = DownloadManager.Request(Uri.parse(wf.downloadUrl))
            .setTitle(wf.title)
            .setDescription(getString(R.string.wf_downloading))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "RTOSify/WatchFaces/${wf.title}")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, R.string.wf_downloading, Toast.LENGTH_SHORT).show()
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                localFragment.refreshList()
            }
        }
    }

    fun transferWatchFace(file: File) {
        if (bluetoothService?.isConnected != true) {
            Toast.makeText(this, R.string.toast_watch_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        
        // Use existing showUploadProgressDialog if possible or implement here
        // For simplicity, I'll just call the service method
        bluetoothService?.sendFile(file, FileTransferData.TYPE_WATCHFACE)
    }

    fun pickWatchFaceFromDevice() {
        pickFileLauncher.launch("*/*") // Filter for zip/watch if possible
    }

    private fun handleImportedUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val fileName = getFileName(uri) ?: "imported_wf.zip"
            val destFile = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RTOSify/WatchFaces"), fileName)
            destFile.parentFile?.mkdirs()
            
            destFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            localFragment.refreshList()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    result = it.getString(it.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    // Callbacks from BluetoothService
    override fun onUploadProgress(progress: Int) {
        // Implement progress dialog update
    }
    
    override fun onStatusChanged(status: String) {}
    override fun onAppListReceived(appsJson: String) {}
    override fun onDownloadProgress(progress: Int) {}
    override fun onFileListReceived(path: String, filesJson: String) {}
    override fun onDeviceConnected(deviceName: String) {}
    override fun onDeviceDisconnected() {}
    override fun onScanResult(devices: List<android.bluetooth.BluetoothDevice>) {}
    override fun onWatchStatusUpdated(battery: Int, charging: Boolean, wifi: String, dnd: Boolean) {}
    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

package com.ailife.rtosify.watchface

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.ailife.rtosify.BluetoothService
import com.ailife.rtosify.DeviceInfoData
import com.ailife.rtosify.FileTransferData
import com.ailife.rtosify.R
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.util.LinkedList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import android.view.View
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.widget.TextView

data class TransferRequest(val file: File, val remotePath: String)

class WatchFaceActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    private lateinit var tabLayout: TabLayout
    private lateinit var toolbar: MaterialToolbar
    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    // Transfer Queue
    private val transferQueue = LinkedList<TransferRequest>()
    private var isProcessingQueue = false
    private var currentTransferFile: File? = null

    private val storeFragment = WatchFaceStoreFragment()

    private val managerFragment = WatchFaceManagerFragment.newInstance(false)
    private val localFragment = WatchFaceManagerFragment.newInstance(true)

    private var progressDialog: AlertDialog? = null
    private val downloadMap = mutableMapOf<Long, String>() // downloadId to fileName

    private val connection =
            object : ServiceConnection {
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

    private val pickFileLauncher =
            registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
                if (uris.isNotEmpty()) {
                    handleImportedUris(uris)
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch_face)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.wf_title)

        tabLayout = findViewById(R.id.tabLayout)
        tabLayout.addTab(tabLayout.newTab().setText(R.string.wf_tab_store))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.wf_tab_watch))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.wf_local)))

        tabLayout.addOnTabSelectedListener(
                object : TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: TabLayout.Tab) {
                        switchFragment(tab.position)
                    }
                    override fun onTabUnselected(tab: TabLayout.Tab) {}
                    override fun onTabReselected(tab: TabLayout.Tab) {}
                }
        )

        switchFragment(0)

        bindService(
                Intent(this, BluetoothService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
        )

        android.util.Log.d("WatchFace", "Registering download receiver (EXPORTED)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    downloadReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                    downloadReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(connection)
        unregisterReceiver(downloadReceiver)
    }

    private fun switchFragment(position: Int) {
        val fragment =
                when (position) {
                    0 -> storeFragment
                    1 -> managerFragment
                    else -> localFragment
                }
        supportFragmentManager.beginTransaction().replace(R.id.fragmentContainer, fragment).commit()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    fun downloadWatchFace(wf: WatchFace) {
        val extension = wf.downloadUrl.substringAfterLast(".", "watch")
        val fileName =
                if (wf.title.endsWith(".$extension", ignoreCase = true)) wf.title
                else "${wf.title}.$extension"

        val request =
                DownloadManager.Request(Uri.parse(wf.downloadUrl))
                        .setTitle(wf.title)
                        .setDescription(getString(R.string.wf_downloading))
                        .setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        .setDestinationInExternalPublicDir(
                                Environment.DIRECTORY_DOWNLOADS,
                                "RTOSify/WatchFaces/Downloaded/$fileName"
                        )
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(true)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        android.util.Log.d("WatchFace", "Enqueued download id: $downloadId, file: $fileName")
        // Store relative path so receiver knows where to look
        downloadMap[downloadId] = "Downloaded/$fileName"
        Toast.makeText(this, R.string.wf_downloading, Toast.LENGTH_SHORT).show()
    }

    private val downloadReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                        android.util.Log.d("WatchFace", "Download complete received for id: $id")
                        val relativePath = downloadMap.remove(id)
                        if (relativePath != null) {
                            val file =
                                    File(
                                            File(
                                                    Environment.getExternalStoragePublicDirectory(
                                                            Environment.DIRECTORY_DOWNLOADS
                                                    ),
                                                    "RTOSify/WatchFaces"
                                            ),
                                            relativePath
                                    )
                            android.util.Log.d(
                                    "WatchFace",
                                    "Resolved file: ${file.absolutePath}, exists: ${file.exists()}"
                            )
                            if (file.exists()) {
                                android.util.Log.d(
                                        "WatchFace",
                                        "Triggering auto-transfer for: ${file.name}"
                                )
                                runOnUiThread { transferWatchFace(file) }
                            } else {
                                android.util.Log.e(
                                        "WatchFace",
                                        "File does not exist: ${file.absolutePath}"
                                )
                            }
                        } else {
                            android.util.Log.w(
                                    "WatchFace",
                                    "No filename found in map for download id: $id. map keys: ${downloadMap.keys}"
                            )
                        }
                        // Refresh manager fragment
                        managerFragment.refresh()
                    }
                }
            }



    private fun showProgressDialog(message: String) {
        runOnUiThread {
            if (progressDialog == null) {
                val builder = MaterialAlertDialogBuilder(this)
                val view = layoutInflater.inflate(R.layout.dialog_upload_progress, null)
                builder.setView(view)
                builder.setCancelable(false)
                progressDialog = builder.create()
                progressDialog?.show()
            }
            progressDialog?.findViewById<android.widget.TextView>(R.id.tvUploadDescription)?.text =
                    message
            progressDialog?.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBarUpload)
                    ?.progress = 0
            progressDialog?.findViewById<android.widget.TextView>(R.id.tvUploadPercentage)?.text =
                    getString(R.string.percent_format, 0)
        }
    }

    private fun dismissProgressDialog() {
        runOnUiThread {
            progressDialog?.dismiss()
            progressDialog = null
        }
    }

    fun requestWatchFileList(path: String) {
        bluetoothService?.sendMessage(com.ailife.rtosify.ProtocolHelper.createRequestFileList(path))
    }

    fun sendBluetoothMessage(msg: com.ailife.rtosify.ProtocolMessage) {
        bluetoothService?.sendMessage(msg)
    }

    fun deleteWatchFaceOnWatch(path: String) {
        val msg = com.ailife.rtosify.ProtocolHelper.createDeleteFile(path)
        bluetoothService?.sendMessage(msg)
        tabLayout.postDelayed({ managerFragment.refresh() }, 1000)
    }

    fun renameWatchFile(oldPath: String, newName: String) {
        // Calculate new path
        val parentPath = oldPath.substringBeforeLast("/")
        val newPath = "$parentPath/$newName"

        val msg = com.ailife.rtosify.ProtocolHelper.createRenameFile(oldPath, newPath)
        bluetoothService?.sendMessage(msg)
        tabLayout.postDelayed({ managerFragment.refresh() }, 1000)
    }

    fun createFolder(path: String) {
        val msg = com.ailife.rtosify.ProtocolHelper.createCreateFolder(path)
        bluetoothService?.sendMessage(msg)
        tabLayout.postDelayed({ managerFragment.refresh() }, 1000)
    }

    private val previewQueue = LinkedList<String>()
    private var isFetchingPreview = false
    private val queueLock = Any()

    fun requestPreview(path: String) {
        synchronized(queueLock) {
            // If already fetching THIS path, do nothing? 
            // Actually, we can't easily know WHICH path is fetching without storing it.
            // But standard behaviour:
            previewQueue.remove(path)
            previewQueue.addFirst(path)
        }
        processNextPreview()
    }

    fun cancelPreviewRequest(path: String) {
         synchronized(queueLock) {
            previewQueue.remove(path)
         }
    }

    private fun processNextPreview() {
        var path: String? = null
        
        synchronized(queueLock) {
            if (isFetchingPreview) return
            if (previewQueue.isEmpty()) return
            
            path = previewQueue.pollFirst()
             // Check if we already have a preview? (Optional optimization)
            if (path != null) {
                isFetchingPreview = true
            }
        }

        if (path != null) {
            android.util.Log.d("WatchFace", "Sending request for: $path")
            bluetoothService?.sendMessage(com.ailife.rtosify.ProtocolHelper.createRequestPreview(path!!))
            
            // Safety timeout
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                synchronized(queueLock) {
                    if (isFetchingPreview) {
                        android.util.Log.w("WatchFace", "Preview timeout for $path")
                        isFetchingPreview = false
                    }
                }
                // Trigger outside lock to avoid nested if processNextPreview locks
                processNextPreview()
            }, 5000)
        }
    }

    private fun handleImportedUris(uris: List<Uri>) {
        val progressDialog = android.app.ProgressDialog(this)
        progressDialog.setMessage(getString(R.string.wf_import))
        progressDialog.setCancelable(false)
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            for (uri in uris) {
                try {
                    val inputStream = contentResolver.openInputStream(uri) ?: continue
                    // Use a more unique name if needed, or keep original
                    val originalName = getFileName(uri)
                    val fileName = originalName ?: "imported_wf_${System.currentTimeMillis()}.watch"
                    
                    val destFile =
                            File(
                                    File(
                                            Environment.getExternalStoragePublicDirectory(
                                                    Environment.DIRECTORY_DOWNLOADS
                                            ),
                                            "RTOSify/WatchFaces/Imported"
                                    ),
                                    fileName
                            )
                    if (!destFile.parentFile.exists()) destFile.parentFile.mkdirs()

                    destFile.outputStream().use { output -> inputStream.copyTo(output) }
                    successCount++
                } catch (e: Exception) {
                    android.util.Log.e("WatchFace", "Failed to import ${uri.path}", e)
                }
            }
            
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (successCount > 0) {
                    localFragment.refresh()
                    Toast.makeText(this@WatchFaceActivity, getString(R.string.wf_import_success, "$successCount files"), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@WatchFaceActivity, getString(R.string.wf_import_failed_files), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun pickWatchFaceFromDevice() {
        pickFileLauncher.launch("*/*")
    }

    private fun handleImportedUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val fileName = getFileName(uri) ?: "imported_wf.watch"
            val destFile =
                    File(
                            File(
                                    Environment.getExternalStoragePublicDirectory(
                                            Environment.DIRECTORY_DOWNLOADS
                                    ),
                                    "RTOSify/WatchFaces"
                            ),
                            fileName
                    )
            destFile.parentFile?.mkdirs()

            destFile.outputStream().use { output -> inputStream.copyTo(output) }
            // File saved, will auto-transfer after download complete
            localFragment.refresh()
            Toast.makeText(this, getString(R.string.wf_import_success, fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.wf_import_fail, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    fun transferWatchFace(file: File) {
        val remotePath = "ClockSkin/${file.name}" // Relative path for watch face
        transferQueue.offer(TransferRequest(file, remotePath))
        if (!isProcessingQueue) {
            processNextTransfer()
        } else {
            Toast.makeText(this, getString(R.string.file_queue_added, transferQueue.size), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun processNextTransfer() {
        if (transferQueue.isEmpty()) {
            isProcessingQueue = false
            currentTransferFile = null
            return
        }
        
        isProcessingQueue = true
        val request = transferQueue.poll()
        val file = request?.file
        currentTransferFile = file
        
        if (file == null || !file.exists()) {
             processNextTransfer()
             return
        }

        // Show Progress Dialog
        showProgressDialog(getString(R.string.upload_transferring).replace("...", ""), file.name)
        
        // Use the remote path from request or default
        val relPath = request!!.remotePath.ifEmpty { "ClockSkin/${file.name}" }
        // Delay slightly to allow dialog to show? No, execute immediately.
        bluetoothService?.sendFile(file, FileTransferData.TYPE_WATCHFACE, relPath)
    }

    private fun showProgressDialog(title: String, message: String) {
        runOnUiThread {
            if (progressDialog == null || progressDialog?.isShowing == false) {
                val builder = MaterialAlertDialogBuilder(this)
                val view = layoutInflater.inflate(R.layout.dialog_upload_progress, null)
                builder.setView(view)
                builder.setCancelable(false)
                progressDialog = builder.create()
                progressDialog?.show()
                
                view.findViewById<android.widget.Button>(R.id.btnUploadCancel).setOnClickListener {
                    android.util.Log.d("WatchFace", "Cancel button clicked in dialog. bluetoothService is null: ${bluetoothService == null}")
                    bluetoothService?.cancelTransfer()
                    transferQueue.clear()
                    isProcessingQueue = false
                    dismissProgressDialog()
                    Toast.makeText(this, getString(R.string.file_transfer_cancelled), Toast.LENGTH_SHORT).show()
                }
                view.findViewById<android.widget.Button>(R.id.btnUploadOk).setOnClickListener {
                    dismissProgressDialog()
                    isProcessingQueue = false // Ensure focused reset
                }
            }
            // Update content
            progressDialog?.findViewById<android.widget.TextView>(R.id.tvUploadTitle)?.text = title
            progressDialog?.findViewById<android.widget.TextView>(R.id.tvUploadDescription)?.text = message
            progressDialog?.findViewById<android.widget.TextView>(R.id.tvUploadPercentage)?.text = getString(R.string.percent_format, 0)
            progressDialog?.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBarUpload)?.progress = 0
            progressDialog?.findViewById<android.widget.Button>(R.id.btnUploadOk)?.visibility = View.GONE
            progressDialog?.findViewById<android.widget.Button>(R.id.btnUploadCancel)?.visibility = View.VISIBLE
        }
    }

    fun applyWatchFace(relPath: String, fileUri: Uri?) {
        // Send command to watch to set watch face via Bluetooth
        val msg = com.ailife.rtosify.ProtocolHelper.createSetWatchFace(relPath)
        bluetoothService?.sendMessage(msg)
        Toast.makeText(this, getString(R.string.wf_setting, relPath), Toast.LENGTH_SHORT).show()
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    result =
                            it.getString(
                                    it.getColumnIndexOrThrow(
                                            android.provider.OpenableColumns.DISPLAY_NAME
                                    )
                            )
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
        runOnUiThread {
            if (progress >= 100 || progress < 0) {
                if (progress == 100) {
                    if (transferQueue.isEmpty()) {
                        // All done
                        isProcessingQueue = false
                        Toast.makeText(this, getString(R.string.wf_transfer_complete), Toast.LENGTH_SHORT).show()
                        
                         // Update UI to show completion
                         progressDialog?.findViewById<android.widget.Button>(R.id.btnUploadOk)?.visibility = View.VISIBLE
                         progressDialog?.findViewById<android.widget.Button>(R.id.btnUploadCancel)?.visibility = View.GONE
                         progressDialog?.findViewById<android.widget.TextView>(R.id.tvUploadTitle)?.text = getString(R.string.upload_complete_title)
                         progressDialog?.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBarUpload)?.progress = 100
                         progressDialog?.findViewById<android.widget.TextView>(R.id.tvUploadPercentage)?.text = getString(R.string.percent_100)
                    } else {
                         // Next item - keep dialog open
                         android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                             processNextTransfer()
                         }, 500)
                    }
                } else {
                    // Error case
                    isProcessingQueue = false
                    transferQueue.clear()
                    dismissProgressDialog()
                    Toast.makeText(this, getString(R.string.wf_transfer_failed), Toast.LENGTH_SHORT).show()
                }
            } else {
                progressDialog?.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBarUpload)
                        ?.progress = progress
                progressDialog?.findViewById<android.widget.TextView>(R.id.tvUploadPercentage)
                        ?.text = getString(R.string.percent_format, progress)
            }
        }
    }

    override fun onStatusChanged(status: String) {}
    override fun onAppListReceived(appsJson: String) {}
    override fun onDownloadProgress(progress: Int, file: java.io.File?) {}
    override fun onFileListReceived(path: String, filesJson: String) {
        val clockSkinRoot = "Android/data/com.ailife.ClockSkinCoco/files/ClockSkin"
        val normalizedPath = path.removePrefix("/").removeSuffix("/")
        val normalizedTarget = clockSkinRoot.removePrefix("/").removeSuffix("/")

        if (normalizedPath.contains("ClockSkin", ignoreCase = true)) {
            // Check if this is the target root folder (ignoring potential /sdcard/ or
            // /storage/emulated/0 prefixes)
            if (normalizedPath == normalizedTarget || normalizedPath.endsWith("/$normalizedTarget")
            ) {
                // Root folder
                managerFragment.updateList(filesJson)
            } else if (normalizedPath.contains(normalizedTarget)) {
                // Subfolder of our target
                managerFragment.updateFolderContents(path, filesJson)
            }
        }
    }
    override fun onDeviceConnected(deviceName: String) {}
    override fun onDeviceDisconnected() {}
    override fun onScanResult(devices: List<android.bluetooth.BluetoothDevice>) {}
    override fun onWatchStatusUpdated(
            batteryLevel: Int,
            isCharging: Boolean,
            wifiSsid: String,
            wifiEnabled: Boolean,
            dndEnabled: Boolean,
            ipAddress: String?,
            wifiState: String?
    ) {}
    override fun onPreviewReceived(path: String, imageBase64: String?, textContent: String?) {
        synchronized(queueLock) {
            isFetchingPreview = false
        }
        if (imageBase64 != null) {
            managerFragment.updatePreview(path, imageBase64)
        }
        processNextPreview()
    }

    override fun onError(message: String) {
        dismissProgressDialog()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        
        var wasFetching = false
        synchronized(queueLock) {
            if (isFetchingPreview) {
                isFetchingPreview = false
                wasFetching = true
            }
        }
        if (wasFetching) {
            processNextPreview()
        }
    }

    override fun onDeviceInfoReceived(info: DeviceInfoData) {}
}

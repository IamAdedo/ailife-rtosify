package com.ailife.rtosify

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray

import android.graphics.PorterDuff
import android.view.Menu
import android.view.MenuItem

class FileManagerActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    private lateinit var toolbar: Toolbar
    private lateinit var tvCurrentPath: TextView
    private lateinit var recyclerViewFiles: RecyclerView
    private lateinit var fabUpload: FloatingActionButton
    private lateinit var progressBarFiles: ProgressBar

    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private val pathStack = Stack<String>()
    private var currentFiles = mutableListOf<FileInfo>()

    private var fileAdapter: FileAdapter? = null

    // Clipboard
    private var clipboardFiles = mutableListOf<String>()
    private var clipboardOperation = "" // "COPY" or "MOVE"
    private var clipboardSourcePath = ""
    
    // Transfer Queue
    private val transferQueue = java.util.LinkedList<TransferRequest>()
    private var isProcessingQueue = false
    
    data class TransferRequest(val type: TransferType, val path: String, val uri: Uri? = null, val remoteDir: String? = null)
    enum class TransferType { DOWNLOAD, UPLOAD }

    // Dialog References (similar to AppListActivity)
    private var transferDialog: AlertDialog? = null

    private var transferProgressBar: ProgressBar? = null
    private var transferPercentageText: TextView? = null
    private var transferDescriptionText: TextView? = null
    private var transferTitleText: TextView? = null
    private var transferIconView: ImageView? = null
    private var transferOkButton: android.widget.Button? = null
    private var transferCancelButton: android.widget.Button? = null
    private var transferViewFileButton: android.widget.Button? = null
    private var downloadedFile: File? = null
    
    private var totalTransferCount = 0
    private var currentTransferIndex = 0

    private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(className: ComponentName, service: IBinder) {
                    val binder = service as BluetoothService.LocalBinder
                    bluetoothService = binder.getService()
                    bluetoothService?.callback = this@FileManagerActivity
                    isBound = true

                    // Initial request
                    requestFileList("/")
                }

                override fun onServiceDisconnected(arg0: ComponentName) {
                    isBound = false
                    bluetoothService = null
                }
            }

    private val pickFileLauncher =
            registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    confirmUpload(uris)
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_manager)
        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        val recyclerView = findViewById<View>(R.id.recyclerViewFiles)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, recyclerView)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.file_manager)
        toolbar.setNavigationOnClickListener { onBackPressed() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_download -> {
                    downloadSelectedFiles()
                    true
                }
                R.id.action_rename -> {
                    showRenameDialog()
                    true
                }
                R.id.action_copy -> {
                    copySelectedFiles()
                    true
                }
                R.id.action_move -> {
                    moveSelectedFiles()
                    true
                }
                R.id.action_delete -> {
                    deleteSelectedFiles()
                    true
                }
                R.id.action_select_all -> {
                    fileAdapter?.selectAll()
                    updateMenu()
                    true
                }
                R.id.action_paste -> {
                    pasteFiles()
                    true
                }
                R.id.action_refresh -> {
                    val current = if (pathStack.isEmpty()) "/" else pathStack.peek()
                    requestFileList(current)
                    true
                }
                android.R.id.home -> {
                    onBackPressed()
                    true
                }
                else -> false
            }
        }
        
        // Ensure popup menu is themed correctly or use white icons
        // Since we can't easily change popup theme dynamically in code without ContextThemeWrapper in layout,
        // we'll at least fix the overflow icon tint.
        toolbar.post { 
             toolbar.overflowIcon?.setTint(Color.WHITE)
        }

        tvCurrentPath = findViewById(R.id.tvCurrentPath)
        recyclerViewFiles = findViewById(R.id.recyclerViewFiles)
        fabUpload = findViewById(R.id.fabUpload)
        progressBarFiles = findViewById(R.id.progressBarFiles)

        recyclerViewFiles.layoutManager = LinearLayoutManager(this)
        val adapter = FileAdapter(
            currentFiles,
            onItemClick = { fileInfo ->
                if (fileAdapter?.selectionMode == true) {
                    toggleSelection(fileInfo)
                } else if (fileInfo.isDirectory) {
                    val newPath =
                        if (pathStack.isEmpty()) {
                            "/" + fileInfo.name
                        } else {
                            val current = pathStack.peek()
                            if (current == "/") "/${fileInfo.name}"
                            else "$current/${fileInfo.name}"
                        }
                    requestFileList(newPath)
                } else {
                    val fullPath = getFullPath(fileInfo.name)
                    bluetoothService?.requestPreview(fullPath)
                    Toast.makeText(this, "Requesting preview...", Toast.LENGTH_SHORT).show()
                }
            },
            onDownloadClick = { fileInfo ->
                if (!fileInfo.isDirectory) {
                    val fullPath = getFullPath(fileInfo.name)
                    queueTransfer(TransferRequest(TransferType.DOWNLOAD, fullPath))
                }
            },
            onDeleteClick = { fileInfo -> confirmDelete(fileInfo) },
            onLongClick = { fileInfo ->
                if (fileAdapter?.selectionMode == false) {
                    fileAdapter?.selectionMode = true
                    toggleSelection(fileInfo)
                    true
                } else {
                    false
                }
            }
        )
        recyclerViewFiles.adapter = adapter
        fileAdapter = adapter


        fabUpload.setOnClickListener { pickFileLauncher.launch("*/*") }

        bindService(Intent(this, BluetoothService::class.java), connection, BIND_AUTO_CREATE)
    }

    private fun getFullPath(fileName: String): String {
        val current = if (pathStack.isEmpty()) "/" else pathStack.peek()
        return if (current == "/") "/$fileName" else "$current/$fileName"
    }

    private fun requestFileList(path: String) {
        progressBarFiles.visibility = View.VISIBLE
        bluetoothService?.requestFileList(path)
    }

    private fun confirmUpload(uris: List<Uri>) {
        val currentPath = if (pathStack.isEmpty()) "/" else pathStack.peek()
        AlertDialog.Builder(this)
                .setTitle(R.string.file_upload_confirm_title)
                .setMessage(getString(R.string.file_upload_confirm_desc) + "\n(${uris.size} files)")
                .setPositiveButton(R.string.file_button_upload) { _, _ ->
                     totalTransferCount = uris.size
                     currentTransferIndex = 0
                     uris.forEach { uri ->
                        queueTransfer(TransferRequest(TransferType.UPLOAD, "", uri, currentPath))
                     }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun queueTransfer(request: TransferRequest) {
        transferQueue.add(request)
        if (!isProcessingQueue) {
            processNextTransfer()
        } else {
            Toast.makeText(this, "Added to queue (${transferQueue.size})", Toast.LENGTH_SHORT).show()
            // If queue is stuck (isProcessingQueue true but no active transfer), we might need a timeout or check?
            // For now, rely on consistent completion callbacks.
        }
    }

    private fun processNextTransfer() {
        if (transferQueue.isEmpty()) {
            isProcessingQueue = false
            return
        }
        
        isProcessingQueue = true
        val request = transferQueue.peek()
        currentTransferIndex++
        
        val total = if (totalTransferCount > 0) totalTransferCount else transferQueue.size // Fallback
        val progressStr = "Processing ${currentTransferIndex}/$total"
        
        if (request.type == TransferType.DOWNLOAD) {
            bluetoothService?.requestFileDownload(request.path)
            showTransferDialog(
                getString(R.string.file_downloading) + " ($currentTransferIndex/$total)",
                getString(R.string.file_getting_from_watch, request.path.substringAfterLast('/')),
                TransferType.DOWNLOAD
            )
        } else if (request.type == TransferType.UPLOAD) {
            request.uri?.let { uri ->
                 val remoteDir = request.remoteDir ?: "/"
                 val fileName = uploadFile(uri, remoteDir)
                 
                 if (fileName != null) {
                     showTransferDialog(
                        getString(R.string.file_uploading) + " ($currentTransferIndex/$total)",
                        getString(R.string.file_sending_to_watch, fileName),
                        TransferType.UPLOAD
                     )
                 } else {
                     // Error starting upload
                     Toast.makeText(this, "Failed to read file: $uri", Toast.LENGTH_SHORT).show()
                     // Treat as error/done to process next?
                     updateTransferProgress(-1, "", "")
                 }
            }
        }
    }

    private fun showTransferDialog(title: String, description: String, type: TransferType) {
        if (transferDialog?.isShowing == true) {
            // Update existing
            transferTitleText?.text = title
            transferDescriptionText?.text = description
            transferProgressBar?.progress = 0
            transferPercentageText?.text = "0%"
            transferOkButton?.visibility = View.GONE
            transferCancelButton?.visibility = View.VISIBLE
            
            val iconRes = if (type == TransferType.DOWNLOAD) android.R.drawable.stat_sys_download else android.R.drawable.ic_menu_upload
            transferIconView?.setImageResource(iconRes)
            transferIconView?.colorFilter = null // Reset color
            return
        }
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_upload_progress, null)
        transferProgressBar = dialogView.findViewById(R.id.progressBarUpload)
        transferPercentageText = dialogView.findViewById(R.id.tvUploadPercentage)
        transferDescriptionText = dialogView.findViewById(R.id.tvUploadDescription)
        transferTitleText = dialogView.findViewById(R.id.tvUploadTitle)
        transferIconView = dialogView.findViewById(R.id.imgUploadIcon)
        transferOkButton = dialogView.findViewById(R.id.btnUploadOk)

        transferCancelButton = dialogView.findViewById(R.id.btnUploadCancel)

        transferTitleText?.text = title
        transferDescriptionText?.text = description
        transferOkButton?.setOnClickListener { dismissTransferDialog() }
        transferCancelButton?.setOnClickListener {
             bluetoothService?.cancelTransfer()
             // Immediate UI feedback
             onTransferCancelled()
        }
        transferViewFileButton = dialogView.findViewById(R.id.btnViewFile)
        transferViewFileButton?.setOnClickListener { viewDownloadedFile() }

        transferDialog = AlertDialog.Builder(this).setView(dialogView).setCancelable(false).create()
        transferDialog?.show()
    }

    private fun updateTransferProgress(
            progress: Int,
            successTitle: String,
            successMessage: String
    ) {
        runOnUiThread {
            when (progress) {
                in 0..99 -> {
                    transferProgressBar?.progress = progress
                    transferPercentageText?.text = getString(R.string.status_progress_format, progress)
                }
                100 -> {
                    // Item complete
                    transferProgressBar?.progress = 100
                    transferPercentageText?.text = getString(R.string.status_progress_format, 100)
                    
                    // Poll current
                    transferQueue.poll()
                    
                    if (transferQueue.isNotEmpty()) {
                        // Continue to next
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            processNextTransfer()
                        }, 500)
                    } else {
                        // All done
                        transferTitleText?.text = successTitle
                        transferDescriptionText?.text = successMessage
                        transferIconView?.setImageResource(android.R.drawable.stat_sys_upload_done)
                        transferIconView?.setColorFilter(android.graphics.Color.GREEN)
                        transferProgressBar?.visibility = View.GONE
                        transferOkButton?.visibility = View.VISIBLE
                        transferOkButton?.visibility = View.VISIBLE
                        transferCancelButton?.visibility = View.GONE

                        isProcessingQueue = false // Reset queue state

                        
                        // Show View File button only for SINGLE downloads
                        if (downloadedFile != null && totalTransferCount == 1) {
                            transferViewFileButton?.visibility = View.VISIBLE
                        }
                    }
                }
                -1 -> {
                    transferProgressBar?.visibility = View.GONE
                    transferTitleText?.text = getString(R.string.status_failed)
                    transferDescriptionText?.text = getString(R.string.file_error_transfer)
                    transferIconView?.setImageResource(android.R.drawable.stat_notify_error)
                    transferIconView?.setColorFilter(android.graphics.Color.RED)
                    transferPercentageText?.text = getString(R.string.status_fail_short)
                    transferPercentageText?.setTextColor(android.graphics.Color.RED)
                    transferOkButton?.visibility = View.VISIBLE
                    transferCancelButton?.visibility = View.GONE
                    
                    // On error, we still proceed? Or stop?
                    // Let's stop queue on error for safety
                    isProcessingQueue = false
                    transferQueue.clear()
                }
            }
        }
    }

    private fun dismissTransferDialog() {
        transferDialog?.dismiss()
        transferDialog = null
        downloadedFile = null
        // Refresh list
        if (!pathStack.isEmpty()) requestFileList(pathStack.peek())
    }

    private fun viewDownloadedFile() {
        val file = downloadedFile ?: return

        try {
            val uri =
                    androidx.core.content.FileProvider.getUriForFile(
                            this,
                            "${applicationContext.packageName}.fileprovider",
                            file
                    )

            val intent =
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, getMimeType(file))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, getString(R.string.file_error_no_app), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.file_error_open, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    private fun uploadFile(uri: Uri, remoteDir: String): String? {
        val fileName = getFileNameFromUri(uri) ?: "upload_${System.currentTimeMillis()}"
        val remotePath = if (remoteDir == "/") "/$fileName" else "$remoteDir/$fileName"

        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val tempFile = File(cacheDir, "temp_upload")
                tempFile.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
                bluetoothService?.sendFile(tempFile, "REGULAR", remotePath)
                return fileName
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) name = it.getString(index)
            }
        }
        return name
    }

    private fun confirmDelete(fileInfo: FileInfo) {
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.file_delete_confirm_title, fileInfo.name))
                .setMessage(
                        getString(R.string.file_delete_confirm_desc, if (fileInfo.isDirectory) getString(R.string.file_type_directory) else getString(R.string.file_type_regular))
                )
                .setPositiveButton(R.string.file_button_delete) { _, _ ->
                    val fullPath = getFullPath(fileInfo.name)
                    bluetoothService?.deleteRemoteFile(fullPath)
                    progressBarFiles.visibility = View.VISIBLE
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (fileAdapter?.selectionMode == true) {
            exitSelectionMode()
            return
        }
        if (pathStack.size > 1) {
            pathStack.pop() // Remove current
            val previous = pathStack.peek()
            pathStack.pop() // Remove again to re-push in onFileListReceived logic if needed?
            // Actually, requestFileList will trigger onFileListReceived which pushes to stack.
            // So we just request the previous path.
            requestFileList(previous)
        } else {
            super.onBackPressed()
        }
    }

    // Service Callbacks
    override fun onStatusChanged(status: String) {}
    override fun onDeviceConnected(deviceName: String) {}
    override fun onDeviceDisconnected() {
        finish()
    }
    override fun onError(message: String) {
        runOnUiThread {
            progressBarFiles.visibility = View.GONE
            if (transferDialog?.isShowing == true) {
                updateTransferProgress(-1, "", "")
                transferDescriptionText?.text = message
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    override fun onScanResult(devices: List<android.bluetooth.BluetoothDevice>) {}
    override fun onAppListReceived(appsJson: String) {}
    override fun onUploadProgress(progress: Int) {
        updateTransferProgress(progress, getString(R.string.file_upload_success_title), getString(R.string.file_upload_success_desc))
    }
    override fun onDownloadProgress(progress: Int, file: java.io.File?) {
        if (progress == 100 && file != null) {
            downloadedFile = file
        }
        updateTransferProgress(progress, getString(R.string.file_download_success_title), getString(R.string.file_download_success_desc))
    }

    override fun onFileListReceived(path: String, filesJson: String) {
        runOnUiThread {
            progressBarFiles.visibility = View.GONE
            tvCurrentPath.text = path

            // Manage stack
            if (pathStack.isEmpty() || pathStack.peek() != path) {
                if (!pathStack.contains(path)) {
                    pathStack.push(path)
                } else {
                    // Backtracking
                    while (pathStack.peek() != path) {
                        pathStack.pop()
                    }
                }
            }

            // Parse files
            currentFiles.clear()
            try {
                val jsonArray = JSONArray(filesJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    currentFiles.add(
                            FileInfo(
                                    name = obj.getString("name"),
                                    size = obj.getLong("size"),
                                    isDirectory = obj.getBoolean("isDirectory"),
                                    lastModified = obj.getLong("lastModified")
                            )
                    )
                }
                // Sort: Dir first, then name
                currentFiles.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                recyclerViewFiles.adapter?.notifyDataSetChanged()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.file_error_parse, e.message), Toast.LENGTH_SHORT)
                        .show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            bluetoothService?.callback = null
            try { unbindService(connection) } catch(e: Exception) {}
            isBound = false
        }
    }

    override fun onPreviewReceived(path: String, imageBase64: String?, textContent: String?) {
        runOnUiThread {
             val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_file_preview, null)
             val imgPreview = dialogView.findViewById<ImageView>(R.id.imgPreview)
             val tvPreview = dialogView.findViewById<TextView>(R.id.tvPreviewContent)
             val tvTitle = dialogView.findViewById<TextView>(R.id.tvPreviewTitle)
             
             tvTitle.text = path.substringAfterLast('/')
             
             if (imageBase64 != null) {
                 try {
                     val decodedString = android.util.Base64.decode(imageBase64, android.util.Base64.DEFAULT)
                     val decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                     imgPreview.setImageBitmap(decodedByte)
                     imgPreview.visibility = View.VISIBLE
                     tvPreview.visibility = View.GONE
                 } catch (e: Exception) {
                     Toast.makeText(this, "Error decoding image", Toast.LENGTH_SHORT).show()
                 }
             } else if (textContent != null) {
                 tvPreview.text = textContent
                 tvPreview.visibility = View.VISIBLE
                 imgPreview.visibility = View.GONE
             } else {
                 Toast.makeText(this, "Empty preview received", Toast.LENGTH_SHORT).show()
                 return@runOnUiThread
             }
             
             AlertDialog.Builder(this)
                 .setView(dialogView)
                 .setPositiveButton("Close", null)
                 .show()
        }
    }
    
    override fun onTransferCancelled() {
        runOnUiThread {
             dismissTransferDialog()
             Toast.makeText(this, "Transfer cancelled", Toast.LENGTH_SHORT).show()
             
             // Stop queue
             isProcessingQueue = false
             transferQueue.clear()
        }
    }
    
    // --- Selection and Menu ---
    
    private fun updateMenu() {
        toolbar.menu.clear()
        val selectionMode = fileAdapter?.selectionMode == true
        
        if (selectionMode) {
            menuInflater.inflate(R.menu.menu_file_selection, toolbar.menu)
            val count = fileAdapter?.selectedItems?.size ?: 0
            supportActionBar?.title = "$count Selected"
            
            
            // Set nav icon to Close
            supportActionBar?.setHomeAsUpIndicator(android.R.drawable.ic_menu_close_clear_cancel)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            
            // Tint menu items white
            for (i in 0 until toolbar.menu.size()) {
                toolbar.menu.getItem(i).icon?.setTint(Color.WHITE)
            }
            
            // Rename only allowed for single selection
            val renameItem = toolbar.menu.findItem(R.id.action_rename)
            renameItem?.isVisible = count == 1
        } else {
            menuInflater.inflate(R.menu.menu_file_manager, toolbar.menu)
            supportActionBar?.title = getString(R.string.file_manager)
            
            supportActionBar?.setHomeAsUpIndicator(null) // Restore default
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            
            val pasteItem = toolbar.menu.findItem(R.id.action_paste)
            pasteItem.isVisible = clipboardFiles.isNotEmpty()
            
            // Tint menu items white
            for (i in 0 until toolbar.menu.size()) {
                toolbar.menu.getItem(i).icon?.setTint(Color.WHITE)
            }
        }
    }
    
    private fun showRenameDialog() {
        val selected = fileAdapter?.selectedItems?.toList() ?: return
        if (selected.size != 1) return
        val fileInfo = selected[0]
        val oldPath = getFullPath(fileInfo.name)
        
        val input = android.widget.EditText(this)
        input.setText(fileInfo.name)
        input.setSelection(fileInfo.name.length)
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_title))
            .setMessage(getString(R.string.rename_message, fileInfo.name))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != fileInfo.name) {
                    val currentDir = if (pathStack.isEmpty()) "/" else pathStack.peek()
                    val newPath = if (currentDir == "/") "/$newName" else "$currentDir/$newName"
                    bluetoothService?.renameRemoteFile(oldPath, newPath)
                    exitSelectionMode()
                    // Refresh not needed immediately if we wait for response, but for now we can just request list or wait.
                    // Ideally we should have a callback for rename success? 
                    // Protocol doesn't guarantee response for rename usually unless error.
                    // But "requestFileList" call after a short delay might be good.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        requestFileList(currentDir)
                    }, 500)
                } else if (newName.isEmpty()) {
                     Toast.makeText(this, getString(R.string.rename_error_empty), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadSelectedFiles() {
        val selected = fileAdapter?.selectedItems?.toList() ?: return
        if (selected.isEmpty()) return
        
        Toast.makeText(this, getString(R.string.file_downloading), Toast.LENGTH_SHORT).show()
        
        totalTransferCount = selected.size
        currentTransferIndex = 0
        
        selected.forEach { file ->
            if (!file.isDirectory) {
               val fullPath = getFullPath(file.name)
               queueTransfer(TransferRequest(TransferType.DOWNLOAD, fullPath))
            }
        }
        exitSelectionMode()
    }
    
    private fun copySelectedFiles() {
        val selected = fileAdapter?.selectedItems?.toList() ?: return
        clipboardFiles.clear()
        selected.forEach { clipboardFiles.add(getFullPath(it.name)) }
        clipboardOperation = "COPY"
        clipboardSourcePath = if (pathStack.isEmpty()) "/" else pathStack.peek()
        
        Toast.makeText(this, getString(R.string.msg_copied_clipboard, selected.size), Toast.LENGTH_SHORT).show()
        exitSelectionMode()
    }
    
    private fun moveSelectedFiles() {
        val selected = fileAdapter?.selectedItems?.toList() ?: return
        clipboardFiles.clear()
        selected.forEach { clipboardFiles.add(getFullPath(it.name)) }
        clipboardOperation = "MOVE"
        clipboardSourcePath = if (pathStack.isEmpty()) "/" else pathStack.peek()
        
        Toast.makeText(this, getString(R.string.msg_moved_clipboard, selected.size), Toast.LENGTH_SHORT).show()
        exitSelectionMode()
    }
    
    private fun deleteSelectedFiles() {
         val selected = fileAdapter?.selectedItems?.toList() ?: return
         if (selected.isEmpty()) return

         AlertDialog.Builder(this)
             .setTitle(getString(R.string.file_delete_confirm_title, "${selected.size} items"))
             .setMessage("Are you sure you want to delete these ${selected.size} items?")
             .setPositiveButton(R.string.file_button_delete) { _, _ ->
                 val paths = selected.map { getFullPath(it.name) }
                 bluetoothService?.deleteRemoteFiles(paths)
                 progressBarFiles.visibility = View.VISIBLE
                 exitSelectionMode()
             }
             .setNegativeButton(android.R.string.cancel, null)
             .show()
    }
    
    private fun pasteFiles() {
        if (clipboardFiles.isEmpty()) return
        val currentPath = if (pathStack.isEmpty()) "/" else pathStack.peek()
        
        if (clipboardOperation == "MOVE") {
             bluetoothService?.moveRemoteFiles(clipboardFiles, currentPath)
             clipboardFiles.clear() // Clear after move
             clipboardOperation = ""
        } else if (clipboardOperation == "COPY") {
             bluetoothService?.copyRemoteFiles(clipboardFiles, currentPath)
             // Keep clipboard for multiple pastes? Standard behavior usually yes.
        }
        
        Toast.makeText(this, getString(R.string.msg_paste_success), Toast.LENGTH_SHORT).show()
        updateMenu() // Update paste icon visibility
        progressBarFiles.visibility = View.VISIBLE
    }
    
    private fun exitSelectionMode() {
        fileAdapter?.selectionMode = false
        updateMenu()
    }

    private fun toggleSelection(file: FileInfo) {
        fileAdapter?.toggleSelection(file)
        updateMenu()
    }
    
    // Need to update menu when file list loads (to reset selection if any error, or just general state)
    // Actually onFileListReceived clears selection if we navigated properly?
    // Let's add updateMenu() to onFileListReceived
    
    // ...
    
    // ViewHolder & Adapter
    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        val imgIcon: ImageView = view.findViewById(R.id.imgFileIcon)
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val tvInfo: TextView = view.findViewById(R.id.tvFileInfo)
        val btnDownload: ImageView = view.findViewById(R.id.btnDownload)
        val btnDelete: ImageView = view.findViewById(R.id.btnDelete)
    }

    class FileAdapter(
            private val files: List<FileInfo>,
            private val onItemClick: (FileInfo) -> Unit,
            private val onDownloadClick: (FileInfo) -> Unit,
            private val onDeleteClick: (FileInfo) -> Unit,
            private val onLongClick: (FileInfo) -> Boolean
    ) : RecyclerView.Adapter<FileViewHolder>() {

        val selectedItems = mutableSetOf<FileInfo>()
        var selectionMode = false
            set(value) {
                field = value
                if (!value) selectedItems.clear()
                notifyDataSetChanged()
            }

        fun toggleSelection(file: FileInfo) {
            if (selectedItems.contains(file)) {
                selectedItems.remove(file)
            } else {
                selectedItems.add(file)
            }
            notifyDataSetChanged()
        }
        
        fun selectAll() {
            selectedItems.clear()
            selectedItems.addAll(files)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
            val view =
                    LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
            return FileViewHolder(view)
        }

        override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
            val file = files[position]
            holder.tvName.text = file.name

            val sdf = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
            val dateStr = sdf.format(Date(file.lastModified))

            if (file.isDirectory) {
                holder.imgIcon.setImageResource(android.R.drawable.ic_menu_save) // Folder icon
                holder.imgIcon.setColorFilter(Color.parseColor("#FFCA28")) // Yellow
                holder.tvInfo.text = holder.itemView.context.getString(R.string.file_info_dir, dateStr)
                holder.btnDownload.visibility = View.GONE
            } else {
                holder.imgIcon.setImageResource(android.R.drawable.ic_menu_gallery) // File icon
                holder.imgIcon.setColorFilter(Color.parseColor("#42A5F5")) // Blue
                holder.tvInfo.text = holder.itemView.context.getString(R.string.file_info_regular, formatSize(file.size), dateStr)
                holder.btnDownload.visibility = if (selectionMode) View.GONE else View.VISIBLE
            }
            
            // Selection Visuals
            if (selectionMode) {
                holder.btnDelete.visibility = View.GONE
                holder.btnDownload.visibility = View.GONE
                if (selectedItems.contains(file)) {
                    // Darker highlight #339E9E9E
                    holder.itemView.setBackgroundColor(Color.parseColor("#339E9E9E")) 
                } else {
                    holder.itemView.setBackgroundColor(Color.TRANSPARENT)
                }
            } else {
                 holder.itemView.setBackgroundColor(Color.TRANSPARENT)
                 holder.btnDelete.visibility = View.VISIBLE
                 if (!file.isDirectory) holder.btnDownload.visibility = View.VISIBLE
            }

            holder.itemView.setOnClickListener { onItemClick(file) }
            holder.itemView.setOnLongClickListener { onLongClick(file) }
            
            // Individual/Non-selection actions
            holder.btnDownload.setOnClickListener { onDownloadClick(file) }
            holder.btnDelete.setOnClickListener { onDeleteClick(file) }
        }

        override fun getItemCount() = files.size

        private fun formatSize(size: Long): String {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            return String.format(
                    "%.1f %s",
                    size / Math.pow(1024.0, digitGroups.toDouble()),
                    units[digitGroups]
            )
        }
    }
}

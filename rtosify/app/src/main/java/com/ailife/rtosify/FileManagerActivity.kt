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
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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

    // Dialog References (similar to AppListActivity)
    private var transferDialog: AlertDialog? = null
    private var transferProgressBar: ProgressBar? = null
    private var transferPercentageText: TextView? = null
    private var transferDescriptionText: TextView? = null
    private var transferTitleText: TextView? = null
    private var transferIconView: ImageView? = null
    private var transferOkButton: android.widget.Button? = null

    private val connection = object : ServiceConnection {
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

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { confirmUpload(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_manager)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "File Manager"
        toolbar.setNavigationOnClickListener { onBackPressed() }

        tvCurrentPath = findViewById(R.id.tvCurrentPath)
        recyclerViewFiles = findViewById(R.id.recyclerViewFiles)
        fabUpload = findViewById(R.id.fabUpload)
        progressBarFiles = findViewById(R.id.progressBarFiles)

        recyclerViewFiles.layoutManager = LinearLayoutManager(this)
        recyclerViewFiles.adapter = FileAdapter(currentFiles, 
            onItemClick = { fileInfo ->
                if (fileInfo.isDirectory) {
                    val newPath = if (pathStack.isEmpty()) {
                        "/" + fileInfo.name
                    } else {
                        val current = pathStack.peek()
                        if (current == "/") "/${fileInfo.name}" else "$current/${fileInfo.name}"
                    }
                    requestFileList(newPath)
                }
            },
            onDownloadClick = { fileInfo ->
                if (!fileInfo.isDirectory) {
                    val fullPath = getFullPath(fileInfo.name)
                    bluetoothService?.requestFileDownload(fullPath)
                    showTransferDialog("Downloading File", "Getting ${fileInfo.name} from watch...")
                }
            },
            onDeleteClick = { fileInfo ->
                confirmDelete(fileInfo)
            }
        )

        fabUpload.setOnClickListener {
            pickFileLauncher.launch("*/*")
        }

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

    private fun confirmUpload(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Upload File")
            .setMessage("Upload this file to the current directory on the watch?")
            .setPositiveButton("Upload") { _, _ ->
                val currentPath = if (pathStack.isEmpty()) "/" else pathStack.peek()
                // Copy to temp file to get actual File object if needed, 
                // but let's assume sendFile handles it or we refactor it.
                // Actually BluetoothService.sendApkFile does temp copy.
                // Let's use a similar approach for generic upload.
                uploadFile(uri, currentPath)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTransferDialog(title: String, description: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_upload_progress, null)
        transferProgressBar = dialogView.findViewById(R.id.progressBarUpload)
        transferPercentageText = dialogView.findViewById(R.id.tvUploadPercentage)
        transferDescriptionText = dialogView.findViewById(R.id.tvUploadDescription)
        transferTitleText = dialogView.findViewById(R.id.tvUploadTitle)
        transferIconView = dialogView.findViewById(R.id.imgUploadIcon)
        transferOkButton = dialogView.findViewById(R.id.btnUploadOk)

        transferTitleText?.text = title
        transferDescriptionText?.text = description
        transferOkButton?.setOnClickListener { dismissTransferDialog() }
        
        transferDialog = AlertDialog.Builder(this).setView(dialogView).setCancelable(false).create()
        transferDialog?.show()
    }

    private fun updateTransferProgress(progress: Int, successTitle: String, successMessage: String) {
        runOnUiThread {
            when (progress) {
                in 0..99 -> {
                    transferProgressBar?.progress = progress
                    transferPercentageText?.text = "$progress%"
                }
                100 -> {
                    transferProgressBar?.progress = 100
                    transferPercentageText?.text = "100%"
                    transferTitleText?.text = successTitle
                    transferDescriptionText?.text = successMessage
                    transferIconView?.setImageResource(android.R.drawable.stat_sys_upload_done)
                    transferIconView?.setColorFilter(android.graphics.Color.GREEN)
                    transferProgressBar?.visibility = View.GONE
                    transferOkButton?.visibility = View.VISIBLE
                }
                -1 -> {
                    transferProgressBar?.visibility = View.GONE
                    transferTitleText?.text = "Failed"
                    transferDescriptionText?.text = "An error occurred during transfer."
                    transferIconView?.setImageResource(android.R.drawable.stat_notify_error)
                    transferIconView?.setColorFilter(android.graphics.Color.RED)
                    transferPercentageText?.text = "FAIL"
                    transferPercentageText?.setTextColor(android.graphics.Color.RED)
                    transferOkButton?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun dismissTransferDialog() {
        transferDialog?.dismiss()
        transferDialog = null
        // Refresh list
        if (!pathStack.isEmpty()) requestFileList(pathStack.peek())
    }

    private fun uploadFile(uri: Uri, remoteDir: String) {
        val fileName = getFileNameFromUri(uri) ?: "upload_${System.currentTimeMillis()}"
        val remotePath = if (remoteDir == "/") "/$fileName" else "$remoteDir/$fileName"
        
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val tempFile = File(cacheDir, "temp_upload")
            tempFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            bluetoothService?.sendFile(tempFile, "REGULAR", remotePath)
            showTransferDialog("Uploading File", "Sending $fileName to watch...")
        }
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
            .setTitle("Delete ${fileInfo.name}?")
            .setMessage("Are you sure you want to delete this ${if (fileInfo.isDirectory) "directory" else "file"}?")
            .setPositiveButton("Delete") { _, _ ->
                val fullPath = getFullPath(fileInfo.name)
                bluetoothService?.deleteRemoteFile(fullPath)
                progressBarFiles.visibility = View.VISIBLE
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
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
        updateTransferProgress(progress, "Upload Complete", "File transferred successfully!")
    }
    override fun onDownloadProgress(progress: Int) {
        updateTransferProgress(progress, "Download Complete", "File saved to Downloads folder")
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
                    currentFiles.add(FileInfo(
                        name = obj.getString("name"),
                        size = obj.getLong("size"),
                        isDirectory = obj.getBoolean("isDirectory"),
                        lastModified = obj.getLong("lastModified")
                    ))
                }
                // Sort: Dir first, then name
                currentFiles.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                recyclerViewFiles.adapter?.notifyDataSetChanged()
            } catch (e: Exception) {
                Toast.makeText(this, "Error parsing file list: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            bluetoothService?.callback = null
            unbindService(connection)
            isBound = false
        }
    }

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
        private val onDeleteClick: (FileInfo) -> Unit
    ) : RecyclerView.Adapter<FileViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
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
                holder.tvInfo.text = "Directory | $dateStr"
                holder.btnDownload.visibility = View.GONE
            } else {
                holder.imgIcon.setImageResource(android.R.drawable.ic_menu_gallery) // File icon
                holder.imgIcon.setColorFilter(Color.parseColor("#42A5F5")) // Blue
                holder.tvInfo.text = "${formatSize(file.size)} | $dateStr"
                holder.btnDownload.visibility = View.VISIBLE
            }

            holder.itemView.setOnClickListener { onItemClick(file) }
            holder.btnDownload.setOnClickListener { onDownloadClick(file) }
            holder.btnDelete.setOnClickListener { onDeleteClick(file) }
        }

        override fun getItemCount() = files.size

        private fun formatSize(size: Long): String {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
        }
    }
}

package com.ailife.rtosify

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class AppListActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyList: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter
    private lateinit var toolbar: Toolbar
    private lateinit var fabInstall: FloatingActionButton

    // Upload Dialog (moved from MainActivity)
    private var uploadDialog: AlertDialog? = null
    private var uploadProgressBar: ProgressBar? = null
    private var uploadPercentageText: TextView? = null
    private var uploadDescriptionText: TextView? = null
    private var uploadTitleText: TextView? = null
    private var uploadIconView: ImageView? = null
    private var uploadOkButton: Button? = null

    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    // Connection to the Service
    private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(className: ComponentName, service: IBinder) {
                    val binder = service as BluetoothService.LocalBinder
                    bluetoothService = binder.getService()
                    bluetoothService?.callback = this@AppListActivity
                    isBound = true

                    // Requests the list as soon as connected
                    fetchApps()
                }

                override fun onServiceDisconnected(arg0: ComponentName) {
                    isBound = false
                    bluetoothService = null
                }
            }

    private val pickApkLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                uri?.let { confirmApkUpload(it) }
            }

    // Extraction launcher (will be implemented next)
    private val extractAppLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val apkPath = result.data?.getStringExtra("apk_path")
                    apkPath?.let { confirmApkUpload(Uri.fromFile(File(it))) }
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)
        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        val rvHost = findViewById<View>(R.id.recyclerViewApps)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, rvHost)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        progressBar = findViewById(R.id.progressBarList)
        tvEmptyList = findViewById(R.id.tvEmptyList)
        recyclerView = findViewById(R.id.recyclerViewApps)

        // Configure RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter { app -> showUninstallDialog(app) }
        recyclerView.adapter = adapter

        fabInstall = findViewById(R.id.fabInstallApp)
        fabInstall.setOnClickListener { showInstallOptionDialog() }

        // Bind to the existing service
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun fetchApps() {
        if (isBound && bluetoothService != null) {
            progressBar.visibility = View.VISIBLE
            tvEmptyList.text = getString(R.string.applist_requesting)
            tvEmptyList.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            bluetoothService?.requestRemoteAppList()
        } else {
            Toast.makeText(this, getString(R.string.toast_service_disconnected), Toast.LENGTH_SHORT)
                    .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    // --- Service Callbacks ---
    override fun onAppListReceived(appsJson: String) {
        lifecycleScope.launch {
            val apps =
                    withContext(Dispatchers.Default) {
                        try {
                            val jsonArray = JSONArray(appsJson)
                            val result = mutableListOf<AppItem>()

                            for (i in 0 until jsonArray.length()) {
                                val obj = jsonArray.getJSONObject(i)
                                val name = obj.getString("name")
                                val pkg = obj.getString("package")
                                val iconBase64 = obj.optString("icon", "")

                                result.add(AppItem(name, pkg, iconBase64))
                            }

                            result.sortBy { it.name.lowercase() }
                            result
                        } catch (e: Exception) {
                            null
                        }
                    }

            progressBar.visibility = View.GONE
            if (apps != null) {
                if (apps.isEmpty()) {
                    tvEmptyList.text = getString(R.string.applist_empty)
                    tvEmptyList.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    tvEmptyList.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    adapter.updateList(apps)
                }
            } else {
                tvEmptyList.text = getString(R.string.applist_error_processing)
                Toast.makeText(
                                this@AppListActivity,
                                getString(R.string.applist_error_processing_list, getString(R.string.status_invalid_json)),
                                Toast.LENGTH_LONG
                        )
                        .show()
            }
        }
    }

    // Empty implementations of other callbacks
    override fun onStatusChanged(status: String) {}
    override fun onDeviceConnected(deviceName: String) {}
    override fun onDeviceDisconnected() {
        runOnUiThread {
            Toast.makeText(this, getString(R.string.toast_watch_disconnected), Toast.LENGTH_SHORT)
                    .show()
            finish()
        }
    }
    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (uploadDialog?.isShowing == true) updateUploadProgress(-1)
        }
    }
    override fun onScanResult(devices: List<BluetoothDevice>) {}
    override fun onUploadProgress(progress: Int) {
        runOnUiThread { if (uploadDialog?.isShowing == true) updateUploadProgress(progress) }
    }

    override fun onFileListReceived(path: String, filesJson: String) {}

    override fun onDownloadProgress(progress: Int, file: java.io.File?) {
        runOnUiThread { if (uploadDialog?.isShowing == true) updateUploadProgress(progress) }
    }

    override fun onWatchStatusUpdated(
            batteryLevel: Int,
            isCharging: Boolean,
            wifiSsid: String,
            wifiEnabled: Boolean,
            dndEnabled: Boolean,
            ipAddress: String?
    ) {}

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // --- New Install/Uninstall Logic ---

    private fun showInstallOptionDialog() {
        val options =
                arrayOf(
                        getString(R.string.dialog_install_option_apk),
                        getString(R.string.dialog_install_option_extract)
                )
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_install_option_title))
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> pickApkLauncher.launch("application/vnd.android.package-archive")
                        1 -> {
                            // Start AppPickerActivity (to be created)
                            val intent = Intent(this, AppPickerActivity::class.java)
                            extractAppLauncher.launch(intent)
                        }
                    }
                }
                .show()
    }

    private fun confirmApkUpload(uri: Uri) {
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_upload_apk_title))
                .setMessage(getString(R.string.dialog_upload_apk_message))
                .setPositiveButton(getString(R.string.dialog_upload_apk_send)) { _, _ ->
                    bluetoothService?.sendApkFile(uri)
                    showUploadDialog()
                }
                .setNegativeButton(getString(R.string.dialog_upload_apk_cancel), null)
                .show()
    }

    private fun showUploadDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_upload_progress, null)
        uploadProgressBar = dialogView.findViewById(R.id.progressBarUpload)
        uploadPercentageText = dialogView.findViewById(R.id.tvUploadPercentage)
        uploadDescriptionText = dialogView.findViewById(R.id.tvUploadDescription)
        uploadTitleText = dialogView.findViewById(R.id.tvUploadTitle)
        uploadIconView = dialogView.findViewById(R.id.imgUploadIcon)
        uploadOkButton = dialogView.findViewById(R.id.btnUploadOk)

        uploadOkButton?.setOnClickListener { dismissUploadDialog() }
        uploadDialog = AlertDialog.Builder(this).setView(dialogView).setCancelable(false).create()
        uploadDialog?.show()
    }

    private fun updateUploadProgress(progress: Int) {
        when (progress) {
            in 0..99 -> {
                uploadProgressBar?.progress = progress
                uploadPercentageText?.text = getString(R.string.status_progress_format, progress)
                uploadDescriptionText?.text = getString(R.string.upload_transferring)
            }
            100 -> {
                uploadProgressBar?.progress = 100
                uploadPercentageText?.text = getString(R.string.status_progress_format, 100)
                uploadTitleText?.text = getString(R.string.upload_complete_title)
                uploadDescriptionText?.text = getString(R.string.upload_complete_message)
                uploadIconView?.setImageResource(android.R.drawable.stat_sys_upload_done)
                uploadIconView?.setColorFilter(android.graphics.Color.GREEN)
                uploadPercentageText?.setTextColor(android.graphics.Color.GREEN)
                uploadProgressBar?.visibility = View.GONE
                uploadOkButton?.visibility = View.VISIBLE
            }
            -1 -> {
                uploadProgressBar?.visibility = View.GONE
                uploadTitleText?.text = getString(R.string.upload_failed_title)
                uploadDescriptionText?.text = getString(R.string.upload_failed_message)
                uploadIconView?.setImageResource(android.R.drawable.stat_notify_error)
                uploadIconView?.setColorFilter(android.graphics.Color.RED)
                uploadPercentageText?.text = getString(R.string.status_failed)
                uploadPercentageText?.setTextColor(android.graphics.Color.RED)
                uploadOkButton?.visibility = View.VISIBLE
                uploadOkButton?.text = getString(android.R.string.ok)
            }
        }
    }

    private fun dismissUploadDialog() {
        uploadDialog?.dismiss()
        uploadDialog = null
        // Reload the list after successful upload (delay to ensure the watch processed)
        FetchAppsAfterDelay()
    }

    private fun FetchAppsAfterDelay() {
        recyclerView.postDelayed({ fetchApps() }, 2000)
    }

    private fun showUninstallDialog(app: AppItem) {
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_uninstall_title))
                .setMessage(getString(R.string.dialog_uninstall_message, app.name))
                .setPositiveButton(getString(R.string.menu_uninstall_app)) { _, _ ->
                    bluetoothService?.sendUninstallCommand(app.packageName)
                    Toast.makeText(this, getString(R.string.toast_command_sent), Toast.LENGTH_SHORT)
                            .show()
                    // Reload after some time
                    FetchAppsAfterDelay()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    // --- Adapter and Data Classes ---

    data class AppItem(val name: String, val packageName: String, val iconBase64: String)

    class AppAdapter(private val onUninstallClick: (AppItem) -> Unit) :
            RecyclerView.Adapter<AppAdapter.AppViewHolder>() {
        private var list = listOf<AppItem>()

        fun updateList(newList: List<AppItem>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return AppViewHolder(view, onUninstallClick)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(list[position])
        }

        override fun getItemCount() = list.size

        class AppViewHolder(itemView: View, private val onUninstallClick: (AppItem) -> Unit) :
                RecyclerView.ViewHolder(itemView) {
            private val imgIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
            private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
            private val tvPkg: TextView = itemView.findViewById(R.id.tvAppPackage)
            private val btnUninstall: ImageButton = itemView.findViewById(R.id.btnUninstall)

            fun bind(item: AppItem) {
                tvName.text = item.name
                tvPkg.text = item.packageName
                btnUninstall.setOnClickListener { onUninstallClick(item) }

                imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)

                if (item.iconBase64.isNotEmpty()) {
                    (itemView.context as? AppCompatActivity)?.lifecycleScope?.launch(
                            Dispatchers.IO
                    ) {
                        try {
                            val decodedString = Base64.decode(item.iconBase64, Base64.DEFAULT)
                            val decodedByte =
                                    BitmapFactory.decodeByteArray(
                                            decodedString,
                                            0,
                                            decodedString.size
                                    )
                            withContext(Dispatchers.Main) { imgIcon.setImageBitmap(decodedByte) }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.ailife.rtosifycompanion

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku

class PermissionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PermissionAdapter
    private lateinit var toolbar: Toolbar
    private lateinit var btnFinish: View
    private var fromSetup = false

    // Shizuku UserService for Shell commands
    private var userService: IUserService? = null
    private var userServiceConnection: Shizuku.UserServiceArgs? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
                        android.content.ComponentName(
                                BuildConfig.APPLICATION_ID,
                                UserService::class.java.name
                        )
                )
                .daemon(false)
                .processNameSuffix("user_service")
                .debuggable(BuildConfig.DEBUG)
                .version(1)
    }

    private val userServiceConn =
            object : android.content.ServiceConnection {
                override fun onServiceConnected(
                        name: android.content.ComponentName?,
                        binder: android.os.IBinder?
                ) {
                    if (binder != null && binder.pingBinder()) {
                        userService = IUserService.Stub.asInterface(binder)
                        android.util.Log.i(
                                "PermissionActivity",
                                "UserService connected successfully"
                        )
                    }
                }

                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    userService = null
                    android.util.Log.w("PermissionActivity", "UserService disconnected")
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.perm_title)

        recyclerView = findViewById(R.id.recyclerViewPermissions)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PermissionAdapter { perm -> handlePermissionClick(perm) }
        recyclerView.adapter = adapter

        btnFinish = findViewById(R.id.btnFinishPermission)
        fromSetup = intent.getBooleanExtra("from_setup", false)

        if (fromSetup) {
            btnFinish.visibility = View.VISIBLE
            btnFinish.setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
        }

        Shizuku.addBinderReceivedListener { updatePermissionList() }

        // Bind UserService if Shizuku is available
        if (Shizuku.pingBinder() &&
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                Shizuku.bindUserService(userServiceArgs, userServiceConn)
                userServiceConnection = userServiceArgs
            } catch (e: Exception) {
                android.util.Log.e("PermissionActivity", "Failed to bind UserService: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionList()
    }

    private fun updatePermissionList() {
        val perms = mutableListOf<PermissionItem>()

        // 1. Bluetooth
        val hasBT =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    checkPerm(Manifest.permission.BLUETOOTH_SCAN) &&
                            checkPerm(Manifest.permission.BLUETOOTH_CONNECT)
                } else true
        perms.add(
                PermissionItem(
                        "BT",
                        getString(R.string.perm_bluetooth),
                        getString(R.string.perm_bluetooth_desc),
                        hasBT
                )
        )

        // 2. Install Unknown Apps
        val hasInstall =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    packageManager.canRequestPackageInstalls()
                } else true
        perms.add(
                PermissionItem(
                        "INSTALL",
                        getString(R.string.perm_install),
                        getString(R.string.perm_install_desc),
                        hasInstall
                )
        )

        // 3. Shizuku
        var hasShizuku = false
        try {
            hasShizuku =
                    Shizuku.pingBinder() &&
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {}
        perms.add(
                PermissionItem(
                        "SHIZUKU",
                        getString(R.string.perm_shizuku),
                        getString(R.string.perm_shizuku_desc),
                        hasShizuku
                )
        )

        // 4. Root
        val isRoot = (Shell.isAppGrantedRoot() == true)
        perms.add(
                PermissionItem(
                        "ROOT",
                        getString(R.string.perm_root),
                        getString(R.string.perm_root_desc),
                        isRoot
                )
        )

        // 5. Location
        perms.add(
                PermissionItem(
                        "LOCATION",
                        getString(R.string.perm_location),
                        getString(R.string.perm_location_desc),
                        checkPerm(Manifest.permission.ACCESS_FINE_LOCATION)
                )
        )

        // 6. Background Location
        val hasBG =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    checkPerm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else true
        perms.add(
                PermissionItem(
                        "LOCATION_BG",
                        getString(R.string.perm_location_bg),
                        getString(R.string.perm_location_bg_desc),
                        hasBG
                )
        )

        // 7. Storage
        val hasStorage =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.os.Environment.isExternalStorageManager()
                } else {
                    checkPerm(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
        perms.add(
                PermissionItem(
                        "STORAGE",
                        getString(R.string.perm_storage),
                        getString(R.string.perm_storage_desc),
                        hasStorage
                )
        )

        // 8. Post Notifications (Android 13+)
        val hasPostNotif =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkPerm(Manifest.permission.POST_NOTIFICATIONS)
                } else true
        perms.add(
                PermissionItem(
                        "POST_NOTIF",
                        getString(R.string.perm_post_notif),
                        getString(R.string.perm_post_notif_desc),
                        hasPostNotif
                )
        )

        // 9. DND Access
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hasDnd =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    notifManager.isNotificationPolicyAccessGranted
                } else true
        perms.add(
                PermissionItem(
                        "DND",
                        getString(R.string.perm_dnd),
                        getString(R.string.perm_dnd_desc),
                        hasDnd
                )
        )

        // 10. Calendar
        perms.add(
                PermissionItem(
                        "CALENDAR",
                        getString(R.string.perm_calendar),
                        getString(R.string.perm_calendar_desc),
                        checkPerm(Manifest.permission.WRITE_CALENDAR)
                )
        )

        // 11. Contacts
        perms.add(
                PermissionItem(
                        "CONTACTS",
                        getString(R.string.perm_contacts),
                        getString(R.string.perm_contacts_desc),
                        checkPerm(Manifest.permission.WRITE_CONTACTS)
                )
        )

        // 12. Watch Face Dir
        val hasWF = isWatchFaceDirAccessible()
        perms.add(
                PermissionItem(
                        "WATCHFACE",
                        getString(R.string.perm_watchface),
                        getString(R.string.perm_watchface_desc),
                        hasWF
                )
        )

        // 13. Nearby WiFi Devices (Android 13+)
        val hasNearbyWifi =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkPerm(Manifest.permission.NEARBY_WIFI_DEVICES)
                } else true
        perms.add(
                PermissionItem(
                        "NEARBY_WIFI",
                        getString(R.string.perm_nearby_wifi),
                        getString(R.string.perm_nearby_wifi_desc),
                        hasNearbyWifi
                )
        )

        // 14. Accessibility Service (for clipboard access)
        val hasAccessibility = isAccessibilityServiceEnabled()
        perms.add(
                PermissionItem(
                        "ACCESSIBILITY",
                        getString(R.string.perm_accessibility),
                        getString(R.string.perm_accessibility_desc),
                        hasAccessibility
                )
        )

        // 15. Write Settings (for WiFi connection via WifiNetworkSpecifier)
        val hasWriteSettings =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.System.canWrite(this)
                } else true
        perms.add(
                PermissionItem(
                        "WRITE_SETTINGS",
                        "Write System Settings",
                        "Required for immediate WiFi connection",
                        hasWriteSettings
                )
        )

        // 16. System Alert Window (for Dynamic Island overlay)
        val hasOverlay =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(this)
                } else true
        perms.add(
                PermissionItem(
                        "OVERLAY",
                        "Display over other apps",
                        "Required for Dynamic Island notification overlay",
                        hasOverlay
                )
        )

        // 17. Usage Stats (App Battery Usage)
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode =
                appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        packageName
                )
        val hasUsageStats = mode == AppOpsManager.MODE_ALLOWED
        perms.add(
                PermissionItem(
                        "USAGE_STATS",
                        getString(R.string.perm_usage_stats),
                        getString(R.string.perm_usage_stats_desc),
                        hasUsageStats
                )
        )

        // 18. Battery Stats (Real per-app battery data)
        val hasBatteryStats =
                try {
                    packageManager.checkPermission(
                            "android.permission.BATTERY_STATS",
                            packageName
                    ) == PackageManager.PERMISSION_GRANTED
                } catch (e: Exception) {
                    false
                }
        perms.add(
                PermissionItem(
                        "BATTERY_STATS",
                        "Battery Stats",
                        "Access real per-app battery consumption data (Android 12+)",
                        hasBatteryStats
                )
        )

        // 19. Dump Permission (Direct Dumpsys)
        val hasDump =
                try {
                    packageManager.checkPermission("android.permission.DUMP", packageName) ==
                            PackageManager.PERMISSION_GRANTED
                } catch (e: Exception) {
                    false
                }
        perms.add(
                PermissionItem(
                        "DUMP",
                        "System Dump",
                        "Directly access system stats for robust data (Method 2)",
                        hasDump
                )
        )

        // 20. Battery Optimization
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isIgnoring =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    powerManager.isIgnoringBatteryOptimizations(packageName)
                } else true
        perms.add(
                PermissionItem(
                        "BATTERY",
                        getString(R.string.perm_battery),
                        getString(R.string.perm_battery_desc),
                        isIgnoring
                )
        )

        // 21. Show on Lock Screen (Guidance for MIUI/HyperOS)
        perms.add(
                PermissionItem(
                        "LOCKSCREEN",
                        "Show on Lock Screen",
                        "Required for Dynamic Island on lock screen (Xiaomi/MIUI)",
                        true
                )
        ) // Shown as granted as it's guidance

        adapter.updateList(perms)
    }

    private fun checkPerm(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun handlePermissionClick(perm: PermissionItem) {
        when (perm.id) {
            "BT" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestPermissions(
                            arrayOf(
                                    Manifest.permission.BLUETOOTH_SCAN,
                                    Manifest.permission.BLUETOOTH_CONNECT
                            ),
                            101
                    )
                }
            }
            "INSTALL" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startActivity(
                            Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:$packageName")
                            )
                    )
                }
            }
            "BATTERY" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent =
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                    startActivity(intent)
                }
            }
            "SHIZUKU" -> {
                try {
                    if (Shizuku.pingBinder()) {
                        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            Shizuku.requestPermission(102)
                        } else {
                            updatePermissionList()
                        }
                    } else {
                        val intent =
                                packageManager.getLaunchIntentForPackage(
                                        "moe.shizuku.privileged.api"
                                )
                        if (intent != null) {
                            startActivity(intent)
                        }
                    }
                } catch (e: Exception) {}
            }
            "ROOT" -> Shell.getShell { updatePermissionList() }
            "LOCATION" -> requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 103)
            "LOCATION_BG" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 104)
                }
            }
            "STORAGE" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!android.os.Environment.isExternalStorageManager()) {
                        val intent =
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                        .apply { data = Uri.parse("package:$packageName") }
                        startActivity(intent)
                    }
                } else {
                    requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
                }
            }
            "POST_NOTIF" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 105)
                }
            }
            "DND" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    if (isGoEdition() && Build.VERSION.SDK_INT <= 33) {
                        showGoRestrictionDialog(
                                intent,
                                "adb shell settings put secure enabled_notification_policy_access_packages $packageName",
                                "Do Not Disturb Access"
                        )
                    } else {
                        startActivity(intent)
                    }
                }
            }
            "CALENDAR" ->
                    requestPermissions(
                            arrayOf(
                                    Manifest.permission.READ_CALENDAR,
                                    Manifest.permission.WRITE_CALENDAR
                            ),
                            111
                    )
            "CONTACTS" ->
                    requestPermissions(
                            arrayOf(
                                    Manifest.permission.READ_CONTACTS,
                                    Manifest.permission.WRITE_CONTACTS
                            ),
                            112
                    )
            "WATCHFACE" -> handleWatchFacePermissionClick()
            "ACCESSIBILITY" -> {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                if (isRestrictedSettingsAllowed()) {
                    startActivity(intent)
                } else {
                    showRestrictedSettingsDialog(intent)
                }
            }
            "NEARBY_WIFI" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 115)
                }
            }
            "WRITE_SETTINGS" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.System.canWrite(this)) {
                        val intent =
                                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                        startActivity(intent)
                    }
                }
            }
            "OVERLAY" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.canDrawOverlays(this)) {
                        val intent =
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                        if (isGoEdition() && Build.VERSION.SDK_INT <= 33) {
                            showGoRestrictionDialog(
                                    intent,
                                    "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow",
                                    "Display over other apps"
                            )
                        } else {
                            startActivity(intent)
                        }
                    }
                }
            }
            "USAGE_STATS" -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            "BATTERY_STATS" -> showBatteryStatsDialog()
            "DUMP" -> showDumpDialog()
            "LOCKSCREEN" -> {
                val intent =
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                startActivity(intent)
                android.widget.Toast.makeText(
                                this,
                                "Enable 'Show on Lock screen' in Permissions",
                                android.widget.Toast.LENGTH_LONG
                        )
                        .show()
            }
        }
    }

    private fun isWatchFaceDirAccessible(): Boolean {
        // Shizuku or Root is always enough
        if (Shizuku.pingBinder() &&
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        )
                return true
        if (Shell.isAppGrantedRoot() == true) return true

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ checking SAF for specific folder
            val uri =
                    Uri.parse(
                            "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Fcom.ailife.ClockSkinCoco"
                    )
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        } else {
            checkPerm(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun showBatteryStatsDialog() {
        showPermissionDialog(
                "Battery Stats",
                "android.permission.BATTERY_STATS",
                "This permission allows access to real per-app battery consumption data."
        )
    }

    private fun showDumpDialog() {
        showPermissionDialog(
                "Dump Permission",
                "android.permission.DUMP",
                "This permission allows direct access to system dumpsys for robust battery stats collection."
        )
    }

    private fun showPermissionDialog(title: String, permission: String, desc: String) {
        val adbCommand = "adb shell pm grant $packageName $permission"

        val dialog =
                android.app.AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage("$desc\n\nChoose activation method:")
                        .setPositiveButton("Copy ADB Command") { _, _ ->
                            val clipboard =
                                    getSystemService(Context.CLIPBOARD_SERVICE) as
                                            android.content.ClipboardManager
                            val clip =
                                    android.content.ClipData.newPlainText("ADB Command", adbCommand)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(
                                            this,
                                            "Command copied! Run it on your computer.",
                                            android.widget.Toast.LENGTH_LONG
                                    )
                                    .show()
                        }
                        .setNeutralButton("Activate with Shizuku") { _, _ ->
                            grantPermissionWithShizuku(permission)
                        }
                        .setNegativeButton("Cancel", null)
                        .create()
        dialog.show()
    }

    private fun grantPermissionWithShizuku(permission: String) {
        android.util.Log.d("PermissionActivity", "grantPermissionWithShizuku: $permission")

        try {
            // Check if root is available (Shizuku or libsu)
            val hasRoot = Shell.isAppGrantedRoot() == true
            val hasShizuku =
                    Shizuku.pingBinder() &&
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

            if (!hasRoot && !hasShizuku) {
                android.widget.Toast.makeText(
                                this,
                                "Root or Shizuku required.",
                                android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
                return
            }

            android.widget.Toast.makeText(
                            this,
                            "Granting $permission...",
                            android.widget.Toast.LENGTH_SHORT
                    )
                    .show()

            // Bind UserService if using Shizuku and not already bound
            if (hasShizuku && userService == null) {
                try {
                    Shizuku.bindUserService(userServiceArgs, userServiceConn)
                    userServiceConnection = userServiceArgs
                    Thread.sleep(500)
                } catch (e: Exception) {
                    android.util.Log.e(
                            "PermissionActivity",
                            "Failed to bind UserService: ${e.message}"
                    )
                }
            }

            // Use UserService to execute command via Shizuku
            Thread {
                        try {
                            if (userService == null && hasShizuku) {
                                runOnUiThread {
                                    android.widget.Toast.makeText(
                                                    this,
                                                    "UserService not connected. Try again.",
                                                    android.widget.Toast.LENGTH_SHORT
                                            )
                                            .show()
                                }
                                return@Thread
                            }

                            val command = "pm grant $packageName $permission"
                            val exitCodeStr =
                                    userService?.executeCommand(command)
                                            ?: Shell.cmd(command).exec().code.toString()
                            val exitCode = exitCodeStr.toIntOrNull() ?: -1

                            runOnUiThread {
                                if (exitCode == 0) {
                                    android.widget.Toast.makeText(
                                                    this,
                                                    "Permission granted!",
                                                    android.widget.Toast.LENGTH_SHORT
                                            )
                                            .show()
                                    updatePermissionList()
                                } else {
                                    android.widget.Toast.makeText(
                                                    this,
                                                    "Failed (Code: $exitCode). Try ADB.",
                                                    android.widget.Toast.LENGTH_LONG
                                            )
                                            .show()
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                                this,
                                                "Error: ${e.message}",
                                                android.widget.Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                        }
                    }
                    .start()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                            this,
                            "Error: ${e.message}",
                            android.widget.Toast.LENGTH_SHORT
                    )
                    .show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val cn = android.content.ComponentName(this, RtosifyAccessibilityService::class.java)
        val flat =
                Settings.Secure.getString(
                        contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun handleWatchFacePermissionClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Check if we can use Shizuku first
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(102)
                    return
                }
            }

            // Fallback to SAF for 11-13 (and 14+ if no Shizuku, though 14+ is harder)
            val path = "Android/data/com.ailife.ClockSkinCoco"
            // Use 'document' instead of 'tree' for INITIAL_URI to robustly target the folder
            val uri =
                    Uri.parse(
                            "content://com.android.externalstorage.documents/document/primary%3A" +
                                    path.replace("/", "%2F")
                    )
            val intent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        putExtra("android.provider.extra.INITIAL_URI", uri)
                        putExtra("android.content.extra.SHOW_ADVANCED", true)
                    }
            try {
                startActivityForResult(intent, 120)
            } catch (e: Exception) {
                // If it fails, just open standard tree
                val intentFallback = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intentFallback, 120)
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 120 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                updatePermissionList()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    data class PermissionItem(
            val id: String,
            val title: String,
            val desc: String,
            val granted: Boolean
    )

    class PermissionAdapter(private val onClick: (PermissionItem) -> Unit) :
            RecyclerView.Adapter<PermissionAdapter.ViewHolder>() {
        private var list = listOf<PermissionItem>()

        fun updateList(newList: List<PermissionItem>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view =
                    LayoutInflater.from(parent.context)
                            .inflate(R.layout.item_permission, parent, false)
            return ViewHolder(view, onClick)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(list[position])
        }

        override fun getItemCount() = list.size

        class ViewHolder(itemView: View, private val onClick: (PermissionItem) -> Unit) :
                RecyclerView.ViewHolder(itemView) {
            private val imgStatus: ImageView = itemView.findViewById(R.id.imgStatus)
            private val tvTitle: TextView = itemView.findViewById(R.id.tvPermTitle)
            private val tvDesc: TextView = itemView.findViewById(R.id.tvPermDesc)
            private val tvStatus: TextView = itemView.findViewById(R.id.tvStatusText)

            fun bind(item: PermissionItem) {
                tvTitle.text = item.title
                tvDesc.text = item.desc

                if (item.granted) {
                    imgStatus.setImageResource(android.R.drawable.presence_online)
                    tvStatus.text = itemView.context.getString(R.string.perm_granted)
                    tvStatus.setTextColor(
                            ContextCompat.getColor(
                                    itemView.context,
                                    android.R.color.holo_green_dark
                            )
                    )
                } else {
                    imgStatus.setImageResource(android.R.drawable.presence_busy)
                    tvStatus.text = itemView.context.getString(R.string.perm_not_granted)
                    tvStatus.setTextColor(
                            ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
                    )
                }

                itemView.setOnClickListener { onClick(item) }
            }
        }
    }
    private fun isRestrictedSettingsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return try {
            val mode =
                    appOps.checkOpNoThrow(
                            "android:access_restricted_settings",
                            android.os.Process.myUid(),
                            packageName
                    )
            android.util.Log.d(
                    "PermissionActivity",
                    "Restricted Mode Check: $mode (Allowed=${AppOpsManager.MODE_ALLOWED})"
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            android.util.Log.e("PermissionActivity", "Restricted Check Error", e)
            false
        }
    }

    private fun showRestrictedSettingsDialog(intent: Intent) {
        val adbCommand = "adb shell appops set $packageName ACCESS_RESTRICTED_SETTINGS allow"

        android.app.AlertDialog.Builder(this)
                .setTitle(R.string.perm_restricted_title)
                .setMessage(R.string.perm_restricted_desc)
                .setPositiveButton("Copy ADB Command") { _, _ ->
                    val clipboard =
                            getSystemService(Context.CLIPBOARD_SERVICE) as
                                    android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("ADB Command", adbCommand)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(
                                    this,
                                    "Command copied! Run it on your computer.",
                                    android.widget.Toast.LENGTH_LONG
                            )
                            .show()
                }
                .setNeutralButton("Open Settings") { _, _ -> startActivity(intent) }
                .setNegativeButton("Activate with Shizuku") { _, _ ->
                    grantRestrictedSettingsWithShizuku()
                }
                .show()
    }

    private fun grantRestrictedSettingsWithShizuku() {
        try {
            val hasRoot = Shell.isAppGrantedRoot() == true
            val hasShizuku =
                    Shizuku.pingBinder() &&
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

            if (!hasRoot && !hasShizuku) {
                android.widget.Toast.makeText(
                                this,
                                "Root or Shizuku required.",
                                android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
                return
            }

            android.widget.Toast.makeText(
                            this,
                            "Granting restricted settings...",
                            android.widget.Toast.LENGTH_SHORT
                    )
                    .show()

            Thread {
                        try {
                            val command = "appops set $packageName ACCESS_RESTRICTED_SETTINGS allow"
                            val exitCodeStr =
                                    userService?.executeCommand(command)
                                            ?: Shell.cmd(command).exec().code.toString()
                            val exitCode = exitCodeStr.toIntOrNull() ?: -1

                            runOnUiThread {
                                if (exitCode == 0) {
                                    android.widget.Toast.makeText(
                                                    this,
                                                    "Permission granted!",
                                                    android.widget.Toast.LENGTH_SHORT
                                            )
                                            .show()
                                    updatePermissionList()
                                } else {
                                    android.widget.Toast.makeText(
                                                    this,
                                                    "Failed (Code: $exitCode). Try ADB.",
                                                    android.widget.Toast.LENGTH_LONG
                                            )
                                            .show()
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                                this,
                                                "Error: ${e.message}",
                                                android.widget.Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                        }
                    }
                    .start()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                            this,
                            "Error: ${e.message}",
                            android.widget.Toast.LENGTH_SHORT
                    )
                    .show()
        }
    }

    private fun isGoEdition(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isLowRam = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && am.isLowRamDevice
        android.util.Log.d("PermissionActivity", "isLowRamDevice: $isLowRam")
        if (isLowRam) return true

        return try {
            val val1 =
                    Runtime.getRuntime()
                            .exec("getprop ro.config.low_ram")
                            .inputStream
                            .bufferedReader()
                            .use { it.readText().trim() }
            android.util.Log.d("PermissionActivity", "ro.config.low_ram: '$val1'")
            if (val1 == "true" || val1 == "1") return true

            val val2 =
                    Runtime.getRuntime()
                            .exec("getprop ro.config.lowram")
                            .inputStream
                            .bufferedReader()
                            .use { it.readText().trim() }
            android.util.Log.d("PermissionActivity", "ro.config.lowram: '$val2'")
            val2 == "true" || val2 == "1"
        } catch (e: Exception) {
            android.util.Log.e("PermissionActivity", "isGoEdition error", e)
            false
        }
    }

    private fun showGoRestrictionDialog(intent: Intent, adbCommand: String, permName: String) {
        android.app.AlertDialog.Builder(this)
                .setTitle(R.string.perm_go_restricted_title)
                .setMessage(getString(R.string.perm_go_restricted_desc))
                .setPositiveButton(R.string.perm_button_try_anyways) {
                        dialog: DialogInterface,
                        which: Int ->
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                                        this,
                                        "Failed to open settings",
                                        android.widget.Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                }
                .setNeutralButton(R.string.perm_button_copy_adb) { _: DialogInterface, _: Int ->
                    val clipboard =
                            getSystemService(Context.CLIPBOARD_SERVICE) as
                                    android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("ADB Command", adbCommand)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(
                                    this,
                                    "Command copied!",
                                    android.widget.Toast.LENGTH_SHORT
                            )
                            .show()
                }
                .setNegativeButton(R.string.perm_button_grant_root_shizuku) {
                        _: DialogInterface,
                        _: Int ->
                    grantGoPermissionWithShizuku(adbCommand)
                }
                .show()
    }

    private fun grantGoPermissionWithShizuku(adbCommand: String) {
        val command = adbCommand.replace("adb shell ", "")
        try {
            val hasRoot = Shell.isAppGrantedRoot() == true
            val hasShizuku =
                    Shizuku.pingBinder() &&
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

            if (!hasRoot && !hasShizuku) {
                android.widget.Toast.makeText(
                                this,
                                "Root or Shizuku required.",
                                android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
                return
            }

            android.widget.Toast.makeText(
                            this,
                            "Granting permission...",
                            android.widget.Toast.LENGTH_SHORT
                    )
                    .show()

            Thread {
                        try {
                            val exitCodeStr =
                                    userService?.executeCommand(command)
                                            ?: Shell.cmd(command).exec().code.toString()
                            val exitCode = exitCodeStr.toIntOrNull() ?: -1

                            runOnUiThread {
                                if (exitCode == 0) {
                                    android.widget.Toast.makeText(
                                                    this,
                                                    "Permission granted!",
                                                    android.widget.Toast.LENGTH_SHORT
                                            )
                                            .show()
                                    updatePermissionList()
                                } else {
                                    android.widget.Toast.makeText(
                                                    this,
                                                    "Failed (Code: $exitCode).",
                                                    android.widget.Toast.LENGTH_LONG
                                            )
                                            .show()
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                                this,
                                                "Error: ${e.message}",
                                                android.widget.Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                        }
                    }
                    .start()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                            this,
                            "Error: ${e.message}",
                            android.widget.Toast.LENGTH_SHORT
                    )
                    .show()
        }
    }
}

package com.ailife.rtosify

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.ailife.rtosify.EdgeToEdgeUtils.dpToPx

class PermissionActivity : AppCompatActivity() {

    private lateinit var permRecyclerView: RecyclerView
    private lateinit var permAdapter: PermissionAdapter
    private lateinit var toolbar: Toolbar
    private lateinit var btnFinish: View
    private var fromSetup = false

    // Shizuku UserService for Shell commands
    private var userService: IUserService? = null
    private var userServiceConnection: Shizuku.UserServiceArgs? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
                        ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name)
                )
                .daemon(false)
                .processNameSuffix("user_service")
                .debuggable(BuildConfig.DEBUG)
                .version(1)
    }

    private val userServiceConn =
            object : android.content.ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
                    if (binder != null && binder.pingBinder()) {
                        userService = IUserService.Stub.asInterface(binder)
                        android.util.Log.i(
                                "PermissionActivity",
                                getString(R.string.perm_shizuku_connected)
                        )
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    userService = null
                    android.util.Log.w("PermissionActivity", getString(R.string.perm_shizuku_disconnected))
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)
        
        val appBarLayout = findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.toolbar).parent as? android.view.View
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, findViewById(R.id.recyclerViewPermissions))
        // Also handle the finish button at the bottom if it's visible
        val btnFinishInset = findViewById<View>(R.id.btnFinishPermission)
        if (btnFinishInset != null) {
            ViewCompat.setOnApplyWindowInsetsListener(btnFinishInset) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                    bottomMargin = systemBars.bottom + 16.dpToPx(this@PermissionActivity) // 16dp margin
                }
                insets
            }
        }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.perm_title)

        permRecyclerView = findViewById(R.id.recyclerViewPermissions)
        permRecyclerView.layoutManager = LinearLayoutManager(this)
        permAdapter = PermissionAdapter { perm -> handlePermissionClick(perm) }
        permRecyclerView.adapter = permAdapter

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

        // Listener for when Shizuku connects
        Shizuku.addBinderReceivedListener { updatePermissionList() }

        // Bind UserService if Shizuku is available
        if (Shizuku.pingBinder() &&
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                Shizuku.bindUserService(userServiceArgs, userServiceConn)
                userServiceConnection = userServiceArgs
            } catch (e: Exception) {
                android.util.Log.e("PermissionActivity", getString(R.string.perm_shizuku_bind_error, e.message))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionList()
    }

    private fun updatePermissionList() {
        val perms = mutableListOf<PermissionItem>()

        // 1. Bluetooth (Scan/Connect)
        val hasBT =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    checkPerm(Manifest.permission.BLUETOOTH_SCAN) &&
                            checkPerm(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    true // Legacy
                }
        perms.add(
                PermissionItem(
                        "BT",
                        getString(R.string.perm_bluetooth),
                        getString(R.string.perm_bluetooth_desc),
                        hasBT
                )
        )

        // 2. Notification Access
        perms.add(
                PermissionItem(
                        "NOTIF",
                        getString(R.string.perm_notifications),
                        getString(R.string.perm_notifications_desc),
                        isNotificationServiceEnabled()
                )
        )

        // 3. Install Unknown Apps
        val hasInstall =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    packageManager.canRequestPackageInstalls()
                } else {
                    true // Not applicable or always true
                }
        perms.add(
                PermissionItem(
                        "INSTALL",
                        getString(R.string.perm_install),
                        getString(R.string.perm_install_desc),
                        hasInstall
                )
        )

        // 4. Battery Optimization
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
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

        // 5. Shizuku
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

        // 6. Root
        val isRoot = Shell.isAppGrantedRoot() == true || (Shell.getCachedShell()?.isRoot == true)
        perms.add(
                PermissionItem(
                        "ROOT",
                        getString(R.string.perm_root),
                        getString(R.string.perm_root_desc),
                        isRoot
                )
        )

        // 7. Location (for BT scan)
        perms.add(
                PermissionItem(
                        "LOCATION",
                        getString(R.string.perm_location),
                        getString(R.string.perm_location_desc),
                        checkPerm(Manifest.permission.ACCESS_FINE_LOCATION)
                )
        )

        // 8. Background Location
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

        // 9. Post Notifications (Android 13+)
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

        // 10. Camera
        perms.add(
                PermissionItem(
                        "CAMERA",
                        getString(R.string.perm_camera),
                        getString(R.string.perm_camera_desc),
                        checkPerm(Manifest.permission.CAMERA)
                )
        )

        // 11. DND Access
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

        // 12. Storage
        val hasStorage =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkPerm(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
                }

        perms.add(
                PermissionItem(
                        "STORAGE",
                        getString(R.string.perm_storage),
                        getString(R.string.perm_storage_desc),
                        hasStorage
                )
        )

        // 13. Call Phone
        perms.add(
                PermissionItem(
                        "CALL",
                        getString(R.string.perm_call),
                        getString(R.string.perm_call_desc),
                        checkPerm(Manifest.permission.CALL_PHONE)
                )
        )

        // 14. Calendar
        perms.add(
                PermissionItem(
                        "CALENDAR",
                        getString(R.string.perm_calendar),
                        getString(R.string.perm_calendar_desc),
                        checkPerm(Manifest.permission.READ_CALENDAR)
                )
        )

        // 15. Contacts
        perms.add(
                PermissionItem(
                        "CONTACTS",
                        getString(R.string.perm_contacts),
                        getString(R.string.perm_contacts_desc),
                        checkPerm(Manifest.permission.READ_CONTACTS)
                )
        )

        // 16. Phone Status
        val hasPhoneStatus =
                checkPerm(Manifest.permission.READ_PHONE_STATE) &&
                        checkPerm(Manifest.permission.READ_CALL_LOG) &&
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                checkPerm(Manifest.permission.ANSWER_PHONE_CALLS)
                        else true)
        perms.add(
                PermissionItem(
                        "PHONE",
                        getString(R.string.perm_phone_status),
                        getString(R.string.perm_phone_status_desc),
                        hasPhoneStatus
                )
        )

        // 17. Accessibility Service (for clipboard access)
        val hasAccessibility = isAccessibilityServiceEnabled()
        perms.add(
                PermissionItem(
                        "ACCESSIBILITY",
                        getString(R.string.perm_accessibility),
                        getString(R.string.perm_accessibility_desc),
                        hasAccessibility
                )
        )

        // 18. Nearby WiFi Devices (Android 13+)
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

        permAdapter.updateList(perms)
    }

    private fun checkPerm(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, MyNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val cn = ComponentName(this, RtosifyAccessibilityService::class.java)
        val flat =
                Settings.Secure.getString(
                        contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun handlePermissionClick(perm: PermissionItem) {
        when (perm.id) {
            "BT" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestPermissions(
                            arrayOf(
                                    Manifest.permission.BLUETOOTH_SCAN,
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_ADVERTISE
                            ),
                            101
                    )
                }
            }
            "NOTIF" -> {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                if (isGoEdition() && Build.VERSION.SDK_INT <= 33) {
                    showGoRestrictionDialog(
                            intent,
                            "adb shell settings put secure enabled_notification_listeners %s/%s.MyNotificationListener".format(
                                    packageName,
                                    packageName
                            ),
                            "Notification Access"
                    )
                } else if (!isRestrictedSettingsAllowed()) {
                    showRestrictedSettingsDialog(intent)
                } else {
                    startActivity(intent)
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
                            // Already granted, just refresh
                            updatePermissionList()
                        }
                    } else {
                        // Open Shizuku app?
                        val intent =
                                packageManager.getLaunchIntentForPackage(
                                        "moe.shizuku.privileged.api"
                                )
                        if (intent != null) {
                            startActivity(intent)
                        } else {
                            val browserIntent =
                                    Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://shizuku.rikka.app/download/")
                                    )
                            startActivity(browserIntent)
                        }
                    }
                } catch (e: Exception) {}
            }
            "ROOT" -> {
                Shell.getShell { /* Triggers root request */}
            }
            "LOCATION" -> requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 103)
            "LOCATION_BG" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 104)
                }
            }
            "POST_NOTIF" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 105)
                }
            }
            "CAMERA" -> requestPermissions(arrayOf(Manifest.permission.CAMERA), 106)
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
            "STORAGE" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 107)
                } else {
                    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 107)
                }
            }
            "CALL" -> requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 108)
            "CALENDAR" -> requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 109)
            "CONTACTS" -> requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 110)
            "PHONE" -> {
                val p =
                        mutableListOf(
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.READ_CALL_LOG
                        )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        p.add(Manifest.permission.ANSWER_PHONE_CALLS)
                requestPermissions(p.toTypedArray(), 111)
            }
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
        }
    }

    private fun isRestrictedSettingsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        return try {
            val mode =
                    appOps.checkOpNoThrow(
                            "android:access_restricted_settings",
                            android.os.Process.myUid(),
                            packageName
                    )
            android.util.Log.d(
                    "PermissionActivity",
                    "Restricted Mode Check: $mode (Allowed=${android.app.AppOpsManager.MODE_ALLOWED})"
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            android.util.Log.e("PermissionActivity", getString(R.string.perm_restricted_check_error), e)
            false
        }
    }

    private fun checkRestrictedSettingsWithShizuku(): Boolean {
        try {
            if (userService != null) {
                // Use bound service
                val output =
                        userService?.executeCommand(
                                "appops get $packageName ACCESS_RESTRICTED_SETTINGS"
                        )
                                ?: ""
                android.util.Log.d("PermissionActivity", "Shizuku check output: $output")
                return output.contains("ALLOW", ignoreCase = true)
            } else if (Shell.isAppGrantedRoot() == true) {
                // Use root
                val result = Shell.cmd("appops get $packageName ACCESS_RESTRICTED_SETTINGS").exec()
                val output = result.out.joinToString("\n")
                android.util.Log.d("PermissionActivity", "Root check output: $output")
                return output.contains("ALLOW", ignoreCase = true)
            }
        } catch (e: Exception) {
            android.util.Log.e("PermissionActivity", "Shizuku/Root check error: ${e.message}")
        }
        return false // Default to false if we can't verify
    }

    private fun showRestrictedSettingsDialog(intent: Intent) {
        val adbCommand = "appops set $packageName ACCESS_RESTRICTED_SETTINGS allow"

        android.app.AlertDialog.Builder(this)
                .setTitle(R.string.perm_restricted_title)
                .setMessage(R.string.perm_restricted_desc)
                .setPositiveButton(R.string.perm_button_copy_adb) { _, _ ->
                    val clipboard =
                            getSystemService(Context.CLIPBOARD_SERVICE) as
                                    android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("ADB Command", adbCommand)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(
                                    this,
                                    getString(R.string.toast_command_copied),
                                    android.widget.Toast.LENGTH_SHORT
                            )
                            .show()
                }
                .setNeutralButton(getString(R.string.toast_open_settings)) { _, _ -> startActivity(intent) }
                .setNegativeButton(getString(R.string.perm_activate_shizuku)) { _, _ ->
                    grantRestrictedSettingsWithShizuku()
                }
                .show()
    }

    private fun grantRestrictedSettingsWithShizuku() {
        try {
            // Check if root is available (Shizuku or libsu)
            val hasRoot = Shell.isAppGrantedRoot() == true
            val hasShizuku =
                    Shizuku.pingBinder() &&
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

            if (!hasRoot && !hasShizuku) {
                android.widget.Toast.makeText(
                                this,
                                getString(R.string.toast_root_shizuku_required),
                                android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
                return
            }

            android.widget.Toast.makeText(
                            this,
                            getString(R.string.toast_granting_restricted),
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
                            getString(R.string.perm_shizuku_bind_error, e.message)
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
                                                    getString(R.string.toast_user_service_not_connected),
                                                    android.widget.Toast.LENGTH_SHORT
                                            )
                                            .show()
                                }
                                return@Thread
                            }

                            val command = "appops set $packageName ACCESS_RESTRICTED_SETTINGS allow"
                            val exitCodeStr =
                                    userService?.executeCommand(command)
                                            ?: Shell.cmd(command).exec().code.toString()
                            val exitCode = exitCodeStr.toIntOrNull() ?: -1

                            runOnUiThread {
                                if (exitCode == 0) {
                                    android.widget.Toast.makeText(
                                                    this,
                                                    getString(R.string.toast_grant_success),
                                                    android.widget.Toast.LENGTH_SHORT
                                            )
                                            .show()
                                    updatePermissionList()
                                } else {
                                    android.widget.Toast.makeText(
                                                    this,
                                                    getString(R.string.toast_grant_failed, exitCode),
                                                    android.widget.Toast.LENGTH_LONG
                                            )
                                            .show()
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                                this,
                                                getString(R.string.health_error_prefix, e.message),
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
                            getString(R.string.health_error_prefix, e.message),
                            android.widget.Toast.LENGTH_SHORT
                    )
                    .show()
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
                                        getString(R.string.toast_open_settings_error),
                                        android.widget.Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                }
                .setNeutralButton(R.string.perm_button_copy_adb) {
                        dialog: DialogInterface,
                        which: Int ->
                    val clipboard =
                            getSystemService(Context.CLIPBOARD_SERVICE) as
                                    android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("ADB Command", adbCommand)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(
                                    this,
                                    getString(R.string.toast_command_copied),
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
                                getString(R.string.toast_root_shizuku_required),
                                android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
                return
            }

            android.widget.Toast.makeText(
                            this,
                            getString(R.string.toast_granting_perm),
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
                                                    getString(R.string.toast_grant_success),
                                                    android.widget.Toast.LENGTH_SHORT
                                            )
                                            .show()
                                    updatePermissionList()
                                } else {
                                    android.widget.Toast.makeText(
                                                    this,
                                                    getString(R.string.toast_grant_failed_simple, exitCode),
                                                    android.widget.Toast.LENGTH_LONG
                                            )
                                            .show()
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                                this,
                                                getString(R.string.health_error_prefix, e.message),
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
                            getString(R.string.health_error_prefix, e.message),
                            android.widget.Toast.LENGTH_SHORT
                    )
                    .show()
        }
    }
}

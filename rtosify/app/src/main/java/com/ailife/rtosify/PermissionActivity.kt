package com.ailife.rtosify

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
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

class PermissionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PermissionAdapter
    private lateinit var toolbar: Toolbar

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

        // Listener para quando o Shizuku conectar
        Shizuku.addBinderReceivedListener {
            updatePermissionList()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionList()
    }

    private fun updatePermissionList() {
        val perms = mutableListOf<PermissionItem>()

        // 1. Bluetooth (Scan/Connect)
        val hasBT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkPerm(Manifest.permission.BLUETOOTH_SCAN) && checkPerm(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true // Legacy
        }
        perms.add(PermissionItem("BT", getString(R.string.perm_bluetooth), getString(R.string.perm_bluetooth_desc), hasBT))

        // 2. Notification Access
        perms.add(PermissionItem("NOTIF", getString(R.string.perm_notifications), getString(R.string.perm_notifications_desc), isNotificationServiceEnabled()))

        // 3. Install Unknown Apps
        val hasInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true // Not applicable or always true
        }
        perms.add(PermissionItem("INSTALL", getString(R.string.perm_install), getString(R.string.perm_install_desc), hasInstall))

        // 4. Battery Optimization
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else true
        perms.add(PermissionItem("BATTERY", getString(R.string.perm_battery), getString(R.string.perm_battery_desc), isIgnoring))

        // 5. Shizuku
        var hasShizuku = false
        try {
            hasShizuku = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {}
        perms.add(PermissionItem("SHIZUKU", getString(R.string.perm_shizuku), getString(R.string.perm_shizuku_desc), hasShizuku))

        // 6. Root
        val isRoot = Shell.isAppGrantedRoot() == true || (Shell.getCachedShell()?.isRoot == true)
        perms.add(PermissionItem("ROOT", getString(R.string.perm_root), getString(R.string.perm_root_desc), isRoot))

        // 7. Location (for BT scan)
        perms.add(PermissionItem("LOCATION", getString(R.string.perm_location), getString(R.string.perm_location_desc), checkPerm(Manifest.permission.ACCESS_FINE_LOCATION)))

        // 8. Background Location
        val hasBG = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkPerm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else true
        perms.add(PermissionItem("LOCATION_BG", getString(R.string.perm_location_bg), getString(R.string.perm_location_bg_desc), hasBG))

        // 9. Post Notifications (Android 13+)
        val hasPostNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkPerm(Manifest.permission.POST_NOTIFICATIONS)
        } else true
        perms.add(PermissionItem("POST_NOTIF", getString(R.string.perm_post_notif), getString(R.string.perm_post_notif_desc), hasPostNotif))

        // 10. Camera
        perms.add(PermissionItem("CAMERA", getString(R.string.perm_camera), getString(R.string.perm_camera_desc), checkPerm(Manifest.permission.CAMERA)))

        // 11. DND Access
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hasDnd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notifManager.isNotificationPolicyAccessGranted
        } else true
        perms.add(PermissionItem("DND", getString(R.string.perm_dnd), getString(R.string.perm_dnd_desc), hasDnd))

        // 12. Storage
        val hasStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkPerm(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        perms.add(PermissionItem("STORAGE", getString(R.string.perm_storage), getString(R.string.perm_storage_desc), hasStorage))
        
        // 13. Call Phone
        perms.add(PermissionItem("CALL", getString(R.string.perm_call), getString(R.string.perm_call_desc), checkPerm(Manifest.permission.CALL_PHONE)))

        // 14. Calendar
        perms.add(PermissionItem("CALENDAR", getString(R.string.perm_calendar), getString(R.string.perm_calendar_desc), checkPerm(Manifest.permission.READ_CALENDAR)))

        // 15. Contacts
        perms.add(PermissionItem("CONTACTS", getString(R.string.perm_contacts), getString(R.string.perm_contacts_desc), checkPerm(Manifest.permission.READ_CONTACTS)))

        adapter.updateList(perms)
    }

    private fun checkPerm(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, MyNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun handlePermissionClick(perm: PermissionItem) {
        when (perm.id) {
            "BT" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 101)
                }
            }
            "NOTIF" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "INSTALL" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
            }
            "BATTERY" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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
                        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (intent != null) {
                            startActivity(intent)
                        } else {
                             val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                             startActivity(browserIntent)
                        }
                    }
                } catch (e: Exception) {}
            }
            "ROOT" -> {
                Shell.getShell { /* Triggers root request */ }
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
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
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
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    data class PermissionItem(val id: String, val title: String, val desc: String, val granted: Boolean)

    class PermissionAdapter(private val onClick: (PermissionItem) -> Unit) : RecyclerView.Adapter<PermissionAdapter.ViewHolder>() {
        private var list = listOf<PermissionItem>()

        fun updateList(newList: List<PermissionItem>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_permission, parent, false)
            return ViewHolder(view, onClick)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(list[position])
        }

        override fun getItemCount() = list.size

        class ViewHolder(itemView: View, private val onClick: (PermissionItem) -> Unit) : RecyclerView.ViewHolder(itemView) {
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
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                } else {
                    imgStatus.setImageResource(android.R.drawable.presence_busy)
                    tvStatus.text = itemView.context.getString(R.string.perm_not_granted)
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                }

                itemView.setOnClickListener { onClick(item) }
            }
        }
    }
}

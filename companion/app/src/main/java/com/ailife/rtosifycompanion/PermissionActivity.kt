package com.ailife.rtosifycompanion

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

        // 1. Bluetooth
        val hasBT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkPerm(Manifest.permission.BLUETOOTH_SCAN) && checkPerm(Manifest.permission.BLUETOOTH_CONNECT)
        } else true
        perms.add(PermissionItem("BT", getString(R.string.perm_bluetooth), getString(R.string.perm_bluetooth_desc), hasBT))

        // 2. Install Unknown Apps
        val hasInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else true
        perms.add(PermissionItem("INSTALL", getString(R.string.perm_install), getString(R.string.perm_install_desc), hasInstall))

        // 3. Shizuku
        var hasShizuku = false
        try {
            hasShizuku = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {}
        perms.add(PermissionItem("SHIZUKU", getString(R.string.perm_shizuku), getString(R.string.perm_shizuku_desc), hasShizuku))

        // 4. Root
        val isRoot = (Shell.isAppGrantedRoot() == true)
        perms.add(PermissionItem("ROOT", getString(R.string.perm_root), getString(R.string.perm_root_desc), isRoot))

        // 5. Location
        perms.add(PermissionItem("LOCATION", getString(R.string.perm_location), getString(R.string.perm_location_desc), checkPerm(Manifest.permission.ACCESS_FINE_LOCATION)))

        // 6. Background Location
        val hasBG = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkPerm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else true
        perms.add(PermissionItem("LOCATION_BG", getString(R.string.perm_location_bg), getString(R.string.perm_location_bg_desc), hasBG))
        
        // 7. Storage
        val hasStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            checkPerm(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        perms.add(PermissionItem("STORAGE", getString(R.string.perm_storage), getString(R.string.perm_storage_desc), hasStorage))

        // 8. Post Notifications (Android 13+)
        val hasPostNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkPerm(Manifest.permission.POST_NOTIFICATIONS)
        } else true
        perms.add(PermissionItem("POST_NOTIF", getString(R.string.perm_post_notif), getString(R.string.perm_post_notif_desc), hasPostNotif))

        // 9. DND Access
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hasDnd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notifManager.isNotificationPolicyAccessGranted
        } else true
        perms.add(PermissionItem("DND", getString(R.string.perm_dnd), getString(R.string.perm_dnd_desc), hasDnd))

        // 10. Calendar
        perms.add(PermissionItem("CALENDAR", getString(R.string.perm_calendar), getString(R.string.perm_calendar_desc), checkPerm(Manifest.permission.WRITE_CALENDAR)))

        // 11. Contacts
        perms.add(PermissionItem("CONTACTS", getString(R.string.perm_contacts), getString(R.string.perm_contacts_desc), checkPerm(Manifest.permission.WRITE_CONTACTS)))

        // 12. Watch Face Dir
        val hasWF = isWatchFaceDirAccessible()
        perms.add(PermissionItem("WATCHFACE", getString(R.string.perm_watchface), getString(R.string.perm_watchface_desc), hasWF))

        adapter.updateList(perms)
    }

    private fun checkPerm(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun handlePermissionClick(perm: PermissionItem) {
        when (perm.id) {
            "BT" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 101)
                }
            }
            "INSTALL" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
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
                        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
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
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
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
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            }
            "CALENDAR" -> requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 111)
            "CONTACTS" -> requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), 112)
            "WATCHFACE" -> handleWatchFacePermissionClick()
        }
    }

    private fun isWatchFaceDirAccessible(): Boolean {
        // Shizuku or Root is always enough
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
        if (Shell.isAppGrantedRoot() == true) return true

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ checking SAF for specific folder
            val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Fcom.ailife.ClockSkinCoco")
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        } else {
            checkPerm(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
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
            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A" + path.replace("/", "%2F"))
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
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
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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

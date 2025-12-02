package com.marinov.watch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var switchEnable: SwitchMaterial
    private lateinit var layoutPermissionWarning: LinearLayout
    private lateinit var layoutAppsSection: LinearLayout
    private lateinit var btnOpenSettings: Button
    private lateinit var recyclerView: RecyclerView

    // Agora referenciamos o container de loading inteiro
    private lateinit var layoutLoadingApps: View

    private lateinit var prefs: SharedPreferences
    private lateinit var appAdapter: AppNotificationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        initViews()
        setupMasterSwitch()
        setupRecyclerView()

        // Carrega apps se tiver permissão, senão a UI já trata no updateUiState
        if (isNotificationServiceEnabled()) {
            loadInstalledApps()
        }
    }

    private fun initViews() {
        switchEnable = findViewById(R.id.switchEnableMirroring)
        layoutPermissionWarning = findViewById(R.id.layoutPermissionWarning)
        layoutAppsSection = findViewById(R.id.layoutAppsSection)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        recyclerView = findViewById(R.id.recyclerViewApps)

        // Novo ID do container
        layoutLoadingApps = findViewById(R.id.layoutLoadingApps)
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun setupMasterSwitch() {
        if (!prefs.contains("notification_mirroring_enabled")) {
            prefs.edit().putBoolean("notification_mirroring_enabled", false).apply()
        }

        val isEnabled = prefs.getBoolean("notification_mirroring_enabled", false)
        switchEnable.isChecked = isEnabled

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notification_mirroring_enabled", isChecked).apply()
            updateUiState()
        }
    }

    private fun updateUiState() {
        val hasPermission = isNotificationServiceEnabled()

        if (hasPermission && prefs.getBoolean("waiting_for_permission", false)) {
            prefs.edit()
                .remove("waiting_for_permission")
                .putBoolean("notification_mirroring_enabled", true)
                .apply()
            switchEnable.isChecked = true
        }

        val isSwitchOn = prefs.getBoolean("notification_mirroring_enabled", false)

        if (switchEnable.isChecked != isSwitchOn) {
            switchEnable.isChecked = isSwitchOn
        }

        if (!hasPermission) {
            switchEnable.isEnabled = false
            switchEnable.isChecked = false
            layoutPermissionWarning.visibility = View.VISIBLE
            layoutAppsSection.visibility = View.GONE

            btnOpenSettings.setOnClickListener {
                prefs.edit().putBoolean("waiting_for_permission", true).apply()
                openNotificationAccessSettings()
            }
        } else {
            switchEnable.isEnabled = true
            layoutPermissionWarning.visibility = View.GONE
            layoutAppsSection.visibility = View.VISIBLE

            if (isSwitchOn) {
                recyclerView.alpha = 1.0f
                recyclerView.isEnabled = true
            } else {
                recyclerView.alpha = 0.4f
            }

            // Se a lista estiver vazia e não estiver carregando, tenta carregar
            if (appAdapter.itemCount == 0 && layoutLoadingApps.visibility == View.GONE) {
                loadInstalledApps()
            }
        }
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        appAdapter = AppNotificationAdapter(prefs)
        recyclerView.adapter = appAdapter
    }

    private fun loadInstalledApps() {
        // Mostra o container de loading (Texto + Spinner)
        layoutLoadingApps.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val appsList = mutableListOf<AppNotificationItem>()

            val allPackages = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }

            val packageNamesFound = mutableSetOf<String>()

            for (appInfo in allPackages) {
                // Ignora o próprio app
                if (appInfo.packageName == packageName) continue

                try {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    appsList.add(AppNotificationItem(label, appInfo.packageName, icon))
                    packageNamesFound.add(appInfo.packageName)
                } catch (e: Exception) {
                    continue
                }
            }
            appsList.sortBy { it.appName.lowercase() }

            if (!prefs.contains("allowed_notif_packages")) {
                prefs.edit().putStringSet("allowed_notif_packages", packageNamesFound).apply()
                appAdapter.reloadAllowedPackages()
            }

            withContext(Dispatchers.Main) {
                // Esconde o container de loading
                layoutLoadingApps.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                appAdapter.setData(appsList)
            }
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, MyNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao abrir configurações.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

// --- Classes do Adapter ---
data class AppNotificationItem(
    val appName: String,
    val packageName: String,
    val icon: Drawable
)

class AppNotificationAdapter(private val prefs: SharedPreferences) : RecyclerView.Adapter<AppNotificationAdapter.ViewHolder>() {

    private var items = listOf<AppNotificationItem>()
    private val allowedPackages = mutableSetOf<String>()

    init {
        reloadAllowedPackages()
    }

    fun reloadAllowedPackages() {
        val savedSet = prefs.getStringSet("allowed_notif_packages", null)
        allowedPackages.clear()
        if (savedSet != null) {
            allowedPackages.addAll(savedSet)
        }
    }

    fun setData(newItems: List<AppNotificationItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvAppPackage: TextView = itemView.findViewById(R.id.tvAppPackage)
        private val switchApp: SwitchMaterial = itemView.findViewById(R.id.switchAppNotification)

        fun bind(item: AppNotificationItem) {
            tvName.text = item.appName
            tvAppPackage.text = item.packageName
            imgIcon.setImageDrawable(item.icon)

            switchApp.setOnCheckedChangeListener(null)
            switchApp.isChecked = allowedPackages.contains(item.packageName)

            switchApp.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    allowedPackages.add(item.packageName)
                } else {
                    allowedPackages.remove(item.packageName)
                }
                prefs.edit().putStringSet("allowed_notif_packages", allowedPackages.toSet()).apply()
            }
        }
    }
}
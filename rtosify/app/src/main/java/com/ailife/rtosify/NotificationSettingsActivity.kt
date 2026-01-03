package com.ailife.rtosify

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.LruCache

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var switchEnable: SwitchMaterial
    private lateinit var switchSkipScreenOn: SwitchMaterial
    private lateinit var switchNotifyDisconnect: SwitchMaterial
    private lateinit var layoutPermissionWarning: LinearLayout
    private lateinit var layoutAppsSection: LinearLayout
    private lateinit var btnOpenSettings: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var editTextSearch: com.google.android.material.textfield.TextInputEditText
    private lateinit var switchShowSystemApps: SwitchMaterial

    // Agora referenciamos o container de loading inteiro
    private lateinit var layoutLoadingApps: View

    private lateinit var prefs: SharedPreferences
    private lateinit var appAdapter: AppNotificationAdapter

    private var loadingJob: Job? = null
    private var isLoadingVisible = false
    private var allAppsList = listOf<AppNotificationItem>()

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
        setupSkipScreenOnSwitch()
        setupNotifyDisconnectSwitch()
        setupRecyclerView()
        setupSearch()
        setupSystemAppsToggle()

        // Carrega apps se tiver permissão, senão a UI já trata no updateUiState
        if (isNotificationServiceEnabled()) {
            loadInstalledApps()
        }
    }

    private fun initViews() {
        switchEnable = findViewById(R.id.switchEnableMirroring)
        switchSkipScreenOn = findViewById(R.id.switchSkipScreenOn)
        switchNotifyDisconnect = findViewById(R.id.switchNotifyDisconnect)
        layoutPermissionWarning = findViewById(R.id.layoutPermissionWarning)
        layoutAppsSection = findViewById(R.id.layoutAppsSection)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        recyclerView = findViewById(R.id.recyclerViewApps)
        editTextSearch = findViewById(R.id.editTextSearch)
        switchShowSystemApps = findViewById(R.id.switchShowSystemApps)

        // Novo ID do container
        layoutLoadingApps = findViewById(R.id.layoutLoadingApps)
    }

    private fun setupSearch() {
        editTextSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                filterApps(s?.toString() ?: "")
            }
        })
    }

    private fun setupSystemAppsToggle() {
        switchShowSystemApps.isChecked = prefs.getBoolean("show_system_apps", false)
        switchShowSystemApps.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_system_apps", isChecked).apply()
            filterApps(editTextSearch.text?.toString() ?: "")
        }
    }

    private var filterJob: Job? = null
    
    private fun filterApps(query: String) {
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            val showSystemApps = switchShowSystemApps.isChecked
            val filtered = withContext(Dispatchers.Default) {
                allAppsList.filter { app ->
                    val matchesSearch = if (query.isEmpty()) {
                        true
                    } else {
                        app.packageName.contains(query, ignoreCase = true) ||
                                app.appName.contains(query, ignoreCase = true)
                    }

                    val matchesSystemFilter = if (showSystemApps) {
                        true
                    } else {
                        // Only show non-system apps
                        !app.isSystemApp
                    }

                    matchesSearch && matchesSystemFilter
                }
            }
            appAdapter.setData(filtered)
        }
    }

    private fun setupSkipScreenOnSwitch() {
        val isSkipEnabled = prefs.getBoolean("skip_screen_on_enabled", false)
        switchSkipScreenOn.isChecked = isSkipEnabled
        switchSkipScreenOn.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("skip_screen_on_enabled", isChecked).apply()
        }
    }

    private fun setupNotifyDisconnectSwitch() {
        val isEnabled = prefs.getBoolean("notify_on_disconnect", false)
        switchNotifyDisconnect.isChecked = isEnabled
        switchNotifyDisconnect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notify_on_disconnect", isChecked).apply()
            
            // Sync with service if connected
            val intent = Intent("com.ailife.rtosify.ACTION_UPDATE_SETTINGS")
            sendBroadcast(intent)
        }
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
            if (appAdapter.itemCount == 0 && !isLoadingVisible) {
                loadInstalledApps()
            }
        }
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        appAdapter = AppNotificationAdapter(prefs)
        recyclerView.adapter = appAdapter

        // Optimize RecyclerView performance for large lists
        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(20)  // Cache more views to reduce rebinding during scroll
    }

    private fun loadInstalledApps() {
        if (isLoadingVisible) return

        // Mostra o container de loading (Texto + Spinner)
        layoutLoadingApps.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        isLoadingVisible = true

        loadingJob?.cancel()
        loadingJob = lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val packageNamesFound = mutableSetOf<String>()

            // Use getInstalledApplications instead of getInstalledPackages - more efficient
            // since we only need ApplicationInfo, not full PackageInfo
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            val appsList = installedApps.mapNotNull { appInfo ->
                val pkgName = appInfo.packageName
                // Skip our own app
                if (pkgName == packageName) return@mapNotNull null

                val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                // Load app name during initial load instead of lazily
                // This prevents UI jank when scrolling through the list
                val appName = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    pkgName
                }

                packageNamesFound.add(pkgName)
                AppNotificationItem(appName, pkgName, null, isSystemApp)
            }.distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }  // Sort by app name instead of package name for better UX

            if (!prefs.contains("allowed_notif_packages")) {
                prefs.edit().putStringSet("allowed_notif_packages", packageNamesFound).apply()
                withContext(Dispatchers.Main) {
                    appAdapter.reloadAllowedPackages()
                }
            }

            withContext(Dispatchers.Main) {
                isLoadingVisible = false
                layoutLoadingApps.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                allAppsList = appsList
                // Apply initial filter (only user apps by default)
                filterApps(editTextSearch.text?.toString() ?: "")
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
            Toast.makeText(this, getString(R.string.toast_error_open_settings), Toast.LENGTH_SHORT).show()
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
    val appName: String, // Deixamos como fallback ou vazio inicialmente
    val packageName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean = false
)

class AppNotificationAdapter(private val prefs: SharedPreferences) : RecyclerView.Adapter<AppNotificationAdapter.ViewHolder>() {

    private var items = listOf<AppNotificationItem>()
    private val allowedPackages = mutableSetOf<String>()
    
    // Cache para evitar IPC repetitivo para metadados
    private val labelCache = LruCache<String, String>(200)
    private val iconCache = LruCache<String, Drawable>(100)

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

        private var loadJob: Job? = null

        fun bind(item: AppNotificationItem) {
            tvAppPackage.text = item.packageName
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

            // Cancela job anterior se a view for reciclada
            loadJob?.cancel()

            // Use pre-loaded app name if available (loaded during initial app list fetch)
            if (item.appName.isNotEmpty()) {
                tvName.text = item.appName
            } else {
                // Fallback: use cached label or load lazily (for edge cases)
                val cachedLabel = labelCache.get(item.packageName)
                if (cachedLabel != null) {
                    tvName.text = cachedLabel
                } else {
                    tvName.text = item.packageName
                }
            }

            // Check cache for icon first
            val cachedIcon = iconCache.get(item.packageName)
            if (cachedIcon != null) {
                imgIcon.setImageDrawable(cachedIcon)
                return
            }

            // Set default icon while loading
            imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)

            // Load icon in background only (app name is already loaded during initial fetch)
            loadJob = (itemView.context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                val pm = itemView.context.packageManager
                try {
                    val appInfo = pm.getApplicationInfo(item.packageName, 0)
                    val icon = pm.getApplicationIcon(appInfo)

                    iconCache.put(item.packageName, icon)

                    withContext(Dispatchers.Main) {
                        imgIcon.setImageDrawable(icon)
                    }
                } catch (e: Exception) {
                    // Icon loading failed, keep default icon
                }
            }
        }
    }
}
package com.ailife.rtosify

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationAppListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var editTextSearch: com.google.android.material.textfield.TextInputEditText
    private lateinit var switchShowSystemApps: MaterialSwitch
    private lateinit var layoutLoadingApps: View

    private lateinit var devicePrefManager: DevicePrefManager
    private lateinit var globalPrefs: SharedPreferences
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()
    private lateinit var appAdapter: AppNotificationAdapter

    private var loadingJob: Job? = null
    private var isLoadingVisible = false
    private var allAppsList = listOf<AppNotificationItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_app_list)
        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, findViewById(R.id.recyclerViewApps))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.notif_apps_title)

        devicePrefManager = DevicePrefManager(this)
        globalPrefs = devicePrefManager.getGlobalPrefs()

        initViews()
        setupRecyclerView()
        setupSearch()
        setupSystemAppsToggle()

        loadInstalledApps()
    }

    override fun onResume() {
        super.onResume()
        if (::appAdapter.isInitialized) {
            appAdapter.reloadAllowedPackages()
            appAdapter.notifyDataSetChanged()
        }
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerViewApps)
        editTextSearch = findViewById(R.id.editTextSearch)
        switchShowSystemApps = findViewById(R.id.switchShowSystemApps)
        layoutLoadingApps = findViewById(R.id.layoutLoadingApps)
    }

    private var searchJob: Job? = null

    private fun setupSearch() {
        editTextSearch.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                    ) {}
                    override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                    ) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        searchJob?.cancel()
                        searchJob = lifecycleScope.launch {
                            kotlinx.coroutines.delay(300) // Debounce
                            filterApps(s?.toString() ?: "")
                        }
                    }
                }
        )
    }

    private fun setupSystemAppsToggle() {
        switchShowSystemApps.isChecked = globalPrefs.getBoolean("show_system_apps", false)
        switchShowSystemApps.setOnCheckedChangeListener { _, isChecked ->
            globalPrefs.edit().putBoolean("show_system_apps", isChecked).apply()
            filterApps(editTextSearch.text?.toString() ?: "")
        }
    }

    private var filterJob: Job? = null

    private fun filterApps(query: String) {
        filterJob?.cancel()
        filterJob =
                lifecycleScope.launch {
                    val showSystemApps = switchShowSystemApps.isChecked
                    val filtered =
                            withContext(Dispatchers.Default) {
                                allAppsList.filter { app ->
                                    val matchesSearch =
                                            if (query.isEmpty()) {
                                                true
                                            } else {
                                                app.packageName.contains(
                                                        query,
                                                        ignoreCase = true
                                                ) || app.appName.contains(query, ignoreCase = true)
                                            }

                                    val matchesSystemFilter =
                                            if (showSystemApps) {
                                                true
                                            } else {
                                                !app.isSystemApp
                                            }

                                    matchesSearch && matchesSystemFilter
                                }
                            }
                    appAdapter.setData(filtered)
                }
    }

    @android.annotation.SuppressLint("InvalidSetHasFixedSize")
    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        appAdapter = AppNotificationAdapter(activePrefs)
        recyclerView.adapter = appAdapter

        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(20)
    }

    private fun loadInstalledApps() {
        if (isLoadingVisible) return

        layoutLoadingApps.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        isLoadingVisible = true

        loadingJob?.cancel()
        loadingJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val packageNamesFound = mutableSetOf<String>()
                
                // 1. Get all installed apps
                val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

                // 2. Map to AppNotificationItem, loading LABELS here (IO thread)
                // This is the heavy operation the user wants to wait for upfront
                val appsList = installedApps.mapNotNull { appInfo ->
                    // Check cancellation periodically
                    if (!isActive) return@mapNotNull null
                    
                    val pkgName = appInfo.packageName
                    if (pkgName == packageName) return@mapNotNull null

                    val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                    // Load label here so it's ready for search
                    val appName = try {
                         appInfo.loadLabel(pm).toString()
                    } catch (e: Exception) {
                         pkgName
                    }

                    packageNamesFound.add(pkgName)
                    AppNotificationItem(appName, pkgName, null, isSystemApp)
                }

                // 3. Sort
                val sortedList = appsList.sortedBy { it.appName.lowercase() }

                // 4. Initial pref setup if needed
                if (!activePrefs.contains("allowed_notif_packages")) {
                    activePrefs.edit().putStringSet("allowed_notif_packages", packageNamesFound).apply()
                    withContext(Dispatchers.Main) { 
                        if (::appAdapter.isInitialized) {
                            appAdapter.reloadAllowedPackages()
                        }
                    }
                }

                // 5. Update UI
                withContext(Dispatchers.Main) {
                    if (!isActive) return@withContext
                    allAppsList = sortedList
                    filterApps(editTextSearch.text?.toString() ?: "")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        isLoadingVisible = false
                        layoutLoadingApps.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                    }
                }
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
}

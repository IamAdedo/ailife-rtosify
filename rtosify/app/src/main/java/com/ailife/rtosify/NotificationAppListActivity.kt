package com.ailife.rtosify

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
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

class NotificationAppListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var editTextSearch: com.google.android.material.textfield.TextInputEditText
    private lateinit var switchShowSystemApps: SwitchMaterial
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

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
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

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerViewApps)
        editTextSearch = findViewById(R.id.editTextSearch)
        switchShowSystemApps = findViewById(R.id.switchShowSystemApps)
        layoutLoadingApps = findViewById(R.id.layoutLoadingApps)
    }

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
                        filterApps(s?.toString() ?: "")
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
        loadingJob =
                lifecycleScope.launch(Dispatchers.IO) {
                    val pm = packageManager
                    val packageNamesFound = mutableSetOf<String>()

                    val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

                    val appsList =
                            installedApps
                                    .mapNotNull { appInfo ->
                                        val pkgName = appInfo.packageName
                                        if (pkgName == packageName) return@mapNotNull null

                                        val isSystemApp =
                                                (appInfo.flags and
                                                        android.content.pm.ApplicationInfo
                                                                .FLAG_SYSTEM) != 0

                                        val appName =
                                                try {
                                                    pm.getApplicationLabel(appInfo).toString()
                                                } catch (e: Exception) {
                                                    pkgName
                                                }

                                        packageNamesFound.add(pkgName)
                                        AppNotificationItem(appName, pkgName, null, isSystemApp)
                                    }
                                    .distinctBy { it.packageName }
                                    .sortedBy { it.appName.lowercase() }

                    if (!activePrefs.contains("allowed_notif_packages")) {
                        activePrefs
                                .edit()
                                .putStringSet("allowed_notif_packages", packageNamesFound)
                                .apply()
                        withContext(Dispatchers.Main) { appAdapter.reloadAllowedPackages() }
                    }

                    withContext(Dispatchers.Main) {
                        isLoadingVisible = false
                        layoutLoadingApps.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                        allAppsList = appsList
                        filterApps(editTextSearch.text?.toString() ?: "")
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

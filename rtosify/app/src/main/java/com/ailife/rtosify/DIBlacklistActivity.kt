package com.ailife.rtosify

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DIBlacklistActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchEdit: EditText
    private lateinit var showSystemAppsSwitch: SwitchMaterial
    private lateinit var adapter: DIBlacklistAdapter
    
    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()
    
    private var allApps = listOf<AppInfo>()
    private var bluetoothService: BluetoothService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_di_blacklist)

        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        val scrollView = findViewById<View>(R.id.mainContent)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, scrollView)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Blacklisted Apps"

        devicePrefManager = DevicePrefManager(this)

        // Bind to BluetoothService
        val serviceIntent = Intent(this, BluetoothService::class.java)
        bindService(serviceIntent, object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                bluetoothService = (service as? BluetoothService.LocalBinder)?.getService()
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                bluetoothService = null
            }
        }, BIND_AUTO_CREATE)

        initViews()
        setupSearch()
        setupSystemAppsToggle()
        setupRecyclerView()
        loadInstalledApps()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerViewApps)
        searchEdit = findViewById(R.id.editTextSearch)
        showSystemAppsSwitch = findViewById(R.id.switchShowSystemApps)
    }

    private fun setupSearch() {
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupSystemAppsToggle() {
        showSystemAppsSwitch.setOnCheckedChangeListener { _, _ ->
            filterApps(searchEdit.text.toString())
        }
    }

    private fun filterApps(query: String) {
        val showSystemApps = showSystemAppsSwitch.isChecked
        val filtered = allApps.filter { app ->
            val matchesQuery = app.name.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            val matchesSystemFilter = showSystemApps || !app.isSystemApp
            matchesQuery && matchesSystemFilter
        }
        adapter.updateList(filtered)
    }

    private fun setupRecyclerView() {
        val blacklistedPackages = activePrefs.getStringSet("di_blacklist_apps", emptySet()) ?: emptySet()
        
        adapter = DIBlacklistAdapter(
            apps = emptyList(),
            blacklistedPackages = blacklistedPackages.toMutableSet()
        ) { packageName, isBlacklisted ->
            onAppBlacklistChanged(packageName, isBlacklisted)
        }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun loadInstalledApps() {
        CoroutineScope(Dispatchers.IO).launch {
            // Load watch's installed apps from SharedPreferences
            // These are sent from the watch via Bluetooth and stored locally
            val prefs = getSharedPreferences("rtosify_prefs", MODE_PRIVATE)
            val installedAppsJson = prefs.getString("installed_apps_list", "[]")
            
            val apps = try {
                // Parse JSON array of installed apps
                val jsonArray = org.json.JSONArray(installedAppsJson)
                val appList = mutableListOf<AppInfo>()
                
                for (i in 0 until jsonArray.length()) {
                    val jsonApp = jsonArray.getJSONObject(i)
                    appList.add(AppInfo(
                        name = jsonApp.optString("label", "Unknown"),
                        packageName = jsonApp.optString("packageName", ""),
                        isSystemApp = jsonApp.optBoolean("isSystem", false)
                    ))
                }
                appList.sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                android.util.Log.e("DIBlacklist", "Error loading watch apps: ${e.message}")
                // Fallback to empty list if watch apps not available
                emptyList()
            }
            
            withContext(Dispatchers.Main) {
                if (apps.isEmpty()) {
                    // Show a message that watch apps need to be synced
                    android.widget.Toast.makeText(
                        this@DIBlacklistActivity,
                        "No watch apps found. Please ensure watch is connected.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                allApps = apps
                filterApps(searchEdit.text.toString())
            }
        }
    }

    private fun onAppBlacklistChanged(packageName: String, isBlacklisted: Boolean) {
        val currentBlacklist = activePrefs.getStringSet("di_blacklist_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        if (isBlacklisted) {
            currentBlacklist.add(packageName)
        } else {
            currentBlacklist.remove(packageName)
        }
        
        activePrefs.edit().putStringSet("di_blacklist_apps", currentBlacklist).apply()
        
        // Sync to watch
        bluetoothService?.syncDynamicIslandSettings()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unbind service if needed
    }

    data class AppInfo(
        val name: String,
        val packageName: String,
        val isSystemApp: Boolean
    )
}

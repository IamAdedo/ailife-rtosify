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
import com.google.android.material.appbar.MaterialToolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DIBlacklistActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchEdit: EditText
    private lateinit var showSystemAppsSwitch: MaterialSwitch
    private lateinit var adapter: DIBlacklistAdapter
    private lateinit var btnSelectAll: View
    private lateinit var btnSelectNone: View
    private lateinit var btnInvertSelection: View
    private lateinit var layoutLoadingApps: View
    
    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()
    
    private val appsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == BluetoothService.ACTION_APPS_RECEIVED) {
                val json = intent.getStringExtra("apps_json") ?: return
                onAppsReceived(json)
            }
        }
    }
    
    private var allApps = listOf<AppInfo>()
    private var bluetoothService: BluetoothService? = null

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            bluetoothService = (service as? BluetoothService.LocalBinder)?.getService()
            // Request apps once bound
            layoutLoadingApps.visibility = View.VISIBLE
            bluetoothService?.requestWatchApps()
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            bluetoothService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_di_blacklist)

        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        val scrollView = findViewById<View>(R.id.mainContent)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, scrollView)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.di_blacklist_title)

        devicePrefManager = DevicePrefManager(this)

        // Bind to BluetoothService
        val serviceIntent = Intent(this, BluetoothService::class.java)
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        
        // Register receiver for watch apps
        val filter = android.content.IntentFilter(BluetoothService.ACTION_APPS_RECEIVED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(appsReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(appsReceiver, filter)
        }

        initViews()
        setupSelectionButtons()
        setupSearch()
        setupSystemAppsToggle()
        setupRecyclerView()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerViewApps)
        searchEdit = findViewById(R.id.editTextSearch)
        showSystemAppsSwitch = findViewById(R.id.switchShowSystemApps)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnSelectNone = findViewById(R.id.btnSelectNone)
        btnInvertSelection = findViewById(R.id.btnInvertSelection)
        layoutLoadingApps = findViewById(R.id.layoutLoadingApps)
    }

    private fun setupSelectionButtons() {
        btnSelectAll.setOnClickListener {
            bulkUpdateBlacklist { set ->
                set.addAll(adapter.apps.map { it.packageName })
            }
        }
        btnSelectNone.setOnClickListener {
            bulkUpdateBlacklist { set ->
                set.removeAll(adapter.apps.map { it.packageName }.toSet())
            }
        }
        btnInvertSelection.setOnClickListener {
            bulkUpdateBlacklist { set ->
                adapter.apps.forEach { app ->
                    if (set.contains(app.packageName)) {
                        set.remove(app.packageName)
                    } else {
                        set.add(app.packageName)
                    }
                }
            }
        }
    }

    private fun bulkUpdateBlacklist(updateAction: (MutableSet<String>) -> Unit) {
        val currentBlacklist = activePrefs.getStringSet("di_blacklist_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        updateAction(currentBlacklist)
        activePrefs.edit().putStringSet("di_blacklist_apps", currentBlacklist).apply()

        // Sync to watch
        bluetoothService?.syncDynamicIslandSettings()
        
        // Refresh UI
        adapter.reloadBlacklistedPackages(currentBlacklist)
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

    private fun onAppsReceived(json: String) {
        layoutLoadingApps.visibility = View.GONE
        try {
            val jsonArray = org.json.JSONArray(json)
            val apps = mutableListOf<AppInfo>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                apps.add(AppInfo(
                    name = obj.getString("name"),
                    packageName = obj.getString("package"),
                    icon = obj.optString("icon"),
                    isSystemApp = obj.optBoolean("isSystemApp", false)
                ))
            }
            
            allApps = apps.sortedBy { it.name.lowercase() }
            filterApps(searchEdit.text.toString())
        } catch (e: Exception) {
            android.util.Log.e("DIBlacklist", "Error parsing received apps: ${e.message}")
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(appsReceiver)
        unbindService(serviceConnection)
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


}

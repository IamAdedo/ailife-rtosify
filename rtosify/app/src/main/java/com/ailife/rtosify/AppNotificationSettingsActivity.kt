package com.ailife.rtosify

import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONArray
import org.json.JSONObject

class AppNotificationSettingsActivity : AppCompatActivity() {

    private lateinit var devicePrefManager: DevicePrefManager
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()

    private lateinit var switchAllowNotifications: SwitchMaterial
    private lateinit var switchOngoing: SwitchMaterial
    private lateinit var switchSilent: SwitchMaterial
    private lateinit var switchNavigation: SwitchMaterial
    
    private lateinit var cardSettings: MaterialCardView
    private lateinit var cardAppRules: MaterialCardView
    private lateinit var spinnerPriority: Spinner
    private lateinit var btnAddRule: MaterialButton
    private lateinit var recyclerViewRules: RecyclerView
    
    private lateinit var rulesAdapter: NotificationRulesAdapter
    private var rules = mutableListOf<NotificationRule>()

    private var pkgName: String = ""
    private var appName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_notification_settings)

        pkgName = intent.getStringExtra("EXTRA_PACKAGE_NAME") ?: return finish()
        appName = intent.getStringExtra("EXTRA_APP_NAME") ?: pkgName

        devicePrefManager = DevicePrefManager(this)

        initViews()
        setupToolbar()
        loadAppInfo()
        setupSwitches()
        setupPrioritySpinner()
        setupRulesList()
    }

    private fun initViews() {
        switchAllowNotifications = findViewById(R.id.switchAllowNotifications)
        switchOngoing = findViewById(R.id.switchOngoing)
        switchSilent = findViewById(R.id.switchSilent)
        switchNavigation = findViewById(R.id.switchNavigation)
        
        cardSettings = findViewById(R.id.cardSettings)
        cardAppRules = findViewById(R.id.cardAppRules)
        
        spinnerPriority = findViewById(R.id.spinnerPriority)
        btnAddRule = findViewById(R.id.btnAddRule)
        recyclerViewRules = findViewById(R.id.recyclerViewRules)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""
    }

    private fun loadAppInfo() {
        findViewById<TextView>(R.id.tvAppName).text = appName
        findViewById<TextView>(R.id.tvAppPackage).text = pkgName

        try {
            val icon = packageManager.getApplicationIcon(pkgName)
            findViewById<ImageView>(R.id.imgAppIcon).setImageDrawable(icon)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun setupSwitches() {
        // 1. Allow Notifications (Whitelist)
        // Check global whitelist logic
        val allowedApps = activePrefs.getStringSet("allowed_notif_packages", mutableSetOf()) ?: mutableSetOf()
        val isAllowed = allowedApps.contains(pkgName)
        
        switchAllowNotifications.isChecked = isAllowed
        updateVisibility(isAllowed)

        switchAllowNotifications.setOnCheckedChangeListener { _, isChecked ->
            val currentSet = activePrefs.getStringSet("allowed_notif_packages", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            if (isChecked) {
                currentSet.add(pkgName)
            } else {
                currentSet.remove(pkgName)
            }
            activePrefs.edit().putStringSet("allowed_notif_packages", currentSet).apply()
            updateVisibility(isChecked)
        }

        // 2. Ongoing
        val ongoingKey = "app_ongoing_$pkgName"
        switchOngoing.isChecked = activePrefs.getBoolean(ongoingKey, false)
        switchOngoing.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean(ongoingKey, isChecked).apply()
        }

        // 3. Silent
        val silentKey = "app_silent_$pkgName"
        switchSilent.isChecked = activePrefs.getBoolean(silentKey, true)
        switchSilent.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean(silentKey, isChecked).apply()
        }
        
        // 4. Navigation
        val navKey = "app_is_nav_$pkgName"
        val defaultNavPackages = setOf("com.google.android.apps.maps", "com.waze", "com.sygic.aura", "com.here.app.maps", "com.google.android.apps.mapslite")
        val isDefaultNav = defaultNavPackages.contains(pkgName)
        switchNavigation.isChecked = activePrefs.getBoolean(navKey, isDefaultNav)
        switchNavigation.setOnCheckedChangeListener { _, isChecked ->
            activePrefs.edit().putBoolean(navKey, isChecked).apply()
        }
    }
    
    private fun updateVisibility(isAllowed: Boolean) {
        if (isAllowed) {
            cardSettings.visibility = View.VISIBLE
            cardAppRules.visibility = View.VISIBLE
        } else {
            cardSettings.visibility = View.GONE
            cardAppRules.visibility = View.GONE
        }
    }

    private fun setupPrioritySpinner() {
        val options = arrayOf(
            getString(R.string.priority_any),
            getString(R.string.priority_low),
            getString(R.string.priority_default),
            getString(R.string.priority_high)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPriority.adapter = adapter

        val priorityKey = "app_min_priority_$pkgName"
        val currentPriority = activePrefs.getInt(priorityKey, 0) // 0=Any, 1=Low, 2=Default, 3=High
        spinnerPriority.setSelection(currentPriority)

        spinnerPriority.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                activePrefs.edit().putInt(priorityKey, position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRulesList() {
        recyclerViewRules.layoutManager = LinearLayoutManager(this)
        
        // We reuse the adapter from NotificationRulesActivity
        // But we need to define the callbacks
        rulesAdapter = NotificationRulesAdapter(
            onEditClick = { rule -> showAddEditRuleDialog(rule) },
            onDeleteClick = { rule -> deleteRule(rule) } 
        )
        recyclerViewRules.adapter = rulesAdapter
        
        loadRules()
        
        btnAddRule.setOnClickListener {
             showAddEditRuleDialog(null)
        }
    }
    
    private fun loadRules() {
        val rulesJson = activePrefs.getString("notification_rules", "[]") ?: "[]"
        rules.clear()
        
        try {
            val jsonArray = JSONArray(rulesJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pName = obj.getString("packageName")
                
                // Only show rules for THIS package
                if (pName == pkgName) {
                    rules.add(
                        NotificationRule(
                            id = obj.getString("id"),
                            packageName = pName,
                            mode = obj.getString("mode"),
                            titlePattern = obj.optString("titlePattern", ""),
                            contentPattern = obj.optString("contentPattern", "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        rulesAdapter.setData(rules)
    }
    
    // NOTE: This duplicates logic from NotificationRulesActivity. 
    // Ideally we should refactor Rule Management into a Helper class.
    private fun saveAllRules(newRule: NotificationRule?, deleteId: String?) {
        // 1. Load ALL existing rules (global)
        val rulesJson = activePrefs.getString("notification_rules", "[]") ?: "[]"
        val allRules = mutableListOf<NotificationRule>()
         try {
            val jsonArray = JSONArray(rulesJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                 allRules.add(
                    NotificationRule(
                        id = obj.getString("id"),
                        packageName = obj.getString("packageName"),
                        mode = obj.getString("mode"),
                        titlePattern = obj.optString("titlePattern", ""),
                        contentPattern = obj.optString("contentPattern", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // 2. Modify
        if (deleteId != null) {
            allRules.removeAll { it.id == deleteId }
        }
        
        if (newRule != null) {
            // Remove existing if editing (ID match)
            allRules.removeAll { it.id == newRule.id }
            allRules.add(newRule)
        }
        
        // 3. Save
        val jsonArray = JSONArray()
        allRules.forEach { rule ->
            val obj = JSONObject()
            obj.put("id", rule.id)
            obj.put("packageName", rule.packageName)
            obj.put("mode", rule.mode)
            obj.put("titlePattern", rule.titlePattern)
            obj.put("contentPattern", rule.contentPattern)
            jsonArray.put(obj)
        }
        activePrefs.edit().putString("notification_rules", jsonArray.toString()).apply()

        // Notify listener
        val intent = android.content.Intent("com.ailife.rtosify.ACTION_RULES_UPDATED")
        intent.setPackage(packageName)
        sendBroadcast(intent)
        
        // 4. Reload local UI
        loadRules()
    }
    
    private fun deleteRule(rule: NotificationRule) {
        AlertDialog.Builder(this)
            .setTitle(R.string.rule_delete_title)
            .setMessage(getString(R.string.rule_delete_msg, rule.packageName))
            .setPositiveButton(R.string.label_delete) { _, _ ->
                saveAllRules(null, rule.id)
            }
            .setNegativeButton(R.string.alarm_cancel, null)
            .show()
    }

    // Simplified dialog since we only support THIS package
    private fun showAddEditRuleDialog(existingRule: NotificationRule?) {
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_notification_rule, null)

        val editTitlePattern = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTitlePattern)
        val editContentPattern = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editContentPattern)
        val btnWhitelist = dialogView.findViewById<android.widget.Button>(R.id.btnWhitelist)
        val btnBlacklist = dialogView.findViewById<android.widget.Button>(R.id.btnBlacklist)
        
        var selectedMode = existingRule?.mode ?: "whitelist"
        existingRule?.let {
            editTitlePattern.setText(it.titlePattern)
            editContentPattern.setText(it.contentPattern)
            selectedMode = it.mode
        }

        fun updateModeButtons() {
            if (selectedMode == "whitelist") {
                btnWhitelist.isSelected = true
                btnBlacklist.isSelected = false
                btnWhitelist.setBackgroundColor(getColor(android.R.color.holo_green_dark))
                btnWhitelist.setTextColor(getColor(android.R.color.white))
                btnBlacklist.setBackgroundColor(getColor(android.R.color.transparent))
                btnBlacklist.setTextColor(getColor(android.R.color.holo_red_dark))
            } else {
                btnWhitelist.isSelected = false
                btnBlacklist.isSelected = true
                btnWhitelist.setBackgroundColor(getColor(android.R.color.transparent))
                btnWhitelist.setTextColor(getColor(android.R.color.holo_green_dark))
                btnBlacklist.setBackgroundColor(getColor(android.R.color.holo_red_dark))
                btnBlacklist.setTextColor(getColor(android.R.color.white))
            }
        }
        updateModeButtons()

        btnWhitelist.setOnClickListener { selectedMode = "whitelist"; updateModeButtons() }
        btnBlacklist.setOnClickListener { selectedMode = "blacklist"; updateModeButtons() }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existingRule == null) R.string.rule_add else R.string.rule_edit)
            .setView(dialogView)
            .setPositiveButton(R.string.rule_save, null)
            .setNegativeButton(R.string.alarm_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val titlePattern = editTitlePattern.text.toString().trim()
                val contentPattern = editContentPattern.text.toString().trim()

                val newRule = NotificationRule(
                    id = existingRule?.id ?: System.currentTimeMillis().toString(),
                    packageName = pkgName,
                    mode = selectedMode,
                    titlePattern = titlePattern,
                    contentPattern = contentPattern
                )
                saveAllRules(newRule, null)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

package com.ailife.rtosify

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class NotificationRule(
        val id: String,
        val packageName: String,
        val mode: String, // "whitelist" or "blacklist"
        val titlePattern: String,
        val contentPattern: String
)

class NotificationRulesActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var fabAddRule: FloatingActionButton
    private lateinit var tvEmptyList: TextView
    private lateinit var devicePrefManager: DevicePrefManager
    private lateinit var globalPrefs: SharedPreferences
    private val activePrefs: SharedPreferences
        get() = devicePrefManager.getActiveDevicePrefs()
    private lateinit var adapter: NotificationRulesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_rules)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.notif_manage_rules_title)

        devicePrefManager = DevicePrefManager(this)
        globalPrefs = devicePrefManager.getGlobalPrefs()

        initViews()
        setupRecyclerView()
        loadRules()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerViewRules)
        fabAddRule = findViewById(R.id.fabAddRule)
        tvEmptyList = findViewById(R.id.tvEmptyList)

        fabAddRule.setOnClickListener { showAddEditRuleDialog(null) }
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter =
                NotificationRulesAdapter(
                        onEditClick = { rule -> showAddEditRuleDialog(rule) },
                        onDeleteClick = { rule -> showDeleteConfirmDialog(rule) }
                )
        recyclerView.adapter = adapter
    }

    private fun loadRules() {
        val rulesJson = activePrefs.getString("notification_rules", "[]") ?: "[]"
        val rules = mutableListOf<NotificationRule>()

        try {
            val jsonArray = JSONArray(rulesJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                rules.add(
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

        if (rules.isEmpty()) {
            tvEmptyList.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            tvEmptyList.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.setData(rules)
        }
    }

    private fun saveRules(rules: List<NotificationRule>) {
        val jsonArray = JSONArray()
        rules.forEach { rule ->
            val obj = JSONObject()
            obj.put("id", rule.id)
            obj.put("packageName", rule.packageName)
            obj.put("mode", rule.mode)
            obj.put("titlePattern", rule.titlePattern)
            obj.put("contentPattern", rule.contentPattern)
            jsonArray.put(obj)
        }
        activePrefs.edit().putString("notification_rules", jsonArray.toString()).apply()
        android.util.Log.d("RulesActivity", "Rules saved: ${rules.size} rules, JSON: $jsonArray")

        // Notify the notification listener service about the rule change
        val intent = android.content.Intent("com.ailife.rtosify.ACTION_RULES_UPDATED")
        intent.setPackage(packageName)
        sendBroadcast(intent)
        android.util.Log.d("RulesActivity", "Broadcast sent: ACTION_RULES_UPDATED")

        loadRules()
    }

    private fun showAddEditRuleDialog(existingRule: NotificationRule?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_notification_rule, null)

        val editPackageName = dialogView.findViewById<TextInputEditText>(R.id.editPackageName)
        val btnSelectApp = dialogView.findViewById<Button>(R.id.btnSelectApp)
        val editTitlePattern = dialogView.findViewById<TextInputEditText>(R.id.editTitlePattern)
        val editContentPattern = dialogView.findViewById<TextInputEditText>(R.id.editContentPattern)
        val btnWhitelist = dialogView.findViewById<Button>(R.id.btnWhitelist)
        val btnBlacklist = dialogView.findViewById<Button>(R.id.btnBlacklist)

        var selectedMode = existingRule?.mode ?: "whitelist"

        existingRule?.let {
            editPackageName.setText(it.packageName)
            editTitlePattern.setText(it.titlePattern)
            editContentPattern.setText(it.contentPattern)
            selectedMode = it.mode
        }

        btnSelectApp.setOnClickListener {
            showAppSelectionDialog { packageName -> editPackageName.setText(packageName) }
        }

        fun updateModeButtons() {
            if (selectedMode == "whitelist") {
                btnWhitelist.isSelected = true
                btnBlacklist.isSelected = false

                // Whitelist selected - show green
                btnWhitelist.setBackgroundColor(getColor(android.R.color.holo_green_dark))
                btnWhitelist.setTextColor(getColor(android.R.color.white))

                // Blacklist not selected - show default
                btnBlacklist.setBackgroundColor(getColor(android.R.color.transparent))
                btnBlacklist.setTextColor(getColor(android.R.color.holo_red_dark))
            } else {
                btnWhitelist.isSelected = false
                btnBlacklist.isSelected = true

                // Whitelist not selected - show default
                btnWhitelist.setBackgroundColor(getColor(android.R.color.transparent))
                btnWhitelist.setTextColor(getColor(android.R.color.holo_green_dark))

                // Blacklist selected - show red
                btnBlacklist.setBackgroundColor(getColor(android.R.color.holo_red_dark))
                btnBlacklist.setTextColor(getColor(android.R.color.white))
            }
        }

        updateModeButtons()

        btnWhitelist.setOnClickListener {
            selectedMode = "whitelist"
            updateModeButtons()
        }

        btnBlacklist.setOnClickListener {
            selectedMode = "blacklist"
            updateModeButtons()
        }

        val dialog =
                AlertDialog.Builder(this)
                        .setTitle(if (existingRule == null) R.string.notif_manage_rules_title else R.string.rule_edit)
                        .setView(dialogView)
                        .setPositiveButton(R.string.rule_save, null) // Set to null initially
                        .setNegativeButton(R.string.alarm_cancel, null)
                        .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val packageName = editPackageName.text.toString().trim()
                val titlePattern = editTitlePattern.text.toString().trim()
                val contentPattern = editContentPattern.text.toString().trim()

                // Validation
                if (packageName.isEmpty()) {
                    editPackageName.error = getString(R.string.rule_package_required)
                    return@setOnClickListener
                }

                // Check if rule has at least some filtering criteria
                // For whitelist: if patterns are empty, all notifications from this app will be
                // forwarded
                // For blacklist: if patterns are empty, all notifications from this app will be
                // blocked
                // This is intentional behavior, so we just show a warning

                val currentRules = adapter.getRules().toMutableList()

                if (existingRule != null) {
                    currentRules.removeAll { it.id == existingRule.id }
                }

                val newRule =
                        NotificationRule(
                                id = existingRule?.id ?: System.currentTimeMillis().toString(),
                                packageName = packageName,
                                mode = selectedMode,
                                titlePattern = titlePattern,
                                contentPattern = contentPattern
                        )

                currentRules.add(newRule)
                saveRules(currentRules)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showDeleteConfirmDialog(rule: NotificationRule) {
        AlertDialog.Builder(this)
                .setTitle(R.string.rule_delete_title)
                .setMessage(getString(R.string.rule_delete_msg, rule.packageName))
                .setPositiveButton(R.string.label_delete) { _, _ ->
                    val currentRules = adapter.getRules().toMutableList()
                    currentRules.removeAll { it.id == rule.id }
                    saveRules(currentRules)
                }
                .setNegativeButton(R.string.alarm_cancel, null)
                .show()
    }

    private fun showAppSelectionDialog(onAppSelected: (String) -> Unit) {
        val progressDialog =
                AlertDialog.Builder(this)
                        .setMessage(R.string.rule_loading_apps)
                        .setCancelable(false)
                        .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            val appsList =
                    installedApps
                            .mapNotNull { appInfo ->
                                val pkgName = appInfo.packageName
                                if (pkgName == packageName) return@mapNotNull null

                                val appName =
                                        try {
                                            pm.getApplicationLabel(appInfo).toString()
                                        } catch (e: Exception) {
                                            pkgName
                                        }

                                Pair(appName, pkgName)
                            }
                            .sortedBy { it.first.lowercase() }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()

                // Show searchable dialog
                showSearchableAppDialog(appsList, onAppSelected)
            }
        }
    }

    private fun showSearchableAppDialog(
            appsList: List<Pair<String, String>>,
            onAppSelected: (String) -> Unit
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_selector, null)
        val searchInput = dialogView.findViewById<android.widget.EditText>(R.id.searchInput)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerViewApps)
        val tvNoResults = dialogView.findViewById<TextView>(R.id.tvNoResults)

        val dialog =
                AlertDialog.Builder(this)
                        .setTitle(R.string.app_select_title)
                        .setView(dialogView)
                        .setNegativeButton(R.string.alarm_cancel, null)
                        .create()

        val adapter =
                AppSelectorAdapter(appsList) { selectedPackage ->
                    onAppSelected(selectedPackage)
                    dialog.dismiss()
                }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        searchInput.addTextChangedListener(
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
                        val query = s?.toString()?.lowercase() ?: ""
                        val filtered =
                                if (query.isEmpty()) {
                                    appsList
                                } else {
                                    appsList.filter {
                                        it.first.lowercase().contains(query) ||
                                                it.second.lowercase().contains(query)
                                    }
                                }

                        if (filtered.isEmpty()) {
                            tvNoResults.visibility = View.VISIBLE
                            recyclerView.visibility = View.GONE
                        } else {
                            tvNoResults.visibility = View.GONE
                            recyclerView.visibility = View.VISIBLE
                            adapter.updateList(filtered)
                        }
                    }
                }
        )

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

class NotificationRulesAdapter(
        private val onEditClick: (NotificationRule) -> Unit,
        private val onDeleteClick: (NotificationRule) -> Unit
) : RecyclerView.Adapter<NotificationRulesAdapter.ViewHolder>() {

    private var rules = listOf<NotificationRule>()

    fun setData(newRules: List<NotificationRule>) {
        rules = newRules
        notifyDataSetChanged()
    }

    fun getRules() = rules

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
                LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_notification_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(rules[position])
    }

    override fun getItemCount() = rules.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPackageName: TextView = itemView.findViewById(R.id.tvPackageName)
        private val tvMode: TextView = itemView.findViewById(R.id.tvMode)
        private val tvPatterns: TextView = itemView.findViewById(R.id.tvPatterns)
        private val btnEdit: Button = itemView.findViewById(R.id.btnEdit)
        private val btnDelete: Button = itemView.findViewById(R.id.btnDelete)

        fun bind(rule: NotificationRule) {
            tvPackageName.text = rule.packageName
            tvMode.text = when(rule.mode) {
                "whitelist" -> itemView.context.getString(R.string.rule_whitelist)
                "blacklist" -> itemView.context.getString(R.string.rule_blacklist)
                else -> rule.mode.uppercase()
            }

            val patterns = mutableListOf<String>()
            if (rule.titlePattern.isNotEmpty()) {
                patterns.add(itemView.context.getString(R.string.rule_title_filter, rule.titlePattern))
            }
            if (rule.contentPattern.isNotEmpty()) {
                patterns.add(itemView.context.getString(R.string.rule_content_filter, rule.contentPattern))
            }

            tvPatterns.text =
                    if (patterns.isEmpty()) {
                        itemView.context.getString(R.string.rule_no_filters)
                    } else {
                        patterns.joinToString("\n")
                    }

            btnEdit.setOnClickListener { onEditClick(rule) }
            btnDelete.setOnClickListener { onDeleteClick(rule) }
        }
    }
}

class AppSelectorAdapter(
        private var apps: List<Pair<String, String>>,
        private val onAppClick: (String) -> Unit
) : RecyclerView.Adapter<AppSelectorAdapter.ViewHolder>() {

    fun updateList(newApps: List<Pair<String, String>>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
                LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_app_selector, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount() = apps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPackageName: TextView = itemView.findViewById(R.id.tvPackageName)

        fun bind(app: Pair<String, String>) {
            tvAppName.text = app.first
            tvPackageName.text = app.second

            itemView.setOnClickListener { onAppClick(app.second) }
        }
    }
}

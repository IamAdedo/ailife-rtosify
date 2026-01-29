package com.ailife.rtosify

import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.util.UUID

class FileObserverSettingsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileObserverRulesAdapter
    private lateinit var tvEmptyState: View
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    private val directoryPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra("picked_path") ?: return@registerForActivityResult
            currentDialogPathInput?.setText(path)
            
            // Auto-detect icon if path is Android/data
            if (path.contains("Android/data/")) {
                val parts = path.split("/")
                val dataIndex = parts.indexOf("data")
                if (dataIndex != -1 && dataIndex + 1 < parts.size) {
                    val pkg = parts[dataIndex + 1]
                     // Attempt icon fetch
                     try {
                         val icon = packageManager.getApplicationIcon(pkg)
                         val base64 = drawableToBase64(icon)
                         currentDialogIconBase64 = base64
                     } catch (e: Exception) {
                         // ignore
                     }
                }
            }
        }
    }

    private var onImagePicked: ((String) -> Unit)? = null

    private val imagePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap != null) {
                    val scaledBitmap = resizeBitmap(originalBitmap, 192)
                    val outputStream = ByteArrayOutputStream()
                    scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                    onImagePicked?.invoke(base64)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val scale = kotlin.math.min(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        
        // Don't upscale
        if (scale >= 1.0f) return bitmap
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    // Temporary references for dialog communication
    private var currentDialogPathInput: TextInputEditText? = null
    // private var currentDialogNameInput: TextInputEditText? = null // Removed
    private var currentDialogIconBase64: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_observer_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = getSharedPreferences("file_observer_prefs", Context.MODE_PRIVATE)

        recyclerView = findViewById(R.id.recyclerViewRules)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        val fab = findViewById<FloatingActionButton>(R.id.fabAddRule)

        adapter = FileObserverRulesAdapter(
            onEditClick = { rule -> showRuleDialog(rule) },
            onDeleteClick = { rule -> deleteRule(rule) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        fab.setOnClickListener { showRuleDialog(null) }

        loadRules()
    }

    private fun loadRules() {
        val json = prefs.getString("rules", "[]")
        val type = object : TypeToken<List<FileObserverRule>>() {}.type
        val rules: List<FileObserverRule> = gson.fromJson(json, type) ?: emptyList()
        
        adapter.setData(rules)
        tvEmptyState.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun saveRules(rules: List<FileObserverRule>) {
        val json = gson.toJson(rules)
        prefs.edit().putString("rules", json).apply()
        loadRules()
        
        // Notify BluetoothService to reload rules
        val intent = android.content.Intent("com.ailife.rtosify.ACTION_RELOAD_FILE_RULES")
        sendBroadcast(intent)
    }

    private fun deleteRule(rule: FileObserverRule) {
        AlertDialog.Builder(this)
            .setTitle("Delete Rule")
            .setMessage("Are you sure you want to delete '${rule.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                val currentRules = adapter.getRules().toMutableList() // Hack: added getRules to adapter
                // Actually, adapter doesn't expose list directly in previous code.
                // Re-fetch from prefs or modify adapter.
                // Let's modify load method to return list or fetch from prefs.
                val json = prefs.getString("rules", "[]")
                val type = object : TypeToken<List<FileObserverRule>>() {}.type
                val list: MutableList<FileObserverRule> = gson.fromJson(json, type)
                list.removeAll { it.id == rule.id }
                saveRules(list)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    // Helper to get access inside deleteRule without casting if needed, 
    // but better to just reload from prefs like above.

    private fun showRuleDialog(existingRule: FileObserverRule?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_file_observer_rule, null)
        
        // val etName = dialogView.findViewById<TextInputEditText>(R.id.etRuleName) // Removed from layout
        val etPath = dialogView.findViewById<TextInputEditText>(R.id.etRulePath)
        val textInputLayoutPath = etPath.parent.parent as? TextInputLayout
        val etNotificationTitle = dialogView.findViewById<TextInputEditText>(R.id.etNotificationTitle)
        val ivRuleIcon = dialogView.findViewById<android.widget.ImageView>(R.id.ivRuleIcon)
        val btnPickIcon = dialogView.findViewById<android.widget.Button>(R.id.btnPickIcon)
        val containerIcon = dialogView.findViewById<View>(R.id.tvIconLabel).parent as View
        
        val cbImage = dialogView.findViewById<CheckBox>(R.id.cbTypeImage)
        val cbVideo = dialogView.findViewById<CheckBox>(R.id.cbTypeVideo)
        val cbAudio = dialogView.findViewById<CheckBox>(R.id.cbTypeAudio)
        val cbText = dialogView.findViewById<CheckBox>(R.id.cbTypeText)
        val switchRecursive = dialogView.findViewById<SwitchMaterial>(R.id.switchRecursive)
        val switchSendToWatch = dialogView.findViewById<SwitchMaterial>(R.id.switchSendToWatch)

        currentDialogPathInput = etPath
        currentDialogIconBase64 = existingRule?.iconBase64
        var smallIconType = existingRule?.smallIconType

        fun updateIconPreview() {
            if (!currentDialogIconBase64.isNullOrEmpty()) {
                try {
                    val decodedBytes = Base64.decode(currentDialogIconBase64, Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    ivRuleIcon.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    ivRuleIcon.setImageResource(android.R.drawable.ic_menu_agenda)
                }
            } else {
                ivRuleIcon.setImageResource(android.R.drawable.ic_menu_agenda)
            }
        }

        updateIconPreview()

        btnPickIcon.setOnClickListener {
            showIconPickerDialog { iconBase64, pkgName ->
                currentDialogIconBase64 = iconBase64
                smallIconType = pkgName
                updateIconPreview()
            }
        }

        // Live path detection
        etPath.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val path = s?.toString() ?: return
                if (currentDialogIconBase64 == null || currentDialogIconBase64!!.isEmpty()) {
                    // Try to detect package
                     if (path.contains("Android/media/") || path.contains("Android/data/")) {
                         try {
                              val parts = path.split("/")
                              // Find the part that looks like a package name (com.xxx)
                              // Heuristic: looks like package name and is after 'data' or 'media'
                              var pkg: String? = null
                              val dataIndex = parts.indexOf("data")
                              if (dataIndex != -1 && dataIndex + 1 < parts.size) pkg = parts[dataIndex + 1]
                              
                              if (pkg == null) {
                                  val mediaIndex = parts.indexOf("media")
                                  if (mediaIndex != -1 && mediaIndex + 1 < parts.size) pkg = parts[mediaIndex + 1]
                              }
                              
                              if (pkg != null && pkg.contains(".")) {
                                  try {
                                      val icon = packageManager.getApplicationIcon(pkg)
                                      val base64 = drawableToBase64(icon)
                                      if (base64 != null) {
                                         ivRuleIcon.setImageDrawable(icon) // Set directly for smooth UI
                                         currentDialogIconBase64 = base64
                                         if (smallIconType == null) smallIconType = pkg
                                      }
                                  } catch (e: Exception) {
                                      // Package not found or icon fail
                                  }
                              }
                         } catch (e: Exception) {}
                     }
                }
            }
        })

        // Setup Path Picker
        textInputLayoutPath?.setEndIconOnClickListener {
           val intent = android.content.Intent(this, DirectoryPickerActivity::class.java)
           directoryPickerLauncher.launch(intent)
        }

        if (existingRule != null) {
            etPath.setText(existingRule.path)
            etNotificationTitle.setText(existingRule.notificationTitle)
            cbImage.isChecked = existingRule.types.contains("image")
            cbVideo.isChecked = existingRule.types.contains("video")
            cbAudio.isChecked = existingRule.types.contains("audio")
            cbText.isChecked = existingRule.types.contains("text")
            switchRecursive.isChecked = existingRule.isRecursive
            switchSendToWatch.isChecked = existingRule.sendToWatch
        } else {
            // Default new
            cbImage.isChecked = true
            switchSendToWatch.isChecked = true
        }

        AlertDialog.Builder(this)
            .setTitle(if (existingRule != null) "Edit Rule" else "Add New Rule")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val path = etPath.text.toString()
                
                if (path.isBlank()) {
                    Toast.makeText(this, "Path is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                // Auto-generate name from path
                val name = if (path.endsWith("/")) path.dropLast(1).substringAfterLast("/") 
                           else path.substringAfterLast("/")

                val types = mutableSetOf<String>()
                if (cbImage.isChecked) types.add("image")
                if (cbVideo.isChecked) types.add("video")
                if (cbAudio.isChecked) types.add("audio")
                if (cbText.isChecked) types.add("text")

                if (types.isEmpty()) {
                    Toast.makeText(this, "Select at least one file type", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                // If path maps to Android/Media apps (like WhatsApp Media), try to fetch icon if not set
                 if (currentDialogIconBase64 == null) {
                     // Try simplistic package detection
                     if (path.contains("Android/media/") || path.contains("Android/data/")) {
                         try {
                             val parts = path.split("/")
                             // Find the part that looks like a package name (com.xxx)
                             val pkg = parts.find { it.contains(".") && it.length > 5 }
                             if (pkg != null) {
                                 val icon = packageManager.getApplicationIcon(pkg)
                                 currentDialogIconBase64 = drawableToBase64(icon)
                                 if (smallIconType == null) smallIconType = pkg
                             }
                         } catch (e: Exception) {}
                     }
                 }

                val newRule = FileObserverRule(
                    id = existingRule?.id ?: UUID.randomUUID().toString(),
                    path = path,
                    types = types,
                    isRecursive = switchRecursive.isChecked,
                    sendToWatch = switchSendToWatch.isChecked,
                    name = name,
                    notificationTitle = etNotificationTitle.text.toString().takeIf { it.isNotBlank() },
                    smallIconType = smallIconType,
                    iconBase64 = currentDialogIconBase64
                )

                val json = prefs.getString("rules", "[]")
                val type = object : TypeToken<List<FileObserverRule>>() {}.type
                val list: MutableList<FileObserverRule> = gson.fromJson(json, type)
                
                if (existingRule != null) {
                    val index = list.indexOfFirst { it.id == existingRule.id }
                    if (index != -1) list[index] = newRule
                } else {
                    list.add(newRule)
                }
                
                saveRules(list)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showIconPickerDialog(onIconPicked: (String?, String?) -> Unit) {
        val apps = packageManager.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { it.loadLabel(packageManager).toString() }

        val items = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select App Icon")
            .setItems(items) { _, which ->
                val app = apps[which]
                try {
                    val icon = packageManager.getApplicationIcon(app.packageName)
                    val base64 = drawableToBase64(icon)
                    onIconPicked(base64, app.packageName)
                } catch (e: Exception) {
                    onIconPicked(null, null)
                }
            }
            .setPositiveButton("Pick from Storage") { dialog, _ ->
                onImagePicked = { base64 ->
                    onIconPicked(base64, "custom_image")
                }
                
                val intent = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    // Android 13+ Photo Picker
                    Intent("android.provider.action.PICK_IMAGES").apply {
                        type = "image/*"
                    }
                } else {
                    // SAF Fallback
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                }
                
                try {
                    imagePickerLauncher.launch(intent)
                } catch (e: Exception) {
                     // Fallback if PICK_IMAGES fails on some modified ROMs even with SDK check
                     val safIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                     }
                     try {
                        imagePickerLauncher.launch(safIntent)
                     } catch (e2: Exception) {
                        Toast.makeText(this, "No image picker available", Toast.LENGTH_SHORT).show()
                     }
                }
            }
            .setNeutralButton("Clear Icon") { _, _ -> onIconPicked(null, null) }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun drawableToBase64(drawable: android.graphics.drawable.Drawable): String? {
        try {
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    // Add getRules to internal adapter class needs reflection or just use the reload logic
    // I handled delete by reloading from prefs, which is safer.
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

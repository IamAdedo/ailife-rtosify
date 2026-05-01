package com.iamadedo.phoneapp

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.LruCache
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class AppPickerActivity : AppCompatActivity() {

    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppPickerAdapter
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editTextSearch: com.google.android.material.textfield.TextInputEditText

    private var allAppsList = listOf<PhoneAppItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        val rvHost = findViewById<View>(R.id.recyclerViewApps)
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, rvHost)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.dialog_install_option_extract)

        progressBar = findViewById(R.id.progressBar)
        editTextSearch = findViewById(R.id.editTextSearch)
        recyclerView = findViewById(R.id.recyclerViewApps)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = AppPickerAdapter { app -> extractAndReturn(app) }
        recyclerView.adapter = adapter

        setupSearch()
        loadApps()
    }

    private fun setupSearch() {
        editTextSearch.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    filterApps(s?.toString() ?: "")
                }
            }
        )
    }

    private var filterJob: Job? = null

    private fun filterApps(query: String) {
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            val filtered = withContext(Dispatchers.Default) {
                if (query.isEmpty()) {
                    allAppsList
                } else {
                    allAppsList.filter { app ->
                        app.label.contains(query, ignoreCase = true) ||
                                app.packageName.contains(query, ignoreCase = true)
                    }
                }
            }
            adapter.updateList(filtered)
        }
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            
            // MÉTODO OTIMIZADO: Busca apps com launcher em UMA chamada IPC
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            val launchableActivities = pm.queryIntentActivities(intent, 0)
            
            val filteredApps = launchableActivities.mapNotNull { resolveInfo ->
                val pkgName = resolveInfo.activityInfo.packageName
                if (pkgName == packageName) return@mapNotNull null
                
                val appInfo = resolveInfo.activityInfo.applicationInfo
                val label = resolveInfo.loadLabel(pm).toString()
                
                PhoneAppItem(
                    packageName = pkgName,
                    label = label,
                    sourceDir = appInfo.sourceDir
                )
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                allAppsList = filteredApps
                filterApps(editTextSearch.text?.toString() ?: "")
            }
        }
    }

    private fun extractAndReturn(app: PhoneAppItem) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d("AppPicker", "Extracting: ${app.label} from ${app.sourceDir}")
                val sourceFile = File(app.sourceDir)
                if (!sourceFile.exists()) {
                    throw Exception("Source file does not exist")
                }
                
                val tempFile = File(cacheDir, "${app.packageName}.apk")
                if (tempFile.exists()) tempFile.delete()
                
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val copied = input.copyTo(output)
                        android.util.Log.d("AppPicker", "Extracted $copied bytes to ${tempFile.absolutePath}")
                    }
                }

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    throw Exception("Extraction resulted in empty file")
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    val intent = Intent().apply {
                        putExtra("apk_path", tempFile.absolutePath)
                    }
                    setResult(RESULT_OK, intent)
                    finish()
                }
            } catch (e: Exception) {
                android.util.Log.e("AppPicker", "Extraction failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@AppPickerActivity, "${getString(R.string.apppicker_extract_error)}: ${e.message}", Toast.LENGTH_SHORT).show()
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

    data class PhoneAppItem(val packageName: String, val label: String, val sourceDir: String)

    class AppPickerAdapter(private val onClick: (PhoneAppItem) -> Unit) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {
        private var list = listOf<PhoneAppItem>()
        
        // Caches para evitar IPC repetitivo (ainda mantendo para os ícones)
        private val iconCache = LruCache<String, Drawable>(100)

        @SuppressLint("NotifyDataSetChanged")
        fun updateList(newList: List<PhoneAppItem>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_picker, parent, false)
            return ViewHolder(view, onClick, iconCache)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(list[position])
        }

        override fun getItemCount() = list.size

        class ViewHolder(
            itemView: View, 
            private val onClick: (PhoneAppItem) -> Unit,
            private val iconCache: LruCache<String, Drawable>
        ) : RecyclerView.ViewHolder(itemView) {
            private val imgIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
            private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
            private val tvPkg: TextView = itemView.findViewById(R.id.tvAppPackage)
            
            private var loadJob: Job? = null

            fun bind(item: PhoneAppItem) {
                tvPkg.text = item.packageName
                tvName.text = item.label
                
                itemView.setOnClickListener { onClick(item) }

                // Cancela anterior
                loadJob?.cancel()

                // Tenta cache
                val cachedIcon = iconCache.get(item.packageName)
                
                if (cachedIcon != null) {
                    imgIcon.setImageDrawable(cachedIcon)
                    return
                }

                imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)

                // Carrega ícone de forma assíncrona
                loadJob = (itemView.context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    val pm = itemView.context.packageManager
                    try {
                        val appInfo = pm.getApplicationInfo(item.packageName, 0)
                        val icon = appInfo.loadIcon(pm)

                        iconCache.put(item.packageName, icon)

                        withContext(Dispatchers.Main) {
                            imgIcon.setImageDrawable(icon)
                        }
                    } catch (e: Exception) {
                        // Icone padrão já setado
                    }
                }
            }
        }
    }
}

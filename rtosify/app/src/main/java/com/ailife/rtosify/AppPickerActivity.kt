package com.ailife.rtosify

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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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

    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppPickerAdapter
    private lateinit var toolbar: Toolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.dialog_install_option_extract)

        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.recyclerViewApps)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = AppPickerAdapter { app -> extractAndReturn(app) }
        recyclerView.adapter = adapter

        loadApps()
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
                PhoneAppItem(
                    packageName = pkgName,
                    sourceDir = appInfo.sourceDir
                )
            }.distinctBy { it.packageName }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                adapter.updateList(filteredApps)
            }
        }
    }

    private fun extractAndReturn(app: PhoneAppItem) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sourceFile = File(app.sourceDir)
                val tempFile = File(cacheDir, "${app.packageName}.apk")
                
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
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
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@AppPickerActivity, getString(R.string.apppicker_extract_error), Toast.LENGTH_SHORT).show()
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

    data class PhoneAppItem(val packageName: String, val sourceDir: String)

    class AppPickerAdapter(private val onClick: (PhoneAppItem) -> Unit) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {
        private var list = listOf<PhoneAppItem>()
        
        // Caches para evitar IPC repetitivo
        private val labelCache = LruCache<String, String>(200)
        private val iconCache = LruCache<String, Drawable>(100)

        @SuppressLint("NotifyDataSetChanged")
        fun updateList(newList: List<PhoneAppItem>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_picker, parent, false)
            return ViewHolder(view, onClick, labelCache, iconCache)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(list[position])
        }

        override fun getItemCount() = list.size

        class ViewHolder(
            itemView: View, 
            private val onClick: (PhoneAppItem) -> Unit,
            private val labelCache: LruCache<String, String>,
            private val iconCache: LruCache<String, Drawable>
        ) : RecyclerView.ViewHolder(itemView) {
            private val imgIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
            private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
            private val tvPkg: TextView = itemView.findViewById(R.id.tvAppPackage)
            
            private var loadJob: Job? = null

            fun bind(item: PhoneAppItem) {
                tvPkg.text = item.packageName
                
                itemView.setOnClickListener { onClick(item) }

                // Cancela anterior
                loadJob?.cancel()

                // Tenta cache
                val cachedLabel = labelCache.get(item.packageName)
                val cachedIcon = iconCache.get(item.packageName)
                
                if (cachedLabel != null && cachedIcon != null) {
                    tvName.text = cachedLabel
                    imgIcon.setImageDrawable(cachedIcon)
                    return
                }

                imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                tvName.text = "..."

                // Carrega nome e ícone de forma assíncrona
                loadJob = (itemView.context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    val pm = itemView.context.packageManager
                    try {
                        val appInfo = pm.getApplicationInfo(item.packageName, 0)
                        val name = appInfo.loadLabel(pm).toString()
                        val icon = appInfo.loadIcon(pm)

                        labelCache.put(item.packageName, name)
                        iconCache.put(item.packageName, icon)

                        withContext(Dispatchers.Main) {
                            tvName.text = name
                            imgIcon.setImageDrawable(icon)
                        }
                    } catch (e: Exception) {
                        // Keep package name as fallback
                        withContext(Dispatchers.Main) {
                            tvName.text = item.packageName
                        }
                    }
                }
            }
        }
    }
}

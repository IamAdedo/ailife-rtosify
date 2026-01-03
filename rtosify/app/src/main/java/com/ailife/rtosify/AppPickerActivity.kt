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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        // Set loading message if there was a textview, but activity_app_picker only has progressbar
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val filteredApps = apps.filter { app ->
                // Filter user apps or apps with launch intent
                (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(app.packageName) != null
            }.map { app ->
                PhoneAppItem(
                    name = app.loadLabel(pm).toString(),
                    packageName = app.packageName,
                    icon = app.loadIcon(pm),
                    sourceDir = app.sourceDir
                )
            }.sortedBy { it.name.lowercase() }

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

    data class PhoneAppItem(val name: String, val packageName: String, val icon: Drawable, val sourceDir: String)

    class AppPickerAdapter(private val onClick: (PhoneAppItem) -> Unit) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {
        private var list = listOf<PhoneAppItem>()

        @SuppressLint("NotifyDataSetChanged")
        fun updateList(newList: List<PhoneAppItem>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_picker, parent, false)
            return ViewHolder(view, onClick)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(list[position])
        }

        override fun getItemCount() = list.size

        class ViewHolder(itemView: View, private val onClick: (PhoneAppItem) -> Unit) : RecyclerView.ViewHolder(itemView) {
            private val imgIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
            private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
            private val tvPkg: TextView = itemView.findViewById(R.id.tvAppPackage)

            fun bind(item: PhoneAppItem) {
                tvName.text = item.name
                tvPkg.text = item.packageName
                imgIcon.setImageDrawable(item.icon)
                itemView.setOnClickListener { onClick(item) }
            }
        }
    }
}

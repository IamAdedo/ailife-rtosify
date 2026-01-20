package com.ailife.rtosify

import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppNotificationItem(
    val appName: String,
    val packageName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean = false
)

class AppNotificationAdapter(private val prefs: SharedPreferences) : RecyclerView.Adapter<AppNotificationAdapter.ViewHolder>() {

    private var items = listOf<AppNotificationItem>()
    private val allowedPackages = mutableSetOf<String>()

    private val labelCache = LruCache<String, String>(200)
    private val iconCache = LruCache<String, Drawable>(100)

    init {
        reloadAllowedPackages()
    }

    fun reloadAllowedPackages() {
        val savedSet = prefs.getStringSet("allowed_notif_packages", null)
        allowedPackages.clear()
        if (savedSet != null) {
            allowedPackages.addAll(savedSet)
        }
    }

    fun setData(newItems: List<AppNotificationItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val layoutAppInfo: View = itemView.findViewById(R.id.layoutAppInfo)
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvAppPackage: TextView = itemView.findViewById(R.id.tvAppPackage)
        private val switchApp: SwitchMaterial = itemView.findViewById(R.id.switchAppNotification)

        private var loadJob: Job? = null

        fun bind(item: AppNotificationItem) {
            tvAppPackage.text = item.packageName
            switchApp.setOnCheckedChangeListener(null)
            switchApp.isChecked = allowedPackages.contains(item.packageName)

            switchApp.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    allowedPackages.add(item.packageName)
                } else {
                    allowedPackages.remove(item.packageName)
                }
                prefs.edit().putStringSet("allowed_notif_packages", allowedPackages.toSet()).apply()
            }
            
            // Handle Clicks on Left Area
            layoutAppInfo.setOnClickListener {
                val context = itemView.context
                val intent = android.content.Intent(context, AppNotificationSettingsActivity::class.java).apply {
                    putExtra("EXTRA_PACKAGE_NAME", item.packageName)
                    putExtra("EXTRA_APP_NAME", tvName.text.toString())
                }
                context.startActivity(intent)
            }

            loadJob?.cancel()

            if (item.appName.isNotEmpty()) {
                tvName.text = item.appName
            } else {
                val cachedLabel = labelCache.get(item.packageName)
                if (cachedLabel != null) {
                    tvName.text = cachedLabel
                } else {
                    tvName.text = item.packageName
                }
            }

            val cachedIcon = iconCache.get(item.packageName)
            if (cachedIcon != null) {
                imgIcon.setImageDrawable(cachedIcon)
                return
            }

            imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)

            loadJob = (itemView.context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                val pm = itemView.context.packageManager
                try {
                    val appInfo = pm.getApplicationInfo(item.packageName, 0)
                    val icon = pm.getApplicationIcon(appInfo)

                    iconCache.put(item.packageName, icon)

                    withContext(Dispatchers.Main) {
                        imgIcon.setImageDrawable(icon)
                    }
                } catch (e: Exception) {
                    // Icon loading failed, keep default icon
                }
            }
        }
    }
}

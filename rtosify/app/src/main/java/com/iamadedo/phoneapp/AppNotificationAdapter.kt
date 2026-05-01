package com.iamadedo.phoneapp

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
import com.google.android.material.materialswitch.MaterialSwitch
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

class AppNotificationAdapter(private val prefs: SharedPreferences) : androidx.recyclerview.widget.ListAdapter<AppNotificationItem, AppNotificationAdapter.ViewHolder>(DiffCallback()) {

    private val allowedPackages = mutableSetOf<String>()
    // Reducing cache size to prevent memory pressure which might cause GC pauses (ANR-like symptoms)
    private val iconCache = LruCache<String, Drawable>(50)

    init {
        reloadAllowedPackages()
    }
    
    // We override submitList to handle our custom logic if needed, but standard usage is fine.
    // However, we need to Expose a setData method for compatibility with existing activity code
    fun setData(newItems: List<AppNotificationItem>) {
        submitList(newItems)
    }

    fun reloadAllowedPackages() {
        val savedSet = prefs.getStringSet("allowed_notif_packages", null)
        allowedPackages.clear()
        if (savedSet != null) {
            allowedPackages.addAll(savedSet)
        }
        notifyItemRangeChanged(0, itemCount, "PAYLOAD_SWITCH_UPDATE")
    }

    class DiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<AppNotificationItem>() {
        override fun areItemsTheSame(oldItem: AppNotificationItem, newItem: AppNotificationItem): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: AppNotificationItem, newItem: AppNotificationItem): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    // Efficient partial update support
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            holder.updateSwitchState(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val layoutAppInfo: View = itemView.findViewById(R.id.layoutAppInfo)
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvAppPackage: TextView = itemView.findViewById(R.id.tvAppPackage)
        private val switchApp: MaterialSwitch = itemView.findViewById(R.id.switchAppNotification)

        private var loadJob: Job? = null

        fun updateSwitchState(item: AppNotificationItem) {
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
        }

        fun bind(item: AppNotificationItem) {
            tvName.text = item.appName
            tvAppPackage.text = item.packageName
            
            updateSwitchState(item)
            
            // Handle Clicks on Left Area
            layoutAppInfo.setOnClickListener {
                val context = itemView.context
                val intent = android.content.Intent(context, AppNotificationSettingsActivity::class.java).apply {
                    putExtra("EXTRA_PACKAGE_NAME", item.packageName)
                    putExtra("EXTRA_APP_NAME", item.appName)
                }
                context.startActivity(intent)
            }

            // Icon Loading Logic
            loadJob?.cancel()
            
            val cachedIcon = iconCache.get(item.packageName)
            if (cachedIcon != null) {
                imgIcon.setImageDrawable(cachedIcon)
                return
            }

            imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)

            loadJob = (itemView.context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                // Optimization: calling getApplicationIcon(packageName) directly instead of getApplicationInfo first
                try {
                    val pm = itemView.context.packageManager
                    val icon = pm.getApplicationIcon(item.packageName)
                    
                    if (icon != null) {
                        iconCache.put(item.packageName, icon)
                        withContext(Dispatchers.Main) {
                            if (adapterPosition != RecyclerView.NO_POSITION && getItem(adapterPosition).packageName == item.packageName) {
                                imgIcon.setImageDrawable(icon)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // keep default
                }
            }
        }
    }
}

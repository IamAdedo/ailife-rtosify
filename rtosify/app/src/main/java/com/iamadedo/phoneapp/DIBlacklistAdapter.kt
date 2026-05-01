package com.iamadedo.phoneapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.checkbox.MaterialCheckBox
import android.widget.ImageView
import android.widget.TextView
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.recyclerview.widget.RecyclerView

class DIBlacklistAdapter(
    var apps: List<AppInfo>,
    private val blacklistedPackages: MutableSet<String>,
    private val onBlacklistChanged: (String, Boolean) -> Unit
) : RecyclerView.Adapter<DIBlacklistAdapter.ViewHolder>() {

    fun updateList(newApps: List<AppInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }

    fun reloadBlacklistedPackages(newBlacklist: Set<String>) {
        blacklistedPackages.clear()
        blacklistedPackages.addAll(newBlacklist)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_di_blacklist_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.bind(app)
    }

    override fun getItemCount() = apps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appName: TextView = itemView.findViewById(R.id.tvAppName)
        private val packageName: TextView = itemView.findViewById(R.id.tvPackageName)
        private val appIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val checkbox: MaterialCheckBox = itemView.findViewById(R.id.checkboxBlacklist)

        fun bind(app: AppInfo) {
            appName.text = app.name
            packageName.text = app.packageName
            
            // Bind icon if available (Base64)
            if (!app.icon.isNullOrEmpty()) {
                try {
                    val decodedBytes = Base64.decode(app.icon, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    appIcon.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    appIcon.setImageResource(R.mipmap.ic_launcher)
                }
            } else {
                appIcon.setImageResource(R.mipmap.ic_launcher)
            }
            
            // Remove listener before updating state to prevent unwanted triggers during recycling
            checkbox.setOnCheckedChangeListener(null)
            
            val isBlacklisted = blacklistedPackages.contains(app.packageName)
            checkbox.isChecked = isBlacklisted
            
            // System app indicator
            if (app.isSystemApp) {
                appName.alpha = 0.5f
                packageName.alpha = 0.5f
                appIcon.alpha = 0.5f
            } else {
                appName.alpha = 1.0f
                packageName.alpha = 1.0f
                appIcon.alpha = 1.0f
            }
            
            // Set listener after updating state
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    blacklistedPackages.add(app.packageName)
                } else {
                    blacklistedPackages.remove(app.packageName)
                }
                onBlacklistChanged(app.packageName, isChecked)
            }
            
            itemView.setOnClickListener {
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }
}

package com.ailife.rtosify

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DIBlacklistAdapter(
    private var apps: List<DIBlacklistActivity.AppInfo>,
    private val blacklistedPackages: MutableSet<String>,
    private val onBlacklistChanged: (String, Boolean) -> Unit
) : RecyclerView.Adapter<DIBlacklistAdapter.ViewHolder>() {

    fun updateList(newApps: List<DIBlacklistActivity.AppInfo>) {
        apps = newApps
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
        private val checkbox: CheckBox = itemView.findViewById(R.id.checkboxBlacklist)

        fun bind(app: DIBlacklistActivity.AppInfo) {
            appName.text = app.name
            packageName.text = app.packageName
            
            val isBlacklisted = blacklistedPackages.contains(app.packageName)
            checkbox.isChecked = isBlacklisted
            
            // System app indicator
            if (app.isSystemApp) {
                appName.alpha = 0.7f
                packageName.alpha = 0.7f
            } else {
                appName.alpha = 1.0f
                packageName.alpha = 1.0f
            }
            
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

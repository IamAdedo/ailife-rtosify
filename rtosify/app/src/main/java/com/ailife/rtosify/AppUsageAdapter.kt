package com.ailife.rtosify

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.graphics.BitmapFactory
import android.util.Base64

class AppUsageAdapter : RecyclerView.Adapter<AppUsageAdapter.ViewHolder>() {

    private val items = mutableListOf<AppUsageData>()
    private var maxUsage = 0L

    fun updateData(newItems: List<AppUsageData>) {
        items.clear()
        items.addAll(newItems)
        maxUsage = newItems.maxOfOrNull { it.usageTimeMillis } ?: 0L
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_usage, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], maxUsage)
    }

    override fun getItemCount() = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgAppIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
        private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        private val pbUsage: ProgressBar = itemView.findViewById(R.id.pbUsage)
        private val tvUsageDetail: TextView = itemView.findViewById(R.id.tvUsageDetail)

        fun bind(item: AppUsageData, maxUsage: Long) {
            tvAppName.text = item.name ?: item.packageName
            
            val minutes = item.usageTimeMillis / 1000 / 60
            if (minutes < 1) {
                 tvUsageDetail.text = "< 1 min"
            } else {
                 tvUsageDetail.text = "$minutes min"
            }
            
            if (maxUsage > 0) {
                pbUsage.max = 100
                pbUsage.progress = ((item.usageTimeMillis.toFloat() / maxUsage) * 100).toInt()
            } else {
                pbUsage.progress = 0
            }
            
            if (item.icon != null) {
                try {
                    val decodedString = Base64.decode(item.icon, Base64.DEFAULT)
                    val decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                    imgAppIcon.setImageBitmap(decodedByte)
                } catch (e: Exception) {
                    imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            } else {
                 imgAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        }
    }
}

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

    enum class SortOrder {
        TIME, POWER, SPEED
    }

    private val items = mutableListOf<AppUsageData>()
    private var maxUsage = 0.0
    private var currentSortOrder = SortOrder.TIME

    fun updateData(newItems: List<AppUsageData>, sortOrder: SortOrder = currentSortOrder) {
        currentSortOrder = sortOrder
        items.clear()
        val sorted = when (sortOrder) {
            SortOrder.TIME -> newItems.sortedByDescending { it.usageTimeMillis }
            SortOrder.POWER -> newItems.sortedByDescending { it.batteryPowerMah ?: 0.0 }
            SortOrder.SPEED -> newItems.sortedByDescending { it.drainSpeed ?: 0.0 }
        }
        items.addAll(sorted)
        maxUsage = when (sortOrder) {
            SortOrder.TIME -> items.maxOfOrNull { it.usageTimeMillis }?.toDouble() ?: 0.0
            SortOrder.POWER -> items.maxOfOrNull { it.batteryPowerMah ?: 0.0 } ?: 0.0
            SortOrder.SPEED -> items.maxOfOrNull { it.drainSpeed ?: 0.0 } ?: 0.0
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_usage, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], maxUsage, currentSortOrder)
    }

    override fun getItemCount() = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgAppIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
        private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        private val pbUsage: ProgressBar = itemView.findViewById(R.id.pbUsage)
        private val tvUsageDetail: TextView = itemView.findViewById(R.id.tvUsageDetail)

        fun bind(item: AppUsageData, maxUsage: Double, sortOrder: SortOrder) {
            tvAppName.text = item.name ?: item.packageName
            
            val detailText = when (sortOrder) {
                SortOrder.TIME -> {
                    val minutes = item.usageTimeMillis / 1000 / 60
                    if (minutes < 1) "< 1 min" else "$minutes min"
                }
                SortOrder.POWER -> {
                    val raw = item.batteryPowerMah ?: 0.0
                    if (raw > 0 && raw < 0.1) String.format("%.3f mAh", raw)
                    else String.format("%.1f mAh", raw)
                }
                SortOrder.SPEED -> {
                    val raw = item.drainSpeed ?: 0.0
                    // If < 1 mA, show more precision (e.g. 0.567 mA)
                    if (raw < 1.0) String.format("%.3f mA", raw)
                    else String.format("%.1f mA", raw)
                }
            }
            tvUsageDetail.text = detailText
            
            if (maxUsage > 0) {
                pbUsage.max = 100
                val currentValue = when (sortOrder) {
                    SortOrder.TIME -> item.usageTimeMillis.toDouble()
                    SortOrder.POWER -> item.batteryPowerMah ?: 0.0
                    SortOrder.SPEED -> item.drainSpeed ?: 0.0
                }
                pbUsage.progress = ((currentValue / maxUsage) * 100).toInt()
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

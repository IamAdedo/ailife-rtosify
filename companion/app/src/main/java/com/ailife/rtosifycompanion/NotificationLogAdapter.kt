package com.ailife.rtosifycompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.TextViewCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationLogAdapter(private var logs: List<NotificationLogEntry>) :
    RecyclerView.Adapter<NotificationLogAdapter.LogViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val expandedKeys = mutableSetOf<String>()

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgAppIcon: ImageView = view.findViewById(R.id.imgAppIcon)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvText: TextView = view.findViewById(R.id.tvText)
        
        // Expanded views
        val layoutExpanded: View = view.findViewById(R.id.layoutExpanded)
        val tvFullText: TextView = view.findViewById(R.id.tvFullText)
        val imgBigPicture: ImageView = view.findViewById(R.id.imgBigPicture)
        val layoutHistory: LinearLayout = view.findViewById(R.id.layoutHistory)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val entry = logs[position]
        val log = entry.notification
        holder.tvAppName.text = log.appName ?: log.packageName
        holder.tvTitle.text = log.title
        holder.tvText.text = log.text
        
        holder.tvTime.text = timeFormat.format(Date(entry.timestamp))

        // App Icon
        val iconBase64 = log.smallIcon ?: log.largeIcon
        setIcon(holder.imgAppIcon, iconBase64)

        // Expansion logic
        val isExpanded = expandedKeys.contains(log.key)
        holder.layoutExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.tvText.visibility = if (isExpanded) View.GONE else View.VISIBLE

        if (isExpanded) {
            holder.tvFullText.text = log.text
            
            // Big Picture
            if (log.bigPicture != null) {
                holder.imgBigPicture.visibility = View.VISIBLE
                setIcon(holder.imgBigPicture, log.bigPicture)
            } else {
                holder.imgBigPicture.visibility = View.GONE
            }

            // History
            if (log.messages.isNotEmpty()) {
                holder.layoutHistory.visibility = View.VISIBLE
                holder.layoutHistory.removeAllViews()
                log.messages.forEach { msg ->
                    val textView = TextView(holder.itemView.context).apply {
                        text = "${msg.senderName ?: ""}: ${msg.text}"
                        setPadding(0, 4, 0, 4)
                        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    }
                    holder.layoutHistory.addView(textView)
                }
            } else {
                holder.layoutHistory.visibility = View.GONE
            }
        }

        holder.itemView.setOnClickListener {
            if (expandedKeys.contains(log.key)) {
                expandedKeys.remove(log.key)
            } else {
                expandedKeys.add(log.key)
            }
            notifyItemChanged(position)
        }
    }

    private fun setIcon(imageView: ImageView, base64: String?) {
        if (base64 != null) {
            try {
                val decodedString = Base64.decode(base64, Base64.DEFAULT)
                val decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                imageView.setImageBitmap(decodedByte)
            } catch (e: Exception) {
                imageView.setImageResource(android.R.drawable.ic_dialog_info)
            }
        } else {
            imageView.setImageResource(android.R.drawable.ic_dialog_info)
        }
    }

    override fun getItemCount(): Int = logs.size

    fun updateLogs(newLogs: List<NotificationLogEntry>) {
        logs = newLogs
        notifyDataSetChanged()
    }
}

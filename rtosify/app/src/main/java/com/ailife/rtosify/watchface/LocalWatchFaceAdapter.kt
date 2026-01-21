package com.ailife.rtosify.watchface

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ailife.rtosify.R
import java.io.File

class LocalWatchFaceAdapter(
    private var list: List<File>,
    private val onSendToWatch: (File) -> Unit
) : RecyclerView.Adapter<LocalWatchFaceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val btnSend: Button = view.findViewById(R.id.btnApply)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_watch_face_local, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = list[position]
        holder.tvFileName.text = file.name
        holder.btnSend.text = holder.itemView.context.getString(R.string.btn_send_to_watch)
        holder.btnSend.setOnClickListener { onSendToWatch(file) }
    }

    override fun getItemCount() = list.size

    fun updateList(newList: List<File>) {
        list = newList
        notifyDataSetChanged()
    }
}

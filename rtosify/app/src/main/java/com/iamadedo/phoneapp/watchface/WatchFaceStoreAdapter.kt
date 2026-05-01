package com.iamadedo.phoneapp.watchface

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.iamadedo.phoneapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import java.net.URL

class WatchFaceStoreAdapter(
    private val list: MutableList<WatchFace>,
    private val onDownload: (WatchFace) -> Unit
) : RecyclerView.Adapter<WatchFaceStoreAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgPreview: ImageView = view.findViewById(R.id.imgPreview)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val btnDownload: MaterialButton = view.findViewById(R.id.btnDownload)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_watch_face_store, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        holder.tvTitle.text = item.title
        holder.btnDownload.setOnClickListener { onDownload(item) }
        
        // Simple async image loading with tag check to avoid mismatches during recycling
        holder.imgPreview.setImageResource(android.R.color.darker_gray)
        holder.imgPreview.tag = item.previewUrl
        
        item.previewUrl?.let { url ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bitmap = BitmapFactory.decodeStream(URL(url).openStream())
                    withContext(Dispatchers.Main) {
                        if (holder.imgPreview.tag == url) {
                            holder.imgPreview.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun getItemCount() = list.size

    fun setList(newList: List<WatchFace>) {
        list.clear()
        list.addAll(newList)
        notifyDataSetChanged()
    }

    fun appendList(newList: List<WatchFace>) {
        val start = list.size
        list.addAll(newList)
        notifyItemRangeInserted(start, newList.size)
    }
}

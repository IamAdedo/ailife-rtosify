package com.ailife.rtosify.watchface

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ailife.rtosify.R

sealed class ManagerItem {
    data class Header(val name: String, var isExpanded: Boolean = true) : ManagerItem()
    data class Face(val fileInfo: WatchFaceFileInfo, val folderName: String) : ManagerItem()
}

class WatchFaceManagerAdapter(
    private var allItems: List<ManagerItem>,
    private val isLocal: Boolean,
    private val onRequestPreview: (String) -> Unit,
    private val onAction: (Action, ManagerItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class Action { SET, RENAME, DELETE, TOGGLE_FOLDER, RENAME_FOLDER, DELETE_FOLDER }

    private var displayedItems = mutableListOf<ManagerItem>()
    private val previewCache = mutableMapOf<String, android.graphics.Bitmap>()

    init {
        updateDisplayedItems()
    }

    private fun updateDisplayedItems() {
        val newList = mutableListOf<ManagerItem>()
        var currentFolderExpanded = true
        for (item in allItems) {
            if (item is ManagerItem.Header) {
                newList.add(item)
                currentFolderExpanded = item.isExpanded
            } else if (currentFolderExpanded) {
                newList.add(item)
            }
        }
        displayedItems = newList
        notifyDataSetChanged()
    }

    fun setData(newData: List<ManagerItem>) {
        allItems = newData
        updateDisplayedItems()
    }

    fun setPreview(path: String, bitmap: android.graphics.Bitmap?) {
        if (bitmap != null) {
            previewCache[path] = bitmap
            notifyDataSetChanged() // Ideally, notify specific item changed
        }
    }

    fun getAllItems(): List<ManagerItem> = allItems

    fun getItemAt(position: Int): ManagerItem? = displayedItems.getOrNull(position)

    override fun getItemViewType(position: Int): Int = if (displayedItems[position] is ManagerItem.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            HeaderViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_watch_face_header, parent, false))
        } else {
            FaceViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_watch_face_manager, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = displayedItems[position]
        if (holder is HeaderViewHolder && item is ManagerItem.Header) {
            holder.bind(item)
        } else if (holder is FaceViewHolder && item is ManagerItem.Face) {
            holder.bind(item)
        }
    }

    override fun getItemCount(): Int = displayedItems.size

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName = view.findViewById<TextView>(R.id.tvFolderName)
        val imgExpand = view.findViewById<ImageView>(R.id.imgExpand)
        val btnRename = view.findViewById<ImageButton>(R.id.btnRenameFolder)
        val btnDelete = view.findViewById<ImageButton>(R.id.btnDeleteFolder)

        fun bind(item: ManagerItem.Header) {
            tvName.text = item.name
            imgExpand.rotation = if (item.isExpanded) 0f else -90f
            
            // Allow checking if rename/delete allowed for folders (remote only)
            btnRename.visibility = if (!isLocal && item.name != "<root>") View.VISIBLE else View.GONE
            btnDelete.visibility = if (!isLocal && item.name != "<root>") View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                item.isExpanded = !item.isExpanded
                updateDisplayedItems()
                onAction(Action.TOGGLE_FOLDER, item)
            }
            btnRename.setOnClickListener { onAction(Action.RENAME_FOLDER, item) }
            btnDelete.setOnClickListener { onAction(Action.DELETE_FOLDER, item) }
        }
    }

    inner class FaceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgPreview = view.findViewById<ImageView>(R.id.imgPreview)
        val tvName = view.findViewById<TextView>(R.id.tvFileName)
        val btnSet = view.findViewById<ImageButton>(R.id.btnSet)
        val btnRename = view.findViewById<ImageButton>(R.id.btnRename)
        val btnDelete = view.findViewById<ImageButton>(R.id.btnDelete)

        fun bind(item: ManagerItem.Face) {
            tvName.text = item.fileInfo.name
            
            val cached = previewCache[item.fileInfo.path]
            if (cached != null) {
                imgPreview.setImageBitmap(cached)
            } else {
                imgPreview.setImageResource(android.R.color.darker_gray)
                if (isLocal) {
                    loadLocalPreview(item.fileInfo.path)
                } else {
                    onRequestPreview(item.fileInfo.path)
                }
            }

            btnSet.setOnClickListener { onAction(Action.SET, item) }
            btnRename.setOnClickListener { onAction(Action.RENAME, item) }
            btnDelete.setOnClickListener { onAction(Action.DELETE, item) }
        }
        
        private fun loadLocalPreview(path: String) {
            // Simple async loading (not robust for large lists but sufficient for demo)
            Thread {
                try {
                    val file = java.io.File(path)
                    var bitmap: android.graphics.Bitmap? = null
                    if (file.name.endsWith(".zip", true) || file.name.endsWith(".watch", true)) {
                        java.util.zip.ZipFile(file).use { zip ->
                            val candidates = listOf("preview.png", "img_gear_0.png", "preview.jpg")
                            for (c in candidates) {
                                val entry = zip.entries().asSequence().find { it.name.endsWith(c, true) && !it.isDirectory }
                                if (entry != null) {
                                    zip.getInputStream(entry).use { input ->
                                        bitmap = android.graphics.BitmapFactory.decodeStream(input)
                                    }
                                    break
                                }
                            }
                        }
                    }
                    if (bitmap != null) {
                        imgPreview.post {
                            if (adapterPosition != RecyclerView.NO_POSITION) { // Check validity
                                previewCache[path] = bitmap!!
                                notifyItemChanged(adapterPosition)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }
    }
}

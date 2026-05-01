package com.iamadedo.phoneapp.watchface

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.iamadedo.phoneapp.R

sealed class ManagerItem {
    data class Header(val name: String, var isExpanded: Boolean = true) : ManagerItem()
    data class Face(val fileInfo: WatchFaceFileInfo, val folderName: String, var isSelected: Boolean = false) : ManagerItem()
}

class WatchFaceManagerAdapter(
    private var allItems: List<ManagerItem>,
    private val isLocal: Boolean,
    private val onRequestPreview: (String) -> Unit,
    private val onCancelPreview: (String) -> Unit, // New callback
    private val onAction: (Action, ManagerItem) -> Unit,
    private val onSelectionChanged: (Boolean, Int) -> Unit // isSelectionMode, selectedCount
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class Action { SET, RENAME, DELETE, TOGGLE_FOLDER, RENAME_FOLDER, DELETE_FOLDER, SELECTION_MODE }

    private var displayedItems = mutableListOf<ManagerItem>()
    private val previewCache = mutableMapOf<String, android.graphics.Bitmap>()
    var isInSelectionMode = false
        private set

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
            // Find the index of the item with this path
            val index = displayedItems.indexOfFirst { it is ManagerItem.Face && it.fileInfo.path == path }
            if (index != -1) {
                notifyItemChanged(index)
            }
        }
    }

    fun getAllItems(): List<ManagerItem> = allItems

    fun clearSelection() {
        isInSelectionMode = false
        allItems.filterIsInstance<ManagerItem.Face>().forEach { it.isSelected = false }
        displayedItems.filterIsInstance<ManagerItem.Face>().forEach { it.isSelected = false }
        notifyDataSetChanged()
        onSelectionChanged(false, 0)
    }

    fun getSelectedItems(): List<ManagerItem.Face> {
        return allItems.filterIsInstance<ManagerItem.Face>().filter { it.isSelected }
    }

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

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is FaceViewHolder) {
            holder.currentPath?.let { path ->
                if (!isLocal) {
                    onCancelPreview(path)
                }
            }
        }
    }

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
        var currentPath: String? = null

        fun bind(item: ManagerItem.Face) {
            currentPath = item.fileInfo.path
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

            // Selection Logic
            if (isInSelectionMode) {
                btnSet.visibility = View.GONE
                btnRename.visibility = View.GONE
                btnDelete.visibility = View.GONE
                
                // Show selection indicator (checking background or overlay)
                if (item.isSelected) {
                    itemView.setBackgroundColor(0x8000FF00.toInt()) // Semi-transparent green
                } else {
                    itemView.setBackgroundColor(0x00000000) // Transparent
                }
            } else {
                btnSet.visibility = View.VISIBLE
                btnRename.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE
                itemView.setBackgroundColor(0x00000000)
            }

            itemView.setOnLongClickListener {
                if (!isInSelectionMode) {
                    enterSelectionMode(item)
                    true
                } else {
                    false
                }
            }
            
            itemView.setOnClickListener {
                if (isInSelectionMode) {
                    toggleSelection(item)
                } else {
                    // Maybe show preview dialog or immediate apply?
                    // For now keeping button actions as primary
                }
            }
        }
        
        private fun enterSelectionMode(initialItem: ManagerItem.Face) {
            isInSelectionMode = true
            initialItem.isSelected = true
            onSelectionChanged(true, 1)
            notifyDataSetChanged()
        }

        private fun toggleSelection(item: ManagerItem.Face) {
            item.isSelected = !item.isSelected
            val wasInSelectionMode = isInSelectionMode
            val count = displayedItems.count { it is ManagerItem.Face && it.isSelected }
            
            if (count == 0) {
                isInSelectionMode = false
                onSelectionChanged(false, 0)
            } else {
                onSelectionChanged(true, count) // Updates UI count
            }
            
            // If we switched modes, we must refresh ALL items to show/hide buttons
            if (wasInSelectionMode != isInSelectionMode) {
                 notifyDataSetChanged()
            } else {
                 notifyItemChanged(adapterPosition)
            }
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

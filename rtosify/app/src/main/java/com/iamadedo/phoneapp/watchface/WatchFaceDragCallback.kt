package com.iamadedo.phoneapp.watchface

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class WatchFaceDragCallback(
    private val onMove: (fromPath: String, toFolder: String) -> Unit
) : ItemTouchHelper.Callback() {

    private var pendingMove: Pair<String, String>? = null

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        // Only allow dragging for face items, not headers
        val dragFlags = if (viewHolder is WatchFaceManagerAdapter.FaceViewHolder) {
            ItemTouchHelper.UP or ItemTouchHelper.DOWN
        } else {
            0
        }
        return makeMovementFlags(dragFlags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        // Get the adapter
        val adapter = recyclerView.adapter as? WatchFaceManagerAdapter ?: return false
        
        val fromPosition = viewHolder.adapterPosition
        val toPosition = target.adapterPosition
        
        // Get the items
        val fromItem = adapter.getItemAt(fromPosition) as? ManagerItem.Face ?: return false
        val toItem = adapter.getItemAt(toPosition)
        
        // Determine target folder
        val targetFolder = when (toItem) {
            is ManagerItem.Header -> toItem.name
            is ManagerItem.Face -> toItem.folderName
            else -> return false
        }
        
        // Only allow moving to different folders
        if (fromItem.folderName != targetFolder) {
            // Update pending move but don't execute yet
            pendingMove = fromItem.fileInfo.path to targetFolder
            return true
        }
        
        pendingMove = null
        return false
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // Not used
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        // Execute the move if one is pending
        pendingMove?.let { (fromPath, toFolder) ->
            onMove(fromPath, toFolder)
        }
        pendingMove = null
    }

    override fun isLongPressDragEnabled(): Boolean = true
}

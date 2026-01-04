package com.ailife.rtosify.watchface

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.rtosify.R
import org.json.JSONArray

class WatchFaceManagerFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WatchFaceManagerAdapter
    private val watchPath = "Android/data/com.ailife.ClockSkinCoco/files/ClockSkin"
    private val folderContents = mutableMapOf<String, List<WatchFaceFileInfo>>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_watch_face_local, container, false)
        view.findViewById<View>(R.id.btnImport).visibility = View.GONE
        
        recyclerView = view.findViewById(R.id.recyclerView)
        val layoutManager = GridLayoutManager(context, 2)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == 0) 2 else 1
            }
        }
        recyclerView.layoutManager = layoutManager
        
        adapter = WatchFaceManagerAdapter(emptyList()) { action, item ->
            handleAction(action, item)
        }
        recyclerView.adapter = adapter
        
        refresh()
        
        return view
    }

    fun refresh() {
        folderContents.clear()
        (activity as? WatchFaceActivity)?.requestWatchFileList(watchPath)
    }

    fun updateList(filesJson: String) {
        try {
            val items = mutableListOf<ManagerItem>()
            val array = JSONArray(filesJson)
            
            val rootFiles = mutableListOf<WatchFaceFileInfo>()
            val folders = mutableListOf<WatchFaceFileInfo>()
            
            // Parse the file list
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val isDir = obj.optBoolean("isDirectory", false)
                val size = obj.optLong("size", 0)
                
                val fileInfo = WatchFaceFileInfo(name, "$watchPath/$name", isDir, size)
                
                if (isDir) {
                    folders.add(fileInfo)
                } else if (name.lowercase().endsWith(".zip") || name.lowercase().endsWith(".watch")) {
                    rootFiles.add(fileInfo)
                }
            }
            
            // Add root files
            if (rootFiles.isNotEmpty()) {
                items.add(ManagerItem.Header("<root>", true))
                items.addAll(rootFiles.map { ManagerItem.Face(it, "<root>") })
            }
            
            // Add folders and request their contents
            folders.forEach { folder ->
                items.add(ManagerItem.Header(folder.name, false))
                // Request folder contents
                (activity as? WatchFaceActivity)?.requestWatchFileList(folder.path)
            }
            
            activity?.runOnUiThread {
                adapter.setData(items)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateFolderContents(folderPath: String, filesJson: String) {
        try {
            val files = mutableListOf<WatchFaceFileInfo>()
            val array = JSONArray(filesJson)
            
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val isDir = obj.optBoolean("isDirectory", false)
                val size = obj.optLong("size", 0)
                
                if (!isDir && (name.lowercase().endsWith(".zip") || name.lowercase().endsWith(".watch"))) {
                    files.add(WatchFaceFileInfo(name, "$folderPath/$name", false, size))
                }
            }
            
            folderContents[folderPath] = files
            rebuildList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun rebuildList() {
        // Rebuild the entire list with folder contents
        val currentData = adapter.getAllItems()
        val newItems = mutableListOf<ManagerItem>()
        
        for (item in currentData) {
            newItems.add(item)
            if (item is ManagerItem.Header && item.name != "<root>" && item.isExpanded) {
                val folderPath = "$watchPath/${item.name}"
                folderContents[folderPath]?.forEach { fileInfo ->
                    newItems.add(ManagerItem.Face(fileInfo, item.name))
                }
            }
        }
        
        activity?.runOnUiThread {
            adapter.setData(newItems)
        }
    }

    private fun handleAction(action: WatchFaceManagerAdapter.Action, item: ManagerItem) {
        when (action) {
            WatchFaceManagerAdapter.Action.SET -> {
                if (item is ManagerItem.Face) {
                    setWatchFace(item)
                }
            }
            WatchFaceManagerAdapter.Action.DELETE -> {
                if (item is ManagerItem.Face) {
                    (activity as? WatchFaceActivity)?.deleteWatchFaceOnWatch(item.fileInfo.path)
                }
            }
            WatchFaceManagerAdapter.Action.RENAME -> {
                if (item is ManagerItem.Face) {
                    showRenameDialog(item.fileInfo)
                }
            }
            WatchFaceManagerAdapter.Action.DELETE_FOLDER -> {
                if (item is ManagerItem.Header && item.name != "<root>") {
                    (activity as? WatchFaceActivity)?.deleteWatchFaceOnWatch("$watchPath/${item.name}")
                }
            }
            WatchFaceManagerAdapter.Action.RENAME_FOLDER -> {
                if (item is ManagerItem.Header && item.name != "<root>") {
                    Toast.makeText(context, "Folder rename not yet implemented", Toast.LENGTH_SHORT).show()
                }
            }
            WatchFaceManagerAdapter.Action.TOGGLE_FOLDER -> {
                rebuildList()
            }
        }
    }

    private fun setWatchFace(face: ManagerItem.Face) {
        val relPath = if (face.folderName == "<root>") face.fileInfo.name else "${face.folderName}/${face.fileInfo.name}"
        (activity as? WatchFaceActivity)?.applyWatchFace(relPath, null)
    }

    private fun showRenameDialog(fileInfo: WatchFaceFileInfo) {
        val input = EditText(context).apply { setText(fileInfo.name) }
        AlertDialog.Builder(context)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotBlank() && newName != fileInfo.name) {
                    // Send rename command via protocol
                    (activity as? WatchFaceActivity)?.renameWatchFile(fileInfo.path, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}


package com.ailife.rtosify.watchface

import android.app.AlertDialog
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.ailife.rtosify.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import java.io.File

class WatchFaceManagerFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WatchFaceManagerAdapter
    private lateinit var btnImport: View
    private lateinit var fabCreateFolder: FloatingActionButton
    
    private val watchPath = "Android/data/com.ailife.ClockSkinCoco/files/ClockSkin"
    private val folderContents = mutableMapOf<String, List<WatchFaceFileInfo>>()
    private val rootFiles = mutableListOf<WatchFaceFileInfo>()
    private val folders = mutableListOf<WatchFaceFileInfo>()
    
    private var isLocal = false

    companion object {
        private const val ARG_IS_LOCAL = "is_local"

        fun newInstance(isLocal: Boolean): WatchFaceManagerFragment {
            val fragment = WatchFaceManagerFragment()
            val args = Bundle()
            args.putBoolean(ARG_IS_LOCAL, isLocal)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isLocal = arguments?.getBoolean(ARG_IS_LOCAL) ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_watch_face_local, container, false)
        
        btnImport = view.findViewById(R.id.btnImport)
        fabCreateFolder = view.findViewById(R.id.fabCreateFolder)
        recyclerView = view.findViewById(R.id.recyclerView)

        if (isLocal) {
            btnImport.visibility = View.VISIBLE
            fabCreateFolder.visibility = View.GONE
            btnImport.setOnClickListener {
                (activity as? WatchFaceActivity)?.pickWatchFaceFromDevice()
            }
        } else {
            btnImport.visibility = View.GONE
            fabCreateFolder.visibility = View.VISIBLE
            fabCreateFolder.setOnClickListener {
                showCreateFolderDialog()
            }
        }
        
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
        
        if (!isLocal) {
            // Setup drag-and-drop only for Watch mode
            val dragCallback = WatchFaceDragCallback { fromPath, toFolder ->
                val fileName = fromPath.substringAfterLast("/")
                val destPath = if (toFolder == "<root>") {
                    "$watchPath/$fileName"
                } else {
                    "$watchPath/$toFolder/$fileName"
                }
                (activity as? WatchFaceActivity)?.let { activity ->
                    val msg = com.ailife.rtosify.ProtocolHelper.createMoveFile(fromPath, destPath)
                    activity.sendBluetoothMessage(msg)
                    activity.runOnUiThread {
                        android.widget.Toast.makeText(context, "Moving $fileName to $toFolder", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    // Refresh after a delay
                    recyclerView.postDelayed({ refresh() }, 1000)
                }
            }
            val itemTouchHelper = ItemTouchHelper(dragCallback)
            itemTouchHelper.attachToRecyclerView(recyclerView)
        }
        
        refresh()
        
        return view
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun showCreateFolderDialog() {
        val input = EditText(context).apply {
            hint = "Folder name"
        }
        AlertDialog.Builder(context)
            .setTitle("Create Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isNotBlank()) {
                    val folderPath = "$watchPath/$folderName"
                    (activity as? WatchFaceActivity)?.createFolder(folderPath)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun refresh() {
        if (isLocal) {
            loadLocalFiles()
        } else {
            folderContents.clear()
            rootFiles.clear()
            folders.clear()
            (activity as? WatchFaceActivity)?.requestWatchFileList(watchPath)
        }
    }

    private fun loadLocalFiles() {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RTOSify/WatchFaces")
            if (!dir.exists()) dir.mkdirs()
            
            val files = dir.listFiles { file -> 
                file.isFile && (file.name.endsWith(".zip", true) || file.name.endsWith(".watch", true))
            } ?: emptyArray()

            val items = mutableListOf<ManagerItem>()
            if (files.isNotEmpty()) {
                items.add(ManagerItem.Header("Downloaded", true))
                items.addAll(files.map { file ->
                    val fileInfo = WatchFaceFileInfo(file.name, file.absolutePath, false, file.length())
                    ManagerItem.Face(fileInfo, "Downloaded")
                })
            }
            
            activity?.runOnUiThread {
                adapter.setData(items)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateList(filesJson: String) {
        if (isLocal) return

        try {
            rootFiles.clear()
            folders.clear()
            
            val array = JSONArray(filesJson)
            
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
            
            // Request contents for each folder
            folders.forEach { folder ->
                (activity as? WatchFaceActivity)?.requestWatchFileList(folder.path)
            }
            
            rebuildList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateFolderContents(folderPath: String, filesJson: String) {
        if (isLocal) return
        
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
        if (isLocal) return

        val items = mutableListOf<ManagerItem>()
        
        // Always add root header
        val rootHeader = adapter.getAllItems().find { it is ManagerItem.Header && it.name == "<root>" } as? ManagerItem.Header 
            ?: ManagerItem.Header("<root>", true)
        
        items.add(rootHeader)
        if (rootHeader.isExpanded) {
            items.addAll(rootFiles.map { ManagerItem.Face(it, "<root>") })
        }
        
        // Add folders
        folders.forEach { folder ->
            val existingHeader = adapter.getAllItems().find { it is ManagerItem.Header && it.name == folder.name } as? ManagerItem.Header
            val header = existingHeader ?: ManagerItem.Header(folder.name, false)
            
            items.add(header)
            
            if (header.isExpanded) {
                folderContents[folder.path]?.forEach { fileInfo ->
                    items.add(ManagerItem.Face(fileInfo, folder.name))
                }
            }
        }
        
        activity?.runOnUiThread {
            adapter.setData(items)
        }
    }

    private fun handleAction(action: WatchFaceManagerAdapter.Action, item: ManagerItem) {
        when (action) {
            WatchFaceManagerAdapter.Action.SET -> {
                if (item is ManagerItem.Face) {
                    if (isLocal) {
                        // Send to watch
                        (activity as? WatchFaceActivity)?.transferWatchFace(File(item.fileInfo.path))
                    } else {
                        // Apply on watch
                        setWatchFace(item)
                    }
                }
            }
            WatchFaceManagerAdapter.Action.DELETE -> {
                if (item is ManagerItem.Face) {
                    if (isLocal) {
                        // Delete local file
                        try {
                            File(item.fileInfo.path).delete()
                            refresh()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Failed to delete: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Delete on watch
                        (activity as? WatchFaceActivity)?.deleteWatchFaceOnWatch(item.fileInfo.path)
                    }
                }
            }
            WatchFaceManagerAdapter.Action.RENAME -> {
                if (!isLocal && item is ManagerItem.Face) {
                    showRenameDialog(item.fileInfo)
                }
            }
            WatchFaceManagerAdapter.Action.DELETE_FOLDER -> {
                if (!isLocal && item is ManagerItem.Header && item.name != "<root>") {
                    (activity as? WatchFaceActivity)?.deleteWatchFaceOnWatch("$watchPath/${item.name}")
                }
            }
            WatchFaceManagerAdapter.Action.RENAME_FOLDER -> {
                if (!isLocal && item is ManagerItem.Header && item.name != "<root>") {
                    showRenameFolderDialog(item.name)
                }
            }
            WatchFaceManagerAdapter.Action.TOGGLE_FOLDER -> {
                // rebuildList() will reuse the expanded state from the adapter which is updated by the viewholder click? 
                // Actually the adapter updates the item state.
                // But we need to refresh the view. 
                // In my rebuildList implementation, I am checking existing headers in adapter.
                // But adapter.setData() replaces the list.
                // The Adapter logic handles toggling by calling notifyDataSetChanged via updateDisplayedItems?
                // Wait, WatchFaceManagerAdapter handles expansion locally via 'displayedItems'.
                // Calls back TOGGLE_FOLDER.
                // But my rebuildList logic re-constructs the list based on expanded state.
                // So I definitely need to rebuild list if content is dynamic.
                // The adapter implementation provided in step 12 handles expansion inside itself.
                // "item.isExpanded = !item.isExpanded; updateDisplayedItems()"
                // So I might NOT need to rebuild the list just for toggling if I trust the adapter.
                // BUT, if I rebuild, I must persist the expanded state.
                // In rebuildList, I am looking up existing headers:
                // val existingHeader = adapter.getAllItems().find ...
                // This seems correct.
                if (!isLocal) rebuildList()
            }
        }
    }

    private fun showRenameFolderDialog(oldName: String) {
        val input = EditText(context).apply {
            setText(oldName)
            hint = "Folder name"
        }
        AlertDialog.Builder(context)
            .setTitle("Rename Folder")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank() && newName != oldName) {
                    val oldPath = "$watchPath/$oldName"
                    (activity as? WatchFaceActivity)?.renameWatchFile(oldPath, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

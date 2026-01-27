
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import java.io.File
import com.ailife.rtosify.R

class WatchFaceManagerFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WatchFaceManagerAdapter
    private lateinit var btnImport: View
    private lateinit var fabCreateFolder: FloatingActionButton
    private lateinit var layoutSelection: View
    private lateinit var btnDeleteSelected: View
    private lateinit var btnUploadSelected: View
    
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
        layoutSelection = view.findViewById(R.id.layoutSelection)
        btnDeleteSelected = view.findViewById(R.id.btnDeleteSelected)
        btnUploadSelected = view.findViewById(R.id.btnUploadSelected)
        
        btnDeleteSelected.setOnClickListener { deleteSelectedItems() }
        btnUploadSelected.setOnClickListener { uploadSelectedItems() }

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
        
        adapter = WatchFaceManagerAdapter(emptyList(), isLocal, 
            onRequestPreview = { path ->
                if (!isLocal) {
                    (activity as? WatchFaceActivity)?.requestPreview(path)
                }
            },
            onAction = { action, item ->
                handleAction(action, item)
            },
            onSelectionChanged = { isSelectionMode, count ->
                updateSelectionUI(isSelectionMode, count)
            }
        )
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
                        android.widget.Toast.makeText(context, getString(R.string.wf_moving_to, fileName, toFolder), android.widget.Toast.LENGTH_SHORT).show()
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
            hint = getString(R.string.wf_folder_name_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.wf_create_folder_title)
            .setView(input)
            .setPositiveButton(R.string.wf_button_create) { _, _ ->
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
    
    fun updatePreview(path: String, imageBase64: String) {
        val bytes = android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        activity?.runOnUiThread {
            adapter.setPreview(path, bitmap)
        }
    }

    private fun loadLocalFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rootDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RTOSify/WatchFaces")
                if (!rootDir.exists()) rootDir.mkdirs()

                val downloadedDir = File(rootDir, "Downloaded")
                val importedDir = File(rootDir, "Imported")
                
                if (!downloadedDir.exists()) downloadedDir.mkdirs()
                if (!importedDir.exists()) importedDir.mkdirs()

                val items = mutableListOf<ManagerItem>()
                
                // 1. Downloaded
                val downloadedFiles = downloadedDir.listFiles { file -> 
                    file.isFile && (file.name.endsWith(".zip", true) || file.name.endsWith(".watch", true))
                } ?: emptyArray()
                
                if (downloadedFiles.isNotEmpty()) {
                    items.add(ManagerItem.Header(getString(R.string.wf_header_downloaded), true))
                    items.addAll(downloadedFiles.map { file ->
                        val fileInfo = WatchFaceFileInfo(file.name, file.absolutePath, false, file.length())
                        ManagerItem.Face(fileInfo, getString(R.string.wf_header_downloaded))
                    })
                }

                // 2. Imported
                val importedFiles = importedDir.listFiles { file -> 
                    file.isFile && (file.name.endsWith(".zip", true) || file.name.endsWith(".watch", true))
                } ?: emptyArray()

                if (importedFiles.isNotEmpty()) {
                    items.add(ManagerItem.Header("Imported", true))
                    items.addAll(importedFiles.map { file ->
                        val fileInfo = WatchFaceFileInfo(file.name, file.absolutePath, false, file.length())
                        ManagerItem.Face(fileInfo, "Imported")
                    })
                }

                // 3. Other (Root)
                val otherFiles = rootDir.listFiles { file -> 
                    file.isFile && (file.name.endsWith(".zip", true) || file.name.endsWith(".watch", true))
                } ?: emptyArray()

                if (otherFiles.isNotEmpty()) {
                    items.add(ManagerItem.Header("Other", true))
                    items.addAll(otherFiles.map { file ->
                        val fileInfo = WatchFaceFileInfo(file.name, file.absolutePath, false, file.length())
                        ManagerItem.Face(fileInfo, "Other")
                    })
                }
                
                withContext(Dispatchers.Main) {
                    adapter.setData(items)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                   Toast.makeText(context, "Error loading files", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun updateSelectionUI(isSelectionMode: Boolean, count: Int) {
        activity?.runOnUiThread {
            if (isSelectionMode) {
                layoutSelection.visibility = View.VISIBLE
                btnImport.visibility = View.GONE
                fabCreateFolder.visibility = View.GONE
                // Update text or buttons if needed
            } else {
                layoutSelection.visibility = View.GONE
                if (isLocal) {
                    btnImport.visibility = View.VISIBLE
                    fabCreateFolder.visibility = View.GONE
                } else {
                    btnImport.visibility = View.GONE
                    fabCreateFolder.visibility = View.VISIBLE
                }
            }
        }
    }
    
    private fun deleteSelectedItems() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) return
        
        AlertDialog.Builder(context)
            .setTitle(R.string.wf_delete)
            .setMessage(getString(R.string.wf_delete_confirm_count, selected.size))
            .setPositiveButton(R.string.wf_button_delete) { _, _ ->
                if (isLocal) {
                    var deletedCount = 0
                    selected.forEach { item ->
                        try {
                            if (File(item.fileInfo.path).delete()) deletedCount++
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    Toast.makeText(context, "Deleted $deletedCount files", Toast.LENGTH_SHORT).show()
                    adapter.clearSelection()
                    refresh()
                } else {
                     // Remote delete
                     selected.forEach { item ->
                        (activity as? WatchFaceActivity)?.deleteWatchFaceOnWatch(item.fileInfo.path)
                     }
                     adapter.clearSelection()
                     // Protocol doesn't support bulk delete properly in one go without delay, 
                     // but sending multiple messages is fine. Refresh will happen eventually.
                     // Maybe trigger refresh after delay
                }
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }

    private fun uploadSelectedItems() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) return
        
        if (!isLocal) return // Can't upload from watch to watch (move?)
        
        selected.forEach { item ->
            (activity as? WatchFaceActivity)?.transferWatchFace(File(item.fileInfo.path))
        }
        adapter.clearSelection()
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
                            Toast.makeText(context, getString(R.string.wf_delete_fail, e.message), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Delete on watch
                        (activity as? WatchFaceActivity)?.deleteWatchFaceOnWatch(item.fileInfo.path)
                    }
                }
            }
            WatchFaceManagerAdapter.Action.RENAME -> {
                if (item is ManagerItem.Face) {
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
                if (!isLocal) rebuildList()
            }
            WatchFaceManagerAdapter.Action.SELECTION_MODE -> {
                // Handled by Adapter internal logic, but required for exhaustiveness
            }
        }
    }

    private fun showRenameFolderDialog(oldName: String) {
        val input = EditText(context).apply {
            setText(oldName)
            hint = getString(R.string.wf_folder_name_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.wf_rename_folder_title)
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
            .setTitle(R.string.wf_rename_title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotBlank() && newName != fileInfo.name) {
                    if (isLocal) {
                        try {
                            val oldFile = File(fileInfo.path)
                            val newFile = File(oldFile.parent, newName)
                            if (oldFile.renameTo(newFile)) {
                                refresh()
                            } else {
                                Toast.makeText(context, getString(R.string.wf_rename_fail), Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, getString(R.string.wf_rename_fail_with_msg, e.message), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Send rename command via protocol
                        (activity as? WatchFaceActivity)?.renameWatchFile(fileInfo.path, newName)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

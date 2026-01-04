package com.ailife.rtosify.watchface

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.rtosify.R
import java.io.File

class LocalWatchFaceFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LocalWatchFaceAdapter
    private val localDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RTOSify/WatchFaces")

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_watch_face_local, container, false)
        
        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(context)
        
        adapter = LocalWatchFaceAdapter(mutableListOf()) { file ->
            // Send to watch
            (activity as? WatchFaceActivity)?.transferWatchFace(file)
        }
        recyclerView.adapter = adapter
        
        // Hide the import button, use FAB instead
        view.findViewById<Button>(R.id.btnImport).visibility = View.GONE
        
        // Setup FAB for importing files
        val fab = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabCreateFolder)
        fab.setImageResource(android.R.drawable.ic_input_add)
        fab.setOnClickListener {
            (activity as? WatchFaceActivity)?.pickWatchFaceFromDevice()
        }

        if (!localDir.exists()) localDir.mkdirs()
        
        return view
    }

    fun refreshList() {
        if (!localDir.exists()) localDir.mkdirs()
        val files = localDir.listFiles()?.filter { file ->
            val name = file.name.lowercase()
            name.endsWith(".zip") || name.endsWith(".watch") 
        } ?: emptyList()
        android.util.Log.d("LocalWatchFace", "Found ${files.size} local watch faces in ${localDir.absolutePath}")
        adapter.updateList(files)
    }
}

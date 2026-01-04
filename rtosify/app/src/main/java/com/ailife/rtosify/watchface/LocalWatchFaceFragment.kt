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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_watch_face_local, container, false)
        
        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(context)
        
        adapter = LocalWatchFaceAdapter(mutableListOf()) { file ->
            // Action on click: Apply to watch
            (activity as? WatchFaceActivity)?.transferWatchFace(file)
        }
        recyclerView.adapter = adapter
        
        view.findViewById<Button>(R.id.btnImport).setOnClickListener {
            (activity as? WatchFaceActivity)?.pickWatchFaceFromDevice()
        }

        if (!localDir.exists()) localDir.mkdirs()
        refreshList()
        
        return view
    }

    fun refreshList() {
        val files = localDir.listFiles { _, name -> 
            name.endsWith(".zip", true) || name.endsWith(".watch", true) 
        }?.toList() ?: emptyList()
        adapter.updateList(files)
    }
}

package com.ailife.rtosify.watchface

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.ailife.rtosify.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchFaceStoreFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: WatchFaceStoreAdapter
    private var currentPage = 1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_watch_face_store, container, false)
        
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView.layoutManager = GridLayoutManager(context, 2)
        
        adapter = WatchFaceStoreAdapter(mutableListOf()) { watchFace ->
            // Download watch face
            (activity as? WatchFaceActivity)?.downloadWatchFace(watchFace)
        }
        recyclerView.adapter = adapter
        
        swipeRefresh.setOnRefreshListener {
            loadStore(1)
        }

        loadStore(1)
        
        return view
    }

    private fun loadStore(page: Int) {
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            val faces = WatchFaceScraper.scrapeWatchFaces(page)
            withContext(Dispatchers.Main) {
                swipeRefresh.isRefreshing = false
                if (faces.isEmpty()) {
                    Toast.makeText(context, R.string.wf_error_load, Toast.LENGTH_SHORT).show()
                } else {
                    if (page == 1) adapter.setList(faces)
                    else adapter.appendList(faces)
                    currentPage = page
                }
            }
        }
    }
}

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
    private var lastThreadId = -1
    private var isLoading = false
    private var isEndReached = false
    private var consecutiveFailures = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_watch_face_store, container, false)
        
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        recyclerView = view.findViewById(R.id.recyclerView)
        val layoutManager = GridLayoutManager(context, 2)
        recyclerView.layoutManager = layoutManager
        
        adapter = WatchFaceStoreAdapter(mutableListOf()) { watchFace ->
            (activity as? WatchFaceActivity)?.downloadWatchFace(watchFace)
        }
        recyclerView.adapter = adapter
        
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !isLoading && !isEndReached) {
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val pastVisibleItems = layoutManager.findFirstVisibleItemPosition()

                    if (visibleItemCount + pastVisibleItems >= totalItemCount - 2) {
                        if (lastThreadId > 0) {
                            loadStore(lastThreadId - 1)
                        } else {
                            isEndReached = true
                        }
                    }
                }
            }
        })

        swipeRefresh.setOnRefreshListener {
            lastThreadId = -1
            isEndReached = false
            consecutiveFailures = 0
            adapter.setList(emptyList()) // Clear list on refresh
            loadStore(-1)
        }

        loadStore(-1)
        
        return view
    }

    private fun loadStore(threadId: Int) {
        if (isLoading || isEndReached) return
        
        isLoading = true
        if (threadId == -1) swipeRefresh.isRefreshing = true
        
        lifecycleScope.launch(Dispatchers.IO) {
            val result = WatchFaceScraper.scrapeWatchFaces(threadId)
            withContext(Dispatchers.Main) {
                isLoading = false
                swipeRefresh.isRefreshing = false
                
                if (result.faces.isEmpty()) {
                    consecutiveFailures++
                    android.util.Log.d("WatchFaceStore", "Thread $threadId empty. Failures: $consecutiveFailures")
                    if (consecutiveFailures >= 3) {
                        isEndReached = true
                        android.util.Log.d("WatchFaceStore", "End reached after 3 failures")
                    }
                    // Try previous thread automatically if we haven't reached the end
                    if (!isEndReached && threadId > 0) {
                        loadStore(threadId - 1)
                    }
                } else {
                    consecutiveFailures = 0 // Reset failures on success
                    android.util.Log.d("WatchFaceStore", "Adding ${result.faces.size} faces from thread ${result.threadId}")
                    
                    // Reverse faces to show last face as first
                    val orderedFaces = result.faces.reversed()
                    
                    if (threadId == -1) {
                        android.util.Log.d("WatchFaceStore", "Initial load, calling setList")
                        adapter.setList(orderedFaces)
                    } else {
                        android.util.Log.d("WatchFaceStore", "Incremental load, calling appendList")
                        adapter.appendList(orderedFaces)
                    }
                    lastThreadId = result.threadId
                }
            }
        }
    }
}

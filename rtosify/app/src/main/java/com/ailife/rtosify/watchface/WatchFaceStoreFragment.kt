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
    
    // Use factory to instantiate ViewModel
    private val viewModel: WatchFaceStoreViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[WatchFaceStoreViewModel::class.java]
    }

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
        
        setupObservers()
        setupListeners(layoutManager)
        
        return view
    }
    
    private fun setupObservers() {
        viewModel.faces.observe(viewLifecycleOwner) { faces ->
            adapter.setList(faces)
        }
        
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            // Only show swipe refresh indicator if it was manually triggered or initial load
            // We can rely on the user dragging for refresh, but if we want to show loading indicator for pagination we might need another progress bar
            // For now, sync swipeRefresh state with loading state if list is empty (initial load)
            if (adapter.itemCount == 0) {
                swipeRefresh.isRefreshing = isLoading
            } else {
                swipeRefresh.isRefreshing = false
            }
        }
        
        viewModel.loadError.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupListeners(layoutManager: GridLayoutManager) {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val pastVisibleItems = layoutManager.findFirstVisibleItemPosition()

                    if (visibleItemCount + pastVisibleItems >= totalItemCount - 4) { // load a bit earlier
                        viewModel.loadMore()
                    }
                }
            }
        })

        swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }
    }
}

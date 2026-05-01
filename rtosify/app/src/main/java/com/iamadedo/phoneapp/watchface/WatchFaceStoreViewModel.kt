package com.iamadedo.phoneapp.watchface

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchFaceStoreViewModel : ViewModel() {

    private val _faces = MutableLiveData<List<WatchFace>>(emptyList())
    val faces: LiveData<List<WatchFace>> = _faces

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _loadError = MutableLiveData<String?>()
    val loadError: LiveData<String?> = _loadError

    private var lastThreadId = -1
    private var isEndReached = false
    private var consecutiveFailures = 0
    
    // Set for fast deduplication based on download URL
    private val loadedFaceUrls = mutableSetOf<String>()
    
    // Internal list to back the LiveData
    private val currentFaces = mutableListOf<WatchFace>()

    init {
        loadStore(-1)
    }

    fun loadMore() {
        if (_isLoading.value == true || isEndReached) return
        
        if (lastThreadId > 0) {
            loadStore(lastThreadId - 1)
        } else if (lastThreadId != -1) { // If lastThreadId is 0, we are done, unless we are at start
             isEndReached = true
        }
    }
    
    fun refresh() {
        lastThreadId = -1
        isEndReached = false
        consecutiveFailures = 0
        loadedFaceUrls.clear()
        currentFaces.clear()
        _faces.value = emptyList()
        loadStore(-1)
    }

    private fun loadStore(threadId: Int) {
        _isLoading.value = true
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = WatchFaceScraper.scrapeWatchFaces(threadId)
                
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    
                    if (result.faces.isEmpty()) {
                        consecutiveFailures++
                        Log.d("WatchFaceStoreVM", "Thread $threadId empty. Failures: $consecutiveFailures")
                        if (consecutiveFailures >= 3) {
                            isEndReached = true
                            Log.d("WatchFaceStoreVM", "End reached after 3 failures")
                        }
                        // Try previous thread automatically within reasonable limits
                        // Using recursion here on Main thread is safe fast, but better to keep it controlled
                        if (!isEndReached && threadId > 0 && consecutiveFailures < 3) {
                            loadStore(threadId - 1)
                        }
                    } else {
                        consecutiveFailures = 0
                        Log.d("WatchFaceStoreVM", "Adding ${result.faces.size} faces from thread ${result.threadId}")
                        
                        // New faces processing
                        val newFaces = result.faces.reversed().filter { face ->
                            val isNew = !loadedFaceUrls.contains(face.downloadUrl)
                            if (isNew) {
                                loadedFaceUrls.add(face.downloadUrl)
                            }
                            isNew
                        }
                        
                        if (newFaces.isNotEmpty()) {
                            currentFaces.addAll(newFaces)
                            _faces.value = currentFaces.toList() // Post new immutable list
                            
                        }
                        lastThreadId = result.threadId
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    Log.e("WatchFaceStoreVM", "Error loading store", e)
                    _loadError.value = "Failed to load watch faces"
                }
            }
        }
    }
}

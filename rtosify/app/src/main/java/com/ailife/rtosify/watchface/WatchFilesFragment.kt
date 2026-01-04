package com.ailife.rtosify.watchface

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.rtosify.R
import org.json.JSONArray

class WatchFilesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WatchFilesAdapter
    private lateinit var tvEmpty: TextView
    private val watchPath = "Android/data/com.ailife.ClockSkinCoco/files/ClockSkin"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_watch_face_local, container, false)
        
        // Hide import button for watch files
        view.findViewById<View>(R.id.btnImport).visibility = View.GONE
        
        recyclerView = view.findViewById(R.id.recyclerView)
        tvEmpty = TextView(context).apply {
            text = "No watch faces found on watch"
            visibility = View.GONE
            gravity = android.view.Gravity.CENTER
            setPadding(0, 50, 0, 0)
        }
        (view as ViewGroup).addView(tvEmpty)
        
        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = WatchFilesAdapter { fileName ->
            (activity as? WatchFaceActivity)?.deleteWatchFaceOnWatch(watchPath + "/" + fileName)
        }
        recyclerView.adapter = adapter
        
        return view
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    fun refreshList() {
        (activity as? WatchFaceActivity)?.requestWatchFileList(watchPath)
    }

    fun updateList(filesJson: String) {
        try {
            val files = mutableListOf<String>()
            val array = JSONArray(filesJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                if (name.lowercase().endsWith(".zip") || name.lowercase().endsWith(".watch")) {
                    files.add(name)
                }
            }
            activity?.runOnUiThread {
                adapter.updateList(files)
                tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class WatchFilesAdapter(private val onDelete: (String) -> Unit) : RecyclerView.Adapter<WatchFilesAdapter.ViewHolder>() {
    private var list = listOf<String>()

    fun updateList(newList: List<String>) {
        list = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_watch_face_local, parent, false)
        return ViewHolder(view, onDelete)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(list[position])
    }

    override fun getItemCount() = list.size

    class ViewHolder(itemView: View, private val onDelete: (String) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvFileName)
        private val btnApply: Button = itemView.findViewById(R.id.btnApply)
        private val btnDelete: Button = itemView.findViewById(R.id.btnDelete)

        fun bind(fileName: String) {
            tvName.text = fileName
            btnApply.visibility = View.GONE // Already on watch
            btnDelete.setOnClickListener { onDelete(fileName) }
        }
    }
}

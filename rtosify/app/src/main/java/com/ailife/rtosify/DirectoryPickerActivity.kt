package com.ailife.rtosify

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Stack

class DirectoryPickerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var currentPathTextView: TextView
    private lateinit var adapter: DirectoryAdapter
    private var currentPath: File = Environment.getExternalStorageDirectory()
    private val historyStack = Stack<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_directory_picker)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Pick Directory"

        currentPathTextView = findViewById(R.id.tvCurrentPath)
        recyclerView = findViewById(R.id.recyclerViewFiles)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = DirectoryAdapter { file ->
            if (file.isDirectory) {
                navigateTo(file)
            }
        }
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btnSelectCurrent).setOnClickListener {
            val resultIntent = Intent()
            resultIntent.putExtra("picked_path", currentPath.absolutePath)
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (historyStack.isNotEmpty()) {
                    val prev = historyStack.pop()
                    currentPath = prev
                    refreshList()
                    return
                }
                finish()
            }
        })

        if (!Environment.isExternalStorageManager()) {
            // Should warn user? For now assume permission granted via PermissionActivity
        }

        refreshList()
    }

    private fun navigateTo(file: File) {
        historyStack.push(currentPath)
        currentPath = file
        refreshList()
    }

    private fun refreshList() {
        currentPathTextView.text = currentPath.absolutePath
        val files = currentPath.listFiles()?.filter { it.isDirectory && !it.isHidden }?.sortedBy { it.name } ?: emptyList()
        adapter.submitList(files)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class DirectoryAdapter(private val onClick: (File) -> Unit) : RecyclerView.Adapter<DirectoryAdapter.ViewHolder>() {
        private var files = listOf<File>()

        fun submitList(newFiles: List<File>) {
            files = newFiles
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view, onClick)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(files[position])
        }

        override fun getItemCount() = files.size

        class ViewHolder(itemView: View, val onClick: (File) -> Unit) : RecyclerView.ViewHolder(itemView) {
            fun bind(file: File) {
                (itemView as TextView).text = file.name
                itemView.setOnClickListener { onClick(file) }
            }
        }
    }
}

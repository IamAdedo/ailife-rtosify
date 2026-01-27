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
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Stack

class DirectoryPickerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var currentPathTextView: TextView
    private lateinit var adapter: DirectoryAdapter
    private var currentPath: File = Environment.getExternalStorageDirectory()
    private val historyStack = Stack<File>()
    private val gson = Gson()

    // Shizuku UserService
    private var userService: IUserService? = null
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("user_service")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
    }

    private val userServiceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (binder.pingBinder()) {
                userService = IUserService.Stub.asInterface(binder)
                Log.i("DirectoryPicker", "UserService connected successfully")
                refreshList() // Refresh once connected
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            userService = null
        }
    }

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

        // Bind Shizuku if available
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.bindUserService(userServiceArgs, userServiceConn)
            }
        } catch (e: Exception) {
            Log.e("DirectoryPicker", "Shizuku bind failed: ${e.message}")
        }

        refreshList()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.unbindUserService(userServiceArgs, userServiceConn, true)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun navigateTo(file: File) {
        historyStack.push(currentPath)
        currentPath = file
        refreshList()
    }

    private fun refreshList() {
        currentPathTextView.text = currentPath.absolutePath
        
        // Try standard API
        var files = currentPath.listFiles()?.filter { it.isDirectory && !it.isHidden }?.sortedBy { it.name }
        
        // Fallback to Shizuku UserService
        if (files == null && userService != null) {
            try {
                val json = userService?.listFiles(currentPath.absolutePath)
                if (!json.isNullOrEmpty() && json != "[]") {
                    val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                    val list: List<Map<String, Any>> = gson.fromJson(json, type)
                    files = list.filter { it["isDirectory"] == true }
                        .map { File(currentPath, it["name"] as String) }
                        .sortedBy { it.name }
                }
            } catch (e: Exception) {
                Log.e("DirectoryPicker", "Shizuku listFiles failed: ${e.message}")
            }
        }
        
        adapter.submitList(files ?: emptyList())
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

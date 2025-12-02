package com.marinov.watch

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray

class AppListActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyList: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter
    private lateinit var toolbar: Toolbar

    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    // Conexão com o Serviço
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            bluetoothService?.callback = this@AppListActivity
            isBound = true

            // Solicita a lista assim que conectar
            fetchApps()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            bluetoothService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        progressBar = findViewById(R.id.progressBarList)
        tvEmptyList = findViewById(R.id.tvEmptyList)
        recyclerView = findViewById(R.id.recyclerViewApps)

        // Configura RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter()
        recyclerView.adapter = adapter

        // Binda no serviço existente
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun fetchApps() {
        if (isBound && bluetoothService != null) {
            progressBar.visibility = View.VISIBLE
            tvEmptyList.text = "Solicitando lista ao Watch..."
            tvEmptyList.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            bluetoothService?.requestRemoteAppList()
        } else {
            Toast.makeText(this, "Serviço desconectado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    // --- Callbacks do Serviço ---
    override fun onAppListReceived(appsJson: String) {
        runOnUiThread {
            progressBar.visibility = View.GONE
            try {
                val jsonArray = JSONArray(appsJson)
                val apps = mutableListOf<AppItem>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.getString("name")
                    val pkg = obj.getString("package")
                    val iconBase64 = obj.optString("icon", "")

                    apps.add(AppItem(name, pkg, iconBase64))
                }

                apps.sortBy { it.name.lowercase() }

                if (apps.isEmpty()) {
                    tvEmptyList.text = "Nenhum app de usuário encontrado no relógio."
                    tvEmptyList.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    tvEmptyList.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    adapter.updateList(apps)
                }

            } catch (e: Exception) {
                tvEmptyList.text = "Erro ao processar dados."
                Toast.makeText(this, "Erro ao processar lista: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Implementações vazias dos outros callbacks
    override fun onStatusChanged(status: String) {}
    override fun onDeviceConnected(deviceName: String) {}
    override fun onDeviceDisconnected() {
        runOnUiThread {
            Toast.makeText(this, "Watch desconectado!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    override fun onError(message: String) {}
    override fun onScanResult(devices: List<BluetoothDevice>) {}
    override fun onUploadProgress(progress: Int) {}

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // --- Classes de Adapter e Dados ---

    data class AppItem(val name: String, val packageName: String, val iconBase64: String)

    class AppAdapter : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {
        private var list = listOf<AppItem>()

        fun updateList(newList: List<AppItem>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return AppViewHolder(view)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(list[position])
        }

        override fun getItemCount() = list.size

        class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imgIcon: ImageView = itemView.findViewById(R.id.imgAppIcon)
            private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
            private val tvPkg: TextView = itemView.findViewById(R.id.tvAppPackage)

            fun bind(item: AppItem) {
                tvName.text = item.name
                tvPkg.text = item.packageName

                if (item.iconBase64.isNotEmpty()) {
                    try {
                        val decodedString = Base64.decode(item.iconBase64, Base64.DEFAULT)
                        val decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                        imgIcon.setImageBitmap(decodedByte)
                    } catch (e: Exception) {
                        imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                } else {
                    imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
        }
    }
}
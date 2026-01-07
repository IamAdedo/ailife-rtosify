package com.ailife.rtosifycompanion

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class ClipboardTestActivity : AppCompatActivity() {

    private var userService: IUserService? = null
    private lateinit var statusText: TextView
    private lateinit var clipboardResult: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = IUserService.Stub.asInterface(service)
            statusText.text = "Status: Connected"
            Toast.makeText(this@ClipboardTestActivity, "UserService Connected", Toast.LENGTH_SHORT).show()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            statusText.text = "Status: Disconnected"
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name))
        .daemon(false)
        .processNameSuffix("user_service")
        .debuggable(BuildConfig.DEBUG)
        .version(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clipboard_test)

        statusText = findViewById(R.id.statusText)
        clipboardResult = findViewById(R.id.clipboardResult)

        findViewById<Button>(R.id.btnBind).setOnClickListener {
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Bind failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnReadClipboard).setOnClickListener {
            if (userService == null) {
                Toast.makeText(this, "Service not bound", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(this, "Reading in 3 seconds... Switch apps now!", Toast.LENGTH_LONG).show()
            
            // Delay reading to allow user to switch to another app and copy something
            handler.postDelayed({
                try {
                    val text = userService?.primaryClipText
                    clipboardResult.text = "Clipboard: ${text ?: "Empty or null"}"
                } catch (e: Exception) {
                    e.printStackTrace()
                    clipboardResult.text = "Error: ${e.message}"
                }
            }, 3000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (e: Exception) {}
    }
}

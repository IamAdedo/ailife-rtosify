package com.ailife.rtosifycompanion

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ReplyDialogActivity : AppCompatActivity() {

    private lateinit var etReply: EditText
    private lateinit var btnSend: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var tvTitle: TextView

    private var notifKey: String? = null
    private var actionKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.activity.enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reply_dialog)

        val rootLayout = findViewById<android.view.View>(R.id.reply_root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Make activity full screen and dialog-like
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        notifKey = intent.getStringExtra(BluetoothService.EXTRA_NOTIF_KEY)
        actionKey = intent.getStringExtra(BluetoothService.EXTRA_ACTION_KEY)
        val appName = intent.getStringExtra("app_name")

        if (notifKey == null || actionKey == null) {
            finish()
            return
        }

        // Ensure activity shows over lock screen and wakes screen
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        initViews()
        setupListeners()

        if (appName != null) {
            tvTitle.text = "Reply to $appName"
        }
    }

    private fun initViews() {
        etReply = findViewById(R.id.etReply)
        btnSend = findViewById(R.id.btnSend)
        btnCancel = findViewById(R.id.btnCancel)
        tvTitle = findViewById(R.id.tvReplyTitle)

        etReply.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun setupListeners() {
        btnCancel.setOnClickListener {
            finish()
        }

        btnSend.setOnClickListener {
            val replyText = etReply.text.toString().trim()
            if (replyText.isNotEmpty()) {
                sendReply(replyText)
                finish()
            }
        }
    }

    private fun sendReply(text: String) {
        val serviceIntent = Intent(this, BluetoothService::class.java).apply {
            action = "SEND_REPLY_TO_PHONE"
            putExtra(BluetoothService.EXTRA_NOTIF_KEY, notifKey)
            putExtra(BluetoothService.EXTRA_ACTION_KEY, actionKey)
            putExtra(BluetoothService.EXTRA_REPLY_TEXT, text)
        }
        startService(serviceIntent)
    }
}

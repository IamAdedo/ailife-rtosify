package com.ailife.rtosifycompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import com.google.gson.Gson

class FilePreviewActivity : ComponentActivity() {

    private lateinit var tvFileName: TextView
    private lateinit var tvFileSize: TextView
    private lateinit var ivPreview: ImageView
    private lateinit var btnDownload: Button
    private lateinit var btnDismiss: Button

    private var currentData: FileDetectedData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_preview)

        tvFileName = findViewById(R.id.tvFileName)
        tvFileSize = findViewById(R.id.tvFileSize)
        ivPreview = findViewById(R.id.ivPreview)
        btnDownload = findViewById(R.id.btnDownload)
        btnDismiss = findViewById(R.id.btnDismiss)

        val dataJson = intent.getStringExtra("data")
        if (dataJson != null) {
            try {
                currentData = Gson().fromJson(dataJson, FileDetectedData::class.java)
                updateUI()
            } catch (e: Exception) {
                Log.e("FilePreviewActivity", "Error parsing data: ${e.message}")
                finish()
            }
        } else {
            finish()
        }

        btnDismiss.setOnClickListener {
            finish()
        }

        btnDownload.setOnClickListener {
            currentData?.let { data ->
                // Send download request intent to BluetoothService
                // Assuming we have a way to trigger downloads.
                // Protocol.kt has MessageType.REQUEST_FILE_DOWNLOAD
                // We need to send an intent to BluetoothService to send this message.
                // Or we can add a helper in BluetoothService to handle ACTION_CMD_DOWNLOAD_FILE
                
                // For now, let's just show a toast as placeholder
                Toast.makeText(this, getString(R.string.msg_requesting_download), Toast.LENGTH_SHORT).show()
                
                // TODO: Implement actual download request logic
                // startDownload(data.path)
            }
        }
    }

    private fun updateUI() {
        currentData?.let { data ->
            tvFileName.text = data.name
            tvFileSize.text = android.text.format.Formatter.formatShortFileSize(this, data.size)

            if (data.thumbnail != null) {
                try {
                    val bytes = Base64.decode(data.thumbnail, Base64.NO_WRAP)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ivPreview.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    ivPreview.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } else {
                // Set default icon based on type
                ivPreview.setImageResource(android.R.drawable.ic_menu_agenda)
            }
        }
    }
}

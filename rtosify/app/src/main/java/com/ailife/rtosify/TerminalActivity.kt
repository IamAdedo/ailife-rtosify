package com.ailife.rtosify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView
import java.util.UUID

class TerminalActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    // UI Elements
    private lateinit var scrollView: NestedScrollView
    private lateinit var tvOutput: TextView
    private lateinit var etCommand: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var cardPermissionInfo: MaterialCardView
    private lateinit var tvPermissionLevel: TextView
    private lateinit var tvUid: TextView
    private lateinit var tvWarning: TextView

    // State
    private val outputBuilder = SpannableStringBuilder()
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var permissionInfo: PermissionInfoData? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            bluetoothService?.callback = this@TerminalActivity
            isBound = true

            // Request permission info immediately
            bluetoothService?.sendMessage(ProtocolHelper.createRequestPermissionInfo())
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            bluetoothService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        initViews()
        setupListeners()

        Intent(this, BluetoothService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        appendOutput("RTOSify Terminal v1.0", COLOR_INFO)
        appendOutput("Connecting to watch...", COLOR_INFO)
        appendOutput("", COLOR_DEFAULT)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            bluetoothService?.callback = null
            unbindService(connection)
            isBound = false
        }
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        scrollView = findViewById(R.id.scrollViewTerminal)
        tvOutput = findViewById(R.id.tvTerminalOutput)
        etCommand = findViewById(R.id.etCommand)
        btnSend = findViewById(R.id.btnSend)
        cardPermissionInfo = findViewById(R.id.cardPermissionInfo)
        tvPermissionLevel = findViewById(R.id.tvPermissionLevel)
        tvUid = findViewById(R.id.tvUid)
        tvWarning = findViewById(R.id.tvWarning)
    }

    private fun setupListeners() {
        btnSend.setOnClickListener {
            executeCommand()
        }

        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                executeCommand()
                true
            } else false
        }
    }

    private fun executeCommand() {
        val command = etCommand.text.toString().trim()
        if (command.isEmpty()) return

        // Add to history
        commandHistory.add(command)
        historyIndex = commandHistory.size

        // Display command
        appendOutput("$ $command", COLOR_COMMAND)

        // Clear input
        etCommand.text.clear()

        // Send to watch
        val sessionId = UUID.randomUUID().toString()
        bluetoothService?.sendMessage(
            ProtocolHelper.createExecuteShellCommand(command, sessionId)
        )

        // Show loading indicator
        appendOutput("Executing...", COLOR_INFO)
    }

    override fun onShellCommandResponse(response: ShellCommandResponse) {
        runOnUiThread {
            // Remove "Executing..." line by rebuilding output without last line
            val lines = outputBuilder.toString().split("\n").toMutableList()
            if (lines.isNotEmpty() && lines.last().contains("Executing...")) {
                lines.removeAt(lines.size - 1)
                outputBuilder.clear()
                lines.forEach { line ->
                    // Determine color based on content
                    val color = when {
                        line.startsWith("$") -> COLOR_COMMAND
                        line.contains("Exit code: 0") -> COLOR_SUCCESS
                        line.contains("Exit code:") -> COLOR_ERROR
                        line.startsWith("RTOSify") || line.contains("level:") -> COLOR_INFO
                        else -> COLOR_DEFAULT
                    }
                    if (line.isNotEmpty()) {
                        val start = outputBuilder.length
                        outputBuilder.append(line)
                        outputBuilder.append("\n")
                        val end = outputBuilder.length

                        outputBuilder.setSpan(
                            ForegroundColorSpan(color),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                tvOutput.text = outputBuilder
            }

            // Add output
            if (response.stdout.isNotEmpty()) {
                appendOutput(response.stdout.trimEnd(), COLOR_DEFAULT)
            }
            if (response.stderr.isNotEmpty()) {
                appendOutput(response.stderr.trimEnd(), COLOR_ERROR)
            }

            // Add exit code and execution time
            val statusColor = if (response.exitCode == 0) COLOR_SUCCESS else COLOR_ERROR
            appendOutput(
                "Exit code: ${response.exitCode} | Time: ${response.executionTimeMs}ms | " +
                        "UID: ${response.uid} (${response.permissionLevel})",
                statusColor
            )
            appendOutput("", COLOR_DEFAULT)

            // Auto-scroll to bottom
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    override fun onPermissionInfoReceived(info: PermissionInfoData) {
        runOnUiThread {
            permissionInfo = info
            updatePermissionUI(info)

            appendOutput("Permission level: ${info.level.uppercase()}", COLOR_INFO)
            appendOutput("UID: ${info.uid}", COLOR_INFO)
            appendOutput("", COLOR_DEFAULT)
        }
    }

    private fun updatePermissionUI(info: PermissionInfoData) {
        cardPermissionInfo.visibility = View.VISIBLE

        when (info.level) {
            "shizuku" -> {
                tvPermissionLevel.text = "Shizuku (Shell Access)"
                tvPermissionLevel.setTextColor(getColor(android.R.color.holo_green_light))
                tvUid.text = "UID: ${info.uid}"
                tvWarning.visibility = View.GONE
            }
            "root" -> {
                tvPermissionLevel.text = "Root Access"
                tvPermissionLevel.setTextColor(getColor(android.R.color.holo_red_light))
                tvUid.text = "UID: ${info.uid}"
                tvWarning.visibility = View.GONE
            }
            else -> {
                tvPermissionLevel.text = "App Context (Limited)"
                tvPermissionLevel.setTextColor(getColor(android.R.color.holo_orange_light))
                tvUid.text = "UID: ${info.uid}"
                tvWarning.visibility = View.VISIBLE
                tvWarning.text = "⚠ Limited permissions - some commands may fail"
            }
        }
    }

    private fun appendOutput(text: String, color: Int) {
        val start = outputBuilder.length
        outputBuilder.append(text)
        outputBuilder.append("\n")
        val end = outputBuilder.length

        outputBuilder.setSpan(
            ForegroundColorSpan(color),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        tvOutput.text = outputBuilder
    }

    companion object {
        private const val COLOR_DEFAULT = 0xFFCCCCCC.toInt()
        private const val COLOR_COMMAND = 0xFF00FF00.toInt()
        private const val COLOR_ERROR = 0xFFFF4444.toInt()
        private const val COLOR_SUCCESS = 0xFF44FF44.toInt()
        private const val COLOR_INFO = 0xFF44AAFF.toInt()
    }

    // BluetoothService.ServiceCallback overrides
    override fun onStatusChanged(status: String) {}
    override fun onDeviceConnected(deviceName: String) {}
    override fun onDeviceDisconnected() {
        runOnUiThread {
            appendOutput("Disconnected from watch", COLOR_ERROR)
        }
    }
    override fun onError(message: String) {}
    override fun onScanResult(devices: List<android.bluetooth.BluetoothDevice>) {}
    override fun onAppListReceived(appsJson: String) {}
    override fun onUploadProgress(progress: Int) {}
    override fun onDownloadProgress(progress: Int, file: java.io.File?) {}
    override fun onFileListReceived(path: String, filesJson: String) {}
}

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
import androidx.activity.enableEdgeToEdge
import java.util.UUID

class TerminalActivity : AppCompatActivity(), BluetoothService.ServiceCallback {

    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    // UI Elements
    private lateinit var scrollView: NestedScrollView
    private lateinit var tvOutput: TextView
    private lateinit var etCommand: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnHistoryUp: android.widget.Button
    private lateinit var btnCtrlC: android.widget.Button
    private lateinit var tvWorkingDir: TextView
    private lateinit var cardPermissionInfo: MaterialCardView
    private lateinit var tvPermissionLevel: TextView
    private lateinit var tvUid: TextView
    private lateinit var tvWarning: TextView

    // State
    private val outputBuilder = SpannableStringBuilder()
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private var permissionInfo: PermissionInfoData? = null
    private var currentWorkingDir = "/sdcard"
    private var currentSessionId: String? = null
    private var isCommandRunning = false

    // Message buffering for ordering
    private val messageBuffer = mutableMapOf<Int, ShellCommandResponse>()
    private var nextExpectedSeq = 0
    private var completionMessage: ShellCommandResponse? = null
    private var removedExecutingLine = false

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

        // Handle Edge-to-Edge and IME insets
        enableEdgeToEdge()
        val rootView = findViewById<android.view.View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                maxOf(systemBars.bottom, ime.bottom)
            )
            insets
        }

        Intent(this, BluetoothService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        appendOutput(getString(R.string.terminal_title), COLOR_INFO)
        appendOutput(getString(R.string.terminal_connecting), COLOR_INFO)
        appendOutput("", COLOR_DEFAULT)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Interrupt any running command before exiting
        if (isCommandRunning) {
            interruptCommand()
        }
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
        btnHistoryUp = findViewById(R.id.btnHistoryUp)
        btnCtrlC = findViewById(R.id.btnCtrlC)
        tvWorkingDir = findViewById(R.id.tvWorkingDir)
        cardPermissionInfo = findViewById(R.id.cardPermissionInfo)
        tvPermissionLevel = findViewById(R.id.tvPermissionLevel)
        tvUid = findViewById(R.id.tvUid)
        tvWarning = findViewById(R.id.tvWarning)

        tvWorkingDir.text = currentWorkingDir
        btnCtrlC.isEnabled = false
    }

    private fun setupListeners() {
        btnSend.setOnClickListener {
            if (!isCommandRunning) {
                executeCommand()
            }
        }

        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && !isCommandRunning) {
                executeCommand()
                true
            } else false
        }

        btnHistoryUp.setOnClickListener {
            navigateHistory()
        }

        btnCtrlC.setOnClickListener {
            interruptCommand()
        }
    }

    private fun navigateHistory() {
        if (commandHistory.isEmpty()) return

        if (historyIndex < 0) {
            historyIndex = commandHistory.size - 1
        } else {
            historyIndex = (historyIndex - 1).coerceAtLeast(0)
        }

        if (historyIndex >= 0 && historyIndex < commandHistory.size) {
            etCommand.setText(commandHistory[historyIndex])
            etCommand.setSelection(etCommand.text.length)
        }
    }

    private fun interruptCommand() {
        if (!isCommandRunning) return

        currentSessionId?.let { sessionId ->
            appendOutput("^C", COLOR_ERROR)
            // Send cancel command to watch
            bluetoothService?.sendMessage(ProtocolHelper.createCancelShellCommand(sessionId))

            isCommandRunning = false
            btnSend.isEnabled = true
            btnCtrlC.isEnabled = false
            currentSessionId = null
        }
    }

    private fun executeCommand() {
        val command = etCommand.text.toString().trim()
        if (command.isEmpty()) return

        // Add to history
        commandHistory.add(command)
        historyIndex = -1 // Reset history navigation

        // Display command with working directory
        appendOutput("$currentWorkingDir $ $command", COLOR_COMMAND)

        // Clear input
        etCommand.text.clear()

        // Track cd command to update working directory
        val cdMatch = Regex("^cd\\s+(.+)$").find(command)
        val targetDir = cdMatch?.groupValues?.get(1)?.trim()

        // Build actual command to execute with working directory context
        val actualCommand = if (targetDir != null) {
            // For cd command, change directory and print new pwd
            "cd ${shellEscape(currentWorkingDir)} && cd $targetDir && pwd"
        } else {
            // For other commands, execute in current working directory
            "cd ${shellEscape(currentWorkingDir)} && $command"
        }

        // Send to watch
        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId
        isCommandRunning = true
        btnSend.isEnabled = false
        btnCtrlC.isEnabled = true

        // Reset buffering state
        messageBuffer.clear()
        nextExpectedSeq = 0
        completionMessage = null
        removedExecutingLine = false

        bluetoothService?.sendMessage(
            ProtocolHelper.createExecuteShellCommand(actualCommand, sessionId)
        )

        // Show loading indicator
        appendOutput(getString(R.string.terminal_executing), COLOR_INFO)
    }

    private fun shellEscape(path: String): String {
        return "'${path.replace("'", "'\\''")}'"
    }

    override fun onShellCommandResponse(response: ShellCommandResponse) {
        runOnUiThread {
            // Buffer the message
            if (response.isComplete) {
                completionMessage = response
            } else {
                messageBuffer[response.sequenceNumber] = response
            }

            // Process messages in order
            processBufferedMessages()
        }
    }

    private fun processBufferedMessages() {
        // Process all consecutive messages starting from nextExpectedSeq
        while (messageBuffer.containsKey(nextExpectedSeq)) {
            val response = messageBuffer.remove(nextExpectedSeq)!!
            nextExpectedSeq++

            // Remove "Executing..." on first output
            if (!removedExecutingLine) {
                if (outputBuilder.toString().endsWith(getString(R.string.terminal_executing) + "\n")) {
                    val lines = outputBuilder.toString().split("\n").dropLast(2)
                    outputBuilder.clear()
                    lines.forEach { line ->
                        if (line.isNotEmpty()) {
                            val color = when {
                                line.contains(" $ ") -> COLOR_COMMAND
                                line.startsWith("RTOSify") || line.contains("level:") -> COLOR_INFO
                                else -> COLOR_DEFAULT
                            }
                            val start = outputBuilder.length
                            outputBuilder.append(line)
                            outputBuilder.append("\n")
                            outputBuilder.setSpan(ForegroundColorSpan(color), start, outputBuilder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    tvOutput.text = outputBuilder
                }
                removedExecutingLine = true
            }

            // Append stdout
            if (response.stdout.isNotEmpty()) {
                appendOutput(response.stdout, COLOR_DEFAULT)
            }

            // Append stderr
            if (response.stderr.isNotEmpty()) {
                appendOutput(response.stderr, COLOR_ERROR)
            }

            // Auto-scroll
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }

        // Check if we can process completion message
        val completion = completionMessage
        if (completion != null && completion.sequenceNumber == nextExpectedSeq) {
            // All messages before completion have been processed
            isCommandRunning = false
            btnSend.isEnabled = true
            btnCtrlC.isEnabled = false
            currentSessionId = null
            completionMessage = null

            // Handle cd command - extract new directory from last output line
            if (commandHistory.isNotEmpty()) {
                val lastCmd = commandHistory.last()
                if (lastCmd.startsWith("cd ") && completion.exitCode == 0) {
                    // Parse last line as the new directory
                    val lines = outputBuilder.toString().split("\n")
                    val newDir = lines.reversed().firstOrNull { it.startsWith("/") }
                    if (newDir != null) {
                        // Remove the pwd output
                        val withoutPwd = lines.dropLast(1).joinToString("\n")
                        outputBuilder.clear()
                        outputBuilder.append(withoutPwd)
                        if (!withoutPwd.endsWith("\n")) outputBuilder.append("\n")
                        tvOutput.text = outputBuilder

                        currentWorkingDir = newDir
                        tvWorkingDir.text = currentWorkingDir
                        appendOutput(getString(R.string.terminal_changed_dir, currentWorkingDir), COLOR_INFO)
                    }
                }
            }

            // Add final status
            val exitCode = completion.exitCode ?: -1
            val statusColor = if (exitCode == 0) COLOR_SUCCESS else COLOR_ERROR
            appendOutput(
                getString(
                    R.string.terminal_exit_code_format,
                    exitCode,
                    completion.executionTimeMs,
                    completion.uid,
                    completion.permissionLevel
                ),
                statusColor
            )
            appendOutput("", COLOR_DEFAULT)

            // Auto-scroll
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }

            // Clear buffers
            messageBuffer.clear()
            nextExpectedSeq = 0
        }
    }

    override fun onPermissionInfoReceived(info: PermissionInfoData) {
        runOnUiThread {
            permissionInfo = info
            updatePermissionUI(info)

            appendOutput(getString(R.string.terminal_perm_level, info.level.uppercase()), COLOR_INFO)
            appendOutput(getString(R.string.terminal_uid_format, info.uid.toString()), COLOR_INFO)
            appendOutput("", COLOR_DEFAULT)
        }
    }

    private fun updatePermissionUI(info: PermissionInfoData) {
        cardPermissionInfo.visibility = View.VISIBLE

        when (info.level) {
            "shizuku" -> {
                tvPermissionLevel.text = getString(R.string.terminal_perm_shizuku)
                tvPermissionLevel.setTextColor(getColor(android.R.color.holo_green_light))
                tvUid.text = getString(R.string.terminal_uid_format, info.uid.toString())
                tvWarning.visibility = View.GONE
            }
            "root" -> {
                tvPermissionLevel.text = getString(R.string.terminal_perm_root)
                tvPermissionLevel.setTextColor(getColor(android.R.color.holo_red_light))
                tvUid.text = getString(R.string.terminal_uid_format, info.uid.toString())
                tvWarning.visibility = View.GONE
            }
            else -> {
                tvPermissionLevel.text = getString(R.string.terminal_perm_limited)
                tvPermissionLevel.setTextColor(getColor(android.R.color.holo_orange_light))
                tvUid.text = getString(R.string.terminal_uid_format, info.uid.toString())
                tvWarning.visibility = View.VISIBLE
                tvWarning.text = getString(R.string.terminal_warning_limited)
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
            appendOutput(getString(R.string.terminal_disconnected), COLOR_ERROR)
        }
    }
    override fun onError(message: String) {}
    override fun onScanResult(devices: List<android.bluetooth.BluetoothDevice>) {}
    override fun onAppListReceived(appsJson: String) {}
    override fun onUploadProgress(progress: Int) {}
    override fun onDownloadProgress(progress: Int, file: java.io.File?) {}
    override fun onFileListReceived(path: String, filesJson: String) {}
    override fun onWatchStatusUpdated(
        batteryLevel: Int,
        isCharging: Boolean,
        wifiSsid: String,
        wifiEnabled: Boolean,
        dndEnabled: Boolean,
        ipAddress: String?,
        wifiState: String?
    ) {}
}

package com.iamadedo.watchapp

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import android.graphics.Color

/**
 * Watch-side Triathlon UI.
 * Displays current sport segment, elapsed time, HR, distance.
 * "Next" button triggers segment transition; "Finish" ends the triathlon.
 * Delegates all logic to TriathlonTracker (bound via BluetoothService).
 */
class TriathlonActivity : Activity() {

    private lateinit var tvSport:   TextView
    private lateinit var tvElapsed: TextView
    private lateinit var tvHr:      TextView
    private lateinit var tvDist:    TextView
    private lateinit var llSegments: LinearLayout
    private lateinit var btnNext:   Button
    private lateinit var btnFinish: Button

    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothService: BluetoothService? = null
    private var startMs = System.currentTimeMillis()

    private val sports = listOf("SWIM", "BIKE", "RUN")
    private var currentSportIndex = 0

    private val sportColors = mapOf(
        "SWIM" to "#4488FF",
        "BIKE" to "#22CC66",
        "RUN"  to "#FF6622"
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            bluetoothService = (b as? BluetoothService.LocalBinder)?.getService()
        }
        override fun onServiceDisconnected(n: ComponentName?) { bluetoothService = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_triathlon)

        tvSport    = findViewById(R.id.tv_triathlon_sport)
        tvElapsed  = findViewById(R.id.tv_triathlon_elapsed)
        tvHr       = findViewById(R.id.tv_triathlon_hr)
        tvDist     = findViewById(R.id.tv_triathlon_dist)
        llSegments = findViewById(R.id.ll_triathlon_segments)
        btnNext    = findViewById(R.id.btn_triathlon_next)
        btnFinish  = findViewById(R.id.btn_triathlon_finish)

        bindService(Intent(this, BluetoothService::class.java), serviceConnection, BIND_AUTO_CREATE)

        buildSegmentDots()
        updateSportDisplay()

        btnNext.setOnClickListener {
            currentSportIndex++
            if (currentSportIndex >= sports.size) {
                finishTriathlon()
            } else {
                startMs = System.currentTimeMillis()
                updateSportDisplay()
                buildSegmentDots()
            }
        }

        btnFinish.setOnClickListener { finishTriathlon() }
        handler.post(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - startMs) / 1000
            val m = elapsed / 60; val s = elapsed % 60
            tvElapsed.text = "%02d:%02d".format(m, s)
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateSportDisplay() {
        val sport = sports.getOrElse(currentSportIndex) { "DONE" }
        tvSport.text = sport
        val colorStr = sportColors[sport] ?: "#FFFFFF"
        tvSport.setTextColor(Color.parseColor(colorStr))
        btnNext.text = if (currentSportIndex < sports.size - 1)
            getString(R.string.triathlon_next_segment) else getString(R.string.triathlon_finish)
    }

    private fun buildSegmentDots() {
        llSegments.removeAllViews()
        sports.forEachIndexed { index, sport ->
            val dot = View(this).apply {
                val size = resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height) / 4
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    it.marginEnd = 8
                }
                val colorStr = sportColors[sport] ?: "#FFFFFF"
                val color = Color.parseColor(colorStr)
                setBackgroundColor(if (index <= currentSportIndex) color else Color.DKGRAY)
            }
            llSegments.addView(dot)
        }
    }

    private fun finishTriathlon() {
        handler.removeCallbacksAndMessages(null)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unbindService(serviceConnection)
        super.onDestroy()
    }
}

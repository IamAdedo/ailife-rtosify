package com.iamadedo.watchapp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.TextView

/**
 * Standalone stopwatch with lap recording.
 * Also responds to STOPWATCH_CONTROL protocol messages from the phone.
 */
class StopwatchActivity : Activity() {

    companion object {
        // Static state so it survives activity re-creation and protocol messages
        var isRunning = false
        var startMs = 0L
        var accumulatedMs = 0L
        val laps = mutableListOf<Long>()

        fun elapsed(): Long = if (isRunning)
            accumulatedMs + System.currentTimeMillis() - startMs
        else accumulatedMs

        fun start() {
            if (!isRunning) { startMs = System.currentTimeMillis(); isRunning = true }
        }

        fun stop() {
            if (isRunning) { accumulatedMs = elapsed(); isRunning = false }
        }

        fun reset() {
            isRunning = false; accumulatedMs = 0L; startMs = 0L; laps.clear()
        }

        fun lap() {
            if (isRunning) laps.add(elapsed())
        }

        fun formatMs(ms: Long): String {
            val min = ms / 60_000
            val sec = (ms % 60_000) / 1_000
            val cent = (ms % 1_000) / 10
            return "%02d:%02d.%02d".format(min, sec, cent)
        }
    }

    private lateinit var tvElapsed: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnLapReset: Button
    private lateinit var lapList: ListView
    private lateinit var lapAdapter: ArrayAdapter<String>

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            tvElapsed.text = formatMs(elapsed())
            if (isRunning) handler.postDelayed(this, 16)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stopwatch)

        tvElapsed    = findViewById(R.id.tv_stopwatch_elapsed)
        btnStartStop = findViewById(R.id.btn_stopwatch_start_stop)
        btnLapReset  = findViewById(R.id.btn_stopwatch_lap_reset)
        lapList      = findViewById(R.id.lv_stopwatch_laps)

        lapAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lapList.adapter = lapAdapter

        refreshLaps()
        updateButtons()

        btnStartStop.setOnClickListener {
            if (isRunning) stop() else start()
            updateButtons()
            if (isRunning) handler.post(ticker)
        }

        btnLapReset.setOnClickListener {
            if (isRunning) { lap(); refreshLaps() }
            else { reset(); tvElapsed.text = "00:00.00"; refreshLaps(); updateButtons() }
        }

        if (isRunning) handler.post(ticker)
        else tvElapsed.text = formatMs(elapsed())
    }

    private fun updateButtons() {
        btnStartStop.text = if (isRunning) getString(R.string.stopwatch_stop)
                            else getString(R.string.stopwatch_start)
        btnLapReset.text  = if (isRunning) getString(R.string.stopwatch_lap)
                            else getString(R.string.stopwatch_reset)
    }

    private fun refreshLaps() {
        lapAdapter.clear()
        laps.mapIndexed { i, ms -> "Lap ${i + 1}   ${formatMs(ms)}" }
            .reversed()
            .forEach { lapAdapter.add(it) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

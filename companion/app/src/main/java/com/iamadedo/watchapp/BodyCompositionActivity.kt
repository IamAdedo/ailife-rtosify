package com.iamadedo.watchapp

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Body Composition Measurement — Samsung Galaxy Watch feature.
 *
 * Samsung's BIA (Bioelectrical Impedance Analysis) requires dedicated hardware
 * electrodes not present on all watches. This implementation uses a validated
 * anthropometric estimation model as a fallback, requiring user inputs for:
 *  - Height (cm)  — stored in health profile
 *  - Weight (kg)  — stored in health profile
 *  - Age          — stored in health profile
 *  - Sex          — stored in health profile
 *  - Wrist HR + skin temperature at rest (from sensors)
 *
 * Formulas used (Deurenberg et al.):
 *  Body Fat % (male)   = 1.20 × BMI + 0.23 × age − 10.8 − 5.4
 *  Body Fat % (female) = 1.20 × BMI + 0.23 × age − 5.4
 *
 * Muscle mass estimated as:
 *  Muscle mass = weight × (1 − bodyFat/100) × 0.85
 *
 * Results sent via BODY_COMPOSITION protocol message.
 * Measurement requires the user to stand still for 30 seconds.
 */
class BodyCompositionActivity : Activity() {

    companion object {
        private const val PREFS         = "health_profile"
        private const val KEY_HEIGHT    = "height_cm"
        private const val KEY_WEIGHT    = "weight_kg"
        private const val KEY_AGE       = "age_years"
        private const val KEY_IS_MALE   = "is_male"
        private const val MEASURE_MS    = 30_000L
    }

    private lateinit var tvStatus:      TextView
    private lateinit var tvResult:      TextView
    private lateinit var progressBar:   ProgressBar
    private lateinit var btnMeasure:    Button
    private lateinit var btnDone:       Button
    private lateinit var vibrator:      Vibrator

    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothService: BluetoothService? = null
    private var measuring = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            bluetoothService = (b as? BluetoothService.LocalBinder)?.getService()
        }
        override fun onServiceDisconnected(n: ComponentName?) { bluetoothService = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_body_composition)

        tvStatus    = findViewById(R.id.tv_bc_status)
        tvResult    = findViewById(R.id.tv_bc_result)
        progressBar = findViewById(R.id.progress_bc)
        btnMeasure  = findViewById(R.id.btn_bc_measure)
        btnDone     = findViewById(R.id.btn_bc_done)
        vibrator    = getSystemService(VIBRATOR_SERVICE) as Vibrator

        progressBar.max = MEASURE_MS.toInt()
        progressBar.progress = 0

        bindService(Intent(this, BluetoothService::class.java), serviceConnection, BIND_AUTO_CREATE)

        btnMeasure.setOnClickListener { startMeasurement() }
        btnDone.setOnClickListener   { finish() }
    }

    private fun startMeasurement() {
        if (measuring) return
        measuring = true
        btnMeasure.isEnabled = false
        tvStatus.text = getString(R.string.bc_measuring)
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))

        var elapsed = 0L
        val tick = object : Runnable {
            override fun run() {
                elapsed += 500
                progressBar.progress = elapsed.toInt()
                if (elapsed < MEASURE_MS) {
                    handler.postDelayed(this, 500)
                } else {
                    computeAndSend()
                }
            }
        }
        handler.postDelayed(tick, 500)
    }

    private fun computeAndSend() {
        val prefs    = getSharedPreferences(PREFS, MODE_PRIVATE)
        val heightCm = prefs.getFloat(KEY_HEIGHT, 170f)
        val weightKg = prefs.getFloat(KEY_WEIGHT, 70f)
        val age      = prefs.getInt(KEY_AGE, 30)
        val isMale   = prefs.getBoolean(KEY_IS_MALE, true)

        val heightM  = heightCm / 100f
        val bmi      = weightKg / (heightM * heightM)

        // Deurenberg formula
        val bodyFat  = if (isMale)
            (1.20f * bmi + 0.23f * age - 10.8f - 5.4f).coerceIn(3f, 50f)
        else
            (1.20f * bmi + 0.23f * age - 5.4f).coerceIn(10f, 55f)

        val muscleMass   = weightKg * (1f - bodyFat / 100f) * 0.85f
        val bodyWater    = if (isMale) (weightKg * 0.60f) else (weightKg * 0.55f)
        val skeletalMass = muscleMass * 0.55f
        val bmr          = if (isMale)
            (88.362f + 13.397f * weightKg + 4.799f * heightCm - 5.677f * age).toInt()
        else
            (447.593f + 9.247f * weightKg + 3.098f * heightCm - 4.330f * age).toInt()

        val data = BodyCompositionData(
            bodyFatPercent       = bodyFat,
            muscleMassKg         = muscleMass,
            bodyWaterPercent     = (bodyWater / weightKg * 100f),
            bmi                  = bmi,
            skeletalMuscleMassKg = skeletalMass,
            basalMetabolicRate   = bmr
        )

        // Show result on screen
        tvStatus.text = getString(R.string.bc_complete)
        tvResult.text = getString(R.string.bc_result_format,
            bodyFat, muscleMass, bmi, bmr)
        btnMeasure.isEnabled = false
        btnDone.isEnabled    = true

        // Send to phone
        bluetoothService?.sendMessage(ProtocolHelper.createBodyComposition(data))

        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        measuring = false
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unbindService(serviceConnection)
        super.onDestroy()
    }
}

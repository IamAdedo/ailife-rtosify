package com.ailife.rtosifycompanion

import android.content.*
import android.os.*
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ailife.rtosifycompanion.ProtocolHelper
import com.ailife.rtosifycompanion.EdgeToEdgeUtils
import com.ailife.rtosifycompanion.BluetoothService

class VideoHelperActivity : AppCompatActivity() {

    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private lateinit var gestureDetector: GestureDetector
    private lateinit var vibrator: Vibrator
    private lateinit var prefs: SharedPreferences

    private lateinit var tutorialLayout: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var gestureArea: View
    private lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar
    private val mainHandler = Handler(Looper.getMainLooper())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_helper)
        
        EdgeToEdgeUtils.applyEdgeToEdge(this, findViewById(R.id.rootLayout))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        tutorialLayout = findViewById(R.id.tutorialLayout)
        tvStatus = findViewById(R.id.tvStatus)
        gestureArea = findViewById(R.id.gestureArea)
        toolbar = findViewById(R.id.toolbar)

        val isTutorialDone = prefs.getBoolean("video_helper_tutorial_done", false)
        if (isTutorialDone) {
            enterStealthMode()
        }

        findViewById<View>(R.id.btnGotIt).setOnClickListener {
            prefs.edit().putBoolean("video_helper_tutorial_done", true).apply()
            enterStealthMode()
        }

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        gestureDetector = GestureDetector(this, GestureListener())
        gestureArea.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        // Bind to service
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun enterStealthMode() {
        tutorialLayout.visibility = View.GONE
        tvStatus.visibility = View.GONE
        toolbar.visibility = View.GONE
        
        // Full screen blackout
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        
        // Low brightness to save power
        val params = window.attributes
        params.screenBrightness = 0.01f
        window.attributes = params
    }

    private fun hapticFeedback() {
        if (vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30)
            }
        }
    }

    private fun sendGesture(type: String) {
        if (isBound && bluetoothService?.isConnected == true) {
            bluetoothService?.sendMessage(ProtocolHelper.createVideoHelperGesture(type))
            hapticFeedback()
        }
    }

    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 50
        private val SWIPE_VELOCITY_THRESHOLD = 50

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null) return false
            val diffY = e2.y - e1.y
            val diffX = e2.x - e1.x
            if (Math.abs(diffY) > Math.abs(diffX)) {
                if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffY < 0) {
                        sendGesture(VideoHelperGestureData.GESTURE_SWIPE_UP)
                    } else {
                        sendGesture(VideoHelperGestureData.GESTURE_SWIPE_DOWN)
                    }
                    return true
                }
            }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            sendGesture(VideoHelperGestureData.GESTURE_SINGLE_TAP)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            sendGesture(VideoHelperGestureData.GESTURE_DOUBLE_TAP)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            hapticFeedback()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}

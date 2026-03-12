package com.ailife.rtosifycompanion

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge

class NavigationOverlayActivity : AppCompatActivity() {

    private lateinit var imgNavImage: ImageView
    private lateinit var txtNavTitle: TextView
    private lateinit var txtNavContent: TextView
    private lateinit var btnClose: ImageButton
    private lateinit var rootLayout: View

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation_overlay)
        
        EdgeToEdgeUtils.applyEdgeToEdge(this, findViewById(R.id.nav_overlay_root))

        initViews()
        handleIntent()
        
        btnClose.setOnClickListener {
            finish()
        }
    }

    private fun initViews() {
        rootLayout = findViewById(R.id.nav_overlay_root)
        imgNavImage = findViewById(R.id.imgNavImage)
        txtNavTitle = findViewById(R.id.txtNavTitle)
        txtNavContent = findViewById(R.id.txtNavContent)
        btnClose = findViewById(R.id.btnClose)
    }

    private fun handleIntent() {
        val imageBase64 = intent.getStringExtra("EXTRA_IMAGE")
        val title = intent.getStringExtra("EXTRA_TITLE")
        val content = intent.getStringExtra("EXTRA_CONTENT")
        val keepScreenOn = intent.getBooleanExtra("EXTRA_KEEP_SCREEN_ON", true)
        val useGreyBackground = intent.getBooleanExtra("EXTRA_USE_GREY_BACKGROUND", false)

        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        if (useGreyBackground) {
            rootLayout.setBackgroundColor(0xFF444444.toInt())
        } else {
            rootLayout.setBackgroundColor(0xFF000000.toInt())
        }

        txtNavTitle.text = title
        txtNavContent.text = content
        
        if (title.isNullOrEmpty()) txtNavTitle.visibility = View.GONE
        if (content.isNullOrEmpty()) txtNavContent.visibility = View.GONE

        if (!imageBase64.isNullOrEmpty()) {
            try {
                val decodedString = Base64.decode(imageBase64, Base64.DEFAULT)
                val decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                imgNavImage.setImageBitmap(decodedByte)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }
}

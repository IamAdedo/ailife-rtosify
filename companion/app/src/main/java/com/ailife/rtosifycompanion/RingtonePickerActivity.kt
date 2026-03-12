package com.ailife.rtosifycompanion

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Activity that hosts the system ringtone picker.
 */
class RingtonePickerActivity : Activity() {

    companion object {
        private const val TAG = "RingtonePickerActivity"
        private const val REQUEST_CODE_RINGTONE_PICKER = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (this is androidx.activity.ComponentActivity || this is androidx.fragment.app.FragmentActivity) {
             // RingtonePickerActivity extends Activity, not ComponentActivity
        }
        // Actually, enableEdgeToEdge is an extension function on ComponentActivity.
        // RingtonePickerActivity extends Activity.
        // I should change it to extend ComponentActivity if I want to use it easily,
        // or just use manual flags. 
        // But since it has no UI of its own, it doesn't matter much.
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "Opening ringtone picker")
        
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Notification Sound")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            
            val currentUri = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("notification_sound_uri", null)
            if (currentUri != null) {
                try {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing existing URI: ${e.message}")
                }
            }
        }
        
        startActivityForResult(intent, REQUEST_CODE_RINGTONE_PICKER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_RINGTONE_PICKER) {
            if (resultCode == RESULT_OK) {
                val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                val ringtoneName = if (uri != null) {
                    try {
                        RingtoneManager.getRingtone(this, uri).getTitle(this)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting ringtone title: ${e.message}")
                        "Selected Sound"
                    }
                } else {
                    "None"
                }
                
                Log.d(TAG, "Ringtone selected: name=$ringtoneName, uri=$uri")
                
                // Send selected ringtone back to phone via BluetoothService
                val responseIntent = Intent(this, BluetoothService::class.java).apply {
                    action = "com.ailife.rtosifycompanion.ACTION_RINGTONE_SELECTED"
                    putExtra("uri", uri?.toString())
                    putExtra("name", ringtoneName)
                }
                startService(responseIntent)
            } else {
                Log.d(TAG, "Ringtone selection cancelled")
            }
        }
        
        finish()
    }
}

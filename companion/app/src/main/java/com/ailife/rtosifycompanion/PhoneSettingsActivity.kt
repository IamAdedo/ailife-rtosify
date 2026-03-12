package com.ailife.rtosifycompanion

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import com.ailife.rtosifycompanion.ui.theme.SmartwatchTheme

class PhoneSettingsActivity : ComponentActivity() {

    companion object {
        const val ACTION_SETTINGS_UPDATE = "com.ailife.rtosifycompanion.ACTION_PHONE_SETTINGS_UPDATE"
        private const val TAG = "PhoneSettingsActivity"
    }

    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true
            bluetoothService?.sendMessage(ProtocolHelper.createRequestPhoneSettings())
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            bluetoothService = null
        }
    }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SETTINGS_UPDATE) {
                val json = intent.getStringExtra("settings_json")
                if (json != null) {
                    try {
                        val gson = com.google.gson.Gson()
                        val data = gson.fromJson(json, PhoneSettingsData::class.java)
                        _settingsState.value = data
                        _isLoading.value = false
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing settings JSON", e)
                    }
                }
            }
        }
    }

    private val _settingsState = mutableStateOf<PhoneSettingsData?>(null)
    private val _isLoading = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        
        // Bind to service
        bindService(Intent(this, BluetoothService::class.java), connection, Context.BIND_AUTO_CREATE)
        
        // Register receiver
        registerReceiver(settingsReceiver, IntentFilter(ACTION_SETTINGS_UPDATE), RECEIVER_NOT_EXPORTED)

        setContent {
            SmartwatchTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhoneSettingsScreen(
                        settings = _settingsState.value,
                        isLoading = _isLoading.value,
                        onRingerModeChange = { mode ->
                            bluetoothService?.sendMessage(ProtocolHelper.createSetRingerMode(mode))
                        },
                        onDndChange = { enabled ->
                            bluetoothService?.sendMessage(ProtocolHelper.createSetDnd(enabled))
                        },
                        onVolumeChange = { streamType, volume ->
                            bluetoothService?.sendMessage(ProtocolHelper.createSetVolume(streamType, volume))
                        },
                        onRefresh = {
                            _isLoading.value = true
                            bluetoothService?.sendMessage(ProtocolHelper.createRequestPhoneSettings())
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        unregisterReceiver(settingsReceiver)
    }
}

@Composable
fun PhoneSettingsScreen(
    settings: PhoneSettingsData?,
    isLoading: Boolean,
    onRingerModeChange: (Int) -> Unit,
    onDndChange: (Boolean) -> Unit,
    onVolumeChange: (Int, Int) -> Unit,
    onRefresh: () -> Unit
) {
    if (isLoading || settings == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.phone_settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Ringer Mode
            item {
                RingerModeCard(
                    currentMode = settings.ringerMode,
                    onModeChange = onRingerModeChange
                )
            }

            // DND Toggle
            item {
                DndCard(
                    enabled = settings.dndEnabled,
                    onToggle = onDndChange
                )
            }

            // Volume Channels
            item {
                Text(
                    text = stringResource(R.string.volume_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }

            items(settings.volumeChannels, key = { it.streamType }) { channel ->
                VolumeSliderCard(channel = channel, onVolumeChange = onVolumeChange)
            }
            
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun RingerModeCard(currentMode: Int, onModeChange: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = stringResource(R.string.ringer_mode_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RingerOption(
                    icon = Icons.Default.Notifications,
                    label = stringResource(R.string.ringer_mode_normal),
                    isSelected = currentMode == 2, // RINGER_MODE_NORMAL
                    onClick = { onModeChange(2) }
                )
                RingerOption(
                    icon = Icons.Default.Vibration,
                    label = stringResource(R.string.ringer_mode_vibrate),
                    isSelected = currentMode == 1, // RINGER_MODE_VIBRATE
                    onClick = { onModeChange(1) }
                )
                RingerOption(
                    icon = Icons.Default.NotificationsOff,
                    label = stringResource(R.string.ringer_mode_silent),
                    isSelected = currentMode == 0, // RINGER_MODE_SILENT
                    onClick = { onModeChange(0) }
                )
            }
        }
    }
}

@Composable
fun RingerOption(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(55.dp)
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(42.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label, 
            style = MaterialTheme.typography.labelSmall, 
            fontSize = 8.sp, 
            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DndCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DoNotDisturbOn,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.dnd_title), 
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Switch(
                checked = enabled, 
                onCheckedChange = onToggle,
                scale = 0.8f
            )
        }
    }
}

@Composable
fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, scale: Float = 1f) {
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.scale(scale),
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

// Scale provided by androidx.compose.ui.draw.scale

@Composable
fun VolumeSliderCard(channel: VolumeChannelData, onVolumeChange: (Int, Int) -> Unit) {
    // Local state for smooth sliding
    var sliderValue by remember(channel.currentVolume) { mutableStateOf(channel.currentVolume.toFloat()) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = channel.name, 
                    style = MaterialTheme.typography.bodySmall, 
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${sliderValue.toInt()}/${channel.maxVolume}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { 
                    sliderValue = it
                },
                onValueChangeFinished = {
                    onVolumeChange(channel.streamType, sliderValue.toInt())
                },
                valueRange = 0f..channel.maxVolume.toFloat(),
                steps = if (channel.maxVolume > 0) channel.maxVolume - 1 else 0
            )
        }
    }
}

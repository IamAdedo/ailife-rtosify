package com.iamadedo.phoneapp

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * RTOSify JSON Protocol V1
 *
 * All messages are JSON objects with the following structure: { "version": 1, "type":
 * "message_type", "timestamp": 1234567890, "data": { ... } }
 */
data class ProtocolMessage(
        val version: Int = 1,
        val type: String,
        val timestamp: Long = System.currentTimeMillis(),
        val data: JsonObject = JsonObject()
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): ProtocolMessage {
            return gson.fromJson(json, ProtocolMessage::class.java)
        }
    }

    fun toJson(): String {
        return gson.toJson(this)
    }

    fun toBytes(): ByteArray {
        return toJson().toByteArray(Charsets.UTF_8)
    }
}

// Message Types
object MessageType {
    const val HEARTBEAT = "heartbeat"
    const val REQUEST_APPS = "request_apps"
    const val RESPONSE_APPS = "response_apps"
    const val NOTIFICATION_POSTED = "notification_posted"
    const val NOTIFICATION_REMOVED = "notification_removed"
    const val DISMISS_NOTIFICATION = "dismiss_notification"
    const val FILE_TRANSFER_START = "file_transfer_start"
    const val FILE_CHUNK = "file_chunk"
    const val FILE_TRANSFER_END = "file_transfer_end"
    const val SHUTDOWN = "shutdown"
    const val REBOOT = "reboot"
    const val LOCK_DEVICE = "lock_device"
    const val FIND_DEVICE = "find_device"
    const val FIND_PHONE = "find_phone"
    const val FIND_DEVICE_LOCATION_UPDATE = "find_device_location_update"
    const val FIND_DEVICE_LOCATION_REQUEST = "find_device_location_request"
    const val STATUS_UPDATE = "status_update"
    const val SET_DND = "set_dnd"
    const val SET_WIFI = "set_wifi"
    const val UNINSTALL_APP = "uninstall_app"
    const val EXECUTE_NOTIFICATION_ACTION = "execute_notification_action"
    const val SEND_NOTIFICATION_REPLY = "send_notification_reply"
    const val MEDIA_CONTROL = "media_control"
    const val MEDIA_STATE_UPDATE = "media_state_update"
    const val REQUEST_MEDIA_STATE = "request_media_state"
    const val CAMERA_START = "camera_start"
    const val CAMERA_STOP = "camera_stop"
    const val CAMERA_FRAME = "camera_frame"
    const val CAMERA_SHUTTER = "camera_shutter"
    const val CAMERA_RECORD_START = "camera_record_start"
    const val CAMERA_RECORD_STOP = "camera_record_stop"
    const val CAMERA_RECORDING_STATUS = "camera_recording_status"
    const val REQUEST_FILE_LIST = "request_file_list"
    const val RESPONSE_FILE_LIST = "response_file_list"
    const val REQUEST_FILE_DOWNLOAD = "request_file_download"
    const val DELETE_FILES = "delete_files" // Batch delete
    const val RENAME_FILE = "rename_file"
    const val MOVE_FILES = "move_files" // Batch move
    const val COPY_FILES = "copy_files" // Batch copy
    const val UPDATE_SETTINGS = "update_settings"
    const val REQUEST_HEALTH_DATA = "request_health_data"
    const val HEALTH_DATA_UPDATE = "health_data_update"
    const val REQUEST_HEALTH_HISTORY = "request_health_history"
    const val RESPONSE_HEALTH_HISTORY = "response_health_history"
    const val START_LIVE_MEASUREMENT = "start_live_measurement"
    const val STOP_LIVE_MEASUREMENT = "stop_live_measurement"
    const val UPDATE_HEALTH_SETTINGS = "update_health_settings"
    const val REQUEST_HEALTH_SETTINGS = "request_health_settings"
    const val RESPONSE_HEALTH_SETTINGS = "response_health_settings"
    const val MAKE_CALL = "make_call"
    const val SYNC_CALENDAR = "sync_calendar"
    const val SYNC_CONTACTS = "sync_contacts"
    const val SET_WATCH_FACE = "set_watch_face"
    const val CREATE_FOLDER = "create_folder"
    const val REQUEST_PREVIEW = "request_preview"
    const val RESPONSE_PREVIEW = "response_preview"
    const val INCOMING_CALL = "incoming_call"
    const val CALL_STATE_CHANGED = "call_state_changed"
    const val REJECT_CALL = "reject_call"
    const val ANSWER_CALL = "answer_call"
    const val REQUEST_WIFI_SCAN = "request_wifi_scan"
    const val WIFI_SCAN_RESULTS = "wifi_scan_results"
    const val CONNECT_WIFI = "connect_wifi"
    const val UPDATE_DND_SETTINGS = "update_dnd_settings"
    const val CLIPBOARD_SYNC = "clipboard_sync"
    const val ENABLE_BT_INTERNET = "enable_bt_internet"
    const val UPDATE_BATTERY_SETTINGS = "update_battery_settings"
    const val REQUEST_BATTERY_STATIC = "request_battery_static"
    const val REQUEST_BATTERY_LIVE = "request_battery_live"
    const val BATTERY_DETAIL_UPDATE = "battery_detail_update"
    const val DEVICE_INFO_UPDATE = "device_info_update"
    const val BATTERY_ALERT = "battery_alert"
    const val REQUEST_ALARMS = "request_alarms"
    const val RESPONSE_ALARMS = "response_alarms"
    const val ADD_ALARM = "add_alarm"
    const val UPDATE_ALARM = "update_alarm"
    const val DELETE_ALARM = "delete_alarm"

    // Screen Mirroring & Remote Control
    const val SCREEN_MIRROR_START = "mirror_start"
    const val SCREEN_MIRROR_STOP = "mirror_stop"
    const val SCREEN_MIRROR_DATA = "mirror_data"
    const val REMOTE_INPUT = "remote_input"
    const val UPDATE_RESOLUTION = "update_resolution"
    const val MIRROR_RES_CHANGE = "mirror_res_change"

    // Phone Battery (Dedicated)
    const val REQUEST_PHONE_BATTERY = "request_phone_battery"
    const val PHONE_BATTERY_UPDATE = "phone_battery_update"
    
    // WiFi Rule Synchronization
    const val UPDATE_WIFI_RULE = "update_wifi_rule"

    // Refactored Polling
    const val REQUEST_WATCH_STATUS = "request_watch_status"
    const val REQUEST_DEVICE_INFO_UPDATE = "request_device_info_update"

    // Terminal / Shell Command Execution
    const val EXECUTE_SHELL_COMMAND = "execute_shell_command"
    const val SHELL_COMMAND_RESPONSE = "shell_command_response"
    const val CANCEL_SHELL_COMMAND = "cancel_shell_command"
    const val REQUEST_PERMISSION_INFO = "request_permission_info"
    const val PERMISSION_INFO_RESPONSE = "permission_info_response"
    
    // WiFi Pairing and Encryption
    const val WIFI_KEY_EXCHANGE = "wifi_key_exchange"
    const val WIFI_KEY_ACK = "wifi_key_ack"
    const val WIFI_TEST_ENCRYPT = "wifi_test_encrypt"
    const val WIFI_TEST_ACK = "wifi_test_ack"
    
    // Sync phone foreground state
    const val SYNC_PHONE_STATE = "sync_phone_state"
    
    // Sharing Synchronization
    const val SHARE_SYNC = "share_sync"

    const val UPDATE_INTERNET_SETTINGS = "update_internet_settings"
    const val SYNC_MAC = "sync_mac"
    const val NAVIGATION_INFO = "navigation_info"

    // Lite Mode Protocol
    const val NOTIFICATION_LITE = "notification_lite"
    const val SET_LITE_MODE = "set_lite_mode"

    // File Observer
    const val FILE_DETECTED = "file_detected"
    
    // Dynamic Island Background
    const val SET_DYNAMIC_ISLAND_BACKGROUND = "set_dynamic_island_background"
    const val SET_DYNAMIC_ISLAND_COLOR = "set_dynamic_island_color"

    // Ringtone Picker
    const val REQUEST_RINGTONE_PICKER = "request_ringtone_picker"
    const val RESPONSE_RINGTONE_PICKER = "response_ringtone_picker"

    // PHONE SETTINGS CONTROL
    const val REQUEST_PHONE_SETTINGS = "request_phone_settings"
    const val PHONE_SETTINGS_UPDATE = "phone_settings_update"
    const val SET_RINGER_MODE = "set_ringer_mode"
    const val SET_VOLUME = "set_volume"
    
    // Video Helper
    const val VIDEO_HELPER_GESTURE = "video_helper_gesture"

    // ── Safety ────────────────────────────────────────────────────────────────
    const val SOS_TRIGGERED = "sos_triggered"
    const val SOS_CANCELLED = "sos_cancelled"
    const val FALL_ALERT = "fall_alert"
    const val FALL_DISMISSED = "fall_dismissed"
    const val CRASH_ALERT = "crash_alert"
    const val HEALTH_ALERT = "health_alert"

    // ── Extended Health ───────────────────────────────────────────────────────
    const val SLEEP_SESSION_START = "sleep_session_start"
    const val SLEEP_SESSION_END = "sleep_session_end"
    const val SLEEP_REPORT = "sleep_report"
    const val ECG_START = "ecg_start"
    const val ECG_DATA = "ecg_data"
    const val TEMP_DATA = "temp_data"
    const val STRESS_LEVEL = "stress_level"
    const val CYCLE_LOG = "cycle_log"
    const val NOISE_ALERT = "noise_alert"
    const val HANDWASH_EVENT = "handwash_event"
    const val MINDFULNESS_SESSION = "mindfulness_session"
    const val VITALS_REQUEST = "vitals_request"
    const val VITALS_RESPONSE = "vitals_response"

    // ── Fitness ───────────────────────────────────────────────────────────────
    const val WORKOUT_START = "workout_start"
    const val WORKOUT_PAUSE = "workout_pause"
    const val WORKOUT_RESUME = "workout_resume"
    const val WORKOUT_END = "workout_end"
    const val WORKOUT_METRIC = "workout_metric"
    const val RUNNING_METRICS = "running_metrics"
    const val SWIM_METRICS = "swim_metrics"
    const val TRAINING_LOAD = "training_load"
    const val CUSTOM_WORKOUT_PUSH = "custom_workout_push"
    const val LOCATION_UPDATE = "location_update"

    // ── Sensors / Adventure ───────────────────────────────────────────────────
    const val COMPASS_DATA = "compass_data"
    const val BARO_DATA = "baro_data"
    const val TIDE_DATA = "tide_data"
    const val ROUTE_CACHE = "route_cache"
    const val WAYPOINT_ADD = "waypoint_add"
    const val DIVE_LOG = "dive_log"

    // ── Communication ─────────────────────────────────────────────────────────
    const val AUDIO_CLIP = "audio_clip"

    // ── Medication ────────────────────────────────────────────────────────────
    const val MED_REMINDER = "med_reminder"
    const val MED_CONFIRM = "med_confirm"

    // ── Huawei-inspired: Advanced Sleep ───────────────────────────────────────
    const val SLEEP_BREATHING_QUALITY = "sleep_breathing_quality"   // respiratory rate + abnormal breathing during sleep
    const val SLEEP_NAP_DETECTED = "sleep_nap_detected"             // daytime nap auto-detection
    const val SLEEP_TIP = "sleep_tip"                               // science-based hygiene tip push
    const val SLEEP_WHITE_NOISE_START = "sleep_white_noise_start"   // start white noise / soundscape on watch
    const val SLEEP_WHITE_NOISE_STOP = "sleep_white_noise_stop"
    const val SMART_ALARM = "smart_alarm"                           // wake in lightest sleep within window

    // ── Huawei-inspired: Cardiac & Respiratory ────────────────────────────────
    const val PREMATURE_BEAT_ALERT = "premature_beat_alert"         // PPG-based premature beat detection
    const val RESPIRATORY_RATE = "respiratory_rate"                 // breaths-per-minute measurement
    const val HR_BROADCAST = "hr_broadcast"                         // broadcast HR to BLE peripherals (e.g. chest strap apps)

    // ── Huawei-inspired: Fitness & VO2Max ────────────────────────────────────
    const val VO2MAX_UPDATE = "vo2max_update"                       // estimated VO2Max from workout data
    const val RUNNING_ABILITY_INDEX = "running_ability_index"       // composite running score
    const val HR_RECOVERY = "hr_recovery"                           // HR drop 1-min post exercise
    const val RECOVERY_TIME = "recovery_time"                       // recommended rest hours
    const val WORKOUT_EVALUATION = "workout_evaluation"             // post-workout data-driven evaluation
    const val AUTO_WORKOUT_DETECT = "auto_workout_detect"           // watch auto-detected a workout type

    // ── Huawei-inspired: Activity & Sedentary ────────────────────────────────
    const val SEDENTARY_REMINDER = "sedentary_reminder"             // prolonged sitting alert
    const val STAND_REMINDER = "stand_reminder"                     // hourly stand/move reminder
    const val ACTIVITY_RINGS_UPDATE = "activity_rings_update"       // Move / Exercise / Stand ring progress
    const val ACTIVITY_GOAL_SET = "activity_goal_set"               // set Move/Exercise/Stand targets

    // ── Huawei-inspired: Weather & Astronomy ─────────────────────────────────
    const val WEATHER_UPDATE = "weather_update"                     // push weather to watch (current + forecast)
    const val MOON_PHASE_UPDATE = "moon_phase_update"               // moonrise, moonset, phase
    const val SUNRISE_SUNSET_UPDATE = "sunrise_sunset_update"

    // ── Huawei-inspired: Utility ──────────────────────────────────────────────
    const val TORCH_CONTROL = "torch_control"                       // turn watch screen white (torch mode)
    const val STOPWATCH_CONTROL = "stopwatch_control"
    const val COUNTDOWN_TIMER_CONTROL = "countdown_timer_control"
    const val LOCK_SCREEN_PASSWORD = "lock_screen_password"         // set/clear watch lock PIN
    const val AUTO_BRIGHTNESS = "auto_brightness"                   // ambient light sensor-driven brightness
    const val BRIGHTNESS_SET = "brightness_set"                     // manual brightness level 1-5

    // ── Huawei-inspired: Family / Health Community ────────────────────────────
    const val FAMILY_MEMBER_HEALTH = "family_member_health"         // share/receive health data with family
    const val FAMILY_HEALTH_REQUEST = "family_health_request"

    // ── Pixel Watch / Fitbit-inspired ─────────────────────────────────────────
    const val DAILY_READINESS_SCORE   = "daily_readiness_score"     // composite readiness 0-100
    const val CARDIO_LOAD_UPDATE      = "cardio_load_update"         // how hard heart worked today
    const val TARGET_LOAD_UPDATE      = "target_load_update"         // recommended training intensity
    const val MORNING_BRIEFING        = "morning_briefing"           // daily summary push to watch
    const val LOSS_OF_PULSE_ALERT     = "loss_of_pulse_alert"        // cardiac arrest detection
    const val AUTO_BEDTIME_START      = "auto_bedtime_start"         // watch detected sleep, mute notifs
    const val AUTO_BEDTIME_END        = "auto_bedtime_end"           // wake detected, restore notifs
    const val WORKOUT_PR_UPDATE       = "workout_pr_update"          // personal record achieved
    const val PACE_TARGET_CUE         = "pace_target_cue"            // haptic/audio cue during run
    const val TRIATHLON_MODE_START    = "triathlon_mode_start"       // multi-sport sequential tracking
    const val TRIATHLON_MODE_SEGMENT  = "triathlon_mode_segment"     // switch sport in triathlon
    const val TRIATHLON_MODE_END      = "triathlon_mode_end"
    const val UWB_UNLOCK_REQUEST      = "uwb_unlock_request"         // UWB proximity unlock phone
    const val MORNING_BRIEF_RESPONSE  = "morning_brief_response"

    // ── Samsung Galaxy Watch-inspired ─────────────────────────────────────────
    const val ENERGY_SCORE_UPDATE     = "energy_score_update"        // Samsung-style daily energy score
    const val BODY_COMPOSITION        = "body_composition"           // BIA: body fat %, muscle mass
    const val AGES_INDEX              = "ages_index"                 // Advanced Glycation End-products index
    const val FTP_ESTIMATE            = "ftp_estimate"               // Functional Threshold Power (cycling)
    const val BLOOD_PRESSURE_TREND    = "blood_pressure_trend"       // PTT-based BP trend
    const val SNORING_DETECTION       = "snoring_detection"          // mic-based snoring event during sleep
    const val SLEEP_ANIMAL            = "sleep_animal"               // gamified sleep archetype
    const val DOUBLE_PINCH_GESTURE    = "double_pinch_gesture"       // Samsung gesture control
    const val EMERGENCY_SIREN         = "emergency_siren"            // 86dB audible emergency siren
    const val RUNNING_COACH_CUE       = "running_coach_cue"          // real-time form correction cue
    const val TRIATHLON_SEGMENT_SWAP  = "triathlon_segment_swap"     // swim→bike→run transition

    // ── Wear OS platform features ─────────────────────────────────────────────
    const val WEAR_OS_TILE_UPDATE     = "wear_os_tile_update"        // push data to custom watch tile
    const val GOOGLE_HOME_CONTROL     = "google_home_control"        // control smart home from watch
    const val OFFLINE_MAPS_SYNC       = "offline_maps_sync"          // sync map tiles to watch storage
    const val BEDTIME_MODE_CONTROL    = "bedtime_mode_control"       // enable/disable bedtime DND
    const val BATTERY_SAVER_MODE      = "battery_saver_mode"         // 15% → auto battery saver
    const val SUGGESTED_REPLY        = "suggested_reply"             // AI-generated quick reply
    const val TRANSCRIPT_REQUEST      = "transcript_request"         // live conversation transcript
    const val TRANSCRIPT_RESULT       = "transcript_result"
}

data class NavigationInfoData(
    val image: String?, // Base64
    val title: String,
    val content: String,
    val keepScreenOn: Boolean,
    val packageName: String,
    val useGreyBackground: Boolean = false
)

data class PreviewRequestData(
    val path: String
)

data class PreviewResponseData(
    val path: String,
    val imageBase64: String?, // For images
    val textContent: String? // For text files (first N chars)
)


data class NotificationLiteData(
    val id: String,
    val title: String,
    val content: String
)

data class FileDetectedData(
    val name: String,
    val path: String, // Phone path
    val size: Long,
    val type: String, // "image", "video", "audio", "text", "other"
    val thumbnail: String?, // Base64 compressed
    val duration: Long?, // Milliseconds
    val timestamp: Long,
    val largeIcon: String?, // Base64 App Icon
    val notificationTitle: String? = null,
    val smallIconType: String? = null,
    val textContent: String? = null
)

data class PhoneBatteryData(val level: Int, val isCharging: Boolean)

// Phone Settings Data
data class VolumeChannelData(
    val streamType: Int, // AudioManager.STREAM_*
    val name: String,
    val currentVolume: Int,
    val maxVolume: Int
)

data class PhoneSettingsData(
    val ringerMode: Int, // AudioManager.RINGER_MODE_*
    val dndEnabled: Boolean,
    val volumeChannels: List<VolumeChannelData>
)

// Sharing sync data
data class ShareData(
    val title: String?,
    val text: String?,
    val url: String?,
    val type: String // mime type
)

// Data classes for specific message types
data class CameraFrameData(val imageBase64: String)

data class MediaControlData(val command: String, val volume: Int? = null, val seekPosition: Long? = null) {
    companion object {
        const val CMD_PLAY = "PLAY"
        const val CMD_PAUSE =
                "PAUSE" // Although often PLAY_PAUSE is used, separate constant for clarity
        const val CMD_PLAY_PAUSE = "PLAY_PAUSE" // Toggle
        const val CMD_NEXT = "NEXT"
        const val CMD_PREVIOUS = "PREVIOUS"
        const val CMD_VOL_UP = "VOL_UP"
        const val CMD_VOL_DOWN = "VOL_DOWN"
        const val CMD_SEEK = "SEEK"
    }
}

data class MediaStateData(
    val isPlaying: Boolean,
    val title: String?,
    val artist: String?,
    val album: String?,
    val duration: Long,        // milliseconds
    val position: Long,        // milliseconds  
    val volume: Int,           // 0-100
    val albumArtBase64: String? // null if no art available
)

data class NotificationActionData(
        val title: String,
        val actionKey: String,
        val isReplyAction: Boolean = false
)

data class NotificationData(
        val packageName: String,
        val title: String,
        val text: String,
        val key: String,
        val appName: String? = null, // Human readable app name
        val largeIcon: String? =
                null, // Base64 encoded - large icon (or app icon as fallback if no others exist)
        val smallIcon: String? = null, // Base64 encoded app icon
        val groupIcon: String? = null, // Base64 encoded group icon (EXTRA_CONVERSATION_ICON)
        val senderIcon: String? = null, // Base64 encoded sender icon (from MessagingStyle)
        val senderName: String? = null, // Name of the sender
        val selfIcon: String? = null, // Base64 encoded self/user icon (for replies)
        val selfName: String? = null, // Display name for self (e.g., "You")
        val bigPicture: String? = null, // Base64 encoded - for BigPictureStyle
        val messages: List<NotificationMessageData> = emptyList(), // History of messages
        val actions: List<NotificationActionData> = emptyList(),
        val isGroupConversation: Boolean = false, // Whether this is a group chat
        val conversationTitle: String? = null, // Explicit conversation title (API 30+)
        val shortcutId: String? = null // For linking to conversation shortcuts (API 30+)
)

data class NotificationMessageData(
        val text: String,
        val timestamp: Long,
        val senderName: String?,
        val senderIcon: String? = null // Base64 encoded sender avatar helper
)

data class StatusUpdateData(
        val battery: Int,
        val charging: Boolean,
        val dnd: Boolean,
        val ipAddress: String? = null,
        val wifiSsid: String? = null,
        val wifiState: String? = null
)

data class FindDeviceLocationData(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val rssi: Int,
        val timestamp: Long
)

data class FileTransferData(
        val name: String,
        val size: Long,
        val checksum: String = "",
        val type: String = TYPE_REGULAR,
        val path: String? = null // Destination path on watch or source path on watch for download
) {
    companion object {
        const val TYPE_REGULAR = "REGULAR"
        const val TYPE_APK = "APK"
        const val TYPE_WATCHFACE = "WATCHFACE"
    }
}

data class FileListData(val path: String, val files: List<FileInfo>)

data class CalendarEvent(
        val title: String,
        val startTime: Long,
        val endTime: Long,
        val location: String? = null,
        val description: String? = null
)

data class Contact(
        val name: String,
        val phoneNumbers: List<String>,
        val emails: List<String>? = null,
        val isStarred: Boolean = false
)

data class FileInfo(
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val lastModified: Long
)

data class SettingsUpdateData(
        val notifyOnDisconnect: Boolean? = null,
        val notificationMirroringEnabled: Boolean? = null,
        val skipScreenOnEnabled: Boolean? = null,
        val forwardOngoingEnabled: Boolean? = null,
        val forwardSilentEnabled: Boolean? = null,
        // Automation settings
        val clipboardSyncEnabled: Boolean? = null,
        val autoWifiEnabled: Boolean? = null,
        val autoDataEnabled: Boolean? = null,
        val autoBtTetherEnabled: Boolean? = null,
        val aggressiveKeepaliveEnabled: Boolean? = null,
        val wakeScreenEnabled: Boolean? = null,
        val wakeScreenDndEnabled: Boolean? = null,
        val vibrateEnabled: Boolean? = null,
        val vibrateInSilentEnabled: Boolean? = null,
        // Dynamic Island settings
        val notificationStyle: String? = null, // "android" or "dynamic_island"
        val dynamicIslandTimeout: Int? = null, // timeout in seconds (2-10)
        val dynamicIslandY: Int? = null,
        val dynamicIslandWidth: Int? = null,
        val dynamicIslandHeight: Int? = null,
        val dynamicIslandHideWhenIdle: Boolean? = null, // DEPRECATED: Use dynamicIslandAutoHideMode
        val dynamicIslandAutoHideMode: Int? = null, // 0=Always Show, 1=Never Show, 2=Hide in Blacklist
        val dynamicIslandBlacklistApps: List<String>? = null, // Package names to hide DI when in foreground
        val dynamicIslandHideWithActiveNotifs: Boolean? = null, // Hide even with active notifications in blacklisted apps
        val dynamicIslandTextMultiplier: Float? = null,
        val dynamicIslandLimitMessageLength: Boolean? = null,
        val dynamicIslandGlobalOpacity: Int? = null,
        // Dynamic Island feature toggles
        val diShowPhoneCalls: Boolean? = null, // Show phone calls in DI (default: true)
        val diShowAlarms: Boolean? = null, // Show alarms in DI (default: true)
        val diShowDisconnect: Boolean? = null, // Show disconnect in DI (default: true)
        val diShowMedia: Boolean? = null, // Show media controls in DI (default: true)
        val forceBtEnabled: Boolean? = null,
        val shareSyncEnabled: Boolean? = null,
        val internetActivationRule: Int? = null,
        val notificationSoundEnabled: Boolean? = null,
        val phoneCallRingingEnabled: Boolean? = null,
        val notificationSoundUri: String? = null,
        val notificationSoundName: String? = null,
        // Full screen notification settings
        val internetSignalingUrl: String? = null,
        val hqLanEnabled: Boolean? = null,
        val dynamicIslandFollowDnd: Boolean? = null,
        val dynamicIslandBlacklistHidePeak: Boolean? = null,
        val inAppReplyDialog: Boolean? = null,
        val fullScreenStackingEnabled: Boolean? = null,
        val fullScreenDismissOnScreenOff: Boolean? = null,
        val vibrationStrength: Int? = null, // 1=Low, 2=Medium, 3=High
        val vibrationPattern: Int? = null, // 0=Default, 1=Double, 2=Long, 3=Heartbeat, 4=Tick, 5=Custom
        val vibrationCustomLength: Int? = null, // ms, used when vibrationPattern is 5
        val fullScreenAppNameSize: Int? = null,
        val fullScreenTitleSize: Int? = null,
        val fullScreenContentSize: Int? = null,
        val fullScreenAutoCloseEnabled: Boolean? = null,
        val fullScreenAutoCloseTimeout: Int? = null,
        val fullScreenKeepScreenOn: Boolean? = null
)

data class HealthDataUpdate(
        val steps: Int,
        val distance: Float, // km
        val calories: Int, // kcal
        val heartRate: Int?, // bpm, null if unavailable
        val heartRateTimestamp: Long?, // millis
        val bloodOxygen: Int?, // percentage, null if unavailable
        val oxygenTimestamp: Long?, // millis
        val errorState: String? = null, // "APP_NOT_INSTALLED", "API_DISABLED", etc.
        val isInstant: Boolean = false
)

data class HealthHistoryRequest(
        val type: String, // "STEP", "HR", "OXYGEN"
        val startTime: Long, // Unix timestamp in seconds
        val endTime: Long // Unix timestamp in seconds
)

data class HealthHistoryResponse(
        val type: String,
        val dataPoints: List<HealthDataPoint>,
        val goal: Int? = null,
        val errorState: String? = null
)

data class HealthDataPoint(
        val timestamp: Long, // Unix timestamp in seconds
        val value: Float
)

data class LiveMeasurementRequest(
        val type: String // "HR" or "OXYGEN"
)

data class HealthSettingsUpdate(
        val stepGoal: Int? = null,
        val backgroundEnabled: Boolean? = null,
        val monitoringTypes: String? = null, // "STEP,HR,OXYGEN"
        val interval: Int? = null, // minutes
        val age: Int? = null,
        val gender: String? = null,
        val height: Int? = null, // cm
        val weight: Float? = null, // kg
        val errorState: String? = null
)

data class FileChunkData(
        val offset: Long,
        val data: String, // Base64 encoded chunk
        val chunkNumber: Int,
        val totalChunks: Int
)

data class AppInfo(
        val name: String,
        val packageName: String,
        val icon: String, // Base64 encoded PNG
        val isSystemApp: Boolean = false
)

data class WifiScanResultData(
        val ssid: String,
        val bssid: String,
        val signalLevel: Int,
        val isSecure: Boolean
)

data class WifiConnectData(val ssid: String, val password: String? = null)

data class DndSettingsData(
        val scheduleEnabled: Boolean,
        val startTime: String? = null, // "HH:mm"
        val endTime: String? = null, // "HH:mm"
        val quickDurationMinutes: Int? = null
)

data class AppUsageData(
        val packageName: String,
        val name: String,
        val usageTimeMillis: Long,
        val icon: String? = null, // Base64
        val batteryPowerMah: Double? = null,
        val drainSpeed: Double? = null // mAh/h
)

data class DeviceInfoData(
        val model: String,
        val androidVersion: String,
        val ramUsage: String,
        val storageUsage: String,
        val processor: String,
        val cpuUsage: Int,
        val btRssi: Int? = null,
        val deviceName: String? = null,
        val companionVersion: String? = null
)

data class BatteryHistoryPoint(
        val timestamp: Long,
        val level: Int,
        val voltage: Int,
        val current: Int,
        val packageName: String? = null
)

data class BatteryDetailData(
        val batteryLevel: Int,
        val isCharging: Boolean,
        val currentNow: Int, // microamperes
        val currentAverage: Int, // microamperes
        val voltage: Int, // millivolts
        val chargeCounter: Int, // microampere-hours
        val energyCounter: Long, // nanowatt-hours
        val capacity: Double, // mAh
        val temperature: Int = 0, // tenths of a degree Celsius
        val timestamp: Long = System.currentTimeMillis(),
        val appUsage: List<AppUsageData> = emptyList(),
        val history: List<BatteryHistoryPoint> = emptyList(),
        val remainingTimeMillis: Long? = null
)

data class BatterySettingsData(
        val notifyFull: Boolean,
        val notifyLow: Boolean,
        val lowThreshold: Int,
        val detailedLogEnabled: Boolean = false
)

data class BatteryAlertData(
        val alertType: String, // "FULL" or "LOW"
        val level: Int
)

data class AlarmData(
        val id: String, // Unique alarm identifier
        val hour: Int, // Hour (0-23)
        val minute: Int, // Minute (0-59)
        val enabled: Boolean, // Whether alarm is active
        val daysOfWeek: List<Int>, // Days of week (1=Monday, 7=Sunday), empty = once
        val label: String = "" // Optional alarm label
)

data class MirrorStartData(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val bitrate: Int = 500000,
        val fps: Int = 10,
        val isRequest: Boolean = false,
        val mode: Int = 1
)

data class MirrorData(
        val data: String, // Base64 encoded frame/chunk
        val isKeyFrame: Boolean = false
)

data class RemoteInputData(
        val action: Int, // MotionEvent action or custom navigation actions
        val x: Float,
        val y: Float
) {
    companion object {
        const val ACTION_NAV_BACK = -10
        const val ACTION_NAV_HOME = -11
        const val ACTION_NAV_RECENTS = -12
        
        const val ACTION_TAP = -20
        const val ACTION_DOUBLE_TAP = -21
        const val ACTION_SCROLL_FORWARD = -22
        const val ACTION_SCROLL_BACKWARD = -23
    }
}

data class ResolutionData(
        val width: Int,
        val height: Int,
        val density: Int,
        val reset: Boolean = false,
        val mode: Int = 1 // 1=Resolution, 2=Aspect
) {
    companion object {
        const val MODE_RESOLUTION = 1
        const val MODE_ASPECT = 2
    }
}

data class VideoHelperGestureData(
    val gestureType: String
) {
    companion object {
        const val GESTURE_SWIPE_UP = "SWIPE_UP"
        const val GESTURE_SWIPE_DOWN = "SWIPE_DOWN"
        const val GESTURE_SINGLE_TAP = "SINGLE_TAP"
        const val GESTURE_DOUBLE_TAP = "DOUBLE_TAP"
    }
}

// Terminal / Shell Command Data Classes
data class ShellCommandRequest(
        val command: String,
        val sessionId: String
)

data class ShellCommandResponse(
        val sessionId: String,
        val exitCode: Int? = null, // null if still running
        val stdout: String = "",
        val stderr: String = "",
        val executionTimeMs: Long = 0,
        val uid: Int,
        val permissionLevel: String, // "shizuku", "root", or "app"
        val isComplete: Boolean = false, // true when command finished
        val isStreaming: Boolean = false, // true if this is partial output
        val sequenceNumber: Int = 0 // line sequence number for ordering
)

data class CancelShellCommandRequest(
        val sessionId: String
)

data class PermissionInfoData(
        val level: String,
        val uid: Int,
        val hasShizuku: Boolean,
        val hasRoot: Boolean
)

// Helper functions to create messages
object ProtocolHelper {
    val gson = Gson()

    fun createHeartbeat(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.HEARTBEAT)
    }

    fun createRequestApps(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_APPS)
    }

    fun createResponseApps(apps: List<AppInfo>): ProtocolMessage {
        val data = JsonObject()
        data.add("apps", gson.toJsonTree(apps))
        return ProtocolMessage(type = MessageType.RESPONSE_APPS, data = data)
    }

    fun createNotificationPosted(notif: NotificationData): ProtocolMessage {
        val data = gson.toJsonTree(notif).asJsonObject
        return ProtocolMessage(type = MessageType.NOTIFICATION_POSTED, data = data)
    }

    fun createNotificationRemoved(key: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("key", key)
        return ProtocolMessage(type = MessageType.NOTIFICATION_REMOVED, data = data)
    }

    fun createDismissNotification(key: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("key", key)
        return ProtocolMessage(type = MessageType.DISMISS_NOTIFICATION, data = data)
    }

    fun createFileTransferStart(fileData: FileTransferData): ProtocolMessage {
        val data = gson.toJsonTree(fileData).asJsonObject
        return ProtocolMessage(type = MessageType.FILE_TRANSFER_START, data = data)
    }

    fun createFileChunk(chunkData: FileChunkData): ProtocolMessage {
        val data = gson.toJsonTree(chunkData).asJsonObject
        return ProtocolMessage(type = MessageType.FILE_CHUNK, data = data)
    }

    fun createFileTransferEnd(success: Boolean = true, error: String = ""): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("success", success)
        data.addProperty("error", error)
        return ProtocolMessage(type = MessageType.FILE_TRANSFER_END, data = data)
    }

    fun createShutdown(force: Boolean = false): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("force", force)
        return ProtocolMessage(type = MessageType.SHUTDOWN, data = data)
    }

    fun createStatusUpdate(status: StatusUpdateData): ProtocolMessage {
        val data = gson.toJsonTree(status).asJsonObject
        return ProtocolMessage(type = MessageType.STATUS_UPDATE, data = data)
    }

    fun createSetDnd(enabled: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("enabled", enabled)
        return ProtocolMessage(type = MessageType.SET_DND, data = data)
    }

    fun createSetWifi(enabled: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("enabled", enabled)
        return ProtocolMessage(type = MessageType.SET_WIFI, data = data)
    }

    fun createUninstallApp(packageName: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("package", packageName)
        return ProtocolMessage(type = MessageType.UNINSTALL_APP, data = data)
    }

    fun createReboot(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REBOOT)
    }

    fun createLockDevice(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.LOCK_DEVICE)
    }

    fun createFindDevice(enabled: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("enabled", enabled)
        return ProtocolMessage(type = MessageType.FIND_DEVICE, data = data)
    }

    fun createFindPhone(enabled: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("enabled", enabled)
        return ProtocolMessage(type = MessageType.FIND_PHONE, data = data)
    }

    fun createFindDeviceLocationUpdate(locationData: FindDeviceLocationData): ProtocolMessage {
        val data = gson.toJsonTree(locationData).asJsonObject
        return ProtocolMessage(type = MessageType.FIND_DEVICE_LOCATION_UPDATE, data = data)
    }

    fun createFindDeviceLocationRequest(enabled: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("enabled", enabled)
        return ProtocolMessage(type = MessageType.FIND_DEVICE_LOCATION_REQUEST, data = data)
    }

    fun createExecuteNotificationAction(notifKey: String, actionKey: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("notifKey", notifKey)
        data.addProperty("actionKey", actionKey)
        return ProtocolMessage(type = MessageType.EXECUTE_NOTIFICATION_ACTION, data = data)
    }

    fun createSendNotificationReply(
            notifKey: String,
            actionKey: String,
            replyText: String
    ): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("notifKey", notifKey)
        data.addProperty("actionKey", actionKey)
        data.addProperty("replyText", replyText)
        return ProtocolMessage(type = MessageType.SEND_NOTIFICATION_REPLY, data = data)
    }

    fun createRequestFileList(path: String = "/"): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("path", path)
        return ProtocolMessage(type = MessageType.REQUEST_FILE_LIST, data = data)
    }

    fun createResponseFileList(path: String, files: List<FileInfo>): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("path", path)
        data.add("files", gson.toJsonTree(files))
        return ProtocolMessage(type = MessageType.RESPONSE_FILE_LIST, data = data)
    }

    fun createRequestFileDownload(path: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("path", path)
        return ProtocolMessage(type = MessageType.REQUEST_FILE_DOWNLOAD, data = data)
    }

    fun createDeleteFiles(paths: List<String>): ProtocolMessage {
        val data = JsonObject()
        val array = com.google.gson.JsonArray()
        paths.forEach { array.add(it) }
        data.add("paths", array)
        return ProtocolMessage(type = MessageType.DELETE_FILES, data = data)
    }

    fun createRenameFile(oldPath: String, newPath: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("oldPath", oldPath)
        data.addProperty("newPath", newPath)
        return ProtocolMessage(type = MessageType.RENAME_FILE, data = data)
    }

    fun createMoveFiles(srcPaths: List<String>, dstPath: String): ProtocolMessage {
        val data = JsonObject()
        val array = com.google.gson.JsonArray()
        srcPaths.forEach { array.add(it) }
        data.add("srcPaths", array)
        data.addProperty("dstPath", dstPath)
        return ProtocolMessage(type = MessageType.MOVE_FILES, data = data)
    }

    fun createCopyFiles(srcPaths: List<String>, dstPath: String): ProtocolMessage {
        val data = JsonObject()
        val array = com.google.gson.JsonArray()
        srcPaths.forEach { array.add(it) }
        data.add("srcPaths", array)
        data.addProperty("dstPath", dstPath)
        return ProtocolMessage(type = MessageType.COPY_FILES, data = data)
    }

    // Singular helpers for compatibility
    fun createDeleteFile(path: String): ProtocolMessage {
        return createDeleteFiles(listOf(path))
    }

    fun createMoveFile(srcPath: String, dstPath: String): ProtocolMessage {
        return createMoveFiles(listOf(srcPath), dstPath)
    }

    fun createRequestPreview(path: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("path", path)
        return ProtocolMessage(type = MessageType.REQUEST_PREVIEW, data = data)
    }


    fun createMediaControl(command: String, volume: Int? = null): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("command", command)
        if (volume != null) data.addProperty("volume", volume)
        return ProtocolMessage(type = MessageType.MEDIA_CONTROL, data = data)
    }

    fun createCameraFrame(imageBase64: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("imageBase64", imageBase64)
        return ProtocolMessage(type = MessageType.CAMERA_FRAME, data = data)
    }

    fun createUpdateSettings(settings: SettingsUpdateData): ProtocolMessage {
        val data = gson.toJsonTree(settings).asJsonObject
        return ProtocolMessage(type = MessageType.UPDATE_SETTINGS, data = data)
    }
    
    fun createSetDynamicIslandBackground(image: String, opacity: Int): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("image", image)
        data.addProperty("opacity", opacity)
        return ProtocolMessage(type = MessageType.SET_DYNAMIC_ISLAND_BACKGROUND, data = data)
    }

    fun createSetDynamicIslandColor(color: Int, opacity: Int): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("color", color)
        data.addProperty("opacity", opacity)
        return ProtocolMessage(type = MessageType.SET_DYNAMIC_ISLAND_COLOR, data = data)
    }

    fun createRequestHealthData(type: String? = null): ProtocolMessage {
        val data = JsonObject()
        if (type != null) data.addProperty("type", type)
        return ProtocolMessage(type = MessageType.REQUEST_HEALTH_DATA, data = data)
    }

    fun createHealthDataUpdate(health: HealthDataUpdate): ProtocolMessage {
        val data = gson.toJsonTree(health).asJsonObject
        return ProtocolMessage(type = MessageType.HEALTH_DATA_UPDATE, data = data)
    }

    fun createRequestHealthHistory(request: HealthHistoryRequest): ProtocolMessage {
        val data = gson.toJsonTree(request).asJsonObject
        return ProtocolMessage(type = MessageType.REQUEST_HEALTH_HISTORY, data = data)
    }

    fun createResponseHealthHistory(response: HealthHistoryResponse): ProtocolMessage {
        val data = gson.toJsonTree(response).asJsonObject
        return ProtocolMessage(type = MessageType.RESPONSE_HEALTH_HISTORY, data = data)
    }

    fun createFileDetected(data: FileDetectedData): ProtocolMessage {
        val jsonData = gson.toJsonTree(data).asJsonObject
        return ProtocolMessage(type = MessageType.FILE_DETECTED, data = jsonData)
    }

    fun createStartLiveMeasurement(request: LiveMeasurementRequest): ProtocolMessage {
        val data = gson.toJsonTree(request).asJsonObject
        return ProtocolMessage(type = MessageType.START_LIVE_MEASUREMENT, data = data)
    }

    fun createStopLiveMeasurement(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.STOP_LIVE_MEASUREMENT)
    }

    fun createUpdateHealthSettings(settings: HealthSettingsUpdate): ProtocolMessage {
        val data = gson.toJsonTree(settings).asJsonObject
        return ProtocolMessage(type = MessageType.UPDATE_HEALTH_SETTINGS, data = data)
    }

    fun createRequestHealthSettings(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_HEALTH_SETTINGS)
    }

    fun createResponseHealthSettings(settings: HealthSettingsUpdate): ProtocolMessage {
        val data = gson.toJsonTree(settings).asJsonObject
        return ProtocolMessage(type = MessageType.RESPONSE_HEALTH_SETTINGS, data = data)
    }

    fun createNotificationLite(id: String, title: String, content: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("id", id)
        data.addProperty("title", title)
        data.addProperty("content", content)
        return ProtocolMessage(type = MessageType.NOTIFICATION_LITE, data = data)
    }

    fun createSetLiteMode(enabled: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("enabled", enabled)
        return ProtocolMessage(type = MessageType.SET_LITE_MODE, data = data)
    }

    fun createMakeCall(phoneNumber: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("phoneNumber", phoneNumber)
        return ProtocolMessage(type = MessageType.MAKE_CALL, data = data)
    }


    fun createResponsePreview(path: String, imageBase64: String?, textContent: String?): ProtocolMessage {
        val data = gson.toJsonTree(PreviewResponseData(path, imageBase64, textContent)).asJsonObject
        return ProtocolMessage(type = MessageType.RESPONSE_PREVIEW, data = data)
    }


    fun createSyncCalendar(events: List<CalendarEvent>): ProtocolMessage {
        val data = JsonObject()
        data.add("events", gson.toJsonTree(events))
        return ProtocolMessage(type = MessageType.SYNC_CALENDAR, data = data)
    }

    fun createSyncContacts(contacts: List<Contact>): ProtocolMessage {
        val data = JsonObject()
        data.add("contacts", gson.toJsonTree(contacts))
        return ProtocolMessage(type = MessageType.SYNC_CONTACTS, data = data)
    }

    fun createSetWatchFace(facePath: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("facePath", facePath)
        return ProtocolMessage(type = MessageType.SET_WATCH_FACE, data = data)
    }

    fun createCreateFolder(path: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("path", path)
        return ProtocolMessage(type = MessageType.CREATE_FOLDER, data = data)
    }



    fun createResponsePreview(path: String, imageBase64: String?): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("path", path)
        if (imageBase64 != null) {
            data.addProperty("imageBase64", imageBase64)
        }
        return ProtocolMessage(type = MessageType.RESPONSE_PREVIEW, data = data)
    }

    fun createNavigationInfo(navInfo: NavigationInfoData): ProtocolMessage {
        val data = gson.toJsonTree(navInfo).asJsonObject
        return ProtocolMessage(type = MessageType.NAVIGATION_INFO, data = data)
    }

    // Helper to extract data from message
    inline fun <reified T> extractData(message: ProtocolMessage): T {
        return gson.fromJson(message.data, T::class.java)
    }

    fun extractStringField(message: ProtocolMessage, field: String): String? {
        return message.data.get(field)?.asString
    }

    fun extractBooleanField(message: ProtocolMessage, field: String): Boolean {
        return message.data.get(field)?.asBoolean ?: false
    }

    fun createRequestPhoneSettings(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_PHONE_SETTINGS)
    }

    fun createPhoneSettingsUpdate(settings: PhoneSettingsData): ProtocolMessage {
        val data = gson.toJsonTree(settings).asJsonObject
        return ProtocolMessage(type = MessageType.PHONE_SETTINGS_UPDATE, data = data)
    }

    fun createSetRingerMode(mode: Int): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("mode", mode)
        return ProtocolMessage(type = MessageType.SET_RINGER_MODE, data = data)
    }

    fun createSetVolume(streamType: Int, volume: Int): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("streamType", streamType)
        data.addProperty("volume", volume)
        return ProtocolMessage(type = MessageType.SET_VOLUME, data = data)
    }

    fun extractIntField(message: ProtocolMessage, field: String): Int {
        return message.data.get(field)?.asInt ?: 0
    }

    fun extractLongField(message: ProtocolMessage, field: String): Long {
        return message.data.get(field)?.asLong ?: 0L
    }

    fun createIncomingCall(number: String, callerId: String?): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("number", number)
        if (callerId != null) data.addProperty("callerId", callerId)
        return ProtocolMessage(type = MessageType.INCOMING_CALL, data = data)
    }

    fun createSyncMac(mac: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("mac", mac)
        return ProtocolMessage(type = MessageType.SYNC_MAC, data = data)
    }

    fun createCallStateChanged(state: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("state", state)
        return ProtocolMessage(type = MessageType.CALL_STATE_CHANGED, data = data)
    }

    fun createRejectCall(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REJECT_CALL)
    }

    fun createAnswerCall(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.ANSWER_CALL)
    }

    fun createRequestWifiScan(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_WIFI_SCAN)
    }

    fun createWifiScanResults(results: List<WifiScanResultData>): ProtocolMessage {
        val data = JsonObject()
        data.add("results", gson.toJsonTree(results))
        return ProtocolMessage(type = MessageType.WIFI_SCAN_RESULTS, data = data)
    }

    fun createConnectWifi(ssid: String, password: String? = null): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("ssid", ssid)
        if (password != null) {
            data.addProperty("password", password)
        }
        return ProtocolMessage(type = MessageType.CONNECT_WIFI, data = data)
    }

    fun createUpdateDndSettings(settings: DndSettingsData): ProtocolMessage {
        val data = gson.toJsonTree(settings).asJsonObject
        return ProtocolMessage(type = MessageType.UPDATE_DND_SETTINGS, data = data)
    }

    fun createClipboardSync(text: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("text", text)
        return ProtocolMessage(type = MessageType.CLIPBOARD_SYNC, data = data)
    }

    fun createEnableBluetoothInternet(enabled: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("enabled", enabled)
        return ProtocolMessage(type = MessageType.ENABLE_BT_INTERNET, data = data)
    }

    fun createBatteryDetailUpdate(detail: BatteryDetailData): ProtocolMessage {
        val data = gson.toJsonTree(detail).asJsonObject
        return ProtocolMessage(type = MessageType.BATTERY_DETAIL_UPDATE, data = data)
    }

    fun createUpdateBatterySettings(settings: BatterySettingsData): ProtocolMessage {
        val data = gson.toJsonTree(settings).asJsonObject
        return ProtocolMessage(type = MessageType.UPDATE_BATTERY_SETTINGS, data = data)
    }

    fun createRequestBatteryStatic(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_BATTERY_STATIC)
    }

    fun createRequestBatteryLive(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_BATTERY_LIVE)
    }

    fun createDeviceInfoUpdate(info: DeviceInfoData): ProtocolMessage {
        val data = gson.toJsonTree(info).asJsonObject
        return ProtocolMessage(type = MessageType.DEVICE_INFO_UPDATE, data = data)
    }

    fun createBatteryAlert(alertType: String, level: Int): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("alertType", alertType)
        data.addProperty("level", level)
        return ProtocolMessage(type = MessageType.BATTERY_ALERT, data = data)
    }

    fun createRequestAlarms(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_ALARMS)
    }

    fun createResponseAlarms(alarms: List<AlarmData>): ProtocolMessage {
        val data = JsonObject()
        data.add("alarms", gson.toJsonTree(alarms))
        return ProtocolMessage(type = MessageType.RESPONSE_ALARMS, data = data)
    }

    fun createAddAlarm(alarm: AlarmData): ProtocolMessage {
        val data = gson.toJsonTree(alarm).asJsonObject
        return ProtocolMessage(type = MessageType.ADD_ALARM, data = data)
    }

    fun createUpdateAlarm(alarm: AlarmData): ProtocolMessage {
        val data = gson.toJsonTree(alarm).asJsonObject
        return ProtocolMessage(type = MessageType.UPDATE_ALARM, data = data)
    }

    fun createDeleteAlarm(alarmId: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("alarmId", alarmId)
        return ProtocolMessage(type = MessageType.DELETE_ALARM, data = data)
    }

    fun createMirrorStart(
            width: Int,
            height: Int,
            dpi: Int,
            isRequest: Boolean = false,
            mode: Int = 1
    ): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("width", width)
        data.addProperty("height", height)
        data.addProperty("dpi", dpi)
        data.addProperty("isRequest", isRequest)
        data.addProperty("mode", mode)
        return ProtocolMessage(type = MessageType.SCREEN_MIRROR_START, data = data)
    }

    fun createMirrorResChange(width: Int, height: Int, density: Int): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("width", width)
        data.addProperty("height", height)
        data.addProperty("density", density)
        return ProtocolMessage(type = MessageType.MIRROR_RES_CHANGE, data = data)
    }

    fun createMirrorStop(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.SCREEN_MIRROR_STOP)
    }

    fun createMirrorData(base64Data: String, isKeyFrame: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("data", base64Data)
        data.addProperty("isKeyFrame", isKeyFrame)
        return ProtocolMessage(type = MessageType.SCREEN_MIRROR_DATA, data = data)
    }

    fun createRemoteInput(action: Int, x: Float, y: Float): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("action", action)
        data.addProperty("x", x)
        data.addProperty("y", y)
        return ProtocolMessage(type = MessageType.REMOTE_INPUT, data = data)
    }

    fun createUpdateResolution(
            width: Int,
            height: Int,
            density: Int,
            reset: Boolean = false,
            mode: Int = 1
    ): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("width", width)
        data.addProperty("height", height)
        data.addProperty("density", density)
        data.addProperty("reset", reset)
        data.addProperty("mode", mode)
        return ProtocolMessage(type = MessageType.UPDATE_RESOLUTION, data = data)
    }

    fun createRequestPhoneBattery(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_PHONE_BATTERY)
    }

    fun createPhoneBatteryUpdate(level: Int, isCharging: Boolean): ProtocolMessage {
        val data = gson.toJsonTree(PhoneBatteryData(level, isCharging)).asJsonObject
        return ProtocolMessage(type = MessageType.PHONE_BATTERY_UPDATE, data = data)
    }

    fun createRequestWatchStatus(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_WATCH_STATUS)
    }

    fun createRequestDeviceInfoUpdate(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_DEVICE_INFO_UPDATE)
    }

    // Terminal / Shell Command Helper Functions
    fun createExecuteShellCommand(command: String, sessionId: String): ProtocolMessage {
        val data = gson.toJsonTree(ShellCommandRequest(command, sessionId)).asJsonObject
        return ProtocolMessage(type = MessageType.EXECUTE_SHELL_COMMAND, data = data)
    }

    fun createShellCommandResponse(response: ShellCommandResponse): ProtocolMessage {
        val data = gson.toJsonTree(response).asJsonObject
        return ProtocolMessage(type = MessageType.SHELL_COMMAND_RESPONSE, data = data)
    }

    fun createCancelShellCommand(sessionId: String): ProtocolMessage {
        val data = gson.toJsonTree(CancelShellCommandRequest(sessionId)).asJsonObject
        return ProtocolMessage(type = MessageType.CANCEL_SHELL_COMMAND, data = data)
    }

    fun createRequestPermissionInfo(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_PERMISSION_INFO)
    }

    fun createPermissionInfoResponse(info: PermissionInfoData): ProtocolMessage {
        val data = gson.toJsonTree(info).asJsonObject
        return ProtocolMessage(type = MessageType.PERMISSION_INFO_RESPONSE, data = data)
    }

    fun createCameraStart(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.CAMERA_START)
    }

    fun createCameraStop(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.CAMERA_STOP)
    }

    fun createCameraShutter(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.CAMERA_SHUTTER)
    }

    fun createCameraRecordStart(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.CAMERA_RECORD_START)
    }

    fun createCameraRecordStop(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.CAMERA_RECORD_STOP)
    }

    fun createCameraRecordingStatus(isRecording: Boolean): ProtocolMessage {
        val data = com.google.gson.JsonObject()
        data.addProperty("isRecording", isRecording)
        return ProtocolMessage(type = MessageType.CAMERA_RECORDING_STATUS, data = data)
    }

    fun createWifiKeyExchange(localMac: String, targetMac: String, encryptionKey: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("deviceMac", localMac)
        data.addProperty("targetMac", targetMac)
        data.addProperty("encryptionKey", encryptionKey)
        return ProtocolMessage(type = MessageType.WIFI_KEY_EXCHANGE, data = data)
    }

    fun createWifiKeyAck(deviceMac: String, success: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("deviceMac", deviceMac)
        data.addProperty("success", success)
        return ProtocolMessage(type = MessageType.WIFI_KEY_ACK, data = data)
    }

    fun createWifiTestEncrypt(message: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("message", message)
        return ProtocolMessage(type = MessageType.WIFI_TEST_ENCRYPT, data = data)
    }

    fun createWifiTestAck(success: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("success", success)
        return ProtocolMessage(type = MessageType.WIFI_TEST_ACK, data = data)
    }

    fun createUpdateWifiRule(rule: Int): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("rule", rule)
        return ProtocolMessage(type = MessageType.UPDATE_WIFI_RULE, data = data)
    }

    fun createUpdateInternetSettings(
        rule: Int, 
        url: String,
        stunUrl: String = "",
        turnUrl: String = "",
        turnUsername: String = "",
        turnPassword: String = ""
    ): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("rule", rule)
        data.addProperty("url", url)
        data.addProperty("stunUrl", stunUrl)
        data.addProperty("turnUrl", turnUrl)
        data.addProperty("turnUsername", turnUsername)
        data.addProperty("turnPassword", turnPassword)
        return ProtocolMessage(type = MessageType.UPDATE_INTERNET_SETTINGS, data = data)
    }

    fun createSyncPhoneState(isForeground: Boolean): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("isForeground", isForeground)
        return ProtocolMessage(type = MessageType.SYNC_PHONE_STATE, data = data)
    }

    fun createShareSync(shareData: ShareData): ProtocolMessage {
        val data = gson.toJsonTree(shareData).asJsonObject
        return ProtocolMessage(type = MessageType.SHARE_SYNC, data = data)
    }

    fun createVideoHelperGesture(gestureType: String, x: Float = 0f, y: Float = 0f): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("gestureType", gestureType)
        data.addProperty("x", x)
        data.addProperty("y", y)
        return ProtocolMessage(type = MessageType.VIDEO_HELPER_GESTURE, data = data)
    }

    // ── Safety ────────────────────────────────────────────────────────────────
    fun createSosTriggered(sosData: SosTriggerData): ProtocolMessage =
        ProtocolMessage(type = MessageType.SOS_TRIGGERED, data = gson.toJsonTree(sosData).asJsonObject)

    fun createSosCancelled(): ProtocolMessage =
        ProtocolMessage(type = MessageType.SOS_CANCELLED)

    fun createFallAlert(fallData: FallAlertData): ProtocolMessage =
        ProtocolMessage(type = MessageType.FALL_ALERT, data = gson.toJsonTree(fallData).asJsonObject)

    fun createFallDismissed(): ProtocolMessage =
        ProtocolMessage(type = MessageType.FALL_DISMISSED)

    fun createCrashAlert(fallData: FallAlertData): ProtocolMessage =
        ProtocolMessage(type = MessageType.CRASH_ALERT, data = gson.toJsonTree(fallData).asJsonObject)

    fun createHealthAlert(alertData: HealthAlertData): ProtocolMessage =
        ProtocolMessage(type = MessageType.HEALTH_ALERT, data = gson.toJsonTree(alertData).asJsonObject)

    // ── Extended Health ───────────────────────────────────────────────────────
    fun createSleepSessionStart(data: SleepSessionData): ProtocolMessage =
        ProtocolMessage(type = MessageType.SLEEP_SESSION_START, data = gson.toJsonTree(data).asJsonObject)

    fun createSleepSessionEnd(data: SleepSessionData): ProtocolMessage =
        ProtocolMessage(type = MessageType.SLEEP_SESSION_END, data = gson.toJsonTree(data).asJsonObject)

    fun createEcgData(ecg: EcgData): ProtocolMessage =
        ProtocolMessage(type = MessageType.ECG_DATA, data = gson.toJsonTree(ecg).asJsonObject)

    fun createTempData(temp: TempData): ProtocolMessage =
        ProtocolMessage(type = MessageType.TEMP_DATA, data = gson.toJsonTree(temp).asJsonObject)

    fun createStressLevel(stress: StressData): ProtocolMessage =
        ProtocolMessage(type = MessageType.STRESS_LEVEL, data = gson.toJsonTree(stress).asJsonObject)

    fun createCycleLog(cycle: CycleLogData): ProtocolMessage =
        ProtocolMessage(type = MessageType.CYCLE_LOG, data = gson.toJsonTree(cycle).asJsonObject)

    fun createNoiseAlert(noise: NoiseAlertData): ProtocolMessage =
        ProtocolMessage(type = MessageType.NOISE_ALERT, data = gson.toJsonTree(noise).asJsonObject)

    fun createHandwashEvent(): ProtocolMessage =
        ProtocolMessage(type = MessageType.HANDWASH_EVENT)

    fun createMindfulnessSession(session: MindfulnessSessionData): ProtocolMessage =
        ProtocolMessage(type = MessageType.MINDFULNESS_SESSION, data = gson.toJsonTree(session).asJsonObject)

    fun createVitalsRequest(): ProtocolMessage =
        ProtocolMessage(type = MessageType.VITALS_REQUEST)

    fun createVitalsResponse(vitals: VitalsResponseData): ProtocolMessage =
        ProtocolMessage(type = MessageType.VITALS_RESPONSE, data = gson.toJsonTree(vitals).asJsonObject)

    // ── Fitness ───────────────────────────────────────────────────────────────
    fun createWorkoutStart(data: WorkoutStartData): ProtocolMessage =
        ProtocolMessage(type = MessageType.WORKOUT_START, data = gson.toJsonTree(data).asJsonObject)

    fun createWorkoutPause(): ProtocolMessage =
        ProtocolMessage(type = MessageType.WORKOUT_PAUSE)

    fun createWorkoutResume(): ProtocolMessage =
        ProtocolMessage(type = MessageType.WORKOUT_RESUME)

    fun createWorkoutEnd(data: WorkoutEndData): ProtocolMessage =
        ProtocolMessage(type = MessageType.WORKOUT_END, data = gson.toJsonTree(data).asJsonObject)

    fun createWorkoutMetric(metric: WorkoutMetricData): ProtocolMessage =
        ProtocolMessage(type = MessageType.WORKOUT_METRIC, data = gson.toJsonTree(metric).asJsonObject)

    fun createRunningMetrics(metrics: RunningMetricsData): ProtocolMessage =
        ProtocolMessage(type = MessageType.RUNNING_METRICS, data = gson.toJsonTree(metrics).asJsonObject)

    fun createSwimMetrics(metrics: SwimMetricsData): ProtocolMessage =
        ProtocolMessage(type = MessageType.SWIM_METRICS, data = gson.toJsonTree(metrics).asJsonObject)

    fun createTrainingLoad(load: TrainingLoadData): ProtocolMessage =
        ProtocolMessage(type = MessageType.TRAINING_LOAD, data = gson.toJsonTree(load).asJsonObject)

    fun createCustomWorkoutPush(workout: CustomWorkoutData): ProtocolMessage =
        ProtocolMessage(type = MessageType.CUSTOM_WORKOUT_PUSH, data = gson.toJsonTree(workout).asJsonObject)

    fun createLocationUpdate(loc: LocationUpdateData): ProtocolMessage =
        ProtocolMessage(type = MessageType.LOCATION_UPDATE, data = gson.toJsonTree(loc).asJsonObject)

    // ── Adventure ─────────────────────────────────────────────────────────────
    fun createCompassData(compass: CompassData): ProtocolMessage =
        ProtocolMessage(type = MessageType.COMPASS_DATA, data = gson.toJsonTree(compass).asJsonObject)

    fun createBaroData(baro: BaroData): ProtocolMessage =
        ProtocolMessage(type = MessageType.BARO_DATA, data = gson.toJsonTree(baro).asJsonObject)

    fun createTideData(tide: TideDataPayload): ProtocolMessage =
        ProtocolMessage(type = MessageType.TIDE_DATA, data = gson.toJsonTree(tide).asJsonObject)

    fun createRouteCache(route: RouteCacheData): ProtocolMessage =
        ProtocolMessage(type = MessageType.ROUTE_CACHE, data = gson.toJsonTree(route).asJsonObject)

    fun createWaypointAdd(wp: WaypointData): ProtocolMessage =
        ProtocolMessage(type = MessageType.WAYPOINT_ADD, data = gson.toJsonTree(wp).asJsonObject)

    fun createDiveLog(dive: DiveLogData): ProtocolMessage =
        ProtocolMessage(type = MessageType.DIVE_LOG, data = gson.toJsonTree(dive).asJsonObject)

    // ── Communication ─────────────────────────────────────────────────────────
    fun createAudioClip(clip: AudioClipData): ProtocolMessage =
        ProtocolMessage(type = MessageType.AUDIO_CLIP, data = gson.toJsonTree(clip).asJsonObject)

    // ── Medication ────────────────────────────────────────────────────────────
    fun createMedReminder(med: MedReminderData): ProtocolMessage =
        ProtocolMessage(type = MessageType.MED_REMINDER, data = gson.toJsonTree(med).asJsonObject)

    fun createMedConfirm(confirm: MedConfirmData): ProtocolMessage =
        ProtocolMessage(type = MessageType.MED_CONFIRM, data = gson.toJsonTree(confirm).asJsonObject)

    // ── Huawei-inspired: Advanced Sleep ───────────────────────────────────────
    fun createSleepBreathingQuality(data: SleepBreathingData): ProtocolMessage =
        ProtocolMessage(type = MessageType.SLEEP_BREATHING_QUALITY, data = gson.toJsonTree(data).asJsonObject)

    fun createSleepNapDetected(data: SleepNapData): ProtocolMessage =
        ProtocolMessage(type = MessageType.SLEEP_NAP_DETECTED, data = gson.toJsonTree(data).asJsonObject)

    fun createSleepTip(tip: String): ProtocolMessage {
        val d = JsonObject(); d.addProperty("tip", tip)
        return ProtocolMessage(type = MessageType.SLEEP_TIP, data = d)
    }

    fun createSleepWhiteNoiseStart(soundType: String): ProtocolMessage {
        val d = JsonObject(); d.addProperty("soundType", soundType)
        return ProtocolMessage(type = MessageType.SLEEP_WHITE_NOISE_START, data = d)
    }

    fun createSleepWhiteNoiseStop(): ProtocolMessage =
        ProtocolMessage(type = MessageType.SLEEP_WHITE_NOISE_STOP)

    fun createSmartAlarm(data: SmartAlarmData): ProtocolMessage =
        ProtocolMessage(type = MessageType.SMART_ALARM, data = gson.toJsonTree(data).asJsonObject)

    // ── Huawei-inspired: Cardiac & Respiratory ────────────────────────────────
    fun createPrematureBeatAlert(data: PrematureBeatData): ProtocolMessage =
        ProtocolMessage(type = MessageType.PREMATURE_BEAT_ALERT, data = gson.toJsonTree(data).asJsonObject)

    fun createRespiratoryRate(data: RespiratoryRateData): ProtocolMessage =
        ProtocolMessage(type = MessageType.RESPIRATORY_RATE, data = gson.toJsonTree(data).asJsonObject)

    fun createHrBroadcast(hrBpm: Int): ProtocolMessage {
        val d = JsonObject(); d.addProperty("hrBpm", hrBpm)
        return ProtocolMessage(type = MessageType.HR_BROADCAST, data = d)
    }

    // ── Huawei-inspired: Fitness & VO2Max ────────────────────────────────────
    fun createVo2MaxUpdate(data: Vo2MaxData): ProtocolMessage =
        ProtocolMessage(type = MessageType.VO2MAX_UPDATE, data = gson.toJsonTree(data).asJsonObject)

    fun createRunningAbilityIndex(data: RunningAbilityData): ProtocolMessage =
        ProtocolMessage(type = MessageType.RUNNING_ABILITY_INDEX, data = gson.toJsonTree(data).asJsonObject)

    fun createHrRecovery(data: HrRecoveryData): ProtocolMessage =
        ProtocolMessage(type = MessageType.HR_RECOVERY, data = gson.toJsonTree(data).asJsonObject)

    fun createRecoveryTime(hours: Int): ProtocolMessage {
        val d = JsonObject(); d.addProperty("recommendedRestHours", hours)
        return ProtocolMessage(type = MessageType.RECOVERY_TIME, data = d)
    }

    fun createWorkoutEvaluation(data: WorkoutEvaluationData): ProtocolMessage =
        ProtocolMessage(type = MessageType.WORKOUT_EVALUATION, data = gson.toJsonTree(data).asJsonObject)

    fun createAutoWorkoutDetect(workoutType: String): ProtocolMessage {
        val d = JsonObject(); d.addProperty("detectedType", workoutType)
        return ProtocolMessage(type = MessageType.AUTO_WORKOUT_DETECT, data = d)
    }

    // ── Huawei-inspired: Activity & Sedentary ────────────────────────────────
    fun createSedentaryReminder(): ProtocolMessage =
        ProtocolMessage(type = MessageType.SEDENTARY_REMINDER)

    fun createStandReminder(): ProtocolMessage =
        ProtocolMessage(type = MessageType.STAND_REMINDER)

    fun createActivityRingsUpdate(data: ActivityRingsData): ProtocolMessage =
        ProtocolMessage(type = MessageType.ACTIVITY_RINGS_UPDATE, data = gson.toJsonTree(data).asJsonObject)

    fun createActivityGoalSet(data: ActivityGoalData): ProtocolMessage =
        ProtocolMessage(type = MessageType.ACTIVITY_GOAL_SET, data = gson.toJsonTree(data).asJsonObject)

    // ── Huawei-inspired: Weather & Astronomy ─────────────────────────────────
    fun createWeatherUpdate(data: WeatherData): ProtocolMessage =
        ProtocolMessage(type = MessageType.WEATHER_UPDATE, data = gson.toJsonTree(data).asJsonObject)

    fun createMoonPhaseUpdate(data: MoonPhaseData): ProtocolMessage =
        ProtocolMessage(type = MessageType.MOON_PHASE_UPDATE, data = gson.toJsonTree(data).asJsonObject)

    fun createSunriseSunsetUpdate(data: SunriseSunsetData): ProtocolMessage =
        ProtocolMessage(type = MessageType.SUNRISE_SUNSET_UPDATE, data = gson.toJsonTree(data).asJsonObject)

    // ── Huawei-inspired: Utility ──────────────────────────────────────────────
    fun createTorchControl(on: Boolean): ProtocolMessage {
        val d = JsonObject(); d.addProperty("on", on)
        return ProtocolMessage(type = MessageType.TORCH_CONTROL, data = d)
    }

    fun createStopwatchControl(action: String): ProtocolMessage {
        val d = JsonObject(); d.addProperty("action", action)   // "START","STOP","RESET","LAP"
        return ProtocolMessage(type = MessageType.STOPWATCH_CONTROL, data = d)
    }

    fun createCountdownTimerControl(action: String, durationSeconds: Int = 0): ProtocolMessage {
        val d = JsonObject()
        d.addProperty("action", action)   // "START","STOP","RESET"
        d.addProperty("durationSeconds", durationSeconds)
        return ProtocolMessage(type = MessageType.COUNTDOWN_TIMER_CONTROL, data = d)
    }

    fun createLockScreenPassword(pin: String): ProtocolMessage {
        val d = JsonObject(); d.addProperty("pin", pin)
        return ProtocolMessage(type = MessageType.LOCK_SCREEN_PASSWORD, data = d)
    }

    fun createAutoBrightness(enabled: Boolean): ProtocolMessage {
        val d = JsonObject(); d.addProperty("enabled", enabled)
        return ProtocolMessage(type = MessageType.AUTO_BRIGHTNESS, data = d)
    }

    fun createBrightnessSet(level: Int): ProtocolMessage {
        val d = JsonObject(); d.addProperty("level", level.coerceIn(1, 5))
        return ProtocolMessage(type = MessageType.BRIGHTNESS_SET, data = d)
    }

    // ── Huawei-inspired: Family Health ───────────────────────────────────────
    fun createFamilyMemberHealth(data: FamilyHealthData): ProtocolMessage =
        ProtocolMessage(type = MessageType.FAMILY_MEMBER_HEALTH, data = gson.toJsonTree(data).asJsonObject)

    fun createFamilyHealthRequest(memberId: String): ProtocolMessage {
        val d = JsonObject(); d.addProperty("memberId", memberId)
        return ProtocolMessage(type = MessageType.FAMILY_HEALTH_REQUEST, data = d)
    }

    // ── Pixel Watch / Fitbit-inspired ─────────────────────────────────────────
    fun createDailyReadinessScore(data: DailyReadinessData): ProtocolMessage =
        ProtocolMessage(type = MessageType.DAILY_READINESS_SCORE, data = gson.toJsonTree(data).asJsonObject)

    fun createCardioLoadUpdate(data: CardioLoadData): ProtocolMessage =
        ProtocolMessage(type = MessageType.CARDIO_LOAD_UPDATE, data = gson.toJsonTree(data).asJsonObject)

    fun createTargetLoadUpdate(data: TargetLoadData): ProtocolMessage =
        ProtocolMessage(type = MessageType.TARGET_LOAD_UPDATE, data = gson.toJsonTree(data).asJsonObject)

    fun createMorningBriefing(data: MorningBriefingData): ProtocolMessage =
        ProtocolMessage(type = MessageType.MORNING_BRIEFING, data = gson.toJsonTree(data).asJsonObject)

    fun createLossOfPulseAlert(): ProtocolMessage =
        ProtocolMessage(type = MessageType.LOSS_OF_PULSE_ALERT)

    fun createAutoBedtimeStart(): ProtocolMessage =
        ProtocolMessage(type = MessageType.AUTO_BEDTIME_START)

    fun createAutoBedtimeEnd(): ProtocolMessage =
        ProtocolMessage(type = MessageType.AUTO_BEDTIME_END)

    fun createWorkoutPrUpdate(data: WorkoutPrData): ProtocolMessage =
        ProtocolMessage(type = MessageType.WORKOUT_PR_UPDATE, data = gson.toJsonTree(data).asJsonObject)

    fun createPaceTargetCue(data: PaceTargetCueData): ProtocolMessage =
        ProtocolMessage(type = MessageType.PACE_TARGET_CUE, data = gson.toJsonTree(data).asJsonObject)

    fun createTriathlonStart(segments: List<String>): ProtocolMessage {
        val d = JsonObject(); d.add("segments", gson.toJsonTree(segments))
        return ProtocolMessage(type = MessageType.TRIATHLON_MODE_START, data = d)
    }

    fun createTriathlonSegment(sport: String, index: Int): ProtocolMessage {
        val d = JsonObject(); d.addProperty("sport", sport); d.addProperty("index", index)
        return ProtocolMessage(type = MessageType.TRIATHLON_MODE_SEGMENT, data = d)
    }

    fun createTriathlonEnd(data: TriathlonResultData): ProtocolMessage =
        ProtocolMessage(type = MessageType.TRIATHLON_MODE_END, data = gson.toJsonTree(data).asJsonObject)

    fun createUwbUnlockRequest(): ProtocolMessage =
        ProtocolMessage(type = MessageType.UWB_UNLOCK_REQUEST)

    // ── Samsung Galaxy Watch-inspired ─────────────────────────────────────────
    fun createEnergyScore(data: EnergyScoreData): ProtocolMessage =
        ProtocolMessage(type = MessageType.ENERGY_SCORE_UPDATE, data = gson.toJsonTree(data).asJsonObject)

    fun createBodyComposition(data: BodyCompositionData): ProtocolMessage =
        ProtocolMessage(type = MessageType.BODY_COMPOSITION, data = gson.toJsonTree(data).asJsonObject)

    fun createAgesIndex(data: AgesIndexData): ProtocolMessage =
        ProtocolMessage(type = MessageType.AGES_INDEX, data = gson.toJsonTree(data).asJsonObject)

    fun createFtpEstimate(data: FtpData): ProtocolMessage =
        ProtocolMessage(type = MessageType.FTP_ESTIMATE, data = gson.toJsonTree(data).asJsonObject)

    fun createBloodPressureTrend(data: BloodPressureTrendData): ProtocolMessage =
        ProtocolMessage(type = MessageType.BLOOD_PRESSURE_TREND, data = gson.toJsonTree(data).asJsonObject)

    fun createSnoringDetection(data: SnoringData): ProtocolMessage =
        ProtocolMessage(type = MessageType.SNORING_DETECTION, data = gson.toJsonTree(data).asJsonObject)

    fun createEmergencySiren(on: Boolean): ProtocolMessage {
        val d = JsonObject(); d.addProperty("on", on)
        return ProtocolMessage(type = MessageType.EMERGENCY_SIREN, data = d)
    }

    fun createRunningCoachCue(data: RunningCoachCueData): ProtocolMessage =
        ProtocolMessage(type = MessageType.RUNNING_COACH_CUE, data = gson.toJsonTree(data).asJsonObject)

    fun createDoublePinchGesture(): ProtocolMessage =
        ProtocolMessage(type = MessageType.DOUBLE_PINCH_GESTURE)

    // ── Wear OS platform ──────────────────────────────────────────────────────
    fun createGoogleHomeControl(data: GoogleHomeControlData): ProtocolMessage =
        ProtocolMessage(type = MessageType.GOOGLE_HOME_CONTROL, data = gson.toJsonTree(data).asJsonObject)

    fun createBedtimeModeControl(enabled: Boolean): ProtocolMessage {
        val d = JsonObject(); d.addProperty("enabled", enabled)
        return ProtocolMessage(type = MessageType.BEDTIME_MODE_CONTROL, data = d)
    }

    fun createBatterySaverMode(enabled: Boolean): ProtocolMessage {
        val d = JsonObject(); d.addProperty("enabled", enabled)
        return ProtocolMessage(type = MessageType.BATTERY_SAVER_MODE, data = d)
    }

    fun createSuggestedReply(data: SuggestedReplyData): ProtocolMessage =
        ProtocolMessage(type = MessageType.SUGGESTED_REPLY, data = gson.toJsonTree(data).asJsonObject)

    fun createTranscriptRequest(): ProtocolMessage =
        ProtocolMessage(type = MessageType.TRANSCRIPT_REQUEST)

    fun createTranscriptResult(text: String): ProtocolMessage {
        val d = JsonObject(); d.addProperty("text", text)
        return ProtocolMessage(type = MessageType.TRANSCRIPT_RESULT, data = d)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// WearOS / Pixel Watch / Samsung Galaxy Watch data classes
// ─────────────────────────────────────────────────────────────────────────────

// Pixel Watch — Readiness & Load
data class DailyReadinessData(
    val score: Int,                     // 0-100
    val sleepScore: Int,
    val restingHrScore: Int,
    val hrvScore: Int,
    val recommendation: String,         // "PUSH", "MODERATE", "RECOVER"
    val date: Long = System.currentTimeMillis()
)

data class CardioLoadData(
    val todayLoad: Float,               // arbitrary units (TRIMP-like)
    val weeklyLoad: Float,
    val acuteLoad: Float,               // 7-day rolling
    val chronicLoad: Float,             // 28-day rolling
    val acuteChronicRatio: Float,       // >1.5 = overtraining risk
    val trend: String                   // "INCREASING","STABLE","DECREASING"
)

data class TargetLoadData(
    val targetMin: Float,
    val targetMax: Float,
    val suggestedWorkout: String,       // "EASY_RUN","TEMPO","INTERVAL","REST","CROSS_TRAIN"
    val reasoning: String
)

data class MorningBriefingData(
    val readinessScore: Int,
    val sleepScore: Int,
    val targetLoad: String,
    val weatherSummary: String,
    val stepGoalProgress: Int,          // percent of weekly goal
    val date: Long = System.currentTimeMillis()
)

data class WorkoutPrData(
    val workoutType: String,
    val metric: String,                 // "PACE_5K","PACE_10K","MAX_SPEED","LONGEST_RUN"
    val previousValue: Float,
    val newValue: Float,
    val unit: String
)

data class PaceTargetCueData(
    val cueType: String,                // "TOO_FAST","ON_PACE","TOO_SLOW","INTERVAL_START","COOLDOWN"
    val targetPaceSecPerKm: Int,
    val currentPaceSecPerKm: Int
)

data class TriathlonResultData(
    val totalDurationMs: Long,
    val segments: List<TriathlonSegmentResult>
)

data class TriathlonSegmentResult(
    val sport: String,                  // "SWIM","BIKE","RUN"
    val durationMs: Long,
    val distanceKm: Float,
    val avgHr: Int?
)

// Samsung-inspired
data class EnergyScoreData(
    val score: Int,                     // 0-100
    val sleepFactor: Int,               // contribution 0-100
    val activityFactor: Int,
    val hrvFactor: Int,
    val spo2Factor: Int,
    val skinTempFactor: Int,
    val date: Long = System.currentTimeMillis()
)

data class BodyCompositionData(
    val bodyFatPercent: Float,
    val muscleMassKg: Float,
    val bodyWaterPercent: Float,
    val bmi: Float,
    val skeletalMuscleMassKg: Float,
    val basalMetabolicRate: Int         // kcal/day
)

data class AgesIndexData(
    val index: Float,                   // 0-100, lower = healthier glycation
    val biologicalAge: Int,             // estimated biological age
    val trend: String                   // "IMPROVING","STABLE","WORSENING"
)

data class FtpData(
    val ftpWatts: Int,                  // Functional Threshold Power
    val estimationMethod: String,       // "HR_BASED","POWER_METER"
    val fitnessCategory: String         // "UNTRAINED","FAIR","MODERATE","GOOD","EXCELLENT"
)

data class BloodPressureTrendData(
    val systolicEstimate: Int,
    val diastolicEstimate: Int,
    val trend: String,                  // "NORMAL","ELEVATED","HIGH_STAGE_1","HIGH_STAGE_2"
    val calibrationRequired: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class SnoringData(
    val events: Int,                    // number of snoring episodes
    val totalMinutes: Float,
    val peakDb: Float,
    val sessionStart: Long,
    val sessionEnd: Long
)

data class RunningCoachCueData(
    val cueType: String,                // "CADENCE_LOW","CADENCE_HIGH","OVERSTRIDING","GOOD_FORM"
    val metric: String,
    val currentValue: Float,
    val targetValue: Float
)

// Wear OS platform
data class GoogleHomeControlData(
    val deviceId: String,
    val deviceName: String,
    val command: String,                // "ON","OFF","DIM","LOCK","UNLOCK","SET_TEMP"
    val value: String = ""
)

data class SuggestedReplyData(
    val notificationKey: String,
    val suggestions: List<String>       // 2-3 short reply options
)


// ─────────────────────────────────────────────────────────────────────────────
// Huawei-inspired data classes
// ─────────────────────────────────────────────────────────────────────────────

// Advanced Sleep
data class SleepBreathingData(
    val respiratoryRateBreathsPerMin: Float,
    val abnormalEvents: Int = 0,        // count of breathing irregularities
    val breathingScore: Int = 0         // 0-100
)

data class SleepNapData(
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int
)

data class SmartAlarmData(
    val targetWakeTime: Long,           // latest acceptable wake time (epoch ms)
    val windowMinutes: Int = 30,        // minutes before targetWakeTime to start watching
    val enabled: Boolean = true
)

// Cardiac & Respiratory
data class PrematureBeatData(
    val eventsPerHour: Float,
    val classification: String,         // "OCCASIONAL", "FREQUENT", "BIGEMINY"
    val timestamp: Long = System.currentTimeMillis()
)

data class RespiratoryRateData(
    val breathsPerMinute: Float,
    val context: String = "REST",       // "REST", "SLEEP", "EXERCISE"
    val timestamp: Long = System.currentTimeMillis()
)

// Fitness & VO2Max
data class Vo2MaxData(
    val vo2MaxMlKgMin: Float,           // mL/kg/min
    val fitnessLevel: String,           // "POOR","FAIR","GOOD","EXCELLENT","SUPERIOR"
    val estimatedFromWorkout: Boolean = true
)

data class RunningAbilityData(
    val index: Int,                     // 0-100 composite score
    val paceProjection5km: Int,         // seconds
    val paceProjection10km: Int
)

data class HrRecoveryData(
    val hrAtPeakBpm: Int,
    val hrAt1MinBpm: Int,
    val dropBpm: Int,
    val classification: String          // "EXCELLENT","GOOD","AVERAGE","POOR"
)

data class WorkoutEvaluationData(
    val workoutType: String,
    val durationMinutes: Int,
    val avgHrBpm: Int,
    val peakHrBpm: Int,
    val zoneDistribution: Map<Int, Int>,    // zone -> minutes
    val vo2MaxEstimate: Float?,
    val recoveryHours: Int,
    val overallScore: Int               // 0-100
)

// Activity Rings
data class ActivityRingsData(
    val moveCaloriesBurned: Int,
    val moveCaloriesGoal: Int,
    val exerciseMinutes: Int,
    val exerciseGoalMinutes: Int,
    val standHours: Int,
    val standGoalHours: Int
)

data class ActivityGoalData(
    val moveCaloriesGoal: Int,
    val exerciseGoalMinutes: Int,
    val standGoalHours: Int
)

// Weather & Astronomy
data class WeatherData(
    val locationName: String,
    val currentTempC: Float,
    val conditionCode: String,          // "SUNNY","CLOUDY","RAIN","SNOW","THUNDERSTORM"
    val humidity: Int,
    val uvIndex: Int,
    val forecast: List<WeatherForecastDay>
)

data class WeatherForecastDay(
    val dayOffset: Int,                 // 0=today, 1=tomorrow …
    val highTempC: Float,
    val lowTempC: Float,
    val conditionCode: String
)

data class MoonPhaseData(
    val moonriseTime: Long,             // epoch ms, 0 if no moonrise today
    val moonsetTime: Long,
    val phasePercent: Float,            // 0=new, 50=half, 100=full
    val phaseName: String               // "NEW","WAXING_CRESCENT","FIRST_QUARTER","WAXING_GIBBOUS","FULL","WANING_GIBBOUS","LAST_QUARTER","WANING_CRESCENT"
)

data class SunriseSunsetData(
    val sunriseTime: Long,
    val sunsetTime: Long,
    val goldenHourMorning: Long,
    val goldenHourEvening: Long
)

// Family Health Community
data class FamilyHealthData(
    val memberId: String,
    val memberName: String,
    val heartRateBpm: Int?,
    val bloodOxygen: Int?,
    val steps: Int,
    val sleepScore: Int?,
    val lastUpdated: Long = System.currentTimeMillis()
)
data class RingtonePickerResponseData(
    val uri: String,
    val name: String
)

// ─────────────────────────────────────────────────────────────────────────────
// Safety data classes
// ─────────────────────────────────────────────────────────────────────────────

data class SosTriggerData(
    val latitude: Double,
    val longitude: Double,
    val message: String = ""
)

data class FallAlertData(
    val confidence: Float,       // 0..1 — certainty of fall detection
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

data class HealthAlertData(
    val alertType: String,       // "IRREGULAR_RHYTHM", "LOW_SPO2", "HIGH_HR", "LOW_HR", "APNEA"
    val value: Float,
    val threshold: Float,
    val timestamp: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────────────────────────
// Extended health data classes
// ─────────────────────────────────────────────────────────────────────────────

data class SleepSessionData(
    val startTime: Long,
    val endTime: Long = 0L,
    val score: Int = 0,            // 0-100
    val deepMinutes: Int = 0,
    val remMinutes: Int = 0,
    val lightMinutes: Int = 0,
    val awakeMinutes: Int = 0,
    val apneaEvents: Int = 0
)

data class EcgData(
    val samples: List<Float>,      // mV readings at ~512Hz
    val durationMs: Long,
    val classification: String = "NORMAL" // "NORMAL", "AFib", "UNCLASSIFIED"
)

data class TempData(
    val wristTempC: Float,
    val ambientTempC: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class StressData(
    val score: Int,                // 0-100 (100 = very stressed)
    val sdnn: Float,               // ms — HRV metric
    val timestamp: Long = System.currentTimeMillis()
)

data class CycleLogData(
    val date: Long,                // epoch millis
    val phase: String,             // "PERIOD", "FOLLICULAR", "OVULATION", "LUTEAL"
    val flowLevel: Int = 0,        // 0=none, 1=light, 2=medium, 3=heavy
    val tempDeviation: Float = 0f  // wrist temp deviation from baseline
)

data class NoiseAlertData(
    val peakDb: Float,
    val durationSeconds: Int
)

data class MindfulnessSessionData(
    val pattern: String,           // "BOX", "4_7_8", "COHERENCE"
    val durationMinutes: Int,
    val hrvBefore: Float = 0f,
    val hrvAfter: Float = 0f
)

data class VitalsResponseData(
    val heartRate: Int?,
    val bloodOxygen: Int?,
    val steps: Int,
    val stressScore: Int?,
    val wristTempC: Float?,
    val timestamp: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────────────────────────
// Fitness data classes
// ─────────────────────────────────────────────────────────────────────────────

data class WorkoutStartData(
    val type: String,              // "RUNNING", "CYCLING", "SWIMMING", "HIKING", "HIIT", "ROWING"
    val startTime: Long = System.currentTimeMillis()
)

data class WorkoutMetricData(
    val elapsedSeconds: Long,
    val heartRate: Int?,
    val heartRateZone: Int?,       // 1-5
    val paceSecondsPerKm: Int?,
    val distanceKm: Float,
    val calories: Int,
    val cadence: Int? = null
)

data class WorkoutEndData(
    val type: String,
    val startTime: Long,
    val endTime: Long,
    val distanceKm: Float,
    val calories: Int,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val gpxData: String? = null    // base64 encoded GPX if available
)

data class RunningMetricsData(
    val cadenceStepsPerMin: Int,
    val strideLengthM: Float,
    val verticalOscillationCm: Float,
    val groundContactMs: Int,
    val runningPowerWatts: Int? = null
)

data class SwimMetricsData(
    val strokeType: String,        // "FREESTYLE", "BACKSTROKE", "BREASTSTROKE", "BUTTERFLY"
    val strokesPerLength: Int,
    val swolfScore: Int,
    val lengthsCompleted: Int
)

data class TrainingLoadData(
    val todayTrimp: Float,
    val weeklyTrimp: Float,
    val recommendation: String     // "REST", "EASY", "MODERATE", "HARD"
)

data class CustomWorkoutData(
    val name: String,
    val intervals: List<WorkoutInterval>
)

data class WorkoutInterval(
    val type: String,              // "WORK", "REST", "WARMUP", "COOLDOWN"
    val durationSeconds: Int,
    val targetHrZone: Int? = null,
    val targetPaceSecondsPerKm: Int? = null
)

data class LocationUpdateData(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Float = 0f,
    val accuracyM: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────────────────────────
// Sensor / adventure data classes
// ─────────────────────────────────────────────────────────────────────────────

data class CompassData(
    val bearingDegrees: Float,
    val timestamp: Long = System.currentTimeMillis()
)

data class BaroData(
    val pressureHpa: Float,
    val altitudeM: Float,
    val elevationGainM: Float = 0f,
    val stormWarning: Boolean = false
)

data class TideDataEntry(
    val timestamp: Long,
    val heightM: Float,
    val type: String               // "HIGH", "LOW"
)

data class TideDataPayload(
    val locationName: String,
    val entries: List<TideDataEntry>,
    val sunrise: Long,
    val sunset: Long
)

data class RouteCacheData(
    val encodedPolyline: String,   // Google-encoded polyline
    val waypointName: String = ""
)

data class WaypointData(
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class DiveLogData(
    val startTime: Long,
    val endTime: Long,
    val maxDepthM: Float,
    val avgDepthM: Float,
    val bottomTimeMinutes: Int,
    val ndlMinutes: Int,
    val waterTempC: Float
)

// ─────────────────────────────────────────────────────────────────────────────
// Communication data classes
// ─────────────────────────────────────────────────────────────────────────────

data class AudioClipData(
    val audioBase64: String,       // Opus-encoded, base64
    val durationMs: Int,
    val senderId: String
)

// ─────────────────────────────────────────────────────────────────────────────
// Medication data classes
// ─────────────────────────────────────────────────────────────────────────────

data class MedReminderData(
    val medId: String,
    val name: String,
    val dosage: String,
    val scheduledTime: Long
)

data class MedConfirmData(
    val medId: String,
    val action: String,            // "TAKEN", "SKIPPED"
    val timestamp: Long = System.currentTimeMillis()
)

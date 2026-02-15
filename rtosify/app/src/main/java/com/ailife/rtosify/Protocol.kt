package com.ailife.rtosify

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
}

data class NavigationInfoData(
    val image: String?, // Base64
    val title: String,
    val content: String,
    val keepScreenOn: Boolean,
    val packageName: String
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
        val vibrationPattern: Int? = null, // 0=Default, 1=Double, 2=Long
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
        val deviceName: String? = null
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
}
data class RingtonePickerResponseData(
    val uri: String,
    val name: String
)

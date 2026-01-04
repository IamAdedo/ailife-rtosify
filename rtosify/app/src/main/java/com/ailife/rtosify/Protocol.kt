package com.ailife.rtosify

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * RTOSify JSON Protocol V1
 *
 * All messages are JSON objects with the following structure:
 * {
 *   "version": 1,
 *   "type": "message_type",
 *   "timestamp": 1234567890,
 *   "data": { ... }
 * }
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
    const val STATUS_UPDATE = "status_update"
    const val SET_DND = "set_dnd"
    const val UNINSTALL_APP = "uninstall_app"
    const val EXECUTE_NOTIFICATION_ACTION = "execute_notification_action"
    const val SEND_NOTIFICATION_REPLY = "send_notification_reply"
    const val MEDIA_CONTROL = "media_control"
    const val CAMERA_START = "camera_start"
    const val CAMERA_STOP = "camera_stop"
    const val CAMERA_FRAME = "camera_frame"
    const val CAMERA_SHUTTER = "camera_shutter"
    const val REQUEST_FILE_LIST = "request_file_list"
    const val RESPONSE_FILE_LIST = "response_file_list"
    const val REQUEST_FILE_DOWNLOAD = "request_file_download"
    const val DELETE_FILE = "delete_file"
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
}

// Data classes for specific message types
data class CameraFrameData(
    val imageBase64: String
)

data class MediaControlData(
    val command: String,
    val volume: Int? = null
) {
    companion object {
        const val CMD_PLAY = "PLAY"
        const val CMD_PAUSE = "PAUSE" // Although often PLAY_PAUSE is used, separate constant for clarity
        const val CMD_PLAY_PAUSE = "PLAY_PAUSE" // Toggle
        const val CMD_NEXT = "NEXT"
        const val CMD_PREVIOUS = "PREVIOUS"
        const val CMD_VOL_UP = "VOL_UP"
        const val CMD_VOL_DOWN = "VOL_DOWN"
    }
}

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
    val appName: String? = null,    // Human readable app name
    val largeIcon: String? = null,  // Base64 encoded - large icon (or app icon as fallback if no others exist)
    val smallIcon: String? = null,  // Base64 encoded app icon
    val groupIcon: String? = null,  // Base64 encoded group icon (EXTRA_CONVERSATION_ICON)
    val senderIcon: String? = null, // Base64 encoded sender icon (from MessagingStyle)
    val senderName: String? = null, // Name of the sender
    val bigPicture: String? = null,  // Base64 encoded - for BigPictureStyle
    val messages: List<NotificationMessageData> = emptyList(), // History of messages
    val actions: List<NotificationActionData> = emptyList()
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
    val wifi: String
)

data class FileTransferData(
    val name: String,
    val size: Long,
    val checksum: String = "",
    val type: String = "REGULAR", // "APK", "REGULAR"
    val path: String? = null      // Destination path on watch or source path on watch for download
)

data class FileListData(
    val path: String,
    val files: List<FileInfo>
)

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
    val emails: List<String>? = null
)

data class FileInfo(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModified: Long
)

data class SettingsUpdateData(
    val notifyOnDisconnect: Boolean? = null
)

data class HealthDataUpdate(
    val steps: Int,
    val distance: Float,              // km
    val calories: Int,                 // kcal
    val heartRate: Int?,               // bpm, null if unavailable
    val heartRateTimestamp: Long?,     // millis
    val bloodOxygen: Int?,             // percentage, null if unavailable
    val oxygenTimestamp: Long?,        // millis
    val errorState: String? = null,    // "APP_NOT_INSTALLED", "API_DISABLED", etc.
    val isInstant: Boolean = false
)

data class HealthHistoryRequest(
    val type: String,              // "STEP", "HR", "OXYGEN"
    val startTime: Long,           // Unix timestamp in seconds
    val endTime: Long              // Unix timestamp in seconds
)

data class HealthHistoryResponse(
    val type: String,
    val dataPoints: List<HealthDataPoint>,
    val goal: Int? = null,
    val errorState: String? = null
)

data class HealthDataPoint(
    val timestamp: Long,           // Unix timestamp in seconds
    val value: Float
)

data class LiveMeasurementRequest(
    val type: String               // "HR" or "OXYGEN"
)

data class HealthSettingsUpdate(
    val stepGoal: Int? = null,
    val backgroundEnabled: Boolean? = null,
    val monitoringTypes: String? = null,  // "STEP,HR,OXYGEN"
    val interval: Int? = null,             // minutes
    val age: Int? = null,
    val gender: String? = null,
    val height: Int? = null,               // cm
    val weight: Float? = null,             // kg
    val errorState: String? = null
)

data class FileChunkData(
    val offset: Long,
    val data: String,  // Base64 encoded chunk
    val chunkNumber: Int,
    val totalChunks: Int
)

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: String  // Base64 encoded PNG
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

    fun createExecuteNotificationAction(notifKey: String, actionKey: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("notifKey", notifKey)
        data.addProperty("actionKey", actionKey)
        return ProtocolMessage(type = MessageType.EXECUTE_NOTIFICATION_ACTION, data = data)
    }

    fun createSendNotificationReply(notifKey: String, actionKey: String, replyText: String): ProtocolMessage {
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

    fun createDeleteFile(path: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("path", path)
        return ProtocolMessage(type = MessageType.DELETE_FILE, data = data)
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

    fun createRequestHealthData(): ProtocolMessage {
        return ProtocolMessage(type = MessageType.REQUEST_HEALTH_DATA)
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

    fun createMakeCall(phoneNumber: String): ProtocolMessage {
        val data = JsonObject()
        data.addProperty("phoneNumber", phoneNumber)
        return ProtocolMessage(type = MessageType.MAKE_CALL, data = data)
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

    fun extractIntField(message: ProtocolMessage, field: String): Int {
        return message.data.get(field)?.asInt ?: 0
    }

    fun extractLongField(message: ProtocolMessage, field: String): Long {
        return message.data.get(field)?.asLong ?: 0L
    }
}

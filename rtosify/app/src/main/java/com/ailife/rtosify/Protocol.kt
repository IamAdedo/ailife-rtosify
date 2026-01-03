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
    val checksum: String = ""
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
